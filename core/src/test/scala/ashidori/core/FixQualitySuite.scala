package ashidori.core

import FixQuality.Source

class FixQualitySuite extends munit.FunSuite:
  private val Min = 60_000L
  private val st = EnergyState(gnss = true, 10_000, "gps", detail = false, still = false, screenOn = true, charging = false,
    "balanced", "balanced", "OnFoot", "Outdoor")

  private def fixes(from: Long, to: Long, everyMs: Long, acc: Float, provider: String = "gps") =
    (from until to by everyMs).map(t => Fix(t, 35.0, 139.0, acc, provider = provider))

  test("splits interval and accuracy by screen state and source") {
    val spans = Seq(
      EnergySpan(0, 30 * Min, st),
      EnergySpan(30 * Min, 60 * Min, st.copy(screenOn = false)),
      EnergySpan(60 * Min, 90 * Min, st.copy(screenOn = false, charging = true)))
    val fs = fixes(0, 30 * Min, 10_000, 5f) ++ fixes(30 * Min, 60 * Min, 20_000, 9f) ++
      fixes(0, 60 * Min, 60_000, 30f, "fused") ++ fixes(60 * Min, 90 * Min, 1_000, 3f)
    val r = FixQuality.analyse(fs, spans, Nil)
    val on = r(Source.Gnss, true).get
    assertEqualsDouble(on.intervalS, 10.0, 1e-9)
    assertEqualsDouble(on.accuracyM, 5.0, 1e-9)
    assertEqualsDouble(on.requestedS, 10.0, 1e-9)
    val off = r(Source.Gnss, false).get
    assertEqualsDouble(off.intervalS, 20.0, 1e-9, "charging fixes (1 s) left out")
    assertEqualsDouble(off.accuracyM, 9.0, 1e-9)
    assertEquals(r(Source.Network, true), Some(FixQuality.Cell(29, 60.0, 30, 30.0, 10.0)))
    // The first fix after the screen turned off starts a new run: no interval across the change.
    assertEquals(r(Source.Network, false), Some(FixQuality.Cell(29, 60.0, 30, 30.0, 10.0)))
  }

  test("gaps over 10 minutes and screen changes do not count as intervals") {
    val spans = Seq(EnergySpan(0, 600 * Min, st.copy(screenOn = false)))
    // A fix every 20 min (GNSS off between): no intervals at all.
    val r = FixQuality.analyse(fixes(0, 600 * Min, 20 * Min, 5f), spans, Nil)
    assertEquals(r(Source.Gnss, false), None)
  }

  test("before spans existed, the battery readings give the screen state") {
    val readings = (0L until 120 * Min by 5 * Min).map(t => BatteryReading(t, 80, false, screenOn = t >= 60 * Min, "balanced"))
    val r = FixQuality.analyse(fixes(0, 120 * Min, 30_000, 8f), Nil, readings)
    assertEqualsDouble(r(Source.Gnss, false).get.intervalS, 30.0, 1e-9)
    assertEqualsDouble(r(Source.Gnss, true).get.intervalS, 30.0, 1e-9)
    assert(r(Source.Gnss, true).get.requestedS.isNaN)
    assert(FixQuality.describe(r).contains("gnss.screenOff: interval=30.0s"))
  }

  test("a week of 1 Hz fixes streams through in fixed memory") {
    // 3 million fixes (five weeks at 1 Hz), made one at a time: none is kept.
    val spans = Seq(EnergySpan(0, 3_000_000_000L, st.copy(screenOn = false)))
    val a = FixQuality.Aggregator(spans, Nil)
    def used() =
      val rt = Runtime.getRuntime
      System.gc()
      rt.totalMemory - rt.freeMemory
    val before = used()
    var i = 0L
    while i < 3_000_000L do
      a.add(Fix(i * 1000L, 35.0, 139.0, (3 + i % 7).toFloat, provider = if i % 10 == 0 then "fused" else "gps"))
      i += 1
    val grown = used() - before
    // Holding the fixes would take hundreds of MB.
    assert(grown < 32L * 1024 * 1024, s"heap grew by ${grown / 1024 / 1024} MB")
    val r = a.result
    assertEqualsDouble(r(Source.Gnss, false).get.intervalS, 1.0, 0.05)
    assertEquals(r(Source.Gnss, false).get.intervals, 2_699_999)
    assertEqualsDouble(r(Source.Network, false).get.intervalS, 10.0, 0.4)
    assertEqualsDouble(r(Source.Gnss, false).get.accuracyM, 6.0, 0.25)
  }

  test("the streaming median is exact for equal values and within its bin otherwise") {
    val m = FixQuality.StreamMedian()
    Seq(5.0, 5.0, 5.0).foreach(m.add)
    assertEquals(m.median, 5.0)
    val n = FixQuality.StreamMedian()
    (1 to 1001).foreach(i => n.add(i.toDouble))
    assertEqualsDouble(n.median, 501.0, 501 * 0.03)
    assert(FixQuality.StreamMedian().median.isNaN)
  }

  test("not enough data") {
    val r = FixQuality.analyse(fixes(0, 3 * Min, 10_000, 5f), Seq(EnergySpan(0, 3 * Min, st)), Nil)
    assert(r.cells.values.forall(_.isEmpty))
  }
