package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path}
import android.view.View
import ashidori.core.*
import net.sksat.ashidori.AshidoriApp

/** GNSS quality over the last hour or day, averaged into buckets: satellites
  * used (bars, 0–30), strongest-signal C/N0 (line, 0–50 dB-Hz) and periods
  * judged indoor (shaded). Gaps are time the receiver was off.
  */
final class GnssHistoryView(c: Context) extends View(c):
  private given Context = c
  private final case class B(start: Long, used: Float, cn0: Double, indoor: Boolean)
  private var data: Vector[B] = Vector.empty
  private var bucketMs = 30_000L
  private var from = 0L
  private var to = 1L
  private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shade = Paint()
  private val base = Paint()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  private val axis = SensorUi.TimeAxis()
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(2).toFloat)
  line.setStrokeJoin(Paint.Join.ROUND)
  label.setTextSize(Ui.dp(9).toFloat)

  def update(d: Vector[(GnssSnapshot, Environment, Float)], fromMs: Long, toMs: Long): Unit =
    from = fromMs
    to = math.max(fromMs + 1, toMs)
    bucketMs = if to - from <= 2 * 3_600_000L then 30_000L else 10 * 60_000L
    data = d.filter(_._1.time >= from).groupBy(x => (x._1.time - from) / bucketMs).toVector.sortBy(_._1).map { (i, xs) =>
      B(from + i * bucketMs, xs.map(_._1.used).sum.toFloat / xs.size, xs.map(_._1.top4Cn0).sum / xs.size,
        xs.count(_._2 == Environment.Indoor) * 2 > xs.size)
    }
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), Ui.dp(150))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth.toFloat
    val top = Ui.dp(12).toFloat
    val h = getHeight - Ui.dp(18).toFloat
    def x(t: Long) = (t - from).toFloat / (to - from) * w
    val bw = math.max(Ui.dp(1.5f).toFloat, x(from + bucketMs) - Ui.dp(0.5f))
    base.setColor(Ui.color(Ui.attr.outlineVariant))
    canvas.drawRect(0, h, w, h + Ui.dp(1), base)
    // Indoor periods
    shade.setColor(Ui.color(Ui.attr.secondaryContainer))
    for b <- data if b.indoor do canvas.drawRect(x(b.start), top, x(b.start) + bw, h, shade)
    // Satellites used (0..30 scale)
    bar.setColor(Ui.color(Ui.attr.primary))
    bar.setAlpha(150)
    for b <- data do
      val y = h - (h - top) * math.min(1f, b.used / 30f)
      canvas.drawRect(x(b.start), math.min(y, h - Ui.dp(1)), x(b.start) + bw, h, bar)
    // C/N0 of the strongest signals (0..50 dB-Hz), broken across gaps.
    line.setColor(Ui.color(Ui.attr.tertiary))
    val path = Path()
    var prev = -1L
    for b <- data do
      val px = x(b.start) + bw / 2
      val py = h - (h - top) * math.min(1.0, b.cn0 / 50.0).toFloat
      if prev < 0 || b.start - prev > bucketMs then path.moveTo(px, py) else path.lineTo(px, py)
      prev = b.start
    canvas.drawPath(path, line)
    // Scales: each series' top, in its own color.
    label.setColor(Ui.color(Ui.attr.primary))
    canvas.drawText("30機", 0, top - Ui.dp(2), label)
    label.setColor(Ui.color(Ui.attr.tertiary))
    SensorUi.drawNote(canvas, "50 dB-Hz", w, top - Ui.dp(2), label, alignRight = true)
    axis.draw(canvas, from, to, 0, w, h + Ui.dp(4), AshidoriApp.zone)
