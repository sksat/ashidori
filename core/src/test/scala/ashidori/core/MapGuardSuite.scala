package ashidori.core

import MapGuard.Verdict.{Pass, Reject}
import OutboundFixtures.*

class MapGuardSuite extends munit.FunSuite:
  private val customTemplate = "https://cyberjapandata.gsi.go.jp/mine/{z}/{x}/{y}.png?key=abc"
  private def types(d: Dest): Option[UrlType] = d match
    case Dest.Ofm => Some(UrlType.Ofm)
    case Dest.Gsi => Some(UrlType.Gsi)
    case Dest.CustomTiles => UrlType.template(customTemplate)
    case _ => None

  private def setup(p: Policy = Policy.fromBlock(false)) =
    val g = gate(p)
    val tokens = TokenTable(Map.empty)
    (g, tokens, MapGuard(g, tokens, types))

  private def gsiTile(t: String, z: Int = 14) = TileStyle.withToken(s"https://cyberjapandata.gsi.go.jp/xyz/std/$z/14552/6451.png", t)

  test("a request without a token, or with an unknown one, is refused") {
    val (_, _, guard) = setup()
    assertEquals(guard.check("https://cyberjapandata.gsi.go.jp/xyz/std/14/1/2.png"), Reject("no token"))
    assertEquals(guard.check(gsiTile("0123456789abcdef0123456789abcdef")), Reject("unknown token"))
  }

  test("a request passes with its holder's valid permit, and goes out without the token") {
    val (g, _, guard) = setup()
    val t = guard.hold("snapshot", Object(), g.permit(Dest.Gsi, Mode.Auto).get)
    guard.check(gsiTile(t)) match
      case Pass(url, k, _, zoom) =>
        assertEquals(url, "https://cyberjapandata.gsi.go.jp/xyz/std/14/14552/6451.png")
        assert(!url.contains(TileStyle.Param))
        assertEquals((k.holder, k.dest, zoom), ("snapshot", Dest.Gsi, Some(14)))
      case r => fail(r.toString)
  }

  test("the token decides the destination: a custom URL on a built-in map's host is told apart by its path") {
    val (g, _, guard) = setup()
    val gsi = guard.hold("map", Object(), g.permit(Dest.Gsi, Mode.Auto).get)
    val custom = guard.hold("map", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
    val customTile = "https://cyberjapandata.gsi.go.jp/mine/14/14552/6451.png?key=abc"
    assert(guard.check(TileStyle.withToken(customTile, custom)).isInstanceOf[Pass])
    // The GSI token on the custom URL, and the custom token on a GSI URL.
    assertEquals(guard.check(TileStyle.withToken(customTile, gsi)), Reject("not a gsi URL"))
    assertEquals(guard.check(gsiTile(custom)), Reject("not a custom URL"))
    // Another host with a valid token.
    assertEquals(guard.check(TileStyle.withToken("https://evil.example.net/xyz/std/14/1/2.png", gsi)), Reject("not a gsi URL"))
  }

  test("a token of an old rule, of a paused gate, or of an earlier process is refused") {
    val (g, tokens, guard) = setup()
    val owner = Object()
    val t = guard.hold("snapshot", owner, g.permit(Dest.Ofm, Mode.Auto).get)
    val tile = TileStyle.withToken("https://tiles.openfreemap.org/planet/20261117_001001_pt/14/14552/6451.pbf", t)
    assert(guard.check(tile).isInstanceOf[Pass])
    // A level-only change: the old token is stale.
    g.setRule(Dest.Ofm, Rule(When.Auto, Level.District))
    assert(guard.check(tile).isInstanceOf[Reject])
    val t2 = guard.hold("snapshot", owner, g.permit(Dest.Ofm, Mode.Auto).get)
    val tile2 = TileStyle.withToken("https://tiles.openfreemap.org/planet/20261117_001001_pt/14/14552/6451.pbf", t2)
    assert(guard.check(tile2).isInstanceOf[Pass])
    // Paused: refused, and still after the pause until the holder takes a new permit.
    g.setPaused(true)
    assertEquals(guard.check(tile2), Reject("permit no longer valid"))
    g.setPaused(false)
    assertEquals(guard.check(tile2), Reject("permit no longer valid"))
    // A restart: the same stored tokens, but the holder's permit of the earlier process is gone.
    val again = SendGate(g.snapshot, _ => (), java.util.Random(1))
    val restarted = MapGuard(again, TokenTable(tokens.entries), types)
    assertEquals(restarted.check(tile2), Reject("snapshot holds no permit"))
  }

  test("an on-action map passes only while a view of its holder holds the grant") {
    val (g, _, guard) = setup(Policy.fresh)
    val view = Object()
    g.remember("map", GsiStd, Dest.Gsi)
    val p = g.permit(Dest.Gsi, Mode.OnAction).get
    val t = guard.hold("map", view, p)
    assertEquals(guard.check(gsiTile(t)), Reject("map not on screen"))
    g.grant(view, p)
    assert(guard.check(gsiTile(t)).isInstanceOf[Pass])
    // The view left the screen.
    g.revoke(view)
    assertEquals(guard.check(gsiTile(t)), Reject("map not on screen"))
    // Another surface's grant does not let this holder send.
    val other = Object()
    guard.hold("period", other, p)
    g.grant(other, p)
    assertEquals(guard.check(gsiTile(t)), Reject("map not on screen"))
  }

  test("a tile over the level's zoom is refused; sprites and fonts of the map's host pass") {
    val (g, _, guard) = setup(Policy.fromBlock(false).updated(Dest.Gsi, Rule(When.Auto, Level.District)).updated(Dest.Ofm, Rule(When.Auto, Level.Area)))
    val t = guard.hold("snapshot", Object(), g.permit(Dest.Gsi, Mode.Auto).get)
    assert(guard.check(gsiTile(t, 15)).isInstanceOf[Pass])
    assertEquals(guard.check(gsiTile(t, 16)), Reject("zoom 16 over the level"))
    val o = guard.hold("snapshot", Object(), g.permit(Dest.Ofm, Mode.Auto).get)
    assertEquals(guard.check(TileStyle.withToken("https://tiles.openfreemap.org/planet/v1/13/1/2.pbf", o)), Reject("zoom 13 over the level"))
    assert(guard.check(TileStyle.withToken("https://tiles.openfreemap.org/planet/v1/12/1/2.pbf", o)).isInstanceOf[Pass])
    for u <- Seq("https://tiles.openfreemap.org/sprites/ofm_f384/ofm@2x.json?ashidori-t=" + o,
                 TileStyle.withToken("https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/0-255.pbf", o)) do
      assert(guard.check(u).isInstanceOf[Pass], u)
    // A raster map has nothing but tiles.
    assertEquals(guard.check(TileStyle.withToken("https://cyberjapandata.gsi.go.jp/xyz/std/sprite.json", t)), Reject("not a gsi URL"))
  }

  test("a redirect is checked again: same destination's URLs, permit still valid, zoom in the level") {
    val (g, _, guard) = setup()
    val t = guard.hold("snapshot", Object(), g.permit(Dest.Gsi, Mode.Auto).get)
    val Pass(_, k, _, _) = guard.check(gsiTile(t)): @unchecked
    assert(guard.recheck(k, "https://cyberjapandata.gsi.go.jp/xyz/std/14/1/2.png").isInstanceOf[Pass])
    assertEquals(guard.recheck(k, "https://evil.example.net/xyz/std/14/1/2.png"), Reject("not a gsi URL"))
    g.setPaused(true)
    assertEquals(guard.recheck(k, "https://cyberjapandata.gsi.go.jp/xyz/std/14/1/2.png"), Reject("permit no longer valid"))
  }

  test("a later hop needs the holder and its grant as the first: a revoke or release after the check refuses it") {
    val (g, _, guard) = setup(Policy.fresh)
    val view = Object()
    g.remember("map", GsiStd, Dest.Gsi)
    val p = g.permit(Dest.Gsi, Mode.OnAction).get
    g.grant(view, p)
    val t = guard.hold("map", view, p)
    val Pass(_, k, _, _) = guard.check(gsiTile(t)): @unchecked
    val next = "https://cyberjapandata.gsi.go.jp/xyz/std/14/1/3.png"
    assert(guard.recheck(k, next).isInstanceOf[Pass])
    g.revoke(view)
    assertEquals(guard.recheck(k, next), Reject("map not on screen"))
    g.grant(view, p)
    assert(guard.recheck(k, next).isInstanceOf[Pass])
    guard.release("map", view)
    assertEquals(guard.recheck(k, next), Reject("map holds no permit"))
  }

  test("OpenFreeMap: each kind by its own path; any other path on its host is refused") {
    val (g, _, guard) = setup(Policy.fromBlock(false).updated(Dest.Ofm, Rule(When.Auto, Level.District)))
    val o = guard.hold("snapshot", Object(), g.permit(Dest.Ofm, Mode.Auto).get)
    def verdict(u: String) = guard.check(TileStyle.withToken(u, o))
    for u <- Seq("https://tiles.openfreemap.org/planet/20261115_080022_pt/14/1/2.pbf",
                 "https://tiles.openfreemap.org/natural_earth/ne2sr/6/1/2.png",
                 "https://tiles.openfreemap.org/sprites/ofm_f384/ofm@2x.json",
                 "https://tiles.openfreemap.org/sprites/ofm_f384/ofm.png",
                 "https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/0-255.pbf") do
      assert(verdict(u).isInstanceOf[Pass], u)
    for u <- Seq("https://tiles.openfreemap.org/styles/liberty", "https://tiles.openfreemap.org/planet",
                 "https://tiles.openfreemap.org/anything/else.json", "https://tiles.openfreemap.org/planet/v/14/1/2.pbf?x=1",
                 "http://tiles.openfreemap.org/planet/v/14/1/2.pbf") do
      assertEquals(verdict(u), Reject("not a ofm URL"), u)
    // Any z/x/y-like segments carry a zoom, whatever the kind.
    assertEquals(UrlType.Ofm.classify("https://tiles.openfreemap.org/sprites/18/1/2.png"), None)
    assertEquals(UrlType.zxy("https://t.example.net/a/18/1/2.pbf?x=1"), Some(18))
    assertEquals(UrlType.template("https://t.example.net/x/17/1/2/{z}/{x}/{y}.png").flatMap(_.classify("https://t.example.net/x/17/1/2/12/1/2.png")),
      Some("tile" -> Some(17)))
    // A redirect target with a zoom over the level, of a known kind or not.
    val Pass(_, k, _, _) = verdict("https://tiles.openfreemap.org/planet/v/14/1/2.pbf"): @unchecked
    assertEquals(guard.recheck(k, "https://tiles.openfreemap.org/planet/v/18/1/2.pbf"), Reject("zoom 18 over the level"))
    assertEquals(guard.recheck(k, "https://tiles.openfreemap.org/planet/v/18/1/2.pbf?x=1"), Reject("not a ofm URL"))
    assertEquals(UrlType.Ofm.hosts, Set("tiles.openfreemap.org"))
  }

  test("tokens: one per key, kept across a restart, dropped with their generation") {
    var stored = Map.empty[TokenKey, String]
    val table = TokenTable(Map.empty, t => stored = t)
    val k = TokenKey("map", Dest.Gsi, Mode.OnAction, Level.Exact, 42)
    val t = table.token(k)
    assertEquals(table.token(k), t)
    assertEquals(t.length, 32)
    assertNotEquals(table.token(k.copy(holder = "period")), t)
    assertEquals(TokenTable.read(TokenTable.encode(stored)), stored)
    assertEquals(TokenTable(TokenTable.read(TokenTable.encode(stored))).token(k), t)
    table.token(k.copy(gen = 43))
    assertEquals(table.lookup(t), None)
    assertEquals(TokenTable.read("{bad"), Map.empty[TokenKey, String])
  }

  test("the mark: added and taken out without touching the rest of the URL") {
    for u <- Seq("https://t.example.net/1/2/3.png", "https://t.example.net/1/2/3.png?key=abc", "https://t.example.net/a?x=1&y=2") do
      val m = TileStyle.withToken(u, "00ff")
      assertEquals(TileStyle.strip(m), (u, Some("00ff")))
    assertEquals(TileStyle.strip("https://t.example.net/s@2x.json?ashidori-t=0a&x=1"), ("https://t.example.net/s@2x.json?x=1", Some("0a")))
    assertEquals(TileStyle.strip("https://t.example.net/a?x=1&ashidori-t=0a&y=2"), ("https://t.example.net/a?x=1&y=2", Some("0a")))
    // Two marks: none is taken as the token.
    assertEquals(TileStyle.strip("https://t.example.net/a?ashidori-t=0a&ashidori-t=0b")._2, None)
  }

  test("styles: every remote URL marked, every source within the zoom limit, a file per holder") {
    val style = ujson.write(ujson.Obj(
      "version" -> 8,
      "sprite" -> "https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
      "glyphs" -> "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
      "sources" -> ujson.Obj(
        "openmaptiles" -> ujson.Obj("type" -> "vector", "url" -> "https://tiles.openfreemap.org/planet"),
        "ne2_shaded" -> ujson.Obj("type" -> "raster", "maxzoom" -> 6, "tileSize" -> 256,
          "tiles" -> ujson.Arr("https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"))),
      "layers" -> ujson.Arr()))
    val tj = """{"tiles":["https://tiles.openfreemap.org/planet/20261117_001001_pt/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,"attribution":"OSM"}"""
    val a = TileStyle.vector(style, _ => tj, Some(12), "aa")
    val b = TileStyle.vector(style, _ => tj, Some(12), "bb")
    assertEquals(TileStyle.unmarked(a), Nil)
    assertNotEquals(a, b)
    val src = ujson.read(a)("sources")
    assertEquals(src("openmaptiles")("maxzoom").num, 12.0)
    assertEquals(src("ne2_shaded")("maxzoom").num, 6.0)
    assertEquals(src("openmaptiles")("tiles")(0).str, "https://tiles.openfreemap.org/planet/20261117_001001_pt/{z}/{x}/{y}.pbf?ashidori-t=aa")
    assert(!src("openmaptiles").obj.contains("url"))
    assertEquals(ujson.read(a)("sprite").str, "https://tiles.openfreemap.org/sprites/ofm_f384/ofm?ashidori-t=aa")
    // Unlimited: the server's own zoom.
    assertEquals(ujson.read(TileStyle.vector(style, _ => tj, None, "aa"))("sources")("openmaptiles")("maxzoom").num, 14.0)
    val r = TileStyle.raster("https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png", 18, "地理院タイル", Some(15), "cc", None)
    assertEquals(TileStyle.unmarked(r), Nil)
    assertEquals(ujson.read(r)("sources")("base")("maxzoom").num, 15.0)
  }
