package ashidori.core

class WriteSafetySuite extends munit.FunSuite:
  test("backlog: bounded per class; raw over the cap is spilled, others lost; all counted") {
    val b = WriteBacklog(maxRaw = 100, maxOther = 10, noteEveryMs = 1000, slowMs = 50)
    val r1 = b.offer(60, raw = true, "raw", now = 0L)
    assert(r1.isDefined)
    assertEquals(b.offer(60, raw = true, "raw", now = 1L), None)
    b.spill(60)
    val o1 = b.offer(8, raw = false, "fix", now = 2L)
    assert(o1.isDefined)
    assertEquals(b.offer(3, raw = false, "battery", now = 3L), None)
    assertEquals(b.pending, 68L)
    assertEquals((b.totalLost, b.totalSpilled), (3L, 60L))
    assertEquals(b.note(0L), Some("位置・動きなど 3 行を保存できず・生データ 60 行をファイルに退避・待ち 68 行"))
    b.done(r1.get, 60, raw = true, now = 10L)
    assertEquals(b.note(5000L), None)
    val r2 = b.offer(90, raw = true, "raw", now = 5400L).get
    b.done(r2, 90, raw = true, now = 5470L)
    assertEquals(b.note(5500L), Some("保存待ち最大 0 秒・待ち 8 行"))
    b.done(o1.get, 8, raw = false, now = 5600L, lost = true)
    assertEquals(b.totalLost, 11L)
    assertEquals(b.note(5600L), None) // too soon
    assertEquals(b.note(6600L), Some("位置・動きなど 8 行を保存できず・保存待ち最大 5 秒・待ち 0 行"))
    assertEquals(b.pending, 0L)
  }

  test("per kind: a failed position batch stays unsaved however many battery rows are saved") {
    val b = WriteBacklog()
    val f = b.offer(5, raw = false, "fix", now = 100L).get
    b.done(f, 5, raw = false, now = 200L, lost = true)
    for t <- 300L to 900L by 100L do
      val bt = b.offer(1, raw = false, "battery", now = t).get
      b.done(bt, 1, raw = false, now = t + 1)
    assertEquals(b.unsavedSince, Map("fix" -> 100L))
    assertEquals(b.oldestUnsaved, Some("fix" -> 100L))
    assertEquals(b.lastSaved("battery"), Some(901L))
    assertEquals(b.lastSaved("fix"), None)
    // A waiting batch counts from when it was queued.
    val g = b.offer(5, raw = false, "fix", now = 1000L).get
    assertEquals(b.unsavedSince("fix"), 100L)
    // Saved again: no longer unsaved.
    b.done(g, 5, raw = false, now = 1100L)
    assertEquals(b.unsavedSince, Map.empty[String, Long])
    assertEquals(b.lastSaved("fix"), Some(1100L))
    // Lost at the cap: unsaved from then.
    val small = WriteBacklog(maxOther = 1)
    assertEquals(small.offer(2, raw = false, "motion", now = 7L), None)
    assertEquals(small.unsavedSince, Map("motion" -> 7L))
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

  test("stall alert: a battery write does not clear it while positions keep failing") {
    val b = WriteBacklog()
    val m = WriteStall.Monitor()
    def check(now: Long) = m.update(noCommit = false, WriteStall.queuedTooLong(b.oldestUnsaved.map(_._2), now))
    val f = b.offer(5, raw = false, "位置", now = 0L).get
    b.done(f, 5, raw = false, now = 10L, lost = true)
    val t = WriteStall.OldestMs + 1
    val bt = b.offer(1, raw = false, "電池", now = t - 5).get
    b.done(bt, 1, raw = false, now = t - 4)
    assertEquals(check(t), Some(true))
    // Battery saved again, positions still failing: the alert stays.
    val bt2 = b.offer(1, raw = false, "電池", now = t + 10).get
    b.done(bt2, 1, raw = false, now = t + 11)
    assertEquals(check(t + 20), None)
    assert(m.reported)
    // Positions saved again: over.
    val f2 = b.offer(5, raw = false, "位置", now = t + 30).get
    b.done(f2, 5, raw = false, now = t + 31)
    assertEquals(check(t + 40), Some(false))
    assertEquals(check(t + 50), None)
  }

  test("stall alert across a restart: cleared once each kind it named is saved again, not before") {
    // Raised before the restart, for positions and raw lines.
    val m = WriteStall.Monitor(restored = Some(Set("fixes", "raw_lines")))
    assert(m.reported)
    // After the restart nothing is stalled, but only battery rows were saved.
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("battery")), None)
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("battery", "fixes")), None)
    assert(m.reported)
    // Every kind it named saved again: over.
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("battery", "fixes", "raw_lines")), Some(false))
    assertEquals(m.kinds, Set.empty[String])
    // Raised again in this process: the kinds unsaved now are kept.
    assertEquals(m.update(noCommit = false, unsavedTooLong = true, unsaved = Set("動き"), saved = Set("位置")), Some(true))
    assertEquals(m.kinds, Set("動き"))
    // In the same process it clears as before (not waiting for a save of each kind).
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("位置")), Some(false))
  }

  test("stall alert found only by its notification (0.21.0): cleared after a save, while nothing is stalled") {
    val m = WriteStall.Monitor(restored = Some(Set.empty))
    assertEquals(m.update(noCommit = false, unsavedTooLong = false), None)
    // Still stalled after a save: kept.
    assertEquals(m.update(noCommit = true, unsavedTooLong = false, saved = Set("電池")), None)
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("電池")), Some(false))
    // Nothing restored: as before.
    val fresh = WriteStall.Monitor()
    assert(!fresh.reported)
    assertEquals(fresh.update(noCommit = false, unsavedTooLong = false), None)
  }

  test("stall alert: a change the app could not carry out (save or cancel failed) is tried again") {
    val m = WriteStall.Monitor(restored = Some(Set("位置")))
    // Saving works again, but cancelling the notification fails.
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("位置")), Some(false))
    m.failed()
    assert(m.reported)
    assertEquals(m.kinds, Set("位置"))
    // Next check: cleared again, until it works.
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("位置")), Some(false))
    assertEquals(m.update(noCommit = false, unsavedTooLong = false, saved = Set("位置")), None)
    // Raising it: keeping it on disk failed, so it is raised again.
    assertEquals(m.update(noCommit = true, unsavedTooLong = false, unsaved = Set("動き")), Some(true))
    m.failed()
    assert(!m.reported)
    assertEquals(m.update(noCommit = true, unsavedTooLong = false, unsaved = Set("動き")), Some(true))
    assertEquals(m.update(noCommit = true, unsavedTooLong = false, unsaved = Set("動き")), None)
    // failed() with nothing just returned changes nothing.
    m.failed()
    assert(m.reported)
  }

  test("write kinds: stable keys apart from the labels shown") {
    assertEquals(WriteKind.values.map(_.key).distinct.size, WriteKind.values.size)
    assertEquals(WriteKind.RawLines.key, "raw_lines")
    assertEquals(WriteKind.RawLines.label, "生データ")
    for k <- WriteKind.values do assertEquals(WriteKind.fromKey(k.key), Some(k))
    // Counted by key, shown by label: renaming a label keeps what was counted.
    val b = WriteBacklog()
    val id = b.offer(3, raw = true, WriteKind.RawLines.key, now = 0L).get
    b.done(id, 3, raw = true, now = 1L)
    assertEquals(b.savedKinds, Set("raw_lines"))
    assertEquals(b.savedKinds.map(WriteKind.label), Set("生データ"))
    assertEquals(WriteKind.label("unknown"), "unknown")
  }

