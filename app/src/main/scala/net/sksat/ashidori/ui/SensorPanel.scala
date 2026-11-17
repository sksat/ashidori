package net.sksat.ashidori.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.{Sensor, SensorManager}
import android.os.{Handler, Looper, SystemClock}
import android.text.{SpannableStringBuilder, Spanned}
import android.text.style.{ForegroundColorSpan, RelativeSizeSpan, StyleSpan}
import android.view.{Choreographer, Gravity, View, ViewGroup, ViewTreeObserver}
import android.widget.{LinearLayout, TextView}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*
import SensorUi.*

/** The phone's sensors: 「現在」 what the logger measures now and how old
  * each value is, then every sensor live while the card is on screen, then
  * 「履歴」 what they saw over the last hour or day.
  */
final class SensorPanel(act: MainActivity):
  private given Ctx = act
  private val sm = act.getSystemService(classOf[SensorManager])
  private lazy val hasStepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

  // --- 現在 ---
  private val state = StateLine()
  private val motionRow = ValueRow("動き")
  private val shakeRow = ValueRow("揺れ")
  private val stepsRow = ValueRow("歩数")
  /** The sensors used only in special cases, as one quiet line. */
  private val extras =
    val t = tabular(oneLine(caption("")))
    t.setPadding(0, dp(4), 0, 0)
    t

  // --- live display: the sensors below are registered only while the card
  // is on screen (see updateActive) ---
  private val live = LiveSensors(sm)
  private val accelPlot = AxisPlot(act, live.accel, -4f, 16f, 4f, Some(live.steps))
  accelPlot.setContentDescription("加速度の直近10秒のグラフ")
  private val gyroPlot = AxisPlot(act, live.gyro, -1f, 1f, 0.5f, None)
  gyroPlot.setContentDescription("ジャイロの直近10秒のグラフ")
  private val accelVals = AxisValues()
  private val gyroVals = AxisValues()
  private val stepLegend =
    val t = tabular(oneLine(caption("")))
    t.setGravity(Gravity.CENTER)
    t.setLayoutParams(weighted())
    t
  private val dial = CompassDial(act)
  dial.setContentDescription("方位")
  private val headingText = bigValue(center = true)
  private val headingNote =
    val t = oneLine(caption(""))
    t.setTextSize(11f)
    t.setGravity(Gravity.CENTER)
    t
  private val pressureText = bigValue(center = false)
  private val pressureNote =
    val t = tabular(oneLine(caption("")))
    t.setTextSize(11f)
    t
  private val pressureSpark = PressureSpark(act, live.pressure)
  private val rates =
    val t = tabular(stableCaption(Seq(rateText(0L, sample = true))))
    t.setTextSize(11f)
    t

  private val liveBlock: View =
    // The blinking dot says it is live; the gap pushes the window to the end.
    val note = View(act)
    note.setLayoutParams(weighted())
    val bar = hbox(liveDot(), note, caption("直近10秒"))
    bar.setPadding(0, dp(8), 0, dp(6))
    val axis = hbox(caption("10秒前"), stepLegend, caption("現在"))
    axis.setPadding(0, dp(2), 0, 0)
    val accelBox = plotBox(plotHeader("加速度", "m/s²", accelVals.view), accelPlot, axis)
    val gyroBox = plotBox(plotHeader("ジャイロ", "rad/s", gyroVals.view), gyroPlot)
    val compassBox = plotBox(miniTitle("方位"), dial, headingText, headingNote)
    val nowLabel = caption("現在")
    nowLabel.setGravity(Gravity.END)
    nowLabel.setLayoutParams(weighted())
    val pressureBox = plotBox(miniTitle("気圧"), pressureText, pressureNote, spacer(4), pressureSpark,
      hbox(caption("60秒前"), nowLabel))
    for (b, i) <- Seq(compassBox, pressureBox).zipWithIndex do
      val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
      if i == 0 then lp.setMarginEnd(dp(4)) else lp.setMarginStart(dp(4))
      b.setLayoutParams(lp)
    val duo = hbox(compassBox, pressureBox)
    duo.setGravity(Gravity.TOP)
    vbox(bar, accelBox, spacer(8), gyroBox, spacer(8), duo, spacer(10), sensorChips(), spacer(6), rates)

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
      legendItem(Swatch(MotionClass.Still), "静止"),
      legendItem(Swatch(MotionClass.Off), "センサー停止")
    )
  )

  val view: View =
    val title = text("センサー", 16f, bold = true)
    title.setLayoutParams(weighted())
    card(
      hbox(title, oneLine(caption(android.os.Build.MODEL))),
      section("現在"),
      state.view,
      motionRow.view,
      shakeRow.view,
      stepsRow.view,
      liveBlock,
      extras,
      section("履歴", rangeGroup),
      legend,
      spacer(4),
      history,
      summary,
      spacer(4),
      outlined("この端末のセンサー")(showSensors())
    )

  private val main = Handler(Looper.getMainLooper)

  // Leaving the app stops everything here (the screen's onHide is not
  // called on pause; onShow is, again, on resume).
  SensorUi.onPause(act)(onHide())

  // --- while shown: ages tick every second, batched samples are asked for
  // every 15 s, the history is reloaded every minute ---
  private var shown = false
  private var ticks = 0
  private val tick: Runnable = () =>
    if shown then
      ticks += 1
      updateActive()
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
    // After the next layout pass: before it, nothing is on screen yet.
    view.post(() => updateActive())

  def onHide(): Unit =
    shown = false
    main.removeCallbacks(tick)
    updateActive()

  // --- live display lifecycle: registered while the status tab is shown,
  // the app is in the foreground and some of the card is on screen ---
  private val visible = android.graphics.Rect()
  private def onScreen: Boolean = shown && view.isShown && view.getLocalVisibleRect(visible)

  private def updateActive(): Unit =
    if onScreen then
      if !live.isActive then
        live.start()
        lastText = 0L
        renderLive()
        Choreographer.getInstance.postFrameCallback(frame)
    else if live.isActive then
      live.stop()
      Choreographer.getInstance.removeFrameCallback(frame)

  private val onScroll: ViewTreeObserver.OnScrollChangedListener = () => updateActive()
  private val onLayout: ViewTreeObserver.OnGlobalLayoutListener = () => updateActive()
  view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener:
    override def onViewAttachedToWindow(v: View): Unit =
      v.getViewTreeObserver.addOnScrollChangedListener(onScroll)
      v.getViewTreeObserver.addOnGlobalLayoutListener(onLayout)
    override def onViewDetachedFromWindow(v: View): Unit =
      v.getViewTreeObserver.removeOnScrollChangedListener(onScroll)
      v.getViewTreeObserver.removeOnGlobalLayoutListener(onLayout)
      updateActive()
  )

  // The charts redraw every FrameMs (aligned to the display's frames),
  // values every TextMs, rates every RateMs; only while live.
  private var lastText = 0L
  private var lastRates = 0L
  private val frame: Choreographer.FrameCallback = new Choreographer.FrameCallback:
    override def doFrame(t: Long): Unit =
      if live.isActive then
        accelPlot.invalidate()
        gyroPlot.invalidate()
        pressureSpark.invalidate()
        if t - lastText >= SensorPanel.TextMs * 1_000_000L then
          lastText = t
          renderLive()
        Choreographer.getInstance.postFrameCallbackDelayed(this, SensorPanel.FrameMs)

  /** A sample is current if it is at most this old. */
  private val FreshNs = 1_000_000_000L

  /** Values, compass, pressure and rates. What is missing keeps its place
    * and says why: 「ジャイロなし」, 「受信待ち」.
    */
  private def renderLive(): Unit =
    val now = SystemClock.elapsedRealtimeNanos()
    def fresh(s: LiveSeries) = s.lastTime >= now - FreshNs
    def plotMessage(missing: Boolean, s: LiveSeries, none: String) =
      if missing then none else if s.lastTime >= now - LivePlot.WindowNs then "" else "受信待ち"

    accelPlot.message = plotMessage(live.accelSensor.isEmpty, live.accel, "加速度センサーなし")
    accelVals.set(live.accel, fresh(live.accel))
    gyroPlot.message = plotMessage(live.gyroSensor.isEmpty, live.gyro, "ジャイロなし")
    gyroVals.set(live.gyro, fresh(live.gyro))

    stepLegend.setText(
      if live.stepDetector.isEmpty && live.stepCounter.isEmpty then "歩数センサーなし"
      else if live.stepsDenied then "歩数の権限なし"
      else
        val b = SpannableStringBuilder("│ ")
        b.setSpan(ForegroundColorSpan(LiveColors.step), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(if live.stepDetector.isDefined then "歩（歩行検出）" else "歩（歩数計）")
        b)

    // 方位
    val hd = if fresh(live.heading) then live.heading.last(0) else Float.NaN
    dial.heading = hd
    dial.invalidate()
    val source = live.headingSource match
      case LiveSensors.HeadingSource.Rotation => "回転ベクトル"
      case LiveSensors.HeadingSource.MagAccel => "磁気＋加速度"
      case LiveSensors.HeadingSource.None     => ""
    val acc = live.headingAccuracy
    if live.headingSource == LiveSensors.HeadingSource.None then
      headingText.setText("—")
      headingNote.setText("方位センサーなし")
    else if hd.isNaN then
      headingText.setText("—")
      headingNote.setText(s"$source・受信待ち")
    else
      headingText.setText(s"${math.round(hd) % 360}° ${Compass.point(hd)}")
      headingNote.setText(if acc == -2 then source else s"$source・精度 ${Compass.accuracy(acc)}")
    // Low or unreliable: the compass needs calibrating (a figure-8).
    headingNote.setTextColor(color(if !hd.isNaN && acc >= -1 && acc <= 1 then attr.error else attr.onSurfaceVariant))

    // 気圧: the current value and the height change over the last minute.
    if live.pressureSensor.isEmpty then
      pressureText.setText("—")
      pressureNote.setText("気圧センサーなし")
    else if !fresh(live.pressure) then
      pressureText.setText("—")
      pressureNote.setText("hPa・受信待ち")
    else
      val p = live.pressure.last(0)
      val p0 = live.pressure.firstFrom(now - LivePlot.PressureWindowNs, 0)
      val dz = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p) -
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p0)
      pressureText.setText(f"$p%.2f")
      val b = SpannableStringBuilder("hPa・高度の変化 ")
      val at = b.length
      b.append(LiveSeries.signed(dz, 1) + " m")
      b.setSpan(ForegroundColorSpan(LiveColors.altitude), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      pressureNote.setText(b)

    if now - lastRates >= SensorPanel.RateMs * 1_000_000L then
      lastRates = now
      rates.setText(rateText(now, sample = false))

  /** 「受信 加速度 50 Hz・ジャイロ 50 Hz・方位 25 Hz・気圧 10 Hz・遅延 12 ms」:
    * what each of this phone's sensors actually delivers. `sample`: the
    * widest form, for the height the line keeps.
    */
  private def rateText(now: Long, sample: Boolean): CharSequence =
    val b = SpannableStringBuilder("受信 ")
    val strong = color(attr.onSurface)
    def put(s: String): Unit =
      val at = b.length
      b.append(s)
      b.setSpan(ForegroundColorSpan(strong), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    def hz(v: Double) = if sample then "000" else if v.isNaN then "—" else f"$v%.0f"
    val parts = Seq(
      live.accelSensor.map(_ => ("加速度", live.accel)),
      live.gyroSensor.map(_ => ("ジャイロ", live.gyro)),
      Option.when(live.headingSource != LiveSensors.HeadingSource.None)(("方位", live.heading)),
      live.pressureSensor.map(_ => ("気圧", live.pressure))
    ).flatten
    for ((name, s), i) <- parts.zipWithIndex do
      if i > 0 then b.append("・")
      put(s"$name ${hz(live.rate(s, now))} Hz")
    if parts.isEmpty then b.append("—")
    else
      b.append("・遅延 ")
      val l = live.latencyMs(now)
      put(if sample then "0000 ms" else if l.isNaN || l < 0 || l > 60_000 then "—" else f"$l%.0f ms")
    b

  // --- building blocks of the live display ---

  /** X/Y/Z values beside a chart's title, coloured like its lines; each in a
    * fixed width, so changing values never move anything.
    */
  private final class AxisValues:
    private val tvs = LiveColors.axes.zip(Seq("X", "Y", "Z")).map { (col, name) =>
      val t = tabular(oneLine(text("", 12f)))
      t.setTextColor(col)
      t.setGravity(Gravity.END)
      t.setLayoutParams(LinearLayout.LayoutParams(
        t.getPaint.measureText("X −00.00").ceil.toInt + dp(6), ViewGroup.LayoutParams.WRAP_CONTENT))
      (t, name)
    }
    val view: View = hbox(tvs.map(_._1)*)
    def set(s: LiveSeries, fresh: Boolean): Unit =
      for ((t, name), i) <- tvs.zipWithIndex do
        val b = SpannableStringBuilder(name + " ")
        b.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(RelativeSizeSpan(0.85f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(if fresh then LiveSeries.signed(s.last(i), 2) else "—")
        t.setText(b)
    set(LiveSeries(3, 2), false)

  private def plotHeader(title: String, unit: String, values: View): View =
    val t = oneLine(text(spans((title, false), (" " + unit, true)), 13.5f, bold = true))
    t.setLayoutParams(weighted())
    val h = hbox(t, values)
    h.setPadding(0, 0, 0, dp(4))
    h

  private def miniTitle(s: String): TextView = text(s, 13f, bold = true)

  private def bigValue(center: Boolean): TextView =
    val t = tabular(oneLine(text("—", 20f)))
    if center then t.setGravity(Gravity.CENTER)
    t

  /** A rounded panel in the surface colour, set off from the card. */
  private def plotBox(children: View*): LinearLayout =
    val v = vbox(children*)
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(14).toFloat)
    bg.setColor(color(attr.surface))
    v.setBackground(bg)
    v.setPadding(dp(10), dp(8), dp(10), dp(6))
    v

  private def liveDot(): View =
    val d = View(act)
    val bg = GradientDrawable()
    bg.setShape(GradientDrawable.OVAL)
    bg.setColor(LiveColors.live)
    d.setBackground(bg)
    val lp = LinearLayout.LayoutParams(dp(8), dp(8))
    lp.setMarginEnd(dp(8))
    d.setLayoutParams(lp)
    d

  /** Which sensors this phone has; the missing ones greyed: 「温度 なし」. */
  private def sensorChips(): View =
    val g = com.google.android.material.chip.ChipGroup(act)
    g.setChipSpacingHorizontal(dp(6))
    g.setChipSpacingVertical(dp(6))
    for (t, name) <- Seq(
        Sensor.TYPE_ACCELEROMETER -> "加速度",
        Sensor.TYPE_GYROSCOPE -> "ジャイロ",
        Sensor.TYPE_MAGNETIC_FIELD -> "地磁気",
        Sensor.TYPE_PRESSURE -> "気圧",
        Sensor.TYPE_STEP_COUNTER -> "歩数計",
        Sensor.TYPE_SIGNIFICANT_MOTION -> "動き出し検知",
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "温度"
      )
    do
      val c =
        if sm.getDefaultSensor(t) != null then chip(name, color(attr.primaryContainer), color(attr.onPrimaryContainer))
        else chip(s"$name なし", color(attr.surfaceContainerHigh), color(attr.onSurfaceVariant))
      c.setTextSize(11f)
      c.getBackground.asInstanceOf[GradientDrawable].setCornerRadius(dp(10).toFloat)
      c.setPadding(dp(9), dp(2), dp(9), dp(2))
      g.addView(c)
    g

  /** End of the newest window the history was drawn with, and when. */
  private var loadedUpTo = 0L
  private var loadedAt = 0L

  /** The chosen range of motion summaries, bucketed off the main thread. */
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
    // (at most every 10 s), so its right end matches 「現在」.
    if shown && LoggerState.lastWindow.exists(_.end > loadedUpTo) && now - loadedAt >= 10_000 then loadHistory()
    val win = LoggerState.lastWindow
    val accelOn = running && s.accelHz > 0
    // A window from the current measurement period, delivered recently enough.
    val fresh = win.filter(w => accelOn && w.end >= s.accelSince - 1_000 && now - w.end <= s.accelBatchMs + 25_000)

    // State of the accelerometer, and why.
    if !running then state.set("記録停止中", Tone.Off, "")
    else if accelOn then
      val every = if s.accelBatchMs >= 1_000 then s"${s.accelBatchMs / 1_000} 秒間隔で一括受信" else "逐次受信"
      if fresh.isDefined then state.set(s"計測中 ${s.accelHz} Hz", Tone.Active, every)
      else state.set("一括受信待ち", Tone.Waiting, s"計測中 ${s.accelHz} Hz・$every")
    else if LoggerState.motion == MotionState.Stationary then
      // What will turn it back on: the significant-motion interrupt, else
      // the step counter, else only the periodic re-check.
      state.set("停止中（静止）", Tone.Off,
        if s.motionTrigger || hasStepCounter then "移動開始時に再開" else "移動の検知待ち")
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
    val liveStd =
      if live.isActive then live.accel.magnitudeStd(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L)
      else Float.NaN
    if !liveStd.isNaN then
      shakeRow.set(spans((f"$liveStd%.2f m/s²", false), ("  直近5秒の標準偏差", true)), ageText = "ライブ")
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

    extras.setText(
      "歩行推定 " + (if s.pdr then "動作中" else "待機") +
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
      Sensor.TYPE_SIGNIFICANT_MOTION -> "動き出し検知"
    )
    val body = wanted.map { (t, label) =>
      Option(sm.getDefaultSensor(t, true)).orElse(Option(sm.getDefaultSensor(t))) match
        case Some(s) =>
          val maxHz = if s.getMinDelay > 0 then s" 最大 ${1_000_000 / s.getMinDelay}Hz" else ""
          val fifo = if s.getFifoMaxEventCount > 0 then f"・一括受信 ${s.getFifoMaxEventCount}%,d 件" else ""
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
  /** The live charts redraw at most this often (about 25 fps). */
  val FrameMs: Long = 40L
  /** Live values change at most this often, so they stay readable. */
  val TextMs: Long = 100L
  /** The delivered rates and latency, this often. */
  val RateMs: Long = 1_000L
