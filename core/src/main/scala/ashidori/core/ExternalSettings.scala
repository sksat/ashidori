package ashidori.core

import MapConsents.Stored

/** The stored keys of the external-send settings (design 3.4–3.7) and how
  * they are read, migrated and written. Kept apart from Android: the app
  * reads the values from its SharedPreferences and writes [[Writes]] in one
  * editor.
  */
object ExternalSettings:
  val PolicyKey = "externalPolicy"
  val ConsentsKey = "externalConsents"
  val GensKey = "externalGens"
  val PausedKey = "externalPaused"
  val TokensKey = "externalTokens"
  /** Read once to migrate; afterwards written for older versions only. */
  val LegacyBlockKey = "blockExternalLocation"
  val LegacyConsentsKey = "mapConsents"

  /** The values as stored. A string key missing is [[Stored.Missing]]. */
  final case class Values(policy: Stored[String], consents: Stored[String], gens: Stored[String],
                          paused: Stored[Boolean], legacyBlock: Stored[Boolean], legacyConsents: Stored[String])

  /** What to write, all in one editor. */
  final case class Writes(strings: Map[String, String], booleans: Map[String, Boolean])

  /** The state to start with, and what to write now (a migration, or new
    * generations for unreadable ones). `surfaces` are the map surfaces,
    * `destOf` the destination of a map id (None for the offline map or an id
    * no longer valid, e.g. a changed custom URL).
    *
    * Without a stored policy (a fresh install or an update from a version
    * before these settings) the rules come from `blockExternalLocation`
    * ([[Policy.fromBlock]]; missing or unreadable as true) and the old
    * consents are kept at [[Level.Exact]]. A stored policy is read
    * fail-safe ([[Policy.read]]) and not written back while unreadable.
    */
  def load(v: Values, surfaces: Set[String], destOf: String => Option[Dest],
           rnd: java.util.Random): (SendGate.State, Option[Writes]) =
    v.policy match
      case Stored.Ok(json) =>
        val read = Policy.read(json)
        val p = read.policy
        val consents = v.consents match
          case Stored.Ok(c) => usable(Consents.read(c, surfaces, destOf), p)
          case _ => Map.empty
        val paused = v.paused match
          case Stored.Ok(b) => b
          case Stored.Missing => false
          // Unreadable: the safer side.
          case Stored.Bad => true
        val stored = v.gens match
          case Stored.Ok(g) => Gens.read(g)
          case _ => None
        val state = SendGate.State(p, consents, stored.getOrElse(Gens.random(rnd)), paused, read.problem)
        (state, Option.when(stored.isEmpty)(writes(state)))
      case _ =>
        // Missing (or of the wrong type, which only this version writes): migrate.
        val (block, old) = MapConsents.load(v.legacyBlock, v.legacyConsents, surfaces, id => destOf(id).isDefined)
        val p = Policy.fromBlock(block)
        val consents = old.flatMap((s, m) => destOf(m).map(d => s -> Consent(m, d, Level.Exact)))
        val state = SendGate.State(p, usable(consents, p), Gens.random(rnd))
        (state, Some(writes(state)))

  /** Consents of destinations sent to on action at the consent's level. */
  private def usable(cs: Map[String, Consent], p: Policy): Map[String, Consent] =
    cs.filter((_, c) => p.rule(c.dest).when == When.OnAction && p.rule(c.dest).level == c.level)

  /** Everything stored for `s`: the rules (unless unreadable and not
    * changed since), consents, generations, the pause, and what older
    * versions read ([[legacy]]).
    */
  def writes(s: SendGate.State): Writes =
    val (block, consents) = legacy(s)
    Writes(
      (if s.problem.isEmpty then Map(PolicyKey -> Policy.encode(s.policy)) else Map.empty) ++
        Map(ConsentsKey -> Consents.encode(s.consents), GensKey -> Gens.encode(s.gens),
          LegacyConsentsKey -> MapConsents.encode(consents)),
      Map(PausedKey -> s.paused, LegacyBlockKey -> block)
    )

  /** `blockExternalLocation` and `mapConsents` for an older version (design
    * 3.6): the old setting off only when the rules are exactly that, and
    * only consents an older version would send as they are (on action, no
    * level limit), none while paused.
    */
  def legacy(s: SendGate.State): (Boolean, Map[String, String]) =
    val block = Policy.legacyBlock(s.policy, s.paused)
    val consents =
      if s.paused || !block then Map.empty[String, String]
      else s.consents.collect { case (surface, c) if c.level == Level.Exact && s.policy.rule(c.dest) == Rule(When.OnAction, Level.Exact) => surface -> c.map }
    (block, consents)
