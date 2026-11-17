package ashidori.core

import TransportMode.*

/** 飛行機 only with evidence of a flight ([[ModeClassifier.flying]]), never
  * for a Shinkansen or a short fast stretch ([[FastDay]]).
  */
class FlyingSuite extends munit.FunSuite:
  import FastDay.at
  private lazy val day = FastDay.day()
  private lazy val segs = TimelineBuilder.build(day.fixes, day.motion)
  private def legs(ss: Seq[Segment]) = ss.collect { case t: Trip => t.legs }.flatten
  private def tripAt(ss: Seq[Segment], h: Int): Vector[Leg] =
    ss.collect { case t: Trip if t.start >= at(h, 0) && t.start < at(h, 50) => t.legs.filter(_.mode != Still) }.head
  private def show(ss: Seq[Segment]) = ss.map {
    case t: Trip => t.legs.map(l => s"${l.mode.key} ${(l.end - l.start) / 1000}s ${l.medianSpeed.round}m/s").mkString("trip[", ", ", "]")
    case s       => s.getClass.getSimpleName
  }.mkString("\n")

  test("a Shinkansen at 85 m/s between two tunnels is one train leg, with its slower ends") {
    val parts = tripAt(TimelineBuilder.unmerged(day.fixes, day.motion), 9)
    assert(parts.size > 1 && !parts.exists(_.mode == Flying), show(TimelineBuilder.unmerged(day.fixes, day.motion)))
    val ls = tripAt(segs, 9)
    assertEquals(ls.map(_.mode), Vector(Train), show(segs))
    assert(ls.head.durationMs >= 17 * 60_000L, show(segs))
  }

  test("a flight that climbs to 10 km at 230 m/s for an hour is one flight leg") {
    val fl = tripAt(segs, 11).filter(_.mode == Flying)
    assertEquals(fl.size, 1, show(segs))
    assert(fl.head.durationMs >= 80 * 60_000L, show(segs))
  }

  test("200 s of positions at 90 or 150 m/s in a drive is no flight") {
    for h <- Seq(15, 16) do
      assert(!tripAt(segs, h).exists(_.mode == Flying), show(segs))
      assert(!tripAt(TimelineBuilder.unmerged(day.fixes, day.motion), h).exists(_.mode == Flying), show(segs))
  }

  test("Hong Kong to Haneda, long, high and fast with a fix every few minutes, stays a flight") {
    val d = FastDay.hongKongHaneda()
    val built = TimelineBuilder.build(d.fixes, d.motion)
    assertEquals(built.collect { case u: Unrecorded => u }, Vector.empty, show(built))
    val fl = legs(built).filter(_.mode == Flying)
    assertEquals(fl.size, 1, show(built))
    assert(fl.head.durationMs >= 3 * 3_600_000L, show(built))
  }

  test("the classifier: speed above any train, or fast and high, for at least 10 min") {
    def f(min: Int, v: Double, climb: Double = Double.NaN) =
      LegFeatures(min * 60_000L, v * min * 60, v, v, v, Double.NaN, Double.NaN, 0.0, 0.0, climb)
    def mode(x: LegFeatures) = ModeClassifier.classify(x)._1
    assertEquals(mode(f(60, 230)), Flying)
    assertEquals(mode(f(10, 110)), Flying)
    assertEquals(mode(f(9, 230)), Train)
    assertEquals(mode(f(30, 85)), Train)
    assertEquals(mode(f(30, 85, climb = 900)), Train)
    assertEquals(mode(f(30, 85, climb = 3_000)), Flying)
    assertEquals(mode(f(5, 85, climb = 3_000)), Train)
  }

  test("記録なし uses the same evidence: a flight without positions is a trip, a Shinkansen 記録なし") {
    def silent(km: Double, hours: Double, alt: Double) =
      val b = FastDay.Builder(at(6, 0), 35.0, 139.0, alt)
      b.stay(30)
      b.outage((hours * 3600).toInt, km * 1000 / (hours * 3600))
      b.stay(30)
      TimelineBuilder.build(b.result.fixes, Vector.empty)
    // 2,900 km in 4 h: faster than any train.
    val flight = silent(2_900, 4, 10)
    assertEquals(flight.collect { case u: Unrecorded => u }, Vector.empty, show(flight))
    assertEquals(legs(flight).map(_.mode), Vector(Flying))
    // 515 km in 2.5 h (57 m/s): a Shinkansen as well; nothing says how.
    assertEquals(silent(515, 2.5, 40).collect { case u: Unrecorded => 1 }.size, 1)
  }
