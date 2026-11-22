package ashidori.core

import java.time.{LocalDate, ZoneId}
import PicsRuns.Refused

/** Runs of `pics_adv` (docs/design/pics.md 4.1): flows and their runs, what
  * closes them, the summaries, the acceptance caps, batches out of order,
  * and a stationary day near a signal within the caps.
  */
class PicsRunSuite extends munit.FunSuite:
  private val zone = ZoneId.of("Asia/Tokyo")
  private val t0 = LocalDate.of(2026, 11, 20).atStartOfDay(zone).toInstant.toEpochMilli + 10 * 3_600_000L

  private def bytes(h: String) = PicsFormat.unhex(h).toOption.get.toIndexedSeq
  /** A kind 2 packet of intersection 00CD0849 with two crosswalks. */
  private def sig(a: Int, b: Int, msg: Int = 0xff) = bytes(f"580102$msg%02X000000CD0849$a%02X$b%02XFFFFFFFF0000000000000000")
  private val name = bytes("580100FF000000CD0849" + "50494353000000000000000000000000".take(28))
  private val pos = bytes("580101FF000000CD0849022392B808530664000000000000")
  private val red = sig(0x51, 0x43)
  private val green = sig(0x43, 0x51)

  private def res(at: Long, data: IndexedSeq[Byte], addr: String = "02:00:00:00:00:01", rssi: Int = -70, scan: String = "bal") =
    PicsResult(at, addr, PicsFormat.CompanyId, data, rssi, scan)

  test("kinds alternating are separate flows; red, green, red is three rows") {
    val r = PicsRuns(zone)
    // 150 ms apart, kinds 1 and 2 alternating, the signal red, then green, then red.
    val rs = (0 until 30).map { i =>
      val at = t0 + i * 150L
      if i % 2 == 0 then res(at, pos) else res(at, if i < 10 then red else if i < 20 then green else red)
    }
    val closed = r.add(rs, still = false) ++ r.closeAll()
    val signals = closed.filter(_.kind.contains(2)).sortBy(_.start)
    assertEquals(signals.map(_.data.slice(20, 24)), Seq("5143", "4351", "5143"))
    assertEquals(signals.map(_.n), Seq(5, 5, 5))
    // Kind 1 was not cut by the kind 2 changes: one run (4.2 s).
    assertEquals(closed.count(_.kind.contains(1)), 1)
    assertEquals(closed.find(_.kind.contains(1)).get.n, 15)
    assert(closed.forall(a => a.iid.contains("00CD0849") && a.addr == "02:00:00:00:00:01"))
  }

  test("5 s from the first reception (60 s while still), 3 s without one, a scan change, the end: each closes") {
    val r = PicsRuns(zone)
    val every = (0 until 60).map(i => res(t0 + i * 200L, red))
    val rows = r.add(every, still = false) ++ r.closeAll()
    // 12 s of receptions: 5 s, 5 s, 2 s.
    assertEquals(rows.map(_.n), Seq(25, 25, 10))
    assertEquals(rows.map(a => a.end - a.start), Seq(4800L, 4800L, 1800L))
    val still = PicsRuns(zone)
    val longer = still.add((0 until 400).map(i => res(t0 + i * 200L, red)), still = true) ++ still.closeAll()
    assertEquals(longer.map(_.n), Seq(300, 100))
    // A gap over 3 s.
    val g = PicsRuns(zone)
    val gap = g.add(Seq(res(t0, red), res(t0 + 1000, red), res(t0 + 4100, red)), still = false) ++ g.closeAll()
    assertEquals(gap.map(_.n), Seq(2, 1))
    // The scan changed (a result of the new scan).
    val s = PicsRuns(zone)
    val scans = s.add(Seq(res(t0, red), res(t0 + 200, red, scan = "low")), still = false) ++ s.closeAll()
    assertEquals(scans.map(_.scan), Seq("bal", "low"))
    // tick: none for 3 s (plus the report delay), or past the longest.
    val k = PicsRuns(zone)
    assertEquals(k.add(Seq(res(t0, red)), still = false), Nil)
    assertEquals(k.tick(t0 + 3000, still = false), Nil)
    assertEquals(k.tick(t0 + 3001, still = false, delayMs = 5000), Nil)
    assertEquals(k.tick(t0 + 3001, still = false).map(_.n), Seq(1))
    assertEquals(k.openRuns, 0)
  }

  test("local midnight closes a run; no row has two days") {
    val r = PicsRuns(zone)
    val midnight = LocalDate.of(2026, 11, 21).atStartOfDay(zone).toInstant.toEpochMilli
    val rows = r.add((0 until 20).map(i => res(midnight - 2000 + i * 200L, red)), still = true) ++ r.closeAll()
    assertEquals(rows.map(_.n), Seq(10, 10))
    assert(rows.head.end < midnight && rows(1).start >= midnight)
    val t = PicsRuns(zone)
    t.add(Seq(res(midnight - 500, red)), still = true)
    assertEquals(t.tick(midnight + 10, still = true).map(_.n), Seq(1))
  }

  test("byte 3: the first and last message IDs and how often it changed; RSSI extremes and the first time of the maximum") {
    val r = PicsRuns(zone)
    val msgs = Seq(1, 1, 2, 3, 3, 1)
    val rssi = Seq(-80, -60, -70, -60, -90, -65)
    val rows = r.add(msgs.indices.map(i => res(t0 + i * 200L, sig(0x51, 0x43, msgs(i)), rssi = rssi(i))), still = false) ++
      r.closeAll()
    val a = rows.loneElement
    assertEquals((a.msg, a.msgLast, a.msgChanges, a.n), (Some(1), Some(1), 3, 6))
    assertEquals((a.rssiMax, a.rssiMaxAt, a.rssiMin), (-60, t0 + 200L, -90))
    assertEquals(a.data, PicsFormat.hex(sig(0x51, 0x43, 1)))
  }

  test("short packets: nulls where their bytes end; kept whatever their form") {
    val r = PicsRuns(zone)
    val rows = r.add(Seq(res(t0, bytes("58")), res(t0 + 10, bytes("580102")), res(t0 + 20, bytes("580102FF00")),
      res(t0 + 30, bytes("580107FF000000CD0849AA"))), still = false) ++ r.closeAll()
    val byData = rows.map(a => a.data -> (a.kind, a.msg, a.iid)).toMap
    assertEquals(byData("58"), (None, None, None))
    assertEquals(byData("580102"), (Some(2), None, None))
    assertEquals(byData("580102FF00"), (Some(2), Some(0xff), None))
    assertEquals(byData("580107FF000000CD0849AA"), (Some(7), Some(0xff), Some("00CD0849")))
  }

  test("a batch out of time order is sorted; a result older than the open run is a run of its own") {
    val r = PicsRuns(zone)
    val batch = scala.util.Random(3).shuffle((0 until 20).map(i => res(t0 + i * 200L, red)))
    assertEquals(r.add(batch, still = false), Nil)
    // Late: before the open run's last reception.
    val late = r.add(Seq(res(t0 + 1000L, red)), still = false)
    assertEquals(late.map(a => (a.start, a.n)), Seq((t0 + 1000L, 1)))
    assertEquals(r.closeAll().map(_.n), Seq(20))
  }

  test("closed rows are final: kept in a file and read back, retried, they are the same") {
    val r = PicsRuns(zone)
    val rows = r.add((0 until 40).map(i => res(t0 + i * 200L, if i < 20 then red else green)), still = false) ++ r.closeAll()
    val rec = new SmallRecord.PicsAdvs(rows)
    assertEquals(SmallRecord.decode(SmallRecord.encode(rec)), rec)
    assertEquals(rec.kind, WriteKind.PicsAdv)
    assertEquals(rec.rows, rows.size)
    val short = new SmallRecord.PicsAdvs(Seq(PicsAdv(1, 2, "x", None, None, None, None, 0, 1, -50, 1, -50, "58", "debug")))
    assertEquals(SmallRecord.decode(SmallRecord.encode(short)), short)
    // And as an archive row.
    for a <- rows ++ short.runs do assertEquals(Archive.picsOf(Archive.picsRow(a).toIndexedSeq), a)
  }

  test("caps: per sender 2 rows a second after a burst of 20; refused receptions counted, not written") {
    val r = PicsRuns(zone, PicsRuns.Caps(totalPerSec = 1000, totalBurst = 1000))
    // A sender changing its content every reception: a row each.
    val flood = (0 until 200).map(i => res(t0 + i * 10L, sig(i % 256, 0x43)))
    val rows = r.add(flood, still = false) ++ r.closeAll()
    // 2 s: 20 at once and about 4 more.
    assert(rows.size >= 20 && rows.size <= 25, rows.size)
    assertEquals(r.refusedTotal.getOrElse(Refused.Source, 0L), 200L - rows.size)
    assertEquals(r.takeRefused().values.sum, 200L - rows.size)
    assertEquals(r.takeRefused(), Map.empty[Refused, Long])
  }

  test("caps: rows of all senders, a day's rows, open runs, new senders a minute") {
    val small = PicsRuns.Caps(perSourceBurst = 1000, perSourcePerSec = 1000, totalPerSec = 10, totalBurst = 10)
    val r = PicsRuns(zone, small)
    val many = (0 until 100).map(i => res(t0 + i * 10L, sig(i, 0x43), addr = s"02:00:00:00:00:${i % 5}"))
    val rows = r.add(many, still = false) ++ r.closeAll()
    // 1 s: the burst of 10 and 10 more.
    assert(rows.size >= 19 && rows.size <= 21, rows.size)
    assert(r.refusedTotal.contains(Refused.Total))
    // A day's rows: the rest of that day refused, the next day accepted again.
    val d = PicsRuns(zone, PicsRuns.Caps(perDay = 5))
    val day = (0 until 10).map(i => res(t0 + i * 10_000L, red))
    assertEquals((d.add(day, still = false) ++ d.closeAll()).size, 5)
    assertEquals(d.refusedTotal(Refused.Day), 5L)
    assertEquals(d.add(Seq(res(t0 + 86_400_000L, red)), still = false), Nil)
    assertEquals(d.openRuns, 1)
    // Open runs: a new flow is refused when 64 are open.
    val o = PicsRuns(zone, PicsRuns.Caps(totalBurst = 1000, totalPerSec = 1000))
    o.add((0 until 70).map(i => res(t0, red, addr = f"02:00:00:00:01:$i%02X")), still = false)
    assertEquals(o.openRuns, 32)
    assertEquals(o.refusedTotal(Refused.NewSenders), 38L)
    val o2 = PicsRuns(zone, PicsRuns.Caps(newPerMinute = 1000))
    o2.add((0 until 70).map(i => res(t0, red, addr = f"02:00:00:00:01:$i%02X")), still = false)
    assertEquals(o2.openRuns, 10) // the total burst
    val o3 = PicsRuns(zone, PicsRuns.Caps(newPerMinute = 1000, totalBurst = 1000, totalPerSec = 1000))
    o3.add((0 until 70).map(i => res(t0, red, addr = f"02:00:00:00:01:$i%02X")), still = false)
    assertEquals(o3.openRuns, 64)
    assertEquals(o3.refusedTotal(Refused.Open), 6L)
    // New senders a minute: 32, then more once the minute is over.
    val n = PicsRuns(zone, PicsRuns.Caps(totalBurst = 1000, totalPerSec = 1000))
    n.add((0 until 40).map(i => res(t0 + i, red, addr = f"02:00:00:00:02:$i%02X")), still = false)
    assertEquals(n.refusedTotal(Refused.NewSenders), 8L)
    n.closeAll()
    n.add((0 until 8).map(i => res(t0 + 61_000L, red, addr = f"02:00:00:00:03:$i%02X")), still = false)
    assertEquals(n.openRuns, 8)
  }

  test("caps: a batch out of order refills nothing twice; a clock set back over a minute starts the buckets again") {
    val r = PicsRuns(zone)
    // A flood at t0 uses the total burst.
    r.add((0 until 100).map(i => res(t0 + i, sig(i, 0x43), addr = s"F$i")), still = false)
    val refused = r.refusedTotal.values.sum
    assert(refused > 0)
    // Just before: no refill (the total stays empty).
    r.add(Seq(res(t0 - 500, red, addr = "G")), still = false)
    assertEquals(r.refusedTotal.values.sum, refused + 1)
    // Stamped ten days ago (a clock set back, an injection): refills as their own times go on.
    val back = t0 - 10 * 86_400_000L
    val rows = r.add((0 until 40).map(i => res(back + i * 500L, sig(i, 0x51), addr = "H")), still = false)
    assert(rows.size + r.openRuns - 10 >= 15, (rows.size, r.openRuns))
  }

  test("caps: the senders tracked are the 256 most recent") {
    val r = PicsRuns(zone, PicsRuns.Caps(newPerMinute = 10_000, totalBurst = 1e9, totalPerSec = 1e9, open = 10_000))
    // Sender 0 uses its burst, then 300 others come: it is forgotten, and comes back with a full burst.
    r.add((0 until 20).map(i => res(t0 + i, sig(i, 1), addr = "A")), still = false)
    r.add(Seq(res(t0 + 30, sig(99, 1), addr = "A")), still = false)
    assertEquals(r.refusedTotal(Refused.Source), 1L)
    r.add((0 until 300).map(i => res(t0 + 100 + i, red, addr = s"B$i")), still = false)
    r.add(Seq(res(t0 + 500, sig(98, 1), addr = "A")), still = false)
    assertEquals(r.refusedTotal(Refused.Source), 1L)
  }

  test("「受信の試験」: each reception a row, under the test's own cap") {
    val r = PicsRuns(zone, PicsRuns.Caps(testRows = 50))
    r.add(Seq(res(t0, red)), still = false)
    // Starting the test closes what is open.
    assertEquals(r.setTest(true).map(_.n), Seq(1))
    val rows = r.add((0 until 80).map(i => res(t0 + 1000 + i * 100L, red, scan = "lat")), still = false)
    assertEquals(rows.size, 50)
    assert(rows.forall(_.n == 1))
    assertEquals(r.refusedTotal(Refused.Test), 30L)
    assertEquals(r.openRuns, 0)
    r.setTest(false)
    r.add(Seq(res(t0 + 20_000, red)), still = false)
    assertEquals(r.openRuns, 1)
  }

  /** A roadside unit: kinds 0, 1 and 2 every 200 ms each; the two
    * crosswalks' signals in a 90 s cycle (red 60 s, green 25 s, flashing
    * 5 s, the other the opposite), the gauge counting 8 steps down in
    * each phase.
    */
  private def unit(at: Long, addr: String): Seq[(Long, IndexedSeq[Byte])] =
    val phase = (at / 1000) % 90
    def light(p: Long): (Int, Long, Long) =
      if p < 60 then (1, p, 60) else if p < 85 then (3, p - 60, 25) else (2, p - 85, 5)
    def byte(p: Long) =
      val (l, in, len) = light(p)
      ((7 - (in * 8 / len).toInt).max(0) << 4) | l
    Seq(at -> name, at + 66 -> pos, at + 133 -> sig(byte(phase), byte((phase + 45) % 90)))

  /** A day of receptions of two units: `heard` tells which advertisements came through. */
  private def day(heard: (Long, scala.util.Random) => Boolean): (PicsRuns, Seq[PicsAdv], Long) =
    val r = PicsRuns(zone)
    val rnd = scala.util.Random(11)
    val start = LocalDate.of(2026, 11, 20).atStartOfDay(zone).toInstant.toEpochMilli
    val out = Vector.newBuilder[PicsAdv]
    var received = 0L
    var at = start
    val end = start + 86_400_000L
    while at < end do
      val batch = for addr <- Seq("02:11:00:00:00:01", "02:11:00:00:00:02"); (t, d) <- unit(at, addr) if heard(t, rnd) yield
        res(t, d, addr = addr, rssi = -60 - rnd.nextInt(30), scan = "opp")
      received += batch.size
      out ++= r.add(batch, still = true)
      at += 200
      if at % 1000 == 0 then out ++= r.tick(at, still = true)
    out ++= r.closeAll()
    (r, out.result(), received)

  test("a stationary day near a signal stays under the caps (opportunistic scans of other apps)") {
    // Still: no scan of ours; others' scans 10 s a minute, 40 % of the advertisements heard then.
    val (r, rows, received) = day((t, rnd) => (t / 1000) % 60 < 10 && rnd.nextDouble() < 0.4)
    assertEquals(r.refusedTotal, Map.empty[Refused, Long])
    assert(rows.size < 20_000, rows.size)
    // Every reception is in a row, every row in the day.
    assertEquals(rows.map(_.n.toLong).sum, received)
    val bytes = parquetBytes(rows)
    println(f"pics stationary day: $received%,d receptions, ${rows.size}%,d rows, Parquet $bytes%,d bytes")
    assert(bytes < 600_000, bytes)
  }

  test("a stationary day heard all the time (worst case): the day's cap holds, the rest is refused and counted") {
    val (r, rows, received) = day((_, rnd) => rnd.nextDouble() < 0.25)
    assertEquals(rows.size, 20_000)
    assert(r.refusedTotal(Refused.Day) > 0)
    assertEquals(rows.map(_.n.toLong).sum + r.refusedTotal.values.sum, received)
    val bytes = parquetBytes(rows)
    println(f"pics worst day: $received%,d receptions, ${rows.size}%,d rows (cap), ${r.refusedTotal(Refused.Day)}%,d refused, Parquet $bytes%,d bytes")
  }

  private def parquetBytes(rows: Seq[PicsAdv]): Long =
    val out = java.io.ByteArrayOutputStream()
    val w = Archive.writer(Archive.PicsAdvs, out, 50_000)
    rows.sortBy(_.start).foreach(a => w.write(Archive.picsRow(a)))
    w.close()
    out.size.toLong

  extension [A](xs: Seq[A]) private def loneElement: A =
    assertEquals(xs.size, 1, xs)
    xs.head
