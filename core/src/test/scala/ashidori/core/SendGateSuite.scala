package ashidori.core

class SendGateSuite extends munit.FunSuite:
  test("automatic work asked for before a change of the setting never sends after it (F3, F4)") {
    val g = SendGate(initialBlock = false)
    val t = g.ticket()
    assert(g.automatic(t))
    g.setBlock(true)
    assert(!g.automatic(t))
    // Turned off again: still a new generation, the old ticket stays stale.
    g.setBlock(false)
    assert(!g.automatic(t))
    assert(g.automatic(g.ticket()))
    // Setting the same value is no change.
    val t2 = g.ticket()
    assert(!g.setBlock(false))
    assert(g.automatic(t2))
  }

  test("a detail grant lets only its owner go online, and ends on revoke or a change of the setting (F3, F5)") {
    val g = SendGate(initialBlock = true)
    val (a, b) = (Object(), Object())
    assert(!g.networkAllowed)
    g.grant(a)
    assert(g.online(a) && !g.online(b) && g.networkAllowed)
    // Automatic work does not ride on another view's grant.
    assert(!g.automatic(g.ticket()))
    // The view left the screen.
    g.revoke(a)
    assert(!g.online(a) && !g.networkAllowed)
    g.grant(a)
    g.setBlock(false)
    g.setBlock(true)
    assert(!g.granted(a) && !g.networkAllowed)
  }

  test("a consent is remembered per surface across leaving the screen, apart from the view's grant") {
    var stored = Option.empty[(Boolean, Map[String, String])]
    val g = SendGate(initialBlock = true, persist = (b, c) => stored = Some(b -> c))
    val mapTab = Object()
    g.remember("map", "gsi-std")
    g.grant(mapTab)
    assertEquals(stored, Some(true -> Map("map" -> "gsi-std")))
    // Another surface does not inherit it.
    assertEquals(g.consent("period"), None)
    // The map tab is hidden: the grant (and the network) ends, the consent stays.
    g.revoke(mapTab)
    assert(!g.networkAllowed)
    assertEquals(g.consent("map"), Some("gsi-std"))
    // Shown again.
    g.grant(mapTab)
    assert(g.networkAllowed)
    // Switched back to the on-device map.
    g.forget("map")
    assertEquals(g.consent("map"), None)
    assertEquals(stored, Some(true -> Map.empty[String, String]))
  }

  test("a stored consent survives a restart, and a change of the setting ends it with the grants (F5)") {
    var stored = Option.empty[(Boolean, Map[String, String])]
    val persist: (Boolean, Map[String, String]) => Unit = (b, c) => stored = Some(b -> c)
    val g = SendGate(initialBlock = true, Map("map" -> "gsi-std", "period" -> "ofm-liberty"), persist)
    assertEquals(g.consent("map"), Some("gsi-std"))
    assertEquals(g.consent("period"), Some("ofm-liberty"))
    val v = Object()
    g.grant(v)
    g.setBlock(false)
    // Stored together with the setting.
    assertEquals(stored, Some(false -> Map.empty[String, String]))
    assertEquals(g.consents, Map.empty[String, String])
    g.setBlock(true)
    assertEquals(g.consent("map"), None)
    assert(!g.granted(v) && !g.networkAllowed)
    // With the setting off there is nothing to consent to, and stored consents are not loaded.
    val off = SendGate(initialBlock = false, Map("map" -> "gsi-std"), persist)
    off.remember("period", "gsi-std")
    assertEquals(off.consents, Map.empty[String, String])
  }

  test("stored consents: escaped, versioned, and read back fail-safe") {
    import MapConsents.Stored.*
    val surfaces = Set("map", "period")
    val known = Set("gsi-std", "ofm-liberty", "custom:https://t.example/{z}/{x}/{y}.png")
    def load(b: MapConsents.Stored[Boolean], c: MapConsents.Stored[String]) = MapConsents.load(b, c, surfaces, known)
    val none = Map.empty[String, String]
    val ok = Map("map" -> "gsi-std", "period" -> "ofm-liberty")
    assertEquals(load(Ok(true), Ok(MapConsents.encode(ok))), (true, ok))
    // A custom URL with a newline and tab stays one value: no consent for another surface.
    val forged = Map("map" -> "custom:https://t.example/{z}/{x}/{y}.png\nperiod\tgsi-std")
    assertEquals(MapConsents.decode(MapConsents.encode(forged)), Some(forged.toSeq))
    assertEquals(load(Ok(true), Ok(MapConsents.encode(forged))), (true, none))
    // Consents without the setting, or with an unreadable setting: on, none.
    assertEquals(load(Missing, Ok(MapConsents.encode(ok))), (true, none))
    assertEquals(load(Bad, Ok(MapConsents.encode(ok))), (true, none))
    assertEquals(load(Missing, Missing), (true, none))
    // Setting off: never any consent.
    assertEquals(load(Ok(false), Ok(MapConsents.encode(ok))), (false, none))
    // Unreadable, old-format, wrong-version or duplicated records: none.
    assertEquals(load(Ok(true), Bad), (true, none))
    assertEquals(load(Ok(true), Ok("map\tgsi-std")), (true, none))
    assertEquals(load(Ok(true), Ok("""{"v":2,"consents":[]}""")), (true, none))
    val dup = """{"v":1,"consents":[{"surface":"map","map":"gsi-std"},{"surface":"map","map":"ofm-liberty"}]}"""
    assertEquals(load(Ok(true), Ok(dup)), (true, none))
    // A repeated JSON key: a reader taking the last one would see a consent for period.
    val dupKey = """{"v":1,"consents":[],"consents":[{"surface":"period","map":"gsi-std"}]}"""
    assertEquals(load(Ok(true), Ok(dupKey)), (true, none))
    // Not as encode writes it (spacing, order, extra fields): none.
    assertEquals(load(Ok(true), Ok("""{"v":1, "consents":[{"surface":"map","map":"gsi-std"}]}""")), (true, none))
    assertEquals(load(Ok(true), Ok("""{"v":1,"consents":[{"surface":"map","map":"gsi-std"}],"x":0}""")), (true, none))
    // Unknown surfaces and maps no longer valid are dropped, the rest kept.
    val mixed = MapConsents.encode(Map("map" -> "gsi-std", "elsewhere" -> "gsi-std", "period" -> "custom:https://old/{z}/{x}/{y}"))
    assertEquals(load(Ok(true), Ok(mixed)), (true, Map("map" -> "gsi-std")))
  }

  test("two surfaces: the map leaving the screen drops its online map and grant before the next may use the network (F5)") {
    import BaseMapPolicy.Outcome.Draw
    val g = SendGate(initialBlock = true)
    g.remember("map", "gsi-std")
    g.remember("period", "ofm-liberty")
    val (mapTab, period) = (Object(), Object())
    def base(surface: String, onScreen: Boolean) =
      BaseMapPolicy.interactive(g.block, "gsi-std", "offline", true, g.consent(surface), onScreen)
    // The map tab is on screen with its consent.
    g.grant(mapTab)
    assertEquals(base("map", onScreen = true), Draw("gsi-std"))
    // MainActivity hides the old screen before it shows the new one. Hiding
    // (TrackMap.offScreen) revokes and drops the online map: at that point
    // nothing may use the network and the map tab wants no online map.
    g.revoke(mapTab)
    assert(!g.networkAllowed)
    assertEquals(base("map", onScreen = false), Draw("offline"))
    // Only then does the period view come on screen and turn it back on.
    g.grant(period)
    assert(g.networkAllowed)
    assertEquals(base("period", onScreen = true), Draw("ofm-liberty"))
    // The hidden map tab still wants no online map, though the network is on.
    assertEquals(base("map", onScreen = false), Draw("offline"))
    // The app goes to the background (onStop): every map gives up.
    g.revoke(period)
    assert(!g.networkAllowed)
    // Back to the map tab: its consent was kept.
    g.grant(mapTab)
    assertEquals(base("map", onScreen = true), Draw("gsi-std"))
  }

  test("the listeners hear of a change after the generation moved on") {
    val g = SendGate(initialBlock = false)
    val t = g.ticket()
    var seen = Option.empty[Boolean]
    val stop = g.onChange(() => seen = Some(g.automatic(t)))
    g.setBlock(true)
    assertEquals(seen, Some(false))
    stop()
    seen = None
    g.setBlock(false)
    assertEquals(seen, None)
  }

  test("snapshot jobs take the setting as it is when they start, and a change drops the queued ones and returns the running one (F3)") {
    val g = SendGate(initialBlock = false)
    val q = SnapshotQueue[String](g)
    val started = scala.collection.mutable.ArrayBuffer.empty[(String, Boolean)]
    def job(name: String): Boolean => Option[String] = block => { started += name -> block; Some(name) }
    q.enqueue(job("a"))
    q.enqueue(job("b"))
    q.enqueue(job("c"))
    assertEquals(started.toSeq, Seq("a" -> false))
    // The setting turns on while "a" renders.
    g.setBlock(true)
    assertEquals(q.invalidate(), Some("a"))
    assertEquals(q.queued, 0)
    // "a" calls back after it was cancelled: ignored, nothing else starts.
    q.finished("a")
    assertEquals(started.size, 1)
    // A job asked for now starts with the new setting.
    q.enqueue(job("d"))
    assertEquals(started.last, "d" -> true)
    q.finished("d")
    assert(!q.isBusy)
  }

  test("a queued job of an old generation is skipped even without invalidate, and the next one runs") {
    val g = SendGate(initialBlock = true)
    val q = SnapshotQueue[String](g)
    val started = scala.collection.mutable.ArrayBuffer.empty[(String, Boolean)]
    def job(name: String): Boolean => Option[String] = block => { started += name -> block; Some(name) }
    q.enqueue(job("a"))
    q.enqueue(job("old"))
    g.setBlock(false)
    q.enqueue(job("new"))
    q.finished("a")
    assertEquals(started.toSeq, Seq("a" -> true, "new" -> false))
  }

  test("a fallback server is not asked once the setting stops the work (F4)") {
    var allowed = true
    val asked = scala.collection.mutable.ArrayBuffer.empty[String]
    val r = SendGate.firstSuccess(Seq("main", "mirror1", "mirror2"), () => allowed) { s =>
      asked += s
      allowed = false // the setting turned on while the main server was asked
      throw RuntimeException("timeout")
    }
    assertEquals(r, Left(SendGate.Blocked))
    assertEquals(asked.toSeq, Seq("main"))
    val ok = SendGate.firstSuccess(Seq("main", "mirror1"), () => true)(s => if s == "main" then throw RuntimeException("busy") else s)
    assertEquals(ok, Right("mirror1"))
    assert(SendGate.firstSuccess(Seq("main"), () => true)(_ => throw RuntimeException("x")).left.exists(_.isInstanceOf[SendGate.Failed]))
  }
