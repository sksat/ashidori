package ashidori.core

import java.time.ZoneOffset
import scala.collection.mutable.ArrayBuffer

/** State changes reach the table once and in order whatever happens to the
  * records that carry them: dropped, failed, sent twice, imported late.
  */
class EnergyLogSuite extends munit.FunSuite:
  private val zone = ZoneOffset.UTC
  private val Day = 86_400_000L
  private def st(i: Int) = EnergyState(i % 2 == 0, 1000L * (i + 1), "gps", false, i % 3 == 0, i % 2 == 1, false,
    "balanced", "balanced", "Walking", "Outdoor")

  /** The table as the app keeps it: closed rows and the open one(s). */
  private final class Store(deleted: Seq[(Long, Long)] = Nil):
    val closed = ArrayBuffer.empty[EnergyRow]
    var open = Vector.empty[EnergyRow]
    var cp: Option[EnergyCheckpoint] = None
    def apply(r: SmallRecord.Energy): Unit =
      EnergyLog.apply(open, cp, r, zone, deleted).foreach { ch =>
        if ch.replaceOpen then
          open = ch.insert.filter(_.end.isEmpty).toVector
          closed ++= ch.insert.filter(_.end.isDefined)
        cp = Some(ch.checkpoint)
      }
    def rows: Seq[(Long, Option[Long], EnergyState, Boolean)] =
      (closed ++ open).sortBy(r => (r.start, r.end.getOrElse(Long.MaxValue))).map(r => (r.start, r.end, r.state, r.endUnknown)).toSeq

  test("a lost record is covered by the next; a late or repeated one changes nothing") {
    val s = Store()
    val t = EnergyTracker("0000000000001-a")
    t.update(100, Some(st(1)))
    val r1 = t.record(100)
    t.update(200, Some(st(2)))
    val r2 = t.record(200)
    t.update(300, Some(st(3)))
    val r3 = t.record(300)
    // r1 dropped (queue full), r2 failed: neither confirmed. r3 carries all three.
    assertEquals(r3.transitions.map(_.seq), Seq(1L, 2L, 3L))
    s(r3)
    t.confirmed(3)
    // r1 and r2 come back later from their overflow files, and r3 again.
    s(r2); s(r1); s(r3)
    assertEquals(s.rows, Seq((100L, Some(200L), st(1), false), (200L, Some(300L), st(2), false), (300L, None, st(3), false)))
    // Confirmed changes are not sent again.
    assertEquals(t.record(400).transitions, Nil)
  }

  test("random loss, repeats and delays end in the history the recorder saw") {
    val rnd = scala.util.Random(7)
    for round <- 0 until 50 do
      val s = Store()
      val t = EnergyTracker(f"$round%013d-x")
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
          case 0 => ()                    // dropped
          case 1 => late += r             // kept in a file, imported later
          case _ =>
            s(r)
            if rnd.nextBoolean() then s(r) // sent twice
            r.transitions.lastOption.foreach(x => t.confirmed(x.seq))
      // A heartbeat that is stored, then the late ones in any order.
      val hb = t.record(now + 10)
      s(hb)
      hb.transitions.lastOption.foreach(x => t.confirmed(x.seq))
      rnd.shuffle(late.toSeq).foreach(s(_))
      val expected = truth.zip(truth.drop(1).map(x => Some(x._1)) :+ None).map { case ((a, x), b) => (a, b, x, false) }
      assertEquals(s.rows, expected.toSeq, s"round $round")
  }

  test("a new process closes the previous one's open row at its last confirmation, marked unknown") {
    val s = Store()
    val a = EnergyTracker("0000000000001-a")
    a.update(100, Some(st(1)))
    s(a.record(100))
    s(a.record(500)) // heartbeat
    // Process A is killed; B starts.
    val b = EnergyTracker("0000000000900-b")
    b.update(1000, Some(st(2)))
    s(b.record(1000))
    assertEquals(s.rows, Seq((100L, Some(500L), st(1), true), (1000L, None, st(2), false)))
    // A record of A that comes late (from its overflow file) is ignored.
    a.update(700, Some(st(3)))
    s(a.record(700))
    assertEquals(s.rows, Seq((100L, Some(500L), st(1), true), (1000L, None, st(2), false)))
  }

  test("without any confirmation, the leftover is zero length and marked unknown") {
    val s = Store()
    s.open = Vector(EnergyRow(100, None, st(1)))
    val b = EnergyTracker("0000000000900-b")
    b.update(1000, Some(st(2)))
    s(b.record(1000))
    assertEquals(s.rows.head, (100L, Some(100L), st(1), true))
  }

  test("an open row is closed at each midnight the state was confirmed past") {
    val s = Store()
    val t = EnergyTracker("0000000000001-a")
    val start = 10 * Day - 3_600_000L // 23:00
    t.update(start, Some(st(1)))
    s(t.record(start))
    s(t.record(start + 30 * 60_000L)) // still the same day: nothing closed
    assertEquals(s.rows.size, 1)
    s(t.record(12 * Day + 60_000L)) // two midnights later
    assertEquals(s.rows, Seq((start, Some(10 * Day), st(1), false), (10 * Day, Some(11 * Day), st(1), false),
      (11 * Day, Some(12 * Day), st(1), false), (12 * Day, None, st(1), false)))
  }

  test("nothing is written into a deleted range") {
    val s = Store(deleted = Seq((150L, 250L)))
    val t = EnergyTracker("0000000000001-a")
    t.update(100, Some(st(1)))
    t.update(200, Some(st(2)))
    t.update(300, Some(st(3)))
    s(t.record(300))
    assertEquals(s.rows, Seq((100L, Some(150L), st(1), false), (250L, Some(300L), st(2), false), (300L, None, st(3), false)))
    // A range not over yet: the open row inside it waits.
    val u = Store(deleted = Seq((500L, 10_000L)))
    val v = EnergyTracker("0000000000001-a")
    v.update(600, Some(st(1)))
    u(v.record(600))
    assertEquals(u.rows, Nil)
  }

  test("the tracker keeps at most maxPending changes and counts the rest") {
    val t = EnergyTracker("t", maxPending = 3)
    for i <- 1 to 5 do t.update(i * 10L, Some(st(i)))
    assertEquals(t.record(100).transitions.map(_.seq), Seq(3L, 4L, 5L))
    assertEquals(t.dropped, 2L)
    assert(!t.update(200, Some(st(5))))
  }

  test("records round-trip through their overflow line") {
    val t = EnergyTracker("0000000000001-a")
    t.update(100, Some(st(1)))
    t.update(200, None)
    val r = t.record(250)
    assertEquals(SmallRecord.decode(SmallRecord.encode(r)), r)
    val fx = SmallRecord.Fixes(Seq(Fix(1_760_000_000_123L, 35.5, 139.5, 4f, Double.NaN, Float.NaN, 90f, "gps", 12, msl = 41.5)))
    val back = SmallRecord.decode(SmallRecord.encode(fx)).asInstanceOf[SmallRecord.Fixes].fixes.head
    assertEquals((back.time, back.lat, back.msl, back.provider, back.satellites), (1_760_000_000_123L, 35.5, 41.5, "gps", 12))
    assert(back.altitude.isNaN && back.speed.isNaN && back.altAccuracy.isNaN)
    val b: SmallRecord.Battery = new SmallRecord.Battery(BatteryReading(5L, 80, true, false, "eco", Some(3_000_000L), Some(-5), None, Some(4000), Float.NaN,
      Some("usb")), "GNSS 10 秒間隔")
    val bb = SmallRecord.decode(SmallRecord.encode(b)).asInstanceOf[SmallRecord.Battery]
    assertEquals((bb.reading.copy(tempC = 0f), bb.plan), (b.reading.copy(tempC = 0f), b.plan))
    for r <- Seq(SmallRecord.Motion(Seq(MotionWindow(1, 2, 0.5f, 0.1f, 3))), SmallRecord.Pressure(Seq(PressureSample(9, 1001.5f))),
                 SmallRecord.GnssStatus(1, 20, 10, 35.5, 3, "Outdoor", 4.5f), SmallRecord.RawSession(1, 2, "", "manual"),
                 SmallRecord.FixMsl(Seq((7L, 40.25, 3.5f)))) do
      assertEquals(SmallRecord.decode(SmallRecord.encode(r)), r)
  }
