package ashidori.core

import scala.collection.mutable.ArrayBuffer

/** One stored barometer reading. */
final case class PressureSample(time: Long, hPa: Float)

/** Thins the barometer's ~1 Hz readings to the series that is stored.
  *
  * While moving: the median of each [[movingBucketMs]] bucket. While still:
  * the median of each [[stillBucketMs]] bucket, kept only when it differs
  * from the last stored value by [[stillMinChange]] hPa or more, or when
  * [[stillMaxGapMs]] passed since it. Weather moves the pressure by about
  * 0.1–1 hPa an hour, so a still hour stores a handful of rows.
  *
  * Readings arrive in batches, so times are the readings' own; a reading
  * that goes back in time closes the current bucket.
  */
final class PressureDecimator(
    movingBucketMs: Long = 5_000,
    stillBucketMs: Long = 60_000,
    stillMinChange: Float = 0.05f,
    stillMaxGapMs: Long = 10 * 60_000L
):
  private val times = ArrayBuffer.empty[Long]
  private val values = ArrayBuffer.empty[Float]
  private var bucketStart = 0L
  private var bucketLen = 0L
  private var bucketMoving = false
  private var last: Option[PressureSample] = None

  /** The last value stored (or to be stored). */
  def lastStored: Option[PressureSample] = last

  /** Adds a reading; returns the sample to store, if a bucket closed. */
  def add(time: Long, hPa: Float, moving: Boolean): Option[PressureSample] =
    if !(hPa > 300f && hPa < 1100f) then return None
    val len = if moving then movingBucketMs else stillBucketMs
    val out =
      if times.nonEmpty && (time < bucketStart || time >= bucketStart + bucketLen || moving != bucketMoving) then close()
      else None
    if times.isEmpty then
      bucketStart = time - Math.floorMod(time, len)
      bucketLen = len
      bucketMoving = moving
    times += time
    values += hPa
    out

  /** Closes the open bucket (logging stops, or the state changed). */
  def flush(): Option[PressureSample] = close()

  private def close(): Option[PressureSample] =
    if times.isEmpty then return None
    val order = values.indices.sortBy(values(_))
    val mid = order(order.size / 2)
    val s = PressureSample(times(mid), values(mid))
    times.clear()
    values.clear()
    val keep = bucketMoving || last.forall { l =>
      math.abs(s.hPa - l.hPa) >= stillMinChange || s.time - l.time >= stillMaxGapMs || s.time < l.time
    }
    if keep then
      last = Some(s)
      Some(s)
    else None

/** Elevation above mean sea level over a day, from the barometer and GNSS.
  *
  * The barometer gives precise changes but no level, and the weather moves
  * it by metres an hour; GNSS gives the level (MSL altitude, ±10–20 m) but
  * jumps by metres from fix to fix. [[Elevation.estimate]] keeps the
  * barometer's shape and takes the level from GNSS: elevation is the
  * barometric height plus an offset, and a Kalman filter with an RTS smoother
  * tracks the offset as a random walk (the weather's drift) against the MSL
  * fixes, weighted by their vertical accuracy.
  *
  * Without MSL fixes the barometric height alone is returned (relative only:
  * standard-atmosphere height, off by up to ~100 m with the weather).
  * Without pressure the MSL fixes are smoothed instead, and a larger
  * hysteresis keeps their noise out of the ascent.
  */
object Elevation:
  val StandardHPa = 1013.25

  /** Height in the standard atmosphere for a pressure, metres. */
  def pressureAltitude(hPa: Double, p0: Double = StandardHPa): Double =
    44330.0 * (1.0 - math.pow(hPa / p0, 1.0 / 5.255))

  enum Source:
    /** Barometer shape, GNSS level. */
    case Fused
    /** Barometer only: changes are right, the level is not. */
    case Barometer
    /** GNSS MSL altitude only, smoothed. */
    case Gnss
    case NoData

  final case class Point(time: Long, m: Double, sigmaM: Double)

  final case class Params(
      /** Changes smaller than this are not counted as ascent or descent. */
      hysteresisM: Double = 3.0,
      /** The same without a barometer: GNSS altitude wanders by more. */
      gnssHysteresisM: Double = 10.0,
      /** How fast the weather may move the barometric level (variance per second). */
      driftM2PerS: Double = 16.0 / 3600,
      /** Elevation random walk when only GNSS is there (variance per second). */
      gnssWalkM2PerS: Double = 0.5,
      /** Inside hold spans, the random walks above are scaled by this. */
      holdFactor: Double = 0.05,
      /** GNSS errors are correlated over seconds: one measurement per window. */
      windowMs: Long = 60_000,
      /** Vertical error assumed when a fix does not say. */
      defaultSigmaM: Double = 20.0,
      minSigmaM: Double = 3.0,
      /** Fixes claiming worse than this are left out. */
      maxSigmaM: Double = 60.0,
      /** ... and so are fixes with a worse horizontal accuracy. */
      maxHorizontalM: Double = 100.0,
      /** Innovations beyond this many standard deviations are rejected. */
      gateSigmas: Double = 4.0,
      /** Inside hold spans, a pressure step is the weather when it is smaller
        * than this and slower than [[holdRateMPerMin]]; a faster or larger one
        * (stairs, a lift) is movement.
        */
      holdStepM: Double = 2.0,
      holdRateMPerMin: Double = 2.0,
      /** A pressure sample further than this from a fix does not pair with it. */
      maxPairGapMs: Long = 5 * 60_000L
  )

  /** Estimates the day's elevation.
    *
    * `hold` are spans where the elevation is expected not to change much
    * (stays, still periods): slow pressure changes in them are taken as
    * weather and dropped, and the level is held between fixes.
    */
  def estimate(
      pressure: Seq[PressureSample],
      fixes: Seq[Fix],
      hold: Seq[(Long, Long)] = Nil,
      params: Params = Params()
  ): ElevationProfile =
    val p = params
    val holds = hold.filter((a, b) => b > a).sortBy(_._1).toVector
    val baro = holdCorrected(cleanPressure(pressure), holds, p)
    val meas = fixes.filter(usable(_, p)).sortBy(_.time)
    if baro.nonEmpty then
      val bt = baro.map(_._1).toArray
      // Offset measurements: MSL minus the barometric height at the fix.
      val z = meas.flatMap { f =>
        heightAt(baro, bt, f.time, p.maxPairGapMs).map(h => (f.time, f.msl - h, sigmaOf(f, p)))
      }
      val windows = windowed(z, p.windowMs)
      if windows.isEmpty then
        ElevationProfile(baro.map((t, h) => Point(t, h, Double.NaN)), Source.Barometer, p.hysteresisM)
      else
        val off = smooth(windows, p.driftM2PerS, holds, p)
        val offTimes = off.map(_.t).toArray
        val pts = baro.map { (t, h) =>
          val (b, v) = offsetAt(off, offTimes, t, p.driftM2PerS)
          Point(t, h + b, math.sqrt(v))
        }
        ElevationProfile(pts, Source.Fused, p.hysteresisM)
    else
      val windows = windowed(meas.map(f => (f.time, f.msl, sigmaOf(f, p))), p.windowMs)
      if windows.isEmpty then ElevationProfile(Vector.empty, Source.NoData, p.hysteresisM)
      else
        val s = smooth(windows, p.gnssWalkM2PerS, holds, p)
        ElevationProfile(s.map(e => Point(e.t, e.x, math.sqrt(e.p))), Source.Gnss, p.gnssHysteresisM)

  private def usable(f: Fix, p: Params): Boolean =
    !f.msl.isNaN && !f.msl.isInfinite && (f.accuracy.isNaN || f.accuracy <= p.maxHorizontalM) && sigmaOf(f, p) <= p.maxSigmaM

  private def sigmaOf(f: Fix, p: Params): Double =
    val s =
      if f.mslAccuracy > 0 then f.mslAccuracy.toDouble
      else if f.altAccuracy > 0 then f.altAccuracy.toDouble
      else p.defaultSigmaM
    math.max(s, p.minSigmaM)

  private def cleanPressure(ps: Seq[PressureSample]): Vector[PressureSample] =
    ps.filter(s => s.hPa > 300f && s.hPa < 1100f).sortBy(_.time).foldLeft(Vector.empty[PressureSample]) { (acc, s) =>
      if acc.lastOption.exists(_.time == s.time) then acc else acc :+ s
    }

  private def inHold(holds: Vector[(Long, Long)], a: Long, b: Long): Boolean =
    holds.exists((s, e) => s <= a && b <= e)

  /** Overlap of [a, b) with the hold spans, ms. */
  private def heldMs(holds: Vector[(Long, Long)], a: Long, b: Long): Long =
    holds.iterator.map((s, e) => math.max(0L, math.min(b, e) - math.max(a, s))).sum

  /** Barometric heights, with slow changes inside hold spans taken out. */
  private def holdCorrected(ps: Vector[PressureSample], holds: Vector[(Long, Long)], p: Params): Vector[(Long, Double)] =
    if ps.isEmpty then Vector.empty
    else
      val raw = ps.map(s => pressureAltitude(s.hPa.toDouble))
      val out = Vector.newBuilder[(Long, Double)]
      var h = raw(0)
      out += ((ps(0).time, h))
      for i <- 1 until ps.size do
        val d = raw(i) - raw(i - 1)
        val dtMin = (ps(i).time - ps(i - 1).time) / 60_000.0
        val weather = inHold(holds, ps(i - 1).time, ps(i).time) &&
          math.abs(d) < p.holdStepM && math.abs(d) < p.holdRateMPerMin * dtMin
        if !weather then h += d
        out += ((ps(i).time, h))
      out.result()

  /** Height at `t` by linear interpolation; None when no sample is near. */
  private def heightAt(hs: Vector[(Long, Double)], ts: Array[Long], t: Long, maxGap: Long): Option[Double] =
    val i = java.util.Arrays.binarySearch(ts, t) match
      case k if k >= 0 => return Some(hs(k)._2)
      case k           => -k - 1
    val before = if i > 0 then Some(hs(i - 1)) else None
    val after = if i < hs.size then Some(hs(i)) else None
    (before, after) match
      case (Some((ta, a)), Some((tb, b))) if tb - ta <= 2 * maxGap =>
        Some(a + (b - a) * (t - ta).toDouble / (tb - ta))
      case (Some((ta, a)), _) if t - ta <= maxGap => Some(a)
      case (_, Some((tb, b))) if tb - t <= maxGap => Some(b)
      case _                                      => None

  /** One measurement per window: the precision-weighted mean, with the
    * variance of a typical fix in it (not divided by the count: errors in a
    * window are correlated).
    */
  private final case class Meas(t: Long, z: Double, r: Double)
  private def windowed(z: Seq[(Long, Double, Double)], windowMs: Long): Vector[Meas] =
    z.groupBy(x => Math.floorDiv(x._1, windowMs)).toVector.sortBy(_._1).map { (_, xs) =>
      val w = xs.map(x => 1.0 / (x._3 * x._3))
      val sw = w.sum
      val t = (xs.map(_._1.toDouble).sum / xs.size).round
      Meas(t, xs.zip(w).map((x, wi) => x._2 * wi).sum / sw, xs.size / sw)
    }

  private final case class Est(t: Long, x: Double, p: Double)

  /** Random-walk Kalman filter and RTS smoother over the measurements. */
  private def smooth(ms: Vector[Meas], q: Double, holds: Vector[(Long, Long)], p: Params): Vector[Est] =
    def qOver(a: Long, b: Long): Double =
      val dt = math.max(0L, b - a)
      val held = math.min(dt, heldMs(holds, a, b))
      q * ((dt - held) + held * p.holdFactor) / 1000.0
    // A vague prior: the first measurement sets the level.
    var x = ms(0).z
    var pv = 1e6
    val xf = new Array[Double](ms.size)
    val pf = new Array[Double](ms.size)
    val pp = new Array[Double](ms.size)
    for k <- ms.indices do
      if k > 0 then pv += qOver(ms(k - 1).t, ms(k).t)
      pp(k) = pv
      val m = ms(k)
      val nu = m.z - x
      val s = pv + m.r
      if nu * nu <= p.gateSigmas * p.gateSigmas * s then
        val g = pv / s
        x += g * nu
        pv *= (1 - g)
      xf(k) = x
      pf(k) = pv
    val xs = xf.clone()
    val ps = pf.clone()
    for k <- ms.indices.reverse.drop(1) do
      val c = pf(k) / pp(k + 1)
      xs(k) = xf(k) + c * (xs(k + 1) - xf(k))
      ps(k) = pf(k) + c * c * (ps(k + 1) - pp(k + 1))
    ms.indices.map(k => Est(ms(k).t, xs(k), ps(k))).toVector

  /** The smoothed offset at `t`: linear between estimates (exact for a random
    * walk), flat beyond them with a growing variance.
    */
  private def offsetAt(es: Vector[Est], ts: Array[Long], t: Long, q: Double): (Double, Double) =
    if t <= es.head.t then (es.head.x, es.head.p + q * (es.head.t - t) / 1000.0)
    else if t >= es.last.t then (es.last.x, es.last.p + q * (t - es.last.t) / 1000.0)
    else
      val k = java.util.Arrays.binarySearch(ts, t)
      if k >= 0 then return (es(k).x, es(k).p)
      val (a, b) = (es(-k - 2), es(-k - 1))
      val f = (t - a.t).toDouble / (b.t - a.t)
      // Bridge variance of the random walk between two smoothed points.
      (a.x + (b.x - a.x) * f, a.p + (b.p - a.p) * f + q * (t - a.t) * (b.t - t) / (b.t - a.t) / 1000.0)

/** Total and range of the elevation over a span. */
final case class ElevationStats(ascentM: Double, descentM: Double, minM: Double, maxM: Double, startM: Double, endM: Double)

/** The estimated elevation series of a day and what the timeline needs from it. */
final case class ElevationProfile(points: Vector[Elevation.Point], source: Elevation.Source, hysteresisM: Double):
  def isEmpty: Boolean = points.isEmpty
  /** The level is MSL (not only the changes). */
  def absolute: Boolean = source == Elevation.Source.Fused || source == Elevation.Source.Gnss

  private lazy val times: Array[Long] = points.map(_.time).toArray

  /** Elevation at `t`, interpolated; None outside the series. */
  def at(t: Long): Option[Double] =
    if points.isEmpty || t < points.head.time || t > points.last.time then None
    else
      java.util.Arrays.binarySearch(times, t) match
        case k if k >= 0 => Some(points(k).m)
        case k =>
          val i = -k - 1
          val (a, b) = (points(i - 1), points(i))
          Some(a.m + (b.m - a.m) * (t - a.time).toDouble / (b.time - a.time))

  /** Values over [from, to], with the ends interpolated. */
  private def span(from: Long, to: Long): Vector[Double] =
    val inside = points.filter(p => p.time > from && p.time < to).map(_.m)
    at(from).toVector ++ inside ++ at(to).toVector

  /** Ascent and descent (changes under the hysteresis ignored), min and max over [from, to]. */
  def stats(from: Long, to: Long): Option[ElevationStats] =
    val vs = span(from, to)
    if vs.isEmpty then None
    else
      val (up, down) = ElevationProfile.climb(vs, hysteresisM)
      Some(ElevationStats(up, down, vs.min, vs.max, vs.head, vs.last))

  /** The whole series. */
  def total: Option[ElevationStats] =
    if points.isEmpty then None else stats(points.head.time, points.last.time)

  /** Typical elevation over [from, to] (a visit): the median, or the value at the middle. */
  def level(from: Long, to: Long): Option[Double] =
    val inside = points.filter(p => p.time >= from && p.time <= to).map(_.m).sorted
    if inside.nonEmpty then Some(inside(inside.size / 2)) else at(from + (to - from) / 2)

  /** Per-leg ascent and descent of a day's timeline, and each visit's elevation. */
  def forTimeline(items: Seq[Segment]): ElevationProfile.Day =
    ElevationProfile.Day(
      total,
      items.collect { case t: Trip => t.legs }.flatten.map(l => l -> stats(l.start, l.end)).toVector,
      items.collect { case v: Visit => v -> level(v.start, v.end) }.toVector
    )

object ElevationProfile:
  final case class Day(total: Option[ElevationStats], legs: Vector[(Leg, Option[ElevationStats])], visits: Vector[(Visit, Option[Double])])

  /** Ascent and descent with a dead band: a change counts once the value has
    * moved `hysteresis` or more from the last counted level.
    */
  def climb(vs: Seq[Double], hysteresis: Double): (Double, Double) =
    if vs.isEmpty then (0.0, 0.0)
    else
      var ref = vs.head
      var up = 0.0
      var down = 0.0
      for v <- vs.iterator.drop(1) do
        if v - ref >= hysteresis then
          up += v - ref
          ref = v
        else if ref - v >= hysteresis then
          down += ref - v
          ref = v
      (up, down)
