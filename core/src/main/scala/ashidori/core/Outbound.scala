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
  /** A registered connection. Every I/O call on it runs under the read
    * lock after checking that it was not aborted and its permit is valid
    * ([[io]]); an abort sets the flag and disconnects it until it can take
    * the write lock, so it returns only after the I/O call in progress has
    * ended, and none starts after it.
    */
  private final class Conn(val c: HttpURLConnection, val p: Permit):
    @volatile var aborted = false
    val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

  private val open = mutable.Map.empty[HttpURLConnection, Conn]

  /** On a change of a destination's rules (or the pause), before the change
    * returns: every connection of it is aborted, in parallel, and the abort
    * waits (at most [[AbortWaitMs]]) until the I/O call each was in has
    * ended. When the change returns, no further byte is written on them.
    */
  val stop: () => Unit = gate.onChange { ds =>
    val cut = synchronized {
      val c = open.values.filter(x => ds(x.p.dest)).toSeq
      open --= c.map(_.c)
      c
    }
    if cut.nonEmpty then
      val deadline = System.nanoTime() + AbortWaitMs * 1_000_000L
      val done = cut.map { x =>
        log(s"cut dest=${x.p.dest.key} host=${x.c.getURL.getHost}")
        x.aborted = true
        java.util.concurrent.CompletableFuture.runAsync(() => abort(x, deadline), Outbound.aborter)
      }
      try java.util.concurrent.CompletableFuture.allOf(done*).get(AbortWaitMs + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
      catch case _: Exception => log(s"cut: not all ended within ${AbortWaitMs} ms")
  }

  /** Disconnects `x` until the I/O call in progress on it has ended (or the deadline). */
  private def abort(x: Conn, deadline: Long): Unit =
    var ended = false
    while !ended && System.nanoTime() < deadline do
      try x.c.disconnect() catch case _: Exception => ()
      ended = x.lock.writeLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)
    if ended then x.lock.writeLock().unlock()

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
    * registered, with "checked:<call>" right after the check of each I/O
    * call (connect, write, answer, header, read), before the call, and with
    * "done:<call>" when it ended (still under the read lock).
    */
  @volatile var step: String => Unit = _ => ()

  /** One I/O call `f` on `x` (`at` names it): only if it was not aborted
    * and its permit is valid, and under the read lock, so an abort waits
    * for it to end. A failure after an abort or a change is [[Blocked]].
    */
  private def io[A](x: Conn, at: String)(f: => A): A =
    x.lock.readLock().lock()
    try
      if x.aborted || !gate.valid(x.p) then
        log(s"block dest=${x.p.dest.key} reason=changed at=$at host=${x.c.getURL.getHost}")
        throw Blocked(s"${x.p.dest.key}: not allowed any more")
      step(s"checked:$at")
      try f
      catch case e: IOException if !e.isInstanceOf[Blocked] && (x.aborted || !gate.valid(x.p)) =>
        throw Blocked(s"${x.p.dest.key}: not allowed any more")
      finally step(s"done:$at")
    finally x.lock.readLock().unlock()

  /** Sends one request under `p` and returns the answer (status read).
    *
    * The check of the permit and the registration of the connection are
    * one step under this object's lock, which the cut on a change of the
    * rules also takes: a connection checked before a change is registered
    * before the cut runs and is aborted; one checked after it fails the
    * check. Each I/O call is checked again right before it ([[io]]).
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
      val x = Conn(c, p)
      synchronized {
        allowed(p, u)
        open(c) = x
      }
      try
        step("registered")
        onOpen(c)
        c.setInstanceFollowRedirects(false)
        c.setConnectTimeout(connectMs)
        c.setReadTimeout(readMs)
        c.setRequestMethod(method)
        headers.foreach((k, v) => c.setRequestProperty(k, v))
        body.foreach { b =>
          c.setDoOutput(true)
          // Written as it is given, not buffered to be sent later.
          c.setFixedLengthStreamingMode(b.length)
        }
        io(x, "connect")(c.connect())
        log(s"send dest=${p.dest.key} mode=${p.mode} level=${p.level.key} method=$method host=${c.getURL.getHost} path=${c.getURL.getPath} hop=$hops")
        body.foreach { b =>
          io(x, "write") {
            val out = c.getOutputStream
            try out.write(b) finally out.close()
          }
        }
        val code = io(x, "answer")(c.getResponseCode)
        val location = io(x, "header")(Option(c.getHeaderField("Location")))
        if Redirects(code) && method == "GET" && location.nonEmpty && hops < MaxHops then
          u = URI(u).resolve(location.get).toString
          hops += 1
          close(c)
        else return Response(x, code)
      catch
        case e: Exception =>
          close(c)
          throw (if !gate.valid(p) && !e.isInstanceOf[Blocked] then Blocked(s"${p.dest.key}: not allowed any more") else e)
    throw IllegalStateException("unreachable")

  private def close(c: HttpURLConnection): Unit =
    synchronized(open -= c)
    try c.disconnect() catch case _: Exception => ()

  /** An answer: its headers and body are read only while it was not aborted
    * and the permit stays valid.
    */
  final class Response private[Outbound] (x: Conn, val code: Int) extends AutoCloseable:
    private val c = x.c
    def header(name: String): Option[String] = io(x, "header")(Option(c.getHeaderField(name)))
    def body: InputStream =
      val in = io(x, "read") {
        if code < 400 then c.getInputStream else Option(c.getErrorStream).getOrElse(InputStream.nullInputStream())
      }
      new java.io.FilterInputStream(in):
        override def read(): Int = io(x, "read")(super.read())
        override def read(b: Array[Byte], off: Int, len: Int): Int = io(x, "read")(super.read(b, off, len))
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
  val Redirects: Set[Int] = Set(301, 302, 303, 307, 308)
  /** How long a change waits for the aborted connections' I/O calls to end. */
  val AbortWaitMs = 3_000L
  private val aborter = java.util.concurrent.Executors.newCachedThreadPool { r =>
    val t = Thread(r, "ashidori-outbound-abort")
    t.setDaemon(true)
    t
  }

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
