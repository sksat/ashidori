package ashidori.core

import java.time.{Instant, LocalDate, ZoneId}

/** 「訪れた日」 of a venue: the days with a stay at it, from a per-day
  * spatial index of the stays. The index is derived data: it can be emptied
  * and built again at any time from the record, and nothing else reads it.
  *
  * Content: for each local day, its stays ([[Visit]]: centre, radius,
  * times) as the timeline builder makes them for the day view, and the
  * coarse cells ([[CellDeg]] square) each stay can be "at a venue" in
  * ([[VenueCheckIn.near]]): every cell the box of its centre ± its reach
  * ([[reach]]) touches. A venue's days are then the stays registered in the
  * venue's cell and its neighbours, refined by the exact distance
  * ([[refine]]).
  *
  * Freshness: the index remembers how far it has read the record
  * ([[Marks]]: the largest rowid of positions and motion, and the deletion
  * sequence; rowids of those tables only grow). New rows and deleted ranges
  * mark the days they can change ([[MarginMs]] on both sides, as a day is
  * built with that context) as changed: a generation ([[DayState.want]])
  * that the stored stays ([[DayState.built]]) no longer match. A deleted
  * range also removes the stays it overlaps at once. Today is marked
  * changed whenever the list is opened ([[touch]]). Days are built newest
  * first ([[pending]]), changed days before never-built ones, a bounded
  * number per run ([[build]]); a day's stays are stored only if no change
  * marked it while it was being built.
  */
object VisitIndex:
  /** The side of a cell in degrees (about 220 m north–south, 180 m east–west at 35°). */
  val CellDeg = 0.002
  /** A stay is registered no farther than this from its centre (bounds the cells of a huge radius). */
  val MaxReachM = 2_000.0
  /** Days listed at most. */
  val MaxDays = 50
  /** Context read on both sides of a day to build it (as the day view's). */
  val MarginMs: Long = 12 * 3_600_000L
  /** The layout and derivation of the stored index: another value empties it. */
  val Version = 1

  private val Rows = math.round(180 / CellDeg).toInt
  private val Cols = math.round(360 / CellDeg).toInt
  private val MaxWidth = 2_048
  /** Metres per degree of latitude, rounded down: the boxes come out a little larger. */
  private val MPerDeg = 110_000.0

  final case class Cell(y: Int, x: Int):
    def key: Long = y.toLong * Cols + x

  def cellOf(lat: Double, lon: Double): Cell =
    val y = math.floor((lat + 90) / CellDeg).toInt.max(0).min(Rows - 1)
    val x = Math.floorMod(math.floor((lon + 180) / CellDeg).toInt, Cols)
    Cell(y, x)

  def cellOf(p: LatLng): Cell = cellOf(p.lat, p.lon)

  /** The cell and its eight neighbours (across the 180° meridian; none beyond a pole). */
  def neighbourhood(c: Cell): Vector[Cell] =
    (for dy <- -1 to 1; dx <- -1 to 1 if c.y + dy >= 0 && c.y + dy < Rows
    yield Cell(c.y + dy, Math.floorMod(c.x + dx, Cols))).toVector.distinct

  /** How far from its centre a venue may be for the stay to be at it ([[VenueCheckIn.near]]), capped at [[MaxReachM]]. */
  def reach(v: Visit): Double = math.min(math.max(VenueCheckIn.StayM, v.radius), MaxReachM)

  /** The cells a stay is registered in: every cell the box of its centre ±
    * [[reach]] touches. A venue the stay is at lies in the box, so its cell
    * is one of them.
    */
  def cellsOf(v: Visit): Vector[Cell] =
    val r = reach(v) + 1.0
    val dLat = r / MPerDeg
    val maxLat = math.min(89.99, math.abs(v.lat) + dLat)
    val dLon = math.min(180.0, r / (MPerDeg * math.cos(math.toRadians(maxLat))))
    val south = cellOf(v.lat - dLat, v.lon)
    val north = cellOf(v.lat + dLat, v.lon)
    val west = cellOf(v.lat, v.lon - dLon).x
    val east = cellOf(v.lat, v.lon + dLon).x
    // Near a pole the box would span most of the world: at most MaxWidth
    // cells (all of them up to about 89.5° of latitude).
    val width = math.min(MaxWidth, if dLon >= 180.0 then Cols else Math.floorMod(east - west, Cols) + 1)
    (for y <- south.y to north.y; i <- 0 until width yield Cell(y, Math.floorMod(west + i, Cols))).toVector

  // --- the list -----------------------------------------------------------------------------

  /** A day the venue was visited: its stays at the venue, in time order. */
  final case class DayVisit(day: LocalDate, stays: Vector[Visit]):
    def start: Long = stays.head.start
    def end: Long = stays.last.end
    def durationMs: Long = stays.map(_.durationMs).sum

  /** The days of `rows` (day, stay) with a stay at `venue`, newest first, at most `max`. */
  def refine(venue: LatLng, rows: Seq[(LocalDate, Visit)], max: Int = MaxDays): Vector[DayVisit] =
    rows.filter((_, v) => VenueCheckIn.near(venue, v)).distinct.groupBy(_._1).toVector
      .sortBy((d, _) => -d.toEpochDay).take(max)
      .map((d, vs) => DayVisit(d, vs.map(_._2).sortBy(_.start).toVector))

  // --- storage ------------------------------------------------------------------------------

  /** How far the index has read the record: the largest rowid of the
    * positions and of the motion windows, and the deletion sequence.
    */
  final case class Marks(fixes: Long, motion: Long, deletions: Long):
    /** The record is behind these marks: not the one they were read from. */
    def behind(read: Marks): Boolean = fixes < read.fixes || motion < read.motion || deletions < read.deletions

  /** A day of the index: `want`, the generation of its last change; `built`,
    * the generation its stored stays were built for (None: never built).
    */
  final case class DayState(want: Long, built: Option[Long]):
    def fresh: Boolean = built.contains(want)

  /** What changed in the record between two marks: the hours (time /
    * 3,600,000) with new positions or motion, and the ranges deleted.
    */
  final case class Changes(hours: Set[Long], deleted: Seq[(Long, Long)])

  /** The index's storage. Days are epoch days of the local date. */
  trait Store:
    /** `f` in one write transaction. */
    def transaction[A](f: => A): A
    def meta(key: String): Option[String]
    def setMeta(key: String, value: String): Unit
    /** The next generation: larger than every one given before (also across [[clear]]). */
    def nextGen(): Long
    def day(d: Long): Option[DayState]
    def setDay(d: Long, s: DayState): Unit
    /** The days of [from, to] that have a state. */
    def days(from: Long, to: Long): Map[Long, DayState]
    /** Replaces the stays of day `d` (each with the keys of its cells). */
    def putStays(d: Long, stays: Seq[(Visit, Seq[Long])]): Unit
    /** Removes the stays that overlap [from, to). */
    def removeStays(from: Long, to: Long): Unit
    /** The stays registered in any of `cells`, each once, with its day. */
    def candidates(cells: Seq[Long]): Vector[(Long, Visit)]
    /** Empties the index: stays, cells, days and meta (not the generation). */
    def clear(): Unit

  /** The record, read through its safe readers. */
  trait Source:
    def marks(): Marks
    def changes(from: Marks, to: Marks): Changes
    /** The first day with a position. */
    def firstDay(): Option[LocalDate]
    /** The stays of `day`, as the day view builds them. */
    def stays(day: LocalDate): Vector[Visit]

  private def marksOf(s: Store): Option[Marks] =
    for f <- s.meta("fixes").flatMap(_.toLongOption); m <- s.meta("motion").flatMap(_.toLongOption)
        d <- s.meta("deletions").flatMap(_.toLongOption) yield Marks(f, m, d)

  private def setMarks(s: Store, m: Marks): Unit =
    s.setMeta("fixes", m.fixes.toString)
    s.setMeta("motion", m.motion.toString)
    s.setMeta("deletions", m.deletions.toString)

  private def localDay(t: Long, zone: ZoneId): Long = Instant.ofEpochMilli(t).atZone(zone).toLocalDate.toEpochDay

  /** The days whose build reads something of [from, to) (with [[MarginMs]] on both sides). */
  def daysTouching(from: Long, to: Long, zone: ZoneId): (Long, Long) =
    (localDay(from - MarginMs, zone), localDay(math.max(from, to + MarginMs - 1), zone))

  /** Marks the days `ch` touches as changed (only days with a state:
    * others are not built yet), and removes the stays of deleted ranges.
    * Returns the days marked.
    */
  def mark(store: Store, ch: Changes, zone: ZoneId): Set[Long] =
    for (a, b) <- ch.deleted do store.removeStays(a, b)
    val spans = ch.hours.toSeq.map(h => (h * 3_600_000L, (h + 1) * 3_600_000L)) ++ ch.deleted
    val touched = spans.map((a, b) => daysTouching(a, b, zone))
    // Merged spans: a long deletion is one range of days, not one per hour.
    val merged = touched.sortBy(_._1).foldLeft(List.empty[(Long, Long)]) {
      case ((a, b) :: rest, (c, d)) if c <= b + 1 => (a, math.max(b, d)) :: rest
      case (acc, x)                               => x :: acc
    }
    val days = merged.flatMap((a, b) => store.days(a, b).keys).toSet
    if days.nonEmpty then
      val g = store.nextGen()
      for d <- days do store.setDay(d, DayState(g, store.day(d).flatMap(_.built)))
    days

  /** Brings the index up to the record's marks ([[mark]]). An index of
    * another [[Version]] or zone, or one whose marks are ahead of the record
    * (not this record: restored, or the data cleared), is emptied first.
    * Returns the days marked.
    */
  def note(store: Store, source: Source, zone: ZoneId): Set[Long] =
    store.transaction {
      val now = source.marks()
      val mine = marksOf(store).filter(_ =>
        store.meta("version").contains(Version.toString) && store.meta("zone").contains(zone.getId))
      mine match
        case Some(m) if !now.behind(m) =>
          if m == now then Set.empty
          else
            val days = mark(store, source.changes(m, now), zone)
            setMarks(store, now)
            days
        case _ =>
          store.clear()
          store.setMeta("version", Version.toString)
          store.setMeta("zone", zone.getId)
          setMarks(store, now)
          Set.empty
    }

  /** Today is built again whenever asked for: marked changed (if built). */
  def touch(store: Store, today: LocalDate): Unit =
    store.transaction {
      store.day(today.toEpochDay).foreach(s => store.setDay(today.toEpochDay, DayState(store.nextGen(), s.built)))
    }

  /** Days to build, newest first: changed days first, then never-built ones. */
  def pending(store: Store, first: LocalDate, today: LocalDate): Vector[LocalDate] =
    if first.isAfter(today) then Vector.empty
    else
      val states = store.days(first.toEpochDay, today.toEpochDay)
      val all = (today.toEpochDay to first.toEpochDay by -1).toVector
      val changed = all.filter(d => states.get(d).exists(s => s.built.isDefined && !s.fresh))
      val never = all.filter(d => states.get(d).forall(_.built.isEmpty))
      (changed ++ never).map(LocalDate.ofEpochDay)

  /** Days of [first, today] never built. */
  def unbuilt(store: Store, first: LocalDate, today: LocalDate): Int =
    if first.isAfter(today) then 0
    else
      val built = store.days(first.toEpochDay, today.toEpochDay).count(_._2.built.isDefined)
      (today.toEpochDay - first.toEpochDay + 1).toInt - built

  /** A run of [[build]]: days built, days that failed to read (`failed`),
    * and days still to build after it (`left`, the failed ones included).
    */
  final case class Run(built: Int, failed: Vector[LocalDate], left: Int)

  /** Builds up to `maxDays` of the [[pending]] days, newest first, until
    * `stop`. `skip`: days not to try (failed before in this session).
    */
  def build(store: Store, source: Source, zone: ZoneId, today: LocalDate, maxDays: Int,
            stop: () => Boolean = () => false, skip: Set[LocalDate] = Set.empty): Run =
    note(store, source, zone)
    val first = source.firstDay().getOrElse(today)
    val todo = pending(store, first, today).filterNot(skip)
    var built = 0
    val failed = Vector.newBuilder[LocalDate]
    for d <- todo.take(maxDays) if !stop() do
      val e = d.toEpochDay
      val want = store.transaction {
        store.day(e).map(_.want).getOrElse {
          val g = store.nextGen()
          store.setDay(e, DayState(g, None))
          g
        }
      }
      scala.util.Try(source.stays(d)).toOption match
        case Some(stays) =>
          store.transaction {
            // A change marked meanwhile (or the index emptied): built again later.
            if store.day(e).exists(_.want == want) then
              store.putStays(e, stays.map(v => (v, cellsOf(v).map(_.key))))
              store.setDay(e, DayState(want, Some(want)))
          }
          built += 1
        case None => failed += d
    Run(built, failed.result(), pending(store, first, today).size)

  /** The days with a stay at `venue` that the index holds, newest first, at most `max`. */
  def query(store: Store, venue: LatLng, max: Int = MaxDays): Vector[DayVisit] =
    val rows = store.candidates(neighbourhood(cellOf(venue)).map(_.key))
    refine(venue, rows.map((d, v) => (LocalDate.ofEpochDay(d), v)), max)
