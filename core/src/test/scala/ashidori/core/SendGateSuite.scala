package ashidori.core

import OutboundFixtures.*

class SendGateSuite extends munit.FunSuite:
  test("a permit ends with a change of its destination's rule, also of the level only") {
    val g = gate(Policy.fromBlock(false))
    val p = g.permit(Dest.Gsi, Mode.Auto).get
    assert(g.valid(p))
    g.setRule(Dest.Gsi, Rule(When.Auto, Level.District))
    assert(!g.valid(p))
    val q = g.permit(Dest.Gsi, Mode.Auto).get
    assertEquals(q.level, Level.District)
    // Back to the old rule: still a new generation, the old permit stays stale.
    g.setRule(Dest.Gsi, Rule(When.Auto, Level.Exact))
    assert(!g.valid(p) && !g.valid(q))
    // Setting the same rule is no change.
    val r = g.permit(Dest.Gsi, Mode.Auto).get
    assert(!g.setRule(Dest.Gsi, Rule(When.Auto, Level.Exact)))
    assert(g.valid(r))
  }

  test("a change of one destination does not end another's permits, grants or consents") {
    val g = gate()
    val view = Object()
    g.remember("map", GsiStd, Dest.Gsi)
    val tile = g.permit(Dest.Gsi, Mode.OnAction).get
    g.grant(view, tile)
    val data = g.permit(Dest.Data, Mode.Auto).get
    g.setRule(Dest.Geocoder, Rule(When.Auto, Level.Area))
    assert(g.valid(tile) && g.valid(data) && g.granted(view).contains(tile))
    assertEquals(g.consent("map").map(_.map), Some(GsiStd))
    // The tiles' own change ends them.
    g.setRule(Dest.Gsi, Rule(When.OnAction, Level.District))
    assert(!g.valid(tile) && g.granted(view).isEmpty && g.consent("map").isEmpty)
    assert(g.valid(data))
  }

  test("the pause ends every permit, at its start and again at its end; nothing passes meanwhile (design 4.5)") {
    val g = gate(Policy.fromBlock(false))
    val before = Dest.values.flatMap(d => g.permit(d, Mode.OnAction))
    assertEquals(before.length, Dest.values.length)
    g.setPaused(true)
    assert(before.forall(!g.valid(_)))
    for d <- Dest.values; m <- Mode.values do assertEquals(g.permit(d, m), None)
    assert(!g.networkAllowed)
    for d <- Dest.values do assertEquals(g.when(d), When.Never)
    g.setPaused(false)
    // A permit of before the pause is still not valid after it.
    assert(before.forall(!g.valid(_)))
    assert(g.permit(Dest.Ofm, Mode.Auto).isDefined)
  }

  test("the pause ends the consents: an explicit send does not come back by itself after it") {
    val g = gate()
    g.remember("map", GsiStd, Dest.Gsi)
    g.setPaused(true)
    g.setPaused(false)
    assertEquals(g.consent("map"), None)
    // Nothing is remembered while paused.
    g.setPaused(true)
    g.remember("map", GsiStd, Dest.Gsi)
    assertEquals(g.consents, Map.empty[String, Consent])
  }

  test("a permit of an earlier process never passes, whatever its generation (restart)") {
    val st = Store()
    val g1 = st.start()
    val p = g1.permit(Dest.Data, Mode.Auto).get
    val g2 = st.start()
    assertEquals(g2.generation(Dest.Data), p.gen)
    assert(!g2.valid(p))
    // Stored work keeps its generation and takes a new permit of this process.
    val q = g2.permitFor(Dest.Data, Mode.Auto, p.gen).get
    assert(g2.valid(q) && q.epoch == g2.epoch)
  }

  test("stored work asked for before a change does not get a permit, also after a restart before it was cancelled") {
    val st = Store()
    val g1 = st.start()
    val gen = g1.generation(Dest.Data)
    g1.setRule(Dest.Data, Rule(When.Auto, Level.Whole))
    // The process stops between storing the rule and cancelling the work.
    val g2 = st.start()
    assertEquals(g2.permitFor(Dest.Data, Mode.Auto, gen), None)
    // Work asked for under on-action rules does not run automatically.
    g2.setRule(Dest.Data, Rule(When.OnAction, Level.Whole))
    assertEquals(g2.permitFor(Dest.Data, Mode.Auto, g2.generation(Dest.Data)), None)
    assert(g2.permitFor(Dest.Data, Mode.OnAction, g2.generation(Dest.Data)).isDefined)
  }

  test("modes: never refuses even an action, on action refuses automatic work") {
    val g = gate(Policy.fresh.updated(Dest.Ofm, Rule(When.Never, Level.Exact)))
    assertEquals(g.permit(Dest.Ofm, Mode.OnAction), None)
    assertEquals(g.permit(Dest.Gsi, Mode.Auto), None)
    assert(g.permit(Dest.Gsi, Mode.OnAction).isDefined)
    // A rule that does not allow the mode of a permit ends it.
    val auto = gate(Policy.fromBlock(false))
    val p = auto.permit(Dest.Geocoder, Mode.Auto).get
    auto.setRule(Dest.Geocoder, Rule(When.Never, Level.Exact))
    assert(!auto.valid(p))
  }

  test("a consent is per surface, at its level, only while its destination is on action; a grant is per view") {
    val g = gate()
    val (a, b) = (Object(), Object())
    assert(!g.networkAllowed)
    g.remember("map", GsiStd, Dest.Gsi)
    assertEquals(g.consent("map"), Some(Consent(GsiStd, Dest.Gsi, Level.Exact)))
    assertEquals(g.consent("period"), None)
    val p = g.permit(Dest.Gsi, Mode.OnAction).get
    g.grant(a, p)
    assert(g.online(a, Dest.Gsi) && !g.online(b, Dest.Gsi) && !g.online(a, Dest.Ofm) && g.networkAllowed)
    g.revoke(a)
    assert(!g.online(a, Dest.Gsi) && !g.networkAllowed)
    assertEquals(g.consent("map").map(_.map), Some(GsiStd))
    g.forget("map")
    assertEquals(g.consent("map"), None)
    // A stale permit is not granted.
    g.setRule(Dest.Gsi, Rule(When.OnAction, Level.Area))
    g.grant(b, p)
    assertEquals(g.granted(b), None)
    // Automatic destinations: no consent to remember.
    val auto = gate(Policy.fromBlock(false))
    auto.remember("map", GsiStd, Dest.Gsi)
    assertEquals(auto.consents, Map.empty[String, Consent])
    assert(auto.networkAllowed && auto.online(a, Dest.Gsi))
  }

  test("consents and rules survive a restart in one store") {
    val st = Store()
    val g = st.start()
    g.remember("map", GsiStd, Dest.Gsi)
    g.setRule(Dest.Ofm, Rule(When.OnAction, Level.District))
    g.remember("period", Positron, Dest.Ofm)
    val again = st.start()
    assertEquals(again.consents, g.consents)
    assertEquals(again.policy, g.policy)
    assertEquals(again.consent("period"), Some(Consent(Positron, Dest.Ofm, Level.District)))
  }

  test("the listeners hear which destinations changed, after their generation moved on") {
    val g = gate()
    val p = g.permit(Dest.Ofm, Mode.OnAction).get
    var seen = Seq.empty[(Set[Dest], Boolean)]
    val stop = g.onChange(ds => seen :+= ds -> g.valid(p))
    g.setRule(Dest.Ofm, Rule(When.Never, Level.Exact))
    g.setPaused(true)
    assertEquals(seen, Seq(Set(Dest.Ofm) -> false, Dest.values.toSet -> false))
    stop()
    g.setPaused(false)
    assertEquals(seen.size, 2)
  }

  test("snapshot jobs take their permit when they start; a change of a map rule drops the queued ones") {
    val g = gate(Policy.fromBlock(false))
    val q = SnapshotQueue[String](g)
    val started = scala.collection.mutable.ArrayBuffer.empty[(String, Boolean)]
    def job(name: String): () => Option[String] = () =>
      started += name -> g.permit(Dest.Gsi, Mode.Auto).isDefined
      Some(name)
    q.enqueue(job("a"))
    q.enqueue(job("b"))
    assertEquals(started.toSeq, Seq("a" -> true))
    g.setRule(Dest.Gsi, Rule(When.OnAction, Level.Exact))
    assertEquals(q.invalidate(), Some("a"))
    q.finished("a")
    assertEquals(started.size, 1)
    q.enqueue(job("c"))
    assertEquals(started.last, "c" -> false)
    // A change of a destination snapshots do not use keeps the queue.
    q.finished("c")
    q.enqueue(job("d"))
    q.enqueue(job("e"))
    g.setRule(Dest.Geocoder, Rule(When.Auto, Level.Area))
    q.finished("d")
    assertEquals(started.map(_._1).toSeq, Seq("a", "c", "d", "e"))
  }

  test("a job is running until it finishes or the queue drops it (a timed-out snapshot acts only then)") {
    val g = gate(Policy.fromBlock(false))
    val q = SnapshotQueue[String](g)
    q.enqueue(() => Some("a"))
    q.enqueue(() => Some("b"))
    assert(q.isRunning("a") && !q.isRunning("b"))
    q.finished("a")
    assert(!q.isRunning("a") && q.isRunning("b"))
    // Dropped by a change of the rules (or a hold): no longer its job, though not finished.
    assertEquals(q.invalidate(), Some("b"))
    assert(!q.isRunning("b"))
    q.enqueue(() => Some("c"))
    q.hold()
    assert(!q.isRunning("c"))
  }

  test("a queued job of an old generation is skipped even without invalidate, and the next one runs") {
    val g = gate()
    val q = SnapshotQueue[String](g)
    val started = scala.collection.mutable.ArrayBuffer.empty[String]
    def job(name: String): () => Option[String] = () => { started += name; Some(name) }
    q.enqueue(job("a"))
    q.enqueue(job("old"))
    g.setRule(Dest.Ofm, Rule(When.Auto, Level.Exact))
    q.enqueue(job("new"))
    q.finished("a")
    assertEquals(started.toSeq, Seq("a", "new"))
  }

  test("a screen leaving holds the queue first: a card still attached while the others detach starts nothing") {
    // The day map cards of a feed: a job starts only for a card still in a
    // window (DayMap), and a card leaving ends its running job (finished).
    val g = gate(Policy.fromBlock(false))
    val q = SnapshotQueue[String](g)
    val attached = scala.collection.mutable.Map("1" -> true, "2" -> true, "3" -> true)
    val started = scala.collection.mutable.ArrayBuffer.empty[String]
    def job(card: String): () => Option[String] = () =>
      if attached(card) && g.permit(Dest.Ofm, Mode.Auto).isDefined then { started += card; Some(card) } else None
    def detach(card: String): Unit =
      attached(card) = false
      q.finished(card)

    // Without the hold: card 1 detaching first lets card 2 (still attached) start.
    Seq("1", "2", "3").foreach(c => q.enqueue(job(c)))
    assertEquals(started.toSeq, Seq("1"))
    detach("1")
    assertEquals(started.toSeq, Seq("1", "2"))
    q.invalidate()

    // With it: held before any view detaches; card 1 rendering, cards 2 and 3 waiting and attached.
    Seq("1", "2", "3").foreach(attached(_) = true)
    started.clear()
    Seq("1", "2", "3").foreach(c => q.enqueue(job(c)))
    assertEquals(started.toSeq, Seq("1"))
    assertEquals(q.hold(), Some("1"))
    assertEquals(q.queued, 0)
    detach("1")
    assertEquals(started.toSeq, Seq("1"))
    // Card 2 is still attached here, between the detach callbacks.
    assert(attached("2"))
    q.finished("2")
    detach("2")
    detach("3")
    assertEquals(started.toSeq, Seq("1"))
    // A card attached again while held asks again, and waits for the screen.
    attached("2") = true
    q.enqueue(job("2"))
    assertEquals(started.toSeq, Seq("1"))
    assert(!q.isBusy)
    // Shown again: it starts, and the next waits for it.
    attached("3") = true
    q.enqueue(job("3"))
    q.release()
    assertEquals(started.toSeq, Seq("1", "2"))
    q.finished("2")
    assertEquals(started.toSeq, Seq("1", "2", "3"))
    // A change of a map rule while held keeps it held.
    q.hold()
    q.enqueue(job("2"))
    g.setRule(Dest.Ofm, Rule(When.OnAction, Level.Exact))
    q.invalidate()
    q.enqueue(job("2"))
    assert(q.isHeld)
    assertEquals(started.toSeq, Seq("1", "2", "3"))
    q.release()
    assertEquals(started.toSeq, Seq("1", "2", "3"))
  }

  test("a fallback server is not asked once the permit ends (retry re-checked)") {
    val g = gate()
    val p = g.permit(Dest.Overpass, Mode.OnAction).get
    val asked = scala.collection.mutable.ArrayBuffer.empty[String]
    val r = SendGate.firstSuccess(Seq("main", "mirror1", "mirror2"), () => g.valid(p)) { s =>
      asked += s
      g.setPaused(true)
      throw RuntimeException("timeout")
    }
    assertEquals(r, Left(SendGate.Blocked))
    assertEquals(asked.toSeq, Seq("main"))
    val ok = SendGate.firstSuccess(Seq("main", "mirror1"), () => true)(s => if s == "main" then throw RuntimeException("busy") else s)
    assertEquals(ok, Right("mirror1"))
  }

  test("only rules a destination offers are accepted") {
    val g = gate()
    intercept[IllegalArgumentException](g.setRule(Dest.Geocoder, Rule(When.OnAction, Level.Exact)))
    intercept[IllegalArgumentException](g.setRule(Dest.Overpass, Rule(When.Auto, Level.Exact)))
    intercept[IllegalArgumentException](g.setRule(Dest.Overpass, Rule(When.OnAction, Level.Exact, Policy.OverpassHosts)))
    intercept[IllegalArgumentException](g.setRule(Dest.Data, Rule(When.Auto, Level.Exact)))
    intercept[IllegalArgumentException](g.setRule(Dest.Ofm, Rule(When.Auto, Level.Exact, Set("overpass-api.de"))))
  }
