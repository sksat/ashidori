package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, RectF}
import android.view.View
import ashidori.core.*
import net.sksat.ashidori.AshidoriApp

/** Motion history in three rows over one time axis:
  *  - 動き: what each bucket mostly was (color + pattern, see the legend);
  *  - 揺れ: mean shake of the measured windows (std of |a|, 0–3 m/s²);
  *  - 歩数: steps per bucket.
  * Time the sensor was off is hatched; time not delivered yet (batched) is
  * left blank.
  */
final class MotionHistoryView(c: Context) extends View(c):
  private given Context = c
  private var data: Vector[MotionHistory.Bucket] = Vector.empty
  private var from = 0L
  private var to = 1L
  /** False on a phone without a step counter: the row says so instead. */
  private var hasSteps = true
  private var stepUnit = "歩/分"
  private val paint = SensorUi.MotionPaint()
  private val axis = SensorUi.TimeAxis()
  private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
  private val base = Paint()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(Ui.dp(10).toFloat)
  private val small = Paint(Paint.ANTI_ALIAS_FLAG)
  small.setTextSize(Ui.dp(9).toFloat)
  private val r = RectF()

  private val Gutter = Ui.dp(30).toFloat
  private val StripH = Ui.dp(12).toFloat
  private val ShakeH = Ui.dp(52).toFloat
  private val StepsH = Ui.dp(26).toFloat
  private val Gap = Ui.dp(6).toFloat
  private val AxisH = Ui.dp(16).toFloat
  private val MaxStd = 3f

  def update(d: Vector[MotionHistory.Bucket], fromMs: Long, toMs: Long, perBucket: String, steps: Boolean): Unit =
    data = d
    from = fromMs
    to = math.max(fromMs + 1, toMs)
    stepUnit = perBucket
    hasSteps = steps
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), (Ui.dp(4) + StripH + Gap + ShakeH + Gap + StepsH + AxisH).toInt)

  override def onDraw(canvas: Canvas): Unit =
    val right = getWidth.toFloat
    def x(t: Long) = Gutter + (t - from).toFloat / (to - from) * (right - Gutter)
    val stripTop = Ui.dp(4).toFloat
    val shakeTop = stripTop + StripH + Gap
    val shakeBottom = shakeTop + ShakeH
    val stepsTop = shakeBottom + Gap
    val stepsBottom = stepsTop + StepsH
    val muted = Ui.color(Ui.attr.onSurfaceVariant)

    // Row names in the gutter.
    label.setColor(muted)
    canvas.drawText("動き", 0, stripTop + StripH - Ui.dp(1), label)
    canvas.drawText("揺れ", 0, shakeTop + Ui.dp(10), label)
    canvas.drawText("歩数", 0, stepsTop + Ui.dp(10), label)

    // Baselines, so an empty row still reads as a row.
    base.setColor(Ui.color(Ui.attr.outlineVariant))
    canvas.drawRect(Gutter, shakeBottom, right, shakeBottom + Ui.dp(1), base)
    canvas.drawRect(Gutter, stepsBottom, right, stepsBottom + Ui.dp(1), base)

    val topSteps = data.map(_.steps).maxOption.getOrElse(0)
    val maxSteps = math.max(10, topSteps)
    for b <- data do
      val x0 = x(b.start)
      val x1 = math.max(x(b.end) - Ui.dp(0.5f), x0 + Ui.dp(1))
      // None: nothing of it delivered yet (batched), so left blank.
      b.shown.foreach { m =>
        r.set(x0, stripTop, x1, stripTop + StripH)
        paint.draw(canvas, m, r)
        if m == MotionClass.Off then
          // Not measured: hatched, so it does not read as "no shake".
          r.set(x0, shakeTop, x1, shakeBottom)
          paint.draw(canvas, MotionClass.Off, r)
        else
          val std = if b.meanStd.isNaN then 0f else b.meanStd
          // At least a sliver: "measured, still" differs from "not measured".
          val top = math.min(shakeBottom - Ui.dp(2), shakeBottom - ShakeH * math.min(1f, std / MaxStd))
          r.set(x0, top, x1, shakeBottom)
          paint.draw(canvas, m, r)
      }
      if hasSteps && b.steps > 0 then
        bar.setColor(SensorUi.Colors.Walking)
        val top = math.min(stepsBottom - Ui.dp(2), stepsBottom - StepsH * b.steps / maxSteps)
        canvas.drawRect(x0, top, x1, stepsBottom, bar)

    // Scales, top right of each row.
    small.setColor(muted)
    SensorUi.drawNote(canvas, f"${MaxStd}%.0f m/s²", right, shakeTop + Ui.dp(8), small, alignRight = true)
    if hasSteps then
      SensorUi.drawNote(canvas, f"最大 $topSteps%,d $stepUnit", right, stepsTop + Ui.dp(8), small, alignRight = true)
    else
      label.setColor(muted)
      canvas.drawText("歩数計なし", Gutter + Ui.dp(4), stepsBottom - Ui.dp(8), label)

    axis.draw(canvas, from, to, Gutter, right, stepsBottom + Ui.dp(4), AshidoriApp.zone)
