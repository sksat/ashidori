package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint, Path, RectF}
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.LinearLayout
import java.time.{DayOfWeek, LocalDate}
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
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

/** タイムライン: days newest first, scrolling back like a social feed, with
  * week and month summaries "posted" where those periods ended. Tapping a day
  * opens it on the map; tapping a summary opens the period view.
  */
final class FeedScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val dayFmt = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
  private val shortFmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)

  private val items = vbox()
  private val footer = padded(caption("読み込み中…"), 16, 16)
  private val scroller =
    val s = androidx.core.widget.NestedScrollView(act)
    s.addView(vbox(items, footer, spacer(88)))
    s.setOnScrollChangeListener((v: androidx.core.widget.NestedScrollView, _, y, _, _) =>
      if y + v.getHeight > items.getHeight - dp(1200) then loadMore()
    )
    s
  val view: View = scroller

  /** Monday of the next week to load (going back in time). */
  private var nextWeek: LocalDate = LocalDate.MIN
  private var oldest: Option[LocalDate] = None
  private var loading = false
  private var done = false
  /** Bumped on reload so late batches from a previous load are dropped. */
  private var generation = 0
  private var loadedAt = 0L
  /** Consecutive empty days not yet shown, newest first. */
  private var emptyRun = Vector.empty[LocalDate]

  override def onShow(): Unit =
    // Refresh when coming back after a while (today's card changes).
    if nextWeek == LocalDate.MIN || System.currentTimeMillis() - loadedAt > 60_000 then reload()

  def reload(): Unit =
    generation += 1
    items.removeAllViews()
    footer.setText("読み込み中…")
    emptyRun = Vector.empty
    done = false
    loading = false
    val today = LocalDate.now(AshidoriApp.zone)
    nextWeek = today.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    loadedAt = System.currentTimeMillis()
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
    val monday = nextWeek
    nextWeek = monday.minusWeeks(1)
    val today = LocalDate.now(AshidoriApp.zone)
    val days = (0 until 7).map(i => monday.plusDays(6 - i)).filterNot(_.isAfter(today))
    AshidoriApp.io.execute { () =>
      val tls = days.map(TimelineRepo.day)
      act.runOnUiThread { () =>
        if gen == generation then
          loading = false
          appendWeek(monday, tls, today)
          val reachedEnd = oldest.forall(o => !monday.isAfter(o))
          if reachedEnd then
            flushEmpty()
            done = true
            footer.setText(if oldest.isEmpty then "まだ記録がありません" else "これより前の記録はありません")
          // Fill the screen even when the first weeks are short.
          else scroller.post(() => if items.getHeight < scroller.getHeight + dp(1200) then loadMore())
      }
    }

  private def appendWeek(monday: LocalDate, tls: Seq[DayTimeline], today: LocalDate): Unit =
    val sunday = monday.plusDays(6)
    for tl <- tls do
      val d = tl.date
      // Summaries appear where their period ended, above its last day.
      if d == d.`with`(TemporalAdjusters.lastDayOfMonth()) && d.isBefore(today) then
        flushEmpty()
        items.addView(periodCard(Span.Month, d))
      if d == sunday && d.isBefore(today) then
        flushEmpty()
        items.addView(periodCard(Span.Week, d))
      if tl.segments.isEmpty && tl.checkIns.isEmpty && d != today then emptyRun :+= d
      else
        flushEmpty()
        items.addView(dayCard(tl, today))

  /** A run of days without records, as one quiet line. */
  private def flushEmpty(): Unit =
    if emptyRun.nonEmpty then
      val (newest, oldestDay) = (emptyRun.head, emptyRun.last)
      val label =
        if newest == oldestDay then s"${newest.format(dayFmt)}　記録なし"
        else s"${oldestDay.format(shortFmt)}〜${newest.format(shortFmt)}　記録なし"
      items.addView(padded(caption(label), 24, 6))
      emptyRun = Vector.empty

  private def cardBg(v: View, colorAttr: Int): Unit =
    val d = GradientDrawable()
    d.setCornerRadius(dp(16).toFloat)
    d.setColor(color(colorAttr))
    v.setBackground(d)

  private def tappable(inner: LinearLayout, colorAttr: Int)(onTap: => Unit): View =
    inner.setPadding(dp(16), dp(14), dp(16), dp(14))
    cardBg(inner, colorAttr)
    inner.setClickable(true)
    inner.setFocusable(true)
    inner.setForeground(act.getDrawable(android.R.drawable.list_selector_background))
    inner.setOnClickListener(_ => onTap)
    val lp = matchWrap
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    inner.setLayoutParams(lp)
    inner

  private def modeLine(byMode: Map[TransportMode, Double]): String =
    byMode.toSeq.filter((m, d) => m != TransportMode.Still && d >= 50).sortBy(-_._2)
      .map((m, d) => s"${m.emoji} ${fmtDistance(d)}").mkString("　")

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

  private def dayCard(tl: DayTimeline, today: LocalDate): View =
    val d = tl.date
    val title =
      (if d == today then "今日　" else if d == today.minusDays(1) then "昨日　" else "") + d.format(dayFmt)
    val head = text(title, 16f, bold = true)
    head.setLayoutParams(weighted())
    val steps = caption(if tl.summary.steps > 0 then f"${tl.summary.steps}%,d歩" else "")
    val box = vbox(hbox(head, steps))

    val trips = tl.segments.collect { case t: Trip => t }
    val visits = tl.segments.collect { case v: Visit => v }
    if trips.nonEmpty || visits.nonEmpty then
      val legs = trips.flatMap(t => t.legs.filter(_.mode != TransportMode.Still).map { l =>
        (Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), 15).map(_.latLng),
          android.graphics.Color.parseColor(l.mode.color))
      })
      val sketch = RouteSketchView(act, legs, visits.map(_.latLng))
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
      lp.setMargins(0, dp(10), 0, dp(10))
      sketch.setLayoutParams(lp)
      box.addView(sketch)

    // Where I was, in order (repeated stays merged).
    val names = visits.map(v => v.placeId.flatMap(tl.places.get).map(_.displayName).getOrElse("不明な場所"))
      .foldLeft(Vector.empty[String])((acc, n) => if acc.lastOption.contains(n) then acc else acc :+ n)
    if names.nonEmpty then
      val shown = if names.size > 6 then names.take(5) :+ s"…ほか${names.size - 5}か所" else names
      box.addView(text(shown.mkString(" → "), 14f))
    val modes = modeLine(tl.summary.distanceByMode)
    if modes.nonEmpty then
      val m = caption(modes)
      m.setPadding(0, dp(4), 0, 0)
      box.addView(m)
    for c <- tl.checkIns.sortBy(_.time) do
      val t = text(s"✓ ${c.venueName}", 14f, colorAttr = attr.primary)
      t.setPadding(0, dp(4), 0, 0)
      box.addView(t)
    if tl.segments.isEmpty && d == today then
      box.addView(caption(if LoggerState.running then "移動や滞在が検出されるとここに表示されます" else "記録停止中"))
    tappable(box, attr.surfaceContainer)(act.openDay(d))

  /** Week / month summary, filled in once its days are tallied. */
  private def periodCard(span: Span, anyDay: LocalDate): View =
    val (a, b) = span.range(anyDay)
    val label = span match
      case Span.Week  => s"週のまとめ　${a.format(shortFmt)}〜${b.format(shortFmt)}"
      case Span.Month => s"${a.getYear}年${a.getMonthValue}月のまとめ"
      case _          => ""
    val head = text(label, 16f, bold = true, colorAttr = attr.onPrimaryContainer)
    val totals = text("集計中…", 14f, colorAttr = attr.onPrimaryContainer)
    val box = vbox(head, totals)
    val gen = generation
    AshidoriApp.io.execute { () =>
      val tls = Iterator.iterate(a)(_.plusDays(1)).takeWhile(!_.isAfter(b)).map(TimelineRepo.day).toVector
      val byMode = tls.flatMap(_.summary.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _)
      val steps = tls.map(_.summary.steps).sum
      val activeDays = tls.count(_.segments.nonEmpty)
      val places = tls.flatMap(_.segments.collect { case v: Visit => v.placeId }.flatten).distinct.size
      val top = tls.flatMap(tl => tl.segments.collect { case v: Visit => v }.flatMap(v => v.placeId.flatMap(tl.places.get)))
        .filter(_.named).groupMapReduce(_.displayName)(_ => 1)(_ + _).toSeq.sortBy(-_._2).take(3).map(_._1)
      act.runOnUiThread { () =>
        if gen == generation then
          if activeDays == 0 then totals.setText("記録なし")
          else
            totals.setText(f"${fmtDistance(byMode.values.sum)}・$steps%,d歩・${places}か所・記録 ${activeDays}日")
            box.addView(modeBar(byMode))
            val m = modeLine(byMode)
            if m.nonEmpty then box.addView(text(m, 13f, colorAttr = attr.onPrimaryContainer))
            if top.nonEmpty then box.addView(text("よく行った場所: " + top.mkString("・"), 13f, colorAttr = attr.onPrimaryContainer))
      }
    }
    tappable(box, if span == Span.Month then attr.secondaryContainer else attr.primaryContainer)(act.openPeriod(span, anyDay))
