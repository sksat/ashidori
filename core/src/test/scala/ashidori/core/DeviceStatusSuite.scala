package ashidori.core

import java.nio.charset.StandardCharsets.UTF_8
import ashidori.core.sync.*

class DeviceStatusSuite extends munit.FunSuite:
  private val status = DeviceStatus(
    t = 1_760_000_000_000L, batteryPct = 82, charging = true, recording = true, mode = "balanced",
    since = 1_759_000_000_000L, lastFix = Some(StatusFix(1_759_999_000_000L, 35.6812345678, 139.7671234567, 12.34)),
    versionName = "0.14.0", versionCode = 14, model = "Pixel 8a"
  )

  test("record survives seal, gzip and decode under the source device's AAD") {
    val key = BlobKeys.of(Crypto.newMasterKey())
    val aad = Crypto.aad("devA", DeviceStatus.Stream, 3)
    val blob = Crypto.seal(key, aad, Crypto.gzip(DeviceStatus.encode(status)))
    val back = DeviceStatus.decode(Crypto.gunzip(Crypto.open(key, aad, blob))).get
    assertEquals(back.copy(lastFix = None), status.copy(lastFix = None))
    val f = back.lastFix.get
    assertEqualsDouble(f.lat, 35.681235, 1e-9)
    assertEqualsDouble(f.acc, 12.3, 1e-9)
    // Another device's slot does not open.
    intercept[Exception](Crypto.open(key, Crypto.aad("devB", DeviceStatus.Stream, 3), blob))
  }

  test("record stays small") {
    assert(DeviceStatus.encode(status).length < 300, String(DeviceStatus.encode(status), UTF_8))
  }

  test("missing fields and a null position are tolerated") {
    val s = DeviceStatus.fromJson(ujson.read("""{"t":5,"lastFix":null,"extra":1}"""))
    assertEquals(s.t, 5L)
    assertEquals(s.batteryPct, -1)
    assertEquals(s.lastFix, None)
    assert(!s.recording)
  }

  /** A listing row; the size and hash are those of `blob`. */
  private def meta(cursor: Long, dev: String, stream: String, seq: Long, blob: Array[Byte] = Array[Byte](1, 2, 3)) =
    BatchMeta(cursor, dev, stream, seq, blob.length, Crypto.sha256Hex(blob))

  test("newest status per other device, only when newer than known") {
    val items = Seq(
      meta(1, "me", "status", 9),
      meta(2, "a", "status", 0),
      meta(3, "a", "fixes", 7),
      meta(4, "a", "status", 1),
      meta(5, "b", "status", 4),
      meta(6, "c", "status", 2)
    )
    assertEquals(BatchMeta.newestStatus(items, "me", Map("b" -> 4L, "c" -> 1L)), Map("a" -> 1L, "c" -> 2L))
  }

  test("listing caps: decompressed size and line count abort") {
    val bomb = Crypto.gzip(new Array[Byte](1 << 20))
    intercept[LimitExceeded](Crypto.gunzip(bomb, DeviceStatus.MaxPlain))
    assertEquals(Crypto.gunzip(bomb).length, 1 << 20)
    val many = (DeviceStatus.encode(status) ++ Array.fill(DeviceStatus.MaxLines)(DeviceStatus.encode(status)).flatten)
    intercept[LimitExceeded](DeviceStatus.decode(many))
    val hash = "0" * 64
    intercept[IllegalArgumentException](BatchMeta.fromJson(ujson.read(
      s"""{"cursor":1,"deviceId":"../x","stream":"status","seq":0,"size":40,"sha256":"$hash"}""")))
  }

  test("a listing row keeps size and SHA-256; a row without them or with a malformed hash is refused") {
    val hash = "ab" * 32
    val b = BatchMeta.fromJson(ujson.read(s"""{"cursor":1,"deviceId":"a","stream":"status","seq":2,"size":40,"sha256":"$hash","createdAt":0}"""))
    assertEquals(b, BatchMeta(1, "a", "status", 2, 40, hash))
    intercept[Exception](BatchMeta.fromJson(ujson.read("""{"cursor":1,"deviceId":"a","stream":"status","seq":2}""")))
    intercept[IllegalArgumentException](BatchMeta.fromJson(ujson.read(
      """{"cursor":1,"deviceId":"a","stream":"status","seq":2,"size":40,"sha256":"xyz"}""")))
    intercept[IllegalArgumentException](BatchMeta.fromJson(ujson.read(
      s"""{"cursor":1,"deviceId":"a","stream":"status","seq":2,"size":-1,"sha256":"$hash"}""")))
  }

  test("a fetched blob must match the listed size and SHA-256 before it is opened") {
    val key = BlobKeys.of(Crypto.newMasterKey())
    val aad = Crypto.aad("a", DeviceStatus.Stream, 5)
    val plain = Crypto.gzip(DeviceStatus.encode(status))
    val listedBlob = Crypto.seal(key, aad, plain)
    // Another valid blob of the same size for the same slot and key (sealed
    // again, with a fresh nonce), e.g. from a lost race.
    val other = Crypto.seal(key, aad, plain)
    assert(!other.sameElements(listedBlob))
    assertEquals(other.length, listedBlob.length)
    val st = StatusSyncState.empty.listed(Seq(meta(10, "a", "status", 5, listedBlob)), "me")
    val c = st.targets("a")
    assertEquals(c.mismatch(listedBlob), None)
    // Both open under the slot's AAD, so only the listing check tells them apart.
    Crypto.open(key, aad, other)
    assert(c.mismatch(other).exists(_.contains("SHA-256")))
    assert(c.mismatch(listedBlob.dropRight(1)).exists(_.contains("size")))
    // A mismatch is unresolved, like any unreadable blob.
    val failed = st.failed("a", c, c.mismatch(other).get)
    assertEquals(failed.problem("a").map(_.seq), Some(5L))
    assertEquals(failed.authenticated.get("a"), None)
  }

  test("an unreadable status is retried a few times, kept, and cleared by a newer readable one") {
    var st = StatusSyncState.empty.listed(Seq(meta(10, "a", "status", 5), meta(11, "me", "status", 9)), "me")
    assertEquals(st.cursor, 11L)
    assertEquals(st.targets.view.mapValues(_.seq).toMap, Map("a" -> 5L))
    for _ <- 1 to StatusSyncState.MaxAttempts do st = st.failed("a", st.targets("a"), "bad tag")
    // Given up on for now, but still shown and not counted as read.
    assertEquals(st.targets, Map.empty[String, StatusCandidate])
    assertEquals(st.problem("a").map(_.seq), Some(5L))
    assertEquals(st.authenticated.get("a"), None)
    st = st.listed(Seq(meta(12, "a", "status", 6)), "me")
    assertEquals(st.targets.view.mapValues(_.seq).toMap, Map("a" -> 6L))
    st = st.succeeded("a", 6)
    assertEquals(st.problem("a"), None)
    assertEquals(st.targets, Map.empty[String, StatusCandidate])
    assertEquals(StatusSyncState.fromJson(ujson.read(ujson.write(StatusSyncState.toJson(st)))), st)
  }

  test("the cursor only moves to listed rows; an older seq is kept as a further candidate") {
    val st = StatusSyncState.empty.listed(Seq(meta(3, "a", "status", 7)), "me").listed(Seq(meta(4, "a", "status", 2)), "me")
    assertEquals(st.cursor, 4L)
    assertEquals(st.candidates("a").map(_.seq), Vector(7L, 2L))
    assertEquals(st.targets("a").seq, 7L)
    assertEquals(st.listed(Nil, "me").cursor, 4L)
  }

  /** `st` with candidate `seq` of device `dev` failed MaxAttempts times. */
  private def exhaust(st0: StatusSyncState, dev: String, seq: Long): StatusSyncState =
    var st = st0
    for _ <- 1 to StatusSyncState.MaxAttempts do
      st = st.failed(dev, st.candidates(dev).find(_.seq == seq).get, "bad tag")
    st

  test("a made-up newest status does not hide later genuine ones (review #7)") {
    var st = StatusSyncState.empty.listed(Seq(meta(1, "a", "status", 0)), "me").succeeded("a", 0)
    // The server lists a status with a huge seq that does not read.
    st = st.listed(Seq(meta(2, "a", "status", 9, Array[Byte](9))), "me")
    assertEquals(st.targets("a").seq, 9L)
    st = exhaust(st, "a", 9)
    assertEquals(st.targets.get("a"), None)
    assertEquals(st.problem("a").map(_.seq), Some(9L))
    // Nothing is left to try: a rescan is wanted.
    assert(st.rescanWanted)
    // A genuine, older seq listed later is still fetched.
    st = st.listed(Seq(meta(3, "a", "status", 1)), "me")
    assertEquals(st.targets("a").seq, 1L)
    st = st.succeeded("a", 1)
    assertEquals(st.authenticated("a"), 1L)
    // The unreadable seq 9 stays shown: it is never skipped silently.
    assertEquals(st.problem("a").map(_.seq), Some(9L))
    assertEquals(st.targets.get("a"), None)
    // Its failure count and retry time survive storage, so it is not retried before then.
    assertEquals(StatusSyncState.fromJson(ujson.read(ujson.write(StatusSyncState.toJson(st)))), st)
  }

  private val hour = 60L * 60 * 1000

  test("a genuine status that failed a few times is retried after a delay, from its own budget (R5)") {
    val t0 = 1_000 * hour
    var st = StatusSyncState.empty.listed(Seq(meta(1, "a", "status", 5)), "me")
    // The server answers 404 three times: exhausted, no normal target left.
    for i <- 1 to StatusSyncState.MaxAttempts do st = st.failed("a", st.targets("a"), "not found", t0 + i)
    assertEquals(st.targets.get("a"), None)
    val t3 = t0 + StatusSyncState.MaxAttempts
    assertEquals(st.retries(t3 + hour - 1), Map.empty[String, StatusCandidate])
    // An hour later it is tried again; the server serves it now.
    assertEquals(st.retries(t3 + hour).get("a").map(_.seq), Some(5L))
    st = st.succeeded("a", 5)
    assertEquals(st.authenticated("a"), 5L)
    assertEquals(st.problem("a"), None)
    assertEquals(st.retries(Long.MaxValue), Map.empty[String, StatusCandidate])
  }

  test("retries back off up to a day, are bounded per run, and the user can make them due now") {
    assertEquals(StatusSyncState.retryDelay(StatusSyncState.MaxAttempts), hour)
    assertEquals(StatusSyncState.retryDelay(StatusSyncState.MaxAttempts + 2), 4 * hour)
    assertEquals(StatusSyncState.retryDelay(StatusSyncState.MaxAttempts + 60), 24 * hour)
    // A made-up row keeps failing: its next try moves further away.
    var st = exhaust(StatusSyncState.empty.listed(Seq(meta(1, "a", "status", 9, Array[Byte](9))), "me"), "a", 9)
    val c = st.retries(hour).apply("a")
    st = st.failed("a", c, "bad tag", 10 * hour)
    assertEquals(st.candidates("a").head.retryAt, 12 * hour)
    // Several due devices: at most MaxRetries per run, one per device.
    var many = StatusSyncState.empty
    for d <- 1 to StatusSyncState.MaxRetries + 2 do
      many = many.listed(Seq(meta(d, s"d$d", "status", 1), meta(100 + d, s"d$d", "status", 0)), "me")
      many = exhaust(exhaust(many, s"d$d", 1), s"d$d", 0)
    val due = many.retries(Long.MaxValue)
    assertEquals(due.size, StatusSyncState.MaxRetries)
    assert(due.forall((d, c) => many.candidates(d).contains(c)))
    // 更新: every exhausted candidate is due at once.
    assertEquals(st.retries(hour), Map.empty[String, StatusCandidate])
    assertEquals(st.retryNow.retries(0L).get("a").map(_.seq), Some(9L))
  }

  test("a cursor outside the API's range is a malformed row, and a stored one restarts the walk (R4)") {
    val hash = "ab" * 32
    def row(cursor: String, seq: String = "2") =
      ujson.read(s"""{"cursor":$cursor,"deviceId":"a","stream":"status","seq":$seq,"size":40,"sha256":"$hash"}""")
    assertEquals(BatchMeta.fromJson(row("999999999999999")).cursor, BatchMeta.MaxCursor)
    intercept[IllegalArgumentException](BatchMeta.fromJson(row("1000000000000000")))
    intercept[IllegalArgumentException](BatchMeta.fromJson(row("1.5")))
    intercept[IllegalArgumentException](BatchMeta.fromJson(row("-1")))
    intercept[IllegalArgumentException](BatchMeta.fromJson(row("1", seq = "1000000000000")))
    // State stored by a version that took such a cursor: the walk starts over from 0.
    val st = StatusSyncState.fromJson(ujson.read(
      """{"cursor":1000000000000000,"candidates":{},"authenticated":{"a":3},"unresolved":{},"rescan":10000000000000000,"rescanAt":5}"""))
    assertEquals((st.cursor, st.rescan, st.authenticated), (0L, Some(0L), Map("a" -> 3L)))
    // A refused listing asks for a rescan.
    assert(StatusSyncState.empty.listFailed.rescanWanted)
  }

  test("when the newest candidate is exhausted, the next newest is tried; an older failure does not hide a newer one") {
    var st = StatusSyncState.empty.listed(Seq(meta(1, "a", "status", 3), meta(2, "a", "status", 4)), "me")
    st = exhaust(st, "a", 4)
    assertEquals(st.targets("a").seq, 3L)
    st = st.failed("a", st.targets("a"), "bad tag")
    // Still shown as the newest failure (seq 4), not replaced by seq 3's.
    assertEquals(st.problem("a").map(u => (u.seq, u.attempts)), Some((4L, StatusSyncState.MaxAttempts)))
    st = st.succeeded("a", 3)
    assertEquals(st.problem("a").map(_.seq), Some(4L))
    assertEquals(st.candidates("a").map(_.seq), Vector(4L))
  }

  test("candidates are bounded per device; exhausted ones do not crowd out the others") {
    var st = StatusSyncState.empty
    val fakes = Seq(20L, 21L, 22L, 23L)
    for (q, i) <- fakes.zipWithIndex do
      st = exhaust(st.listed(Seq(meta(i + 1, "a", "status", q, Array(i.toByte))), "me"), "a", q)
    assertEquals(st.targets.get("a"), None)
    st = st.listed(Seq(meta(10, "a", "status", 5), meta(11, "a", "status", 6)), "me")
    assertEquals(st.candidates("a").map(_.seq), Vector(23L, 22L, 21L, 20L, 6L, 5L))
    assertEquals(st.targets("a").seq, 6L)
    // Listed again (e.g. by a rescan), an exhausted row keeps its failures.
    val again = st.listed(fakes.zipWithIndex.map((q, i) => meta(20 + i, "a", "status", q, Array(i.toByte))), "me")
    assertEquals(again.candidates, st.candidates)
    // A fifth exhausted one drops the oldest exhausted one.
    st = exhaust(st.listed(Seq(meta(12, "a", "status", 24, Array[Byte](7))), "me"), "a", 24)
    assertEquals(st.candidates("a").map(_.seq), Vector(24L, 23L, 22L, 21L, 6L, 5L))
    // Five fresh ones: only the newest four are kept.
    st = StatusSyncState.empty.listed((1 to 5).map(q => meta(q, "a", "status", q)), "me")
    assertEquals(st.candidates("a").map(_.seq), Vector(5L, 4L, 3L, 2L))
    // A row with the same seq but another hash is another candidate.
    st = StatusSyncState.empty.listed(Seq(meta(1, "a", "status", 7, Array[Byte](1)), meta(2, "a", "status", 7, Array[Byte](2))), "me")
    assertEquals(st.candidates("a").size, 2)
  }

  test("a genuine status below more made-up rows than are kept is still read: the candidates rotate (F6)") {
    // One well-formed page: the genuine seq 0 and made-up seqs 1 to 8 of device b.
    val rows = (0 to 8).map(q => meta(q + 1, "b", "status", q, Array(q.toByte)))
    def list(after: Long): Seq[BatchMeta] = rows.filter(_.cursor > after)
    var st = StatusSyncState.empty
    var now = 1_000 * hour
    // Runs as PeerStatus.pull does, hourly for 60 days: main walk, rescan, then the fetches.
    for _ <- 1 to 24 * 60 if !st.authenticated.contains("b") do
      st = st.rescanIfDue(now)
      st = st.listed(list(st.cursor), "me", 100)
      while st.rescan.isDefined do st = st.rescanned(list(st.rescan.get), "me", 100, more = false)
      for (d, c) <- st.targets ++ st.retries(now) do
        st = if c.seq == 0 then st.succeeded(d, 0) else st.failed(d, c, "bad tag", now)
      now += hour
    assertEquals(st.authenticated.get("b"), Some(0L))
    // The made-up rows stay shown as unreadable.
    assert(st.problem("b").exists(_.seq > 0))
    assertEquals(StatusSyncState.fromJson(ujson.read(ujson.write(StatusSyncState.toJson(st)))), st)
  }

  test("a page whose cursors do not increase, or jump far at the end of the listing, does not move the cursor past them") {
    val big = StatusSyncState.MaxCursorJump
    // Not increasing: stop before the offending row, and want a rescan.
    var st = StatusSyncState.empty.listed(Seq(meta(5, "a", "status", 0), meta(4, "b", "status", 0), meta(6, "c", "status", 0)), "me", 10)
    assertEquals(st.cursor, 5L)
    assert(st.rescanWanted)
    // Its rows are still candidates (they are checked when fetched).
    assertEquals(st.targets.keySet, Set("a", "b", "c"))
    st = StatusSyncState.empty.listed(Seq(meta(0, "a", "status", 0)), "me", 10)
    assertEquals((st.cursor, st.rescanWanted), (0L, true))
    // A far jump on a short page (the end of the listing) is not taken.
    st = StatusSyncState.empty.listed(Seq(meta(5, "a", "status", 0), meta(5 + big + 1, "b", "status", 0)), "me", 10)
    assertEquals((st.cursor, st.rescanWanted), (5L, true))
    // On a full page it is followed (an honest gap must not stop the walk).
    st = StatusSyncState.empty.listed(Seq(meta(5, "a", "status", 0), meta(5 + big + 1, "b", "status", 0)), "me", 2)
    assertEquals((st.cursor, st.rescanWanted), (5 + big + 1, false))
    // A gap within the bound is normal (other users' batches).
    st = StatusSyncState.empty.listed(Seq(meta(big, "a", "status", 0)), "me", 10)
    assertEquals((st.cursor, st.rescanWanted), (big, false))
  }

  test("a rescan from cursor 0 is due weekly, or a day after one is wanted, and lists rows behind a made-up cursor") {
    val day = 24L * 60 * 60 * 1000
    val t0 = 100 * day
    // A fresh walk starts the clock without a rescan.
    var st = StatusSyncState.empty.rescanIfDue(t0)
    assertEquals((st.rescan, st.rescanAt), (None, t0))
    // A made-up row with a huge cursor on a full page: the main walk takes it.
    st = st.listed(Seq(meta(1, "a", "status", 0), meta(1000, "a", "status", 1, Array[Byte](9))), "me", 2)
    assertEquals(st.cursor, 1000L)
    st = st.succeeded("a", 0)
    st = exhaust(st, "a", 1)
    assert(st.rescanWanted)
    assertEquals(st.rescanIfDue(t0 + day - 1).rescan, None)
    st = st.rescanIfDue(t0 + day)
    assertEquals((st.rescan, st.rescanAt, st.rescanWanted), (Some(0L), t0 + day, false))
    // Genuine rows listed after the fake one have lower cursors; the rescan finds them.
    st = st.rescanned(Seq(meta(1, "a", "status", 0), meta(2, "a", "status", 1)), "me", 2, more = true)
    assertEquals(st.rescan, Some(2L))
    assertEquals(st.targets("a").seq, 1L)
    // The server no longer lists the fake row: the rescan ends, and the cursor follows it back.
    st = st.rescanned(Seq(meta(3, "b", "status", 0)), "me", 2, more = false)
    assertEquals((st.rescan, st.cursor), (None, 3L))
    // Without a reason, the next one is a week later.
    assertEquals(st.rescanIfDue(t0 + 7 * day).rescan, None)
    assertEquals(st.rescanIfDue(t0 + 8 * day).rescan, Some(0L))
  }

  test("stored state without listed hashes restarts the listing") {
    val old = ujson.read("""{"cursor":40,"latest":{"a":7},"authenticated":{"a":5},"unresolved":{}}""")
    val st = StatusSyncState.fromJson(old)
    assertEquals(st.cursor, 0L)
    assertEquals(st.candidates, Map.empty[String, Vector[StatusCandidate]])
    assertEquals(st.authenticated, Map("a" -> 5L))
  }

  test("stored state with one candidate per device keeps it, with its failures") {
    val hash = "ab" * 32
    val old = ujson.read(
      s"""{"cursor":40,"latest":{"a":{"seq":7,"size":40,"sha256":"$hash"}},"authenticated":{"a":5},"unresolved":{"a":{"seq":7,"attempts":3,"error":"x"}}}""")
    val st = StatusSyncState.fromJson(old)
    assertEquals(st.cursor, 40L)
    assertEquals(st.candidates, Map("a" -> Vector(StatusCandidate(7, 40, hash, 3))))
    assertEquals(st.targets, Map.empty[String, StatusCandidate])
    assertEquals(st.problem("a").map(_.seq), Some(7L))
    assertEquals((st.rescan, st.rescanAt, st.rescanWanted), (None, 0L, false))
    // An upgraded state is rescanned on the first run.
    assertEquals(st.rescanIfDue(1_760_000_000_000L).rescan, Some(0L))
  }
