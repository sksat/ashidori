package ashidori.core

class WriteSafetySuite extends munit.FunSuite:
  test("backlog: bounded per class, drops counted and noted, at most every noteEveryMs") {
    val b = WriteBacklog(maxRaw = 100, maxOther = 10, noteEveryMs = 1000, slowMs = 50)
    assert(b.offer(60, raw = true))
    assert(!b.offer(60, raw = true))
    assert(b.offer(8, raw = false))
    assert(!b.offer(3, raw = false))
    assertEquals(b.pending, 68L)
    assertEquals(b.note(0L), Some("位置・動きなど 3 行を保存できず・詳細ログ 60 行を保存できず・待ち 68 行"))
    // Nothing new: nothing to say.
    b.done(60, raw = true, waitedMs = 10)
    assertEquals(b.note(5000L), None)
    // Space again once the queue drained.
    assert(b.offer(90, raw = true))
    b.done(90, raw = true, waitedMs = 70)
    assertEquals(b.note(5500L), Some("保存待ち最大 0 秒・待ち 8 行"))
    b.done(8, raw = false, waitedMs = 0, lost = true)
    assertEquals(b.note(5600L), None) // too soon
    assertEquals(b.note(6600L), Some("位置・動きなど 8 行を保存できず・待ち 0 行"))
    assertEquals(b.pending, 0L)
  }

  test("stall: at least 15 minutes awake, more for sparse plans") {
    assertEquals(WriteStall.thresholdMs(1_000L, 0L), 15 * 60_000L)
    assertEquals(WriteStall.thresholdMs(10 * 60_000L, 0L), 25 * 60_000L)
    assertEquals(WriteStall.thresholdMs(60_000L, 20 * 60_000L), 45 * 60_000L)
    assert(!WriteStall.stalled(0L, 15 * 60_000L, 15 * 60_000L))
    assert(WriteStall.stalled(0L, 15 * 60_000L + 1, 15 * 60_000L))
  }

  test("crash loop: 3 crashes within 6 hours after the update") {
    val h = 3_600_000L
    val now = 100 * h
    assert(CrashLoop.tripped(Seq(now - 5 * h, now - 2 * h, now - h), now, since = 0L))
    assert(!CrashLoop.tripped(Seq(now - 7 * h, now - 2 * h, now - h), now, since = 0L))
    // Crashes of the build before the update do not count.
    assert(!CrashLoop.tripped(Seq(now - 5 * h, now - 2 * h, now - h), now, since = now - 3 * h))
    assert(!CrashLoop.tripped(Nil, now, 0L))
  }
