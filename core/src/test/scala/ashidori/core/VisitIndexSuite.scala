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
    def candidates(cells: Seq[Long]): Vector[(Long, Visit)] =
      stays.toVector.flatMap((d, ss) => ss.collect { case (v, cs) if cs.exists(cells.contains) => (d, v) })
    def clear(): Unit = { meta.clear(); dayStates.clear(); stays.clear() }

  /** A record: stays per day, marks moved by hand, changes as given. */
  class FakeSource(var first: Option[LocalDate]) extends Source:
    val stays = mutable.Map.empty[LocalDate, Vector[Visit]]
    var now = Marks(0, 0, 0)
    var pendingChanges = Changes(Set.empty, Seq.empty)
    val reads = mutable.ArrayBuffer.empty[LocalDate]
    var failing = Set.empty[LocalDate]
    def marks(): Marks = now
    def changes(from: Marks, to: Marks): Changes = pendingChanges
    def firstDay(): Option[LocalDate] = first
    def stays(day: LocalDate): Vector[Visit] =
      reads += day
      if failing(day) then throw IllegalStateException("cannot read")
      stays.getOrElse(day, Vector.empty)
    /** New rows at `times` (and deleted ranges): the marks move on. */
    def add(times: Seq[Long], deleted: Seq[(Long, Long)] = Nil): Unit =
      now = now.copy(fixes = now.fixes + times.size, deletions = now.deletions + deleted.size)
      pendingChanges = Changes(times.map(_ / H).toSet, deleted)

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
    src.now = Marks(0, 0, 0)
    note(store, src, zone)
    assertEquals(query(store, venue), Vector.empty)
    assertEquals(unbuilt(store, today.minusDays(2), today), 3)
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
