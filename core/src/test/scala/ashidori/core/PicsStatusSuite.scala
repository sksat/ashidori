package ashidori.core

import java.time.ZoneId
import PicsFormat.{Light, Ped}
import PicsScanPolicy.State
import PicsStatus.*

/** The ステータス row 「信号機」 (docs/design/pics.md 6): shown in every state
  * while the reception is on, the same two lines each time.
  */
class PicsStatusSuite extends munit.FunSuite:
  private val zone = ZoneId.of("Asia/Tokyo")
  // 2026-11-24 12:00:00 JST.
  private val noon = 1_795_489_200_000L
  private val now = 50_000_000L
  private val peds = Seq(Ped(0, Light.Red, 1, Some(3)), Ped(1, Light.Green, 3, Some(5)))
  private def heard(ago: Long, signalsAgo: Long = 0L) =
    Heard("A交差点", peds, now - signalsAgo, now - ago, noon - ago)
  private val base = Input(now, noon, zone, permission = true, bluetooth = Some(true), State.Standby,
    current = None, nearest = None, stored = None)

  test("the clock time of a reception") {
    assertEquals(time(noon, zone), "12:00:00")
    assertEquals(time(noon - 3 * 3_600_000L + 5_000L, zone), "9:00:05")
  }

  test("distances: 10 m steps below 1 km, then 0.1 km, then whole km") {
    assertEquals(distance(0), "0 m")
    assertEquals(distance(324), "320 m")
    assertEquals(distance(326), "330 m")
    assertEquals(distance(994), "990 m")
    assertEquals(distance(996), "1.0 km")
    assertEquals(distance(1_249), "1.2 km")
    assertEquals(distance(9_949), "9.9 km")
    assertEquals(distance(9_951), "10 km")
    assertEquals(distance(23_400), "23 km")
  }

  test("receiving: the intersection, its signals coloured for 2 s then grey, the last reception") {
    val in = base.copy(state = State.Receiving, current = Some(heard(0)))
    val r = row(in)
    assertEquals((r.main, r.tone, r.signals, r.detail, r.sub, r.opens), ("A交差点", Tone.Normal, Some((peds, true)), None,
      "最終受信 12:00:00", None))
    assertEquals(r.nextAt, now + PicsDisplay.FreshMs)
    val grey = row(in.copy(now = now + 2_000L, nowWall = noon + 2_000L))
    assertEquals(grey.signals, Some((peds, false)))
    assertEquals(grey.nextAt, now + PicsScanPolicy.QuietMs + 1)
    // No signals for 20 s: the intersection alone.
    val quiet = row(in.copy(now = now + 21_000L))
    assertEquals((quiet.main, quiet.signals, quiet.nextAt), ("A交差点", None, Long.MaxValue))
    // The test receives too.
    assertEquals(row(in.copy(state = State.Test)).signals, Some((peds, true)))
  }

  test("not receiving: the state in its colour, the distance from a recent fix, the last reception of today") {
    val r = row(base.copy(nearest = Some(Nearest(324, now - 60_000L))))
    assertEquals((r.main, r.tone, r.signals, r.detail, r.sub, r.opens), ("待受", Tone.Active, None, Some("最寄り 320 m"),
      NoneToday, None))
    // The distance goes with its fix, 5 min after it.
    assertEquals(r.nextAt, now - 60_000L + PicsScanPolicy.FixMaxAgeMs + 1)
    assertEquals(row(base.copy(nearest = Some(Nearest(324, now - PicsScanPolicy.FixMaxAgeMs - 1)))).detail, None)
    assertEquals(row(base.copy(state = State.Opportunistic)).tone, Tone.Normal)
    assertEquals(row(base.copy(state = State.Stopped)).main, "停止")
    assertEquals(row(base.copy(state = State.Stopped)).tone, Tone.Muted)
    // Receiving ended: the intersection heard moves to the second line.
    val after = row(base.copy(state = State.Opportunistic, current = Some(heard(90_000L, 90_000L))))
    assertEquals((after.main, after.signals, after.sub), ("便乗", None, "最終受信 11:58:30・A交差点"))
    // Midnight ends 「本日」.
    assertEquals(after.nextAt, now + 12 * 3_600_000L)
    // Receiving with nothing heard yet (the test): the state.
    assertEquals(row(base.copy(state = State.Test)).main, "試験")
  }

  test("the last reception: the newer of this process's and the stored, of today only") {
    val stored = Last("ID 1234", noon - 30_000L)
    assertEquals(row(base.copy(stored = Some(stored))).sub, "最終受信 11:59:30・ID 1234")
    assertEquals(row(base.copy(stored = Some(stored), current = Some(heard(60_000L)))).sub, "最終受信 11:59:30・ID 1234")
    assertEquals(row(base.copy(stored = Some(stored), current = Some(heard(10_000L)))).sub, "最終受信 11:59:50・A交差点")
    // Yesterday's is not today's.
    assertEquals(row(base.copy(stored = Some(Last("B", noon - 13 * 3_600_000L)))).sub, NoneToday)
  }

  test("the permission missing or Bluetooth off: said, and the settings a tap opens; the same lines") {
    val stored = Some(Last("A交差点", noon - 30_000L))
    val p = row(base.copy(permission = false, stored = stored))
    assertEquals((p.main, p.tone, p.detail, p.sub, p.opens), (NoPermission, Tone.Error, None, "最終受信 11:59:30・A交差点",
      Some(Opens.AppPermission)))
    val b = row(base.copy(bluetooth = Some(false), nearest = Some(Nearest(100, now))))
    assertEquals((b.main, b.tone, b.detail, b.sub, b.opens), (BluetoothOff, Tone.Error, None, NoneToday, Some(Opens.BluetoothSettings)))
    assertEquals((row(base.copy(bluetooth = None)).main, row(base.copy(bluetooth = None)).opens), (NoBluetooth, None))
    // Before the state: a plan not made again yet still says receiving.
    assertEquals(row(base.copy(bluetooth = Some(false), state = State.Receiving, current = Some(heard(0)))).main, BluetoothOff)
  }

  private def s32(v: Int): Seq[Int] = Seq(24, 16, 8, 0).map(s => (v >> s) & 0xff)
  private def packet(kind: Int, id: Long, rest: Seq[Int]): String =
    val head = Seq(0x58, 0x01, kind, 0xff, 0, 0) ++ Seq(24, 16, 8, 0).map(s => ((id >> s) & 0xff).toInt)
    PicsFormat.hex((head ++ rest ++ Seq.fill(24)(0)).take(24).map(_.toByte).toArray)
  private def adv(end: Long, kind: Int, id: Long, rest: Seq[Int] = Nil): PicsAdv =
    val data = packet(kind, id, rest)
    val p = PicsFormat.packet(PicsFormat.unhex(data).toOption.get)
    PicsAdv(end - 1000, end, "02:00:00:00:00:01", p.intersection, p.kind, p.msgId, p.msgId, 0, 5, -60, end, -70, data, "debug")

  test("the last stored reception of today, named by a kind 1 position of its ID") {
    val day = noon - 12 * 3_600_000L
    val siteOf = (lat: Double, lon: Double) => Option.when(math.abs(lat - 35.69) < 1e-4)("A交差点")
    val label = (iid: String) => s"ID ${PicsFormat.idNumber(iid)}"
    val pos = adv(noon - 60_000L, 1, 13_000_037L, s32(35_690_000) ++ s32(139_700_000))
    val sig = adv(noon - 30_000L, 2, 13_000_037L, Seq(0x31, 0x13, 0xff, 0xff, 0xff, 0xff))
    assertEquals(lastStored(Seq(sig, pos), day, siteOf, label), Some(Last("A交差点", noon - 30_000L)))
    // Without its position: its ID.
    assertEquals(lastStored(Seq(sig), day, siteOf, label), Some(Last(label(sig.iid.get), noon - 30_000L)))
    // Not today, or not a qualifying reception.
    assertEquals(lastStored(Seq(sig), noon, siteOf, label), None)
    assertEquals(lastStored(Seq(adv(noon - 1_000L, 7, 13_000_037L)), day, siteOf, label), None)
  }

  test("the nearest listed site at any distance") {
    val a = PicsSite("東京都", "新宿区", "A交差点", 35.690000, 139.700000)
    val b = PicsSite("宮城県", "仙台市", "B交差点", 38.260000, 140.880000)
    val idx = PicsSites.Index(Vector(a, b))
    assertEquals(idx.nearestAny(35.693, 139.700).map(_._1), Some(a))
    assertEqualsDouble(idx.nearestAny(35.693, 139.700).get._2, 333.6, 1.0)
    // 50 km and more: every site.
    assertEquals(idx.nearestAny(37.9, 140.5).map(_._1), Some(b))
    assertEquals(idx.nearestAny(Double.NaN, 139.0), None)
    assertEquals(PicsSites.Index(Vector.empty).nearestAny(35.0, 139.0), None)
  }
