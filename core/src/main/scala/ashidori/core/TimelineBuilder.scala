package ashidori.core

import scala.collection.mutable.ArrayBuffer
import ModeClassifier.Coarse

final case class TimelineParams(
    stay: StayParams = StayParams(),
    /** trips shorter than this (both distance and time) are dropped as jitter */
    minTripDistanceM: Double = 150.0,
    minTripDurationMs: Long = 2 * 60_000L,
    /** a gap between fixes longer than this is a GNSS outage, not normal sampling */
    gapMs: Long = 90_000L,
    /** legs shorter than this (both) are merged into neighbours */
    minLegDurationMs: Long = 2 * 60_000L,
    minLegDistanceM: Double = 250.0,
    /** a vehicle run shorter than this between two GNSS outages is part of the underground ride */
    maxSurfacingMs: Long = 3 * 60_000L,
    /** faster than this between fixes is a position jump, not movement (~1080 km/h) */
    maxPlausibleSpeedMps: Double = 300.0,
    /** no position for this long while the place changed: 記録なし unless the
      * leg's mode has evidence of its own ([[TimelineBuilder.isUnrecorded]])
      */
    unrecordedMinMs: Long = 15 * 60_000L
)

/** Turns raw fixes and motion windows into a sequence of visits, trips and
  * stretches without a record ([[Unrecorded]]).
  */
object TimelineBuilder:

  def build(
      rawFixes: IndexedSeq[Fix],
      motion: IndexedSeq[MotionWindow] = IndexedSeq.empty,
      p: TimelineParams = TimelineParams()
  ): Vector[Segment] =
    if rawFixes.isEmpty then return Vector.empty
    val fixes = Geo.removeSpikes(rawFixes.sortBy(_.time))
    // A jump faster than any airliner is not travel (a bad fix, a mock or
    // imported location, a device reset): build each continuous run on its
    // own so the jump never counts as distance.
    splitAtJumps(fixes, p.maxPlausibleSpeedMps).flatMap(run => buildRun(run, motion, p))

  /** Splits where consecutive fixes imply an impossible speed. */
  def splitAtJumps(fixes: IndexedSeq[Fix], maxSpeed: Double): Vector[IndexedSeq[Fix]] =
    if fixes.isEmpty then Vector.empty
    else
      val cuts = (1 until fixes.size).filter { i =>
        val a = fixes(i - 1)
        val b = fixes(i)
        val d = Geo.distance(a, b)
        d > 1_000 && d / math.max(1.0, (b.time - a.time) / 1000.0) > maxSpeed
      }
      ((0 +: cuts) :+ fixes.size).sliding(2).collect { case Seq(a, b) => fixes.slice(a, b) }.toVector

  private def buildRun(fixes: IndexedSeq[Fix], motion: IndexedSeq[MotionWindow], p: TimelineParams): Vector[Segment] =
    val stays = StayPointDetector.detect(fixes, p.stay)
    val out = Vector.newBuilder[Segment]
    val motionIndex = MotionIndex(motion)

    def addTrip(from: Int, to: Int, betweenStays: Boolean): Unit =
      if to > from then
        val path = fixes.slice(from, to + 1).toVector
        val dist = Geo.distance(path.head, path.last).max(Geo.pathLength(path) * 0.5)
        val dur = path.last.time - path.head.time
        // Between two distinct visits any movement is real; at the ends of the
        // log it may just be jitter before the first fix settles.
        val minDist = if betweenStays then p.minTripDistanceM / 3 else p.minTripDistanceM
        if dist >= minDist || (dur >= p.minTripDurationMs && dist >= minDist / 2) then
          out ++= splitUnrecorded(path, pieces(path, motionIndex, p))

    var cursor = 0
    for (s, k) <- stays.zipWithIndex do
      addTrip(cursor, s.from, betweenStays = k > 0)
      out += s.visit
      cursor = s.to
    addTrip(cursor, fixes.size - 1, betweenStays = false)
    absorbEdgeStops(out.result())

  /** A trip's legs, with the stretches without a record taken out as
    * [[Unrecorded]] segments between the trips on either side.
    */
  private def splitUnrecorded(path: Vector[Fix], ps: Vector[(Leg, Boolean)]): Vector[Segment] =
    if !ps.exists(_._2) then Vector(Trip(path.head.time, path.last.time, path, ps.map(_._1)))
    else
      def at(t: Long) = path.find(_.time >= t).getOrElse(path.last).latLng
      val out = Vector.newBuilder[Segment]
      var legs = Vector.empty[Leg]
      def flush(): Unit =
        if legs.nonEmpty then
          val (a, b) = (legs.head.start, legs.last.end)
          out += Trip(a, b, path.filter(f => f.time >= a && f.time <= b), legs)
          legs = Vector.empty
      for (l, unrecorded) <- ps do
        if unrecorded then
          flush()
          out += Unrecorded(l.start, l.end, at(l.start), at(l.end))
        else legs :+= l
      flush()
      out.result()

  /** With sparse sampling while stationary, the last fix of a visit may be
    * minutes before departure. A trip that starts (or ends) with a stop is
    * really the visit lasting until departure, so move that time to the visit.
    */
  private def absorbEdgeStops(segs: Vector[Segment]): Vector[Segment] =
    val a = segs.toArray
    for i <- a.indices do
      a(i) match
        case t: Trip if t.legs.size > 1 =>
          var trip = t
          if i > 0 && trip.legs.head.mode == TransportMode.Still then
            a(i - 1) match
              case v: Visit =>
                val cut = trip.legs.head.end
                a(i - 1) = v.copy(end = cut)
                trip = trip.copy(start = cut, legs = trip.legs.tail, path = trip.path.filter(_.time >= cut))
              case _ =>
          if i + 1 < a.length && trip.legs.size > 1 && trip.legs.last.mode == TransportMode.Still then
            a(i + 1) match
              case v: Visit =>
                val cut = trip.legs.last.start
                a(i + 1) = v.copy(start = cut)
                trip = trip.copy(end = cut, legs = trip.legs.init, path = trip.path.filter(_.time <= cut))
              case _ =>
          a(i) = trip
        case _ =>
    a.toVector

  /** Split a trip path into legs of homogeneous transport mode. */
  def legs(path: Vector[Fix], motion: MotionIndex, p: TimelineParams): Vector[Leg] = pieces(path, motion, p).map(_._1)

  /** The legs of a trip path, each with whether it is a stretch without a record. */
  private def pieces(path: Vector[Fix], motion: MotionIndex, p: TimelineParams): Vector[(Leg, Boolean)] =
    if path.size < 2 then return Vector.empty
    val iv = intervals(path, motion, p)
    // Smooth the coarse class with a duration-weighted majority over neighbours.
    val smoothed = iv.indices.map { i =>
      val lo = math.max(0, i - 2)
      val hi = math.min(iv.size - 1, i + 2)
      if iv(i).coarse == Coarse.Gap then Coarse.Gap
      else
        (lo to hi)
          .filter(j => iv(j).coarse != Coarse.Gap)
          .groupMapReduce(j => iv(j).coarse)(j => iv(j).dt.toDouble)(_ + _)
          .maxBy(_._2)
          ._1
    }
    // Runs of equal class: (startInterval, endIntervalInclusive, class)
    var runs = ArrayBuffer.empty[(Int, Int, Coarse)]
    var s = 0
    for i <- 1 to iv.size do
      if i == iv.size || smoothed(i) != smoothed(s) then
        runs += ((s, i - 1, smoothed(s)))
        s = i
    // Absorb short runs into the longer neighbour until stable.
    def runDur(r: (Int, Int, Coarse)) = iv(r._2).end - iv(r._1).start
    def runDist(r: (Int, Int, Coarse)) = (r._1 to r._2).map(iv(_).dist).sum
    var changed = true
    while changed && runs.size > 1 do
      changed = false
      def short(r: (Int, Int, Coarse)) = r._3 match
        case Coarse.Gap => false
        // A stop shorter than a visit is part of the journey (lights, stations).
        case Coarse.Still => runDur(r) < p.stay.minDurationMs
        case _            => runDur(r) < p.minLegDurationMs && runDist(r) < p.minLegDistanceM
      // Absorb the shortest offending run first so that tiny blips go before
      // longer runs get a chance to grow.
      val idx = runs.indices.filter(k => short(runs(k))).minByOption(k => runDur(runs(k)))
      idx.foreach { k =>
        val r = runs(k)
        def moving(j: Int) = runs(j)._3 != Coarse.Still && runs(j)._3 != Coarse.Gap
        val target =
          if k == 0 then 1
          else if k == runs.size - 1 then k - 1
          else if moving(k - 1) != moving(k + 1) then (if moving(k - 1) then k - 1 else k + 1)
          else if runDur(runs(k - 1)) >= runDur(runs(k + 1)) then k - 1
          else k + 1
        val t = runs(target)
        runs(target) = (math.min(r._1, t._1), math.max(r._2, t._2), t._3)
        runs.remove(k)
        changed = true
      }
      // Coalesce neighbours that now share a class.
      val merged = ArrayBuffer.empty[(Int, Int, Coarse)]
      for r <- runs do
        if merged.nonEmpty && merged.last._3 == r._3 then
          merged(merged.size - 1) = (merged.last._1, r._2, r._3)
        else merged += r
      runs = merged
    // A brief vehicle-speed run between two GNSS outages is the same
    // underground ride surfacing (a station near the surface, an
    // above-ground stretch), not a car ride between two subway rides.
    // Walking stays: that is a transfer.
    var k = 1
    while k < runs.size - 1 do
      val r = runs(k)
      val vehicle = r._3 == Coarse.Vehicle || r._3 == Coarse.Mid
      if vehicle && runs(k - 1)._3 == Coarse.Gap && runs(k + 1)._3 == Coarse.Gap && runDur(r) < p.maxSurfacingMs then
        runs(k - 1) = (runs(k - 1)._1, runs(k + 1)._2, Coarse.Gap)
        runs.remove(k, 2)
      else k += 1
    runs.toVector.map { case (a, b, _) => makeLeg(iv.slice(a, b + 1), motion, p) }

  private final case class Interval(
      start: Long,
      end: Long,
      dist: Double,
      speed: Double,
      gap: Boolean,
      /** no position for at least [[TimelineParams.unrecordedMinMs]] while the place changed */
      silent: Boolean,
      /** either end has accuracy worse than 50 m */
      poor: Boolean,
      cadence: Double,
      accelStd: Double,
      coarse: Coarse
  ):
    def dt: Long = end - start

  private def intervals(path: Vector[Fix], motion: MotionIndex, p: TimelineParams): IndexedSeq[Interval] =
    (0 until path.size - 1).map { k =>
      val a = path(k)
      val b = path(k + 1)
      val dt = math.max(1L, b.time - a.time)
      val d = Geo.distance(a, b)
      // A long silence where the place changed is a gap too, at any speed:
      // otherwise a phone off for hours reads as a slow walk.
      val silent = dt >= p.unrecordedMinMs && d >= p.stay.radiusM
      val gap = (dt > p.gapMs && d / (dt / 1000.0) > 2.5) || silent
      // Doppler speed is far better than position differences for short intervals.
      val speed =
        if !gap && a.hasSpeed && b.hasSpeed && dt < 30_000 then (a.speed + b.speed) / 2.0
        else d / (dt / 1000.0)
      // Position noise makes zig-zags; integrate Doppler speed where we can.
      val dist = if !gap && a.hasSpeed && b.hasSpeed && dt < 30_000 then math.min(d, speed * dt / 1000.0) else d
      val m = motion.summary(a.time, b.time)
      val poor = (a.hasAccuracy && a.accuracy > 50) || (b.hasAccuracy && b.accuracy > 50)
      Interval(a.time, b.time, dist, speed, gap, silent, poor, m.cadence, m.accelStd, ModeClassifier.coarse(speed, m.cadence, gap))
    }

  private def makeLeg(iv: IndexedSeq[Interval], motion: MotionIndex, p: TimelineParams): (Leg, Boolean) =
    val start = iv.head.start
    val end = iv.last.end
    val dur = end - start
    val dist = iv.map(_.dist).sum
    val moving = iv.filterNot(_.gap)
    val (med, p90, mx) =
      if moving.isEmpty then
        val v = dist / math.max(1.0, dur / 1000.0)
        (v, v, v)
      else
        val sorted = moving.map(i => (i.speed, i.dt)).sortBy(_._1)
        (weightedQuantile(sorted, 0.5), weightedQuantile(sorted, 0.9), sorted.last._1)
    val stopTime = moving.filter(_.speed < 1.0).map(_.dt).sum
    val gapTime = iv.filter(_.gap).map(_.dt).sum
    val m = motion.summary(start, end)
    val f = LegFeatures(
      dur,
      dist,
      med,
      p90,
      mx,
      m.cadence,
      m.accelStd,
      if dur > 0 then stopTime.toDouble / dur else 0.0,
      if dur > 0 then gapTime.toDouble / dur else 0.0
    )
    val (mode, conf) = ModeClassifier.classify(f)
    val roughTime = iv.filter(i => i.gap || i.poor).map(_.dt).sum
    val silentTime = iv.filter(_.silent).map(_.dt).sum
    (Leg(start, end, mode, dist, m.steps, conf, mx, med, if dur > 0 then roughTime.toDouble / dur else 0.0),
      isUnrecorded(mode, silentTime, dur, f.hasMotion))

  /** A leg that is mostly a long silence is 記録なし, unless something besides
    * the two ends says how the user moved: the speed of an underground ride
    * or a flight, or steps counted meanwhile. A low-confidence mode with
    * such evidence stays a trip (shown as estimated).
    */
  def isUnrecorded(mode: TransportMode, silentMs: Long, durationMs: Long, hasMotion: Boolean): Boolean =
    silentMs > 0 && silentMs * 2 >= durationMs && (mode match
      case TransportMode.Unknown                      => true
      case TransportMode.Subway | TransportMode.Flying => false
      case _                                          => !hasMotion)

  /** A user correction applies to a leg if they overlap by more than half;
    * the leg is then marked [[Leg.corrected]].
    */
  def applyOverrides(segs: Vector[Segment], os: Seq[(Long, Long, TransportMode)]): Vector[Segment] =
    if os.isEmpty then segs
    else segs.map {
      case t: Trip => t.copy(legs = t.legs.map(l => applyOverride(l, os)))
      case s       => s
    }

  def applyOverride(l: Leg, os: Seq[(Long, Long, TransportMode)]): Leg =
    os.find { (s, e, _) =>
      val ov = math.min(e, l.end) - math.max(s, l.start)
      ov > (l.end - l.start) / 2
    }.fold(l)((_, _, m) => l.copy(mode = m, confidence = 1.0, corrected = true))

  private def weightedQuantile(sorted: IndexedSeq[(Double, Long)], q: Double): Double =
    val total = sorted.map(_._2).sum.toDouble
    var acc = 0.0
    sorted
      .find { case (_, w) =>
        acc += w
        acc >= total * q
      }
      .map(_._1)
      .getOrElse(sorted.last._1)

/** Fast lookup of motion summaries for a time range. */
final class MotionIndex(windows: IndexedSeq[MotionWindow]):
  private val sorted = windows.sortBy(_.start).toIndexedSeq

  final case class Summary(steps: Int, cadence: Double, accelStd: Double)

  def summary(from: Long, to: Long): Summary =
    if sorted.isEmpty || to <= from then Summary(0, Double.NaN, Double.NaN)
    else
      var lo = 0
      var hi = sorted.size
      while lo < hi do
        val mid = (lo + hi) >>> 1
        if sorted(mid).end <= from then lo = mid + 1 else hi = mid
      var covered = 0L
      var steps = 0.0
      var stdSum = 0.0
      var i = lo
      while i < sorted.size && sorted(i).start < to do
        val w = sorted(i)
        val ov = math.min(to, w.end) - math.max(from, w.start)
        if ov > 0 then
          val frac = ov.toDouble / w.durationMs
          covered += ov
          steps += w.steps * frac
          stdSum += w.accelStd * ov
        i += 1
      // Require reasonable coverage before trusting the motion data.
      if covered < (to - from) * 0.3 then Summary(steps.round.toInt, Double.NaN, Double.NaN)
      else Summary(steps.round.toInt, steps * 60000.0 / covered, stdSum / covered)
