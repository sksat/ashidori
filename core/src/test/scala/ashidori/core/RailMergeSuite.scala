package ashidori.core

import TransportMode.*

/** Adjacent legs of 電車 and 地下鉄 that are one ride are one leg ([[RailDay]]). */
class RailMergeSuite extends munit.FunSuite:
  import RailDay.at
  private lazy val day = RailDay.build()
  private lazy val segs = TimelineBuilder.build(day.fixes, day.motion)
  private lazy val split = TimelineBuilder.unmerged(day.fixes, day.motion)

  private def legs(ss: Seq[Segment]) = ss.collect { case t: Trip => t.legs }.flatten
  private def trips(ss: Seq[Segment], h: Int) = ss.collect { case t: Trip if t.start >= at(h, 0) && t.start < at(h + 2, 0) => t }
  /** The legs of the trip that starts between `h`:00 and `h`:30. */
  private def tripAt(ss: Seq[Segment], h: Int): Vector[Leg] =
    ss.collect { case t: Trip if t.start >= at(h, 0) && t.start < at(h, 30) => t.legs }.head
  private def show(ss: Seq[Segment]) = ss.map {
    case t: Trip       => t.legs.map(l => s"${l.mode.key} ${l.start}-${l.end} ${l.steps} steps${if l.corrected then " ✎" else ""}").mkString("trip[", ", ", "]")
    case u: Unrecorded => s"unrecorded ${u.start}-${u.end}"
    case v: Visit      => s"visit ${v.start}-${v.end}"
  }.mkString("\n")

  test("as cut by speed, one train in and out of the ground alternates 電車 and 地下鉄 (the bug)") {
    assertEquals(tripAt(split, 9).map(_.mode), Vector(Walking, Train, Subway, Train, Subway, Train, Walking), show(split))
  }

  test("one train in and out of the ground is one leg, with the mode of the longer time") {
    val parts = tripAt(split, 9).filter(l => TimelineBuilder.isRail(l.mode))
    val ls = tripAt(segs, 9)
    assertEquals(ls.map(_.mode), Vector(Walking, Train, Walking), show(segs))
    val ride = ls(1)
    assertEquals((ride.start, ride.end), (parts.head.start, parts.last.end))
    // 電車 for 18 min, 地下鉄 for 13.
    val time = parts.groupMapReduce(_.mode)(_.durationMs)(_ + _)
    assert(time(Train) > time(Subway), time)
    // Built again from all its intervals: the parts' distance and steps.
    assertEqualsDouble(ride.distanceM, parts.map(_.distanceM).sum, 1e-6)
    assertEquals(ride.steps, parts.map(_.steps).sum)
    assertEquals(ride.inferred, None)
  }

  test("a few steps to the door are no transfer") {
    val parts = tripAt(split, 9).filter(l => TimelineBuilder.isRail(l.mode))
    val steps = parts.map(_.steps).sum
    assert(steps > 0 && steps < TimelineBuilder.TransferSteps, parts.map(_.steps))
  }

  test("a ride mostly underground is 地下鉄") {
    // 17:00: 8 min underground and 5 above, then (after 記録なし) 6 above and 8 underground.
    assertEquals(trips(segs, 17).map(_.legs.map(_.mode)), Vector(Vector(Walking, Subway), Vector(Subway, Walking)), show(segs))
    assertEquals(trips(split, 17).map(_.legs.map(_.mode)), Vector(Vector(Walking, Subway, Train), Vector(Train, Subway, Walking)))
  }

  test("on equal time, 電車") {
    def leg(s: Long, e: Long, m: TransportMode) = Leg(s, e, m, 0.0, 0, 0.5, 0.0, 0.0)
    assertEquals(TimelineBuilder.railMode(Seq(leg(0, 600, Subway), leg(600, 1200, Train))), Train)
    assertEquals(TimelineBuilder.railMode(Seq(leg(0, 601, Subway), leg(601, 1200, Train))), Subway)
    // The mode the record says: a corrected part counts as what it was.
    assertEquals(TimelineBuilder.railMode(Seq(leg(0, 900, Train).copy(inferred = Some(Subway)), leg(900, 1200, Train))), Subway)
  }

  test("a transfer with a walk underground stays two legs") {
    // 11:00: the walk is in the outage that ends on the platform above ground.
    assertEquals(tripAt(split, 11).map(_.mode), Vector(Walking, Subway, Train, Walking), show(split))
    assertEquals(tripAt(segs, 11).map(_.mode), Vector(Walking, Subway, Train, Walking), show(segs))
  }

  test("a transfer with a walk above ground too short for a leg stays two legs") {
    // 13:00: as cut by speed the walk is part of the express's leg.
    val parts = tripAt(split, 13)
    assertEquals(parts.map(_.mode), Vector(Walking, Subway, Train, Walking), show(split))
    assert(parts(2).steps >= TimelineBuilder.TransferSteps, parts(2).steps)
    assertEquals(tripAt(segs, 13), parts)
  }

  test("a stay between them keeps them apart: two trips") {
    assertEquals(trips(segs, 15).map(_.legs.map(_.mode)), Vector(Vector(Walking, Subway), Vector(Train, Walking)), show(segs))
  }

  test("never across a stretch without a record") {
    val around = segs.filter(s => s.start >= at(17, 0) && s.start < at(19, 0))
    assertEquals(around.map(_.getClass.getSimpleName), Vector("Trip", "Unrecorded", "Trip", "Visit"), show(around))
  }

  test("the day's distance and travel time do not change; 電車 and 地下鉄 only move between each other") {
    val a = Stats.day(RailDay.Date, Stats.splitByDay(segs, RailDay.Zone)(RailDay.Date), 0)
    val b = Stats.day(RailDay.Date, Stats.splitByDay(split, RailDay.Zone)(RailDay.Date), 0)
    assertEquals(a.movingMs, b.movingMs)
    assertEqualsDouble(a.totalDistanceM, b.totalDistanceM, 1e-6)
    assertEquals(a.unrecordedMs, b.unrecordedMs)
    assertEquals(a.visits, b.visits)
    def rail(m: Map[TransportMode, Long]) = m.getOrElse(Train, 0L) + m.getOrElse(Subway, 0L)
    assertEquals(rail(a.durationByMode), rail(b.durationByMode))
    for m <- TransportMode.values if !TimelineBuilder.isRail(m) do
      assertEquals(a.durationByMode.get(m), b.durationByMode.get(m), m)
    // 09:00 loses four legs, each trip at 17:00 one.
    assertEquals(legs(segs).size, legs(split).size - 6)
  }

  // --- corrections ------------------------------------------------------

  private lazy val parts9 = tripAt(split, 9).filter(l => TimelineBuilder.isRail(l.mode))
  private def corrected(os: ModeOverride*) = tripAt(TimelineBuilder.build(day.fixes, day.motion, overrides = os), 9).filter(_.mode != Walking)

  test("a part corrected to the other rail mode keeps apart from its neighbours") {
    // The middle 電車 corrected to 地下鉄: it joins the 地下鉄 on either side, not the 電車 beyond.
    val t2 = parts9(2)
    val ls = corrected(ModeOverride(t2.start, t2.end, Subway))
    assertEquals(ls.map(l => (l.start, l.end, l.mode)),
      Vector((parts9(0).start, parts9(0).end, Train), (parts9(1).start, parts9(3).end, Subway), (parts9(4).start, parts9(4).end, Train)))
  }

  test("a part corrected to another mode keeps apart; the rest of the ride is one leg") {
    val s1 = parts9(1)
    val ls = corrected(ModeOverride(s1.start, s1.end, Bus))
    assertEquals(ls.map(l => (l.start, l.end, l.mode, l.corrected)),
      Vector((parts9(0).start, parts9(0).end, Train, false), (s1.start, s1.end, Bus, true), (parts9(2).start, parts9(4).end, Train, false)))
  }

  test("a part corrected to the ride's mode: one leg, as the record says it") {
    // The first 地下鉄 corrected to 電車: it joins the 電車 around it, and that the rest.
    val s1 = parts9(1)
    val ls = corrected(ModeOverride(s1.start, s1.end, Train))
    assertEquals(ls.map(l => (l.start, l.end, l.mode, l.inferred)), Vector((parts9.head.start, parts9.last.end, Train, None)))
  }

  test("a correction saved for the ride covers its parts: one leg, corrected; 取り消し gives it back as built") {
    val ride = tripAt(segs, 9)(1)
    val edit = ModeOverride(ride.wholeStart, ride.wholeEnd, Subway, 1)
    val ls = corrected(edit)
    assertEquals(ls.map(l => (l.start, l.end, l.mode, l.inferred)), Vector((ride.start, ride.end, Subway, Some(Train))))
    assertEquals(ls.head.confidence, 1.0)
    // 取り消し writes back the mode shown before the change.
    val back = ModeOverride(ride.wholeStart, ride.wholeEnd, TimelineBuilder.undoMode(ride), 2)
    assertEquals(corrected(edit, back), Vector(ride))
  }
