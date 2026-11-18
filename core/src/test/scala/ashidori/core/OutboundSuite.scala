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

  test("a change right after the check and registration: aborted before it connects, no request reaches the server") {
    withServer(ex => reply(ex, 200, "ok")) { s =>
      val (g, out, log) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      // The rules change (and the abort runs) between registration and the first I/O call.
      out.step = st => if st == "registered" then g.setPaused(true)
      intercept[Outbound.Blocked](out.request(p, s.local("/a"), "POST", Some("lat=35.681236".getBytes)))
      assertEquals(s.hits.get, 0)
      assertEquals(out.openCount, 0)
      assert(!log.exists(_.startsWith("send ")), log.toString)
    }
  }

  /** A pause from another thread right after the check of I/O call `at`
    * (the call then runs, under the read lock): the pause returns only after
    * that call ended, and nothing reaches the server after it returned.
    */
  private def pauseAfterFinalCheck(at: String): Unit =
    val arrivals = java.util.concurrent.ConcurrentLinkedQueue[Long]()
    withServer { ex =>
      arrivals.add(System.nanoTime())
      ex.getRequestBody.readAllBytes()
      reply(ex, 302, headers = Seq("Location" -> "/next"))
    } { s =>
      val (g, out, _) = setup()
      val p = g.permit(Dest.Data, Mode.Auto).get
      @volatile var returned = Long.MaxValue
      @volatile var ended = 0L
      val pauser = java.util.concurrent.atomic.AtomicReference[Thread]()
      out.step = st =>
        if st == s"done:$at" then ended = System.nanoTime()
        if st == s"checked:$at" && pauser.get == null then
          val t = Thread(() => { g.setPaused(true); returned = System.nanoTime() })
          pauser.set(t)
          t.start()
          // The pause is under way (permits invalid, the abort waiting for this call).
          while g.valid(p) do Thread.sleep(1)
          Thread.sleep(100)
      val e = intercept[java.io.IOException](out.request(p, s.local("/a"), "POST", Some("lat=35.681236".getBytes)))
      pauser.get.join(5_000)
      assert(e.isInstanceOf[Outbound.Blocked], e.toString)
      assert(returned != Long.MaxValue, "the pause returned")
      // The call under way ended before the pause returned; nothing was sent
      // after it (the redirect was not followed, no call after the change).
      // The call under way either did not start (aborted right after its
      // check) or ended before the pause returned; nothing was sent after it.
      assert(ended < returned, s"$at: the call ended after the pause returned")
      Thread.sleep(300)
      assert(arrivals.size <= 1, arrivals.toString)
      assertEquals(out.openCount, 0)
    }

  test("a pause right after the final check before connecting: it waits for the call, then nothing more is sent") {
    pauseAfterFinalCheck("connect")
  }

  test("a pause right after the final check before writing the request: it waits for the write, then nothing more is sent") {
    pauseAfterFinalCheck("write")
  }

  test("a pause right after the final check before reading the answer: the redirect is not followed") {
    pauseAfterFinalCheck("answer")
  }

  test("a slow disconnect does not hold back the others, and the change returns within its bound") {
    val g = gate()
    val out = Outbound(g, _ => Set("localhost"))
    // Three connections stuck in reading an answer that never comes.
    val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress)
    val accepted = java.util.concurrent.ConcurrentLinkedQueue[java.net.Socket]()
    val acceptor = Thread(() => try while true do accepted.add(ss.accept()) catch case _: Exception => ())
    acceptor.setDaemon(true)
    acceptor.start()
    try
      val p = g.permit(Dest.Data, Mode.Auto).get
      val errors = java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
      val ts = (1 to 3).map { i =>
        val t = Thread { () =>
          try out.request(p, s"http://localhost:${ss.getLocalPort}/$i", readMs = 60_000)
          catch case e: Throwable => errors.add(e)
          ()
        }
        t.start()
        t
      }
      while out.openCount < 3 || accepted.size < 3 do Thread.sleep(5)
      Thread.sleep(200)
      val t0 = System.nanoTime()
      g.setPaused(true)
      val ms = (System.nanoTime() - t0) / 1_000_000
      ts.foreach(_.join(5_000))
      assert(ms < SendGate.RevokeWaitMs + 1_000, s"$ms ms")
      assertEquals(errors.size, 3)
      errors.forEach(e => assert(e.isInstanceOf[Outbound.Blocked], e.toString))
    finally ss.close()
  }

  test("only the URLs a destination lists, where it lists them") {
    withServer(ex => reply(ex, 200, "ok")) { s =>
      val g = gate()
      val only = UrlType(Seq(UrlKind("style", s"http://localhost:${s.port}/styles/[a-z]+".r, tile = false)))
      val out = Outbound(g, _ => Set("localhost"), urls = d => Option.when(d == Dest.Data)(only))
      val p = g.permit(Dest.Data, Mode.Auto).get
      assertEquals(out.small(p, s.local("/styles/liberty")), Some("ok"))
      intercept[Outbound.Refused](out.request(p, s.local("/elsewhere")))
      assertEquals(s.hits.get, 1)
    }
  }

  test("a call stuck past the change's deadline: the change says Stopping, completes once it ended, nothing sent") {
    val arrivals = AtomicInteger()
    withServer { ex =>
      arrivals.incrementAndGet()
      reply(ex, 200, "ok")
    } { s =>
      val g = gate(revokeWaitMs = 200)
      val out = Outbound(g, d => if d == Dest.Data then Set("localhost") else Set.empty)
      val p = g.permit(Dest.Data, Mode.Auto).get
      val stuck = java.util.concurrent.CountDownLatch(1)
      @volatile var change: SendGate.Change = null
      @volatile var returnedAt = 0L
      @volatile var releasedAt = 0L
      out.step = st =>
        if st == "checked:write" then
          // The pause comes while this call is stuck (as in a socket that blocks) for 800 ms.
          val t = Thread(() => { change = g.changePaused(true); returnedAt = System.nanoTime() })
          t.start()
          stuck.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
          releasedAt = System.nanoTime()
      val e = intercept[java.io.IOException](out.request(p, s.local("/a"), "POST", Some("lat=35.681236".getBytes)))
      assert(e.isInstanceOf[Outbound.Blocked], e.toString)
      // The change returned at its deadline, before the call was released, and said so.
      assert(returnedAt > 0 && returnedAt < releasedAt)
      change match
        case SendGate.Change.Stopping(n, done) =>
          assertEquals(n, 1)
          done.get(2, java.util.concurrent.TimeUnit.SECONDS)
        case other => fail(s"not Stopping: $other")
      // The stuck call did not go on after its check: no request reached the server.
      Thread.sleep(200)
      assertEquals(arrivals.get, 0)
      assertEquals(out.openCount, 0)
    }
  }

  test("a change with nothing stuck is Done within the deadline") {
    val g = gate(revokeWaitMs = 200)
    val out = Outbound(g, _ => Set("localhost"))
    assertEquals(g.changePaused(true), SendGate.Change.Done)
    assertEquals(g.changePaused(true), SendGate.Change.Unchanged)
    assertEquals(out.openCount, 0)
  }

