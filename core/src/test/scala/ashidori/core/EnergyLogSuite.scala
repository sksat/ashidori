package ashidori.core

import java.time.ZoneOffset
import scala.collection.mutable.ArrayBuffer

/** State changes reach the table once and in order whatever happens to the
  * records that carry them: dropped, failed, sent twice, imported late,
  * across restarts, with a clock that goes back.
  */
class EnergyLogSuite extends munit.FunSuite:
  private val zone = ZoneOffset.UTC
  private val Day = 86_400_000L
  private def st(i: Int) = EnergyState(i % 2 == 0, 1000L * (i + 1), "gps", false, i % 3 == 0, i % 2 == 1, false,
    "balanced", "balanced", "Walking", "Outdoor")

  /** The table as the app keeps it, applying a record as Db.applyEnergy does. */
  private final class Store(deleted: Seq[(Long, Long)] = Nil):
    var rows = Vector.empty[EnergyRow]
    val cps = scala.collection.mutable.Map.empty[Long, EnergyCheckpoint]
    private var nextId = 1L
    def apply(r: SmallRecord.Energy): Unit =
      val b = r.boot
      val tail = rows.filter(_.boot == b).sortBy(x => (x.seq, x.start)).lastOption
      val newest = cps.keys.maxOption.forall(_ <= b)
      val olderOpen = if newest then rows.filter(x => x.end.isEmpty && x.boot < b).map(x => (x, cps.get(x.boot).map(_.confirmedAt))) else Nil
      val ch = EnergyLog.apply(EnergyLog.Before(tail, cps.get(b), newest, olderOpen), r, zone, deleted)
      rows = rows.filterNot(x => ch.remove.contains(x.id)) ++ ch.insert.map { x =>
        nextId += 1
        x.copy(id = nextId)
      }
      cps(b) = ch.checkpoint
    def view: Seq[(Long, Option[Long], EnergyState, Boolean)] =
      rows.sortBy(r => (r.start, r.end.getOrElse(Long.MaxValue))).map(r => (r.start, r.end, r.state, r.endUnknown))

  test("a lost record is covered by the next; a late or repeated one changes nothing") {
    val s = Store()
    val t = EnergyTracker(1)
    t.update(100, Some(st(1)))
    val r1 = t.record(100)
    t.update(200, Some(st(2)))
    val r2 = t.record(200)
    t.update(300, Some(st(3)))
    val r3 = t.record(300)
    assertEquals(r3.transitions.map(_.seq), Seq(1L, 2L, 3L))
    s(r3)
    t.confirmed(3)
    s(r2); s(r1); s(r3)
    assertEquals(s.view, Seq((100L, Some(200L), st(1), false), (200L, Some(300L), st(2), false), (300L, None, st(3), false)))
    assertEquals(t.record(400).transitions, Nil)
  }

  test("random loss, repeats and delays end in the history the recorder saw") {
    val rnd = scala.util.Random(7)
    for round <- 0 until 50 do
      val s = Store()
      val t = EnergyTracker(1)
      val late = ArrayBuffer.empty[SmallRecord.Energy]
      val truth = ArrayBuffer.empty[(Long, EnergyState)]
      var now = 1_000L
      var last = -1
      for _ <- 0 until 40 do
        now += 1 + rnd.nextInt(5000)
        val i = { var k = rnd.nextInt(5); while k == last do k = rnd.nextInt(5); k }
        last = i
        t.update(now, Some(st(i)))
        truth += now -> st(i)
        val r = t.record(now)
        rnd.nextInt(4) match
          case 0 => ()
          case 1 => late += r
          case _ =>
            s(r)
            if rnd.nextBoolean() then s(r)
            r.transitions.lastOption.foreach(x => t.confirmed(x.seq))
      val hb = t.record(now + 10)
      s(hb)
      hb.transitions.lastOption.foreach(x => t.confirmed(x.seq))
      rnd.shuffle(late.toSeq).foreach(s(_))
      val expected = truth.zip(truth.drop(1).map(x => Some(x._1)) :+ None).map { case ((a, x), b) => (a, b, x, false) }
      assertEquals(s.view, expected.toSeq, s"round $round")
  }

  test("a new process closes the previous one's open row at its last confirmation, marked unknown") {
    val s = Store()
    val a = EnergyTracker(1)
    a.update(100, Some(st(1)))
    s(a.record(100))
    s(a.record(500)) // heartbeat
    val b = EnergyTracker(2)
    b.update(1000, Some(st(2)))
    s(b.record(1000))
    assertEquals(s.view, Seq((100L, Some(500L), st(1), true), (1000L, None, st(2), false)))
  }

  test("an earlier process's records imported after the next one wrote are put in place, not dropped") {
    val s = Store()
    val a = EnergyTracker(1)
    a.update(100, Some(st(1)))
    s(a.record(100))
    // Process 1's later changes only reached overflow files: 60 of them
    // (more than one import pass takes), then it was killed.
    val spilled = ArrayBuffer.empty[SmallRecord.Energy]
    for i <- 1 to 60 do
      a.update(100 + i * 10L, Some(st(i % 5 + 1)))
      spilled += a.record(100 + i * 10L)
    // Process 2 starts and stores first.
    val b = EnergyTracker(2)
    b.update(5000, Some(st(4)))
    s(b.record(5000))
    assertEquals(s.view.head, (100L, Some(100L), st(1), true))
    // The import comes in passes of up to 50 files, between process 2's records.
    spilled.take(50).foreach(s(_))
    b.update(6000, Some(st(5)))
    s(b.record(6000))
    spilled.drop(50).foreach(s(_))
    val one = s.view.filter(_._1 < 5000)
    assertEquals(one.size, 61)
    // In order, each until the next, the last until process 1's last confirmation.
    assertEquals(one.map(_._1), (0 to 60).map(i => 100 + i * 10L))
    assertEquals(one.init.map(_._4), Seq.fill(60)(false))
    assertEquals(one.last, (700L, Some(700L), st(60 % 5 + 1), true))
    assertEquals(s.view.filter(_._1 >= 5000), Seq((5000L, Some(6000L), st(4), false), (6000L, None, st(5), false)))
  }

  test("a clock that goes back: changes stay in order, and a restart with an earlier clock is accepted") {
    val s = Store()
    val a = EnergyTracker(1)
    a.update(10_000, Some(st(1)))
    a.update(9_000, Some(st(2)))  // the clock went back a second
    a.update(9_500, Some(st(3)))
    s(a.record(9_500))
    assertEquals(s.view, Seq((10_000L, Some(10_000L), st(1), false), (10_000L, Some(10_000L), st(2), false),
      (10_000L, None, st(3), false)))
    // Restart with the clock an hour earlier: still the later process.
    val b = EnergyTracker(2)
    b.update(2_000, Some(st(4)))
    s(b.record(2_000))
    assert(s.view.contains((2_000L, None, st(4), false)), s.view.toString)
    assert(s.view.contains((10_000L, Some(10_000L), st(3), true)), s.view.toString)
  }

  test("without any confirmation, the leftover is zero length and marked unknown") {
    val s = Store()
    s.rows = Vector(EnergyRow(100, None, st(1), id = 1))
    val b = EnergyTracker(2)
    b.update(1000, Some(st(2)))
    s(b.record(1000))
    assertEquals(s.view.head, (100L, Some(100L), st(1), true))
  }

  test("an open row is closed at each midnight the state was confirmed past") {
    val s = Store()
    val t = EnergyTracker(1)
    val start = 10 * Day - 3_600_000L
    t.update(start, Some(st(1)))
    s(t.record(start))
    s(t.record(start + 30 * 60_000L))
    assertEquals(s.rows.size, 1)
    s(t.record(12 * Day + 60_000L))
    assertEquals(s.view, Seq((start, Some(10 * Day), st(1), false), (10 * Day, Some(11 * Day), st(1), false),
      (11 * Day, Some(12 * Day), st(1), false), (12 * Day, None, st(1), false)))
  }

  test("nothing is written into a deleted range") {
    val s = Store(deleted = Seq((150L, 250L)))
    val t = EnergyTracker(1)
    t.update(100, Some(st(1)))
    t.update(200, Some(st(2)))
    t.update(300, Some(st(3)))
    s(t.record(300))
    assertEquals(s.view, Seq((100L, Some(150L), st(1), false), (250L, Some(300L), st(2), false), (300L, None, st(3), false)))
    val u = Store(deleted = Seq((500L, 10_000L)))
    val v = EnergyTracker(1)
    v.update(600, Some(st(1)))
    u(v.record(600))
    assertEquals(u.view, Nil)
  }

  test("reading: one version per piece of a state change, the definitive one; never by start alone") {
    val a = EnergySpan(600, 610, st(1), endKnown = false, boot = 3, seq = 1)    // archived, closed at a confirmation
    val b = EnergySpan(600, 620, st(1), boot = 3, seq = 1)                      // closed again later, known end
    val open = EnergySpan(600, 700, st(1), boot = 3, seq = 1, open = true)      // as it was while open
    assertEquals(EnergyLog.select(Seq(a, b, open)), Vector(b))
    assertEquals(EnergyLog.select(Seq(a, open)), Vector(a))
    // Two unknown ends: the later confirmation.
    assertEquals(EnergyLog.select(Seq(a, a.copy(end = 615))), Vector(a.copy(end = 615)))
    // Another origin with the same start (another recorder, a clock that went back): both stay.
    val other = EnergySpan(600, 650, st(2), boot = 4, seq = 1)
    assertEquals(EnergyLog.select(Seq(b, other)).toSet, Set(b, other))
    // The pieces of one change split at midnight: each kept.
    val day1 = EnergySpan(0, 100, st(1), boot = 5, seq = 2)
    val day2 = EnergySpan(100, 200, st(1), boot = 5, seq = 2)
    assertEquals(EnergyLog.select(Seq(day2, day1)), Vector(day1, day2))
    // Rows of no origin (written before origins were kept): exact repeats once, the rest kept.
    val l1 = EnergySpan(600, 610, st(1))
    val l2 = EnergySpan(600, 620, st(1))
    assertEquals(EnergyLog.select(Seq(l1, l2, l1)).toSet, Set(l1, l2))
    // Through the archive's columns and back.
    assertEquals(Archive.energyOf(Archive.energyRow(b).toIndexedSeq), b)
  }

  test("the tracker keeps at most maxPending changes and counts the rest") {
    val t = EnergyTracker(1, maxPending = 3)
    for i <- 1 to 5 do t.update(i * 10L, Some(st(i)))
    assertEquals(t.record(100).transitions.map(_.seq), Seq(3L, 4L, 5L))
    assertEquals(t.dropped, 2L)
    assert(!t.update(200, Some(st(5))))
  }

  test("records round-trip through their overflow line") {
    val t = EnergyTracker(42)
    t.update(100, Some(st(1)))
    t.update(200, None)
    val r = t.record(250)
    assertEquals(SmallRecord.decode(SmallRecord.encode(r)), r)
    val fx = SmallRecord.Fixes(Seq(Fix(1_760_000_000_123L, 35.5, 139.5, 4f, Double.NaN, Float.NaN, 90f, "gps", 12, msl = 41.5)))
    val back = SmallRecord.decode(SmallRecord.encode(fx)).asInstanceOf[SmallRecord.Fixes].fixes.head
    assertEquals((back.time, back.lat, back.msl, back.provider, back.satellites), (1_760_000_000_123L, 35.5, 41.5, "gps", 12))
    assert(back.altitude.isNaN && back.speed.isNaN && back.altAccuracy.isNaN)
    val b: SmallRecord.Battery = new SmallRecord.Battery(BatteryReading(5L, 80, true, false, "eco", Some(3_000_000L), Some(-5), None,
      Some(4000), Float.NaN, Some("usb")), "GNSS 10 秒間隔")
    val bb = SmallRecord.decode(SmallRecord.encode(b)).asInstanceOf[SmallRecord.Battery]
    assertEquals((bb.reading.copy(tempC = 0f), bb.plan), (b.reading.copy(tempC = 0f), b.plan))
    for r <- Seq(SmallRecord.Motion(Seq(MotionWindow(1, 2, 0.5f, 0.1f, 3))), SmallRecord.Pressure(Seq(PressureSample(9, 1001.5f))),
                 SmallRecord.GnssStatus(1, 20, 10, 35.5, 3, "Outdoor", 4.5f), SmallRecord.RawSession(1, 2, "", "manual"),
                 SmallRecord.FixMsl(Seq((7L, 40.25, 3.5f)))) do
      assertEquals(SmallRecord.decode(SmallRecord.encode(r)), r)
    // The PICS scan in the state: kept when known; a record of an earlier version (none) reads as not known.
    val s0 = EnergyState(true, 1000L, "gps", false, false, true, false, "balanced", "balanced", "Walking", "Outdoor")
    val withBle = new SmallRecord.Energy(3, Seq(EnergyTransition(1, 100, Some(s0.copy(ble = Some("opp"))))), 150, Some(s0.copy(ble = Some("opp"))))
    assertEquals(SmallRecord.decode(SmallRecord.encode(withBle)), withBle)
    val earlier = SmallRecord.encode(withBle.copy(transitions = Seq(EnergyTransition(1, 100, Some(s0))), current = Some(s0)))
    assert(!earlier.contains("ble"), earlier)
    assertEquals(SmallRecord.decode(earlier).asInstanceOf[SmallRecord.Energy].current.get.ble, None)
  }
