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
