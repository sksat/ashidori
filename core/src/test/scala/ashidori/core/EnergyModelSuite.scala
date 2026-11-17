package ashidori.core

import java.io.ByteArrayOutputStream
import java.time.{LocalDate, ZoneId}
import scala.util.Random
import ashidori.core.parquet.*
import EnergyModel.GnssClass

class EnergyModelSuite extends munit.FunSuite:
  private val Zone = ZoneId.of("Asia/Tokyo")
  private val Min = 60_000L
  private val Hour = 3_600_000L
  private val Day0 = LocalDate.of(2026, 10, 1).atStartOfDay(Zone).toInstant.toEpochMilli

  // Known drain, % per hour.
  private val Base = 0.4
  private val ScreenX = 6.0
  private val ActiveX = 0.3
  private val DetailX = 6.0
  private val GnssX = Map(GnssClass.S1 -> 3.0, GnssClass.S5 -> 2.0, GnssClass.S15 -> 1.2, GnssClass.S30 -> 0.6)
  private val CapUah = 4_500_000.0

  private val quiet = EnergyState(gnss = false, 900_000, "fused", detail = false, still = true, screenOn = false,
    charging = false, "balanced", "balanced", "Stationary", "Unknown")

  private def rate(s: EnergyState): Double =
    if s.charging then 0
    else
      Base + (if s.screenOn then ScreenX else 0) + (if !s.still then ActiveX else 0) + (if s.detail then DetailX else 0) +
        (if s.gnss then GnssX(GnssClass.of(s.gnssIntervalMs)) else 0)

  /** `days` of synthetic recording: charging at night, then a random mix of
    * quiet stretches, screen use, trips with GNSS at different intervals,
    * moving indoors without GNSS and detail logs. Readings at every change
    * and every 5 minutes, but only now and then in quiet stretches (doze).
    * Returns (readings, spans).
    */
  private def synth(days: Int, seed: Long, counterMissing: Double = 0.0, counterNoiseUah: Double = 2000,
      spikes: Int = 0): (Vector[BatteryReading], Vector[EnergySpan]) =
    val rnd = Random(seed)
    val spans = Vector.newBuilder[EnergySpan]
    for d <- 0 until days do
      val t0 = Day0 + d * 24 * Hour
      spans += EnergySpan(t0, t0 + 7 * Hour, quiet.copy(charging = true))
      var t = t0 + 7 * Hour
      val end = t0 + 23 * Hour
      while t < end do
        val u = rnd.nextDouble()
        val (st, dur) =
          if u < 0.35 then (quiet, (30 + rnd.nextInt(90)) * Min)
          else if u < 0.5 then (quiet.copy(screenOn = true), (5 + rnd.nextInt(35)) * Min)
          else if u < 0.78 then
            val ms = Seq(1_000L, 5_000L, 10_000L, 30_000L)(rnd.nextInt(4))
            val motion = if ms <= 5_000 then "Vehicle" else "OnFoot"
            (quiet.copy(gnss = true, gnssIntervalMs = ms, provider = "gps", still = false, screenOn = rnd.nextDouble() < 0.25,
              motion = motion, env = "Outdoor"), (10 + rnd.nextInt(50)) * Min)
          else if u < 0.88 then (quiet.copy(still = false, motion = "OnFoot", env = "Indoor"), (10 + rnd.nextInt(20)) * Min)
          else (quiet.copy(gnss = true, gnssIntervalMs = 1_000, provider = "gps", detail = true), (1 + rnd.nextInt(4)) * Min)
        val e = math.min(end, t + dur)
        spans += EnergySpan(t, e, st)
        t = e
      spans += EnergySpan(end, t0 + 24 * Hour, quiet.copy(charging = true))
    val sp = spans.result()
    // Readings.
    val spikeAt = Vector.fill(spikes)(Day0 + rnd.nextInt(days) * 24 * Hour + (8 + rnd.nextInt(14)) * Hour)
    val out = Vector.newBuilder[BatteryReading]
    var level = 100.0
    var last = sp.head.start
    def read(t: Long, s: EnergyState): Unit =
      val counter =
        if rnd.nextDouble() < counterMissing then None
        else Some((level / 100 * CapUah + rnd.nextGaussian() * counterNoiseUah).toLong)
      out += BatteryReading(t, math.floor(level).toInt.min(100), s.charging, s.screenOn, s.mode, counter)
    for s <- sp do
      if s.state.charging then
        level = 100.0
        read(s.start, s.state)
      else
        read(s.start, s.state)
        var t = s.start
        val step = if s.state == quiet then (30 + rnd.nextInt(60)) * Min else 5 * Min
        while t < s.end do
          val n = math.min(s.end, t + step)
          level -= rate(s.state) * (n - t) / Hour
          // Something else draining the battery now and then.
          if spikeAt.exists(x => x >= t && x < n) then level -= 3.0
          t = n
          if t < s.end then read(t, s.state)
    (out.result(), sp)

  private def assertNear(name: String, got: Double, want: Double, tol: Double)(using munit.Location): Unit =
    assert(math.abs(got - want) <= tol, s"$name: got $got, want $want ± $tol")

  test("recovers known coefficients from the charge counter, despite noise and spikes") {
    val (rs, sp) = synth(7, 1, spikes = 4)
    val f = EnergyModel.fit(rs, sp).toOption.get
    assertEquals(f.source, EnergyModel.Source.Counter)
    assertNear("capacity", f.capacityMah, 4500, 60)
    assertNear("baseline", f.baseline.perHour, Base, 0.08)
    assertNear("screen", f.screen.perHour, ScreenX, 0.4)
    assertNear("active", f.active.perHour, ActiveX, 0.15)
    assertNear("detail", f.detail.perHour, DetailX, 1.5)
    for (c, v) <- GnssX do assertNear(s"gnss ${c.key}", f.gnss(c).perHour, v, 0.25 * v)
    assert(f.baseline.ok && f.screen.ok && f.gnss.values.forall(_.ok), f.toString)
  }

  test("falls back to percentages where the counter is missing") {
    val (rs, sp) = synth(7, 2, counterMissing = 1.0)
    val f = EnergyModel.fit(rs, sp).toOption.get
    assertEquals(f.source, EnergyModel.Source.Percent)
    assert(f.capacityMah.isNaN)
    assertNear("baseline", f.baseline.perHour, Base, 0.15)
    assertNear("screen", f.screen.perHour, ScreenX, 1.0)
    for (c, v) <- GnssX do assertNear(s"gnss ${c.key}", f.gnss(c).perHour, v, 0.45 * v + 0.2)
  }

  test("a counter missing in some readings still uses it where both ends have one") {
    val (rs, sp) = synth(7, 3, counterMissing = 0.2)
    val f = EnergyModel.fit(rs, sp).toOption.get
    assertEquals(f.source, EnergyModel.Source.Counter)
    assertNear("baseline", f.baseline.perHour, Base, 0.1)
    for (c, v) <- GnssX do assertNear(s"gnss ${c.key}", f.gnss(c).perHour, v, 0.3 * v)
  }

  test("an implausible counter (the emulator's constant 10 mAh) is ignored") {
    val rs = (0 until 20).map(i => BatteryReading(i * 5 * Min, 90 - i, false, false, "balanced", Some(10_000L)))
    assertEquals(EnergyModel.capacityUah(rs), None)
    val flat = (0 until 20).map(i => BatteryReading(i * 5 * Min, 90 - i, false, false, "balanced", Some(3_000_000L)))
    assertEquals(EnergyModel.capacityUah(flat), None, "a counter that does not follow the percentage")
  }

  test("says honestly when there is not enough data") {
    val (rs, sp) = synth(1, 4)
    val short = rs.filter(_.time < Day0 + 10 * Hour)
    assert(EnergyModel.fit(short, sp).isLeft)
    assert(EnergyModel.fit(rs, Nil).isLeft, "no spans: nothing to attribute")
    val r = EnergyModel.analyse(short, sp.filter(_.start < Day0 + 10 * Hour))
    assert(r.recordingPerDay.isLeft)
    assert(r.modePerDay.values.forall(_.isLeft))
  }

  test("charging time is excluded") {
    val (rs, sp) = synth(7, 5)
    // Readings while charging rise to 100%: had they been used, the fit would be far off.
    assert(rs.exists(_.charging))
    val f = EnergyModel.fit(rs, sp).toOption.get
    assertNear("baseline", f.baseline.perHour, Base, 0.08)
  }

  test("per-day estimates: actual recording and per mode from the typical day") {
    val (rs, sp) = synth(7, 6)
    val r = EnergyModel.analyse(rs, sp)
    val u = r.usage
    assertNear("days", u.days, 7, 0.01)
    // Truth: recording terms over the on-battery time, per day.
    val truth = sp.filterNot(_.state.charging).map { s =>
      val st = s.state
      ((if !st.still then ActiveX else 0) + (if st.detail then DetailX else 0) +
        (if st.gnss then GnssX(GnssClass.of(st.gnssIntervalMs)) else 0)) * s.ms / Hour
    }.sum / 7
    assertNear("recording/day", r.recordingPerDay.toOption.get, truth, 0.15 * truth)
    val modes = LoggingMode.values.map(m => r.modePerDay(m).toOption.get)
    assert(modes.sliding(2).forall(p => p(0) < p(1)), modes.mkString(","))
    assert(EnergyModel.describe(r).contains("recording/day="))
  }

  test("policy exposure: still in eco costs nothing beyond the minimum; max runs GNSS at 1 s") {
    val eco = EnergyModel.planExposure(LoggingMode.Eco, MotionState.Stationary, Environment.Outdoor)
    assert(eco(2) == 0.0, eco.mkString(","))
    val max = EnergyModel.planExposure(LoggingMode.Max, MotionState.Stationary, Environment.Outdoor)
    assertNear("max gnss 1s", max(4), 1.0, 1e-9)
    assertNear("max detail", max(3), 1.0, 1e-9)
  }

  test("spans are split at local midnight") {
    val s = EnergySpan(Day0 + 23 * Hour, Day0 + 49 * Hour, quiet)
    val parts = EnergySpan.splitDays(s, Zone)
    assertEquals(parts.map(p => (p.start, p.end)), Seq(
      (Day0 + 23 * Hour, Day0 + 24 * Hour), (Day0 + 24 * Hour, Day0 + 48 * Hour), (Day0 + 48 * Hour, Day0 + 49 * Hour)))
  }

  test("battery files of schema 1 read with nulls in the new columns") {
    val old = Seq(Column("time", ColType.Int64), Column("pct", ColType.Int32), Column("charging", ColType.Int32),
      Column("screen_on", ColType.Int32), Column("mode", ColType.Str))
    val bo = ByteArrayOutputStream()
    val w = ParquetWriter(bo, old)
    w.write(Seq(1000L, 80, 0, 1, "balanced"))
    w.close()
    val rows = Archive.conform(Archive.Battery, ParquetReader.read(bo.toByteArray)).toVector
    assertEquals(rows.head.size, Archive.Battery.columns.size)
    val b = Archive.batteryOf(rows.head)
    assertEquals((b.time, b.pct, b.charging, b.screenOn, b.mode), (1000L, 80, false, true, "balanced"))
    assertEquals((b.chargeUah, b.currentNowUa, b.plug), (None, None, None))
    assert(b.tempC.isNaN)
    // Old and new rows together write as one file of the new schema.
    val now = BatteryReading(2000L, 79, true, false, "eco", Some(3_500_000L), Some(-120_000), Some(-150_000), Some(4100), 31.5f, Some("usb"))
    val bo2 = ByteArrayOutputStream()
    val w2 = ParquetWriter(bo2, Archive.Battery.columns)
    (rows :+ Archive.batteryRow(now).toIndexedSeq).foreach(w2.write)
    w2.close()
    val back = ParquetReader.read(bo2.toByteArray).rowIterator.map(Archive.batteryOf).toVector
    assertEquals(back(1), now)
  }

  test("energy spans round-trip through Parquet") {
    val s = EnergySpan(1, 2, quiet.copy(gnss = true, gnssIntervalMs = 5000, provider = "gps", detail = true))
    val bo = ByteArrayOutputStream()
    val w = ParquetWriter(bo, Archive.EnergyStates.columns)
    w.write(Archive.energyRow(s))
    w.close()
    assertEquals(Archive.energyOf(ParquetReader.read(bo.toByteArray).rowIterator.next()), s)
  }

