package net.sksat.ashidori.ui

import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

trait Screen:
  def view: View
  def onShow(): Unit = ()
  def onHide(): Unit = ()
  def onDestroy(): Unit = ()

/** Logger status, mode selection, live GNSS view. */
final class LoggerScreen(act: MainActivity) extends Screen:
  private given Ctx = act

  private val status = text("", 18f, bold = true)
  private val planText = caption("")
  private val reasons = caption("")
  private val toggle = button("記録を開始") {
    if LoggerState.running then LoggerService.send(act, LoggerService.ActionStop)
    else act.ensurePermissions(() => startLogging())
  }
  private val modeButtons: Seq[(LoggingMode, MaterialButton)] =
    LoggingMode.values.toSeq.map { m =>
      m -> outlined(m.label) {
        Settings.mode = m
        if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
        refresh()
      }
    }
  private val modeRow =
    val r = hbox(modeButtons.map(_._2)*)
    modeButtons.foreach { (_, b) =>
      b.setLayoutParams(weighted())
      b.setTextSize(12f)
      b.setPadding(dp(2), 0, dp(2), 0)
    }
    r
  private val modeHint = caption("")

  private val detailText = text("", 14f)
  private val burst = button("今すぐ詳細ログ (5分)", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }

  private val fixText = mono("")
  private val motionText = text("", 14f)
  private val batteryText = text("", 14f)
  private val sky = SkyPlotView(act)
  private val bars = Cn0BarsView(act)
  private val satSummary = caption("")

  val view: View =
    scroll(
      vbox(
        card(status, planText, reasons, spacer(8), toggle),
        card(text("記録モード", 16f, bold = true), spacer(4), modeRow, modeHint),
        card(text("詳細 GNSS ログ", 16f, bold = true), detailText, spacer(4), burst),
        card(text("衛星", 16f, bold = true), sky, bars, satSummary),
        card(text("現在地・センサー", 16f, bold = true), fixText, spacer(4), motionText, batteryText),
        card(
          text("ツール", 16f, bold = true),
          outlined("端末診断") { act.showDiagnostics() },
          outlined("ログファイルを共有") { act.shareLatestGnssLog() }
        ),
        spacer(16)
      )
    )

  private def startLogging(): Unit =
    Settings.loggingEnabled = true
    LoggerService.send(act, LoggerService.ActionStart)

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    refresh()
  override def onHide(): Unit = unobserve()

  private val profileHint = Map(
    LoggingMode.Eco -> "徒歩30秒・乗り物15秒間隔。静止中は GNSS を止める。詳細ログは6時間ごとに30秒",
    LoggingMode.Balanced -> "徒歩10秒・乗り物5秒間隔。静止中は15分ごとに省電力測位。詳細ログは移動中30分ごとに1分",
    LoggingMode.Precise -> "徒歩2秒・乗り物1秒間隔。詳細ログは10分ごとに1分",
    LoggingMode.Max -> "常時1秒間隔で GNSS 生データ（フルトラッキング）と加速度100Hzを記録。電池を大きく消費します"
  )

  def refresh(): Unit =
    val running = LoggerState.running
    status.setText(if running then "● 記録中" else "○ 停止中")
    status.setTextColor(color(if running then attr.primary else attr.onSurfaceVariant))
    toggle.setText(if running then "記録を停止" else "記録を開始")
    LoggerState.plan match
      case Some(p) if running =>
        planText.setText(s"実効モード: ${p.effectiveMode.label}  /  ${LoggerState.motion.label}  /  ${p.describe}")
        reasons.setText(p.reasons.mkString("・"))
      case _ =>
        planText.setText("")
        reasons.setText("")
    val sel = Settings.mode
    modeButtons.foreach { (m, b) =>
      if m == sel then tint(b, color(attr.primaryContainer), color(attr.onPrimaryContainer))
      else tint(b, 0x00000000, color(attr.onSurface))
    }
    modeHint.setText(profileHint(sel))

    val now = System.currentTimeMillis()
    detailText.setText(LoggerState.detailFile match
      case Some(f) =>
        s"記録中: $f\n経過 ${(now - LoggerState.detailStartedAt) / 1000} 秒・測定値 ${LoggerState.rawMeasurementsSeen} 件"
      case None =>
        val last = Settings.lastDetailStart
        if last == 0 then "まだ記録していません"
        else s"前回: ${fmtDuration(now - last)}前"
    )

    val sats = LoggerState.satellites
    sky.update(sats)
    bars.update(sats)
    satSummary.setText(
      if sats.isEmpty then "GNSS 停止中（静止中または記録停止）"
      else
        val used = sats.count(_.usedInFix)
        val byConst = sats.groupBy(_.constellation).toSeq.sortBy(-_._2.size)
          .map((c, ss) => s"${Constellations.name(c)} ${ss.map(_.svid).distinct.size}").mkString(" ")
        val l5 = sats.count(_.band == "L5")
        s"信号 ${sats.size}（測位使用 $used, L5 $l5）  $byConst"
    )

    fixText.setText(LoggerState.lastFix match
      case Some(f) =>
        val age = (now - f.time) / 1000
        f"${f.lat}%.6f, ${f.lon}%.6f  ±${f.accuracy}%.0fm\n" +
          f"${f.provider}  速度 ${if f.hasSpeed then f.speed * 3.6 else Double.NaN}%.1f km/h  ${age}秒前"
      case None => "測位なし"
    )
    motionText.setText(LoggerState.lastWindow match
      case Some(w) => f"状態: ${LoggerState.motion.label}  加速度σ ${w.accelStd}%.2f  歩数(今回) ${LoggerState.stepsSession}"
      case None    => s"状態: ${LoggerState.motion.label}  歩数(今回) ${LoggerState.stepsSession}"
    )
    val d = LoggerState.drainPctPerHour
    batteryText.setText(
      s"電池 ${LoggerState.battery}%" + (if d.isNaN then "（消費率は30分以上の画面オフ後に表示）" else f"  画面オフ時の消費 $d%.1f%%/h")
    )
