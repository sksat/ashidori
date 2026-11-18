package ashidori.core

import scala.collection.mutable
import scala.util.matching.Regex

/** Who a map request belongs to: a map surface (`map`, `period`, `picker`),
  * the snapshots (`snapshot`) or a picker thumbnail (`thumb:<dest>`), with
  * the destination, mode, level and generation it was made for.
  */
final case class TokenKey(holder: String, dest: Dest, mode: Mode, level: Level, gen: Long)

/** The marks (`ashidori-t=<token>`) the app puts on every remote URL it
  * gives MapLibre (design 4.4): a 128-bit random token per [[TokenKey]], the
  * same while the key is, so MapLibre's cache (keyed by URL) still works
  * after a restart. Stored through `persist`; entries of an old generation
  * are dropped. A token is not a permit: [[MapGuard]] checks the holder's
  * permit of this process. Thread-safe.
  */
final class TokenTable(initial: Map[TokenKey, String], persist: Map[TokenKey, String] => Unit = _ => (),
                       rnd: java.util.Random = java.security.SecureRandom()):
  private var table = initial

  def token(k: TokenKey): String = synchronized {
    table.getOrElse(k, {
      val t = f"${rnd.nextLong()}%016x${rnd.nextLong()}%016x"
      // Tokens of an older generation of this destination never pass again.
      table = table.filterNot((o, _) => o.dest == k.dest && o.gen != k.gen) + (k -> t)
      persist(table)
      t
    })
  }

  def lookup(t: String): Option[TokenKey] = synchronized(table.collectFirst { case (k, v) if v == t => k })
  def entries: Map[TokenKey, String] = synchronized(table)

object TokenTable:
  val Version = 1

  def encode(t: Map[TokenKey, String]): String =
    ujson.write(ujson.Obj("v" -> Version, "tokens" -> ujson.Arr.from(t.toSeq.sortBy(_._2).map((k, v) => ujson.Obj(
      "holder" -> k.holder, "dest" -> k.dest.key, "mode" -> k.mode.toString, "level" -> k.level.key,
      "gen" -> java.lang.Long.toHexString(k.gen), "token" -> v)))))

  /** The stored table, or none if it is not readable (new tokens are then made). */
  def read(json: String): Map[TokenKey, String] =
    try
      val o = StrictJson.read(json).get.obj
      if !o.get("v").contains(ujson.Num(Version)) then Map.empty
      else o("tokens").arr.toSeq.map { e =>
        val r = e.obj
        TokenKey(r("holder").str, Dest.fromKey(r("dest").str).get, Mode.valueOf(r("mode").str),
          Level.fromKey(r("level").str).get, java.lang.Long.parseUnsignedLong(r("gen").str, 16)) -> r("token").str
      }.toMap
    catch case _: Exception => Map.empty

/** One kind of resource a map destination serves (a tile, a sprite, a
  * font range): its URLs match `pattern` as a whole. For a tile the first
  * group is the zoom.
  */
final case class UrlKind(name: String, pattern: Regex, tile: Boolean)

/** The URLs a map destination may be asked for, kind by kind ([[UrlKind]]);
  * any other URL, also on the same host, is not one of them. A URL with
  * z/x/y-like path segments carries the largest such zoom as well, so the
  * zoom limit holds whatever kind it is taken for.
  */
final case class UrlType(kinds: Seq[UrlKind]):
  /** None if `url` is not of this type; else its kind and the zoom it carries, if any. */
  def classify(url: String): Option[(String, Option[Int])] =
    kinds.iterator.flatMap { k =>
      k.pattern.unapplySeq(url: CharSequence).map { groups =>
        val z = if k.tile then groups.headOption.flatMap(_.toIntOption) else None
        k.name -> (z.toSeq ++ UrlType.zxy(url)).maxOption
      }
    }.nextOption()

  /** Whether `host` serves one of the kinds (for the name lookup). */
  def hosts: Set[String] = kinds.flatMap(k => UrlType.hostOf(k.pattern.regex)).toSet

object UrlType:
  /** `scheme://host[:port]`, lower case, or None (not http(s), user info, no host). */
  def origin(url: String): Option[String] =
    try
      val u = java.net.URI(url)
      val scheme = Option(u.getScheme).map(_.toLowerCase)
      if !scheme.exists(s => s == "https" || s == "http") || u.getRawUserInfo != null || u.getHost == null then None
      else Some(s"${scheme.get}://${u.getHost.toLowerCase}${if u.getPort >= 0 then s":${u.getPort}" else ""}")
    catch case _: Exception => None

  private val Triple = """/(\d{1,2})/(\d+)/(\d+)(?=[./?#]|$)""".r
  /** The largest zoom of z/x/y-like path segments in `url`. */
  def zxy(url: String): Option[Int] =
    val path = url.takeWhile(c => c != '?' && c != '#')
    Triple.findAllMatchIn(path).flatMap(_.group(1).toIntOption).maxOption

  /** The literal host a pattern starts with (for [[UrlType.hosts]]). */
  private[core] def hostOf(pattern: String): Option[String] =
    val rest = pattern.stripPrefix("\\Q").dropWhile(_ != '/').drop(2)
    val host = rest.takeWhile(c => c != '/' && c != ':').replace("\\", "")
    Option(host).filter(h => h.nonEmpty && h.forall(c => c.isLetterOrDigit || c == '.' || c == '-'))

  /** The tiles of an XYZ template ({z} {x} {y}): exactly that URL with numbers in. */
  def template(t: String): Option[UrlType] =
    origin(Seq("{z}", "{x}", "{y}").foldLeft(t)(_.replace(_, "0"))).map { _ =>
      val parts = java.util.regex.Pattern.quote(t).replace("{z}", "\\E(\\d+)\\Q").replace("{x}", "\\E\\d+\\Q").replace("{y}", "\\E\\d+\\Q")
      UrlType(Seq(UrlKind("tile", parts.r, tile = true)))
    }

  /** OpenFreeMap as its styles refer to it: vector tiles, the shaded relief
    * raster, sprites and font ranges. Its style and TileJSON are fetched by
    * the app ([[Outbound]]), not by the map library.
    */
  val Ofm: UrlType = UrlType(Seq(
    UrlKind("tile", """https://tiles\.openfreemap\.org/planet/[0-9A-Za-z_]+/(\d{1,2})/\d+/\d+\.pbf""".r, tile = true),
    UrlKind("relief", """https://tiles\.openfreemap\.org/natural_earth/[0-9a-z_]+/(\d{1,2})/\d+/\d+\.png""".r, tile = true),
    UrlKind("sprite", """https://tiles\.openfreemap\.org/sprites/[0-9A-Za-z_]+/[0-9A-Za-z_]+(?:@[23]x)?\.(?:json|png)""".r, tile = false),
    UrlKind("glyphs", """https://tiles\.openfreemap\.org/fonts/[^/?#]+/\d+-\d+\.pbf""".r, tile = false)
  ))
  /** What the app itself fetches from OpenFreeMap: a style and the TileJSON its sources name. */
  val OfmStyles: UrlType = UrlType(Seq(
    UrlKind("style", """https://tiles\.openfreemap\.org/styles/[0-9a-z_-]+""".r, tile = false),
    UrlKind("tilejson", """https://tiles\.openfreemap\.org/(?:planet|natural_earth/[0-9a-z_]+)""".r, tile = false)
  ))
  /** 地理院タイル: tiles only. */
  val Gsi: UrlType = UrlType(Seq(
    UrlKind("tile", """https://cyberjapandata\.gsi\.go\.jp/xyz/[a-z0-9_]+/(\d{1,2})/\d+/\d+\.(?:png|jpg)""".r, tile = true)))

/** The third line of defence for MapLibre (design 4.4, 4.6): every request
  * of the map library passes [[check]] in an HTTP interceptor before it is
  * sent, and every redirect hop passes [[recheck]] before it connects. A
  * map holder registers the permit it renders under ([[hold]]) and gets
  * the token to mark its URLs with; a request passes only when
  *
  *  1. it carries a known token,
  *  2. its URL is one of the token's destination's kinds ([[UrlType]]: the
  *     token, not the host, decides the destination, so a custom URL on a
  *     built-in map's host is told apart),
  *  3. the holder's permit of this process matches the token and is valid,
  *     and for an explicit (on-action) map, a view of that holder holds a
  *     grant for it ([[SendGate.grant]]),
  *  4. any zoom it carries is within the level's ([[Precision.maxZoom]]).
  *
  * The token is taken out of the URL sent. Thread-safe.
  */
final class MapGuard(gate: SendGate, tokens: TokenTable, urlType: Dest => Option[UrlType]):
  private final case class Held(permit: Permit, owners: Set[AnyRef])
  private val held = mutable.Map.empty[(String, Dest), Held]

  /** `owner` renders for `holder` under `p`: returns the token to put on its URLs. */
  def hold(holder: String, owner: AnyRef, p: Permit): String =
    synchronized {
      val owners = held.get((holder, p.dest)).filter(_.permit == p).fold(Set.empty[AnyRef])(_.owners)
      held((holder, p.dest)) = Held(p, owners + owner)
    }
    tokens.token(TokenKey(holder, p.dest, p.mode, p.level, p.gen))

  /** `owner` no longer renders for `holder` (its map is gone or offline). */
  def release(holder: String, owner: AnyRef): Unit = synchronized {
    for k <- held.keys.toSeq if k._1 == holder do
      val h = held(k)
      val rest = h.owners - owner
      if rest.isEmpty then held -= k else held(k) = h.copy(owners = rest)
  }

  /** Whether `url` may be sent now, and what to send instead. */
  def check(url: String): MapGuard.Verdict =
    import MapGuard.Verdict.Reject
    TileStyle.strip(url) match
      case (_, None) => Reject("no token")
      case (clean, Some(t)) =>
        tokens.lookup(t) match
          case None => Reject("unknown token")
          case Some(k) => verify(k, clean)

  /** A further hop (a redirect, or the request on the network) of a request
    * [[check]] passed with key `k`: everything checked again for `url` now,
    * the holder, its permit and grant included.
    */
  def recheck(k: TokenKey, url: String): MapGuard.Verdict = verify(k, url)

  /** The key of the token `url` carries, if any. */
  def keyOf(url: String): Option[TokenKey] = TileStyle.strip(url)._2.flatMap(tokens.lookup)

  /** `owner` still renders for `holder` under `p`, which is valid, and on
    * action its view holds the grant: what a retry on its behalf needs.
    */
  def allows(holder: String, owner: AnyRef, p: Permit): Boolean =
    this.synchronized(held.get((holder, p.dest))).exists(h => h.permit == p && h.owners.contains(owner)) &&
      gate.valid(p) && (p.mode == Mode.Auto || gate.granted(owner).contains(p))

  /** Hosts the map library may look up now: those of the destinations' URLs. */
  def hosts: Set[String] = Dest.Tiles.flatMap(d => urlType(d).toSeq.flatMap(_.hosts))

  private def verify(k: TokenKey, clean: String): MapGuard.Verdict =
    import MapGuard.Verdict.{Pass, Reject}
    urlType(k.dest).flatMap(_.classify(clean)) match
      case None => Reject(s"not a ${k.dest.key} URL")
      case Some((_, zoom)) =>
        this.synchronized(held.get((k.holder, k.dest))) match
          case None => Reject(s"${k.holder} holds no permit")
          case Some(h) =>
            val p = h.permit
            if p.mode != k.mode || p.level != k.level || p.gen != k.gen then Reject("stale token")
            else if !gate.valid(p) then Reject("permit no longer valid")
            else if p.mode == Mode.OnAction && !h.owners.exists(o => gate.granted(o).contains(p)) then
              Reject(s"${k.holder} not on screen")
            else if zoom.exists(z => Precision.maxZoom(p.level).exists(z > _)) then Reject(s"zoom ${zoom.get} over the level")
            else Pass(clean, k, p, zoom)

object MapGuard:
  enum Verdict:
    case Pass(url: String, key: TokenKey, permit: Permit, zoom: Option[Int])
    case Reject(reason: String)

/** Styles for MapLibre with the marks and the zoom limit of a holder (design 4.7). */
object TileStyle:
  val Param = "ashidori-t"
  private val QueryTok = ("""([?&])""" + Param + """=([0-9a-f]+)(?=&|$)""").r

  /** `url` with the token as a query parameter (MapLibre keeps it through
    * its expansion of sprites, fonts and tiles; prototypes 2.2).
    */
  def withToken(url: String, token: String): String =
    val (base, frag) = url.indexOf('#') match
      case -1 => (url, "")
      case i => (url.substring(0, i), url.substring(i))
    base + (if base.contains("?") then "&" else "?") + s"$Param=$token" + frag

  /** `url` without its token, and the token (None if it has none or two). */
  def strip(url: String): (String, Option[String]) =
    QueryTok.findAllMatchIn(url).toSeq match
      case Seq(m) =>
        val rest = url.substring(m.end)
        // "?t&a" keeps "?a"; "?t" and "&t" go with their separator.
        val clean =
          if m.group(1) == "?" && rest.startsWith("&") then url.substring(0, m.start) + "?" + rest.drop(1)
          else url.substring(0, m.start) + rest
        (clean, Some(m.group(2)))
      case _ => (url, None)

  private def remote(s: String): Boolean = s.startsWith("https://") || s.startsWith("http://")

  /** A raster map ({z} {x} {y} template) at most at `cap`, marked with `token`. */
  def raster(template: String, maxZoom: Int, credit: String, cap: Option[Int], token: String,
             glyphs: Option[String]): String =
    val style = ujson.Obj(
      "version" -> 8,
      "sources" -> ujson.Obj(
        "base" -> ujson.Obj(
          "type" -> "raster",
          "tiles" -> ujson.Arr(withToken(template, token)),
          "tileSize" -> 256,
          "maxzoom" -> cap.fold(maxZoom)(math.min(_, maxZoom)),
          "attribution" -> credit
        )
      ),
      "layers" -> ujson.Arr(ujson.Obj("id" -> "base", "type" -> "raster", "source" -> "base"))
    )
    glyphs.foreach(g => style("glyphs") = g)
    ujson.write(style)

  /** A vector style fetched from its server, made to be sent only marked:
    * each source given by a TileJSON `url` gets that TileJSON's `tiles`
    * (`tileJson` fetches it), every remote URL gets `token`, and every
    * source's `maxzoom` is at most `cap`.
    */
  def vector(style: String, tileJson: String => String, cap: Option[Int], token: String): String =
    val st = ujson.read(style)
    def mark(s: String) = if remote(s) then withToken(s, token) else s
    st.obj.get("sprite").foreach {
      case ujson.Str(s) => st("sprite") = mark(s)
      case ujson.Arr(xs) => xs.foreach(e => e.obj.get("url").collect { case ujson.Str(u) => e("url") = mark(u) })
      case _ => ()
    }
    st.obj.get("glyphs").collect { case ujson.Str(g) => st("glyphs") = mark(g) }
    for (_, src) <- st.obj.get("sources").map(_.obj).getOrElse(mutable.LinkedHashMap.empty[String, ujson.Value]) do
      val so = src.obj
      so.get("url").collect { case ujson.Str(u) if remote(u) =>
        val tj = ujson.read(tileJson(u))
        so.remove("url")
        so("tiles") = tj("tiles")
        for k <- Seq("minzoom", "maxzoom", "bounds", "attribution", "scheme", "tileSize", "encoding") do
          tj.obj.get(k).foreach(v => if !so.contains(k) || k != "attribution" then so(k) = v)
      }
      so.get("tiles").foreach(ts => so("tiles") = ujson.Arr.from(ts.arr.map(t => ujson.Str(mark(t.str)))))
      so.get("data").collect { case ujson.Str(d) if remote(d) => so("data") = mark(d) }
      if so.contains("tiles") || so.get("type").exists(t => t.strOpt.exists(Set("vector", "raster", "raster-dem")(_))) then
        cap.foreach(c => so("maxzoom") = so.get("maxzoom").flatMap(_.numOpt).fold(c.toDouble)(m => math.min(m, c.toDouble)))
    ujson.write(st)

  /** Every remote URL a style refers to (sprites, fonts, sources). */
  def remoteUrls(style: String): Seq[String] =
    val st = ujson.read(style)
    val sprite = st.obj.get("sprite").toSeq.flatMap {
      case ujson.Str(s) => Seq(s)
      case ujson.Arr(xs) => xs.toSeq.flatMap(_.obj.get("url").flatMap(_.strOpt))
      case _ => Nil
    }
    val glyphs = st.obj.get("glyphs").flatMap(_.strOpt).toSeq
    val sources = st.obj.get("sources").toSeq.flatMap(_.obj.values).flatMap { s =>
      s.obj.get("url").flatMap(_.strOpt).toSeq ++ s.obj.get("tiles").toSeq.flatMap(_.arr.map(_.str)) ++
        s.obj.get("data").flatMap(_.strOpt).toSeq
    }
    (sprite ++ glyphs ++ sources).filter(remote)

  /** Remote URLs of a style without a token: none may be left (a request without one is refused). */
  def unmarked(style: String): Seq[String] = remoteUrls(style).filter(u => strip(u)._2.isEmpty)
