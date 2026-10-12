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

  test("timeline alternates visits and trips in time order") {
    val (_, segments, _) = runScenario(Scenarios.commute())
    segments.sliding(2).foreach { case Seq(a, b) =>
      assert(a.end <= b.start + 1, s"$a overlaps $b")
    }
  }
