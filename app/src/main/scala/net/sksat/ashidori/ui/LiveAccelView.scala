package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path, RectF}
import android.view.View

/** The last 60 seconds of |a| − g (how hard the phone is being moved,
  * orientation free), from the live accelerometer. Keeps its height in
  * every state; outside live display it is an empty box with a hint.
  */
final class LiveAccelView(c: Context) extends View(c):
  private given Context = c
  private val Cap = 2048
  private val ts = new Array[Long](Cap)
  private val vs = new Array[Float](Cap)
  private var head = 0
  private var size = 0
  var live = false
  private var lastDraw = 0L
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(1.5f).toFloat)
  line.setStrokeJoin(Paint.Join.ROUND)
  private val base = Paint()
  private val box = Paint(Paint.ANTI_ALIAS_FLAG)
  private val boxRect = RectF()
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(Ui.dp(10).toFloat)
  private val path = Path()
  val WindowMs = 60_000L

  def clear(): Unit =
    size = 0
    invalidate()

  /** One sample (wall-clock ms, m/s²). Redraws at most 5 times a second. */
  def add(t: Long, v: Float): Unit =
    ts(head) = t
    vs(head) = v
    head = (head + 1) % Cap
    size = math.min(size + 1, Cap)
    if t - lastDraw >= 200 then
      lastDraw = t
      invalidate()

  /** Samples within the last `ms` before `now`, oldest first. */
  private def recent(now: Long, ms: Long): Iterator[(Long, Float)] =
    (0 until size).iterator.map(i => (head - size + i + Cap) % Cap).map(i => (ts(i), vs(i))).filter(_._1 >= now - ms)

  /** Std of the samples in the last `ms`; NaN if too few. */
  def std(now: Long, ms: Long): Float =
    val xs = recent(now, ms).map(_._2.toDouble).toVector
    if xs.size < 5 then Float.NaN
    else
      val m = xs.sum / xs.size
      math.sqrt(xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1)).toFloat

  /** Samples per second over the last 5 s. */
  def rateHz(now: Long): Int = (recent(now, 5_000).size / 5.0).round.toInt

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), Ui.dp(80))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth.toFloat
    val axisH = Ui.dp(16).toFloat
    val pad = Ui.dp(6).toFloat
    // The plot box; the time labels sit under it.
    boxRect.set(0, 0, w, getHeight - axisH)
    box.setColor(Ui.color(Ui.attr.surfaceContainerHigh))
    canvas.drawRoundRect(boxRect, Ui.dp(8).toFloat, Ui.dp(8).toFloat, box)
    label.setColor(Ui.color(Ui.attr.onSurfaceVariant))
    val now = System.currentTimeMillis()
    val pts = if live then recent(now, WindowMs).toVector else Vector.empty
    if !live then
      // Only the hint until live display starts: no axes for data that is not there.
      val s = "「ライブ表示」で直近60秒の波形を表示"
      val fm = label.getFontMetrics
      canvas.drawText(s, (w - label.measureText(s)) / 2, boxRect.centerY - (fm.ascent + fm.descent) / 2, label)
    else
      val top = boxRect.top + pad
      val bottom = boxRect.bottom - pad
      val mid = (top + bottom) / 2
      // Symmetric scale, at least ±2 m/s², so a still phone draws a flat line.
      val peak = math.max(2f, pts.map(p => math.abs(p._2)).maxOption.getOrElse(0f))
      val scale = math.ceil(peak).toFloat
      base.setColor(Ui.color(Ui.attr.outlineVariant))
      canvas.drawRect(pad, mid, w - pad, mid + Ui.dp(1), base)
      // Scale marks inside the box, at its top and bottom left.
      canvas.drawText(f"+$scale%.0f m/s²", pad, top + Ui.dp(9), label)
      canvas.drawText(f"−$scale%.0f", pad, bottom - Ui.dp(1), label)
      val base2 = getHeight.toFloat - Ui.dp(3)
      canvas.drawText("60秒前", 0, base2, label)
      canvas.drawText("いま", w - label.measureText("いま"), base2, label)
      if pts.nonEmpty then
        path.reset()
        var first = true
        for (t, v) <- pts do
          val x = pad + (1 - (now - t).toFloat / WindowMs) * (w - 2 * pad)
          val y = mid - v / scale * (bottom - top) / 2
          if first then path.moveTo(x, y) else path.lineTo(x, y)
          first = false
        line.setColor(SensorUi.Colors.Walking)
        canvas.drawPath(path, line)
