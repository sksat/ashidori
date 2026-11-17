package ashidori.core

import java.time.LocalDate
import TransportMode.*

/** Adjacent legs that end with the same mode are one leg ([[MergeDay]]). */
class LegMergeSuite extends munit.FunSuite:
  import MergeDay.at
  private lazy val day = MergeDay.build()
  private lazy val segs = TimelineBuilder.build(day.fixes, day.motion)
  private lazy val split = TimelineBuilder.unmerged(day.fixes, day.motion)

  private def legs(ss: Seq[Segment]) = ss.collect { case t: Trip => t.legs }.flatten
  /** The moving legs of the trip that starts between `h`:00 and `h`:30. */
  private def tripAt(ss: Seq[Segment], h: Int): Vector[Leg] =
    ss.collect { case t: Trip if t.start >= at(h, 0) && t.start < at(h, 30) => t.legs.filter(_.mode != Still) }.head
  private def show(ss: Seq[Segment]) = ss.map {
    case t: Trip => t.legs.map(l => s"${l.mode.key} ${l.start}-${l.end}${if l.corrected then " ✎" else ""}").mkString("trip[", ", ", "]")
    case s       => s.toString
  }.mkString("\n")

  test("as cut by speed, a slow start and the rest of a drive are two legs of 車 (the bug)") {
    assertEquals(tripAt(split, 9).map(_.mode), Vector(Car, Car), show(split))
    assertEquals(tripAt(split, 10).map(_.mode), Vector(Car, Car, Car), show(split))
  }

  test("a slow start (Mid) and the rest (Vehicle), both 車, are one leg") {
    val ls = tripAt(segs, 9)
    assertEquals(ls.map(_.mode), Vector(Car), show(segs))
    val parts = tripAt(split, 9)
    assertEquals((ls.head.start, ls.head.end), (parts.head.start, parts.last.end))
  }

  test("a drive around a short outage is one leg, the outage's distance in it as before") {
    val ls = tripAt(segs, 10)
    assertEquals(ls.map(_.mode), Vector(Car), show(segs))
    val parts = tripAt(split, 10)
    assertEqualsDouble(ls.head.distanceM, parts.map(_.distanceM).sum, 1e-6)
    // The outage: 105 s between the fixes on either side, the rest good.
    assertEqualsDouble(ls.head.uncertain, 105.0 / ((ls.head.end - ls.head.start) / 1000.0), 1e-9)
    // The outage's own leg: 350 m without a position and the 60 m to its first fix.
    assertEqualsDouble(parts(1).distanceM, 410.0, 1.0)
  }

  test("a walk, then a drive: still two legs") {
    assertEquals(tripAt(segs, 11).map(_.mode), Vector(Walking, Car), show(segs))
  }

  test("the day's distance and time per mode do not change") {
    val a = Stats.day(MergeDay.Date, Stats.splitByDay(segs, MergeDay.Zone)(MergeDay.Date), 0)
    val b = Stats.day(MergeDay.Date, Stats.splitByDay(split, MergeDay.Zone)(MergeDay.Date), 0)
    assertEquals(a.durationByMode, b.durationByMode)
    assertEquals(a.distanceByMode.keySet, b.distanceByMode.keySet)
    for (m, d) <- a.distanceByMode do assertEqualsDouble(d, b.distanceByMode(m), 1e-6)
    // 9:00 and 12:00 lose one leg each, 10:00 two.
    assertEquals(legs(segs).size, legs(split).size - 4)
  }

  // --- corrections ------------------------------------------------------

  private def corrected(os: ModeOverride*) = tripAt(TimelineBuilder.build(day.fixes, day.motion, overrides = os), 12)
  private lazy val parts12 = tripAt(split, 12)

  test("a correction on one part keeps to that part: the two are not merged") {
    val (a, b) = (parts12(0), parts12(1))
    val ls = corrected(ModeOverride(a.start, a.end, Bus))
    assertEquals(ls.map(l => (l.start, l.end, l.mode, l.corrected)), Vector((a.start, a.end, Bus, true), (b.start, b.end, Car, false)))
    assertEquals(ls.head.inferred, Some(Car))
    // On the other part: the same, the other way round.
    assertEquals(corrected(ModeOverride(b.start, b.end, Bus)).map(_.mode), Vector(Car, Bus))
  }

  test("parts corrected to the same mode are one leg, corrected, with the mode the record says") {
    val (a, b) = (parts12(0), parts12(1))
    val ls = corrected(ModeOverride(a.start, a.end, Bus, 1), ModeOverride(b.start, b.end, Bus, 2))
    assertEquals(ls.map(l => (l.start, l.end, l.mode)), Vector((a.start, b.end, Bus)))
    assertEquals(ls.head.inferred, Some(Car))
    assertEquals(ls.head.confidence, 1.0)
  }

  test("a correction saved for a merged leg covers both parts: it stays one leg") {
    val whole = tripAt(segs, 12).head
    val ls = corrected(ModeOverride(whole.wholeStart, whole.wholeEnd, Train))
    assertEquals(ls.map(l => (l.start, l.end, l.mode, l.inferred)), Vector((whole.start, whole.end, Train, Some(Car))))
    // 取り消し: the inferred mode, newer, leaves it as built.
    val back = corrected(ModeOverride(whole.wholeStart, whole.wholeEnd, Train, 1), ModeOverride(whole.wholeStart, whole.wholeEnd, Car, 2))
    assertEquals(back, Vector(whole))
  }

  test("取り消し after changing a leg whose parts were corrected shows the corrected mode again") {
    val (a, b) = (parts12(0), parts12(1))
    val parts = Seq(ModeOverride(a.start, a.end, Bus, 1), ModeOverride(b.start, b.end, Bus, 2))
    val before = corrected(parts*)
    assertEquals(before.map(l => (l.mode, l.inferred)), Vector((Bus, Some(Car))))
    val l = before.head
    // Changed to 電車 for the whole leg (as DayRows saves it), then taken back.
    val edit = ModeOverride(l.wholeStart, l.wholeEnd, Train, 3)
    assertEquals(corrected((parts :+ edit)*).map(_.mode), Vector(Train))
    val back = ModeOverride(l.wholeStart, l.wholeEnd, TimelineBuilder.undoMode(l), 4)
    assertEquals(corrected((parts ++ Seq(edit, back))*), before)
    // Writing the inferred mode instead would have hidden both corrections.
    assertEquals(corrected((parts ++ Seq(edit, ModeOverride(l.wholeStart, l.wholeEnd, l.autoMode, 4)))*).map(_.mode), Vector(Car))
  }

  test("取り消し without an earlier correction writes the inferred mode: the leg as built") {
    val l = tripAt(segs, 12).head
    assertEquals(TimelineBuilder.undoMode(l), Car)
  }

  test("a correction back to the inferred mode on one part changes nothing") {
    val a = parts12(0)
    assertEquals(corrected(ModeOverride(a.start, a.end, Car)), tripAt(segs, 12))
  }

  test("a leg corrected to its neighbour's mode joins it") {
    val walk = tripAt(split, 11).head
    val ls = tripAt(TimelineBuilder.build(day.fixes, day.motion, overrides = Seq(ModeOverride(walk.start, walk.end, Car))), 11)
    assertEquals(ls.map(l => (l.start, l.mode)), Vector((walk.start, Car)), show(segs))
  }

  test("build with corrections is applyOverrides where nothing merges") {
    // A correction to a leg with no neighbour of that mode.
    val l = tripAt(segs, 10).head
    val os = Seq(ModeOverride(l.start, l.end, Bus))
    assertEquals(TimelineBuilder.build(day.fixes, day.motion, overrides = os), TimelineBuilder.applyOverrides(segs, os))
  }

  // --- boundaries -------------------------------------------------------

  test("a merged leg across midnight is clipped per day, each part knowing the whole leg") {
    val d = MergeDay.build(at(23, 30), Seq(at(23, 55)))
    val built = TimelineBuilder.build(d.fixes, d.motion)
    val car = legs(built).filter(_.mode == Car)
    assertEquals(car.size, 1, show(built))
    val days = Stats.splitByDay(built, MergeDay.Zone)
    val clipped = Seq(MergeDay.Date, MergeDay.Date.plusDays(1)).map(dd => legs(days(dd)).filter(_.mode == Car))
    assertEquals(clipped.map(_.size), Seq(1, 1))
    for c <- clipped.flatten do assertEquals((c.wholeStart, c.wholeEnd), (car.head.start, car.head.end))
    assertEqualsDouble(clipped.flatten.map(_.distanceM).sum, car.head.distanceM, 1e-6)
  }

  test("never across a stretch without a record") {
    // Driving, the phone off for 20 min while 10 km away, driving again.
    val t0 = at(14, 0)
    val f = Vector.newBuilder[Fix]
    val m = Vector.newBuilder[MotionWindow]
    var north = 0.0
    var t = t0
    def fix() = f += Fix(t, 36.0 + north / 111_320.0, 140.0, accuracy = 8f)
    def drive(n: Int) = for _ <- 0 until n do
      fix()
      m += MotionWindow(t, t + 5_000, 0.4f, 0.1f, 0)
      t += 5_000
      north += 60.0
    drive(96)
    t += 20 * 60_000L
    north += 10_000.0
    drive(96)
    val built = TimelineBuilder.build(f.result(), m.result())
    assertEquals(built.map(_.getClass.getSimpleName), Vector("Trip", "Unrecorded", "Trip"), show(built))
    assertEquals(legs(built).map(_.mode), Vector(Car, Car))
  }

  test("a long drive with many class switches merges in one pass, fast") {
    // 2,000 runs of a minute each, alternating 5 m/s (Mid) and 12 m/s (Vehicle): about 33 h of 車.
    val t0 = at(0, 0)
    val f = Vector.newBuilder[Fix]
    val m = Vector.newBuilder[MotionWindow]
    var north = 0.0
    var t = t0
    for k <- 0 until 2000; _ <- 0 until 12 do
      f += Fix(t, 36.0 + north / 111_320.0, 140.0, accuracy = 8f)
      m += MotionWindow(t, t + 5_000, 0.4f, 0.1f, 0)
      t += 5_000
      north += (if k % 2 == 0 then 5.0 else 12.0) * 5
    val (fixes, motion) = (f.result(), m.result())
    assert(TimelineBuilder.unmerged(fixes, motion).collect { case t: Trip => t.legs.size }.sum >= 1000)
    val started = System.nanoTime()
    val built = TimelineBuilder.build(fixes, motion)
    val ms = (System.nanoTime() - started) / 1_000_000
    assertEquals(legs(built).map(_.mode), Vector(Car))
    assert(ms < 3_000, s"$ms ms")
  }
