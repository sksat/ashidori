package ashidori.core

class PdrSuite extends munit.FunSuite:
  private val start = Fix(0, 35.0, 139.0, 5f)

  test("walking north 100 steps moves ~70 m north with growing uncertainty") {
    val p = Pdr()
    p.reset(start)
    val fixes = (1 to 100).flatMap(i => p.onStep(i * 600L, 0))
    val last = fixes.last
    assertEqualsDouble(Geo.distance(start, last), 70.0, 1.0)
    assert(last.lat > start.lat)
    assertEqualsDouble(last.lon, start.lon, 1e-9)
    assert(last.accuracy > fixes.head.accuracy)
    assertEquals(last.provider, Pdr.Provider)
  }

  test("square walk returns near the start") {
    val p = Pdr()
    p.reset(start)
    for (h, k) <- Seq(0.0, 90.0, 180.0, 270.0).zipWithIndex; i <- 1 to 20 do p.onStep(k * 100L + i, h)
    val end = p.onStep(9999, 0).get
    assert(Geo.distance(start, end) < 1.5, Geo.distance(start, end).toString)
  }

  test("stride calibration accepts plausible values only") {
    val p = Pdr()
    p.calibrate(80, 100)
    assertEqualsDouble(p.strideM, 0.7 * 0.7 + 0.8 * 0.3, 1e-9)
    p.calibrate(300, 100) // 3 m strides: not walking
    assertEqualsDouble(p.strideM, 0.7 * 0.7 + 0.8 * 0.3, 1e-9)
  }

  test("a disagreeing network fix pulls the estimate back") {
    val p = Pdr()
    p.reset(start)
    (1 to 300).foreach(i => p.onStep(i, 90)) // ~210 m east
    val net = Fix(1000, 35.0, 139.0, 40f, provider = "network")
    p.onNetworkFix(net)
    val next = p.onStep(1001, 90).get
    assert(Geo.distance(net, next) < 2, Geo.distance(net, next).toString)
  }

  test("no estimate before a starting fix") {
    assertEquals(Pdr().onStep(0, 0), None)
  }
