package ashidori.core

import scala.collection.mutable

/** An external service the app itself sends to (docs/design/outbound-and-offline.md 2.1, 3.1).
  * The sync server is not one: the sync settings control it.
  */
enum Dest(val key: String):
  /** OpenFreeMap (「標準」「シンプル」): tiles, style, sprites and fonts. */
  case Ofm extends Dest("ofm")
  /** 地理院タイル. */
  case Gsi extends Dest("gsi")
  /** The user's XYZ tile URL. */
  case CustomTiles extends Dest("custom")
  /** The platform `android.location.Geocoder`. */
  case Geocoder extends Dest("geocoder")
  /** Overpass API instances (the online venue search). */
  case Overpass extends Dest("overpass")
  /** Offline map and venue data archives (GitHub Releases, the sync server's copy). */
  case Data extends Dest("data")

object Dest:
  def fromKey(k: String): Option[Dest] = values.find(_.key == k)
  /** Map tile servers: what MapLibre talks to. */
  val Tiles: Set[Dest] = Set(Ofm, Gsi, CustomTiles)

/** When a destination may be sent to. */
enum When(val key: String):
  /** Never, not even on an explicit action. */
  case Never extends When("never")
  /** Only when the user asks for that map or that search. */
  case OnAction extends When("action")
  /** Also by itself: snapshots, thumbnails, the geocoder, automatic downloads. */
  case Auto extends When("auto")

object When:
  def fromKey(k: String): Option[When] = values.find(_.key == k)

/** How fine what is sent may be: the granularity of the coordinates or
  * tile numbers sent, not a guarantee of how well the position is hidden
  * (design 3.2). [[Whole]] and [[Cell]] are for [[Dest.Data]] only.
  */
enum Level(val key: String):
  /** About 10 km: map tiles to zoom 12, a 0.1° grid. */
  case Area extends Level("area")
  /** About 1 km: map tiles to zoom 15, a 0.01° grid. */
  case District extends Level("district")
  /** No limit (maps), the position as it is (geocoder, venue search). */
  case Exact extends Level("exact")
  /** Archives of whole countries only. */
  case Whole extends Level("whole")
  /** Also the chosen region cells (phase 2, design 6). */
  case Cell extends Level("cell")

object Level:
  def fromKey(k: String): Option[Level] = values.find(_.key == k)

/** What is sent automatically or by an action. */
enum Mode:
  case OnAction, Auto

/** One destination's setting. `off`: Overpass hosts not to ask (Overpass only). */
final case class Rule(when: When, level: Level, off: Set[String] = Set.empty):
  /** The rule allows sending in `mode`. */
  def allows(mode: Mode): Boolean = mode match
    case Mode.Auto => when == When.Auto
    case Mode.OnAction => when != When.Never

/** The settings of every destination. Holds only combinations a destination
  * offers ([[Policy.choices]]).
  */
final case class Policy(rules: Map[Dest, Rule]):
  def rule(d: Dest): Rule = rules.getOrElse(d, Policy.recovery.rules(d))
  def updated(d: Dest, r: Rule): Policy = Policy(rules.updated(d, r))

object Policy:
  /** Overpass instances by operator (design 2.1): a group is switched as one. */
  val OverpassGroups: Seq[Seq[String]] = Seq(
    Seq("overpass-api.de"),
    Seq("overpass.kumi.systems", "overpass.private.coffee"),
    Seq("maps.mail.ru")
  )
  val OverpassHosts: Set[String] = OverpassGroups.flatten.toSet

  /** The times and levels a destination offers (design 3.1). */
  def choices(d: Dest): (Seq[When], Seq[Level]) = d match
    case Dest.Ofm | Dest.Gsi | Dest.CustomTiles =>
      (Seq(When.Never, When.OnAction, When.Auto), Seq(Level.Area, Level.District, Level.Exact))
    case Dest.Geocoder => (Seq(When.Never, When.Auto), Seq(Level.Area, Level.District, Level.Exact))
    case Dest.Overpass => (Seq(When.Never, When.OnAction), Seq(Level.District, Level.Exact))
    case Dest.Data => (Seq(When.Never, When.OnAction, When.Auto), Seq(Level.Whole, Level.Cell))

  /** `r` is a rule `d` offers. */
  def valid(d: Dest, r: Rule): Boolean =
    val (ws, ls) = choices(d)
    ws.contains(r.when) && ls.contains(r.level) &&
      (if d == Dest.Overpass then r.off.subsetOf(OverpassHosts) && r.off != OverpassHosts else r.off.isEmpty)

  /** From the old single setting `blockExternalLocation` (design 3.7): true
    * (or none, the old default) is what the app did by default, false
    * sends maps and the geocoder automatically.
    */
  def fromBlock(block: Boolean): Policy =
    val maps = if block then Rule(When.OnAction, Level.Exact) else Rule(When.Auto, Level.Exact)
    Policy(Map(
      Dest.Ofm -> maps, Dest.Gsi -> maps, Dest.CustomTiles -> maps,
      Dest.Geocoder -> (if block then Rule(When.Never, Level.Exact) else Rule(When.Auto, Level.Exact)),
      Dest.Overpass -> Rule(When.OnAction, Level.Exact),
      Dest.Data -> Rule(When.Auto, Level.Cell)
    ))

  /** A fresh install: what the app did before these settings (design 3.5). */
  val fresh: Policy = fromBlock(true)

  /** For a stored value that cannot be read (design 3.5): nothing sent,
    * except the offline data downloaded when the user asks for it.
    */
  val recovery: Policy = Policy(Map(
    Dest.Ofm -> Rule(When.Never, Level.Exact),
    Dest.Gsi -> Rule(When.Never, Level.Exact),
    Dest.CustomTiles -> Rule(When.Never, Level.Exact),
    Dest.Geocoder -> Rule(When.Never, Level.Exact),
    Dest.Overpass -> Rule(When.Never, Level.Exact),
    Dest.Data -> Rule(When.OnAction, Level.Whole)
  ))

  /** Why a stored policy was not used as it is. */
  enum Problem:
    /** Not JSON, a repeated key, or not in the canonical form. */
    case Corrupt
    /** Written by a newer version of the app. */
    case UnknownVersion
    /** Readable, but without a rule for these destinations. */
    case Missing(dests: Set[Dest])

  /** A stored policy as read: the policy to use, and what was wrong. */
  final case class Read(policy: Policy, problem: Option[Problem])

  val Version = 1

  /** One versioned JSON record, the rules in the order of their keys. */
  def encode(p: Policy): String = ujson.write(toJson(p))

  private def toJson(p: Policy): ujson.Value =
    ujson.Obj(
      "v" -> Version,
      "rules" -> ujson.Arr.from(p.rules.toSeq.sortBy(_._1.key).map { (d, r) =>
        val o = ujson.Obj("dest" -> d.key, "when" -> r.when.key, "level" -> r.level.key)
        if d == Dest.Overpass then o("off") = ujson.Arr.from(r.off.toSeq.sorted.map(ujson.Str(_)))
        o
      })
    )

  /** Reads a stored policy (design 3.5): parsed refusing repeated keys,
    * the version checked, unknown fields and destinations dropped, and
    * what remains must be exactly what [[encode]] writes for it. A corrupt
    * value or one of another version gives [[recovery]] for every
    * destination; a missing destination gets its recovery rule.
    */
  def read(json: String): Read =
    StrictJson.read(json) match
      case None => Read(recovery, Some(Problem.Corrupt))
      case Some(v) =>
        v.objOpt match
          case None => Read(recovery, Some(Problem.Corrupt))
          case Some(o) =>
            o.get("v") match
              case Some(ujson.Num(n)) if n == Version.toDouble => readV1(o)
              case Some(ujson.Num(_)) => Read(recovery, Some(Problem.UnknownVersion))
              case _ => Read(recovery, Some(Problem.Corrupt))

  private def readV1(o: mutable.Map[String, ujson.Value]): Read =
    try
      val rules = o.get("rules").flatMap(_.arrOpt).getOrElse(throw IllegalArgumentException("rules"))
      // Unknown destinations and fields dropped (a newer version may add them).
      val known = rules.toSeq.flatMap { r =>
        val ro = r.obj
        Dest.fromKey(ro("dest").str).map { d =>
          val fields = Seq("dest", "when", "level") ++ (if d == Dest.Overpass then Seq("off") else Nil)
          d -> ujson.Obj.from(ro.toSeq.filter((k, _) => fields.contains(k)))
        }
      }
      val stripped = ujson.Obj("v" -> Version, "rules" -> ujson.Arr.from(known.map(_._2)))
      val decoded = known.map { (d, r) =>
        val off = if d == Dest.Overpass then r("off").arr.map(_.str).toSet else Set.empty[String]
        d -> Rule(When.fromKey(r("when").str).get, Level.fromKey(r("level").str).get, off)
      }
      val p = Policy(decoded.toMap)
      // Each destination once, each rule one it offers, in the canonical form.
      if decoded.map(_._1).distinct.size != decoded.size || !decoded.forall((d, r) => valid(d, r)) ||
        encode(p) != ujson.write(stripped) then Read(recovery, Some(Problem.Corrupt))
      else
        val missing = Dest.values.toSet -- p.rules.keySet
        if missing.isEmpty then Read(p, None)
        else Read(Policy(recovery.rules ++ p.rules), Some(Problem.Missing(missing)))
    catch case _: Exception => Read(recovery, Some(Problem.Corrupt))

  /** What `blockExternalLocation` says to an older version of the app
    * (design 3.6): false only when the rules are exactly the old setting
    * off and nothing is paused; true otherwise, so an older version never
    * sends automatically to maps or the geocoder where these rules do not.
    */
  def legacyBlock(p: Policy, paused: Boolean): Boolean = paused || p != fromBlock(false)

/** A remembered choice of an online map on a map surface (design 4.1):
  * the map, its destination and the level it was given at.
  */
final case class Consent(map: String, dest: Dest, level: Level)

/** The stored consents (`externalConsents`). */
object Consents:
  val Version = 1

  def encode(cs: Map[String, Consent]): String =
    ujson.write(ujson.Obj(
      "v" -> Version,
      "consents" -> ujson.Arr.from(cs.toSeq.sortBy(_._1).map((s, c) =>
        ujson.Obj("surface" -> s, "map" -> c.map, "dest" -> c.dest.key, "level" -> c.level.key)))
    ))

  /** Consents read back fail-safe: none unless the record is exactly in the
    * form [[encode]] writes (no repeated key or surface). Entries for an
    * unknown surface, a map no longer valid or not of its destination
    * (`destOf`) are dropped.
    */
  def read(json: String, surfaces: Set[String], destOf: String => Option[Dest]): Map[String, Consent] =
    try
      val o = StrictJson.read(json).get.obj
      if !o.get("v").contains(ujson.Num(Version)) then Map.empty
      else
        val es = o("consents").arr.toSeq.map { e =>
          val r = e.obj
          r("surface").str -> Consent(r("map").str, Dest.fromKey(r("dest").str).get, Level.fromKey(r("level").str).get)
        }
        if es.map(_._1).distinct.size != es.size || encode(es.toMap) != json then Map.empty
        else es.filter((s, c) => surfaces(s) && destOf(c.map).contains(c.dest) && Dest.Tiles(c.dest)).toMap
    catch case _: Exception => Map.empty

/** Each process's own identifier (design 4.2): a permit of an earlier
  * process never passes, whatever its generation.
  */
final case class Epoch(hi: Long, lo: Long)

object Epoch:
  def random(rnd: java.util.Random = java.security.SecureRandom()): Epoch = Epoch(rnd.nextLong(), rnd.nextLong())

/** A permit to send to `dest`, taken right before sending (design 4.2):
  * valid while this process runs, nothing is paused, the rule allows
  * `mode`, and the destination's generation is still `gen` (its rule,
  * level included, did not change).
  */
final case class Permit(epoch: Epoch, dest: Dest, gen: Long, mode: Mode, level: Level)

/** The stored generations (`externalGens`): a random 64-bit value per
  * destination, new on every change of its rule and every pause or resume.
  */
object Gens:
  val Version = 1

  def encode(gs: Map[Dest, Long]): String =
    ujson.write(ujson.Obj(
      "v" -> Version,
      "gens" -> ujson.Obj.from(gs.toSeq.sortBy(_._1.key).map((d, g) => d.key -> ujson.Str(java.lang.Long.toHexString(g))))
    ))

  /** The stored generations, or None if any is unreadable or missing (then
    * every destination gets a new random one, which matches nothing stored).
    */
  def read(json: String): Option[Map[Dest, Long]] =
    try
      val o = StrictJson.read(json).get.obj
      if !o.get("v").contains(ujson.Num(Version)) then None
      else
        val gs = o("gens").obj
        Some(Dest.values.toSeq.map(d => d -> java.lang.Long.parseUnsignedLong(gs(d.key).str, 16)).toMap)
    catch case _: Exception => None

  def random(rnd: java.util.Random): Map[Dest, Long] = Dest.values.toSeq.map(d => d -> rnd.nextLong()).toMap

/** JSON parsed refusing a repeated key in any object. */
object StrictJson:
  import upickle.core.{ArrVisitor, ObjVisitor, Visitor}

  private final class Repeated(k: String) extends IllegalArgumentException(s"repeated key $k")

  def read(s: String): Option[ujson.Value] =
    try Some(ujson.transform(s, Strict)) catch case _: Exception => None

  private object Strict extends Visitor.Delegate[ujson.Value, ujson.Value](ujson.Value):
    override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[ujson.Value, ujson.Value] =
      val inner = ujson.Value.visitObject(length, jsonableKeys, index)
      new ObjVisitor[ujson.Value, ujson.Value]:
        private val seen = mutable.HashSet.empty[String]
        def visitKey(i: Int): Visitor[?, ?] = inner.visitKey(i)
        def visitKeyValue(k: Any): Unit =
          val key = k.toString
          if !seen.add(key) then throw Repeated(key)
          inner.visitKeyValue(k)
        def subVisitor: Visitor[?, ?] = Strict
        def visitValue(v: ujson.Value, i: Int): Unit = inner.visitValue(v, i)
        def visitEnd(i: Int): ujson.Value = inner.visitEnd(i)
    override def visitArray(length: Int, index: Int): ArrVisitor[ujson.Value, ujson.Value] =
      val inner = ujson.Value.visitArray(length, index)
      new ArrVisitor[ujson.Value, ujson.Value]:
        def subVisitor: Visitor[?, ?] = Strict
        def visitValue(v: ujson.Value, i: Int): Unit = inner.visitValue(v, i)
        def visitEnd(i: Int): ujson.Value = inner.visitEnd(i)
