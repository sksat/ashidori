package ashidori.core

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import OutboundFixtures.*

/** [[Outbound]] against local HTTP servers: "localhost" is the
  * destination's host, "127.0.0.1" another one.
  */
class OutboundSuite extends munit.FunSuite:
  private final class Server(handle: HttpExchange => Unit):
    val hits = AtomicInteger()
    private val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", ex => { hits.incrementAndGet(); try handle(ex) finally ex.close() })
    s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    s.start()
    def port: Int = s.getAddress.getPort
    def local(path: String) = s"http://localhost:$port$path"
    def other(path: String) = s"http://127.0.0.1:$port$path"
    def stop(): Unit = s.stop(0)

  private def reply(ex: HttpExchange, code: Int, body: String = "", headers: Seq[(String, String)] = Nil): Unit =
    headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    val b = body.getBytes("UTF-8")
    ex.sendResponseHeaders(code, if b.isEmpty then -1 else b.length)
    if b.nonEmpty then ex.getResponseBody.write(b)

  private def withServer(handle: HttpExchange => Unit)(f: Server => Unit): Unit =
    val s = Server(handle)
    try f(s) finally s.stop()

  private def setup() =
    val g = gate()
    val log = scala.collection.mutable.ArrayBuffer.empty[String]
    val out = Outbound(g, d => if d == Dest.Data then Set("localhost") else Set.empty, l => log.synchronized(log += l))
    (g, out, log)

  test("a request goes out only with a valid permit and to one of the destination's hosts") {
    withServer(ex => reply(ex, 200, "ok")) { s =>
      val (g, out, _) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      assertEquals(out.small(p, s.local("/a")), Some("ok"))
      intercept[Outbound.Refused](out.request(p, s.other("/a")))
      g.setRule(Dest.Data, Rule(When.OnAction, Level.Whole))
      intercept[Outbound.Blocked](out.request(p, s.local("/a")))
      assertEquals(s.hits.get, 1)
      assertEquals(out.openCount, 0)
    }
  }

  test("a redirect to another host is not followed; one to the destination's host is, checked again") {
    withServer { ex =>
      ex.getRequestURI.getPath match
        case "/away" => reply(ex, 302, headers = Seq("Location" -> s"http://127.0.0.1:${ex.getLocalAddress.getPort}/file"))
        case "/here" => reply(ex, 302, headers = Seq("Location" -> "/file"))
        case _ => reply(ex, 200, "data")
    } { s =>
      val (g, out, log) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      intercept[Outbound.Refused](out.request(p, s.local("/away")))
      assertEquals(s.hits.get, 1)
      assertEquals(out.small(p, s.local("/here")), Some("data"))
      assertEquals(s.hits.get, 3)
      assert(log.exists(_.contains("hop=1")))
    }
  }

  test("a permit that ends while the server answers stops the redirect and the answer") {
    var gateRef: SendGate = null
    withServer { ex =>
      ex.getRequestURI.getPath match
        case "/move" =>
          gateRef.setPaused(true)
          reply(ex, 302, headers = Seq("Location" -> "/file"))
        case _ => reply(ex, 200, "data")
    } { s =>
      val (g, out, _) = setup()
      gateRef = g
      val p = g.permit(Dest.Data, Mode.Auto).get
      intercept[Outbound.Blocked](out.request(p, s.local("/move")))
      assertEquals(s.hits.get, 1)
      assertEquals(out.openCount, 0)
    }
  }

  test("the pause cuts a connection being read, and the body is not read further") {
    val sent = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    withServer { ex =>
      ex.sendResponseHeaders(200, 0)
      val o = ex.getResponseBody
      o.write(Array.fill[Byte](1024)(1))
      o.flush()
      sent.countDown()
      release.await(5, java.util.concurrent.TimeUnit.SECONDS)
      try o.write(Array.fill[Byte](1024)(2)) catch case _: Exception => ()
    } { s =>
      val (g, out, log) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      val r = out.request(p, s.local("/slow"))
      val in = r.body
      assertEquals(in.read(), 1)
      sent.await()
      g.setPaused(true)
      assertEquals(out.openCount, 0)
      val e = intercept[java.io.IOException](in.readAllBytes())
      assert(e.isInstanceOf[Outbound.Blocked] || e.isInstanceOf[java.net.SocketException], e.toString)
      release.countDown()
      assert(log.exists(_.startsWith("cut dest=data")), log.toString)
    }
  }

  test("each retry of a resumable download takes the checked path: none after the rule changed") {
    withServer(ex => reply(ex, 416)) { s =>
      val (g, out, _) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      val base = out.rangeHttp(p, "ashidori/test")
      // The rule changes after the first answer (416: the download asks again from 0).
      val http: RangeHttp = (url, from) =>
        val r = base.get(url, from)
        new RangeHttp.Response:
          def code: Int = r.code
          def header(name: String): Option[String] = r.header(name)
          def body: java.io.InputStream = r.body
          def close(): Unit =
            r.close()
            g.setRule(Dest.Data, Rule(When.Never, Level.Whole))
      val part = java.io.File.createTempFile("outbound", ".part")
      try
        val e = intercept[java.io.IOException](ResumableDownload.run(http, s.local("/f"), part, 100))
        assert(e.isInstanceOf[Outbound.Blocked], e.toString)
        assertEquals(s.hits.get, 1)
      finally part.delete()
    }
  }
