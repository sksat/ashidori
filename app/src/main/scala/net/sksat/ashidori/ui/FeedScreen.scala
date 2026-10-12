package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path, RectF}
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.LinearLayout
import java.time.{DayOfWeek, LocalDate}
import java.time.format.DateTimeFormatter
import com.google.android.material.button.{MaterialButton, MaterialButtonToggleGroup}
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The day's route drawn without a map: legs in transport mode colors and
  * stays as dots, fitted to the view. Cheap enough for every card in a feed
  * and works offline.
  */
final class RouteSketchView(c: Context, legs: Seq[(Seq[LatLng], Int)], stays: Seq[LatLng]) extends View(c):
  private given Context = c
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(3).toFloat)
  line.setStrokeCap(Paint.Cap.ROUND)
  line.setStrokeJoin(Paint.Join.ROUND)
  private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
  private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
  bg.setColor(Ui.color(Ui.attr.surfaceContainerHigh))

  override def onDraw(canvas: Canvas): Unit =
    val w = getWidth.toFloat
    val h = getHeight.toFloat
    val r = Ui.dp(12).toFloat
    canvas.drawRoundRect(RectF(0, 0, w, h), r, r, bg)
    val pts = legs.flatMap(_._1) ++ stays
    if pts.isEmpty then return
    // Equirectangular projection around the day's mean latitude.
    val k = math.cos(math.toRadians(pts.map(_.lat).sum / pts.size))
    val xs = pts.map(_.lon * k)
    val ys = pts.map(_.lat)
    val (x0, x1, y0, y1) = (xs.min, xs.max, ys.min, ys.max)
    val pad = Ui.dp(14).toFloat
    // At least ~300 m across so a day at home is not blown up to noise.
    val span = math.max(math.max(x1 - x0, y1 - y0), 0.003)
    val scale = math.min((w - 2 * pad) / span, (h - 2 * pad) / span).toFloat
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    def px(p: LatLng) = (w / 2 + ((p.lon * k - cx) * scale).toFloat, h / 2 - ((p.lat - cy) * scale).toFloat)
    for (ps, col) <- legs if ps.size >= 2 do
      val path = Path()
      val (sx, sy) = px(ps.head)
      path.moveTo(sx, sy)
      ps.tail.foreach { p =>
        val (x, y) = px(p)
        path.lineTo(x, y)
      }
      line.setColor(col)
      canvas.drawPath(path, line)
    for s <- stays do
      val (x, y) = px(s)
      dot.setColor(Ui.color(Ui.attr.primary))
      canvas.drawCircle(x, y, Ui.dp(5).toFloat, dot)
      dot.setColor(Ui.color(Ui.attr.surface))
      canvas.drawCircle(x, y, Ui.dp(2).toFloat, dot)

/** タイムライン: everything newest first, scrolling back without end.
  * 日 is one continuous rail of stays and trips with date headings; 週 and 月
  * show one card per period. Tapping opens the day on the map or the period.
  */
final class FeedScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val dayFmt = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
  private val shortFmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")

  private val prefs = act.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
  private var unit: Span =
    Span.values.find(_.toString == prefs.getString("feedUnit", "Day")).filter(_ != Span.Year).getOrElse(Span.Day)

  // 日 / 週 / 月 switch, pinned above the feed.
  private val toggle = MaterialButtonToggleGroup(act)
  toggle.setSingleSelection(true)
  toggle.setSelectionRequired(true)
  private val unitButtons = Seq(Span.Day, Span.Week, Span.Month).map { sp =>
    val b = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    b.setId(View.generateViewId())
    b.setText(sp.label)
    b.setLayoutParams(weighted())
    toggle.addView(b)
    sp -> b
  }.toMap
  toggle.check(unitButtons(unit).getId)
  toggle.addOnButtonCheckedListener { (_, id, checked) =>
    if checked then
      unitButtons.find(_._2.getId == id).foreach { (sp, _) =>
        if sp != unit then
          unit = sp
          prefs.edit().putString("feedUnit", sp.toString).apply()
          reload()
      }
  }

  private val items = vbox()
  private val footer = padded(caption("読み込み中…"), 16, 16)
  private val scroller =
    val s = androidx.core.widget.NestedScrollView(act)
    s.addView(vbox(items, footer, spacer(88)))
    s.setOnScrollChangeListener((v: androidx.core.widget.NestedScrollView, _, y, _, _) =>
      if y + v.getHeight > items.getHeight - dp(1200) then loadMore()
    )
    s.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    s
  /** Search the history ("when did I go to …"), at thumb reach. */
  private val searchFab =
    val f = com.google.android.material.floatingactionbutton.FloatingActionButton(act)
    f.setImageResource(R.drawable.ic_search)
    f.setContentDescription("記録を探す")
    f.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(attr.secondaryContainer)))
    f.setImageTintList(android.content.res.ColorStateList.valueOf(color(attr.onSecondaryContainer)))
    f.setOnClickListener(_ => act.openSearch())
    f

  lazy val view: View =
    val content = vbox(padded(toggle, 16, 8), padded(vbox(backToToday), 16, 0), scroller)
    val lp = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM | Gravity.END)
    lp.setMargins(0, 0, dp(16), dp(16))
    val f = frame(content)
    f.addView(searchFab, lp)
    f

  /** Open 日 at a given day (older days continue below it). */
  def showFrom(d: LocalDate): Unit =
    startAt = Some(d).filter(_ != LocalDate.now(AshidoriApp.zone))
    if unit != Span.Day then
      unit = Span.Day
      toggle.check(unitButtons(Span.Day).getId)
    cursor = LocalDate.MIN // forces a reload on show

  /** Start of the next period to load (going back in time). */
  private var cursor: LocalDate = LocalDate.MIN
  private var oldest: Option[LocalDate] = None
  private var loading = false
  private var done = false
  /** Bumped on reload so late batches from a previous load are dropped. */
  private var generation = 0
  private var loadedAt = 0L
  /** Consecutive empty days not yet shown, newest first (日). */
  private var emptyRun = Vector.empty[LocalDate]
  /** Each shown day's views, so an edit redraws only that day. */
  private val dayBoxes = scala.collection.mutable.Map.empty[LocalDate, LinearLayout]
  private lazy val rows = DayRows(act, tl => refreshDay(tl.date))
  /** Show 日 starting from this day instead of today (from the map). */
  private var startAt: Option[LocalDate] = None
  private val backToToday =
    val b = outlined("今日へ") { startAt = None; reload() }
    b.setVisibility(View.GONE)
    b

  override def onShow(): Unit =
    // Refresh when coming back after a while (today changes).
    if cursor == LocalDate.MIN || System.currentTimeMillis() - loadedAt > 60_000 then reload()

  def reload(): Unit =
    generation += 1
    items.removeAllViews()
    footer.setText("読み込み中…")
    emptyRun = Vector.empty
    done = false
    loading = false
    dayBoxes.clear()
    DayMap.cancelPending()
    if unit != Span.Day then startAt = None
    backToToday.setVisibility(if startAt.isDefined then View.VISIBLE else View.GONE)
    cursor = startAt.getOrElse(unit.range(LocalDate.now(AshidoriApp.zone))._1)
    loadedAt = System.currentTimeMillis()
    scroller.scrollTo(0, 0)
    val gen = generation
    AshidoriApp.io.execute { () =>
      val first = AshidoriApp.db.firstDataDate()
      act.runOnUiThread { () =>
        if gen == generation then
          oldest = first
          loadMore()
      }
    }

  private def loadMore(): Unit =
    if loading || done then return
    loading = true
    val gen = generation
    val today = LocalDate.now(AshidoriApp.zone)
    // A batch: a week of days, or a few weeks / months.
    val starts = unit match
      case Span.Day   => Seq(cursor)
      case Span.Week  => (0 until 4).map(i => cursor.minusWeeks(i))
      case _          => (0 until 2).map(i => cursor.minusMonths(i))
    cursor = if unit == Span.Day then cursor.minusDays(7) else unit.shift(starts.last, -1)
    val sp = unit
    AshidoriApp.io.execute { () =>
      val periods = starts.map { a =>
        val (from, to0) = (if sp == Span.Day then (a.minusDays(6), a) else sp.range(a))
        val to = if to0.isAfter(today) then today else to0
        a -> Iterator.iterate(to)(_.minusDays(1)).takeWhile(!_.isBefore(from)).map(TimelineRepo.day).toVector
      }
      act.runOnUiThread { () =>
        if gen == generation then
          loading = false
          if sp == Span.Day then periods.foreach((_, tls) => appendDays(tls, today))
          else periods.foreach((a, tls) => items.addView(periodCard(sp, a, tls, today)))
          val reachedEnd = oldest.forall(o => cursor.isBefore(unit.range(o)._1))
          if reachedEnd then
            flushEmpty()
            done = true
            footer.setText(if oldest.isEmpty then "まだ記録がありません" else "これより前の記録はありません")
          // Fill the screen even when the first batch is short.
          else scroller.post(() => if items.getHeight < scroller.getHeight + dp(1200) then loadMore())
      }
    }

  // --- 日: one continuous rail ------------------------------------------------------

  private def appendDays(tls: Seq[DayTimeline], today: LocalDate): Unit =
    for tl <- tls do
      if tl.segments.isEmpty && tl.checkIns.isEmpty && tl.date != today then emptyRun :+= tl.date
      else
        flushEmpty()
        val box = vbox()
        dayBoxes(tl.date) = box
        fillDay(box, tl, today, first = items.getChildCount == 0)
        items.addView(box)

  private def fillDay(box: LinearLayout, tl: DayTimeline, today: LocalDate, first: Boolean): Unit =
    box.removeAllViews()
    if !first then box.addView(divider())
    box.addView(dayHeading(tl, today))
    DayMap.view(act, tl).foreach { m =>
      m.setOnClickListener(_ => act.openDay(tl.date))
      m.setContentDescription("この日の地図。タップでマップを開く")
      box.addView(m)
    }
    // Newest first, like the rest of the feed.
    val rs = rows.rows(tl)
    rs.foreach(box.addView)
    if rs.isEmpty then
      box.addView(padded(caption(if LoggerState.running then "記録待ち" else "記録停止中"), 52, 8))

  /** After an edit (place name, mode), rebuild just that day. */
  private def refreshDay(d: LocalDate): Unit =
    val gen = generation
    AshidoriApp.io.execute { () =>
      val tl = TimelineRepo.day(d)
      act.runOnUiThread { () =>
        if gen == generation then
          dayBoxes.get(d).foreach(box => fillDay(box, tl, LocalDate.now(AshidoriApp.zone), first = items.indexOfChild(box) == 0))
      }
    }

  /** A run of days without records, as one quiet line. */
  private def flushEmpty(): Unit =
    if emptyRun.nonEmpty then
      val (newest, oldestDay) = (emptyRun.head, emptyRun.last)
      val label =
        if newest == oldestDay then s"${newest.format(dayFmt)}　記録なし"
        else s"${oldestDay.format(shortFmt)}〜${newest.format(shortFmt)}　記録なし"
      items.addView(padded(caption(label), 24, 10))
      emptyRun = Vector.empty

  private def dayHeading(tl: DayTimeline, today: LocalDate): View =
    val d = tl.date
    val title = text(
      (if d == today then "今日　" else if d == today.minusDays(1) then "昨日　" else "") + d.format(dayFmt), 16f, bold = true)
    title.setLayoutParams(weighted())
    val s = tl.summary
    val stats = caption(Seq(
      if s.steps > 0 then f"${s.steps}%,d歩" else "",
      if s.totalDistanceM > 0 then fmtDistance(s.totalDistanceM) else ""
    ).filter(_.nonEmpty).mkString("・"))
    val row = hbox(title, stats)
    row.setPadding(dp(16), dp(18), dp(16), dp(6))
    row.setMinimumHeight(dp(48))
    row.setBackgroundResource(android.R.drawable.list_selector_background)
    // The heading opens the day on the map.
    row.setContentDescription(s"${d.format(dayFmt)}。タップで地図を開く")
    row.setOnClickListener(_ => act.openDay(d))
    row

  private def divider(): View =
    val v = View(act)
    v.setBackgroundColor(color(attr.outlineVariant))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    lp.setMargins(dp(16), dp(8), dp(16), 0)
    v.setLayoutParams(lp)
    v

  // --- 週 / 月: one card per period --------------------------------------------------

  private def modeLine(byMode: Map[TransportMode, Double]): String =
    byMode.toSeq.filter((m, d) => m != TransportMode.Still && d >= 50).sortBy(-_._2)
      // No line break inside one item ("🚶 2.1 km").
      .map((m, d) => s"${m.emoji} ${fmtDistance(d)}".replace(' ', ' ')).mkString("　")

  /** Mode shares of distance as one stacked bar. */
  private def modeBar(byMode: Map[TransportMode, Double]): View =
    val bar = LinearLayout(act)
    val total = byMode.values.sum
    for (m, d) <- byMode.toSeq.filter(_._1 != TransportMode.Still).sortBy(-_._2) if total > 0 do
      val v = View(act)
      v.setBackgroundColor(android.graphics.Color.parseColor(m.color))
      v.setLayoutParams(LinearLayout.LayoutParams(0, dp(8), (d / total).toFloat))
      bar.addView(v)
    bar.setClipToOutline(true)
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(4).toFloat)
    bar.setBackground(bg)
    val lp = matchWrap
    lp.setMargins(0, dp(8), 0, dp(6))
    bar.setLayoutParams(lp)
    bar

  private def periodCard(sp: Span, start: LocalDate, tls: Vector[DayTimeline], today: LocalDate): View =
    val (a, b) = sp.range(start)
    val current = !today.isBefore(a) && !today.isAfter(b)
    val label = sp match
      case Span.Week => (if current then "今週　" else "") + s"${a.format(shortFmt)}〜${b.format(shortFmt)}"
      case _         => (if current then "今月　" else "") + s"${a.getYear}年${a.getMonthValue}月"
    val box = vbox(text(label, 16f, bold = true))
    val active = tls.count(_.segments.nonEmpty)
    if active == 0 then box.addView(caption("記録なし"))
    else
      val trips = tls.flatMap(_.segments.collect { case t: Trip => t })
      val visits = tls.flatMap(_.segments.collect { case v: Visit => v })
      val tolerance = if sp == Span.Week then 40.0 else 150.0
      val legs = trips.flatMap(t => t.legs.filter(_.mode != TransportMode.Still).map { l =>
        (Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), tolerance).map(_.latLng),
          android.graphics.Color.parseColor(l.mode.color))
      })
      val sketch = RouteSketchView(act, legs, visits.map(_.latLng).distinct.take(200))
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
      lp.setMargins(0, dp(10), 0, dp(8))
      sketch.setLayoutParams(lp)
      box.addView(sketch)
      val byMode = tls.flatMap(_.summary.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _)
      val steps = tls.map(_.summary.steps).sum
      val places = visits.flatMap(_.placeId).distinct.size
      box.addView(text(f"${fmtDistance(byMode.values.sum)}・$steps%,d歩・${places}か所・記録 ${active}日", 14f))
      box.addView(modeBar(byMode))
      val m = modeLine(byMode)
      if m.nonEmpty then box.addView(caption(m))
      val top = tls.flatMap(tl => tl.segments.collect { case v: Visit => v }.flatMap(v => v.placeId.flatMap(tl.places.get)))
        .filter(_.named).groupMapReduce(_.displayName)(_ => 1)(_ + _).toSeq.sortBy(-_._2).take(3).map(_._1)
      if top.nonEmpty then
        val t = caption("よく行った場所: " + top.mkString("・"))
        t.setPadding(0, dp(4), 0, 0)
        box.addView(t)
    box.setPadding(dp(16), dp(14), dp(16), dp(14))
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(16).toFloat)
    bg.setColor(color(attr.surfaceContainer))
    box.setBackground(bg)
    box.setClickable(true)
    box.setForeground(act.getDrawable(android.R.drawable.list_selector_background))
    box.setOnClickListener(_ => act.openPeriod(sp, a))
    val lp = matchWrap
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    box.setLayoutParams(lp)
    box
