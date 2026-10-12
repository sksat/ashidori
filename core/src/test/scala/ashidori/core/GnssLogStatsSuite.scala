package ashidori.core

class GnssLogStatsSuite extends munit.FunSuite:
  private def raw(t: Long, nanos: Long, const: Int, sv: Int, cn0: Double, adrState: Int, hz: Double): String =
    val cols = GnssLogFormat.RawHeader.split(',').toIndexedSeq
    val values = Map(
      "Raw" -> "Raw", "utcTimeMillis" -> t.toString, "TimeNanos" -> nanos.toString, "Svid" -> sv.toString,
      "Cn0DbHz" -> cn0.toString, "AccumulatedDeltaRangeState" -> adrState.toString,
      "CarrierFrequencyHz" -> hz.toString, "ConstellationType" -> const.toString
    )
    cols.map(c => values.getOrElse(c, "")).mkString(",")

  test("summarizes a GnssLogger-format log using the header columns") {
    val log =
      GnssLogFormat.header("t", "15", "SHARP", "SH-M26").linesIterator.toSeq ++ Seq(
        raw(1000, 1, 1, 3, 40, 1, 1.57542e9),
        raw(1000, 1, 1, 3, 35, 0, 1.17645e9),
        raw(1000, 1, 6, 11, 30, 0, 1.57542e9),
        raw(2000, 2, 1, 3, 41, 1, 1.57542e9),
        GnssLogFormat.row("Fix", "gps", 35.0, 139.0, 40.0, 1.0f, 4.5f, Float.NaN, 2000L)
      )
    val s = GnssLogStats.summarize(log.iterator)
    assertEquals(s.epochs, 2)
    assertEquals(s.measurements, 4)
    assertEquals(s.durationMs, 1000L)
    assertEquals(s.constellations("GPS"), (1, Set("L1", "L5")))
    assertEquals(s.constellations("Galileo")._1, 1)
    assertEqualsDouble(s.adrValidRatio, 0.5, 1e-9)
    assertEqualsDouble(s.meanCn0, (40 + 35 + 30 + 41) / 4.0, 1e-9)
    assertEquals(s.fixes, 1)
    assertEqualsDouble(s.bestAccuracyM, 4.5, 1e-6)
  }
