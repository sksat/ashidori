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
