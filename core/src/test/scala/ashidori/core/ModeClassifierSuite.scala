package ashidori.core

class ModeClassifierSuite extends munit.FunSuite:
  private def gap(durationMs: Long, distanceM: Double) =
    val v = distanceM / (durationMs / 1000.0)
    LegFeatures(durationMs, distanceM, v, v, v, Double.NaN, Double.NaN, 0.0, 0.9)

  test("a short GNSS gap at train speed is a subway ride") {
    assertEquals(ModeClassifier.classify(gap(20 * 60_000L, 12_000))._1, TransportMode.Subway)
  }

  test("an hours-long gap is not a subway ride") {
    assertEquals(ModeClassifier.classify(gap(10 * 3_600_000L, 830_000))._1, TransportMode.Unknown)
    assertEquals(ModeClassifier.classify(gap(80 * 60_000L, 100_000))._1, TransportMode.Unknown)
  }
