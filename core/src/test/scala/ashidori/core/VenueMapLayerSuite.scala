package ashidori.core

import VenueMapLayer.Group

class VenueMapLayerSuite extends munit.FunSuite:
  private val c = LatLng(35.6812, 139.7671)
  private var seq = 0
  /** A venue `dy`, `dx` metres north and east of `c`. */
  private def at(name: String, key: String, value: String, dy: Double = 0, dx: Double = 0) =
    seq += 1
    val lat = c.lat + dy / 111_320.0
    val lon = c.lon + dx / (111_320.0 * math.cos(math.toRadians(c.lat)))
    VenueIndex.Tagged(Venue(s"node/$seq", name, Overpass.label(key, value), lat, lon), key, value, VenueIndex.normalize(name))

  test("visible range: 15 for stations and major places, 16 for most, 17 for offices") {
    def mz(k: String, v: String) = VenueMapLayer.style(k, v).minZoom
    for (k, v) <- Seq("railway" -> "station", "public_transport" -> "station", "shop" -> "mall",
                      "shop" -> "department_store", "shop" -> "supermarket", "tourism" -> "museum",
                      "leisure" -> "park", "amenity" -> "university", "amenity" -> "hospital",
                      "amenity" -> "townhall", "amenity" -> "library") do
      assertEquals(mz(k, v), 15, s"$k=$v")
    for (k, v) <- Seq("shop" -> "convenience", "amenity" -> "restaurant", "amenity" -> "cafe",
                      "shop" -> "clothes", "amenity" -> "bank", "office" -> "government") do
      assertEquals(mz(k, v), 16, s"$k=$v")
    assertEquals(mz("office", "company"), 17)
  }

  test("groups, colours and sprites of the Protomaps light style") {
    val station = VenueMapLayer.style("railway", "station")
    assertEquals((station.group, station.icon), (Group.Transit, "train_station"))
    assertEquals(VenueMapLayer.style("amenity", "cafe").group, Group.Food)
    assertEquals(VenueMapLayer.style("amenity", "cafe").icon, "cafe")
    assertEquals(VenueMapLayer.style("shop", "convenience").icon, "convenience")
    // No sprite of that name: a dot in the group colour.
    assertEquals(VenueMapLayer.style("shop", "bakery"), VenueMapLayer.style("shop", "bakery").copy(icon = ""))
    assertEquals(VenueMapLayer.style("shop", "bakery").group, Group.Shop)
    assertEquals(VenueMapLayer.style("office", "government").group, Group.Civic)
    assertEquals(VenueMapLayer.style("historic", "castle").group, Group.Culture)
    assert(Group.values.map(_.color).distinct.size == Group.values.size)
  }

  test("collision order: stations first; from zoom 17 shops before the malls holding them") {
    val mall = VenueMapLayer.style("shop", "mall")
    val dept = VenueMapLayer.style("shop", "department_store")
    val shop = VenueMapLayer.style("shop", "clothes")
    val food = VenueMapLayer.style("amenity", "restaurant")
    val station = VenueMapLayer.style("railway", "station")
    val office = VenueMapLayer.style("office", "company")
    assert(station.rankAt(16) < mall.rankAt(16))
    assert(mall.rankAt(16) < shop.rankAt(16) && dept.rankAt(16.9) < food.rankAt(16.9))
    for z <- Seq(17.0, 18.5) do
      assert(shop.rankAt(z) < mall.rankAt(z) && food.rankAt(z) < dept.rankAt(z), z)
      assert(mall.rankAt(z) < office.rankAt(z) && station.rankAt(z) < shop.rankAt(z), z)
  }

  test("select: by zoom, the next level ahead, nothing far out") {
    val vs = Seq(at("東京", "railway", "station"), at("大丸", "shop", "department_store", 30),
      at("カフェ A", "amenity", "cafe", 60), at("株式会社 B", "office", "company", 90))
    def names(z: Double) = VenueMapLayer.select(vs, z, c, 100).map(_.venue.name).toSet
    assertEquals(names(13.9), Set.empty[String])
    assertEquals(names(14.5), Set("東京", "大丸"))
    assertEquals(names(15.2), Set("東京", "大丸", "カフェ A"))
    assertEquals(names(16), Set("東京", "大丸", "カフェ A", "株式会社 B"))
  }

  test("select: same name within 50 m merged, the first by rank kept; farther apart both") {
    val vs = Seq(at("ＳＴＡＲＢＵＣＫＳ", "amenity", "cafe", 0, 0), at("Starbucks", "amenity", "cafe", 30, 0),
      at("starbucks", "amenity", "cafe", 400, 0), at("東京", "railway", "station", 10, 10),
      at("東京", "public_transport", "station", 20, 20))
    val sel = VenueMapLayer.select(vs, 16, c, 100)
    assertEquals(sel.count(i => VenueIndex.normalize(i.venue.name) == "starbucks"), 2)
    assertEquals(sel.count(_.venue.name == "東京"), 1)
    // The nearer of the two close ones (same rank) stays.
    assert(sel.exists(_.venue.id == vs.head.venue.id))
  }

  test("select: a station's nodes per line within 400 m are one; shops of a chain 100 m apart are two") {
    val vs = Seq(at("東京", "railway", "station"), at("東京", "railway", "station", 250, 120),
      at("東京", "railway", "station", 900), at("ローソン", "shop", "convenience", 100), at("ローソン", "shop", "convenience", 200))
    val sel = VenueMapLayer.select(vs, 17, c, 100)
    assertEquals(sel.count(_.venue.name == "東京"), 2)
    assertEquals(sel.count(_.venue.name == "ローソン"), 2)
  }

  test("select: capped, by rank and then distance from the centre") {
    val shops = (1 to 50).map(i => at(s"店 $i", "shop", "clothes", i * 10.0))
    val station = at("駅", "railway", "station", 2_000)
    val sel = VenueMapLayer.select(shops :+ station, 16, c, 10)
    assertEquals(sel.size, 10)
    assertEquals(sel.head.venue.name, "駅")
    assertEquals(sel.tail.map(_.venue.name), (1 to 9).map(i => s"店 $i").toVector)
    // Unnamed venues are not drawn.
    assertEquals(VenueMapLayer.select(Seq(at("", "shop", "clothes")), 16, c, 10), Vector.empty)
  }
