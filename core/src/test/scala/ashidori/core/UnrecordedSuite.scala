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
    assertEquals(us.size, 1, segs.mkString("\n"))
    val u = us.head
    assertEqualsDouble(u.durationMs / 60_000.0, 180.0, 0.5)
    assertEqualsDouble(u.displacementM, 20_000.0, 300.0)
    // The trips on either side keep their walks; nothing of the silence is a leg.
    assert(legs(segs).forall(l => l.end <= u.start || l.start >= u.end), legs(segs).mkString("\n"))
    assert(!legs(segs).exists(_.mode == TransportMode.Unknown))
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
    assert(TimelineBuilder.isUnrecorded(Unknown, 3 * h, 3 * h, hasMotion = false))
    assert(TimelineBuilder.isUnrecorded(Walking, h, h, hasMotion = false))
    assert(!TimelineBuilder.isUnrecorded(Walking, h, h, hasMotion = true))
    assert(!TimelineBuilder.isUnrecorded(Subway, h, h, hasMotion = false))
    assert(!TimelineBuilder.isUnrecorded(Flying, 3 * h, 3 * h, hasMotion = false))
    // Mostly recorded: a trip, however it was classified.
    assert(!TimelineBuilder.isUnrecorded(Bus, h / 4, h, hasMotion = false))
    assert(!TimelineBuilder.isUnrecorded(Car, 0, h, hasMotion = false))
  }

  test("day totals leave out the stretch without a record") {
    val daySegs = Stats.splitByDay(segs, zone).getOrElse(GapDay.Date, Vector.empty)
    val s = Stats.day(GapDay.Date, daySegs, 0)
    val u = daySegs.collect { case u: Unrecorded => u }.head
    assertEquals(s.unrecordedMs, u.durationMs)
    // Only the walks and the ride: far less than the 20 km covered unrecorded.
    assert(s.totalDistanceM < 10_000, s.toString)
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

  test("a mode correction marks the leg corrected") {
    val t = segs.collect { case t: Trip => t }.find(_.legs.exists(_.mode == TransportMode.Subway)).get
    val l = t.legs.find(_.mode == TransportMode.Subway).get
    val out = TimelineBuilder.applyOverrides(segs, Seq((l.start, l.end, TransportMode.Train)))
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
