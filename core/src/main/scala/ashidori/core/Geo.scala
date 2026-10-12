package ashidori.core

import scala.math.*

object Geo:
  final val EarthRadiusM = 6371008.8

  /** Great-circle distance in metres (haversine). */
  def distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    val p1 = toRadians(lat1)
    val p2 = toRadians(lat2)
    val dp = p2 - p1
    val dl = toRadians(lon2 - lon1)
    val a = pow(sin(dp / 2), 2) + cos(p1) * cos(p2) * pow(sin(dl / 2), 2)
    2 * EarthRadiusM * asin(min(1.0, sqrt(a)))

  def distance(a: Fix, b: Fix): Double = distance(a.lat, a.lon, b.lat, b.lon)

  /** Sum of consecutive distances. */
  def pathLength(path: Iterable[Fix]): Double =
    path.iterator.sliding(2).withPartial(false).map { case Seq(a, b) => distance(a, b) }.sum

  /** Initial bearing from a to b in degrees [0, 360). */
  def bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    val p1 = toRadians(lat1)
    val p2 = toRadians(lat2)
    val dl = toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    (toDegrees(atan2(y, x)) + 360) % 360

  /** Weighted centroid; weights are typically 1/accuracy^2. Fine for the small
    * extents (< few km) that stays cover.
    */
  def centroid(fixes: Iterable[Fix]): LatLng =
    val acc = CentroidAccumulator()
    fixes.foreach(acc += _)
    acc.result

  final class CentroidAccumulator:
    private var sw, slat, slon = 0.0
    def +=(f: Fix): Unit =
      val acc = if f.hasAccuracy then max(3.0, f.accuracy.toDouble) else 30.0
      val w = 1.0 / (acc * acc)
      sw += w
      slat += f.lat * w
      slon += f.lon * w
    def result: LatLng = LatLng(slat / sw, slon / sw)

  /** Drops single-fix excursions ("spikes"): a fix that jumps far from its
    * neighbours while the neighbours themselves are close together. Fast
    * straight movement is kept because there the neighbours are far apart too.
    */
  def removeSpikes(path: IndexedSeq[Fix], minJumpM: Double = 80.0): Vector[Fix] =
    if path.size < 3 then path.toVector
    else
      val out = Vector.newBuilder[Fix]
      out += path.head
      for i <- 1 until path.size - 1 do
        val a = path(i - 1)
        val b = path(i)
        val c = path(i + 1)
        val ab = distance(a, b)
        val bc = distance(b, c)
        val ac = distance(a, c)
        val spike = ab > minJumpM && bc > minJumpM && ac < math.min(ab, bc) * 0.5
        if !spike then out += b
      out += path.last
      out.result()

  /** Douglas-Peucker simplification with tolerance in metres, for drawing long tracks. */
  def simplify(path: IndexedSeq[Fix], toleranceM: Double): Vector[Fix] =
    if path.size <= 2 then path.toVector
    else
      val keep = Array.fill(path.size)(false)
      keep(0) = true
      keep(path.size - 1) = true
      val stack = scala.collection.mutable.Stack((0, path.size - 1))
      while stack.nonEmpty do
        val (s, e) = stack.pop()
        var maxD = -1.0
        var idx = -1
        var i = s + 1
        while i < e do
          val d = crossTrackDistance(path(i), path(s), path(e))
          if d > maxD then
            maxD = d
            idx = i
          i += 1
        if idx >= 0 && maxD > toleranceM then
          keep(idx) = true
          stack.push((s, idx))
          stack.push((idx, e))
      path.indices.filter(keep).map(path).toVector

  /** Distance of p from segment a-b using a local equirectangular projection. */
  def crossTrackDistance(p: Fix, a: Fix, b: Fix): Double =
    val k = cos(toRadians(a.lat))
    def xy(f: Fix) = ((f.lon - a.lon) * k * 111320.0, (f.lat - a.lat) * 110540.0)
    val (px, py) = xy(p)
    val (bx, by) = xy(b)
    val len2 = bx * bx + by * by
    if len2 == 0 then sqrt(px * px + py * py)
    else
      val t = max(0.0, min(1.0, (px * bx + py * by) / len2))
      val dx = px - t * bx
      val dy = py - t * by
      sqrt(dx * dx + dy * dy)
