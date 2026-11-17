package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View}
import android.widget.LinearLayout
import java.time.{DayOfWeek, LocalDate}
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Granularity of the timeline. */
enum Span(val label: String, val period: String):
  case Day extends Span("日", "1日")
  case Week extends Span("週", "1週間")
  case Month extends Span("月", "1か月")
  case Year extends Span("年", "1年")

  /** First and last day of the span containing `d`. */
  def range(d: LocalDate): (LocalDate, LocalDate) = this match
    case Day   => (d, d)
    case Week  => (d.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), d.`with`(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
    case Month => (d.withDayOfMonth(1), d.`with`(TemporalAdjusters.lastDayOfMonth()))
    case Year  => (d.withDayOfYear(1), d.`with`(TemporalAdjusters.lastDayOfYear()))

  def shift(d: LocalDate, n: Int): LocalDate = this match
    case Day   => d.plusDays(n)
    case Week  => d.plusWeeks(n)
    case Month => d.plusMonths(n)
    case Year  => d.plusYears(n)

  def title(d: LocalDate, today: LocalDate): String =
    val (a, b) = range(d)
    this match
      case Day =>
        (if d == today then "今日 " else if d == today.minusDays(1) then "昨日 " else "") +
          d.format(DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE))
      case Week  => (if !today.isBefore(a) && !today.isAfter(b) then "今週 " else "") + s"${a.getMonthValue}/${a.getDayOfMonth}〜${b.getMonthValue}/${b.getDayOfMonth}"
      case Month => s"${d.getYear}年${d.getMonthValue}月"
      case Year  => s"${d.getYear}年"

/** Week/month/year view on the timeline's map and sheet: where I went and
  * how I moved over the period, with links back to single days.
  */
final class PeriodView(act: MainActivity, map: TrackMap, summary: LinearLayout, list: LinearLayout, openDay: LocalDate => Unit):
  private given Ctx = act
  private val dayFmt = DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPANESE)
  @volatile private var token = 0

  private def stat(value: String, label: String): View =
    val v = vbox(tabular(oneLine(text(value, 20f, bold = true))), caption(label))
    v.setGravity(Gravity.CENTER)
    v.setLayoutParams(weighted())
    v

  private def bar(frac: Double, col: Int): View =
    val outer = LinearLayout(act)
    val fill = View(act)
    val d = GradientDrawable()
    d.setCornerRadius(dp(4).toFloat)
    d.setColor(col)
    fill.setBackground(d)
    fill.setLayoutParams(LinearLayout.LayoutParams(0, dp(10), frac.toFloat.max(0.01f)))
    val rest = View(act)
    rest.setLayoutParams(LinearLayout.LayoutParams(0, dp(10), (1 - frac).toFloat.max(0f)))
    outer.addView(fill)
    outer.addView(rest)
    outer.setLayoutParams(weighted(2f))
    outer

  private def section(name: String): View = padded(text(name, 15f, bold = true), 16, 10)

  def load(span: Span, anchor: LocalDate): Unit =
    val my = { token += 1; token }
    val today = LocalDate.now(AshidoriApp.zone)
    val (a, b0) = span.range(anchor)
    val b = if b0.isAfter(today) then today else b0
    val days = Iterator.iterate(a)(_.plusDays(1)).takeWhile(!_.isAfter(b)).toVector
    // The totals keep their place ("—") while the period is summed up.
    summary.removeAllViews()
    summary.addView(stat("—", "移動距離"))
    summary.addView(stat("—", "歩数"))
    summary.addView(stat("—", "場所"))
    padded(summary, 8, 8)
    list.removeAllViews()
    val progress = tabular(caption("集計中…"))
    list.addView(padded(progress, 16, 8))
    AshidoriApp.io.execute { () =>
      val tls = days.zipWithIndex.map { (d, i) =>
        if i % 15 == 0 then act.runOnUiThread(() => if token == my then progress.setText(s"集計中… ${i}/${days.size}日"))
        TimelineRepo.day(d)
      }
      val visits = tls.flatMap(_.segments.collect { case v: Visit => v })
      val db = AshidoriApp.db
      // Learn home / work from the period's dwell pattern.
      val known = db.places().map(p => p.id -> p).toMap
      for (id, k) <- Places.inferHomeWork(visits, AshidoriApp.zone); p <- known.get(id) if p.kind == PlaceKind.Other do
        db.updatePlace(p.copy(kind = k))
      val places = db.places().map(p => p.id -> p).toMap
      val byPlace = visits.flatMap(v => v.placeId.map(_ -> v)).groupMap(_._1)(_._2)
      val ranked = byPlace.toVector.flatMap((id, vs) => places.get(id).map(p => (p, vs.sortBy(-_.start).toVector)))
        .sortBy((_, vs) => (-vs.size, -vs.map(_.durationMs).sum))
      // Legs in mode colors for every span; coarser for longer periods.
      val tolerance = span match
        case Span.Week  => 8.0
        case Span.Month => 25.0
        case _          => 80.0
      val paths =
        tls.flatMap(_.segments.collect { case t: Trip => t }).flatMap { t =>
          t.legs.filter(_.mode != TransportMode.Still).map(l =>
            MapPath(Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), tolerance), l.mode.color, l.isUncertain))
        }.filter(_.points.size >= 2)
      val regions = resolveRegions(ranked.map(_._1).take(60), db.placeRegions())
      act.runOnUiThread(() => if token == my then render(tls, ranked, paths, regions))
    }

  /** City / country per place, cached in the DB: from the offline index,
    * else (if allowed) from the platform geocoder for a few places per call.
    */
  private def resolveRegions(ps: Seq[Place], cached: Map[Long, (String, String)]): Map[Long, (String, String)] =
    ps.filterNot(p => cached.contains(p.id)).zipWithIndex.foldLeft(cached) { case (acc, (p, i)) =>
      Geocoding.region(p.lat, p.lon, platform = i < 15).fold(acc) { (city, country) =>
        AshidoriApp.db.setPlaceRegion(p.id, city, country)
        acc + (p.id -> (city, country))
      }
    }

  private def render(tls: Vector[DayTimeline], ranked: Vector[(Place, Vector[Visit])], paths: Seq[MapPath],
                     regions: Map[Long, (String, String)]): Unit =
    map.setData(
      paths,
      ranked.map((p, vs) => MapMarker(p.latLng, p.id.toString, p.displayName, vs.size.toDouble)),
      tls.flatMap(_.checkIns).map(c => MapMarker(LatLng(c.lat, c.lon), c.id.toString, c.venueName))
    )
    map.onMarkerClick = (layer, key) =>
      if layer == "visit" then ranked.find(_._1.id.toString == key).foreach((p, vs) => openPlace(p, vs))

    val byMode = tls.flatMap(_.summary.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _)
    val total = byMode.values.sum
    val steps = tls.map(_.summary.steps)
    summary.removeAllViews()
    summary.addView(stat(fmtDistance(total), "移動距離"))
    summary.addView(stat(f"${steps.sum}%,d", "歩数"))
    summary.addView(stat(ranked.size.toString, "場所"))
    padded(summary, 8, 8)

    list.removeAllViews()
    if tls.forall(_.segments.isEmpty) then
      list.addView(padded(caption("この期間の記録はありません"), 16, 8))
      return

    list.addView(section("移動手段"))
    for (m, d) <- byMode.toSeq.sortBy(-_._2) do
      val l = text(s"${m.emoji} ${m.label}", 14f)
      l.setLayoutParams(weighted(1.2f))
      val v = caption(fmtDistance(d))
      v.setLayoutParams(weighted(0.8f))
      list.addView(padded(hbox(l, bar(if total > 0 then d / total else 0, android.graphics.Color.parseColor(m.color)), v), 16, 4))

    list.addView(section("よく行った場所"))
    for (p, vs) <- ranked.take(10) do
      val row = vbox(text((if p.kind != PlaceKind.Other then s"[${p.kind.label}] " else "") + p.displayName, 15f),
        caption(s"${vs.size}回・${fmtDuration(vs.map(_.durationMs).sum)}"))
      row.setMinimumHeight(dp(52))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setOnClickListener(_ => openPlace(p, vs))
      list.addView(padded(row, 16, 2))

    // Cities and countries visited in the period (from the places' regions).
    val visitedRegions = ranked.flatMap((p, vs) => regions.get(p.id).map(r => (r, vs.size)))
    val byCountry = visitedRegions.groupBy(_._1._2).toSeq.filter(_._1.nonEmpty).sortBy(-_._2.map(_._2).sum)
    if byCountry.nonEmpty then
      list.addView(section("都市・国"))
      for (country, rs) <- byCountry do
        val cities = rs.groupMapReduce(_._1._1)(_._2)(_ + _).toSeq.filter(_._1.nonEmpty).sortBy(-_._2).map(_._1)
        list.addView(padded(vbox(text(s"$country（${cities.size}都市）", 15f), caption(cities.take(15).mkString("・"))), 16, 4))

    list.addView(section("日別"))
    val maxSteps = steps.maxOption.getOrElse(0).max(1)
    for tl <- tls.reverse if tl.segments.nonEmpty do
      val l = text(tl.date.format(dayFmt), 14f)
      l.setLayoutParams(weighted(0.9f))
      val v = caption(f"${tl.summary.steps}%,d歩・${fmtDistance(tl.summary.totalDistanceM)}")
      v.setLayoutParams(weighted(1.1f))
      val row = hbox(l, bar(tl.summary.steps.toDouble / maxSteps, color(attr.tertiary)), v)
      row.setMinimumHeight(dp(44))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setOnClickListener(_ => openDay(tl.date))
      list.addView(padded(row, 16, 0))

    val cs = tls.flatMap(_.checkIns).sortBy(-_.time)
    if cs.nonEmpty then
      list.addView(section(s"チェックイン（${cs.size}回）"))
      for c <- cs.take(20) do
        val row = vbox(text((if c.isPrivate then "🔒 " else "") + c.venueName, 15f), caption(Stats.localDate(c.time, AshidoriApp.zone).format(dayFmt)))
        row.setMinimumHeight(dp(48))
        row.setOnClickListener(_ => openDay(Stats.localDate(c.time, AshidoriApp.zone)))
        list.addView(padded(row, 16, 2))

  private def openPlace(p: Place, vs: Vector[Visit]): Unit =
    PlaceSheet(act, p, None, () => (), Some(openDay), vs).show()
