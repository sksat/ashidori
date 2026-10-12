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
  override val title = "測位"
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

  private val fixText = mono("")
  private val motionText = text("", 14f)
  private val batteryText = text("", 14f)
  private val gnssLink = outlined("衛星測位の詳細 ›")(act.push(GnssScreen(act)))

  val view: View =
    scroll(
      vbox(
        card(status, planText, reasons, spacer(8), start),
        card(text("記録モード", 16f, bold = true), modes),
        card(
          text("詳細 GNSS ログ", 16f, bold = true),
          caption("衛星の生データ（GnssLogger 形式）を記録します。"),
          detailText,
          spacer(4),
          burst,
          files
        ),
        card(text("現在地", 16f, bold = true), fixText, motionText, batteryText, gnssLink),
        padded(stop, 16, 8),
        spacer(24)
      )
    )

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    modeButtons.find(_._1 == Settings.mode).foreach(_._2.setChecked(true))
    refresh()
    loadFiles()
  override def onHide(): Unit = unobserve()

  private def loadFiles(): Unit =
    val dir = File(act.getExternalFilesDir(null), "gnss")
    val fs = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(-_.lastModified()).take(8)
    files.removeAllViews()
    for f <- fs do
      val t = text(Instant.ofEpochMilli(f.lastModified()).atZone(AshidoriApp.zone).format(timeFmt), 14f)
      t.setLayoutParams(weighted())
      files.addView(hbox(t, caption(f"${f.length / 1024.0 / 1024.0}%.1f MB "), outlined("共有")(act.shareFile(f))))

  def refresh(): Unit =
    val running = LoggerState.running
    status.setText(if running then "● 記録中" else "○ 停止中")
    status.setTextColor(color(if running then attr.primary else attr.onSurfaceVariant))
    start.setVisibility(if running then View.GONE else View.VISIBLE)
    stop.setVisibility(if running then View.VISIBLE else View.GONE)
    LoggerState.plan match
      case Some(p) if running =>
        planText.setText(
          s"${LoggerState.motion.label}・${p.describe}" +
            (if p.effectiveMode != Settings.mode then s"（いまは${p.effectiveMode.label}で動作）" else "")
        )
        reasons.setText(p.reasons.mkString("・"))
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

    fixText.setText(LoggerState.lastFix match
      case Some(f) =>
        f"${f.lat}%.6f, ${f.lon}%.6f ±${f.accuracy}%.0fm\n" +
          f"${f.provider} ${if f.hasSpeed then f.speed * 3.6 else Double.NaN}%.1f km/h ${(now - f.time) / 1000}秒前"
      case None => "測位なし"
    )
    motionText.setText(LoggerState.lastWindow match
      case Some(w) => f"動き: ${LoggerState.motion.label}・加速度σ ${w.accelStd}%.2f・歩数 ${LoggerState.stepsSession}"
      case None    => s"動き: ${LoggerState.motion.label}・歩数 ${LoggerState.stepsSession}"
    )
    val d = LoggerState.drainPctPerHour
    batteryText.setText(s"電池 ${LoggerState.battery}%" + (if d.isNaN then "" else f"・画面オフ時の消費 $d%.1f%%/時"))
