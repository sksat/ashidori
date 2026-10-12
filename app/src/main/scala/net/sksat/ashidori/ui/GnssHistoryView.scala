package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path}
import android.view.View
import ashidori.core.*

/** Last hour of GNSS quality: satellites used (bars), strongest-signal C/N0
  * (line) and periods judged indoor (shaded).
  */
final class GnssHistoryView(c: Context) extends View(c):
  private given Context = c
  private var data: Vector[(GnssSnapshot, Environment, Float)] = Vector.empty
  private var from = 0L
  private var to = 1L
  private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shade = Paint()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(2).toFloat)
  label.setTextSize(Ui.dp(10).toFloat)

  def update(d: Vector[(GnssSnapshot, Environment, Float)], fromMs: Long, toMs: Long): Unit =
    data = d
    from = fromMs
    to = math.max(fromMs + 1, toMs)
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), Ui.dp(150))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth.toFloat
    val h = getHeight - Ui.dp(16).toFloat
    def x(t: Long) = (t - from).toFloat / (to - from) * w
    // Indoor periods
    shade.setColor(Ui.color(Ui.attr.secondaryContainer))
    for case Seq((a, ea, _), (b, _, _)) <- data.sliding(2) if ea == Environment.Indoor && b.time - a.time < 120_000 do
      canvas.drawRect(x(a.time), 0, x(b.time), h, shade)
    // Satellites used (0..30 scale)
    bar.setColor(Ui.color(Ui.attr.primary))
    bar.setAlpha(140)
    val bw = math.max(Ui.dp(1.5f).toFloat, w / 360f)
    for (s, _, _) <- data do
      val top = h * (1 - math.min(1f, s.used / 30f))
      canvas.drawRect(x(s.time), top, x(s.time) + bw, h, bar)
    // C/N0 of the strongest signals (0..50 dB-Hz)
    line.setColor(Ui.color(Ui.attr.tertiary))
    val path = Path()
    var started = false
    var prevT = 0L
    for (s, _, _) <- data do
      val px = x(s.time)
      val py = h * (1 - math.min(1.0, s.top4Cn0 / 50.0)).toFloat
      // Break the line across gaps (GNSS was off).
      if !started || s.time - prevT > 120_000 then path.moveTo(px, py) else path.lineTo(px, py)
      started = true
      prevT = s.time
    canvas.drawPath(path, line)
    label.setColor(Ui.color(Ui.attr.onSurfaceVariant))
    canvas.drawText("1時間前", 0, getHeight.toFloat - Ui.dp(2), label)
    val now = "いま"
    canvas.drawText(now, w - label.measureText(now), getHeight.toFloat - Ui.dp(2), label)
