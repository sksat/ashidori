package ashidori.core

import java.time.{LocalDate, ZoneId}

/** Synthetic fast trips, for [[FlyingSuite]] and the web viewer's fixtures
  * ([[WebFixtures]]). [[day]]:
  *  - 09:00 a Shinkansen: 60 m/s out of the station, a tunnel, 200 s at
  *    85 m/s, a tunnel, 60 m/s into the station (smooth, no steps);
  *  - 11:00 a flight: taxiing, a climb to 10 km and 230 m/s for an hour, a
  *    descent, taxiing;
  *  - 15:00 and 16:00 a drive at 12 m/s with 200 s at 90 m/s, then at
  *    150 m/s, in the middle (a GNSS jump below the impossible speed).
  * Stays of 20 min sit between them. Altitudes are GNSS heights: 40 m on
  * the ground, 10 m at the airports.
  */
object FastDay:
  val Zone: ZoneId = ZoneId.of("Asia/Tokyo")
  val Date: LocalDate = LocalDate.of(2026, 10, 9)
  def at(h: Int, m: Int): Long = Date.atTime(h, m).atZone(Zone).toInstant.toEpochMilli

  final case class Out(fixes: Vector[Fix], motion: Vector[MotionWindow])

  /** Moves north (or along `heading`, degrees of latitude and longitude per
    * metre) and records fixes and motion rows.
    */
  final class Builder(var t: Long, var lat: Double, var lon: Double, var alt: Double):
    private val fixes = Vector.newBuilder[Fix]
    private val motion = Vector.newBuilder[MotionWindow]
    var heading: (Double, Double) = (1 / 111_320.0, 0.0)
    private def fix(): Unit = fixes += Fix(t, lat, lon, accuracy = 8f, altitude = alt)
    def stay(minutes: Int): Unit =
      for _ <- 0 until minutes * 2 do
        fix()
        t += 30_000
    /** `seconds` from `v0` to `v1` m/s and from the current altitude to
      * `toAlt`, a fix every `every` s; motion rows of `std` without steps.
      */
    def move(seconds: Int, v0: Double, v1: Double, toAlt: Double = Double.NaN, every: Int = 5, std: Float = 0.15f): Unit =
      val a0 = alt
      val n = seconds / every
      for k <- 0 until n do
        fix()
        motion += MotionWindow(t, t + every * 1000L, std, 0.05f, 0)
        val v = v0 + (v1 - v0) * (k + 0.5) / n
        t += every * 1000L
        lat += heading._1 * v * every
        lon += heading._2 * v * every
        if !toAlt.isNaN then alt = a0 + (toAlt - a0) * (k + 1) / n
    /** No position for `seconds` at `v` m/s. */
    def outage(seconds: Int, v: Double, std: Float = 0.15f): Unit =
      for k <- 0 until seconds / 5 do motion += MotionWindow(t + k * 5_000L, t + (k + 1) * 5_000L, std, 0.05f, 0)
      t += seconds * 1000L
      lat += heading._1 * v * seconds
      lon += heading._2 * v * seconds
    def stayUntil(until: Long): Unit = while t < until do stay(1)
    def result: Out = Out(fixes.result(), motion.result())

  def day(): Out =
    val b = Builder(at(8, 40), 35.0, 139.0, 40.0)
    b.stayUntil(at(9, 0))
    // Shinkansen
    b.move(90, 0, 60)
    b.move(240, 60, 60)
    b.outage(120, 70)
    b.move(200, 85, 85)
    b.outage(120, 70)
    b.move(240, 60, 60)
    b.move(90, 60, 0)
    b.stay(20)
    b.alt = 10.0
    b.stayUntil(at(11, 0))
    // A flight
    b.move(600, 8, 8, std = 0.3f)
    b.move(60, 8, 80, std = 0.3f)
    b.move(1200, 80, 230, toAlt = 10_000, every = 10, std = 0.2f)
    b.move(3600, 230, 230, every = 10, std = 0.2f)
    b.move(1200, 230, 80, toAlt = 10.0, every = 10, std = 0.2f)
    b.move(60, 80, 8, std = 0.3f)
    b.move(600, 8, 8, std = 0.3f)
    b.stay(20)
    b.alt = 40.0
    // Drives with a stretch of jumped positions
    for (h, v) <- Seq(15 -> 90.0, 16 -> 150.0) do
      b.stayUntil(at(h, 0))
      b.move(480, 12, 12, std = 0.4f)
      b.move(200, v, v, std = 0.4f)
      b.move(480, 12, 12, std = 0.4f)
    b.stay(20)
    b.result

  /** Hong Kong to Haneda: 4 h, 2,900 km, cruising at 11 km, a fix only every
    * 5 min (20 min twice) while in the air, half an hour at each airport.
    */
  def hongKongHaneda(): Out =
    val (hkg, hnd) = (LatLng(22.308, 113.918), LatLng(35.549, 139.780))
    val b = Builder(at(8, 0), hkg.lat, hkg.lon, 10.0)
    b.stay(30)
    val flightS = 4 * 3600
    val dist = hkg.distanceTo(hnd)
    b.heading = ((hnd.lat - hkg.lat) / dist, (hnd.lon - hkg.lon) / dist)
    val v = dist / flightS
    var s = 0
    while s < flightS do
      val step = if s == 60 * 60 || s == 2 * 3600 then 1200 else 300
      val climb = math.min(1.0, math.min(s + step, flightS - s - step).max(0) / 1500.0)
      b.alt = 10.0 + 11_000 * climb
      b.move(step, v, v, every = step, std = 0.2f)
      s += step
    b.alt = 10.0
    b.stay(30)
    b.result
