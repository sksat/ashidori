package ashidori.core

import java.io.IOException
import java.net.InetAddress
import okhttp3.{Dispatcher, Dns, Interceptor, OkHttpClient, Response}
import MapGuard.Verdict
import scala.jdk.CollectionConverters.*

/** The map library's HTTP client with [[MapGuard]] on every request
  * (design 4.4). OkHttp runs the application interceptors before it looks
  * up a name or connects, so a request refused there makes no connection.
  *
  *  - [[check]] (application interceptor): the request passes
  *    [[MapGuard.check]] and goes out without its mark. Redirects are not
  *    followed by OkHttp: this interceptor follows them itself, and each
  *    next hop passes [[MapGuard.recheck]] (holder, permit, grant, URL kind,
  *    zoom) before it is asked for, so no name is looked up and no
  *    connection made for a hop that is not allowed.
  *  - [[hop]] (network interceptor): each request on the network is
  *    verified once more (a second line).
  *  - [[dns]]: only the hosts of the map destinations' URLs are looked up,
  *    and the HTTP proxy the system selected for a request of theirs
  *    ([[selector]]; OkHttp resolves a proxy's name through the same
  *    resolver). Without a proxy no other name is looked up.
  *
  * A refusal is a plain IOException, which the map library treats as a
  * permanent failure and does not retry (prototypes 2.3). `log` hears each
  * pass and refusal; `system` resolves the names allowed.
  */
final class MapHttp(guard: MapGuard, log: String => Unit = _ => (), maxHops: Int = 5, system: Dns = Dns.SYSTEM,
                    proxies: java.net.ProxySelector = java.net.ProxySelector.getDefault):
  private def refuse(reason: String, url: String): Nothing =
    log(s"reject reason=$reason url=${Outbound.redact(url)}")
    throw IOException(s"ashidori: $reason")

  private def passed(p: Verdict.Pass, hop: Int): Unit =
    val u = java.net.URI(p.url)
    log(s"pass holder=${p.key.holder} dest=${p.key.dest.key} mode=${p.permit.mode} level=${p.permit.level.key} " +
      s"z=${p.zoom.fold("-")(_.toString)} host=${u.getHost} path=${u.getRawPath} hop=$hop")

  val check: Interceptor = chain =>
    val req = chain.request()
    guard.check(req.url.toString) match
      case Verdict.Reject(reason) => refuse(reason, TileStyle.strip(req.url.toString)._1)
      case p: Verdict.Pass =>
        passed(p, 0)
        var current = req.newBuilder().url(p.url).tag(classOf[TokenKey], p.key).build()
        var hops = 0
        var answer: Response = null
        while answer == null do
          val r = chain.proceed(current)
          val next = Option(r.header("Location")).flatMap(l => Option(r.request.url.resolve(l)))
          if r.isRedirect && next.nonEmpty then
            r.close()
            if hops >= maxHops then refuse("too many redirects", p.url)
            guard.recheck(p.key, next.get.toString) match
              case Verdict.Reject(reason) => refuse(s"redirect: $reason", next.get.toString)
              case q: Verdict.Pass =>
                hops += 1
                passed(q, hops)
                current = current.newBuilder().url(next.get).build()
          else answer = r
        answer

  val hop: Interceptor = chain =>
    val req = chain.request()
    Option(req.tag(classOf[TokenKey])) match
      case Some(k) => guard.recheck(k, req.url.toString) match
        case _: Verdict.Pass => chain.proceed(req)
        case Verdict.Reject(reason) => refuse(s"hop: $reason", req.url.toString)
      case None => refuse("hop: unchecked", req.url.toString)

  /** The names of the proxies selected for requests of the map
    * destinations ([[selector]]): OkHttp looks an HTTP proxy's name up
    * through [[dns]] too.
    */
  private val proxyNames = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** The system's selector, recording the proxy it selects for each request
    * URL (scheme, host and port, as OkHttp asks) of a map destination's host:
    * exactly that proxy's name may then be looked up.
    */
  val selector: java.net.ProxySelector = new java.net.ProxySelector:
    def select(u: java.net.URI): java.util.List[java.net.Proxy] =
      val ps = Option(proxies).flatMap(p => Option(p.select(u))).getOrElse(java.util.List.of(java.net.Proxy.NO_PROXY))
      if Option(u.getHost).exists(h => guard.hosts(h.toLowerCase)) then
        ps.asScala.foreach { p =>
          if p.`type` == java.net.Proxy.Type.HTTP then
            Option(p.address).collect { case a: java.net.InetSocketAddress => a.getHostString.toLowerCase }.foreach(proxyNames.add)
        }
      ps
    def connectFailed(u: java.net.URI, a: java.net.SocketAddress, e: IOException): Unit =
      Option(proxies).foreach(_.connectFailed(u, a, e))

  private def proxyHosts(host: String): Boolean = proxyNames.contains(host)

  /** On a change of a map destination's rules (or the pause): the calls of
    * `dispatcher` marked for it are cancelled ([[SendGate.Revoker]]), and
    * each one's future completes when it has left the dispatcher (a call
    * waiting for a name or a connection fails at its next step). Cancelled
    * again until then.
    */
  def revoker(dispatcher: Dispatcher): SendGate.Revoker = (ds, _) =>
    val calls = dispatcher.runningCalls().asScala.toSeq ++ dispatcher.queuedCalls().asScala
    calls.filter(c => guard.keyOf(c.request().url.toString).map(_.dest).forall(ds)).map { c =>
      log(s"cut dest=${guard.keyOf(c.request().url.toString).fold("?")(_.dest.key)} host=${c.request().url.host}")
      c.cancel()
      java.util.concurrent.CompletableFuture.supplyAsync(() =>
        def inFlight = (dispatcher.runningCalls().asScala.iterator ++ dispatcher.queuedCalls().asScala.iterator).exists(_ eq c)
        while inFlight do
          c.cancel()
          Thread.sleep(20)
        log(s"cut done host=${c.request().url.host}")
      , MapHttp.waiter)
    }

  val dns: Dns = host =>
    if guard.hosts(host.toLowerCase) || proxyHosts(host.toLowerCase) then system.lookup(host)
    else
      log(s"reject reason=dns host=$host")
      throw java.net.UnknownHostException(s"ashidori: $host is not a map destination's")

  /** The client: no redirects or retries of its own; the proxy the system selects. */
  def client(dispatcher: Dispatcher = Dispatcher()): OkHttpClient =
    val b = OkHttpClient.Builder()
      .dispatcher(dispatcher)
      .dns(dns)
      .addInterceptor(check)
      .addNetworkInterceptor(hop)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
    b.proxySelector(selector)
    b.build()

object MapHttp:
  private val waiter = java.util.concurrent.Executors.newCachedThreadPool { r =>
    val t = Thread(r, "ashidori-map-cut")
    t.setDaemon(true)
    t
  }
