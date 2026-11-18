package ashidori.core

import java.io.{IOException, InputStream}
import java.net.{HttpURLConnection, URI}
import scala.collection.mutable

/** Every HTTP request the app makes itself to an external destination
  * (Overpass, the data downloads, OpenFreeMap's style): sent only under a
  * valid [[Permit]] and only to that destination's hosts (design 4.4).
  *
  *  - The permit is checked right before each connection, at each redirect
  *    (followed by hand, one hop at a time, only to an allowed host), after
  *    the answer, and before each read of the body.
  *  - Open connections are registered per destination: a change of its
  *    rule or a pause cuts them at once ([[SendGate.onChange]]).
  *
  * `hosts` gives a destination's allowed hosts now (e.g. the paired sync
  * server for [[Dest.Data]]), `urls` its allowed URLs where they are known
  * kind by kind. `log` hears every request and refusal (the
  * debug build logs them). The sync client is not here: the sync settings
  * control it.
  */
final class Outbound(gate: SendGate, hosts: Dest => Set[String], log: String => Unit = _ => (),
                     urls: Dest => Option[UrlType] = _ => None):
  import Outbound.*
  private val open = mutable.Map.empty[HttpURLConnection, Permit]

  /** Cuts the connections of destinations whose rule changed. The
    * connections are disconnected on their own thread: a disconnect may wait
    * for a connection in progress, and the change comes from the UI.
    */
  val stop: () => Unit = gate.onChange { ds =>
    val cut = synchronized {
      val c = open.filter((_, p) => ds(p.dest)).toSeq
      open --= c.map(_._1)
      c
    }
    for (c, p) <- cut do
      log(s"cut dest=${p.dest.key} host=${c.getURL.getHost}")
      Outbound.cutter.execute(() => try c.disconnect() catch case _: Exception => ())
  }

  /** Connections open now (for tests). */
  def openCount: Int = synchronized(open.size)

  private def allowed(p: Permit, url: String): Unit =
    if !gate.valid(p) then
      log(s"block dest=${p.dest.key} reason=permit url=${redact(url)}")
      throw Blocked(s"${p.dest.key}: not allowed now")
    val host = try Option(URI(url).getHost).map(_.toLowerCase) catch case _: Exception => None
    val scheme = try Option(URI(url).getScheme).map(_.toLowerCase) catch case _: Exception => None
    // Where a destination's URLs are known kind by kind (OpenFreeMap's styles), only those.
    if !scheme.exists(s => s == "https" || s == "http") || !host.exists(hosts(p.dest)) ||
       urls(p.dest).exists(_.classify(url).isEmpty) then
      log(s"block dest=${p.dest.key} reason=host url=${redact(url)}")
      throw Refused(s"${p.dest.key}: ${host.getOrElse("?")} is not one of its hosts")

  /** Test hook: called with "registered" after a connection is checked and
    * registered, and "connected" after it connected, before the request is
    * written (to force a change of the rules in between).
    */
  @volatile private[core] var step: String => Unit = _ => ()

  /** `c` is still registered (no change of its destination's rules cut it)
    * and its permit still valid; otherwise it goes and [[Blocked]] is thrown.
    */
  private def still(p: Permit, c: HttpURLConnection, at: String): Unit =
    if !synchronized(open.contains(c) && gate.valid(p)) then
      log(s"block dest=${p.dest.key} reason=changed at=$at host=${c.getURL.getHost}")
      throw Blocked(s"${p.dest.key}: not allowed any more")

  /** Sends one request under `p` and returns the answer (status read).
    *
    * The check of the permit and the registration of the connection are
    * one step under this object's lock, which the cut on a change of the
    * rules also takes: a connection checked before a change is registered
    * before the cut runs and is cut; one checked after it fails the check.
    * The connection is checked again right before it connects and right
    * before the request is written, so no request leaves after the cut.
    *
    * GET follows up to [[MaxHops]] redirects, each checked as the first
    * request before it connects; other methods do not follow them. `onOpen`
    * sees each connection right after it is opened (e.g. to cut it at a
    * deadline).
    */
  def request(p: Permit, url: String, method: String = "GET", body: Option[Array[Byte]] = None,
              headers: Seq[(String, String)] = Nil, connectMs: Int = 15_000, readMs: Int = 30_000,
              onOpen: HttpURLConnection => Unit = _ => ()): Response =
    var u = url
    var hops = 0
    while true do
      // Opening makes no connection yet.
      val c = URI(u).toURL.openConnection().asInstanceOf[HttpURLConnection]
      synchronized {
        allowed(p, u)
        open(c) = p
      }
      try
        step("registered")
        onOpen(c)
        c.setInstanceFollowRedirects(false)
        c.setConnectTimeout(connectMs)
        c.setReadTimeout(readMs)
        c.setRequestMethod(method)
        headers.foreach((k, v) => c.setRequestProperty(k, v))
        if body.nonEmpty then c.setDoOutput(true)
        still(p, c, "connect")
        c.connect()
        step("connected")
        still(p, c, "write")
        log(s"send dest=${p.dest.key} mode=${p.mode} level=${p.level.key} method=$method host=${c.getURL.getHost} path=${c.getURL.getPath} hop=$hops")
        body.foreach { b =>
          val out = c.getOutputStream
          try out.write(b) finally out.close()
        }
        val code = c.getResponseCode
        // The rule may have changed while the server answered.
        still(p, c, "answer")
        val location = Option(c.getHeaderField("Location"))
        if Redirects(code) && method == "GET" && location.nonEmpty && hops < MaxHops then
          u = URI(u).resolve(location.get).toString
          hops += 1
          close(c)
        else return Response(c, p, code)
      catch
        case e: Exception =>
          close(c)
          throw (if !gate.valid(p) && !e.isInstanceOf[Blocked] then Blocked(s"${p.dest.key}: not allowed any more") else e)
    throw IllegalStateException("unreachable")

  private def close(c: HttpURLConnection): Unit =
    synchronized(open -= c)
    try c.disconnect() catch case _: Exception => ()

  /** An answer: its body is read only while the permit stays valid. */
  final class Response private[Outbound] (c: HttpURLConnection, p: Permit, val code: Int) extends AutoCloseable:
    def header(name: String): Option[String] = Option(c.getHeaderField(name))
    def body: InputStream =
      val in = if code < 400 then c.getInputStream else Option(c.getErrorStream).getOrElse(InputStream.nullInputStream())
      new java.io.FilterInputStream(in):
        private def blocked = Blocked(s"${p.dest.key}: not allowed any more")
        private def checked[A](f: => A): A =
          def ok = Outbound.this.synchronized(open.contains(c)) && gate.valid(p)
          if !ok then throw blocked
          try f catch case e: IOException => throw (if ok then e else blocked)
        override def read(): Int = checked(super.read())
        override def read(b: Array[Byte], off: Int, len: Int): Int = checked(super.read(b, off, len))
    def host: String = c.getURL.getHost
    def close(): Unit = Outbound.this.close(c)

  /** [[RangeHttp]] for [[ResumableDownload]] under `p`: each request, the
    * retries of a run included, takes this path. Uncompressed, so byte
    * ranges mean bytes of the file.
    */
  def rangeHttp(p: Permit, userAgent: String): RangeHttp = (url: String, from: Long) =>
    val r = request(p, url, headers = Seq("User-Agent" -> userAgent, "Accept-Encoding" -> "identity") ++
      (if from > 0 then Seq("Range" -> s"bytes=$from-") else Nil))
    new RangeHttp.Response:
      def code: Int = r.code
      def header(name: String): Option[String] = r.header(name)
      def body: InputStream = if r.code < 400 then r.body else InputStream.nullInputStream()
      def close(): Unit = r.close()

  /** A small body (at most `max` bytes) of a 200 answer, or None. A
    * refusal by the rules ([[Blocked]], [[Refused]]) is thrown, not None.
    */
  def small(p: Permit, url: String, headers: Seq[(String, String)] = Nil, max: Int = 1 << 20,
            connectMs: Int = 10_000, readMs: Int = 10_000): Option[String] =
    val r = try request(p, url, headers = headers, connectMs = connectMs, readMs = readMs) catch
      case e @ (_: Blocked | _: Refused) => throw e
      case _: IOException => return None
    try
      if r.code != 200 then None
      else
        val in = r.body
        try Some(String(in.readNBytes(max), java.nio.charset.StandardCharsets.UTF_8)) finally in.close()
    catch
      case e: Blocked => throw e
      case _: IOException => None
    finally r.close()

object Outbound:
  val MaxHops = 5
  private val cutter = java.util.concurrent.Executors.newSingleThreadExecutor { r =>
    val t = Thread(r, "ashidori-outbound-cut")
    t.setDaemon(true)
    t
  }
  val Redirects: Set[Int] = Set(301, 302, 303, 307, 308)

  /** The rules (or the pause) do not allow the request now. */
  final class Blocked(msg: String) extends IOException(msg)
  /** The URL is not one of the destination's hosts (e.g. a redirect elsewhere). */
  final class Refused(msg: String) extends IOException(msg)

  /** Scheme, host and path only: no query (it may hold a key or a position). */
  def redact(url: String): String =
    try
      val u = URI(url)
      s"${u.getScheme}://${u.getHost}${Option(u.getRawPath).getOrElse("")}"
    catch case _: Exception => "?"

  /** GitHub Releases and where it redirects the download to. */
  val GitHubHosts: Set[String] = Set("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
  val OfmHosts: Set[String] = Set("tiles.openfreemap.org")
