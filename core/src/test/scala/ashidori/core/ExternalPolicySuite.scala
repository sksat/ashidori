package ashidori.core

import MapConsents.Stored
import OutboundFixtures.*

class ExternalPolicySuite extends munit.FunSuite:
  private val freshJson = Policy.encode(Policy.fresh)

  test("the canonical form: rules in key order, Overpass with its hosts off") {
    assertEquals(freshJson,
      """{"v":1,"rules":[{"dest":"custom","when":"action","level":"exact"},{"dest":"data","when":"auto","level":"cell"},""" +
      """{"dest":"geocoder","when":"never","level":"exact"},{"dest":"gsi","when":"action","level":"exact"},""" +
      """{"dest":"ofm","when":"action","level":"exact"},{"dest":"overpass","when":"action","level":"exact","off":[]}]}""")
    assertEquals(Policy.read(freshJson), Policy.Read(Policy.fresh, None))
    val p = Policy.fresh.updated(Dest.Overpass, Rule(When.OnAction, Level.District, Set("maps.mail.ru", "overpass-api.de")))
    assertEquals(Policy.read(Policy.encode(p)), Policy.Read(p, None))
  }

  test("a corrupt policy reads as the recovery value: nothing sent but data on action (design 3.5)") {
    val corrupt = Seq(
      "", "not json", "[]", """{"v":1}""", """{"v":"1","rules":[]}""",
      // A repeated key, at the top or in a rule: a reader taking either value would differ.
      freshJson.replace("""{"v":1,""", """{"v":1,"rules":[],"""),
      freshJson.replace(""""dest":"geocoder","when":"never"""", """"dest":"geocoder","when":"auto","when":"never""""),
      // A destination twice.
      freshJson.replace("""{"dest":"custom",""", """{"dest":"ofm","when":"auto","level":"exact"},{"dest":"custom","""),
      // Not in the canonical form: order of rules, order of fields.
      Policy.encode(Policy.fresh).replace("""{"dest":"custom","when":"action","level":"exact"},{"dest":"data","when":"auto","level":"cell"},""",
        """{"dest":"data","when":"auto","level":"cell"},{"dest":"custom","when":"action","level":"exact"},"""),
      freshJson.replace("""{"dest":"custom","when":"action","level":"exact"}""", """{"when":"action","dest":"custom","level":"exact"}"""),
      // A combination the destination does not offer.
      freshJson.replace(""""dest":"geocoder","when":"never"""", """"dest":"geocoder","when":"action""""),
      freshJson.replace(""""dest":"overpass","when":"action"""", """"dest":"overpass","when":"auto""""),
      freshJson.replace(""""dest":"ofm","when":"action","level":"exact"""", """"dest":"ofm","when":"action","level":"cell""""),
      freshJson.replace(""""off":[]""", """"off":["evil.example.net"]"""),
      freshJson.replace(""""off":[]""", """"off":["maps.mail.ru","overpass-api.de","overpass.kumi.systems","overpass.private.coffee"]"""),
      freshJson.replace(""""when":"never"""", """"when":"sometimes""""),
    )
    for c <- corrupt do
      val r = Policy.read(c)
      assertEquals(r.policy, Policy.recovery, c)
      assertEquals(r.problem, Some(Policy.Problem.Corrupt), c)
  }

  test("another version reads as the recovery value; unknown fields and destinations are dropped") {
    assertEquals(Policy.read(freshJson.replace("""{"v":1""", """{"v":2""")), Policy.Read(Policy.recovery, Some(Policy.Problem.UnknownVersion)))
    // A newer version's extra field and destination: the rest is used as it is.
    val newer = freshJson
      .replace("""{"dest":"custom","when":"action","level":"exact"}""", """{"dest":"custom","when":"action","level":"exact","hint":"x"}""")
      .replace("""{"dest":"data",""", """{"dest":"car-share","when":"auto","level":"exact"},{"dest":"data",""")
      .replace(""""rules":""", """"extra":{"a":1},"rules":""")
    assertEquals(Policy.read(newer), Policy.Read(Policy.fresh, None))
  }

  test("a missing destination gets its recovery rule") {
    val p = Policy(Policy.fresh.rules - Dest.Geocoder - Dest.Ofm)
    val r = Policy.read(Policy.encode(p))
    assertEquals(r.policy, Policy.fresh.updated(Dest.Ofm, Policy.recovery.rule(Dest.Ofm)))
    assertEquals(r.problem, Some(Policy.Problem.Missing(Set(Dest.Geocoder, Dest.Ofm))))
  }

  test("the fresh default and the recovery value differ; recovery sends nothing automatically, data included") {
    assertNotEquals(Policy.fresh, Policy.recovery)
    for d <- Dest.values do
      assert(!Policy.recovery.rule(d).allows(Mode.Auto), d.toString)
      assert(Policy.valid(d, Policy.recovery.rule(d)) && Policy.valid(d, Policy.fresh.rule(d)), d.toString)
    assertEquals(Dest.values.filter(d => Policy.recovery.rule(d).allows(Mode.OnAction)).toSeq, Seq(Dest.Data))
    assertEquals(Policy.recovery.rule(Dest.Data).level, Level.Whole)
  }

  test("a corrupt or newer policy is not written back until the user changes a rule") {
    for stored <- Seq("garbage", freshJson.replace("""{"v":1""", """{"v":9""")) do
      val st = Store(strings = Map(ExternalSettings.PolicyKey -> stored))
      val g = st.start()
      assertEquals(st.strings(ExternalSettings.PolicyKey), stored)
      assert(g.problem.nonEmpty && g.policy == Policy.recovery)
      // The pause is stored, the rules still are not.
      g.setPaused(true)
      g.setPaused(false)
      assertEquals(st.strings(ExternalSettings.PolicyKey), stored)
      // A change of the user: written in this version's form.
      g.setRule(Dest.Ofm, Rule(When.OnAction, Level.District))
      assertEquals(Policy.read(st.strings(ExternalSettings.PolicyKey)).policy,
        Policy.recovery.updated(Dest.Ofm, Rule(When.OnAction, Level.District)))
      assertEquals(g.problem, None)
  }

  test("migration from blockExternalLocation: true, false, missing, of the wrong type; onlineVenues folded in first") {
    val ok = Map("map" -> GsiStd, "period" -> Liberty)
    val cases = Seq[(Stored[Boolean], Policy, Map[String, Consent])](
      (Stored.Ok(true), Policy.fresh, Map("map" -> Consent(GsiStd, Dest.Gsi, Level.Exact), "period" -> Consent(Liberty, Dest.Ofm, Level.Exact))),
      (Stored.Ok(false), Policy.fromBlock(false), Map.empty),
      (Stored.Missing, Policy.fresh, Map.empty),
      (Stored.Bad, Policy.fresh, Map.empty)
    )
    for (block, policy, consents) <- cases do
      val st = Store()
      st.booleans = block match
        case Stored.Ok(b) => Map(ExternalSettings.LegacyBlockKey -> b)
        case _ => Map.empty
      st.strings = Map(ExternalSettings.LegacyConsentsKey -> MapConsents.encode(ok))
      val values = if block == Stored.Bad then st.values.copy(legacyBlock = Stored.Bad) else st.values
      val (state, writes) = ExternalSettings.load(values, Surfaces, destOf, java.util.Random(1))
      assertEquals(state.policy, policy, block.toString)
      assertEquals(state.consents, consents, block.toString)
      assert(!state.paused && state.problem.isEmpty)
      assert(writes.exists(_.strings.contains(ExternalSettings.PolicyKey)), "migrated rules are stored")
    // The app folds onlineVenues = false into blockExternalLocation = true first: the stricter rules.
    assertEquals(Policy.fromBlock(true).rule(Dest.Geocoder).when, When.Never)
  }

  test("old-version values: the old setting off only when the rules are exactly that; only unlimited on-action consents") {
    val c = Map("map" -> Consent(GsiStd, Dest.Gsi, Level.Exact))
    def legacy(p: Policy, cs: Map[String, Consent] = Map.empty, paused: Boolean = false) =
      ExternalSettings.legacy(SendGate.State(p, cs, Map.empty, paused))
    assertEquals(legacy(Policy.fromBlock(false)), (false, Map.empty[String, String]))
    assertEquals(legacy(Policy.fromBlock(false), paused = true)._1, true)
    assertEquals(legacy(Policy.fresh, c), (true, Map("map" -> GsiStd)))
    // Any other rules: true (an older version then sends nothing automatically to maps or the geocoder).
    for (d, r) <- Seq(Dest.Ofm -> Rule(When.Auto, Level.District), Dest.Geocoder -> Rule(When.Auto, Level.Area),
                      Dest.Overpass -> Rule(When.Never, Level.Exact), Dest.Data -> Rule(When.OnAction, Level.Whole)) do
      assertEquals(legacy(Policy.fromBlock(false).updated(d, r))._1, true, d.toString)
    // A consent at a limited level is not given to an older version, which would send it unlimited.
    val limited = Policy.fresh.updated(Dest.Gsi, Rule(When.OnAction, Level.District))
    assertEquals(legacy(limited, Map("map" -> Consent(GsiStd, Dest.Gsi, Level.District))), (true, Map.empty[String, String]))
    assertEquals(legacy(Policy.fresh, c, paused = true), (true, Map.empty[String, String]))
  }

  test("generations: random, stored with the rules, new for every destination when unreadable") {
    val st = Store()
    val g = st.start()
    val gens0 = Gens.read(st.strings(ExternalSettings.GensKey)).get
    assertEquals(gens0, Dest.values.map(d => d -> g.generation(d)).toMap)
    g.setRule(Dest.Geocoder, Rule(When.Auto, Level.Area))
    val gens1 = Gens.read(st.strings(ExternalSettings.GensKey)).get
    assertNotEquals(gens1(Dest.Geocoder), gens0(Dest.Geocoder))
    assertEquals(gens1 - Dest.Geocoder, gens0 - Dest.Geocoder)
    // The rule and its generation went out in the same write.
    assertEquals(Policy.read(st.strings(ExternalSettings.PolicyKey)).policy.rule(Dest.Geocoder), Rule(When.Auto, Level.Area))
    // Unreadable generations: new random ones, matching nothing stored with work.
    st.strings += ExternalSettings.GensKey -> "{oops"
    val g2 = st.start(seed = 99)
    for d <- Dest.values do assertNotEquals(g2.generation(d), gens1(d))
    assert(Gens.read(st.strings(ExternalSettings.GensKey)).isDefined)
  }

  test("stored consents: canonical, of a known surface and of the map's destination") {
    val cs = Map("map" -> Consent(GsiStd, Dest.Gsi, Level.Exact), "period" -> Consent(Custom, Dest.CustomTiles, Level.District))
    val json = Consents.encode(cs)
    assertEquals(Consents.read(json, Surfaces, destOf), cs)
    assertEquals(Consents.read(json.replace("""{"v":1,""", """{"v":1,"consents":[],"""), Surfaces, destOf), Map.empty[String, Consent])
    assertEquals(Consents.read(Consents.encode(cs + ("elsewhere" -> cs("map"))), Surfaces, destOf), cs)
    // A map claimed for another destination.
    assertEquals(Consents.read(Consents.encode(Map("map" -> Consent(GsiStd, Dest.Ofm, Level.Exact))), Surfaces, destOf), Map.empty[String, Consent])
  }

  test("a policy stored with the wrong type is corrupt: recovery, kept as it is, no migration, the pause as stored") {
    for paused <- Seq(Stored.Ok(true), Stored.Missing, Stored.Bad) do
      val v = ExternalSettings.Values(Stored.Bad, Stored.Ok(Consents.encode(Map("map" -> Consent(GsiStd, Dest.Gsi, Level.Exact)))),
        Stored.Missing, paused, Stored.Ok(false), Stored.Missing)
      val (state, writes) = ExternalSettings.load(v, Surfaces, destOf, java.util.Random(1))
      assertEquals(state.policy, Policy.recovery, paused.toString)
      assertEquals(state.problem, Some(Policy.Problem.Corrupt))
      assertEquals(state.consents, Map.empty[String, Consent])
      assertEquals(state.paused, paused != Stored.Missing, paused.toString)
      // New generations are stored, the policy is not touched; older versions read "block".
      assert(writes.forall(w => !w.strings.contains(ExternalSettings.PolicyKey)))
      assert(writes.forall(w => w.booleans.get(ExternalSettings.LegacyBlockKey).forall(identity)))
    // Only a missing policy migrates.
    val (migrated, _) = ExternalSettings.load(ExternalSettings.Values(Stored.Missing, Stored.Missing, Stored.Missing,
      Stored.Ok(true), Stored.Ok(false), Stored.Missing), Surfaces, destOf, java.util.Random(1))
    assertEquals(migrated.policy, Policy.fromBlock(false))
    assert(migrated.paused)
  }

