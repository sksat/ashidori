package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, DashPathEffect, Paint, Path}
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{GridLayout, HorizontalScrollView, LinearLayout, TextView}
import com.google.android.material.bottomsheet.{BottomSheetDialog, BottomSheetDragHandleView}
import java.time.{LocalDate, YearMonth}
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Parts of the day view: the date header, the summary row, the empty day
  * and the calendar.
  */
object DayParts:
  val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)

  /** 「‹ 11月17日（月） ▾ ›」: the arrows move a day, the date opens the calendar. */
  final class DateHeader(c: Context, shift: Int => Unit, pick: () => Unit) extends LinearLayout(c):
    private given Context = c
    private def arrow(label: String, description: String, n: Int): TextView =
      val t = text(label, 22f, colorAttr = attr.primary)
      t.setGravity(Gravity.CENTER)
      t.setContentDescription(description)
      t.setLayoutParams(LinearLayout.LayoutParams(dp(48), dp(48)))
      ripple(t, borderless = true)
      t.setOnClickListener(_ => shift(n))
      a11yRole(t, classOf[android.widget.Button])
      t
    private val prev = arrow("‹", "前の日", -1)
    private val next = arrow("›", "次の日", 1)
    private val date = oneLine(tabular(text("", 17f, bold = true)))
    date.setGravity(Gravity.CENTER)
    date.setLayoutParams(weighted())
    date.setMinHeight(dp(48))
    ripple(date)
    date.setOnClickListener(_ => pick())
    a11yRole(date, classOf[android.widget.Button])
    setOrientation(LinearLayout.HORIZONTAL)
    setGravity(Gravity.CENTER_VERTICAL)
    setPadding(dp(8), 0, dp(8), 0)
    addView(prev)
    addView(date)
    addView(next)

    def show(d: LocalDate, today: LocalDate): Unit =
      val label = d.format(dateFmt)
      val sb = android.text.SpannableStringBuilder(label).append(" ")
      val s = sb.length
      sb.append("▾")
      sb.setSpan(android.text.style.ForegroundColorSpan(color(attr.onSurfaceVariant)), s, sb.length, 0)
      sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), s, sb.length, 0)
      date.setText(sb)
      date.setContentDescription(s"$label、日付を選択")
      // Nothing after today: the arrow stays in place, disabled.
      next.setEnabled(d.isBefore(today))
      next.setAlpha(if d.isBefore(today) then 1f else 0.3f)

  /** A pill: a colour dot (optional), then the text. */
  private def pill(label: String, dot: Option[Int], muted: Boolean = false)(using c: Context): TextView =
    val t = tabular(text(label, 13f, colorAttr = if muted then attr.onSurfaceVariant else attr.onSurface))
    t.setSingleLine(true)
    dot.foreach { col =>
      val d = GradientDrawable()
      d.setShape(GradientDrawable.OVAL)
      d.setColor(col)
      d.setSize(dp(9), dp(9))
      t.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
      t.setCompoundDrawablePadding(dp(6))
    }
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(16).toFloat)
    bg.setColor(color(attr.surfaceContainerHigh))
    t.setBackground(bg)
    t.setGravity(Gravity.CENTER_VERTICAL)
    t.setPadding(dp(10), 0, dp(12), 0)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
    lp.setMarginEnd(dp(6))
    t.setLayoutParams(lp)
    t

  /** One line of chips: each mode with its distance and time (the map's
    * colour), the number of stays, the time without a record and the
    * steps. Always one line tall, scrolling sideways, so days with more
    * modes do not move the list.
    */
  final class SummaryRow(c: Context) extends HorizontalScrollView(c):
    private given Context = c
    private val row = LinearLayout(c)
    row.setPadding(dp(16), 0, dp(16), 0)
    setHorizontalScrollBarEnabled(false)
    addView(row)
    setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
    setPadding(0, dp(2), 0, dp(8))
    setClipToPadding(false)

    def clear(): Unit = row.removeAllViews()

    def show(s: DaySummary): Unit =
      row.removeAllViews()
      for (m, d) <- s.distanceByMode.toSeq.filter((m, d) => m != TransportMode.Still && d >= 10).sortBy(-_._2) do
        val p = pill(s"${m.label} ${fmtDistance(d)} · ${fmtDuration(s.durationByMode.getOrElse(m, 0L))}",
          Some(android.graphics.Color.parseColor(m.color)))
        row.addView(p)
      if s.visits > 0 || row.getChildCount > 0 then row.addView(pill(s"📍 滞在 ${s.visits}", None))
      if s.unrecordedMs > 0 then
        row.addView(pill(s"記録なし ${fmtDuration(s.unrecordedMs)}", Some(android.graphics.Color.parseColor(Palette.Unrecorded)), muted = true))
      if s.steps > 0 then row.addView(pill(f"${s.steps}%,d歩", None, muted = true))
      scrollTo(0, 0)

  /** The empty day's picture: a dotted path between two grey dots. */
  private final class EmptyPicture(c: Context) extends View(c):
    private given Context = c
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    line.setStyle(Paint.Style.STROKE)
    line.setStrokeWidth(dp(3).toFloat)
    line.setStrokeCap(Paint.Cap.ROUND)
    line.setColor(android.graphics.Color.parseColor(Palette.Unrecorded))
    line.setPathEffect(DashPathEffect(Array(0.1f, dp(8).toFloat), 0))
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    dot.setColor(color(attr.outlineVariant))
    setLayoutParams(LinearLayout.LayoutParams(dp(96), dp(72)))
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    override def onDraw(canvas: Canvas): Unit =
      val k = getWidth / 96f
      val p = Path()
      p.moveTo(8 * k, 60 * k)
      p.cubicTo(28 * k, 40 * k, 40 * k, 52 * k, 56 * k, 34 * k)
      p.cubicTo(72 * k, 16 * k, 82 * k, 18 * k, 90 * k, 10 * k)
      canvas.drawPath(p, line)
      canvas.drawCircle(8 * k, 60 * k, 5 * k, dot)
      canvas.drawCircle(90 * k, 10 * k, 5 * k, dot)

  /** The empty day: the picture and one line (and, today with recording
    * stopped, the way to start it).
    */
  def emptyDay(act: MainActivity, date: LocalDate): View =
    given Ctx = act
    val today = date == LocalDate.now(AshidoriApp.zone)
    val label = text(
      if !today then "この日の記録はありません"
      else if LoggerState.running then "記録中・データなし"
      else "記録停止中", 15f, colorAttr = attr.onSurfaceVariant)
    label.setGravity(Gravity.CENTER)
    val box = vbox(EmptyPicture(act), spacer(12), label)
    if today && !LoggerState.running then box.addView(button("▶  記録を開始")(act.startLogging()))
    box.setGravity(Gravity.CENTER_HORIZONTAL)
    box.setPadding(dp(16), dp(24), dp(16), dp(24))
    box

  /** What the calendar knows of a day. */
  enum Recorded:
    case None, Full, Partial

  /** The calendar: a month of days, a dot on days with a record (grey when
    * only part of the day was recorded), 今日 and キャンセル. `status` fills the
    * dots in the background, a day at a time.
    */
  final class Calendar(act: MainActivity, selected: LocalDate, first: Option[LocalDate],
                       status: LocalDate => Recorded, onPick: LocalDate => Unit):
    private given Ctx = act
    private val dialog = BottomSheetDialog(act)
    private val today = LocalDate.now(AshidoriApp.zone)
    private var month = YearMonth.from(selected)
    private val title = text("", 16f, bold = true)
    private val grid = GridLayout(act)
    @volatile private var generation = 0
    private val known = scala.collection.mutable.Map.empty[LocalDate, Recorded]

    def show(): Unit =
      grid.setColumnCount(7)
      title.setGravity(Gravity.CENTER)
      title.setLayoutParams(weighted())
      def arrow(label: String, description: String, n: Int) =
        val t = text(label, 22f, colorAttr = attr.primary)
        t.setGravity(Gravity.CENTER)
        t.setContentDescription(description)
        t.setLayoutParams(LinearLayout.LayoutParams(dp(48), dp(48)))
        ripple(t, borderless = true)
        t.setOnClickListener { _ =>
          val m = month.plusMonths(n)
          if !m.isAfter(YearMonth.from(today)) then
            month = m
            render()
        }
        t
      val header = hbox(arrow("‹", "前の月", -1), title, arrow("›", "次の月", 1))
      header.setGravity(Gravity.CENTER_VERTICAL)
      header.setPadding(dp(8), 0, dp(8), 0)
      def legendDot(col: Int, label: String) =
        val t = caption(label)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.OVAL)
        d.setColor(col)
        d.setSize(dp(6), dp(6))
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
        t.setCompoundDrawablePadding(dp(6))
        t.setPadding(0, 0, dp(16), 0)
        t
      val legend = hbox(legendDot(color(attr.primary), "記録あり"), legendDot(android.graphics.Color.parseColor(Palette.Unrecorded), "一部のみ"))
      legend.setPadding(dp(20), dp(8), dp(20), dp(4))
      def textButton(label: String)(f: => Unit) =
        val b = com.google.android.material.button.MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle)
        b.setText(label)
        b.setOnClickListener(_ => f)
        b
      val buttons = hbox(textButton("今日") { dialog.dismiss(); onPick(today) }, textButton("キャンセル")(dialog.dismiss()))
      buttons.setGravity(Gravity.END)
      buttons.setPadding(dp(12), 0, dp(12), dp(8))
      val glp = matchWrap
      glp.setMargins(dp(12), 0, dp(12), 0)
      grid.setLayoutParams(glp)
      dialog.setContentView(vbox(BottomSheetDragHandleView(act), header, grid, legend, buttons))
      dialog.getBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED)
      dialog.setOnDismissListener(_ => generation += 1)
      dialog.show()
      grid.post(() => render())

    private def render(): Unit =
      val my = { generation += 1; generation }
      title.setText(s"${month.getYear}年${month.getMonthValue}月")
      grid.removeAllViews()
      val cell = (grid.getWidth / 7).max(dp(40))
      for d <- Seq("日", "月", "火", "水", "木", "金", "土") do
        val h = caption(d)
        h.setGravity(Gravity.CENTER)
        h.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        grid.addView(h, cellParams(cell, dp(28)))
      val lead = month.atDay(1).getDayOfWeek.getValue % 7
      for _ <- 0 until lead do grid.addView(View(act), cellParams(cell, dp(44)))
      val days = (1 to month.lengthOfMonth).map(month.atDay)
      val cells = days.map { d =>
        val v = DayCell(act, d, d == selected, d.isAfter(today))
        v.recorded = known.getOrElse(d, Recorded.None)
        if !d.isAfter(today) then v.setOnClickListener { _ => dialog.dismiss(); onPick(d) }
        grid.addView(v, cellParams(cell, dp(44)))
        d -> v
      }.toMap
      // Dots as each day is known, newest first; one task per day so the
      // shared I/O thread stays free for other work in between.
      val todo = days.filter(d => !d.isAfter(today) && first.forall(f => !d.isBefore(f)) && !known.contains(d)).reverse
      def next(rest: Seq[LocalDate]): Unit = rest.headOption.foreach { d =>
        AshidoriApp.io.execute { () =>
          if my == generation then
            val r = status(d)
            act.runOnUiThread { () =>
              known(d) = r
              if my == generation then
                cells.get(d).foreach(_.recorded = r)
                next(rest.tail)
            }
        }
      }
      next(todo)

    private def cellParams(w: Int, h: Int): GridLayout.LayoutParams =
      val lp = GridLayout.LayoutParams()
      lp.width = w
      lp.height = h
      lp

  /** A day of the calendar: its number, a circle when selected, a dot under it. */
  private final class DayCell(c: Context, d: LocalDate, sel: Boolean, future: Boolean) extends View(c):
    private given Context = c
    private var rec = Recorded.None
    def recorded: Recorded = rec
    def recorded_=(r: Recorded): Unit =
      rec = r
      setContentDescription(d.format(dateFmt) + (r match
        case Recorded.Full    => "、記録あり"
        case Recorded.Partial => "、一部のみ"
        case Recorded.None    => ""))
      invalidate()
    private val num = Paint(Paint.ANTI_ALIAS_FLAG)
    num.setTextAlign(Paint.Align.CENTER)
    num.setTextSize(dp(14).toFloat)
    num.setFontFeatureSettings("tnum")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if !future then
      ripple(this, borderless = true)
      a11yRole(this, classOf[android.widget.Button])
    setContentDescription(d.format(dateFmt))
    override def onDraw(canvas: Canvas): Unit =
      val cx = getWidth / 2f
      val cy = getHeight / 2f
      if sel then
        p.setColor(color(attr.primary))
        canvas.drawCircle(cx, cy, dp(18).toFloat, p)
      num.setColor(
        if sel then color(com.google.android.material.R.attr.colorOnPrimary)
        else if future then color(attr.outlineVariant)
        else color(attr.onSurface))
      if sel then num.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
      val fm = num.getFontMetrics
      canvas.drawText(d.getDayOfMonth.toString, cx, cy - (fm.ascent + fm.descent) / 2 - dp(2), num)
      rec match
        case Recorded.None =>
        case r =>
          p.setColor(
            if sel then color(com.google.android.material.R.attr.colorOnPrimary)
            else if r == Recorded.Partial then android.graphics.Color.parseColor(Palette.Unrecorded)
            else color(attr.primary))
          canvas.drawCircle(cx, cy + dp(11), dp(2.5f).toFloat, p)
