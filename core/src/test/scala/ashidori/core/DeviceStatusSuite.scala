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
    val key = Crypto.batchKey(Crypto.newMasterKey())
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
    val key = Crypto.batchKey(Crypto.newMasterKey())
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
    val failed = st.failed("a", 5, c.mismatch(other).get)
    assertEquals(failed.problem("a").map(_.seq), Some(5L))
    assertEquals(failed.authenticated.get("a"), None)
  }

  test("an unreadable status is retried a few times, kept, and cleared by a newer readable one") {
    var st = StatusSyncState.empty.listed(Seq(meta(10, "a", "status", 5), meta(11, "me", "status", 9)), "me")
    assertEquals(st.cursor, 11L)
    assertEquals(st.targets.view.mapValues(_.seq).toMap, Map("a" -> 5L))
    for _ <- 1 to StatusSyncState.MaxAttempts do st = st.failed("a", 5, "bad tag")
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

  test("the cursor only moves to listed rows; an older seq does not replace the candidate") {
    val st = StatusSyncState.empty.listed(Seq(meta(3, "a", "status", 7)), "me").listed(Seq(meta(4, "a", "status", 2)), "me")
    assertEquals(st.cursor, 4L)
    assertEquals(st.latest.view.mapValues(_.seq).toMap, Map("a" -> 7L))
    assertEquals(st.listed(Nil, "me").cursor, 4L)
  }

  test("stored state without listed hashes restarts the listing") {
    val old = ujson.read("""{"cursor":40,"latest":{"a":7},"authenticated":{"a":5},"unresolved":{}}""")
    val st = StatusSyncState.fromJson(old)
    assertEquals(st.cursor, 0L)
    assertEquals(st.latest, Map.empty[String, StatusCandidate])
    assertEquals(st.authenticated, Map("a" -> 5L))
  }
