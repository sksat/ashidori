package ashidori.core

import ashidori.core.parquet.*
import Elevation.Source

class ElevationSuite extends munit.FunSuite:
  private val Min = 60_000L
  private val Hour = 60 * Min
  private val T0 = 1_790_000_000_000L

  /** Pressure at a true elevation `h` when the sea-level pressure is `p0`. */
  private def pressureAt(h: Double, p0: Double): Double = p0 * math.pow(1 - h / 44330.0, 5.255)

  /** A synthetic day: true elevation and sea-level pressure over time, the
    * spans spent still, and what the phone would record.
    */
  private final case class Day(
      elevation: Long => Double,
      p0: Long => Double,
      still: Seq[(Long, Long)],
      end: Long,
      seed: Int = 1,
      gnssSigma: Double = 8.0,
      outliers: Boolean = true
  ):
    private def isStill(t: Long) = still.exists((a, b) => t >= a && t < b)
    /** Recorded as the logger does: 1 Hz through the decimator. */
    lazy val pressure: Vector[PressureSample] =
      val rnd = scala.util.Random(seed)
      val d = PressureDecimator()
      val out = Vector.newBuilder[PressureSample]
      var t = T0
      while t < end do
        val p = pressureAt(elevation(t), p0(t)) + rnd.nextGaussian() * 0.02
        d.add(t, p.toFloat, moving = !isStill(t)).foreach(out += _)
        t += 1000
      d.flush().foreach(out += _)
      out.result()
    /** GNSS while moving (1 Hz), nothing while still (indoors, GNSS off). */
    lazy val fixes: Vector[Fix] =
      val rnd = scala.util.Random(seed + 100)
      // Correlated error: a slow wander plus white noise, and a few gross outliers.
      var wander = 0.0
      (T0 until end by 1000L).filterNot(isStill).map { t =>
        wander = wander * 0.98 + rnd.nextGaussian() * gnssSigma * 0.2
        val bad = outliers && rnd.nextDouble() < 0.01
        val err = wander + rnd.nextGaussian() * gnssSigma * 0.5 + (if bad then 80.0 else 0.0)
        Fix(t, 35.0, 139.0, 5f, elevation(t) + 40, altAccuracy = 10f, msl = elevation(t) + err, mslAccuracy = 12f)
      }.toVector

  /** 1 h at 50 m, climb 600 m in 1.5 h, 1 h at the top, down in 1.5 h, 1 h at the bottom.
    * The weather drops the pressure by 8 hPa over the day (about 70 m of
    * apparent climb on the barometer alone).
    */
  private val climbDay = Day(
    elevation = t =>
      val h = (t - T0).toDouble / Hour
      if h < 1 then 50.0
      else if h < 2.5 then 50 + 600 * (h - 1) / 1.5
      else if h < 3.5 then 650.0
      else if h < 5 then 650 - 600 * (h - 3.5) / 1.5
      else 50.0,
    p0 = t => 1013.0 - 8.0 * (t - T0) / (6.0 * Hour),
    still = Seq((T0, T0 + Hour), (T0 + (2.5 * Hour).toLong, T0 + (3.5 * Hour).toLong), (T0 + 5 * Hour, T0 + 6 * Hour)),
    end = T0 + 6 * Hour
  )

  private def assertNear(actual: Double, expected: Double, tol: Double, what: String)(using munit.Location): Unit =
    assert(math.abs(actual - expected) <= tol, s"$what: $actual, expected $expected ± $tol")

  test("barometric height") {
    assertNear(Elevation.pressureAltitude(1013.25), 0, 1e-9, "sea level")
    assertNear(Elevation.pressureAltitude(pressureAt(1000, 1013.25)), 1000, 0.01, "1000 m")
    assertNear(Elevation.pressureAltitude(pressureAt(1000, 1003.0), 1003.0), 1000, 0.01, "other p0")
  }

  test("climb with noisy GNSS and drifting pressure") {
    val d = climbDay
    val prof = Elevation.estimate(d.pressure, d.fixes, d.still)
    assertEquals(prof.source, Source.Fused)
    assert(prof.absolute)
    val s = prof.total.get
    assertNear(s.ascentM, 600, 20, "ascent")
    assertNear(s.descentM, 600, 20, "descent")
    assertNear(s.maxM, 650, 10, "max")
    assertNear(s.minM, 50, 10, "min")
    // Level throughout: within the reported uncertainty, and close in any case.
    for p <- prof.points do
      val truth = d.elevation(p.time)
      assert(math.abs(p.m - truth) < math.max(12.0, 3 * p.sigmaM), s"at ${(p.time - T0) / Min} min: ${p.m} vs $truth (σ ${p.sigmaM})")
    // Legs and visits of the timeline.
    val up = Leg(T0 + Hour, T0 + (2.5 * Hour).toLong, TransportMode.Walking, 5000, 8000, 0.9, 1.5, 1.0)
    val down = Leg(T0 + (3.5 * Hour).toLong, T0 + 5 * Hour, TransportMode.Walking, 5000, 8000, 0.9, 1.5, 1.0)
    val top = Visit(T0 + (2.5 * Hour).toLong, T0 + (3.5 * Hour).toLong, 35, 139, 50, 10)
    val day = prof.forTimeline(Seq(Trip(up.start, up.end, Vector.empty, Vector(up)), top,
      Trip(down.start, down.end, Vector.empty, Vector(down))))
    val Seq((_, Some(a)), (_, Some(b))) = day.legs: @unchecked
    assertNear(a.ascentM, 600, 20, "leg up: ascent")
    assert(a.descentM < 10, s"leg up: descent ${a.descentM}")
    assertNear(b.descentM, 600, 20, "leg down: descent")
    assert(b.ascentM < 10, s"leg down: ascent ${b.ascentM}")
    assertNear(day.visits.head._2.get, 650, 10, "summit visit")
  }

  test("without the barometer the drift would show as climbing") {
    // The same day without hold spans or GNSS: the weather's 8 hPa is counted.
    val d = climbDay
    val raw = Elevation.estimate(d.pressure, Nil).total.get
    assert(raw.ascentM - raw.descentM > 50, s"net ${raw.ascentM - raw.descentM}")
  }

  test("flat day: weather drift and GNSS noise are not counted as climbing") {
    val d = Day(
      elevation = _ => 30.0,
      p0 = t => 1015.0 - 7.0 * (t - T0) / (12.0 * Hour),
      // Walks of 30 min at 3, 6 and 9 h; still otherwise.
      still = Seq((T0, T0 + 3 * Hour), (T0 + (3.5 * Hour).toLong, T0 + 6 * Hour),
        (T0 + (6.5 * Hour).toLong, T0 + 9 * Hour), (T0 + (9.5 * Hour).toLong, T0 + 12 * Hour)),
      end = T0 + 12 * Hour
    )
    val prof = Elevation.estimate(d.pressure, d.fixes, d.still)
    assertEquals(prof.source, Source.Fused)
    val s = prof.total.get
    assert(s.ascentM < 10, s"ascent ${s.ascentM}")
    assert(s.descentM < 10, s"descent ${s.descentM}")
    assertNear(s.minM, 30, 10, "min")
    assertNear(s.maxM, 30, 10, "max")
    // Stored far less while still: one row per 0.05 hPa of weather (0.6 hPa
    // an hour here), against 720 an hour while moving.
    val stillRows = d.pressure.count(p => d.still.exists((a, b) => p.time >= a && p.time < b))
    assert(stillRows <= 10.5 * 15, s"$stillRows rows while still")
  }

  test("pressure without MSL: relative only") {
    val d = climbDay
    val prof = Elevation.estimate(d.pressure, Nil, d.still)
    assertEquals(prof.source, Source.Barometer)
    assert(!prof.absolute)
    val s = prof.total.get
    // Drift while moving (3 h at 1.3 hPa/h) stays in; it is about 10 m per hour.
    assertNear(s.ascentM, 600, 40, "ascent")
    assertNear(s.descentM, 600, 40, "descent")
    assertNear(s.maxM - s.minM, 600, 40, "range")
  }

  test("MSL without pressure: smoothed GNSS") {
    val d = climbDay
    val prof = Elevation.estimate(Nil, d.fixes, d.still)
    assertEquals(prof.source, Source.Gnss)
    assert(prof.absolute)
    assertEquals(prof.hysteresisM, Elevation.Params().gnssHysteresisM)
    val s = prof.total.get
    assertNear(s.ascentM, 600, 60, "ascent")
    assertNear(s.descentM, 600, 60, "descent")
    assertNear(s.maxM, 650, 20, "max")
    assertNear(s.minM, 50, 20, "min")
    // Flat GNSS-only: the noise stays under the larger hysteresis mostly.
    val flat = Day(_ => 30.0, _ => 1013.0, Nil, T0 + 2 * Hour, seed = 7)
    val f = Elevation.estimate(Nil, flat.fixes).total.get
    assert(f.ascentM < 40 && f.descentM < 40, s"flat: ${f.ascentM} / ${f.descentM}")
  }

  test("no data, and fixes outside the pressure series") {
    val none = Elevation.estimate(Nil, Seq(Fix(T0, 35, 139)))
    assertEquals(none.source, Source.NoData)
    assertEquals(none.total, None)
    assertEquals(none.at(T0), None)
    // Fixes hours away from any pressure sample do not set the level.
    val p = Seq(PressureSample(T0, 1000f), PressureSample(T0 + Min, 1000f))
    val far = Elevation.estimate(p, Seq(Fix(T0 + 5 * Hour, 35, 139, msl = 500)))
    assertEquals(far.source, Source.Barometer)
  }

  test("ascent and descent with hysteresis") {
    assertEquals(ElevationProfile.climb(Seq(0, 2, 0, 2, 0, 2.5), 3), (0.0, 0.0))
    assertEquals(ElevationProfile.climb(Seq(0.0, 1, 2, 3, 4, 5, 6, 3, 0), 3), (6.0, 6.0))
    assertEquals(ElevationProfile.climb(Seq(10.0, 5, 6, 4), 3), (0.0, 5.0))
  }

  test("decimator: medians while moving, rare rows while still") {
    val d = PressureDecimator()
    val moving = (0 until 20).flatMap(i => d.add(i * 1000L, 1000f + (if i == 3 then 5f else 0f), moving = true)).toVector
    // Buckets [0,5) [5,10) [10,15) closed; the spike at 3 s is not a median.
    assertEquals(moving.map(_.time), Vector(2000L, 7000L, 12000L))
    assert(moving.forall(_.hPa == 1000f))
    // Still: a minute's median, skipped while it moves less than 0.05 hPa, kept after 10 min.
    val still = (20 until 20 + 30 * 60).flatMap(i => d.add(i * 1000L, 1000f + i * 0.00001f, moving = false)).toVector ++ d.flush()
    assert(still.size >= 3 && still.size <= 5, s"${still.size} rows in 30 still minutes")
    val falling = PressureDecimator()
    val f = (0 until 10 * 60).flatMap(i => falling.add(i * 1000L, 1000f - i * 0.001f, moving = false)).toVector
    // 0.06 hPa a minute: every minute is kept.
    assertEquals(f.size, 9)
    // Out-of-range readings are ignored.
    assertEquals(PressureDecimator().add(0, Float.NaN, moving = true), None)
  }

  test("archive: new fix columns and pressure round-trip; old files read with NaN") {
    def write(cols: Seq[Column], rows: Seq[Seq[Any]]): java.io.File =
      val f = java.io.File.createTempFile("elevation", ".parquet")
      f.deleteOnExit()
      val w = ParquetWriter(java.io.FileOutputStream(f), cols)
      rows.foreach(w.write)
      w.close()
      f
    def conform(t: Archive.TableDef, f: java.io.File): Vector[IndexedSeq[Any]] =
      scala.util.Using.resource(Archive.open(t, f.getPath))(_.rowIterator.toVector)
    val f = Fix(T0, 35.1, 139.2, 4f, 80.5, 1f, 90f, "gps", altAccuracy = 3f, msl = 41.25, mslAccuracy = 3.5f)
    val g = Fix(T0 + 1000, 35.1, 139.2, 30f, provider = "fused")
    val back = conform(Archive.Fixes, write(Archive.Fixes.columns, Seq(f, g).map(Archive.fixRow))).map(Archive.fixOf).toVector
    assertEquals(back.map(_.toString), Vector(f, g).map(_.toString))
    // A file of schema 1 or 2: the first eight columns only.
    val old = write(Archive.Fixes.columns.take(8), Seq(Archive.fixRow(f).take(8)))
    val o = conform(Archive.Fixes, old).map(Archive.fixOf).toVector.head
    assertEquals(o.altitude, 80.5)
    assert(o.msl.isNaN && o.mslAccuracy.isNaN && o.altAccuracy.isNaN)
    val ps = Seq(PressureSample(T0, 1001.25f), PressureSample(T0 + 5000, 1001.5f))
    val pb = conform(Archive.Pressure, write(Archive.Pressure.columns, ps.map(Archive.pressureRow))).map(Archive.pressureOf).toVector
    assertEquals(pb, ps.toVector)
    assert(Archive.SignalTables.contains(Archive.Pressure))
  }
