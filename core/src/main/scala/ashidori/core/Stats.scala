package ashidori.core

import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import java.time.temporal.{ChronoUnit, IsoFields}

final case class DaySummary(
    date: LocalDate,
    distanceByMode: Map[TransportMode, Double],
    durationByMode: Map[TransportMode, Long],
    steps: Int,
    visits: Int,
    distinctPlaces: Int
):
  def totalDistanceM: Double = distanceByMode.values.sum
  def movingMs: Long = durationByMode.values.sum

final case class MonthSummary(
    month: YearMonth,
    distanceByMode: Map[TransportMode, Double],
    steps: Int,
    visits: Int,
    distinctPlaces: Int,
    activeDays: Int
)

object Stats:

  def day(date: LocalDate, segments: Seq[Segment], steps: Int): DaySummary =
    val legs = segments.collect { case t: Trip => t.legs }.flatten.filter(_.mode != TransportMode.Still)
    val visits = segments.collect { case v: Visit => v }
    DaySummary(
      date,
      legs.groupMapReduce(_.mode)(_.distanceM)(_ + _),
      legs.groupMapReduce(_.mode)(_.durationMs)(_ + _),
      steps,
      visits.size,
      visits.flatMap(_.placeId).distinct.size
    )

  def month(ym: YearMonth, days: Seq[DaySummary], placeIds: Seq[Long]): MonthSummary =
    val ds = days.filter(d => YearMonth.from(d.date) == ym)
    MonthSummary(
      ym,
      ds.flatMap(_.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _),
      ds.map(_.steps).sum,
      ds.map(_.visits).sum,
      placeIds.distinct.size,
      ds.count(d => d.visits > 0 || d.totalDistanceM > 0)
    )

  def localDate(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate

  /** Consecutive days with at least one check-in, ending today or yesterday. */
  def dailyStreak(dates: Seq[LocalDate], today: LocalDate): Int =
    val set = dates.toSet
    val startDay = if set(today) then today else today.minusDays(1)
    Iterator.iterate(startDay)(_.minusDays(1)).takeWhile(set).size

  /** Consecutive ISO weeks with at least one check-in (Swarm-style), ending this or last week. */
  def weeklyStreak(dates: Seq[LocalDate], today: LocalDate): Int =
    def wk(d: LocalDate) = (d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    val weeks = dates.map(wk).toSet
    val start = if weeks(wk(today)) then today else today.minusWeeks(1)
    Iterator.iterate(start)(_.minusWeeks(1)).takeWhile(d => weeks(wk(d))).size

  /** Splits a segment list into calendar days, clipping segments that cross midnight. */
  def splitByDay(segments: Seq[Segment], zone: ZoneId): Map[LocalDate, Vector[Segment]] =
    val out = scala.collection.mutable.Map.empty[LocalDate, Vector[Segment]].withDefaultValue(Vector.empty)
    for s <- segments do
      var d = localDate(s.start, zone)
      val last = localDate(math.max(s.start, s.end - 1), zone)
      while !d.isAfter(last) do
        val ds = d.atStartOfDay(zone).toInstant.toEpochMilli
        val de = d.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
        out(d) :+= clip(s, ds, de)
        d = d.plusDays(1)
    out.toMap

  private def clip(s: Segment, from: Long, to: Long): Segment =
    val a = math.max(s.start, from)
    val b = math.min(s.end, to)
    if a == s.start && b == s.end then s
    else
      s match
        case v: Visit => v.copy(start = a, end = b)
        case t: Trip =>
          val frac = (b - a).toDouble / math.max(1L, t.end - t.start)
          t.copy(
            start = a,
            end = b,
            path = t.path.filter(f => f.time >= a && f.time <= b),
            legs = t.legs.filter(l => l.end > a && l.start < b).map(l =>
              val la = math.max(l.start, a)
              val lb = math.min(l.end, b)
              val lf = (lb - la).toDouble / math.max(1L, l.end - l.start)
              l.copy(start = la, end = lb, distanceM = l.distanceM * lf, steps = (l.steps * lf).toInt)
            )
          )
        case other => other

  def daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
