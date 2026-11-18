package ashidori.core

class PrecisionSuite extends munit.FunSuite:
  test("snap: the center of the fixed cell, the same for every point of it") {
    assertEquals(Precision.snap(35.6812, 139.7671, Level.District), (35.685, 139.765))
    assertEquals(Precision.snap(35.6899, 139.7601, Level.District), (35.685, 139.765))
    assertEquals(Precision.snap(35.6812, 139.7671, Level.Area), (35.65, 139.75))
    assertEquals(Precision.snap(35.6812, 139.7671, Level.Exact), (35.6812, 139.7671))
    // A value printed as a cell's edge belongs to that cell.
    assertEquals(Precision.snap(0.29, 0.29, Level.District), (0.295, 0.295))
    assertEquals(Precision.snap(35.7, 139.7, Level.Area), (35.75, 139.75))
  }

  test("snap: negative coordinates, the 180° meridian and the poles") {
    assertEquals(Precision.snap(-33.8688, -151.2093, Level.District), (-33.865, -151.205))
    assertEquals(Precision.snap(-0.001, -0.001, Level.District), (-0.005, -0.005))
    assertEquals(Precision.snap(10, 180.0, Level.Area), (10.05, -179.95))
    assertEquals(Precision.snap(10, -180.0, Level.Area), (10.05, -179.95))
    assertEquals(Precision.snap(10, 179.999, Level.Area), (10.05, 179.95))
    assertEquals(Precision.snap(10, 539.95, Level.Area), (10.05, 179.95))
    assertEquals(Precision.snap(90, 0, Level.Area), (89.95, 0.05))
    assertEquals(Precision.snap(-90, 0, Level.Area), (-89.95, 0.05))
  }

  test("zoom limits and grids of the levels") {
    assertEquals(Seq(Level.Area, Level.District, Level.Exact).map(Precision.maxZoom), Seq(Some(12), Some(15), None))
    assertEquals(Seq(Level.Area, Level.District, Level.Exact).map(Precision.gridDeg), Seq(Some(0.1), Some(0.01), None))
  }

  test("the venue search at District sends the 3×3 cell box, not one digit of the position beyond the cell") {
    val (lat, lon) = (35.681236, 139.767125)
    val q = Overpass.queryBox(lat, lon)
    assert(q.contains("(35.67,139.75,35.70,139.78)"), q)
    for digits <- Seq("35.681", "139.767", "681236", "767125", "35.68", "139.76") do
      assert(!q.contains(digits), s"$digits in $q")
    // Every position of the cell gives the same query.
    assertEquals(Overpass.queryBox(35.6899, 139.7601), q)
    assertEquals(Overpass.queryAt(Level.District, lat, lon, 200), (q, Overpass.BoxLimit))
    assertEquals(Overpass.queryAt(Level.Exact, lat, lon, 200), (Overpass.query(lat, lon, 200), Overpass.Limit))
    assertEquals(Precision.districtBox(-0.001, 179.999), ("-0.02", "179.98", "0.01", "180.00"))
  }
