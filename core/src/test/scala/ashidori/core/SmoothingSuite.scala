package ashidori.core

import ModeClassifier.Coarse
import Coarse.*

/** Ties in the duration-weighted smoothing of coarse classes are broken the
  * same way every time, and as in the web viewer (smoothing-ties.json).
  */
class SmoothingSuite extends munit.FunSuite:
  private def smooth(cs: Seq[Coarse], dt: Seq[Long] = Nil) =
    TimelineBuilder.smooth(cs.toIndexedSeq, (if dt.isEmpty then cs.map(_ => 5_000L) else dt).toIndexedSeq).toVector

  test("on equal weight, an interval keeps its own class") {
    assertEquals(smooth(Seq(Mid, Mid, Vehicle, Vehicle)), Vector(Mid, Mid, Vehicle, Vehicle))
    assertEquals(smooth(Seq(Mid, Gap, Vehicle), Seq(10_000L, 100_000L, 10_000L)), Vector(Mid, Gap, Vehicle))
  }

  test("else the first class in order") {
    assertEquals(smooth(Seq(Vehicle, Mid, Still, Vehicle, Mid))(2), Mid)
    assertEquals(smooth(Seq(Foot, Vehicle, Still, Foot, Vehicle))(2), Foot)
  }

  test("weights are durations, not counts") {
    // At the middle: Still 10 s, Vehicle 3 + 7 s, Foot 4 s, Mid 6 s.
    assertEquals(smooth(Seq(Still, Vehicle, Vehicle, Foot, Mid), Seq(10_000L, 3_000L, 7_000L, 4_000L, 6_000L))(2), Vehicle)
    assertEquals(smooth(Seq(Still, Vehicle, Mid, Foot, Mid), Seq(10_000L, 3_000L, 7_000L, 4_000L, 6_000L))(2), Mid)
  }
