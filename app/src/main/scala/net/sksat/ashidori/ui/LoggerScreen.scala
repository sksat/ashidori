package net.sksat.ashidori.ui

import android.view.View
import android.widget.{RadioButton, RadioGroup}
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Battery impact estimates shown next to each mode. Rough guidance until
  * measured on real devices.
  */
object ModeInfo:
  val summary: Map[LoggingMode, String] = Map(
    LoggingMode.Eco -> "移動中のみ30秒ごと。静止中は GNSS を止めます",
    LoggingMode.Balanced -> "移動中10秒ごと（乗り物5秒）。ときどき詳細ログ",
    LoggingMode.Precise -> "移動中1〜2秒ごと。詳細ログを頻繁に",
    LoggingMode.Max -> "常時1秒ごとに衛星の生データまで全部記録"
  )
  val battery: Map[LoggingMode, String] = Map(
    LoggingMode.Eco -> "電池: ごくわずか",
    LoggingMode.Balanced -> "電池: 少なめ（おすすめ）",
    LoggingMode.Precise -> "電池: やや多い",
    LoggingMode.Max -> "電池: 多い（短時間向け）"
  )

/** Recording status and controls, opened from the status chip in the top bar. */
final class LoggerScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "ステータス"
  private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")

  private val status = text("", 20f, bold = true)
  private val planText = caption("")
  private val reasons = caption("")
  /** Shown only while stopped. Recording is meant to stay on; the user
    * normally just changes the mode.
    */
  private val start = button("記録を始める")(act.startLogging())
  private val stop = outlined("記録を停止…") {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("記録を停止しますか")
      .setMessage("停止している間の移動や滞在は残りません。電池を節約したいだけなら「省電力」モードがおすすめです。")
      .setNegativeButton("やめる", null)
      // Checking the radio button runs the normal mode-change path.
      .setNeutralButton("省電力にする", (_, _) => modeButtons.find(_._1 == LoggingMode.Eco).foreach(_._2.setChecked(true)))
      .setPositiveButton("停止する", (_, _) => {
        Settings.loggingEnabled = false
        LoggerService.send(act, LoggerService.ActionStop)
      })
      .show()
  }

  private val modes = RadioGroup(act)
  private val modeButtons = LoggingMode.values.toSeq.map { m =>
    val rb = RadioButton(act)
    rb.setId(View.generateViewId())
    rb.setText(s"${m.label}　${ModeInfo.battery(m)}\n${ModeInfo.summary(m)}")
    rb.setMinHeight(dp(56))
    rb.setPadding(dp(8), dp(6), 0, dp(6))
    modes.addView(rb)
    m -> rb
  }
  modes.setOnCheckedChangeListener { (_, id) =>
    modeButtons.find(_._2.getId == id).foreach { (m, _) =>
      if Settings.mode != m then
        Settings.mode = m
        if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
    }
  }

  private val detailText = text("", 14f)
  private val burst = button("今すぐ詳細ログ（5分）", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val files = vbox()

  private val panel = PositioningPanel(act)

  val view: View =
    scroll(
      vbox((Seq(
        card(status, planText, reasons, spacer(8), start)
      ) ++ panel.views ++ Seq(
        card(text("記録モード", 16f, bold = true), modes),
        card(
          text("詳細ログ（GNSS・IMU）", 16f, bold = true),
          caption("衛星の生データと加速度・ジャイロ・地磁気を記録します。"),
          detailText,
          spacer(4),
          burst,
          files
        ),
        padded(stop, 16, 8),
        spacer(24)
      ))*)
    )

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    modeButtons.find(_._1 == Settings.mode).foreach(_._2.setChecked(true))
    refresh()
    panel.loadHistory()
    loadFiles()
  override def onHide(): Unit = unobserve()

  private def loadFiles(): Unit =
    val dir = File(act.getExternalFilesDir(null), "gnss")
    val fs = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(-_.lastModified()).take(8)
    files.removeAllViews()
    for f <- fs do
      val t = text(Instant.ofEpochMilli(f.lastModified()).atZone(AshidoriApp.zone).format(timeFmt), 14f)
      t.setLayoutParams(weighted())
      val row = hbox(t, caption(f"${f.length / 1024.0 / 1024.0}%.1f MB "), outlined("共有")(act.shareFile(f)))
      row.setMinimumHeight(dp(48))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setOnClickListener(_ => showStats(f))
      files.addView(row)

  /** What a raw log contains, summarized from the file itself. */
  private def showStats(f: File): Unit =
    AshidoriApp.io.execute { () =>
      val st = scala.util.Using.resource(scala.io.Source.fromFile(f, "UTF-8"))(src => GnssLogStats.summarize(src.getLines()))
      val consts = st.constellations.toSeq.sortBy(-_._2._1).map { case (c, (n, bands)) =>
        s"$c ${n}機（${bands.toSeq.sorted.mkString("/")}）"
      }
      val body =
        f"""記録 ${st.sessions} 回・計 ${fmtDuration(st.durationMs)}・${st.epochs}%,d エポック・測定値 ${st.measurements}%,d 件
           |平均 C/N0 ${st.meanCn0}%.1f dB-Hz
           |搬送波位相（ADR）有効 ${st.adrValidRatio * 100}%.0f%%
           |測位 ${st.fixes} 回・最良精度 ${if st.bestAccuracyM.isNaN then "-" else f"±${st.bestAccuracyM}%.1fm"}
           |
           |${consts.mkString("\n")}""".stripMargin
      act.runOnUiThread { () =>
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
          .setTitle(f.getName)
          .setMessage(body)
          .setPositiveButton("閉じる", null)
          .setNeutralButton("共有", (_, _) => act.shareFile(f))
          .show()
      }
    }

  def refresh(): Unit =
    val running = LoggerState.running
    status.setText(if running then "● 記録中" else "○ 停止中")
    status.setTextColor(color(if running then attr.primary else attr.onSurfaceVariant))
    start.setVisibility(if running then View.GONE else View.VISIBLE)
    stop.setVisibility(if running then View.VISIBLE else View.GONE)
    LoggerState.plan match
      case Some(p) if running =>
        planText.setText(PositioningPanel.planSentence(p))
        // Why the effective mode differs from the chosen one, if it does.
        reasons.setText(
          if p.effectiveMode != Settings.mode then s"${p.effectiveMode.label}で動作中（${p.reasons.mkString("・")}）"
          else p.reasons.mkString("・")
        )
      case _ =>
        planText.setText(if running then "" else "記録を始めると、ここに現在の動作が表示されます")
        reasons.setText("")

    val now = System.currentTimeMillis()
    detailText.setText(LoggerState.detailFile match
      case Some(_) => s"記録中: ${(now - LoggerState.detailStartedAt) / 1000} 秒・測定値 ${LoggerState.rawMeasurementsSeen} 件"
      case None =>
        val last = Settings.lastDetailStart
        if last == 0 then "まだ記録していません" else s"前回: ${fmtDuration(now - last)}前"
    )

    panel.refresh()
