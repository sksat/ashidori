package ashidori.core

import scala.collection.mutable.ArrayBuffer

/** Detects visits (stay points) from a time-ordered stream of fixes.
  *
  * Based on the classic stay-point algorithm (Li et al. 2008) with a few
  * adjustments for an adaptive logger:
  *  - GNSS is switched off while stationary, so long gaps between fixes at the
  *    same location are treated as continuous presence.
  *  - Isolated outliers (multipath jumps, coarse network fixes) do not break a
  *    stay as long as a later fix returns inside the radius.
  *  - Poor-accuracy fixes are ignored when better ones exist.
  *  - Nearby consecutive stays separated by a short excursion are merged.
  */
final case class StayParams(
    radiusM: Double = 120.0,
    minDurationMs: Long = 5 * 60_000L,
    maxAccuracyM: Double = 200.0,
    /** how many consecutive fixes may lie outside the radius before the stay ends */
    outlierTolerance: Int = 2,
    /** outliers must be followed by a return within this time */
    outlierWindowMs: Long = 3 * 60_000L,
    mergeDistanceM: Double = 150.0,
    mergeGapMs: Long = 10 * 60_000L
)

object StayPointDetector:

  /** A detected stay with the index range [from, to] of fixes it consumed. */
  final case class Stay(visit: Visit, from: Int, to: Int)

  def detect(input: IndexedSeq[Fix], p: StayParams = StayParams()): Vector[Stay] =
    val fixes = input.filter(f => !f.hasAccuracy || f.accuracy <= p.maxAccuracyM)
    // Map filtered indices back to the input so callers can slice trips.
    val origIndex: IndexedSeq[Int] =
      if fixes.size == input.size then input.indices
      else input.indices.filter(i => !input(i).hasAccuracy || input(i).accuracy <= p.maxAccuracyM)

    val stays = ArrayBuffer.empty[Stay]
    var i = 0
    val n = fixes.size
    while i < n do
      val members = ArrayBuffer(fixes(i))
      val acc = Geo.CentroidAccumulator()
      acc += fixes(i)
      var c = fixes(i).latLng
      var j = i + 1
      var last = i
      var continue = true
      while continue && j < n do
        val f = fixes(j)
        if inside(f, c, p) then
          members += f
          acc += f
          last = j
          c = acc.result
          j += 1
        else
          // Look ahead for a return to the cluster within the tolerance.
          var k = j + 1
          var back = -1
          while back < 0 && k < n && k - j <= p.outlierTolerance &&
            fixes(k).time - f.time <= p.outlierWindowMs
          do
            if inside(fixes(k), c, p) then back = k
            k += 1
          if back >= 0 then j = back
          else continue = false
      // Fixes at either end that are clearly moving belong to the trip, not the
      // stay (walking inside the radius on the way in or out).
      var from = i
      var to = last
      while from < to && isMoving(fixes(from)) do from += 1
      while to > from && isMoving(fixes(to)) do to -= 1
      if fixes(to).time - fixes(from).time >= p.minDurationMs then
        val kept = members.filter(f => f.time >= fixes(from).time && f.time <= fixes(to).time)
        stays += Stay(makeVisit(kept.toVector, Geo.centroid(kept)), origIndex(from), origIndex(to))
        i = last + 1
      else i += 1
    merge(stays.toVector, input, p)

  private def isMoving(f: Fix): Boolean = f.hasSpeed && f.speed > 1.0f

  private def inside(f: Fix, c: LatLng, p: StayParams): Boolean =
    // Allow a little extra slack for fixes whose own uncertainty is large.
    val slack = if f.hasAccuracy then math.min(f.accuracy.toDouble, p.radiusM) * 0.5 else 0.0
    Geo.distance(f.lat, f.lon, c.lat, c.lon) <= p.radiusM + slack

  private def makeVisit(members: Vector[Fix], c: LatLng): Visit =
    val dists = members.map(f => Geo.distance(f.lat, f.lon, c.lat, c.lon)).sorted
    val r = dists((dists.size * 0.8).toInt.min(dists.size - 1))
    // More fixes and a tighter cluster -> higher confidence.
    val conf = math.min(0.95, 0.5 + 0.05 * math.min(members.size, 6) + (if r < 50 then 0.1 else 0.0))
    Visit(members.head.time, members.last.time, c.lat, c.lon, math.max(r, 10.0), members.size, None, conf)

  /** Merge consecutive stays at the same spot separated by a short wander
    * (e.g. stepping outside a building), but not by a real excursion.
    */
  private def merge(stays: Vector[Stay], fixes: IndexedSeq[Fix], p: StayParams): Vector[Stay] =
    def excursionM(prev: Stay, next: Stay): Double =
      val c = prev.visit.latLng
      (prev.to to next.from).map(k => Geo.distance(fixes(k).lat, fixes(k).lon, c.lat, c.lon)).maxOption.getOrElse(0.0)
    stays.foldLeft(Vector.empty[Stay]) { (acc, s) =>
      acc.lastOption match
        case Some(prev)
            if s.visit.start - prev.visit.end <= p.mergeGapMs &&
              prev.visit.latLng.distanceTo(s.visit.latLng) <= p.mergeDistanceM &&
              excursionM(prev, s) <= p.radiusM * 2 =>
          val a = prev.visit
          val b = s.visit
          val wa = a.fixCount.toDouble
          val wb = b.fixCount.toDouble
          val merged = Visit(
            a.start,
            b.end,
            (a.lat * wa + b.lat * wb) / (wa + wb),
            (a.lon * wa + b.lon * wb) / (wa + wb),
            math.max(a.radius, b.radius),
            a.fixCount + b.fixCount,
            a.placeId.orElse(b.placeId),
            math.max(a.confidence, b.confidence)
          )
          acc.init :+ Stay(merged, prev.from, s.to)
        case _ => acc :+ s
    }
