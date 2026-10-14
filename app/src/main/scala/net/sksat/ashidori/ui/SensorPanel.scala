package net.sksat.ashidori.ui

import android.hardware.{Sensor, SensorEvent, SensorEventListener, SensorManager}
import android.os.{Handler, Looper, SystemClock}
import android.view.View
import android.widget.TextView
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*
import SensorUi.*

/** The motion sensors (IMU): 「いま」 what they measure now and how old each
  * value is, then 「履歴」 what they saw over the last hour or day.
  */
final class SensorPanel(act: MainActivity):
  private given Ctx = act
  private val sm = act.getSystemService(classOf[SensorManager])
  private lazy val hasStepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

  // --- いま ---
  private val liveButton =
    val b = outlined("ライブ表示")(toggleLive())
    b.setTextSize(12f)
    b.setMinHeight(dp(32))
    b.setMinimumHeight(dp(32))
    b.setInsetTop(0)
    b.setInsetBottom(0)
    b.setPadding(dp(12), 0, dp(12), 0)
    // As wide as its longer label, so toggling does not move anything.
    b.setMinWidth(Seq("ライブ表示", "ライブ終了").map(s => b.getPaint.measureText(s)).max.ceil.toInt + dp(24 + 4))
    b
  private val state = StateLine()
  private val motionRow = ValueRow("動き")
  private val shakeRow = ValueRow("揺れ")
  private val stepsRow = ValueRow("歩数")
  private val gyroRow = ValueRow("ジャイロ")
  private val spark = LiveAccelView(act)
  /** Under the sparkline: what it shows, or that it is live and for how long. */
  private val sparkNote = tabular(oneLine(caption("")))
  /** The sensors used only in special cases, as one quiet line. */
  private val extras =
    val t = tabular(oneLine(caption("")))
    t.setPadding(0, dp(4), 0, 0)
    t

  // --- 履歴 ---
  private val history = MotionHistoryView(act)
  private val summary = tabular(oneLine(caption("")))
  private val rangeGroup = rangeToggle(SensorPanel.day) { d =>
    SensorPanel.day = d
    loadHistory()
  }
  private val legend = vbox(
    hbox(
      legendItem(Swatch(MotionClass.Walking), "歩行"),
      legendItem(Swatch(MotionClass.Vehicle), "乗り物"),
      legendItem(Swatch(MotionClass.Other), "その他")
    ),
    hbox(
      legendItem(Swatch(MotionClass.Still), "静止（計測中）"),
      legendItem(Swatch(MotionClass.Off), "センサー停止")
    )
  )

  val view: View = card(
    text("IMU", 16f, bold = true),
    caption("加速度・歩数計・ジャイロ"),
    section("いま", liveButton),
    state.view,
    motionRow.view,
    shakeRow.view,
    stepsRow.view,
    gyroRow.view,
    spacer(6),
    spark,
    sparkNote,
    extras,
    section("履歴", rangeGroup),
    legend,
    spacer(4),
    history,
    summary,
    spacer(4),
    outlined("この端末のセンサー")(showSensors())
  )

  // --- live display: the accelerometer at UI rate, unbatched, only while
  // asked for and on screen; never longer than LiveMaxMs ---
  private val main = Handler(Looper.getMainLooper)
  private var liveUntil = 0L
  private val liveListener = new SensorEventListener:
    override def onAccuracyChanged(s: Sensor, a: Int): Unit = ()
    override def onSensorChanged(e: SensorEvent): Unit =
      val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
      val (x, y, z) = (e.values(0), e.values(1), e.values(2))
      spark.add(wall, (math.sqrt(x * x + y * y + z * z) - MotionAccumulator.G).toFloat)

  private def live: Boolean = liveUntil > 0

  private def toggleLive(): Unit = if live then stopLive() else startLive()

  private def startLive(): Unit =
    // The non-wake-up accelerometer: it never wakes the phone by itself.
    Option(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).foreach { s =>
      spark.clear()
      if sm.registerListener(liveListener, s, SensorManager.SENSOR_DELAY_UI, 0) then
        liveUntil = System.currentTimeMillis() + SensorPanel.LiveMaxMs
        spark.live = true
        liveButton.setText("ライブ終了")
        refresh()
    }

  def stopLive(): Unit =
    if live then
      sm.unregisterListener(liveListener)
      liveUntil = 0L
      spark.live = false
      // An old trace would read as current: the chart goes back to empty.
      spark.clear()
      liveButton.setText("ライブ表示")
      refresh()

  // Leaving the app stops everything here (the screen's onHide is not
  // called on pause; onShow is, again, on resume).
  SensorUi.onPause(act)(onHide())

  // --- while shown: ages tick every second, batched samples are asked for
  // every 15 s, the history is reloaded every minute ---
  private var shown = false
  private var ticks = 0
  private val visible = android.graphics.Rect()
  private val tick: Runnable = () =>
    if shown then
      ticks += 1
      val now = System.currentTimeMillis()
      if live && (now >= liveUntil || !view.isShown || !view.getLocalVisibleRect(visible)) then stopLive()
      if ticks % 15 == 0 then LoggerState.flushSensors()
      if ticks % 60 == 0 then loadHistory()
      refresh()
      main.postDelayed(tick, 1_000)

  def onShow(): Unit =
    if !shown then
      shown = true
      ticks = 0
      LoggerState.flushSensors()
      main.postDelayed(tick, 1_000)
    loadHistory()

  def onHide(): Unit =
    shown = false
    main.removeCallbacks(tick)
    stopLive()

  /** The chosen range of motion summaries, bucketed off the main thread. */
  /** End of the newest window the history was drawn with, and when. */
  private var loadedUpTo = 0L
  private var loadedAt = 0L

  def loadHistory(): Unit =
    val now = System.currentTimeMillis()
    val day = SensorPanel.day
    val span = if day then 24 * 3_600_000L else 3_600_000L
    val bucket = if day then 15 * 60_000L else 60_000L
    val from = now - span
    // Windows not yet written to the DB (writes are batched).
    val recent = LoggerState.recentWindows
    val s = LoggerState.sensors
    val lastEnd = (recent.lastOption ++ LoggerState.lastWindow).map(_.end).maxOption.getOrElse(0L)
    // While measuring, time after the last delivery has simply not arrived.
    val pendingFrom = if s.accelHz > 0 && LoggerState.running then math.max(lastEnd, s.accelSince) else Long.MaxValue
    val steps = hasStepCounter
    loadedUpTo = lastEnd
    loadedAt = now
    AshidoriApp.io.execute { () =>
      val stored = AshidoriApp.db.motion(from, now)
      val seen = stored.iterator.map(_.start).toSet
      val ws = (stored ++ recent.filter(w => w.start >= from && !seen(w.start))).sortBy(_.start)
      val speeds = AshidoriApp.db.fixes(from, now).iterator.filter(_.hasSpeed).map(f => (f.time, f.speed)).toVector
      val bs = MotionHistory.buckets(ws, speeds, from, now, bucket, pendingFrom)
      val tot = MotionHistory.totals(bs)
      val stepSum = bs.map(_.steps).sum
      act.runOnUiThread { () =>
        history.update(bs, from, now, if day then "歩/15分" else "歩/分", steps)
        def t(m: MotionClass) = fmtDuration(tot(m))
        summary.setText(
          f"歩行 ${t(MotionClass.Walking)}・乗り物 ${t(MotionClass.Vehicle)}・その他 ${t(MotionClass.Other)}" +
            (if steps then f"・歩数 $stepSum%,d 歩" else ""))
      }
    }

  private def motionLabel(m: MotionState): String = m match
    case MotionState.Stationary => "静止"
    case MotionState.OnFoot     => "歩行"
    case MotionState.Vehicle    => "乗り物"
    case MotionState.Unknown    => "その他"

  def refresh(): Unit =
    val now = System.currentTimeMillis()
    val s = LoggerState.sensors
    val running = LoggerState.running
    // A new window arrived since the history was drawn: draw it again
    // (at most every 10 s), so its right end matches 「いま」.
    if shown && LoggerState.lastWindow.exists(_.end > loadedUpTo) && now - loadedAt >= 10_000 then loadHistory()
    val win = LoggerState.lastWindow
    val accelOn = running && s.accelHz > 0
    // A window from the current measurement period, delivered recently enough.
    val fresh = win.filter(w => accelOn && w.end >= s.accelSince - 1_000 && now - w.end <= s.accelBatchMs + 25_000)

    // State of the accelerometer, and why.
    if !running then state.set("記録停止中", Tone.Off, "")
    else if accelOn then
      val every = if s.accelBatchMs >= 1_000 then s"${s.accelBatchMs / 1_000}秒ごとに一括受信" else "逐次受信"
      if fresh.isDefined then state.set(s"計測中 ${s.accelHz} Hz", Tone.Active, every)
      else state.set("一括受信待ち", Tone.Waiting, s"計測中 ${s.accelHz} Hz・$every")
    else if LoggerState.motion == MotionState.Stationary then
      // What will turn it back on: the significant-motion interrupt, else
      // the step counter, else only the periodic re-check.
      state.set("停止中（静止）", Tone.Off,
        if s.motionTrigger then "動くと再開" else if hasStepCounter then "歩き出すと再開" else "移動の検知待ち")
    else state.set("停止中", Tone.Off, "")

    // 動き: the logger's classification, since when, and the age of its latest evidence.
    val since = LoggerState.motionSince
    val evidence = win.map(w => now - w.end)
    if !running then motionRow.none()
    else if LoggerState.motion == MotionState.Unknown && win.forall(w => now - w.end > 10 * 60_000) then
      motionRow.set("判定中")
    else
      motionRow.set(
        spans((motionLabel(LoggerState.motion), false), (if since > 0 then s"  ${fmtDuration(now - since)}前から" else "", true)),
        evidence)

    // 揺れ: live over the last 5 s, else the logger's last 10 s window.
    if live then
      val v = spark.std(now, 5_000)
      if v.isNaN then shakeRow.set(spans(("—  ", false), ("受信中", true)), ageText = "ライブ")
      else shakeRow.set(spans((f"$v%.2f m/s²", false), ("  直近5秒の標準偏差", true)), ageText = "ライブ")
    else fresh match
      case Some(w) if !w.accelStd.isNaN =>
        shakeRow.set(spans((f"${w.accelStd}%.2f m/s²", false), (s"  ${(w.durationMs + 500) / 1_000}秒間の標準偏差", true)),
          Some(now - w.end))
      case _ =>
        shakeRow.none(if !running then "" else if accelOn then "受信待ち" else "計測停止中")

    // 歩数: the hardware counter runs even while the accelerometer is off.
    if !running then stepsRow.none()
    else if !hasStepCounter then stepsRow.none("歩数計なし")
    else
      val lastMin = LoggerState.recentSteps.iterator.filter(_._1 >= now - 60_000).map(_._2).sum
      val today = LoggerState.stepsToday
      stepsRow.set(
        spans((s"$lastMin 歩", false), ("  直近1分", true), (if today >= 0 then f"・$today%,d 歩" else "・—", false), ("  今日", true)),
        Option(LoggerState.stepsAt).filter(_ > 0).map(now - _))

    // ジャイロ: sampled only by detail logs.
    LoggerState.gyro.filter(g => s.imuHz > 0 && now - g._1 < 10_000) match
      case Some((t, radPerS)) =>
        gyroRow.set(spans((f"${math.toDegrees(radPerS)}%.0f °/s", false), (s"  ${s.imuHz} Hz", true)), Some(now - t))
      case None => gyroRow.none("詳細ログ時のみ")

    sparkNote.setText(
      if live then
        val left = math.max(0L, liveUntil - now) / 1_000
        f"● ライブ表示中・残り ${left / 60}%d:${left % 60}%02d・${spark.rateHz(now)} Hz（最長2分）"
      else "|加速度| − 重力（直近60秒・ライブ表示中のみ）")
    sparkNote.setTextColor(color(if live then attr.primary else attr.onSurfaceVariant))

    extras.setText(
      "歩行推定 " + (if s.pdr then "動作中" else "待機（屋内歩行時のみ）") +
        "・生データ " + (if s.imuHz > 0 then f"記録中 ${s.imuHz} Hz" else "待機"))

  /** The phone's motion sensors: what exists, who made it, how fast it can go. */
  private def showSensors(): Unit =
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

object SensorPanel:
  /** History range: false = 1時間, true = 24時間. Kept while the app runs. */
  var day = false
  /** Live display ends by itself after this long. */
  val LiveMaxMs: Long = 2 * 60_000L
