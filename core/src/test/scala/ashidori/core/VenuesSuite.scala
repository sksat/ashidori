package ashidori.core

class VenuesSuite extends munit.FunSuite:
  test("parses nodes and ways with center, sorted by distance, with Japanese labels") {
    val json =
      """{"elements":[
        |{"type":"way","id":2,"center":{"lat":35.6585,"lon":139.7020},"tags":{"name":"Shibuya Station","name:ja":"渋谷駅","railway":"station"}},
        |{"type":"node","id":1,"lat":35.6581,"lon":139.7017,"tags":{"name":"Cafe A","amenity":"cafe"}},
        |{"type":"node","id":3,"lat":35.6600,"lon":139.7000,"tags":{"shop":"convenience"}}
        |]}""".stripMargin
    val vs = Overpass.parse(json, 35.6580, 139.7016)
    assertEquals(vs.map(_._1.name), Vector("Cafe A", "渋谷駅"))
    assertEquals(vs.head._1.category, "カフェ")
    assertEquals(vs(1)._1.id, "way/2")
    assert(vs.head._2 < vs(1)._2)
  }

  test("query mentions the location and radius") {
    val q = Overpass.query(35.0, 139.0, 150)
    assert(q.contains("around:150,35.0,139.0"))
    assert(q.startsWith("[out:json]"))
  }
