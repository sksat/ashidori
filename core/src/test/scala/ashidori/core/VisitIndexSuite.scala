package ashidori.core

import java.time.{LocalDate, ZoneId}
import scala.collection.mutable
import VisitIndex.*

class VisitIndexSuite extends munit.FunSuite:
  private val zone = ZoneId.of("Asia/Tokyo")
  private val H = 3_600_000L
  private val venue = LatLng(35.6812, 139.7671)
  private def north(p: LatLng, m: Double) = LatLng(p.lat + m / 111_195.0, p.lon)
  private def east(p: LatLng, m: Double) = LatLng(p.lat, p.lon + m / (111_195.0 * math.cos(math.toRadians(p.lat))))
  private def startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant.toEpochMilli
  private def stay(d: LocalDate, at: LatLng, fromH: Double, hours: Double, radius: Double = 30) =
    Visit(startOf(d) + (fromH * H).toLong, startOf(d) + ((fromH + hours) * H).toLong, at.lat, at.lon, radius, 20)
  private val today = LocalDate.of(2026, 11, 18)

  /** The index in memory. */
  final class FakeStore extends Store:
    val meta = mutable.Map.empty[String, String]
    val dayStates = mutable.Map.empty[Long, DayState]
    val stays = mutable.Map.empty[Long, Vector[(Visit, Seq[Long])]]
    var gen = 0L
    def transaction[A](f: => A): A = f
    def meta(key: String): Option[String] = meta.get(key)
    def setMeta(key: String, value: String): Unit = meta(key) = value
    def nextGen(): Long = { gen += 1; gen }
    def day(d: Long): Option[DayState] = dayStates.get(d)
    def setDay(d: Long, s: DayState): Unit = dayStates(d) = s
    def days(from: Long, to: Long): Map[Long, DayState] = dayStates.filter((d, _) => d >= from && d <= to).toMap
    def putStays(d: Long, ss: Seq[(Visit, Seq[Long])]): Unit = stays(d) = ss.toVector
    def removeStays(from: Long, to: Long): Unit =
      for (d, ss) <- stays do stays(d) = ss.filterNot((v, _) => v.start < to && v.end > from)
    var pagesRead = 0
    def candidateDays(cells: Seq[Long], before: Long, limit: Int): Vector[Long] =
      pagesRead += 1
      stays.toVector.collect { case (d, ss) if d < before && ss.exists((_, cs) => cs.exists(cells.contains)) => d }
        .sorted.reverse.take(limit)
    def candidates(cells: Seq[Long], days: Seq[Long]): Vector[(Long, Visit)] =
      stays.toVector.filter((d, _) => days.contains(d))
        .flatMap((d, ss) => ss.collect { case (v, cs) if cs.exists(cells.contains) => (d, v) })
    def clear(): Unit = { meta.filterInPlace((k, _) => k.startsWith("stale")); dayStates.clear(); stays.clear() }

  /** A record: stays per day; position rows (rowid -> time) in "SQLite"
    * until archived; deleted ranges in sequence.
    */
  class FakeSource(var first: Option[LocalDate]) extends Source:
    val stays = mutable.Map.empty[LocalDate, Vector[Visit]]
    val rows = mutable.Map.empty[Long, Long]
    var high = 0L
    val deletions = mutable.ArrayBuffer.empty[(Long, Long)]
    /** Marks as if of another record. */
    var forced: Option[Marks] = None
    val reads = mutable.ArrayBuffer.empty[LocalDate]
    var failing = Set.empty[LocalDate]
    var changesRead = 0
    def marks(): Marks = forced.getOrElse(Marks(high, 0, deletions.size))
    def changes(from: Marks, to: Marks): Changes =
      changesRead += 1
      Changes(rows.collect { case (id, t) if id > from.fixes && id <= to.fixes => t / H }.toSet,
        deletions.slice(from.deletions.toInt, to.deletions.toInt).toSeq)
    /** Archiving moves the rows of `d` out of "SQLite". */
    def archive(d: LocalDate): Unit = rows.filterInPlace((_, t) => t < startOf(d) || t >= startOf(d.plusDays(1)))
    def firstDay(): Option[LocalDate] = first
    def stays(day: LocalDate): Vector[Visit] =
      reads += day
      if failing(day) then throw IllegalStateException("cannot read")
      stays.getOrElse(day, Vector.empty)
    /** New rows at `times` (and deleted ranges): the marks move on. */
    def add(times: Seq[Long], deleted: Seq[(Long, Long)] = Nil): Unit =
      for t <- times do
        high += 1
        rows(high) = t
      deletions ++= deleted

  private def fixture(days: Int = 10) =
    val src = FakeSource(Some(today.minusDays(days - 1)))
    (FakeStore(), src)

  // --- cells -------------------------------------------------------------------------------

  test("cells: about 0.002° square, floor at negative coordinates, the 180° meridian wraps") {
    val c = cellOf(35.6812, 139.7671)
    assertEquals(cellOf(35.6812 + 0.0005, 139.7671 + 0.0005), c)
    assertNotEquals(cellOf(35.6812 + 0.002, 139.7671), c)
    assertEquals(cellOf(-0.0001, -0.0001), Cell(44_999, 89_999))
    assertEquals(cellOf(0.0001, 0.0001), Cell(45_000, 90_000))
    assertEquals(cellOf(10, 180.0), cellOf(10, -180.0))
    assertEquals(cellOf(10, 179.9999).x, 179_999)
    assertEquals(cellOf(90, 0).y, 89_999)
  }

  test("the neighbourhood: nine cells, across the 180° meridian, none beyond a pole") {
    val c = cellOf(venue)
    val n = neighbourhood(c)
    assertEquals(n.size, 9)
    assert(n.contains(c))
    assert(n.forall(x => math.abs(x.y - c.y) <= 1))
    val edge = neighbourhood(cellOf(10, 179.9999))
    assert(edge.exists(_.x == 0))
    assertEquals(neighbourhood(cellOf(90, 0)).size, 6)
    assertEquals(neighbourhood(cellOf(10, 0)).map(_.key).distinct.size, 9)
  }

  test("a stay is registered in every cell of its reach: 150 m, or a larger radius, at most 2 km") {
    val d = today
    val small = cellsOf(stay(d, venue, 9, 1, radius = 20))
    // 150 m + margin in either direction: 2 or 3 cells each way at 35°.
    assert(small.size >= 4 && small.size <= 9, small.size.toString)
    val wide = cellsOf(stay(d, venue, 9, 1, radius = 600))
    assert(wide.size > small.size)
    val huge = cellsOf(stay(d, venue, 9, 1, radius = 50_000))
    assert(huge.size <= 25 * 25, huge.size.toString)
    // Near a pole the box is cut, never the whole world.
    assert(cellsOf(stay(d, LatLng(89.99, 0), 9, 1)).size <= 2_048 * 30)
  }

  test("index and brute force agree: random stays and venues, across the meridian and in the south") {
    val rnd = scala.util.Random(7)
    for base <- Seq(venue, LatLng(-33.86, 151.21), LatLng(64.1, -21.9), LatLng(0.0005, 179.9995)) do
      val stays = (0 until 400).map { i =>
        val p = east(north(base, rnd.between(-1500.0, 1500.0)), rnd.between(-1500.0, 1500.0))
        val r = if rnd.nextInt(10) == 0 then rnd.between(150.0, 900.0) else rnd.between(5.0, 140.0)
        val v = Visit(i * H, i * H + H, p.lat, ((p.lon + 540) % 360) - 180, r, 10)
        (LocalDate.ofEpochDay(20_000L + i % 120), v)
      }
      val keyed = stays.map((d, v) => (d, v, cellsOf(v).map(_.key).toSet))
      for _ <- 0 until 200 do
        val q = east(north(base, rnd.between(-1500.0, 1500.0)), rnd.between(-1500.0, 1500.0))
        val ven = LatLng(q.lat, ((q.lon + 540) % 360) - 180)
        val cells = neighbourhood(cellOf(ven)).map(_.key).toSet
        val viaIndex = refine(ven, keyed.filter((_, _, cs) => cs.exists(cells)).map((d, v, _) => (d, v)), Int.MaxValue)
        val brute = refine(ven, stays, Int.MaxValue)
        assertEquals(viaIndex, brute)
  }

  // --- refinement --------------------------------------------------------------------------

  test("refine: within 150 m of the centre or within the radius; newest first; one row per day; at most 50") {
    val d1 = today.minusDays(1)
    val d2 = today.minusDays(2)
    val rows = Seq(
      d2 -> stay(d2, north(venue, 140), 9, 1),
      d2 -> stay(d2, north(venue, 20), 18, 0.5),
      d1 -> stay(d1, north(venue, 160), 9, 1),
      d1 -> stay(d1, north(venue, 300), 12, 1, radius = 320),
      today -> stay(today, north(venue, 400), 9, 1))
    val r = refine(venue, rows)
    assertEquals(r.map(_.day), Vector(d1, d2))
    assertEquals(r(1).stays.map(_.start), Vector(startOf(d2) + 9 * H, startOf(d2) + 18 * H))
    assertEquals(r(1).durationMs, (1.5 * H).toLong)
    assertEquals(r(1).start, startOf(d2) + 9 * H)
    assertEquals(r(1).end, startOf(d2) + (18.5 * H).toLong)
    val many = (0 until 80).map(i => today.minusDays(i) -> stay(today.minusDays(i), venue, 9, 1))
    val top = refine(venue, many)
    assertEquals(top.size, 50)
    assertEquals(top.head.day, today)
    assertEquals(top.last.day, today.minusDays(49))
  }

  // --- building and freshness --------------------------------------------------------------

  test("a first build: newest first, a bounded number per run, until every day is built") {
    val (store, src) = fixture(10)
    src.stays(today.minusDays(3)) = Vector(stay(today.minusDays(3), venue, 10, 2))
    src.stays(today.minusDays(8)) = Vector(stay(today.minusDays(8), north(venue, 50), 10, 1))
    val r1 = build(store, src, zone, today, maxDays = 4)
    assertEquals(r1, Run(4, Vector.empty, 6))
    assertEquals(src.reads.toVector, (0 until 4).map(today.minusDays(_)).toVector)
    assertEquals(query(store, venue).map(_.day), Vector(today.minusDays(3)))
    assertEquals(unbuilt(store, today.minusDays(9), today), 6)
    val r2 = build(store, src, zone, today, maxDays = 100)
    assertEquals(r2, Run(6, Vector.empty, 0))
    assertEquals(query(store, venue).map(_.day), Vector(today.minusDays(3), today.minusDays(8)))
    assertEquals(build(store, src, zone, today, maxDays = 100), Run(0, Vector.empty, 0))
  }

  test("new rows mark the days whose build reads them (12 hours of context) as changed; those are built again first") {
    val (store, src) = fixture(10)
    build(store, src, zone, today, maxDays = 100)
    src.reads.clear()
    val d = today.minusDays(5)
    // 13:00 is in the context of the next day (from 12:00 of d), not of the day before.
    src.add(Seq(startOf(d) + 13 * H))
    assertEquals(note(store, src, zone), Set(d.toEpochDay, d.plusDays(1).toEpochDay))
    // 06:00 is in the context of the day before (to 12:00 of d).
    src.add(Seq(startOf(d) + 6 * H))
    assertEquals(note(store, src, zone), Set(d.minusDays(1).toEpochDay, d.toEpochDay))
    assertEquals(note(store, src, zone), Set.empty[Long])
    assertEquals(pending(store, today.minusDays(9), today), Vector(d.plusDays(1), d, d.minusDays(1)))
    src.stays(d) = Vector(stay(d, venue, 13, 1))
    build(store, src, zone, today, maxDays = 100)
    assertEquals(src.reads.toVector, Vector(d.plusDays(1), d, d.minusDays(1)))
    assertEquals(query(store, venue).map(_.day), Vector(d))
  }

  test("changed days keep their stays until built again; never-built days are not marked") {
    val (store, src) = fixture(10)
    val d = today.minusDays(2)
    src.stays(d) = Vector(stay(d, venue, 9, 1))
    build(store, src, zone, today, maxDays = 3)
    src.add(Seq(startOf(d) + 13 * H, startOf(today.minusDays(6)) + 13 * H))
    // Today-6 and today-5 are not built yet: not marked.
    assertEquals(note(store, src, zone), Set(d.toEpochDay, d.plusDays(1).toEpochDay))
    assertEquals(query(store, venue).map(_.day), Vector(d))
    assertEquals(pending(store, today.minusDays(9), today).take(2), Vector(d.plusDays(1), d))
  }

  test("a deleted range removes the stays it overlaps at once and marks its days") {
    val (store, src) = fixture(10)
    val d = today.minusDays(4)
    src.stays(d) = Vector(stay(d, venue, 9, 1), stay(d, north(venue, 30), 15, 1))
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).flatMap(_.stays).size, 2)
    src.add(Nil, deleted = Seq((startOf(d) + 9 * H + 30 * 60_000L, startOf(d) + 10 * H)))
    src.stays(d) = Vector(stay(d, north(venue, 30), 15, 1))
    assertEquals(note(store, src, zone), Set(d.toEpochDay, d.minusDays(1).toEpochDay))
    // Only the stay outside the range is left, before any build.
    assertEquals(query(store, venue).flatMap(_.stays).map(_.start), Vector(startOf(d) + 15 * H))
    src.reads.clear()
    build(store, src, zone, today, maxDays = 100)
    assertEquals(src.reads.toSet, Set(d, d.minusDays(1)))
  }

  test("a long deletion marks every built day in it, not one per hour") {
    val (store, src) = fixture(30)
    build(store, src, zone, today, maxDays = 100)
    src.add(Nil, deleted = Seq((startOf(today.minusDays(20)), startOf(today.minusDays(10)))))
    val marked = note(store, src, zone)
    assertEquals(marked.size, 12)
    assertEquals(marked.min, today.minusDays(21).toEpochDay)
    assertEquals(marked.max, today.minusDays(10).toEpochDay)
  }

  test("today is built again whenever touched") {
    val (store, src) = fixture(3)
    build(store, src, zone, today, maxDays = 100)
    src.stays(today) = Vector(stay(today, venue, 8, 1))
    assertEquals(query(store, venue), Vector.empty)
    touch(store, today)
    src.reads.clear()
    build(store, src, zone, today, maxDays = 100)
    assertEquals(src.reads.toVector, Vector(today))
    assertEquals(query(store, venue).map(_.day), Vector(today))
  }

  test("a change while a day is being built: its stays are not stored as fresh") {
    val (store, src) = fixture(1)
    val racing = new FakeSource(Some(today)):
      override def stays(day: LocalDate): Vector[Visit] =
        // Another process notes a change of this day meanwhile.
        store.setDay(day.toEpochDay, DayState(store.nextGen(), store.day(day.toEpochDay).flatMap(_.built)))
        Vector(stay(day, venue, 9, 1))
    val r = build(store, racing, zone, today, maxDays = 1)
    assertEquals(r.built, 1)
    assertEquals(query(store, venue), Vector.empty)
    assertEquals(pending(store, today, today), Vector(today))
    assertEquals(build(store, src, zone, today, maxDays = 1).left, 0)
  }

  test("a day that cannot be read is reported and skipped, the others go on") {
    val (store, src) = fixture(5)
    src.failing = Set(today.minusDays(1))
    val r = build(store, src, zone, today, maxDays = 100)
    assertEquals(r, Run(4, Vector(today.minusDays(1)), 1))
    src.reads.clear()
    val again = build(store, src, zone, today, maxDays = 100, skip = Set(today.minusDays(1)))
    assertEquals(again, Run(0, Vector.empty, 1))
    assertEquals(src.reads.toVector, Vector.empty)
  }

  test("an index of another version or zone, or ahead of the record, is emptied and built again") {
    val (store, src) = fixture(3)
    src.stays(today) = Vector(stay(today, venue, 8, 1))
    src.add(Seq(1L))
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).size, 1)
    // The record went back (another database): emptied.
    src.forced = Some(Marks(0, 0, 0))
    note(store, src, zone)
    assertEquals(query(store, venue), Vector.empty)
    assertEquals(unbuilt(store, today.minusDays(2), today), 3)
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).size, 1)
    src.forced = None
    note(store, src, zone)
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).size, 1)
    note(store, src, ZoneId.of("UTC"))
    assertEquals(query(store, venue), Vector.empty)
    build(store, src, ZoneId.of("UTC"), today, maxDays = 100)
    store.setMeta("version", "0")
    note(store, src, ZoneId.of("UTC"))
    assertEquals(query(store, venue), Vector.empty)
    // Generations keep growing across the emptying.
    val g = store.gen
    assertEquals(store.nextGen(), g + 1)
  }

  test("no record yet: nothing to build") {
    val store = FakeStore()
    val src = FakeSource(None)
    assertEquals(build(store, src, zone, today, maxDays = 10), Run(1, Vector.empty, 0))
    assertEquals(pending(store, today.plusDays(1), today), Vector.empty)
  }

  // --- review: no permanent misses, bounded steps, the cap, paging --------------------------

  test("a row added after the marks were read and archived before it is read: its days are built again") {
    val (store, src) = fixture(10)
    build(store, src, zone, today, maxDays = 100)
    val d = today.minusDays(4)
    // Without the mark before archiving the day is missed: the hazard.
    src.add(Seq(startOf(d) + 13 * H))
    src.archive(d)
    assertEquals(note(store, src, zone), Set.empty[Long])
    assertEquals(pending(store, today.minusDays(9), today), Vector.empty)
    // Archiving marks the day and its neighbours first: the row is never needed.
    src.add(Seq(startOf(d) + 13 * H))
    archiving(store, d)
    src.archive(d)
    note(store, src, zone)
    assertEquals(pending(store, today.minusDays(9), today), Vector(d.plusDays(1), d, d.minusDays(1)))
    src.stays(d) = Vector(stay(d, venue, 13, 1))
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).map(_.day), Vector(d))
  }

  test("the marks are read in bounded steps, each its own transaction, until stopped; then resumed") {
    val (store, src) = fixture(10)
    build(store, src, zone, today, maxDays = 100)
    val d = today.minusDays(6)
    src.add(Seq.fill((NoteStep * 2 + 5).toInt)(startOf(d) + 13 * H))
    src.changesRead = 0
    var steps = 0
    note(store, src, zone, stop = () => { steps += 1; steps > 1 })
    assertEquals(src.changesRead, 1)
    assertEquals(store.meta.get("fixes"), Some(NoteStep.toString))
    assertEquals(note(store, src, zone), Set(d.toEpochDay, d.plusDays(1).toEpochDay))
    assertEquals(src.changesRead, 3)
    assertEquals(store.meta.get("fixes"), Some((NoteStep * 2 + 5).toString))
  }

  test("a 2.5 km radius: registration and the list use the same 2 km reach") {
    val d = today
    val wide = stay(d, venue, 9, 1, radius = 2_500)
    val (store, src) = fixture(1)
    src.stays(d) = Vector(wide)
    build(store, src, zone, today, maxDays = 1)
    val in = north(venue, 1_950)
    val out = north(venue, 2_200)
    assertEquals(query(store, in).map(_.day), Vector(d))
    assertEquals(refine(in, Seq(d -> wide)).map(_.day), Vector(d))
    assertEquals(query(store, out), Vector.empty)
    assertEquals(refine(out, Seq(d -> wide)), Vector.empty)
    // The check-in's own test is not capped (a different question).
    assert(VenueCheckIn.near(out, wide))
  }

  test("near a pole the cut registration and the list agree with brute force") {
    val rnd = scala.util.Random(11)
    val base = LatLng(89.8, 30)
    val stays = (0 until 200).map { i =>
      val p = east(north(base, rnd.between(-3000.0, 3000.0)), rnd.between(-3000.0, 3000.0))
      (LocalDate.ofEpochDay(20_000L + i % 50), Visit(i * H, i * H + H, p.lat.min(89.999), p.lon, rnd.between(5.0, 2_500.0), 10))
    }
    val keyed = stays.map((d, v) => (d, v, cellsOf(v).map(_.key).toSet))
    for _ <- 0 until 100 do
      val q = east(north(base, rnd.between(-3000.0, 3000.0)), rnd.between(-3000.0, 3000.0))
      val ven = LatLng(q.lat.min(89.999), q.lon)
      val cells = neighbourhood(cellOf(ven)).map(_.key).toSet
      assertEquals(refine(ven, keyed.filter((_, _, cs) => cs.exists(cells)).map((d, v, _) => (d, v)), Int.MaxValue),
        refine(ven, stays, Int.MaxValue))
  }

  test("the query reads candidate days newest first, a page at a time, and stops at 50") {
    val (store, src) = fixture(400)
    for i <- 0 until 400 do
      val d = today.minusDays(i)
      // Every day a stay nearby (a candidate); every other day one at the venue.
      src.stays(d) = Vector(stay(d, north(venue, 200), 8, 1)) ++
        (if i % 2 == 0 then Vector(stay(d, venue, 12, 1)) else Vector.empty)
    build(store, src, zone, today, maxDays = 1_000)
    store.pagesRead = 0
    val r = query(store, venue)
    assertEquals(r.size, 50)
    assertEquals(r.head.day, today)
    assertEquals(r.last.day, today.minusDays(98))
    // 50 days found among the first 99 candidates: two pages of 64, not all 400 days.
    assertEquals(store.pagesRead, 2)
  }

  // --- re-review: the mark after the commit, failures not hidden ----------------------------

  test("mark, a build, a late row, the same archiving: the mark after the commit builds the day again") {
    val (store, src) = fixture(10)
    val d = today.minusDays(3)
    src.stays(d) = Vector(stay(d, north(venue, 500), 9, 1))
    build(store, src, zone, today, maxDays = 100)
    // 1. Archiving marks the day before it moves rows.
    archiving(store, d)
    // 2. The other process builds the day: the mark is used up.
    build(store, src, zone, today, maxDays = 100)
    assertEquals(pending(store, today.minusDays(9), today), Vector.empty)
    // 3. A late spill brings a row of the day (and the day now has a stay at the venue).
    src.add(Seq(startOf(d) + 13 * H))
    src.stays(d) = Vector(stay(d, north(venue, 500), 9, 1), stay(d, venue, 13, 1))
    // 4. The same commit moves it out of SQLite before the rowid scan reads it.
    src.archive(d)
    assertEquals(note(store, src, zone), Set.empty[Long])
    assertEquals(query(store, venue), Vector.empty)
    // 5. The mark after the commit: the day is built again from the final state.
    archiving(store, d)
    build(store, src, zone, today, maxDays = 100)
    assertEquals(query(store, venue).map(_.day), Vector(d))
    assertEquals(pending(store, today.minusDays(9), today), Vector.empty)
  }

  test("a changed day that cannot be built again: not up to date, and the list says so") {
    val (store, src) = fixture(5)
    val d = today.minusDays(2)
    src.stays(d) = Vector(stay(d, venue, 9, 1))
    build(store, src, zone, today, maxDays = 100)
    src.add(Seq(startOf(d) + 13 * H))
    src.failing = Set(d)
    val r = build(store, src, zone, today, maxDays = 100)
    assertEquals(r.failed, Vector(d))
    // Its old stays are still listed; it is never built, but not up to date.
    assertEquals(query(store, venue).map(_.day), Vector(d))
    assertEquals(unbuilt(store, today.minusDays(4), today), 0)
    assertEquals(stale(store, today.minusDays(4), today), 1)
    assertEquals(footer(answered = true, shown = 1, unbuilt = 0, stale = 1, building = false, trouble = false), Footer.Failed(true))
    // While runs go on nothing is said yet; once up to date, nothing.
    assertEquals(footer(answered = true, shown = 1, unbuilt = 0, stale = 1, building = true, trouble = false), Footer.Nothing)
    src.failing = Set.empty
    build(store, src, zone, today, maxDays = 100)
    assertEquals(footer(answered = true, shown = 1, unbuilt = 0, stale = stale(store, today.minusDays(4), today),
      building = false, trouble = false), Footer.Nothing)
  }

  test("the footer: building, none, a failed read or update, never 'none' for a failure") {
    assertEquals(footer(answered = true, shown = 0, unbuilt = 5, stale = 5, building = true, trouble = false), Footer.Building)
    assertEquals(footer(answered = true, shown = 0, unbuilt = 0, stale = 0, building = false, trouble = false), Footer.NoDays)
    assertEquals(footer(answered = false, shown = 0, unbuilt = 0, stale = 0, building = false, trouble = false), Footer.Failed(false))
    assertEquals(footer(answered = false, shown = 3, unbuilt = 0, stale = 0, building = true, trouble = false), Footer.Failed(true))
    // The update of the index failed (noting the changes): not "none".
    assertEquals(footer(answered = true, shown = 0, unbuilt = 0, stale = 0, building = false, trouble = true), Footer.Failed(false))
    assertEquals(footer(answered = true, shown = 2, unbuilt = 0, stale = 0, building = false, trouble = false), Footer.Nothing)
  }

  // --- the archiving note and the stale marker ----------------------------------------------

  /** The files beside the index in memory, under a lock as the app's;
    * `crash()` releases the hold only. `slow` yields between steps, to
    * let concurrent bumpers interleave if anything is not under the lock.
    */
  final class FakeBeside extends Beside:
    val files = mutable.Map.empty[String, String]
    var held = false
    var failWrites = false
    /** Only the note (and its sequence) cannot be written. */
    var failNote = false
    /** These files cannot be read at all. */
    var unreadable = Set.empty[String]
    var slow = false
    private val lock = java.util.concurrent.locks.ReentrantLock()
    def locked[A](f: => A): A =
      lock.lock()
      try f
      finally lock.unlock()
    def read(name: String): Option[String] =
      if unreadable(name) then throw java.io.IOException(s"cannot read $name")
      if slow then Thread.`yield`()
      files.synchronized(files.get(name))
    def write(name: String, value: String): Unit =
      if failWrites || (failNote && (name == BesideFile.Note || name == BesideFile.Seq)) then
        throw java.io.IOException(s"cannot write $name")
      if slow then Thread.`yield`()
      files.synchronized(files(name) = value)
    def delete(name: String): Unit = files.synchronized(files.remove(name))
    def archivingActive: Boolean = held
    def holdArchiving(): Unit = held = true
    def releaseArchiving(): Unit = held = false
    def crash(): Unit = held = false
    def note: Option[Either[String, LocalDate]] = archivingNote(this)
    def stale: Long = files.get(BesideFile.Stale).flatMap(_.toLongOption).getOrElse(0L)

  /** A use of the index by the list: emptied if stale, built whole, the marker consumed if it may be. */
  private def useAll(store: FakeStore, src: FakeSource, b: Beside, first: LocalDate): Boolean =
    emptyIfStale(store, b)
    noteArchiving(store, b)
    build(store, src, zone, today, maxDays = 1_000)
    consumeStale(store, b, first, today)

  test("a stale index used while an archiving runs, then a crash: still stale until a quiet rebuild") {
    val (store, src) = fixture(5)
    val b = FakeBeside()
    val first = today.minusDays(4)
    val d = today.minusDays(2)
    build(store, src, zone, today, maxDays = 100)
    // The note of an archiving could not be written: stale.
    b.failNote = true
    beforeArchiving(store, b, d)
    b.failNote = false
    assert(isStale(b))
    // The main process uses the index meanwhile: emptied and rebuilt, but not consumed.
    assert(!useAll(store, src, b, first))
    assert(isStale(b))
    // A late row of d, moved by the same archiving; the process dies before its mark after.
    src.add(Seq(startOf(d) + 13 * H))
    src.stays(d) = Vector(stay(d, venue, 13, 1))
    src.archive(d)
    b.crash()
    // Still stale: the rebuild began while the archiving ran.
    assert(isStale(b))
    assert(useAll(store, src, b, first))
    assert(!isStale(b))
    assertEquals(query(store, venue).map(_.day), Vector(d))
  }

  test("an archiving begun during a rebuild keeps the marker; a crash in it too") {
    val (store, src) = fixture(5)
    val b = FakeBeside()
    val first = today.minusDays(4)
    val d = today.minusDays(1)
    assert(markStale(b))
    emptyIfStale(store, b)
    build(store, src, zone, today, maxDays = 2)
    // An archiving begins and the process dies after its commit, before the mark after.
    beforeArchiving(store, b, d)
    build(store, src, zone, today, maxDays = 1_000)
    b.crash()
    assert(!consumeStale(store, b, first, today))
    // The noted day keeps it stale until maintenance settles the note.
    assert(!useAll(store, src, b, first))
    settleArchiving(store, b)
    assertEquals(b.note, None)
    // An archiving begun and finished during a rebuild: that rebuild cannot consume either.
    emptyIfStale(store, b)
    beforeArchiving(store, b, d)
    afterArchiving(store, b, d)
    build(store, src, zone, today, maxDays = 1_000)
    assert(!consumeStale(store, b, first, today))
    // A quiet rebuild consumes it.
    assert(useAll(store, src, b, first))
    assert(!isStale(b))
  }

  test("a corrupt archiving note: the marker moved once per note, however often used; removed by maintenance") {
    val (store, src) = fixture(3)
    val b = FakeBeside()
    val first = today.minusDays(2)
    build(store, src, zone, today, maxDays = 100)
    b.files(BesideFile.Note) = "garbage"
    for _ <- 0 until 3 do noteArchiving(store, b)
    emptyIfStale(store, b)
    assertEquals(b.stale, 1L)
    assertEquals(b.note, Some(Left("corrupt: garbage")))
    // While it stays, uses rebuild once and do not consume (an archiving is noted), and do not bump again.
    for _ <- 0 until 3 do assert(!useAll(store, src, b, first))
    assertEquals(b.stale, 1L)
    settleArchiving(store, b)
    assertEquals(b.note, None)
    assertEquals(b.stale, 1L)
    assert(useAll(store, src, b, first))
    assert(!isStale(b))
    // The same garbage again later is a new note: once more.
    b.files(BesideFile.Note) = "garbage"
    noteArchiving(store, b)
    noteArchiving(store, b)
    assertEquals(b.stale, 2L)
    // A note that cannot be read at all, and a marker that cannot be written: the note stays for next time.
    settleArchiving(store, b)
    b.files(BesideFile.Note) = "x"
    b.unreadable = Set(BesideFile.Note)
    b.failWrites = true
    settleArchiving(store, b)
    b.unreadable = Set.empty
    assertEquals(b.files.get(BesideFile.Note), Some("x"))
  }

  test("a corrupt stale marker is repaired once, not taken for a new generation at every read") {
    val (store, src) = fixture(3)
    val b = FakeBeside()
    val first = today.minusDays(2)
    b.files(BesideFile.Done) = "4"
    b.files(BesideFile.Stale) = "#!garbage"
    assert(isStale(b))
    assertEquals(b.files.get(BesideFile.Stale), Some("5"))
    assert(useAll(store, src, b, first))
    assert(!isStale(b))
    for _ <- 0 until 3 do
      emptyIfStale(store, b)
      assert(!isStale(b))
    assertEquals(b.files.get(BesideFile.Done), Some("5"))
  }

  test("a corrupt done record: one rebuild, then consumed") {
    val (store, src) = fixture(3)
    val b = FakeBeside()
    val first = today.minusDays(2)
    build(store, src, zone, today, maxDays = 100)
    b.files(BesideFile.Stale) = "7"
    b.files(BesideFile.Done) = "seven"
    assert(isStale(b))
    assertEquals(b.files.get(BesideFile.Done), Some("0"))
    assertEquals(b.files.get(BesideFile.Stale), Some("8"))
    assert(useAll(store, src, b, first))
    assert(!isStale(b))
    assertEquals(b.files.get(BesideFile.Done), Some("8"))
  }

  test("concurrent bumpers: every one moves the marker, none is lost") {
    val b = FakeBeside()
    b.slow = true
    val threads = (0 until 8).map(_ => Thread(() => for _ <- 0 until 50 do assert(markStale(b))))
    threads.foreach(_.start())
    threads.foreach(_.join())
    assertEquals(b.stale, 400L)
  }

  test("settling a note (recovery, 「整理を修復」): its day and neighbours marked, the note removed") {
    val (store, src) = fixture(5)
    val b = FakeBeside()
    val d = today.minusDays(2)
    build(store, src, zone, today, maxDays = 100)
    b.files(BesideFile.Note) = d.toString
    settleArchiving(store, b)
    assertEquals(b.note, None)
    assert(!isStale(b))
    assertEquals(pending(store, today.minusDays(4), today), Vector(d.plusDays(1), d, d.minusDays(1)))
    // A normal archiving: held, noted and marked, then marked again, released and the note removed.
    build(store, src, zone, today, maxDays = 100)
    beforeArchiving(store, b, d)
    assert(b.held && b.note.contains(Right(d)) && b.files.get(BesideFile.Seq).contains("1"))
    build(store, src, zone, today, maxDays = 100)
    afterArchiving(store, b, d)
    assert(!b.held && b.note.isEmpty)
    assertEquals(pending(store, today.minusDays(4), today).size, 3)
  }
