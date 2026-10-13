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

  test("newest status per other device, only when newer than known") {
    val items = Seq(
      BatchMeta(1, "me", "status", 9),
      BatchMeta(2, "a", "status", 0),
      BatchMeta(3, "a", "fixes", 7),
      BatchMeta(4, "a", "status", 1),
      BatchMeta(5, "b", "status", 4),
      BatchMeta(6, "c", "status", 2)
    )
    assertEquals(BatchMeta.newestStatus(items, "me", Map("b" -> 4L, "c" -> 1L)), Map("a" -> 1L, "c" -> 2L))
  }

  test("listing caps: decompressed size and line count abort") {
    val bomb = Crypto.gzip(new Array[Byte](1 << 20))
    intercept[LimitExceeded](Crypto.gunzip(bomb, DeviceStatus.MaxPlain))
    assertEquals(Crypto.gunzip(bomb).length, 1 << 20)
    val many = (DeviceStatus.encode(status) ++ Array.fill(DeviceStatus.MaxLines)(DeviceStatus.encode(status)).flatten)
    intercept[LimitExceeded](DeviceStatus.decode(many))
    intercept[IllegalArgumentException](BatchMeta.fromJson(ujson.read("""{"cursor":1,"deviceId":"../x","stream":"status","seq":0}""")))
  }

  test("an unreadable status is retried a few times, kept, and cleared by a newer readable one") {
    var st = StatusSyncState.empty.listed(Seq(BatchMeta(10, "a", "status", 5), BatchMeta(11, "me", "status", 9)), "me")
    assertEquals(st.cursor, 11L)
    assertEquals(st.targets, Map("a" -> 5L))
    for _ <- 1 to StatusSyncState.MaxAttempts do st = st.failed("a", 5, "bad tag")
    // Given up on for now, but still shown and not counted as read.
    assertEquals(st.targets, Map.empty[String, Long])
    assertEquals(st.problem("a").map(_.seq), Some(5L))
    assertEquals(st.authenticated.get("a"), None)
    st = st.listed(Seq(BatchMeta(12, "a", "status", 6)), "me")
    assertEquals(st.targets, Map("a" -> 6L))
    st = st.succeeded("a", 6)
    assertEquals(st.problem("a"), None)
    assertEquals(st.targets, Map.empty[String, Long])
    assertEquals(StatusSyncState.fromJson(ujson.read(ujson.write(StatusSyncState.toJson(st)))), st)
  }

  test("the cursor only moves to listed rows; an older seq does not lower latest") {
    val st = StatusSyncState.empty.listed(Seq(BatchMeta(3, "a", "status", 7)), "me").listed(Seq(BatchMeta(4, "a", "status", 2)), "me")
    assertEquals(st.cursor, 4L)
    assertEquals(st.latest, Map("a" -> 7L))
    assertEquals(st.listed(Nil, "me").cursor, 4L)
  }
