package net.sksat.ashidori.ui

import android.hardware.{Sensor, SensorManager}
import android.view.View
import android.widget.{LinearLayout, TextView}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** What the motion sensors (IMU) are doing now and what gets saved. */
final class SensorPanel(act: MainActivity):
  private given Ctx = act

  private def row(label: String): (View, TextView) =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(LinearLayout.LayoutParams(dp(96), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    val v = text("", 15f)
    v.setLayoutParams(weighted())
    val h = hbox(l, v)
    h.setGravity(android.view.Gravity.TOP)
    h.setPadding(0, dp(3), 0, dp(3))
    (h, v)

  private val (_, accel) = row("加速度")
  private val (shakeRow, shake) = row("直近の動き")
  private val (_, steps) = row("歩数計")
  private val (_, pdr) = row("歩行推定")
  private val (_, raw) = row("生データ")
  private val rows = vbox(Seq(accel, shake, steps, pdr, raw).map(_.getParent.asInstanceOf[View])*)

  private val history = MotionHistoryView(act)
  private def swatch(col: Int, label: String): View =
    val v = View(act)
    v.setBackgroundColor(col)
    v.setLayoutParams(LinearLayout.LayoutParams(dp(10), dp(10)))
    val t = caption(label)
    t.setPadding(dp(4), 0, dp(12), 0)
    hbox(v, t)

  val view: View = card(
    text("IMU", 16f, bold = true),
    caption("加速度・ジャイロ・地磁気・歩数計"),
    spacer(6),
    hbox(
      swatch(0xff2e9d57.toInt, "歩行"),
      swatch(color(attr.tertiary), "その他の動き"),
      swatch(color(attr.surfaceContainerHigh), "静止（センサー停止）")
    ),
    history,
    spacer(6),
    rows,
    spacer(4),
    outlined("この端末のセンサー")(showSensors())
  )

  private def hz(n: Int) = s"${n}Hz"

  /** Last hour of motion summaries for the chart. */
  def loadHistory(): Unit =
    val now = System.currentTimeMillis()
    AshidoriApp.io.execute { () =>
      val d = AshidoriApp.db.motion(now - 3_600_000L, now)
      act.runOnUiThread(() => history.update(d, now - 3_600_000L, now))
    }

  def refresh(): Unit =
    val s = LoggerState.sensors
    val running = LoggerState.running
    accel.setText(
      if !running then "停止"
      else if s.accelHz > 0 then
        s"● ${hz(s.accelHz)}" + (if s.accelBatchMs >= 1000 then s"・${s.accelBatchMs / 1000}秒ごとに一括受信" else "")
      else if s.motionTrigger then "停止（移動検知待ち）"
      else "停止"
    )
    LoggerState.lastWindow match
      case Some(w) if running && System.currentTimeMillis() - w.end < 10 * 60_000 =>
        shakeRow.setVisibility(View.VISIBLE)
        shake.setText(f"揺れ ${w.accelStd}%.2f m/s²・${w.cadencePerMin}%.0f 歩/分")
      case _ => shakeRow.setVisibility(View.GONE)
    steps.setText(if running then f"${LoggerState.stepsSession}%,d 歩" else "—")
    pdr.setText(
      if s.pdr then "● 動作中" else "待機（屋内歩行時のみ）"
    )
    raw.setText(
      if s.imuHz > 0 then f"● 記録中・${hz(s.imuHz)}・${LoggerState.imuSamples}%,d 件"
      else "待機（詳細ログ時のみ）"
    )

  /** The phone's motion sensors: what exists, who made it, how fast it can go. */
  private def showSensors(): Unit =
    val sm = act.getSystemService(classOf[SensorManager])
    val wanted = Seq(
      Sensor.TYPE_ACCELEROMETER -> "加速度",
      Sensor.TYPE_GYROSCOPE -> "ジャイロ",
      Sensor.TYPE_MAGNETIC_FIELD -> "地磁気",
      Sensor.TYPE_PRESSURE -> "気圧",
      Sensor.TYPE_STEP_COUNTER -> "歩数計",
      Sensor.TYPE_STEP_DETECTOR -> "歩行検出",
      Sensor.TYPE_ROTATION_VECTOR -> "回転ベクトル（向き）",
      Sensor.TYPE_SIGNIFICANT_MOTION -> "動き出し検知（割り込み）"
    )
    val body = wanted.map { (t, label) =>
      Option(sm.getDefaultSensor(t, true)).orElse(Option(sm.getDefaultSensor(t))) match
        case Some(s) =>
          val maxHz = if s.getMinDelay > 0 then s" 最大 ${1_000_000 / s.getMinDelay}Hz" else ""
          val fifo = if s.getFifoMaxEventCount > 0 then f"・まとめ受信 ${s.getFifoMaxEventCount}%,d 件" else ""
          val wake = if s.isWakeUpSensor then "・wake-up" else ""
          s"$label\n  ${s.getVendor} ${s.getName}$maxHz$fifo$wake"
        case None => s"$label\n  なし"
    }.mkString("\n\n")
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("この端末のセンサー")
      .setMessage(body)
      .setPositiveButton("閉じる", null)
      .show()
