package ashidori.core

class WriteSafetySuite extends munit.FunSuite:
  test("backlog: bounded per class; raw over the cap is spilled, others lost; all counted") {
    val b = WriteBacklog(maxRaw = 100, maxOther = 10, noteEveryMs = 1000, slowMs = 50)
    val r1 = b.offer(60, raw = true, now = 0L)
    assert(r1.isDefined)
    assertEquals(b.offer(60, raw = true, now = 1L), None)
    b.spill(60)
    val o1 = b.offer(8, raw = false, now = 2L)
    assert(o1.isDefined)
    assertEquals(b.offer(3, raw = false, now = 3L), None)
    assertEquals(b.pending, 68L)
    assertEquals(b.oldestQueued, Some(0L))
    assertEquals((b.totalLost, b.totalSpilled), (3L, 60L))
    assertEquals(b.note(0L), Some("位置・動きなど 3 行を保存できず・詳細ログ 60 行をファイルに退避・待ち 68 行"))
    // The oldest leaves: the next oldest is the position batch.
    b.done(r1.get, 60, raw = true, now = 10L)
    assertEquals(b.oldestQueued, Some(2L))
    assertEquals(b.note(5000L), None)
    val r2 = b.offer(90, raw = true, now = 5400L).get
    b.done(r2, 90, raw = true, now = 5470L)
    assertEquals(b.note(5500L), Some("保存待ち最大 0 秒・待ち 8 行"))
    b.done(o1.get, 8, raw = false, now = 5600L, lost = true)
    assertEquals(b.totalLost, 11L)
    assertEquals(b.note(5600L), None) // too soon
    assertEquals(b.note(6600L), Some("位置・動きなど 8 行を保存できず・保存待ち最大 5 秒・待ち 0 行"))
    assertEquals(b.pending, 0L)
    assertEquals(b.oldestQueued, None)
  }

  test("stall: a batch waiting too long is a stall whatever else was saved") {
    assert(!WriteStall.queuedTooLong(None, 10 * 60_000L))
    assert(!WriteStall.queuedTooLong(Some(0L), WriteStall.OldestMs))
    assert(WriteStall.queuedTooLong(Some(0L), WriteStall.OldestMs + 1))
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
