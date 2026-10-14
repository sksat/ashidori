package ashidori.core.sync

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A registration link: the server to register with, and either a pairing
  * code (add this device to an existing user) or an invite (create a new
  * user, this device its first).
  *
  * Two forms (docs/sync-protocol.md, "Registration links"):
  *  - `https://<server>/pair#c=<CODE>` or `#i=<INVITE>`: issued by the
  *    server and the app. The code is in the fragment, so a browser that
  *    opens the link never sends it to the server. Opens the app through
  *    Android App Links.
  *  - `ashidori://pair?server=<url>&c=<CODE>` or `&i=<INVITE>`: the fallback
  *    the server's `/pair` page links to when the app did not take the https
  *    link.
  *
  * Both are parsed strictly: anything that is not exactly one of these forms
  * is refused. The recovery code is never part of a link.
  *
  * @param server `scheme://host[:port]`, lowercase, without a trailing slash
  * @param code   the pairing code (8 characters) or the invite (16), of
  *               Crockford base32, uppercase, without hyphens
  * @param invite `code` is an invite (`i=`), not a pairing code (`c=`)
  */
final case class PairLink(server: String, code: String, invite: Boolean = false)

object PairLink:
  /** A pairing code as the server issues it (server/src/util.ts, generatePairingCode). */
  private val CodeRe = "[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}".r
  /** An invite as the server issues it (server/src/util.ts, generateInviteCode). */
  private val InviteRe = "[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}".r
  private val HostRe = "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*".r

  def validCode(c: String): Boolean = CodeRe.matches(c)
  def validInvite(c: String): Boolean = InviteRe.matches(c)

  /** The https link for `server` (as configured in the app) and a pairing `code`. */
  def url(server: String, code: String): String = s"${server.stripSuffix("/")}/pair#c=$code"

  /** The https link for `server` and an invite. */
  def inviteUrl(server: String, invite: String): String = s"${server.stripSuffix("/")}/pair#i=$invite"

  /** `scheme://host[:port]` of a server URL without path, query, fragment or
    * user info (a single trailing slash is allowed); None otherwise. Only
    * https, and http when `allowHttp` (debug builds and a local server).
    */
  def normalizeServer(s: String, allowHttp: Boolean): Option[String] =
    Try(URI(s.trim)).toOption.flatMap { u =>
      val scheme = Option(u.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(u.getHost).map(_.toLowerCase).getOrElse("")
      val path = Option(u.getRawPath).getOrElse("")
      val okScheme = scheme == "https" || (allowHttp && scheme == "http")
      val okPort = u.getPort == -1 || (u.getPort >= 1 && u.getPort <= 65535)
      if okScheme && HostRe.matches(host) && okPort && u.getRawUserInfo == null &&
        (path == "" || path == "/") && u.getRawQuery == null && u.getRawFragment == null
      then Some(s"$scheme://$host" + (if u.getPort == -1 then "" else s":${u.getPort}"))
      else None
    }

  /** Parses either link form; None unless it is exactly one of them. */
  def parse(link: String, allowHttp: Boolean = false): Option[PairLink] =
    Try(URI(link)).toOption.flatMap { u =>
      Option(u.getScheme).map(_.toLowerCase) match
        case Some("https") => parseHttps(u)
        case Some("ashidori") => parseCustom(u, allowHttp)
        case _ => None
    }

  /** `c=<CODE>` or `i=<INVITE>`, exactly: the key decides which, and the value must fit it. */
  private def secret(key: String, value: String): Option[(String, Boolean)] = key match
    case "c" if validCode(value) => Some((value, false))
    case "i" if validInvite(value) => Some((value, true))
    case _ => None

  private def parseHttps(u: URI): Option[PairLink] =
    for
      frag <- Option(u.getRawFragment)
      i = frag.indexOf('=')
      if i > 0
      (code, invite) <- secret(frag.take(i), frag.drop(i + 1))
      if u.getRawPath == "/pair" && u.getRawQuery == null
      authority <- Option(u.getRawAuthority)
      server <- normalizeServer(s"https://$authority", allowHttp = false)
    yield PairLink(server, code, invite)

  private def parseCustom(u: URI, allowHttp: Boolean): Option[PairLink] =
    if u.getRawAuthority != "pair" || Option(u.getRawPath).exists(p => p.nonEmpty && p != "/") || u.getRawFragment != null then None
    else
      val params = Option(u.getRawQuery).toSeq.flatMap(_.split("&", -1)).map { kv =>
        val i = kv.indexOf('=')
        if i < 0 then (kv, None) else (kv.take(i), Try(URLDecoder.decode(kv.drop(i + 1), UTF_8)).toOption)
      }
      val keys = params.map(_._1).sorted
      if keys != Seq("c", "server") && keys != Seq("i", "server") then None
      else
        val m = params.toMap
        val key = keys.head
        for
          server <- m("server").flatMap(normalizeServer(_, allowHttp))
          value <- m(key)
          (code, invite) <- secret(key, value)
        yield PairLink(server, code, invite)
