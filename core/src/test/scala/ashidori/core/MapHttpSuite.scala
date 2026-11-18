package ashidori.core

import com.sun.net.httpserver.HttpServer
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.{Dns, Request}
import MapGuard.Verdict.Pass
import OutboundFixtures.*

/** The map library's client ([[MapHttp]]) against local servers: names are
  * resolved by a counting resolver ("tiles.test" is the map's server,
  * "elsewhere.test" another one, both on this machine), so the test sees
  * every name looked up and every connection made.
  */
class MapHttpSuite extends munit.FunSuite:
  /** The map server: `handle` answers by path; counts requests. */
  private final class Tiles(handle: String => (Int, Seq[(String, String)])):
    val hits = AtomicInteger()
    private val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", ex =>
      hits.incrementAndGet()
      val (code, headers) = handle(ex.getRequestURI.toString)
      headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
      val b = "x".getBytes
      ex.sendResponseHeaders(code, if code == 200 then b.length else -1)
      if code == 200 then ex.getResponseBody.write(b)
      ex.close())
    s.start()
    def port: Int = s.getAddress.getPort
    def stop(): Unit = s.stop(0)

  /** Another server: counts the connections it accepts (none may come). */
  private final class Elsewhere:
    val accepts = AtomicInteger()
    private val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    private val t = Thread(() =>
      try while true do
        val c = ss.accept()
        accepts.incrementAndGet()
        c.close()
      catch case _: Exception => ())
    t.setDaemon(true)
    t.start()
    def port: Int = ss.getLocalPort
    def stop(): Unit = ss.close()

  private final class Lookups extends Dns:
    val names = java.util.concurrent.ConcurrentLinkedQueue[String]()
    def lookup(host: String): java.util.List[InetAddress] =
      names.add(host)
      java.util.List.of(InetAddress.getLoopbackAddress)

  private def fixture(policy: Policy, tiles: Tiles, proxy: Option[Int] = None, proxyOnlyFor: Option[Int] = None,
                      dnsHook: String => Unit = _ => ()) =
    val g = gate(policy, revokeWaitMs = 300)
    val template = s"http://tiles.test:${tiles.port}/t/{z}/{x}/{y}.png"
    val guard = MapGuard(g, TokenTable(Map.empty), d => Option.when(d == Dest.CustomTiles)(UrlType.template(template)).flatten)
    val dns = Lookups()
    val log = scala.collection.mutable.ArrayBuffer.empty[String]
    val selector = new java.net.ProxySelector:
      def select(u: java.net.URI): java.util.List[java.net.Proxy] = proxy match
        // Only for the URLs of one port, if so configured (a per-port rule).
        case Some(port) if proxyOnlyFor.forall(_ == u.getPort) =>
          java.util.List.of(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.test", port)))
        case _ => java.util.List.of(java.net.Proxy.NO_PROXY)
      def connectFailed(u: java.net.URI, a: java.net.SocketAddress, e: java.io.IOException): Unit = ()
    val http = MapHttp(guard, l => log.synchronized(log += l), system = { host => dnsHook(host); dns.lookup(host) }, proxies = selector)
    val dispatcher = okhttp3.Dispatcher()
    g.onRevoke(http.revoker(dispatcher))
    val client = http.client(dispatcher)
    (g, guard, client, dns, log)

  private def get(client: okhttp3.OkHttpClient, url: String): Either[java.io.IOException, Int] =
    try
      val r = client.newCall(Request.Builder().url(url).build()).execute()
      try Right(r.code) finally r.close()
    catch case e: java.io.IOException => Left(e)

  private def withServers(handle: (Tiles, Elsewhere) => String => (Int, Seq[(String, String)]))(f: (Tiles, Elsewhere) => Unit): Unit =
    val other = Elsewhere()
    var tiles: Tiles = null
    tiles = Tiles(path => handle(tiles, other)(path))
    try f(tiles, other) finally { tiles.stop(); other.stop() }

  private val auto = Policy.fromBlock(false).updated(Dest.CustomTiles, Rule(When.Auto, Level.District))

  test("a request refused by the guard looks up no name and makes no connection") {
    withServers((_, _) => _ => (200, Nil)) { (tiles, _) =>
      val (g, guard, client, dns, _) = fixture(auto, tiles)
      val url = s"http://tiles.test:${tiles.port}/t/14/1/2.png"
      // No mark; an unknown mark; a valid mark after the pause.
      assert(get(client, url).isLeft)
      assert(get(client, TileStyle.withToken(url, "00ff")).isLeft)
      val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
      g.setPaused(true)
      assert(get(client, TileStyle.withToken(url, t)).isLeft)
      assertEquals(dns.names.size, 0)
      assertEquals(tiles.hits.get, 0)
    }
  }

  test("a redirect to another host is refused before its name is looked up or a connection made") {
    withServers((_, other) => {
      case p if p.startsWith("/t/14/") => (302, Seq("Location" -> s"http://elsewhere.test:${other.port}/t/14/1/2.png"))
      case _ => (200, Nil)
    }) { (tiles, other) =>
      val (g, guard, client, dns, log) = fixture(auto, tiles)
      val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
      val r = get(client, TileStyle.withToken(s"http://tiles.test:${tiles.port}/t/14/1/2.png", t))
      assert(r.isLeft, r.toString)
      assertEquals(tiles.hits.get, 1)
      assertEquals(other.accepts.get, 0)
      assertEquals(dns.names.toArray.toSeq, Seq("tiles.test"))
      assert(log.exists(_.contains("reject reason=redirect: not a custom URL")), log.toString)
    }
  }

  test("a redirect on the same server: followed when allowed, refused over the zoom or after a revoke") {
    withServers((_, _) => {
      case p if p.startsWith("/t/14/1/") => (302, Seq("Location" -> "/t/15/1/2.png"))
      case p if p.startsWith("/t/13/1/") => (302, Seq("Location" -> "/t/16/1/2.png"))
      case _ => (200, Nil)
    }) { (tiles, _) =>
      val (g, guard, client, _, _) = fixture(auto, tiles)
      val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
      def at(z: Int) = TileStyle.withToken(s"http://tiles.test:${tiles.port}/t/$z/1/2.png", t)
      assertEquals(get(client, at(14)), Right(200))
      assertEquals(tiles.hits.get, 2)
      // 16 is over 地区 (15): the hop is not asked for.
      assert(get(client, at(13)).isLeft)
      assertEquals(tiles.hits.get, 3)
    }
  }

  test("a later hop of an on-action map needs the grant still: a revoke during the first answer refuses it") {
    var revoke: () => Unit = () => ()
    withServers((_, _) => {
      case p if p.startsWith("/t/14/") =>
        revoke()
        (302, Seq("Location" -> "/t/15/1/2.png"))
      case _ => (200, Nil)
    }) { (tiles, _) =>
      val policy = Policy.fresh.updated(Dest.CustomTiles, Rule(When.OnAction, Level.Exact))
      val (g, guard, client, _, log) = fixture(policy, tiles)
      val view = Object()
      g.remember("map", Custom, Dest.CustomTiles)
      val p = g.permit(Dest.CustomTiles, Mode.OnAction).get
      g.grant(view, p)
      val t = guard.hold("map", view, p)
      revoke = () => g.revoke(view)
      assert(get(client, TileStyle.withToken(s"http://tiles.test:${tiles.port}/t/14/1/2.png", t)).isLeft)
      assertEquals(tiles.hits.get, 1)
      assert(log.exists(_.contains("redirect: map not on screen")), log.toString)
    }
  }

  test("names: only the hosts of the map destinations' URLs") {
    withServers((_, _) => _ => (200, Nil)) { (tiles, _) =>
      val (_, guard, client, dns, _) = fixture(auto, tiles)
      assertEquals(guard.hosts, Set("tiles.test"))
      intercept[java.net.UnknownHostException](client.dns.lookup("elsewhere.test"))
      assertEquals(dns.names.size, 0)
    }
  }

  test("through the system's HTTP proxy: only the proxy's name is looked up; a refused request looks up nothing") {
    val asked = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    proxy.createContext("/", ex =>
      asked.add(ex.getRequestURI.toString)
      val b = "x".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
      ex.close())
    proxy.start()
    try
      withServers((_, _) => _ => (200, Nil)) { (tiles, _) =>
        val (g, guard, client, dns, _) = fixture(auto, tiles, proxy = Some(proxy.getAddress.getPort))
        val url = s"http://tiles.test:${tiles.port}/t/14/1/2.png"
        assert(get(client, url).isLeft)
        assertEquals(dns.names.size, 0)
        val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
        assertEquals(get(client, TileStyle.withToken(url, t)), Right(200))
        assertEquals(dns.names.toArray.toSeq, Seq("proxy.test"))
        assertEquals(asked.toArray.toSeq, Seq(url))
        assertEquals(tiles.hits.get, 0)
      }
    finally proxy.stop(0)
  }

  test("a per-port proxy: the proxy chosen for the request's own port is looked up, and used") {
    val asked = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    proxy.createContext("/", ex =>
      asked.add(ex.getRequestURI.toString)
      val b = "x".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
      ex.close())
    proxy.start()
    try
      withServers((_, _) => _ => (200, Nil)) { (tiles, _) =>
        val (g, guard, client, dns, _) = fixture(auto, tiles, proxy = Some(proxy.getAddress.getPort), proxyOnlyFor = Some(tiles.port))
        val url = s"http://tiles.test:${tiles.port}/t/14/1/2.png"
        val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
        assertEquals(get(client, TileStyle.withToken(url, t)), Right(200))
        assertEquals(dns.names.toArray.toSeq, Seq("proxy.test"))
        assertEquals(asked.toArray.toSeq, Seq(url))
      }
    finally proxy.stop(0)
  }

  test("a call waiting for a slow name lookup: the change says Stopping, the call ends without connecting") {
    val lookup = java.util.concurrent.CountDownLatch(1)
    val inLookup = java.util.concurrent.CountDownLatch(1)
    withServers((_, _) => _ => (200, Nil)) { (tiles, _) =>
      val (g, guard, client, _, _) = fixture(auto, tiles, dnsHook = _ => { inLookup.countDown(); lookup.await() })
      val t = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
      val result = java.util.concurrent.CompletableFuture.supplyAsync(() =>
        get(client, TileStyle.withToken(s"http://tiles.test:${tiles.port}/t/14/1/2.png", t)))
      inLookup.await()
      val change = g.changePaused(true)
      val done = change match
        case SendGate.Change.Stopping(1, d) => d
        case other => fail(s"not Stopping(1): $other")
      assert(!done.isDone)
      lookup.countDown()
      done.get(3, java.util.concurrent.TimeUnit.SECONDS)
      assert(result.get(3, java.util.concurrent.TimeUnit.SECONDS).isLeft)
      assertEquals(tiles.hits.get, 0)
    }
  }

  test("a call reading a slow answer: cancelled and ended before the change returns (Done)") {
    val started = java.util.concurrent.CountDownLatch(1)
    val other = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    // Answers the headers, then trickles the body without end.
    val t = Thread { () =>
      try
        val c = other.accept()
        c.getInputStream.read(new Array[Byte](4096))
        val o = c.getOutputStream
        o.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".getBytes)
        o.flush()
        started.countDown()
        while true do
          o.write(1)
          o.flush()
          Thread.sleep(50)
      catch case _: Exception => ()
    }
    t.setDaemon(true)
    t.start()
    try
      val g = gate(Policy.fromBlock(false).updated(Dest.CustomTiles, Rule(When.Auto, Level.Exact)), revokeWaitMs = 2_000)
      val template = s"http://tiles.test:${other.getLocalPort}/t/{z}/{x}/{y}.png"
      val guard = MapGuard(g, TokenTable(Map.empty), d => Option.when(d == Dest.CustomTiles)(UrlType.template(template)).flatten)
      val http = MapHttp(guard, system = _ => java.util.List.of(InetAddress.getLoopbackAddress))
      val dispatcher = okhttp3.Dispatcher()
      g.onRevoke(http.revoker(dispatcher))
      val client = http.client(dispatcher)
      val tok = guard.hold("snapshot", Object(), g.permit(Dest.CustomTiles, Mode.Auto).get)
      // As the map library does: an enqueued call that reads the body in its callback.
      val result = java.util.concurrent.CompletableFuture[Int]()
      client.newCall(Request.Builder().url(TileStyle.withToken(s"http://tiles.test:${other.getLocalPort}/t/14/1/2.png", tok)).build())
        .enqueue(new okhttp3.Callback:
          def onFailure(c: okhttp3.Call, e: java.io.IOException): Unit = result.complete(-1)
          def onResponse(c: okhttp3.Call, r: okhttp3.Response): Unit =
            try result.complete(r.body.bytes().length)
            catch case _: java.io.IOException => result.complete(-1)
            finally r.close())
      started.await()
      Thread.sleep(200)
      val t0 = System.nanoTime()
      assertEquals(g.changePaused(true), SendGate.Change.Done)
      assert((System.nanoTime() - t0) / 1_000_000 < 2_000)
      assertEquals(result.get(2, java.util.concurrent.TimeUnit.SECONDS), -1)
      assertEquals(dispatcher.runningCallsCount(), 0)
    finally other.close()
  }

