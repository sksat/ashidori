package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint}
import android.view.View
import ashidori.core.*

/** Last hour of motion from the accelerometer summaries: shake per window
  * (bars, green while walking) and where the sensor was off because the
  * phone was still (shaded).
  */
final class MotionHistoryView(c: Context) extends View(c):
  private given Context = c
  private var data: Vector[MotionWindow] = Vector.empty
  private var from = 0L
  private var to = 1L
  private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shade = Paint()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(Ui.dp(10).toFloat)

  def update(d: Vector[MotionWindow], fromMs: Long, toMs: Long): Unit =
    data = d
    from = fromMs
    to = math.max(fromMs + 1, toMs)
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), Ui.dp(110))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth.toFloat
    val h = getHeight - Ui.dp(16).toFloat
    def x(t: Long) = (t - from).toFloat / (to - from) * w
    // Sensor off (no windows): the phone was judged still.
    shade.setColor(Ui.color(Ui.attr.surfaceContainerHigh))
    var last = from
    for m <- data :+ MotionWindow(to, to, 0, 0, 0) do
      if m.start - last > 60_000 then canvas.drawRect(x(last), 0, x(m.start), h, shade)
      last = math.max(last, m.end)
    // Shake: std of |a| (0..3 m/s²), walking windows in the walking color.
    for m <- data do
      val walking = m.cadencePerMin >= 30
      bar.setColor(if walking then 0xff2e9d57.toInt else Ui.color(Ui.attr.tertiary))
      // At least a sliver, so "measured, but still" differs from "sensor off".
      val top = math.min(h - Ui.dp(2), h * (1 - math.min(1f, m.accelStd / 3f)))
      canvas.drawRect(x(m.start), top, math.max(x(m.end), x(m.start) + Ui.dp(1.5f)), h, bar)
    label.setColor(Ui.color(Ui.attr.onSurfaceVariant))
    canvas.drawText("1時間前", 0, getHeight.toFloat - Ui.dp(2), label)
    val now = "いま"
    canvas.drawText(now, w - label.measureText(now), getHeight.toFloat - Ui.dp(2), label)
