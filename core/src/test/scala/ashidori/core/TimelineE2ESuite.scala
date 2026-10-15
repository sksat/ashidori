package ashidori.core

import ashidori.core.sim.*

/** End-to-end: simulated day -> fixes/motion -> timeline -> compare to truth. */
class TimelineE2ESuite extends munit.FunSuite:

  private def runScenario(sc: TrajectorySimulator.Scenario) =
    val out = TrajectorySimulator.run(sc)
    val segments = TimelineBuilder.build(out.fixes, out.motion)
    val report = Evaluation.evaluate(out.truth, segments)
    (out, segments, report)

  for (name, mk) <- Seq("commute" -> (() => Scenarios.commute()), "weekend" -> (() => Scenarios.weekend()), "flight" -> (() => Scenarios.flight())) do
    test(s"$name: all stays detected, few spurious visits, modes mostly right") {
      val (_, segments, r) = runScenario(mk())
      println(s"[$name] ${r.summary}")
      segments.foreach {
        case v: Visit => println(f"  visit ${(v.end - v.start) / 60000}%4d min  (${v.lat}%.4f, ${v.lon}%.4f)")
        case t: Trip  => println(f"  trip  ${(t.end - t.start) / 60000}%4d min  ${t.legs.map(l => s"${l.mode.key}(${"%.1f".format(l.distanceM / 1000)}km)").mkString(" > ")}")
      }
      assertEquals(r.matchedStays, r.truthStays, r.summary)
      assert(r.spuriousVisits <= 1, r.summary)
      assert(r.familyAccuracy >= 0.85, r.summary)
      assert(r.modeAccuracy >= 0.7, r.summary)
    }

  test("different random seeds stay robust") {
    val reports = (10L to 14L).map(s => runScenario(Scenarios.commute(seed = s))._3)
    reports.foreach(r => println(s"[commute seeds] ${r.summary}"))
    assert(reports.forall(_.stayRecall >= 0.8), reports.map(_.summary).mkString("\n"))
    assert(reports.map(_.familyAccuracy).sum / reports.size >= 0.8)
  }

  test("spike removal drops single outliers but keeps fast straight motion") {
    val base = Fix(0, 35.0, 139.0)
    def at(t: Long, dn: Double, de: Double) = base.copy(time = t, lat = 35.0 + dn / 111_320, lon = 139.0 + de / 91_000)
    val walk = Vector(at(0, 0, 0), at(10, 10, 0), at(20, 300, 300), at(30, 20, 0), at(40, 30, 0))
    assertEquals(Geo.removeSpikes(walk).size, 4)
    val train = (0 until 5).map(i => at(i * 5L, i * 150.0, 0))
    assertEquals(Geo.removeSpikes(train).size, 5)
  }

  test("a position jump across the world is not counted as travel") {
    val out = TrajectorySimulator.run(Scenarios.commute())
    // Fixes from another continent in the middle of the day (e.g. emulator default).
    val mid = out.fixes(out.fixes.size / 2).time
    val far = (0 until 20).map(i => Fix(mid + i * 1000L + 1, 37.42, -122.08, 10f))
    def travelled(fixes: Vector[Fix]) = TimelineBuilder.build(fixes, out.motion).collect { case t: Trip => t.distanceM }.sum
    val base = travelled(out.fixes)
    val dist = travelled((out.fixes ++ far).sortBy(_.time))
    // The day itself is about 120 km; the jump would add some 16,000 km.
    assert(dist < base + 10_000, s"distance $dist (without the jump $base)")
  }

  test("legs without good positions are marked uncertain") {
    val (_, segments, _) = runScenario(Scenarios.commute())
    val legs = segments.collect { case t: Trip => t.legs }.flatten
    assert(legs.filter(_.mode == TransportMode.Subway).forall(_.isUncertain), legs.map(l => l.mode -> l.uncertain))
    assert(legs.filter(_.mode == TransportMode.Walking).exists(!_.isUncertain))
  }

  test("timeline alternates visits and trips in time order") {
    val (_, segments, _) = runScenario(Scenarios.commute())
    segments.sliding(2).foreach { case Seq(a, b) =>
      assert(a.end <= b.start + 1, s"$a overlaps $b")
    }
  }
