package ashidori.core

class LiveSeriesSuite extends munit.FunSuite:
  private val Ms = 1_000_000L

  test("the ring keeps the newest samples") {
    val s = LiveSeries(3, 4)
    for i <- 1 to 6 do s.add(i * Ms, i.toFloat, -i.toFloat, 0f)
    assertEquals(s.size, 4)
    assertEquals(s.lastTime, 6 * Ms)
    assertEquals(s.last(0), 6f)
    assertEquals(s.last(1), -6f)
    assertEquals(s.times(0, Long.MaxValue), Vector(3, 4, 5, 6).map(_ * Ms))
    assertEquals(s.firstFrom(4 * Ms, 0), 4f)
    assertEquals(s.range(0, Long.MaxValue), Some((-6f, 6f)))
    s.clear()
    assert(s.last(0).isNaN)
    assertEquals(s.range(0, Long.MaxValue), None)
  }

  test("50 Hz in fewer columns than samples: every column holds its extremes") {
    val s = LiveSeries(1, 1024)
    // 10 s at 50 Hz, a spike in the middle.
    for i <- 0 until 500 do s.add(i * 20 * Ms, if i == 250 then 9f else (i % 2).toFloat)
    val e = LiveSeries.Envelope(100)
    s.envelope(0, 0, 10_000 * Ms, e, maxGap = 200 * Ms)
    assert(e.present.forall(identity))
    assertEquals(e.breakBefore.count(identity), 1)
    assert(e.breakBefore(0))
    assertEquals(e.max(50), 9f)
    assertEquals((0 until 100).filter(_ != 50).map(e.max(_)).max, 1f)
    assertEquals(e.min.min, 0f)
  }

  test("more columns than samples: empty columns, lines broken only at silences") {
    val s = LiveSeries(1, 64)
    for i <- 0 until 10 do s.add(i * 100 * Ms, i.toFloat)
    // A 2 s silence, then more.
    for i <- 0 until 5 do s.add((3_000 + i * 100) * Ms, i.toFloat)
    val e = LiveSeries.Envelope(400)
    s.envelope(0, 0, 4_000 * Ms, e, maxGap = 500 * Ms)
    assertEquals(e.present.count(identity), 15)
    val breaks = (0 until 400).filter(e.breakBefore)
    assertEquals(breaks, Vector(0, 300))
  }

  test("delivered rate and the std of the vector length") {
    val s = LiveSeries(3, 512)
    for i <- 0 until 100 do s.add(i * 20 * Ms, 0f, 0f, 9.81f)
    assertEqualsDouble(s.rateHz(0), 50.0, 1e-9)
    assertEqualsDouble(s.magnitudeStd(0).toDouble, 0.0, 1e-6)
    assert(s.rateHz(10_000 * Ms).isNaN)
    assertEqualsDouble(s.mean(1_000 * Ms, 2), 9.81, 1e-5)
    assert(s.mean(10_000 * Ms, 2).isNaN)
    val w = LiveSeries(3, 512)
    for i <- 0 until 100 do w.add(i * 20 * Ms, 0f, 0f, if i % 2 == 0 then 9f else 11f)
    assertEqualsDouble(w.magnitudeStd(0).toDouble, 1.005, 0.001)
  }

  test("the scale stays fixed until the data leaves it") {
    assertEquals(LiveSeries.axisRange(-4f, 16f, Some((-1f, 12f)), 4f), (-4f, 16f))
    assertEquals(LiveSeries.axisRange(-4f, 16f, Some((-9f, 21f)), 4f), (-12f, 24f))
    assertEquals(LiveSeries.axisRange(-1f, 1f, None, 1f), (-1f, 1f))
  }

  test("signed values keep their width") {
    assertEquals(LiveSeries.signed(0.123, 2), "+0.12")
    assertEquals(LiveSeries.signed(-9.806, 2), "−9.81")
    assertEquals(LiveSeries.signed(-0.001, 2), "+0.00")
    assertEquals(LiveSeries.signed(Double.NaN, 2), "—")
  }

  test("compass points and accuracy") {
    assertEquals(Compass.point(0), "北")
    assertEquals(Compass.point(22.4), "北")
    assertEquals(Compass.point(22.6), "北東")
    assertEquals(Compass.point(359), "北")
    assertEquals(Compass.point(-90), "西")
    assertEquals(Compass.point(135), "南東")
    assertEqualsDouble(Compass.normalize(-10), 350.0, 1e-9)
    assertEquals(Compass.accuracy(3), "高")
    assertEquals(Compass.accuracy(0), "要調整")
    assertEquals(Compass.accuracy(-1), "要調整")
  }
