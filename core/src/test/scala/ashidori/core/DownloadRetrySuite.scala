package ashidori.core

import DownloadCause.*
import DownloadRetry.Next

class DownloadRetrySuite extends munit.FunSuite:
  private val H = 60L * 60 * 1000
  private val now = 1_800_000_000_000L

  test("causes from transfer errors keep the HTTP status") {
    assertEquals(DownloadCause.of(ResumableDownload.Refused("HTTP 404", Some(404))), Http(404))
    assertEquals(DownloadCause.of(ResumableDownload.HttpFailure("HTTP 503", 503)), Http(503))
    assertEquals(DownloadCause.of(java.net.UnknownHostException("github.com")), Network)
    assertEquals(DownloadCause.of(java.io.IOException("incomplete")), Network)
    // Refused without a status: the server sent more or other bytes than pinned.
    assertEquals(DownloadCause.of(ResumableDownload.Refused("longer than expected")), Mismatch)
  }

  test("a download answered with a status carries it") {
    def fail(status: Int): Throwable =
      val http = new RangeHttp:
        def get(url: String, from: Long) = new RangeHttp.Response:
          def code = status
          def header(name: String) = None
          def body = java.io.InputStream.nullInputStream()
          def close() = ()
      val part = java.io.File(java.nio.file.Files.createTempDirectory("dl").toFile, "a.part")
      intercept[java.io.IOException](ResumableDownload.run(http, "u", part, 10))
    assertEquals(DownloadCause.of(fail(404)), Http(404))
    assertEquals(DownloadCause.of(fail(403)), Http(403))
    assertEquals(DownloadCause.of(fail(500)), Http(500))
    assertEquals(DownloadCause.of(fail(429)), Http(429))
    assert(fail(404).isInstanceOf[ResumableDownload.Refused])
    assert(fail(503).isInstanceOf[ResumableDownload.HttpFailure])
  }

  test("4xx but 408 and 429, wrong bytes and no storage are permanent; the rest is transient") {
    assert(DownloadRetry.permanent(Http(404)))
    assert(DownloadRetry.permanent(Http(410)))
    assert(DownloadRetry.permanent(Http(403)))
    assert(!DownloadRetry.permanent(Http(408)))
    assert(!DownloadRetry.permanent(Http(429)))
    assert(!DownloadRetry.permanent(Http(500)))
    assert(!DownloadRetry.permanent(Http(503)))
    assert(!DownloadRetry.permanent(Network))
    assert(DownloadRetry.permanent(Mismatch))
    assert(DownloadRetry.permanent(Storage))
  }

  test("labels for one line") {
    assertEquals(DownloadCause.label(Http(404)), "配信元にありません・HTTP 404")
    assertEquals(DownloadCause.label(Http(403)), "配信元が拒否しました・HTTP 403")
    assertEquals(DownloadCause.label(Http(503)), "配信元が応答しません・HTTP 503")
    assertEquals(DownloadCause.label(Network), "接続できません")
    assertEquals(DownloadCause.shortLabel(Http(404)), "HTTP 404")
    assertEquals(DownloadCause.shortLabel(Storage), "空き容量不足")
  }

  test("a 404 ends the run at once and waits hours, growing with each run given up") {
    val (f1, n1) = DownloadRetry.afterFailure("v1", now, Http(404), Seq("GitHub" -> Http(404)), 0, None)
    assertEquals(n1, Next.Later(now + 6 * H))
    assertEquals(f1, DownloadFailure("v1", now, Http(404), Seq("GitHub" -> Http(404)), 1, Some(now + 6 * H)))
    val (f2, n2) = DownloadRetry.afterFailure("v1", now, Http(404), Seq.empty, 0, Some(f1))
    assertEquals(n2, Next.Later(now + 12 * H))
    assertEquals(f2.giveUps, 2)
    val (_, n3) = DownloadRetry.afterFailure("v1", now, Http(404), Seq.empty, 0, Some(f2))
    assertEquals(n3, Next.Later(now + 24 * H))
    // Capped at a day.
    val (_, n9) = DownloadRetry.afterFailure("v1", now, Http(404), Seq.empty, 0, Some(f2.copy(giveUps = 40)))
    assertEquals(n9, Next.Later(now + 24 * H))
  }

  test("a transient failure is retried within the run, then given up for an hour") {
    val (f0, n0) = DownloadRetry.afterFailure("v1", now, Network, Seq.empty, 0, None)
    assertEquals(n0, Next.Backoff)
    assertEquals(f0.retryAt, None)
    assertEquals(f0.giveUps, 0)
    assertEquals(DownloadRetry.afterFailure("v1", now, Http(503), Seq.empty, DownloadRetry.MaxAttempts - 2, None)._2,
      Next.Backoff)
    val (f, n) = DownloadRetry.afterFailure("v1", now, Http(503), Seq.empty, DownloadRetry.MaxAttempts - 1, Some(f0))
    assertEquals(n, Next.Later(now + 1 * H))
    assertEquals(f.giveUps, 1)
  }

  test("a failure of another version does not lengthen the cool-down") {
    val old = DownloadFailure("v0", now - H, Http(404), giveUps = 5, retryAt = Some(now + 20 * H))
    assertEquals(DownloadRetry.afterFailure("v1", now, Http(404), Seq.empty, 0, Some(old))._2, Next.Later(now + 6 * H))
    assertEquals(DownloadRetry.current(Some(old), "v1"), None)
    assertEquals(DownloadRetry.autoStartDelay(Some(old), "v1", now), 0L)
  }

  test("app start waits out the cool-down; a user retry starts now and resets it") {
    val f = DownloadFailure("v1", now, Http(404), giveUps = 3, retryAt = Some(now + 6 * H))
    assertEquals(DownloadRetry.autoStartDelay(None, "v1", now), 0L)
    assertEquals(DownloadRetry.autoStartDelay(Some(f), "v1", now), 6 * H)
    assertEquals(DownloadRetry.autoStartDelay(Some(f), "v1", now + 7 * H), 0L)
    // Still being retried within a run: no extra wait.
    assertEquals(DownloadRetry.autoStartDelay(Some(f.copy(retryAt = None)), "v1", now), 0L)
    val r = DownloadRetry.userRetry(Some(f))
    assertEquals(r, Some(f.copy(giveUps = 0, retryAt = None)))
    assertEquals(DownloadRetry.autoStartDelay(r, "v1", now), 0L)
    // The reason stays shown until it succeeds.
    assertEquals(r.map(_.cause), Some(Http(404)))
  }

  test("the stored record round-trips; anything else reads as none") {
    val f = DownloadFailure("20261013", now, Http(404), Seq("GitHub" -> Http(404), "サーバ" -> Network), 2, Some(now + H))
    assertEquals(DownloadFailure.decode(DownloadFailure.encode(f)), Some(f))
    val g = f.copy(cause = Storage, sources = Seq.empty, retryAt = None)
    assertEquals(DownloadFailure.decode(DownloadFailure.encode(g)), Some(g))
    assertEquals(DownloadFailure.decode(""), None)
    assertEquals(DownloadFailure.decode("接続できませんでした"), None)
    assertEquals(DownloadFailure.decode("not-published"), None)
    assertEquals(DownloadFailure.decode("""{"v":1,"version":"../x","at":1,"cause":"network"}"""), None)
    assertEquals(DownloadFailure.decode("""{"v":1,"version":"v1","at":1,"cause":"bogus"}"""), None)
    assertEquals(DownloadFailure.decode("""{"v":2,"version":"v1","at":1,"cause":"network"}"""), None)
  }
