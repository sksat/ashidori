package ashidori.core

class ReverseGeocoderSuite extends munit.FunSuite:
  /** Built by tools/geocoder/build_index.py from isj-sample.csv and cities-sample.txt. */
  private lazy val geo =
    val in = getClass.getResourceAsStream("/geocoder/fixture.bin")
    try ReverseGeocoder.load(in) finally in.close()

  private def area(lat: Double, lon: Double) = geo.lookup(lat, lon).flatMap(_.area)

  test("loads every point of the fixture") {
    // 8 towns; GeoNames rows for Japan are skipped (11 - 1).
    assertEquals(geo.size, 18)
  }

  test("nearest 大字・町丁目 within 2 km, in the AreaName form") {
    // Shibuya scramble crossing: closer to 宇田川町 than 道玄坂二丁目.
    val p = geo.lookup(35.6620, 139.6975).get
    assertEquals(p.level, GeoLevel.Town)
    assertEquals(p.area, Some("渋谷区宇田川町"))
    assertEquals(p.prefecture, "東京都")
    assertEquals(area(35.6667, 139.7040), Some("渋谷区神宮前"))
    assertEquals(area(35.6895, 139.6918), Some("新宿区西新宿"))
    assertEquals(area(43.0681, 141.3546), Some("札幌市中央区北五条西"))
  }

  test("郡 and 大字 are dropped") {
    assertEquals(area(35.9295, 139.2844), Some("毛呂山町阿諏訪"))
    assertEquals(area(34.8703, 134.3541), Some("上郡町大持"))
  }

  test("falls back to the municipality beyond 2 km") {
    // ~5 km west of 日原 in the mountains of 奥多摩町.
    val p = geo.lookup(35.858, 138.958).get
    assertEquals(p.level, GeoLevel.Municipality)
    assertEquals(p.area, Some("奥多摩町"))
    assertEquals(p.city, "奥多摩町")
  }

  test("cities abroad, preferring Japanese names") {
    val paris = geo.lookup(48.86, 2.35).get
    assertEquals(paris.level, GeoLevel.City)
    assertEquals(paris.area, Some("パリ"))
    assertEquals(paris.country, "フランス")
    assertEquals(area(37.39, -122.08), Some("マウンテンビュー"))
    assertEquals(area(39.91, 116.40), Some("北京"))
  }

  test("nothing at sea or far from any place") {
    assertEquals(geo.lookup(30.0, 150.0), None)
    assertEquals(geo.lookup(0.0, -30.0), None)
    assertEquals(geo.lookup(Double.NaN, 0.0), None)
  }

  test("city and country for period summaries") {
    val p = geo.lookup(43.0681, 141.3546).get
    assertEquals(p.city, "札幌市")
    assertEquals(p.country, "日本")
    assertEquals(geo.lookup(35.6620, 139.6975).get.city, "渋谷区")
  }

  test("search wraps around the antimeridian") {
    // No point there in the fixture, but the search must not fail.
    assertEquals(geo.lookup(0.0, 179.999), None)
    assertEquals(geo.lookup(89.9, -179.999), None)
  }

  test("larger cities claim a wider area") {
    assertEquals(ReverseGeocoder.cityRadius(0), 1_000.0)
    assertEqualsDouble(ReverseGeocoder.cityRadius(50), 6_325.0, 1.0)   // 100,000 people
    assert(ReverseGeocoder.cityRadius(69) > 25_000)                     // New York, ~8 million
  }

  test("rejects other files") {
    val bytes = java.io.ByteArrayOutputStream()
    val gz = java.util.zip.GZIPOutputStream(bytes)
    gz.write("NOPE".getBytes)
    gz.close()
    intercept[IllegalArgumentException](ReverseGeocoder.load(java.io.ByteArrayInputStream(bytes.toByteArray)))
  }

class GeocodePolicySuite extends munit.FunSuite:
  test("the platform geocoder is never asked when sending is blocked") {
    var asked = 0
    def platform = { asked += 1; Some("外部") }
    assertEquals(GeocodePolicy.resolve(None, blockExternal = true, platformPresent = true)(platform), None)
    assertEquals(GeocodePolicy.resolve(Some("渋谷区宇田川町"), blockExternal = true, platformPresent = true)(platform), Some("渋谷区宇田川町"))
    assertEquals(asked, 0)
  }

  test("the platform geocoder only fills gaps of the offline index") {
    var asked = 0
    def platform = { asked += 1; Some("外部") }
    assertEquals(GeocodePolicy.resolve(Some("渋谷区宇田川町"), blockExternal = false, platformPresent = true)(platform), Some("渋谷区宇田川町"))
    assertEquals(asked, 0)
    assertEquals(GeocodePolicy.resolve(None, blockExternal = false, platformPresent = true)(platform), Some("外部"))
    assertEquals(asked, 1)
    assertEquals(GeocodePolicy.resolve(None, blockExternal = false, platformPresent = false)(platform), None)
    assertEquals(asked, 1)
  }
