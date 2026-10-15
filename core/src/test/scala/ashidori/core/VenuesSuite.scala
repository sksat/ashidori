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

  test("drops large areas whose center is far away") {
    val json = """{"elements":[{"type":"way","id":9,"center":{"lat":35.69,"lon":139.70},"tags":{"name":"Big Park","leisure":"nature_reserve"}}]}"""
    assertEquals(Overpass.parse(json, 35.658, 139.7016).size, 0)
  }

  test("query mentions the location and radius") {
    val q = Overpass.query(35.0, 139.0, 150)
    assert(q.contains("around:150,35.0,139.0"))
    // A single key-regex statement (fast) rather than a union of statements.
    assert(q.contains("""nwr["name"][~"^(amenity|shop|"""), q)
    assert(q.contains("""aeroway)$"~"."]"""), q)
    assert(!q.contains(");("), q)
    assert(q.startsWith("[out:json]"))
  }

  test("an answer cut at the element limit is not complete") {
    def answer(n: Int) = (1 to n).map(i => s"""{"type":"node","id":$i,"lat":35.658,"lon":139.7016,"tags":{"name":"V$i","shop":"yes"}}""")
      .mkString("""{"elements":[""", ",", "]}")
    assert(Overpass.parseAnswer(answer(3), 35.658, 139.7016)._2)
    assert(!Overpass.parseAnswer(answer(Overpass.Limit), 35.658, 139.7016)._2)
    assert(Overpass.query(35.0, 139.0).contains(s"out center ${Overpass.Limit};"))
  }

  test("a runtime error is a failure when nothing was found, and incomplete otherwise") {
    val remark = "\"remark\":\"runtime error: Query timed out in \\\"query\\\" at line 1 after 10 seconds.\""
    intercept[RuntimeException](Overpass.parseAnswer(s"""{"elements":[],$remark}""", 35.658, 139.7016))
    val partial = s"""{"elements":[{"type":"node","id":1,"lat":35.658,"lon":139.7016,"tags":{"name":"A","shop":"yes"}}],$remark}"""
    val (vs, complete) = Overpass.parseAnswer(partial, 35.658, 139.7016)
    assertEquals(vs.map(_._1.name), Vector("A"))
    assert(!complete)
    // An empty spot is a complete answer.
    assertEquals(Overpass.parseAnswer("""{"elements":[]}""", 35.658, 139.7016), (Vector.empty, true))
  }

  test("timeouts stay within the remaining budget and stop when it is spent") {
    var left = Overpass.BudgetMs
    var tried = 0
    // Every instance uses its full timeouts (the worst case).
    while Overpass.timeouts(tried, left).isDefined do
      val (c, r) = Overpass.timeouts(tried, left).get
      assert(c > 0 && r > 0 && c + r <= left, (tried, left, c, r))
      left -= c + r
      tried += 1
    assert(tried >= 2, tried)
    assert(left >= 0)
    assertEquals(Overpass.timeouts(0, 1_000), None)
  }

  test("tries the last instance that answered first and recently failed ones last") {
    val es = Seq("a", "b", "c", "d")
    assertEquals(Overpass.order(es, None, Map.empty, 0), es)
    assertEquals(Overpass.order(es, Some("c"), Map.empty, 0), Seq("c", "a", "b", "d"))
    assertEquals(Overpass.order(es, None, Map("a" -> 1_000L, "b" -> 2_000L), 60_000), Seq("c", "d", "a", "b"))
    // A failure long ago no longer counts.
    assertEquals(Overpass.order(es, None, Map("a" -> 0L), 3_600_000), es)
    assertEquals(Overpass.Endpoints.head, Overpass.Endpoint)
    assertEquals(Overpass.Endpoints.distinct.size, Overpass.Endpoints.size)
  }

  test("merging keeps what is shown and adds the rest once, by OSM id and then by name, closest first") {
    def v(id: String, name: String, d: Double) = Venue(id, name, "", 0, 0) -> d
    val shown = Vector(v("", "自宅", 5), v("node/1", "東京", 30))
    val more = Vector(v("node/1", "東京駅", 10), v("node/2", "東京", 20), v("node/4", "NewDays", 80),
      v("way/3", "NewDays", 60), v("node/5", "自宅", 1))
    assertEquals(VenueMerge.fresh(shown, more).map(_._1.id), Vector("way/3"))
    assertEquals(VenueMerge.fresh(Vector.empty, more).map(_._1.name), Vector("自宅", "東京駅", "東京", "NewDays"))
  }

