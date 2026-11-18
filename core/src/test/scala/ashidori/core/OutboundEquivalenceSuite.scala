package ashidori.core

import MapConsents.Stored
import OutboundFixtures.*

/** Design 3.7 and 8.1: with the rules migrated from the old setting (true,
  * false, missing, unreadable), every decision is the one the app made
  * before (the reference is the old code, kept in `legacy`), including work
  * that waits (snapshots in the queue, a download waiting for its retry),
  * consents read back after a restart, a map that left the screen, and
  * stored work of a stopped process.
  */
class OutboundEquivalenceSuite extends munit.FunSuite:
  import BaseMapPolicy.Outcome

  private val oldConsents = Map("map" -> GsiStd, "period" -> Custom, "picker" -> Liberty)
  private def stored(b: Stored[Boolean], c: Stored[String]) =
    val st = Store()
    b match
      case Stored.Ok(v) => st.booleans += ExternalSettings.LegacyBlockKey -> v
      case _ => ()
    c match
      case Stored.Ok(v) => st.strings += ExternalSettings.LegacyConsentsKey -> v
      case _ => ()
    st

  /** Every stored combination of the old keys. */
  private val oldStates: Seq[(Stored[Boolean], Stored[String])] =
    for
      b <- Seq(Stored.Ok(true), Stored.Ok(false), Stored.Missing, Stored.Bad)
      c <- Seq(Stored.Missing, Stored.Bad, Stored.Ok(MapConsents.encode(oldConsents)), Stored.Ok(MapConsents.encode(Map("map" -> GsiStd))),
               Stored.Ok("""{"v":1,"consents":[{"surface":"map","map":"gsi-std"},{"surface":"map","map":"ofm-liberty"}]}"""))
    yield (b, c)

  /** The old gate and the new one from the same stored values. */
  private def both(b: Stored[Boolean], c: Stored[String]): (legacy.SendGate, SendGate, Store) =
    val (block, consents) = legacy.MapConsents.load(
      b match { case Stored.Ok(v) => legacy.MapConsents.Stored.Ok(v); case Stored.Missing => legacy.MapConsents.Stored.Missing; case _ => legacy.MapConsents.Stored.Bad },
      c match { case Stored.Ok(v) => legacy.MapConsents.Stored.Ok(v); case Stored.Missing => legacy.MapConsents.Stored.Missing; case _ => legacy.MapConsents.Stored.Bad },
      Surfaces, m => destOf(m).isDefined)
    val st = stored(b, c)
    val values = st.values.copy(legacyBlock = if b == Stored.Bad then Stored.Bad else st.values.legacyBlock,
      legacyConsents = if c == Stored.Bad then Stored.Bad else st.values.legacyConsents)
    val rnd = java.util.Random(3)
    val (state, ws) = ExternalSettings.load(values, Surfaces, destOf, rnd)
    ws.foreach(st.write)
    (legacy.SendGate(block, consents), SendGate(state, s => st.write(ExternalSettings.writes(s)), rnd), st)

  private def when(g: SendGate): String => When = m => destOf(m).fold(When.Auto)(g.when)
  private def same[A, B](o: legacy.BaseMapPolicy.Outcome[A], n: Outcome[B]): Boolean = (o, n) match
    case (legacy.BaseMapPolicy.Outcome.Draw(a), Outcome.Draw(b)) => a == b
    case (legacy.BaseMapPolicy.Outcome.NoBasemap, Outcome.NoBasemap) => true
    case _ => false

  /** Every decision of the maps, the geocoder, the venue search and the downloads. */
  private def decisionsAgree(o: legacy.SendGate, n: SendGate, clue: String): Unit =
    val w = when(n)
    for
      chosen <- Maps
      last <- Online
      avail <- Seq(true, false)
      surface <- Surfaces.toSeq
      onScreen <- Seq(true, false)
    do
      val c = s"$clue chosen=$chosen last=$last avail=$avail surface=$surface onScreen=$onScreen"
      val oi = legacy.BaseMapPolicy.interactive(o.block, chosen, Offline, avail, o.consent(surface), onScreen)
      val ni = BaseMapPolicy.interactive(w, chosen, Offline, avail, n.consent(surface).map(_.map), onScreen)
      assert(same(oi, ni), s"interactive $c: $oi vs $ni")
      val os = legacy.BaseMapPolicy.snapshot(o.block, chosen, Offline, avail)
      val ns = BaseMapPolicy.snapshot(w, chosen, Offline, avail)
      assert(same(os, ns), s"snapshot $c")
      // A snapshot of an online map gets its permit when it starts.
      ns match
        case Outcome.Draw(m) if m != Offline => assert(n.permit(destOf(m).get, Mode.Auto).isDefined, c)
        case _ => ()
      assertEquals(BaseMapPolicy.thumbnail(w, chosen, Offline, avail), legacy.BaseMapPolicy.thumbnail(o.block, chosen, Offline, avail), c)
      val od = legacy.BaseMapPolicy.detailMap(chosen, last, Offline)
      val nd = BaseMapPolicy.detailMap(w, chosen, last, Offline, Online)
      assertEquals(nd, Some(od), c)
      val oOffer = legacy.BaseMapPolicy.offerAlternatives(o.block, oi)
      val nOffer = BaseMapPolicy.offerAlternatives(w, nd, ni)
      assertEquals(nOffer, oOffer, c)
      // The detail button: shown with the old setting on unless the card offers it.
      assertEquals(BaseMapPolicy.offerDetail(w, nd, n.consent(surface).isDefined, nOffer), o.block && !oOffer, c)
    // The geocoder.
    assertEquals(n.permit(Dest.Geocoder, Mode.Auto).isDefined, GeocodePolicy.usePlatform(false, o.block, true), clue)
    // The venue search: on the user's action, whatever the old setting said.
    assert(n.permit(Dest.Overpass, Mode.OnAction).isDefined, clue)
    assertEquals(n.rule(Dest.Overpass).level, Level.Exact)
    assertEquals(n.rule(Dest.Overpass).off, Set.empty[String])
    // The downloads: automatic at start whatever the old setting said.
    assert(n.permit(Dest.Data, Mode.Auto).isDefined && n.permit(Dest.Data, Mode.OnAction).isDefined, clue)
    // Consents as the old code had them.
    assertEquals(Surfaces.toSeq.map(s => s -> n.consent(s).map(_.map)).toMap, Surfaces.toSeq.map(s => s -> o.consent(s)).toMap, clue)
    assertEquals(n.networkAllowed, o.networkAllowed, clue)

  test("every decision agrees for every stored old setting") {
    for (b, c) <- oldStates do
      val (o, n, _) = both(b, c)
      decisionsAgree(o, n, s"$b $c")
  }

  test("an explicit choice, a view on and off screen, and the network switch agree") {
    for (b, c) <- oldStates; surface <- Surfaces.toSeq; m <- Online do
      val (o, n, _) = both(b, c)
      val clue = s"$b $c $surface $m"
      o.remember(surface, m)
      n.remember(surface, m, destOf(m).get)
      decisionsAgree(o, n, clue)
      val (ov, nv) = (Object(), Object())
      // The view of the surface is on screen: the old gate grants when it holds a consent.
      if o.consent(surface).isDefined then o.grant(ov)
      n.consent(surface).foreach(cs => n.grant(nv, n.permit(cs.dest, Mode.OnAction).get))
      assertEquals(n.networkAllowed, o.networkAllowed, clue)
      assertEquals(n.online(nv, destOf(m).get), o.online(ov), clue)
      // It leaves the screen.
      o.revoke(ov)
      n.revoke(nv)
      assertEquals(n.networkAllowed, o.networkAllowed, clue)
      assertEquals(n.online(nv, destOf(m).get), o.online(ov), clue)
      decisionsAgree(o, n, clue)
      // Back to the on-device map.
      o.forget(surface)
      n.forget(surface)
      decisionsAgree(o, n, clue)
  }

  test("after a restart: consents read back, and every decision still agrees") {
    for (b, c) <- oldStates; m <- Online do
      val (o, n, st) = both(b, c)
      o.remember("period", m)
      n.remember("period", m, destOf(m).get)
      val oStored = o.consents
      val again = st.start(seed = 11)
      val oAgain = legacy.SendGate(o.block, oStored)
      decisionsAgree(oAgain, again, s"restart $b $c $m")
  }

  test("snapshots waiting in the queue start with the same base maps") {
    for (b, c) <- oldStates; chosen <- Maps; avail <- Seq(true, false) do
      val (o, n, _) = both(b, c)
      val oq = legacy.SnapshotQueue[String](o)
      val nq = SnapshotQueue[String](n)
      val oStarted = scala.collection.mutable.ArrayBuffer.empty[String]
      val nStarted = scala.collection.mutable.ArrayBuffer.empty[String]
      for i <- 1 to 3 do
        oq.enqueue { block =>
          oStarted += legacy.BaseMapPolicy.snapshot(block, chosen, Offline, avail).toString
          Some(s"o$i")
        }
        nq.enqueue { () =>
          val out = BaseMapPolicy.snapshot(when(n), chosen, Offline, avail) match
            case Outcome.Draw(m) if m != Offline && n.permit(destOf(m).get, Mode.Auto).isEmpty => Outcome.Draw(Offline)
            case other => other
          nStarted += out.toString
          Some(s"n$i")
        }
      for i <- 1 to 3 do
        oq.finished(s"o$i")
        nq.finished(s"n$i")
      assertEquals(nStarted.toSeq, oStarted.toSeq, s"$b $c $chosen $avail")
  }

  test("a download waiting for its retry, and stored work of a stopped process, still run") {
    for (b, c) <- oldStates; mode <- Mode.values do
      val (_, n, st) = both(b, c)
      // Queued (automatically at start, or by the user) with the generation then.
      val gen = n.permit(Dest.Data, mode).get.gen
      // Its retry in this process.
      assert(n.permitFor(Dest.Data, mode, gen).isDefined)
      // The process stops; WorkManager runs it in the next one.
      val next = st.start(seed = 5)
      assert(next.permitFor(Dest.Data, mode, gen).isDefined, s"$b $c $mode")
  }
