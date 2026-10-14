package ashidori.core.sync

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** The QR code the web viewer shows for browser pairing
  * (docs/design/browser-pairing-v1.md, 「QR コード」):
  *
  * `ashidori://viewer?server=<origin>&id=<id>&code=<code>&c=<commit>`
  *
  *  - `server`: the page's origin `scheme://host[:port]`, percent-encoded as
  *    JavaScript's `encodeURIComponent` does;
  *  - `id`: the attempt id, base64url without padding (16 bytes);
  *  - `code`: the code, 8 Crockford characters, upper case, no hyphen;
  *  - `c`: the browser's commit H("ashidori pair commit v1" ‖ pkB ‖ nB),
  *    64 lower-case hex digits.
  *
  * The camera carries `c` from the browser to the app without the server,
  * so the server cannot put another key in its place. Only this exact form
  * is accepted: the parameters in this order, each once, and nothing that
  * encodes differently from [[ViewerQr.uri]].
  */
final case class ViewerQr(server: String, id: String, code: String, commitHex: String):
  def commit: Array[Byte] = ViewerQr.unhex(commitHex)
  def uri: String = ViewerQr.uri(server, id, code, commitHex)

object ViewerQr:
  val Prefix = "ashidori://viewer?"
  /** Far above any real one (about 180 characters); longer text is not parsed. */
  private val MaxLen = 512
  private val CodeRe = "[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}".r
  private val HexRe = "[0-9a-f]{64}".r

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  /** As JavaScript's encodeURIComponent, for the characters an origin has. */
  private def encode(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  def uri(server: String, id: String, code: String, commitHex: String): String =
    s"${Prefix}server=${encode(server)}&id=$id&code=$code&c=$commitHex"

  /** Parses the exact form; None for anything else. http only when `allowHttp` (debug builds). */
  def parse(text: String, allowHttp: Boolean = false): Option[ViewerQr] =
    if text.length > MaxLen || !text.startsWith(Prefix) then None
    else
      text.drop(Prefix.length).split("&", -1).toList match
        case List(s"server=$srv", s"id=$id", s"code=$code", s"c=$c") =>
          for
            raw <- Try(URLDecoder.decode(srv, UTF_8)).toOption
            server <- PairLink.normalizeServer(raw, allowHttp)
            if server == raw
            if ViewerPairing.unb64(id, ViewerPairing.IdLen).isDefined
            if CodeRe.matches(code) && HexRe.matches(c)
            qr = ViewerQr(server, id, code, c)
            // Canonical only: no other percent-encoding of the same values.
            if qr.uri == text
          yield qr
        case _ => None

  /** Whether the QR is of the server the app syncs with (`configured`, as in the
    * sync settings): the same scheme, host and effective port, so that
    * `https://host:443` (a setting) and `https://host` (a page's origin) agree,
    * while other ports differ.
    */
  def sameServer(qr: ViewerQr, configured: String, allowHttp: Boolean): Boolean =
    val a = origin(qr.server, allowHttp)
    a.isDefined && a == origin(configured, allowHttp)

  /** (scheme, host, port) of a server URL, the port 443 or 80 when it has none; None if not a server URL. */
  def origin(server: String, allowHttp: Boolean): Option[(String, String, Int)] =
    PairLink.normalizeServer(server, allowHttp).flatMap { n =>
      Try(java.net.URI(n)).toOption.map { u =>
        val port = if u.getPort != -1 then u.getPort else if u.getScheme == "https" then 443 else 80
        (u.getScheme, u.getHost, port)
      }
    }
