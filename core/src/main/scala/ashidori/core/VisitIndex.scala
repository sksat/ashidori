package ashidori.core

import java.time.{Instant, LocalDate, ZoneId}

/** 「訪れた日」 of a venue: the days with a stay at it, from a per-day
  * spatial index of the stays. The index is derived data: it can be emptied
  * and built again at any time from the record, and nothing else reads it.
  *
  * Content: for each local day, its stays ([[Visit]]: centre, radius,
  * times) as the timeline builder makes them for the day view, and the
  * coarse cells ([[CellDeg]] square) each stay can be at a venue in: every
  * cell the box of its centre ± its reach ([[reach]]) touches. A venue's
  * days are then the stays registered in the venue's cell and its
  * neighbours, refined by [[at]] (the same reach, and the cells as
  * registered), read newest first a page of days at a time until
  * [[MaxDays]] are found ([[query]]).
  *
  * Freshness: the index remembers how far it has read the record
  * ([[Marks]]: the largest rowid of positions and motion, and the deletion
  * sequence; rowids of those tables only grow). New rows and deleted ranges
  * mark the days they can change ([[MarginMs]] on both sides, as a day is
  * built with that context) as changed: a generation ([[DayState.want]])
  * that the stored stays ([[DayState.built]]) no longer match. A deleted
  * range also removes the stays it overlaps at once. The marks are read in
  * bounded steps ([[note]]). Rows moved out of SQLite before they are read
  * there would be missed: so archiving marks a day and its neighbours
  * changed ([[archiving]]) both before it moves the day's rows and again
  * after its commit (a build in between, then a late row moved by the same
  * commit, is followed by another build). Today is marked
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
  /** Candidate days read at a time by [[query]]. */
  val QueryPage = 64
  /** Rowids of each table read per step of [[note]] (each step one short transaction). */
  val NoteStep = 20_000L
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

  /** How far from its centre a venue may be for the stay to be at it: as
    * [[VenueCheckIn.near]] (150 m, or its radius if larger), capped at
    * [[MaxReachM]]. The list uses the cap too ([[at]]): a stay of a larger
    * radius is at the venues within 2 km of its centre, not farther.
    */
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

  /** The stay is at the venue for the list: its centre within [[reach]],
    * and the venue's cell one the stay is registered in (the same thing but
    * near a pole, where [[cellsOf]] is cut: the list and the index agree).
    */
  def at(venue: LatLng, v: Visit): Boolean =
    v.latLng.distanceTo(venue) <= reach(v) && cellsOf(v).contains(cellOf(venue))

  /** The days of `rows` (day, stay) with a stay at `venue` ([[at]]), newest first, at most `max`. */
  def refine(venue: LatLng, rows: Seq[(LocalDate, Visit)], max: Int = MaxDays): Vector[DayVisit] =
    rows.filter((_, v) => at(venue, v)).distinct.groupBy(_._1).toVector
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
    /** The newest `limit` days before `before` with a stay registered in any of `cells`, newest first. */
    def candidateDays(cells: Seq[Long], before: Long, limit: Int): Vector[Long]
    /** The stays of `days` registered in any of `cells`, each once, with its day. */
    def candidates(cells: Seq[Long], days: Seq[Long]): Vector[(Long, Visit)]
    /** Empties the index: stays, cells, days and meta, but not the generation
      * nor the meta keys starting with "stale" ([[emptyIfStale]]).
      */
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

  /** Brings the index up to the record's marks ([[mark]]), [[NoteStep]]
    * rowids of each table per step, each step one short transaction, until
    * `stop`. An index of another [[Version]] or zone, or one whose marks are
    * ahead of the record (not this record: restored, or the data cleared),
    * is emptied first. Returns the days marked; the marks are up to date
    * unless stopped.
    */
  def note(store: Store, source: Source, zone: ZoneId, stop: () => Boolean = () => false): Set[Long] =
    def current: Option[Marks] =
      marksOf(store).filter(_ => store.meta("version").contains(Version.toString) && store.meta("zone").contains(zone.getId))
    // The record's marks read under the index's write lock: marks another
    // process stored before are never ahead of them (rowids only grow).
    var now: Marks = null
    val ok = store.transaction {
      now = source.marks()
      current match
        case Some(m) if !now.behind(m) => true
        case _ =>
          store.clear()
          store.setMeta("version", Version.toString)
          store.setMeta("zone", zone.getId)
          setMarks(store, now)
          false
    }
    var days = Set.empty[Long]
    var go = ok
    while go && !stop() do
      // Read again in each step: the other process may have moved them on.
      go = store.transaction {
        current match
          case Some(m) if m.fixes < now.fixes || m.motion < now.motion || m.deletions < now.deletions =>
            val next = Marks(math.min(now.fixes, m.fixes + NoteStep), math.min(now.motion, m.motion + NoteStep),
              math.max(m.deletions, now.deletions))
            days ++= mark(store, source.changes(m, next), zone)
            setMarks(store, next)
            true
          case _ => false
      }
    days

  /** Marks `days` changed (those with a state), in one transaction. */
  def touchDays(store: Store, days: Seq[LocalDate]): Unit =
    store.transaction {
      val states = days.flatMap(d => store.day(d.toEpochDay).map(d.toEpochDay -> _))
      if states.nonEmpty then
        val g = store.nextGen()
        for (d, s) <- states do store.setDay(d, DayState(g, s.built))
    }

  /** Today is built again whenever asked for: marked changed (if built). */
  def touch(store: Store, today: LocalDate): Unit = touchDays(store, Seq(today))

  /** Archiving moves rows of `day` out of SQLite: that day and the days
    * whose build reads it ([[MarginMs]]) are marked changed. Rows the marks
    * have not read yet would otherwise leave SQLite unseen. Called before
    * the move and again after its commit: a build between the two may have
    * read the day before a late row came and left with the same commit; the
    * mark after it makes the day built again from what is left. It may mark
    * days that did not change (built again once), never miss one.
    */
  def archiving(store: Store, day: LocalDate): Unit =
    touchDays(store, Seq(day.minusDays(1), day, day.plusDays(1)))

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

  /** Days of [first, today] not up to date: never built, or changed since built. */
  def stale(store: Store, first: LocalDate, today: LocalDate): Int = pending(store, first, today).size

  /** What the list says under its days. */
  enum Footer:
    /** 「以前の日は集計中」: days are being built, some never built yet. */
    case Building
    /** 「なし」: every day is up to date and none has a stay at the venue. */
    case NoDays
    /** 「読み込めません」, or 「一部の日を読み込めません」 under days shown, with 「再試行」. */
    case Failed(someShown: Boolean)
    case Nothing

  /** The footer: `answered`, the index was read; `shown`, days listed;
    * `unbuilt`, `stale`: days never built, and not up to date (changed
    * since built included); `building`: runs go on; `trouble`: noting the
    * record's changes or a run failed. A day still not up to date once the
    * runs end could not be built: a failure, not "no days" and not the
    * old result as if current.
    */
  def footer(answered: Boolean, shown: Int, unbuilt: Int, stale: Int, building: Boolean, trouble: Boolean): Footer =
    if !answered then Footer.Failed(shown > 0)
    else if building then (if unbuilt > 0 then Footer.Building else Footer.Nothing)
    else if trouble || stale > 0 then Footer.Failed(shown > 0)
    else if shown == 0 then Footer.NoDays
    else Footer.Nothing

  /** A run of [[build]]: days built, days that failed to read (`failed`),
    * and days still to build after it (`left`, the failed ones included).
    */
  final case class Run(built: Int, failed: Vector[LocalDate], left: Int)

  /** Builds up to `maxDays` of the [[pending]] days, newest first, until
    * `stop`. `skip`: days not to try (failed before in this session).
    */
  def build(store: Store, source: Source, zone: ZoneId, today: LocalDate, maxDays: Int,
            stop: () => Boolean = () => false, skip: Set[LocalDate] = Set.empty): Run =
    note(store, source, zone, stop)
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

  /** The days with a stay at `venue` that the index holds, newest first, at
    * most `max`: candidate days read newest first, [[QueryPage]] at a time,
    * until `max` are found (the cost does not grow with the years kept).
    */
  def query(store: Store, venue: LatLng, max: Int = MaxDays): Vector[DayVisit] =
    val cells = neighbourhood(cellOf(venue)).map(_.key)
    val out = Vector.newBuilder[DayVisit]
    var found = 0
    var before = Long.MaxValue
    var more = true
    while more && found < max do
      val days = store.candidateDays(cells, before, QueryPage)
      if days.isEmpty then more = false
      else
        val page = refine(venue, store.candidates(cells, days).map((d, v) => (LocalDate.ofEpochDay(d), v)), max - found)
        out ++= page
        found += page.size
        before = days.min
        more = days.size == QueryPage
    out.result()

  // --- what must outlive a crash: the archiving note and the stale marker -------------------

  /** Small files beside the index, written durably (each write may throw).
    *
    *   - The stale marker: a generation; the index is of unknown freshness
    *     while it is above the generation a full rebuild consumed.
    *   - The archiving note: the day being archived, from before its rows
    *     move to after the mark after the commit (corrupt: `Left`).
    *   - The archiving sequence: one more for every archiving begun.
    *   - "Active": an archiving is under way (a lock its process holds,
    *     released by a crash).
    */
  trait Beside:
    def staleGen: Long
    def setStaleGen(g: Long): Unit
    def doneGen: Long
    def setDoneGen(g: Long): Unit
    def archiving: Option[Either[String, LocalDate]]
    def setArchiving(day: LocalDate): Unit
    def clearArchiving(): Unit
    def archivingSeq: Long
    def setArchivingSeq(n: Long): Unit
    def archivingActive: Boolean
    def holdArchiving(): Unit
    def releaseArchiving(): Unit

  def isStale(b: Beside): Boolean = b.staleGen > b.doneGen

  /** A new stale generation. False if it could not be written. */
  def markStale(b: Beside): Boolean =
    scala.util.Try(b.setStaleGen(math.max(b.staleGen, b.doneGen) + 1)).isSuccess

  /** No archiving under way, none noted (a crash in one leaves its note). */
  private def quiet(b: Beside): Boolean = !b.archivingActive && b.archiving.isEmpty

  /** Before a use, under the index's write lock. A corrupt archiving note
    * makes the index stale. A stale index is emptied (to be built whole)
    * once per marker generation, and again if it was emptied while an
    * archiving was under way or noted, or one has begun since, and now
    * none is: only a rebuild begun quiet can consume the marker
    * ([[consumeStale]]).
    */
  def emptyIfStale(store: Store, b: Beside): Unit =
    store.transaction {
      if b.archiving.exists(_.isLeft) then markStale(b)
      if isStale(b) then
        val g = b.staleGen.toString
        val seq = b.archivingSeq.toString
        val ok = quiet(b)
        val begun = store.meta("staleGen").contains(g)
        val begunQuiet = begun && store.meta("staleQuiet").contains("1") && store.meta("staleSeq").contains(seq)
        if !begun || (!begunQuiet && ok) then
          store.clear()
          store.setMeta("staleGen", g)
          store.setMeta("staleQuiet", if ok then "1" else "0")
          store.setMeta("staleSeq", seq)
    }

  /** After a run that left nothing to build: the marker generation the
    * rebuild began under is consumed, if it began quiet, no archiving has
    * begun since, and none is under way or noted now. True if consumed.
    */
  def consumeStale(store: Store, b: Beside, first: LocalDate, today: LocalDate): Boolean =
    store.transaction {
      val g = b.staleGen
      val ok = isStale(b) && store.meta("staleGen").contains(g.toString) && store.meta("staleQuiet").contains("1") &&
        store.meta("staleSeq").contains(b.archivingSeq.toString) && quiet(b) && pending(store, first, today).isEmpty
      ok && scala.util.Try(b.setDoneGen(g)).isSuccess
    }

  /** Marks `day` and its neighbours ([[archiving]]); false if that failed. */
  private def markArchiving(store: Store, day: LocalDate): Boolean = scala.util.Try(archiving(store, day)).isSuccess

  /** Before archiving `day`: held active, the sequence moved on, the day
    * noted, then marked. A failed write makes the index stale. Never throws.
    */
  def beforeArchiving(store: Store, b: Beside, day: LocalDate): Unit =
    val held = scala.util.Try(b.holdArchiving()).isSuccess
    val noted = held && scala.util.Try { b.setArchivingSeq(b.archivingSeq + 1); b.setArchiving(day) }.isSuccess
    if !noted then markStale(b)
    if !markArchiving(store, day) then markStale(b)

  /** After archiving `day` (done, failed or stopped): marked again, then the
    * note removed and the hold released. Never throws.
    */
  def afterArchiving(store: Store, b: Beside, day: LocalDate): Unit =
    try
      if markArchiving(store, day) then scala.util.Try(b.clearArchiving())
      else markStale(b)
    finally scala.util.Try(b.releaseArchiving())

  /** In the maintenance process once recovery has finished a commit (and
    * after 「整理を修復」): a note left by a crash is settled: its day
    * marked, or the index made stale if the note cannot be read; then the
    * note removed (kept if neither could be written).
    */
  def settleArchiving(store: Store, b: Beside): Unit =
    b.archiving match
      case None              => ()
      case Some(Right(day))  => if markArchiving(store, day) || markStale(b) then scala.util.Try(b.clearArchiving())
      case Some(Left(_))     => if markStale(b) then scala.util.Try(b.clearArchiving())

  /** In the main process when the list opens: a noted day marked (the note
    * stays for the maintenance process), a corrupt note makes it stale.
    */
  def noteArchiving(store: Store, b: Beside): Unit =
    b.archiving match
      case Some(Right(day)) => if !markArchiving(store, day) then markStale(b)
      case Some(Left(_))    => markStale(b)
      case None             => ()
