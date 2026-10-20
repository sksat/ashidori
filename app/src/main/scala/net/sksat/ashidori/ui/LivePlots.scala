package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path}
import android.os.SystemClock
import android.view.View
import ashidori.core.{Compass, LiveSeries}
import Ui.*

/** Colours of the live sensor display: one per axis (X red, Y green, Z
  * blue), steps and altitude, each in a light and a dark variant.
  */
object LiveColors:
  /** Dark by the theme in use, not the system setting: the activity keeps
    * its theme across a uiMode change (configChanges) until it restarts.
    */
  private def night(using Ctx): Boolean = android.graphics.Color.luminance(color(attr.surface)) < 0.5f
  private def pick(light: Int, dark: Int)(using Ctx): Int = if night then dark else light
  def x(using Ctx): Int = pick(0xffd1495b, 0xffff8a96)
  def y(using Ctx): Int = pick(0xff2e8b57, 0xff7fd69e)
  def z(using Ctx): Int = pick(0xff2f6fd6, 0xff8fb4ff)
  def axes(using Ctx): Seq[Int] = Seq(x, y, z)
  def step(using Ctx): Int = pick(0xffc27c00.toInt, 0xfff0b54a.toInt)
  def altitude(using Ctx): Int = pick(0xff7a4fc9, 0xffc4a7ff)
  def live(using Ctx): Int = pick(0xffc2410c.toInt, 0xffff9a6b.toInt)

/** The last [[LivePlot.WindowNs]] of a 3-axis sensor: one line per axis,
  * drawn as the smallest and largest value of each pixel column so that
  * 50 Hz never looks spiky or broken. Optional vertical marks (steps).
  * The scale is fixed and grows only when a value leaves it.
  */
final class AxisPlot(c: Context, series: LiveSeries, lo: Float, hi: Float, gridStep: Float, marks: Option[LiveSeries])
    extends View(c):
  private given Ctx = c
  /** Shown in the middle instead of lines: why there is nothing to draw. */
  var message: String = ""
  private val grid = Paint()
  private val zero = Paint()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(dp(10).toFloat)
  label.setFontFeatureSettings("tnum")
  private val note = Paint(Paint.ANTI_ALIAS_FLAG)
  note.setTextSize(dp(12).toFloat)
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(dp(1.4f).toFloat)
  line.setStrokeJoin(Paint.Join.ROUND)
  private val mark = Paint()
  mark.setStrokeWidth(dp(1).toFloat)
  private val path = Path()
  private var env = LiveSeries.Envelope(1)

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), dp(86))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth
    val h = getHeight.toFloat
    if w <= 0 then return
    val now = SystemClock.elapsedRealtimeNanos()
    val from = now - LivePlot.WindowNs
    val (l, u) = LiveSeries.axisRange(lo, hi, series.range(from, now), gridStep)
    val pad = dp(2).toFloat
    def y(v: Float): Float = pad + (u - v) / (u - l) * (h - 2 * pad)
    // Grid on round values, the zero line a little stronger.
    grid.setColor((color(attr.outlineVariant) & 0x00ffffff) | 0x66000000)
    zero.setColor(color(attr.outlineVariant))
    var g = (math.ceil(l / gridStep) * gridStep).toFloat
    while g <= u + 1e-3f do
      canvas.drawRect(0, y(g), w.toFloat, y(g) + 1, if math.abs(g) < 1e-3f then zero else grid)
      g += gridStep
    // Marks under the lines.
    marks.foreach { m =>
      mark.setColor((LiveColors.step & 0x00ffffff) | 0x99000000)
      for t <- m.times(from, now) do
        val x = (t - from).toFloat / LivePlot.WindowNs * w
        canvas.drawLine(x, 0, x, h, mark)
    }
    if env.cols != w then env = LiveSeries.Envelope(w)
    val cols = LiveColors.axes
    for ch <- 0 until series.channels do
      series.envelope(ch, from, now, env, LivePlot.MaxGapNs)
      path.reset()
      var i = 0
      while i < w do
        if env.present(i) then
          val x = i + 0.5f
          if env.breakBefore(i) then path.moveTo(x, y(env.first(i))) else path.lineTo(x, y(env.first(i)))
          if env.min(i) != env.max(i) then
            path.lineTo(x, y(env.min(i)))
            path.lineTo(x, y(env.max(i)))
            path.lineTo(x, y(env.last(i)))
        i += 1
      line.setColor(cols(ch % cols.size))
      canvas.drawPath(path, line)
    // Scale inside the plot, top and bottom left.
    label.setColor(color(attr.onSurfaceVariant))
    canvas.drawText(LivePlot.scaleLabel(u), dp(3).toFloat, dp(11).toFloat, label)
    canvas.drawText(LivePlot.scaleLabel(l), dp(3).toFloat, h - dp(3), label)
    if message.nonEmpty then
      note.setColor(color(attr.onSurfaceVariant))
      val fm = note.getFontMetrics
      canvas.drawText(message, (w - note.measureText(message)) / 2, h / 2 - (fm.ascent + fm.descent) / 2, note)

object LivePlot:
  /** The charts' time window: the last 10 s. */
  val WindowNs: Long = 10_000_000_000L
  /** A silence longer than this breaks the line. */
  val MaxGapNs: Long = 250_000_000L
  /** The pressure sparkline's window: the last 60 s. */
  val PressureWindowNs: Long = 60_000_000_000L

  def scaleLabel(v: Float): String =
    val s = if v == v.round then s"${math.abs(v.round)}" else f"${math.abs(v)}%.1f"
    if v > 0 then "+" + s else if v < 0 then "−" + s else s

/** A compass card: the dial turns so that the needle (up, the top of the
  * phone) points at the heading. Without a heading, a still, faded dial.
  */
final class CompassDial(c: Context) extends View(c):
  private given Ctx = c
  /** Degrees, NaN for none. */
  var heading: Float = Float.NaN
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
  ring.setStyle(Paint.Style.STROKE)
  ring.setStrokeWidth(dp(1.5f).toFloat)
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(dp(10).toFloat)
  label.setTextAlign(Paint.Align.CENTER)
  private val needle = Paint(Paint.ANTI_ALIAS_FLAG)
  needle.setStrokeWidth(dp(3).toFloat)
  needle.setStrokeCap(Paint.Cap.ROUND)
  private val tip = Path()

  override def onMeasure(w: Int, h: Int): Unit =
    val s = dp(84)
    setMeasuredDimension(View.MeasureSpec.getSize(w), s)

  override def onDraw(canvas: Canvas): Unit =
    val cx = getWidth / 2f
    val cy = getHeight / 2f
    val r = math.min(getWidth, getHeight) / 2f - dp(4)
    val has = !heading.isNaN
    ring.setColor(color(attr.outlineVariant))
    canvas.drawCircle(cx, cy, r, ring)
    label.setColor(color(attr.onSurfaceVariant))
    label.setAlpha(if has then 255 else 110)
    val fm = label.getFontMetrics
    val h0 = if has then heading else 0f
    for (s, d) <- Seq(("北", 0f), ("東", 90f), ("南", 180f), ("西", 270f)) do
      val a = math.toRadians(d - h0)
      val x = cx + math.sin(a).toFloat * (r - dp(10))
      val y = cy - math.cos(a).toFloat * (r - dp(10))
      canvas.drawText(s, x, y - (fm.ascent + fm.descent) / 2, label)
    if has then
      needle.setColor(LiveColors.x)
      canvas.drawLine(cx, cy + dp(6), cx, cy - r + dp(18), needle)
      tip.reset()
      tip.moveTo(cx, cy - r + dp(12))
      tip.lineTo(cx - dp(5), cy - r + dp(21))
      tip.lineTo(cx + dp(5), cy - r + dp(21))
      tip.close()
      canvas.drawPath(tip, needle)

/** The last minute of air pressure as a height trace: up is higher (lower
  * pressure). At least about 1 m tall, so sensor noise stays flat.
  */
final class PressureSpark(c: Context, series: LiveSeries) extends View(c):
  private given Ctx = c
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(dp(1.5f).toFloat)
  line.setStrokeJoin(Paint.Join.ROUND)
  private val base = Paint()
  private val path = Path()
  private var env = LiveSeries.Envelope(1)

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), dp(34))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth
    val h = getHeight.toFloat
    if w <= 0 then return
    base.setColor((color(attr.outlineVariant) & 0x00ffffff) | 0x66000000)
    canvas.drawRect(0, h - 1, w.toFloat, h, base)
    val now = SystemClock.elapsedRealtimeNanos()
    val from = now - LivePlot.PressureWindowNs
    series.range(from, now).foreach { (lo0, hi0) =>
      // 0.12 hPa ≈ 1 m near sea level.
      val mid = (lo0 + hi0) / 2
      val half = math.max(0.06f, (hi0 - lo0) / 2 * 1.15f)
      val (lo, hi) = (mid - half, mid + half)
      val pad = dp(2).toFloat
      def y(p: Float): Float = pad + (p - lo) / (hi - lo) * (h - 2 * pad)
      if env.cols != w then env = LiveSeries.Envelope(w)
      series.envelope(0, from, now, env, 2_000_000_000L)
      path.reset()
      for i <- 0 until w if env.present(i) do
        val x = i + 0.5f
        if env.breakBefore(i) then path.moveTo(x, y(env.first(i))) else path.lineTo(x, y(env.first(i)))
        if env.min(i) != env.max(i) then
          path.lineTo(x, y(env.min(i)))
          path.lineTo(x, y(env.max(i)))
          path.lineTo(x, y(env.last(i)))
      line.setColor(LiveColors.altitude)
      canvas.drawPath(path, line)
    }
