package net.sksat.ashidori.ui

import android.graphics.{Canvas, Paint, RectF}
import android.graphics.drawable.GradientDrawable
import android.text.{SpannableStringBuilder, Spanned}
import android.text.style.{ForegroundColorSpan, RelativeSizeSpan}
import android.view.{Gravity, View, ViewGroup}
import android.widget.{LinearLayout, TextView}
import com.google.android.material.button.{MaterialButton, MaterialButtonToggleGroup}
import java.time.{Instant, ZoneId}
import ashidori.core.*
import Ui.*

/** Building blocks shared by the sensor cards: a card is split into 「現在」
  * (state, live values, each with its age) and 「履歴」 (a time series).
  */
object SensorUi:
  /** How old a value is: 「3秒前」. */
  def fmtAge(ms: Long): String =
    val s = math.max(0L, ms) / 1000
    if s < 2 then "現在"
    else if s < 60 then s"${s}秒前"
    else if s < 3600 then s"${s / 60}分前"
    else if s < 86_400 then s"${s / 3600}時間前"
    else s"${s / 86_400}日前"

  /** Runs `f` whenever the activity pauses: screens get no onHide then, and
    * nothing that ticks or samples should outlive the visible UI.
    */
  def onPause(act: androidx.appcompat.app.AppCompatActivity)(f: => Unit): Unit =
    act.getLifecycle.addObserver(new androidx.lifecycle.LifecycleEventObserver:
      override def onStateChanged(src: androidx.lifecycle.LifecycleOwner, ev: androidx.lifecycle.Lifecycle.Event): Unit =
        if ev == androidx.lifecycle.Lifecycle.Event.ON_PAUSE then f
    )

  /** Every form an age takes, for the width its column keeps. */
  val AgeSample = "59秒前"

  /** Text whose parts are either the value (`false`) or a quieter note (`true`). */
  def spans(parts: (String, Boolean)*)(using Ctx): CharSequence =
    val b = SpannableStringBuilder()
    for (s, quiet) <- parts do
      val at = b.length
      b.append(s)
      if quiet then
        b.setSpan(ForegroundColorSpan(color(attr.onSurfaceVariant)), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(RelativeSizeSpan(0.85f), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    b

  /** 「現在」/「履歴」: a heading with a rule, and an optional control at the end. */
  def section(title: String, trailing: View*)(using Ctx): LinearLayout =
    val t = text(title, 13f, bold = true, colorAttr = attr.primary)
    val rule = View(summon[Ctx])
    rule.setBackgroundColor(color(attr.outlineVariant))
    val lp = LinearLayout.LayoutParams(0, dp(1), 1f)
    lp.setMargins(dp(8), 0, if trailing.isEmpty then 0 else dp(8), 0)
    rule.setLayoutParams(lp)
    val h = hbox((Seq(t, rule) ++ trailing)*)
    h.setPadding(0, dp(10), 0, dp(4))
    h.setMinimumHeight(dp(40))
    h

  enum Tone:
    case Active, Waiting, Off

  /** The state chip and, below it, why: 「停止中（静止）」 / 「移動開始時に再開」. */
  final class StateLine(using c: Ctx):
    private val chipView =
      val t = oneLine(text("", 13f, bold = true))
      t.setPadding(dp(10), dp(3), dp(10), dp(3))
      t
    private val bg = GradientDrawable()
    bg.setCornerRadius(dp(10).toFloat)
    chipView.setBackground(bg)
    private val reason =
      val t = tabular(oneLine(caption("")))
      t.setPadding(dp(2), dp(3), 0, 0)
      t
    val view: View =
      val v = vbox(hbox(chipView), reason)
      v.setPadding(0, 0, 0, dp(4))
      v

    def set(label: String, tone: Tone, why: String): Unit =
      chipView.setText(label)
      val (b, f) = tone match
        case Tone.Active  => ((Colors.Walking & 0x00ffffff) | 0x30000000, color(attr.onSurface))
        case Tone.Waiting => ((Colors.Waiting & 0x00ffffff) | 0x38000000, color(attr.onSurface))
        case Tone.Off     => (color(attr.surfaceContainerHigh), color(attr.onSurfaceVariant))
      bg.setColor(b)
      chipView.setTextColor(f)
      reason.setText(why)
      // Waiting (e.g. searching for satellites) pulses, so it reads as in progress.
      if tone == Tone.Waiting then
        if pulse == null then
          pulse = android.animation.ObjectAnimator.ofFloat(chipView, "alpha", 1f, 0.45f)
          pulse.setDuration(900)
          pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE)
          pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE)
          pulse.start()
      else if pulse != null then
        pulse.cancel()
        pulse = null
        chipView.setAlpha(1f)
    private var pulse: android.animation.ObjectAnimator = null

  /** A label, its value and how old the value is, in fixed columns. */
  final class ValueRow(label: String)(using c: Ctx):
    private val l =
      val t = text(label, 14f, colorAttr = attr.onSurfaceVariant)
      t.setLayoutParams(LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
      t
    val value: TextView =
      val t = tabular(oneLine(text("", 15f)))
      t.setLayoutParams(weighted())
      t
    private val age =
      val t = tabular(oneLine(caption("")))
      t.setGravity(Gravity.END)
      t.setMinWidth(t.getPaint.measureText(AgeSample).ceil.toInt + dp(8))
      t
    val view: View =
      val h = hbox(l, value, age)
      h.setPadding(0, dp(3), 0, dp(3))
      h

    /** `ageMs` None: no age to tell (the value is 「—」 or live). */
    def set(v: CharSequence, ageMs: Option[Long] = None, stale: Boolean = false, ageText: String = ""): Unit =
      value.setText(v)
      value.setAlpha(if stale then 0.45f else 1f)
      age.setText(ageMs.fold(ageText)(fmtAge))
      age.setTextColor(color(if stale then attr.error else attr.onSurfaceVariant))

    /** Not measured: 「—」 and, quieter, why. */
    def none(why: String = ""): Unit =
      set(if why.isEmpty then "—" else spans(("—  ", false), (why, true)))

  /** 1時間 / 24時間, shared by the history charts. */
  def rangeToggle(day: Boolean)(onChange: Boolean => Unit)(using c: Ctx): MaterialButtonToggleGroup =
    val g = MaterialButtonToggleGroup(c)
    g.setSingleSelection(true)
    g.setSelectionRequired(true)
    def b(label: String) =
      val x = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
      x.setId(View.generateViewId())
      x.setText(label)
      x.setTextSize(12f)
      x.setMinWidth(0)
      x.setMinimumWidth(0)
      x.setMinHeight(dp(32))
      x.setMinimumHeight(dp(32))
      x.setInsetTop(0)
      x.setInsetBottom(0)
      x.setPadding(dp(10), 0, dp(10), 0)
      x
    val hour = b("1時間")
    val all = b("24時間")
    g.addView(hour)
    g.addView(all)
    g.check(if day then all.getId else hour.getId)
    g.addOnButtonCheckedListener((_, id, checked) => if checked then onChange(id == all.getId))
    g

  object Colors:
    val Walking: Int = 0xff2e9d57.toInt
    val Vehicle: Int = 0xff1e6fd9.toInt
    val Waiting: Int = 0xffe8a33d.toInt

  /** How each motion class is painted: a color and, where colors alone could
    * be confused, a pattern (乗り物: stripes, センサー停止: hatching).
    */
  final class MotionPaint(using c: Ctx):
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    stroke.setStyle(Paint.Style.STROKE)
    stroke.setStrokeWidth(dp(1.2f).toFloat)

    def colorOf(m: MotionClass): Int = m match
      case MotionClass.Walking => Colors.Walking
      case MotionClass.Vehicle => Colors.Vehicle
      case MotionClass.Other   => color(attr.tertiary)
      case MotionClass.Still   => (color(attr.onSurfaceVariant) & 0x00ffffff) | 0x66000000
      case MotionClass.Off     => color(attr.outlineVariant)

    /** Diagonal lines over `r`, `gap` apart. */
    private def hatch(canvas: Canvas, r: RectF, col: Int, gap: Float): Unit =
      stroke.setColor(col)
      canvas.save()
      canvas.clipRect(r)
      val h = r.height
      var x = r.left - h
      while x < r.right do
        canvas.drawLine(x, r.bottom, x + h, r.top, stroke)
        x += gap
      canvas.restore()

    def draw(canvas: Canvas, m: MotionClass, r: RectF): Unit =
      if r.width > 0 && r.height > 0 then m match
        case MotionClass.Off =>
          hatch(canvas, r, colorOf(m), dp(4).toFloat)
        case MotionClass.Vehicle =>
          fill.setColor(colorOf(m))
          canvas.drawRect(r, fill)
          hatch(canvas, r, 0x88ffffff.toInt, dp(4).toFloat)
        case _ =>
          fill.setColor(colorOf(m))
          canvas.drawRect(r, fill)

  /** A small sample of a class's paint, for legends. */
  final class Swatch(m: MotionClass)(using c: Ctx) extends View(c):
    private val p = MotionPaint()
    private val r = RectF()
    setLayoutParams(LinearLayout.LayoutParams(dp(14), dp(12)))
    override def onDraw(canvas: Canvas): Unit =
      r.set(0, 0, getWidth.toFloat, getHeight.toFloat)
      p.draw(canvas, m, r)

  def legendItem(sample: View, label: String)(using Ctx): View =
    val t = caption(label)
    t.setPadding(dp(4), 0, dp(12), 0)
    val h = hbox(sample, t)
    h.setPadding(0, dp(1), 0, dp(1))
    h

  /** Clock labels under a chart spanning [from, to]; the right end is 「現在」. */
  final class TimeAxis(using c: Ctx):
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.setTextSize(dp(10).toFloat)
    private val tick = Paint()
    def draw(canvas: Canvas, from: Long, to: Long, left: Float, right: Float, top: Float, zone: ZoneId): Unit =
      p.setColor(color(attr.onSurfaceVariant))
      tick.setColor(color(attr.outlineVariant))
      def x(t: Long) = left + (t - from).toFloat / (to - from) * (right - left)
      val span = to - from
      val step = if span <= 2 * 3_600_000L then 15 * 60_000L else 6 * 3_600_000L
      // Ticks on round clock times in the local zone.
      val offset = zone.getRules.getOffset(Instant.ofEpochMilli(from)).getTotalSeconds * 1000L
      var t = ((from + offset) / step + 1) * step - offset
      val now = "現在"
      val nowW = p.measureText(now)
      val base = top + dp(11)
      canvas.drawText(now, right - nowW, base, p)
      while t < to do
        val lt = Instant.ofEpochMilli(t).atZone(zone).toLocalTime
        val s = if step >= 3_600_000L then s"${lt.getHour}時" else f"${lt.getHour}%d:${lt.getMinute}%02d"
        val w = p.measureText(s)
        val cx = x(t)
        canvas.drawRect(cx, top - dp(3), cx + dp(1), top, tick)
        val lx = cx - w / 2
        if lx >= left && lx + w < right - nowW - dp(6) then canvas.drawText(s, lx, base, p)
        t += step

  /** A fixed-width rounded label drawn in a chart (scale marks). */
  def drawNote(canvas: Canvas, s: String, x: Float, y: Float, p: Paint, alignRight: Boolean = false): Unit =
    val w = p.measureText(s)
    canvas.drawText(s, if alignRight then x - w else x, y, p)
