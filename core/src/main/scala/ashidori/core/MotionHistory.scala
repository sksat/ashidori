package ashidori.core

/** What a stretch of time looked like to the accelerometer. */
enum MotionClass(val label: String):
  case Walking extends MotionClass("歩行")
  case Vehicle extends MotionClass("乗り物")
  case Other extends MotionClass("その他")
  /** measured, and the phone was still */
  case Still extends MotionClass("静止")
  /** no measurement: the accelerometer was off (phone judged still) or the logger was not running */
  case Off extends MotionClass("センサー停止")

/** Motion windows summed into equal time buckets, for the history chart. */
object MotionHistory:
  /** Same thresholds as [[MotionStateEstimator]]. */
  val WalkingCadence = 30.0
  val VehicleSpeedMps = 3.5
  val StillAccelStd = 0.25f
  /** How far from a window a speed is still taken to apply. */
  val SpeedReachMs = 60_000L

  final case class Bucket(
      start: Long,
      end: Long,
      /** milliseconds per [[MotionClass]] ordinal, Off included; with
        * [[pendingMs]] sums to end - start
        */
      ms: IArray[Long],
      steps: Int,
      /** mean accelStd of measured windows; NaN without any */
      meanStd: Float,
      /** time not delivered yet (batched): neither measured nor Off */
      pendingMs: Long = 0L
  ):
    def durationMs: Long = math.max(1L, end - start)
    /** Time whose state is known: measured or Off. */
    def knownMs: Long = math.max(0L, end - start - pendingMs)
    def fraction(c: MotionClass): Float = ms(c.ordinal).toFloat / durationMs
    /** The class covering the most time. */
    def dominant: MotionClass = MotionClass.fromOrdinal(ms.indices.maxBy(i => (ms(i), -i)))
    def measuredMs: Long = ms.sum - ms(MotionClass.Off.ordinal)
    /** What a chart shows for the bucket: the most common measured class,
      * or Off when less than a tenth of the known time was measured; None
      * while nothing of it has arrived yet.
      */
    def shown: Option[MotionClass] =
      if knownMs <= 0 then None
      else if measuredMs * 10 < knownMs then Some(MotionClass.Off)
      else Some(MotionClass.values.filter(_ != MotionClass.Off).maxBy(m => ms(m.ordinal)))

  def classify(w: MotionWindow, speedMps: Double): MotionClass =
    if w.steps > 0 && w.cadencePerMin >= WalkingCadence then MotionClass.Walking
    else if !speedMps.isNaN && speedMps > VehicleSpeedMps && w.cadencePerMin < 60 then MotionClass.Vehicle
    else if w.accelStd.isNaN then if w.steps > 0 then MotionClass.Walking else MotionClass.Off
    else if w.accelStd < StillAccelStd && w.steps == 0 then MotionClass.Still
    else MotionClass.Other

  /** Speed (m/s) nearest to `t` within [[SpeedReachMs]]; NaN if none.
    * `speeds` is sorted by time.
    */
  def speedAt(speeds: IndexedSeq[(Long, Float)], t: Long): Double =
    if speeds.isEmpty then Double.NaN
    else
      // first index with time >= t
      var lo = 0
      var hi = speeds.size
      while lo < hi do
        val mid = (lo + hi) >>> 1
        if speeds(mid)._1 < t then lo = mid + 1 else hi = mid
      val near = Seq(lo - 1, lo).filter(i => i >= 0 && i < speeds.size).map(speeds).minBy(s => math.abs(s._1 - t))
      if math.abs(near._1 - t) <= SpeedReachMs then near._2.toDouble else Double.NaN

  /** Buckets of `bucketMs` covering [from, to); the last one ends at `to`.
    * Each window counts toward the bucket holding its midpoint (a window
    * running past `to` counts toward the last bucket). Time not covered by
    * any window is [[MotionClass.Off]], except from `pendingFrom` on, where
    * it has only not been delivered yet.
    */
  def buckets(
      windows: Seq[MotionWindow],
      speeds: IndexedSeq[(Long, Float)],
      from: Long,
      to: Long,
      bucketMs: Long,
      pendingFrom: Long = Long.MaxValue
  ): Vector[Bucket] =
    require(bucketMs > 0 && to > from)
    val n = ((to - from + bucketMs - 1) / bucketMs).toInt
    val ms = Array.fill(n)(new Array[Long](MotionClass.values.length))
    val steps = new Array[Int](n)
    val stdSum = new Array[Double](n)
    val stdN = new Array[Int](n)
    for w <- windows do
      val mid = w.start + (w.end - w.start) / 2
      if mid >= from && w.start < to then
        val i = math.min(n - 1, ((mid - from) / bucketMs).toInt)
        val c = classify(w, speedAt(speeds, mid))
        if c != MotionClass.Off then ms(i)(c.ordinal) += w.durationMs
        steps(i) += w.steps
        if !w.accelStd.isNaN then
          stdSum(i) += w.accelStd
          stdN(i) += 1
    Vector.tabulate(n) { i =>
      val s = from + i * bucketMs
      val e = math.min(to, s + bucketMs)
      val m = ms(i)
      // Windows can overlap a bucket edge; never claim more than the bucket.
      var measured = m.sum
      if measured > e - s then
        val k = (e - s).toDouble / measured
        for j <- m.indices do m(j) = (m(j) * k).toLong
        measured = m.sum
      val pending = math.min((e - s) - measured, math.max(0L, e - math.max(s, pendingFrom)))
      m(MotionClass.Off.ordinal) = (e - s) - measured - pending
      Bucket(s, e, IArray.unsafeFromArray(m), steps(i), if stdN(i) == 0 then Float.NaN else (stdSum(i) / stdN(i)).toFloat,
        pending)
    }

  /** Totals per class over all buckets, in ms. */
  def totals(bs: Seq[Bucket]): Map[MotionClass, Long] =
    MotionClass.values.map(c => c -> bs.map(_.ms(c.ordinal)).sum).toMap
