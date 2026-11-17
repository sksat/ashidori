package ashidori.core

import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import java.time.temporal.{ChronoUnit, IsoFields}

final case class DaySummary(
    date: LocalDate,
    distanceByMode: Map[TransportMode, Double],
    durationByMode: Map[TransportMode, Long],
    steps: Int,
    visits: Int,
    distinctPlaces: Int,
    /** time without a record ([[Unrecorded]]), not part of the totals */
    unrecordedMs: Long = 0
):
  /** Distance travelled: trips only, never a stretch without a record. */
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

  /** A day's totals. Stretches without a record ([[Unrecorded]]) add only
    * to [[DaySummary.unrecordedMs]]: how the user got from one end to the
    * other is unknown, so they count as neither distance nor travel time.
    */
  def day(date: LocalDate, segments: Seq[Segment], steps: Int): DaySummary =
    val legs = segments.collect { case t: Trip => t.legs }.flatten.filter(_.mode != TransportMode.Still)
    val visits = segments.collect { case v: Visit => v }
    DaySummary(
      date,
      legs.groupMapReduce(_.mode)(_.distanceM)(_ + _),
      legs.groupMapReduce(_.mode)(_.durationMs)(_ + _),
      steps,
      visits.size,
      visits.flatMap(_.placeId).distinct.size,
      segments.collect { case u: Unrecorded => u.durationMs }.sum
    )

  /** A day's segments as shown: a day with nothing but stretches without a
    * record (the phone off from the day before to the day after) is a day
    * without a record, like a day with no data at all. The web viewer does
    * the same (`daySegments`).
    */
  def shown(segments: Vector[Segment]): Vector[Segment] =
    if segments.forall(_.isInstanceOf[Unrecorded]) then Vector.empty else segments

  /** A day without a fix of its own that lies inside a stretch without a
    * record: there is a fix before `dayStart` and one at or after `dayEnd`,
    * however far away (`firstFix`, `lastFix`: the first and last fix of the
    * whole record), and the record does not say the user stopped recording
    * then (`cause`, from the lifecycle log when it still holds that time).
    * The calendar marks such days as recorded only in part.
    */
  def silentDay(dayStart: Long, dayEnd: Long, firstFix: Option[Long], lastFix: Option[Long], cause: Option[GapCause]): Boolean =
    firstFix.exists(_ < dayStart) && lastFix.exists(_ >= dayEnd) && !cause.contains(GapCause.Stopped)

  /** Time of `[from, to)` that no stay or trip covers: stretches without a
    * record and the part of the day before the first or after the last one.
    */
  def missingMs(segments: Seq[Segment], from: Long, to: Long): Long =
    val covered = segments.filterNot(_.isInstanceOf[Unrecorded])
      .map(s => (math.max(s.start, from), math.min(s.end, to))).filter((a, b) => b > a).sortBy(_._1)
    var total = 0L
    var cursor = from
    for (a, b) <- covered do
      if b > cursor then
        total += b - math.max(a, cursor)
        cursor = b
    math.max(0L, to - from) - total

  /** A day recorded only in part (the calendar's grey dot): at least
    * `minMissingMs` of it without a record. `to` is the end of the day, or
    * now for today.
    */
  def partial(segments: Seq[Segment], from: Long, to: Long, minMissingMs: Long = 3_600_000L): Boolean =
    segments.nonEmpty && missingMs(segments, from, to) >= minMissingMs

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
        case u: Unrecorded => u.copy(start = a, end = b)
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
              l.copy(start = la, end = lb, distanceM = l.distanceM * lf, steps = (l.steps * lf).toInt,
                whole = Some((l.wholeStart, l.wholeEnd)))
            )
          )
        case other => other

  def daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
