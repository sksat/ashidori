package ashidori.core

import java.time.{LocalDate, YearMonth, ZoneId}

/** Stretches without a record (記録なし): their own segment, never a trip,
  * never in the totals.
  */
class UnrecordedSuite extends munit.FunSuite:
  private val zone = GapDay.Zone
  private lazy val day = GapDay.build()
  private lazy val segs = TimelineBuilder.build(day.fixes, day.motion)

  private def legs(ss: Seq[Segment]) = ss.collect { case t: Trip => t.legs }.flatten

  test("hours without a position while the place changed are one Unrecorded segment") {
    val us = segs.collect { case u: Unrecorded => u }
    // The phone off for 3 hours, motion rows without steps, the phone off at a
    // subway's speed, the phone still in a car.
    assertEquals(us.size, 4, segs.mkString("\n"))
    val u = us.maxBy(_.durationMs)
    assertEqualsDouble(u.durationMs / 60_000.0, 180.0, 0.5)
    assertEqualsDouble(u.displacementM, 20_000.0, 300.0)
    // The trips on either side keep their walks; nothing of the silence is a leg.
    for x <- us do assert(legs(segs).forall(l => l.end <= x.start || l.start >= x.end), legs(segs).mkString("\n"))
    assert(!legs(segs).exists(_.mode == TransportMode.Unknown))
  }

  test("a long silence is never a vehicle leg, whatever the accelerometer recorded") {
    assert(!legs(segs).exists(l => l.mode.isVehicle && l.durationMs >= 19 * 60_000L), legs(segs).mkString("\n"))
  }

  test("a silence with steps counted stays a trip (walking)") {
    val silentWalk = legs(segs).find(l => l.durationMs >= 19 * 60_000L && l.durationMs <= 21 * 60_000L)
    assertEquals(silentWalk.map(_.mode), Some(TransportMode.Walking), legs(segs).mkString("\n"))
  }

  test("a short underground ride stays a trip (subway)") {
    assert(legs(segs).exists(_.mode == TransportMode.Subway), legs(segs).mkString("\n"))
  }

  test("a long silence without steps is unrecorded even at walking speed") {
    // 40 min, 3 km east: nothing says the user walked.
    val f0 = Fix(0L, 36.0, 140.0, accuracy = 8f)
    def walk(t0: Long, east: Double) = (0 until 60).map(i => f0.copy(time = t0 + i * 5_000L, lat = 36.0 + i * 7 / 111_320.0, lon = 140.0 + east / 91_000.0))
    val fixes = (walk(0, 0) ++ walk(300_000L + 40 * 60_000L, 3_000)).toVector
    val out = TimelineBuilder.build(fixes)
    assertEquals(out.collect { case u: Unrecorded => u }.size, 1, out.mkString("\n"))
  }

  test("isUnrecorded: evidence of the mode keeps a trip") {
    import TransportMode.*
    val h = 3_600_000L
    def un(m: TransportMode, silent: Long, dur: Long, walked: Boolean = false) =
      TimelineBuilder.isUnrecorded(m, silent, dur, walked)
    assert(un(Unknown, 3 * h, 3 * h, walked = true))
    assert(un(Walking, h, h))
    assert(!un(Walking, h, h, walked = true))
    // Strong evidence only: the speed between the ends makes no subway or car ride.
    assert(un(Subway, h, h))
    assert(un(Car, h, h))
    assert(un(Train, h, h, walked = true))
    assert(!un(Flying, 3 * h, 3 * h))
    // Mostly recorded: a trip, however it was classified.
    assert(!un(Bus, h / 4, h))
    assert(!un(Car, 0, h))
  }

  test("walked: enough steps at a walking rate, not motion rows alone") {
    assert(!TimelineBuilder.walked(0, 0.0))
    assert(!TimelineBuilder.walked(500, Double.NaN))
    assert(!TimelineBuilder.walked(12, 30.0))
    assert(TimelineBuilder.walked(1200, 90.0))
  }

  /** A walk north (5 s fixes, steps), a silence moving `eastM` with the given
    * motion rows, and a walk again.
    */
  private def silenceDay(silenceMs: Long, eastM: Double, motionDuring: (Long, Long) => Seq[MotionWindow]): Vector[Segment] =
    val f0 = Fix(0L, 36.0, 140.0, accuracy = 8f)
    def walk(t0: Long, east: Double) =
      (0 until 60).map(i => f0.copy(time = t0 + i * 5_000L, lat = 36.0 + i * 7 / 111_320.0, lon = 140.0 + east / 91_000.0))
    val a = walk(0, 0)
    val b = walk(a.last.time + silenceMs, eastM)
    val steps = (a ++ b).map(f => MotionWindow(f.time, f.time + 5_000, 1.8f, 0.3f, 9))
    TimelineBuilder.build((a ++ b).toVector, (steps ++ motionDuring(a.last.time, b.head.time)).toVector)
  private def tenSecondRows(from: Long, to: Long, steps: Int, std: Float) =
    (from until to by 10_000L).map(t => MotionWindow(t, t + 10_000, std, 0.1f, steps))
  private def unrecorded(ss: Seq[Segment]) = ss.collect { case u: Unrecorded => u }

  test("exactly 15 min without positions is a silence; 14 min is not") {
    assertEquals(unrecorded(silenceDay(15 * 60_000L, 1_500, (_, _) => Nil)).size, 1)
    assertEquals(unrecorded(silenceDay(14 * 60_000L, 1_500, (_, _) => Nil)).size, 0)
  }

  test("motion rows with zero steps do not make a silence a walk") {
    val out = silenceDay(20 * 60_000L, 1_000, (a, b) => tenSecondRows(a, b, 0, 0.05f))
    assertEquals(unrecorded(out).size, 1, out.mkString("\n"))
  }

  test("the phone still in a car (motion rows, no steps) is unrecorded") {
    // 20 min, 10 km between fixes; the accelerometer recorded all along.
    val out = silenceDay(20 * 60_000L, 10_000, (a, b) => tenSecondRows(a, b, 0, 0.4f))
    assertEquals(unrecorded(out).size, 1, out.mkString("\n"))
  }

  test("a long subway ride with sparse fixes is unrecorded; a short one stays a trip") {
    assertEquals(unrecorded(silenceDay(25 * 60_000L, 12_000, (a, b) => tenSecondRows(a, b, 0, 0.2f))).size, 1)
    val short = silenceDay(8 * 60_000L, 5_000, (a, b) => tenSecondRows(a, b, 0, 0.2f))
    assertEquals(unrecorded(short).size, 0)
    assert(legs(short).exists(_.mode == TransportMode.Subway), legs(short).mkString("\n"))
  }

  test("sensors off while travelling is unrecorded") {
    // 40 min, 3 km: walking pace, but no motion rows at all.
    assertEquals(unrecorded(silenceDay(40 * 60_000L, 3_000, (_, _) => Nil)).size, 1)
  }

  test("the phone switched off while travelling at a subway's speed is unrecorded") {
    // 20 min, 10 km between fixes, no motion rows: nothing says how.
    val out = silenceDay(20 * 60_000L, 10_000, (_, _) => Nil)
    assertEquals(unrecorded(out).size, 1, out.mkString("\n"))
  }

  test("a day with nothing but a stretch without a record is a day without a record") {
    val d = LocalDate.of(2026, 10, 7)
    val ds = d.atStartOfDay(zone).toInstant.toEpochMilli
    // Fixes the evening before and the morning after: the day itself is all silence.
    val f0 = Fix(0L, 36.0, 140.0, accuracy = 8f)
    def walk(t0: Long, east: Double) = (0 until 60).map(i => f0.copy(time = t0 + i * 5_000L, lat = 36.0 + i * 7 / 111_320.0, lon = 140.0 + east / 91_000.0))
    val fixes = (walk(ds - 2 * 3_600_000L, 0) ++ walk(ds + 26 * 3_600_000L, 5_000)).toVector
    val days = Stats.splitByDay(TimelineBuilder.build(fixes), zone)
    assert(days(d).nonEmpty && days(d).forall(_.isInstanceOf[Unrecorded]), days(d).toString)
    assertEquals(Stats.shown(days(d)), Vector.empty)
    assertEquals(Stats.shown(days(d.minusDays(1))), days(d.minusDays(1)))
  }

  test("day totals leave out the stretch without a record") {
    val daySegs = Stats.splitByDay(segs, zone).getOrElse(GapDay.Date, Vector.empty)
    val s = Stats.day(GapDay.Date, daySegs, 0)
    assertEquals(s.unrecordedMs, daySegs.collect { case u: Unrecorded => u.durationMs }.sum)
    // Only the walks and the rides: far less than the 31 km covered unrecorded.
    assert(s.totalDistanceM < 25_000, s.toString)
    assertEquals(s.movingMs, legs(daySegs).filter(_.mode != TransportMode.Still).map(_.durationMs).sum)
    assert(s.movingMs < 2 * 3_600_000L, s.toString)
    assert(!s.distanceByMode.contains(TransportMode.Unknown))
  }

  test("month totals leave it out too") {
    val daySegs = Stats.splitByDay(segs, zone).getOrElse(GapDay.Date, Vector.empty)
    val d = Stats.day(GapDay.Date, daySegs, 0)
    val m = Stats.month(YearMonth.from(GapDay.Date), Seq(d), Nil)
    assertEqualsDouble(m.distanceByMode.values.sum, d.totalDistanceM, 1e-6)
  }

  test("an unrecorded stretch across midnight is clipped into both days") {
    val d0 = LocalDate.of(2026, 10, 6)
    val mid = d0.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
    val u = Unrecorded(mid - 3_600_000L, mid + 7_200_000L, LatLng(36, 140), LatLng(36.1, 140))
    val days = Stats.splitByDay(Seq(u), zone)
    assertEquals(days(d0).map(_.durationMs), Vector(3_600_000L))
    assertEquals(days(d0.plusDays(1)).map(_.durationMs), Vector(7_200_000L))
    assert(days.values.flatten.forall(_.isInstanceOf[Unrecorded]))
  }

  test("a day is partial when an hour or more of it has no record") {
    val ds = GapDay.Date.atStartOfDay(zone).toInstant.toEpochMilli
    val de = GapDay.Date.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
    val home = Visit(ds, de, 36, 140, 20, 100)
    assert(!Stats.partial(Seq(home), ds, de))
    assert(!Stats.partial(Nil, ds, de))
    val daySegs = Stats.splitByDay(segs, zone).getOrElse(GapDay.Date, Vector.empty)
    assert(Stats.partial(daySegs, ds, de))
    // Before the first record and after the last count as missing.
    assertEquals(Stats.missingMs(Seq(Visit(ds + 3_600_000L, de - 3_600_000L, 36, 140, 20, 10)), ds, de), 2 * 3_600_000L)
  }

  test("a correction back to the inferred mode (取り消し) leaves the leg as built") {
    val l = legs(segs).find(_.mode == TransportMode.Subway).get
    // Changed to train, then taken back by a newer correction for the same leg.
    val os = Seq(ModeOverride(l.start, l.end, TransportMode.Train, updatedAt = 1), ModeOverride(l.start, l.end, TransportMode.Subway, updatedAt = 2))
    val got = legs(TimelineBuilder.applyOverrides(segs, os)).find(_.start == l.start).get
    assertEquals(got, l)
    assert(!got.corrected)
    assertEquals(got.confidence, l.confidence)
  }

  test("a correction saved for a leg across midnight applies on both days") {
    val d = LocalDate.of(2026, 10, 7)
    val mid = d.atStartOfDay(zone).toInstant.toEpochMilli
    // Walking 23:50 to 00:20 (5 s fixes, steps), a stay on either side.
    val f0 = Fix(0L, 36.0, 140.0, accuracy = 8f)
    def stay(t0: Long, north: Double) = (0 until 40).map(i => f0.copy(time = t0 + i * 30_000L, lat = 36.0 + north / 111_320.0))
    val before = stay(mid - 30 * 60_000L, 0).takeWhile(_.time < mid - 10 * 60_000L)
    val walk = (0 until 360).map(i => f0.copy(time = mid - 10 * 60_000L + i * 5_000L, lat = 36.0 + i * 6 / 111_320.0))
    val after = stay(walk.last.time + 5_000L, 360 * 6.0)
    val fixes = (before ++ walk ++ after).toVector
    val motion = walk.map(f => MotionWindow(f.time, f.time + 5_000, 1.8f, 0.3f, 9)).toVector
    val built = TimelineBuilder.build(fixes, motion)
    val days = Stats.splitByDay(built, zone)
    def walkOn(day: LocalDate, ss: Map[LocalDate, Vector[Segment]]) = legs(ss(day)).find(_.mode == TransportMode.Walking).get
    for day <- Seq(d.minusDays(1), d) do
      // Edited from this day: saved for the whole leg.
      val clipped = walkOn(day, days)
      assert(clipped.whole.isDefined)
      val corrected = Stats.splitByDay(TimelineBuilder.applyOverrides(built, Seq(ModeOverride(clipped.wholeStart, clipped.wholeEnd, TransportMode.Running))), zone)
      for other <- Seq(d.minusDays(1), d) do
        assertEquals(legs(corrected(other)).find(_.start == walkOn(other, days).start).map(_.mode), Some(TransportMode.Running), s"edited on $day, shown on $other")
  }

  test("corrections win by updatedAt, then device id, then the device's change order") {
    val l = legs(segs).find(_.mode == TransportMode.Subway).get
    def winner(os: ModeOverride*) =
      legs(TimelineBuilder.applyOverrides(segs, os.toVector)).find(_.start == l.start).get.mode
    val (s, e) = (l.start, l.end)
    assertEquals(winner(ModeOverride(s, e, TransportMode.Bus, 5), ModeOverride(s, e, TransportMode.Car, 4)), TransportMode.Bus)
    assertEquals(winner(ModeOverride(s, e, TransportMode.Bus, 5, "a"), ModeOverride(s, e, TransportMode.Car, 5, "b")), TransportMode.Car)
    assertEquals(winner(ModeOverride(s, e, TransportMode.Bus, 5, "a", 9), ModeOverride(s, e, TransportMode.Car, 5, "a", 3)), TransportMode.Bus)
    // The order given does not matter.
    assertEquals(winner(ModeOverride(s, e, TransportMode.Car, 5, "a", 3), ModeOverride(s, e, TransportMode.Bus, 5, "a", 9)), TransportMode.Bus)
  }

  test("a mode correction marks the leg corrected") {
    val t = segs.collect { case t: Trip => t }.find(_.legs.exists(_.mode == TransportMode.Subway)).get
    val l = t.legs.find(_.mode == TransportMode.Subway).get
    val out = TimelineBuilder.applyOverrides(segs, Seq(ModeOverride(l.start, l.end, TransportMode.Train)))
    val got = legs(out).find(_.start == l.start).get
    assertEquals(got.mode, TransportMode.Train)
    assert(got.corrected)
    assert(legs(out).filter(_.start != l.start).forall(!_.corrected))
  }

  test("the export leaves stretches without a record out") {
    val json = TimelineJson.exportTimeline(segs, Map.empty, zone)
    val n = ujson.read(json)("semanticSegments").arr.size
    val expected = segs.map {
      case _: Visit      => 1
      case t: Trip       => t.legs.count(_.mode != TransportMode.Still) + 1
      case _: Unrecorded => 0
    }.sum
    assertEquals(n, expected)
  }
