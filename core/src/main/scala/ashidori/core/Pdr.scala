package ashidori.core

import scala.math.*

/** Pedestrian dead reckoning for indoor walking: from the last good fix,
  * advance one stride per step in the heading direction.
  *
  * The result is an estimate. Its accuracy grows with the distance walked
  * (heading error and stride error accumulate), so the timeline marks long
  * PDR stretches as approximate. A network fix that disagrees by more than
  * its own accuracy pulls the estimate back.
  */
final class Pdr(
    initialStrideM: Double = 0.7,
    /** accuracy grows by this fraction of the distance walked */
    driftPerMeter: Double = 0.08
):
  private var stride = initialStrideM
  private var pos: Option[(Double, Double)] = None
  private var acc = Double.NaN
  private var walked = 0.0

  def strideM: Double = stride
  def active: Boolean = pos.isDefined

  /** Start (or restart) from a known position. */
  def reset(f: Fix): Unit =
    pos = Some((f.lat, f.lon))
    acc = if f.hasAccuracy then f.accuracy.toDouble else 20.0
    walked = 0

  def stop(): Unit = pos = None

  /** Learn the stride from an outdoor walk: distance by GNSS over steps counted. */
  def calibrate(distanceM: Double, steps: Int): Unit =
    if steps >= 50 && distanceM > 30 then
      val s = distanceM / steps
      // Plausible human strides only; smooth towards the new value.
      if s >= 0.4 && s <= 1.2 then stride = stride * 0.7 + s * 0.3

  /** One step at `time` heading `headingDeg` (0 = north, clockwise). */
  def onStep(time: Long, headingDeg: Double): Option[Fix] =
    pos.map { (lat, lon) =>
      val h = toRadians(headingDeg)
      val dn = stride * cos(h)
      val de = stride * sin(h)
      val nlat = lat + dn / 111_320.0
      val nlon = lon + de / (111_320.0 * cos(toRadians(lat)))
      pos = Some((nlat, nlon))
      walked += stride
      acc += stride * driftPerMeter
      Fix(time, nlat, nlon, acc.toFloat, Double.NaN, Float.NaN, headingDeg.toFloat, Pdr.Provider)
    }

  /** A coarse (network) fix: if we have drifted outside its circle, trust it. */
  def onNetworkFix(f: Fix): Unit =
    pos.foreach { (lat, lon) =>
      val d = Geo.distance(lat, lon, f.lat, f.lon)
      val fa = if f.hasAccuracy then f.accuracy.toDouble else 50.0
      if d > fa || acc > fa then reset(f)
    }

object Pdr:
  val Provider = "pdr"
