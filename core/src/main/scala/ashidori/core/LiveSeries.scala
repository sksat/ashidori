package ashidori.core

/** The last few seconds of a sensor's samples, for live charts: written by a
  * sensor thread, read by the UI thread (every method locks).
  *
  * Times are in nanoseconds on any one clock (the sensor event clock,
  * elapsedRealtimeNanos).
  */
final class LiveSeries(val channels: Int, capacity: Int):
  require(channels >= 1 && capacity >= 2)
  private val ts = new Array[Long](capacity)
  private val vs = new Array[Float](capacity * channels)
  private var head = 0
  private var n = 0

  /** Index of the `i`-th oldest sample in the ring. */
  private def at(i: Int): Int = (head - n + i + capacity) % capacity

  def add(t: Long, v0: Float, v1: Float = 0f, v2: Float = 0f): Unit = synchronized {
    ts(head) = t
    val o = head * channels
    vs(o) = v0
    if channels > 1 then vs(o + 1) = v1
    if channels > 2 then vs(o + 2) = v2
    head = (head + 1) % capacity
    n = math.min(n + 1, capacity)
  }

  def clear(): Unit = synchronized { n = 0 }

  def size: Int = synchronized(n)

  /** Time of the newest sample, or Long.MinValue if none. */
  def lastTime: Long = synchronized { if n == 0 then Long.MinValue else ts(at(n - 1)) }

  /** Value of channel `ch` of the newest sample, NaN if none. */
  def last(ch: Int): Float = synchronized { if n == 0 then Float.NaN else vs(at(n - 1) * channels + ch) }

  /** Value of channel `ch` of the oldest sample at or after `from`, NaN if none. */
  def firstFrom(from: Long, ch: Int): Float = synchronized {
    var i = 0
    while i < n && ts(at(i)) < from do i += 1
    if i < n then vs(at(i) * channels + ch) else Float.NaN
  }

  /** Times of the samples in [from, to], oldest first. */
  def times(from: Long, to: Long): Vector[Long] = synchronized {
    (0 until n).iterator.map(i => ts(at(i))).filter(t => t >= from && t <= to).toVector
  }

  /** Smallest and largest value over all channels in [from, to]; None if empty. */
  def range(from: Long, to: Long): Option[(Float, Float)] = synchronized {
    var lo = Float.PositiveInfinity
    var hi = Float.NegativeInfinity
    for i <- 0 until n do
      val k = at(i)
      if ts(k) >= from && ts(k) <= to then
        for c <- 0 until channels do
          val v = vs(k * channels + c)
          if v < lo then lo = v
          if v > hi then hi = v
    if lo <= hi then Some((lo, hi)) else None
  }

  /** Mean of channel `ch` over [from, ∞); NaN if empty. */
  def mean(from: Long, ch: Int): Double = synchronized {
    var sum = 0.0
    var k = 0
    for i <- 0 until n do
      val j = at(i)
      if ts(j) >= from then
        sum += vs(j * channels + ch)
        k += 1
    if k == 0 then Double.NaN else sum / k
  }

  /** Sample standard deviation of the vector length over [from, ∞); NaN with fewer than 5 samples. */
  def magnitudeStd(from: Long): Float = synchronized {
    var m = 0.0
    var s = 0.0
    var k = 0
    for i <- 0 until n do
      val j = at(i)
      if ts(j) >= from then
        var sq = 0.0
        for c <- 0 until channels do
          val v = vs(j * channels + c).toDouble
          sq += v * v
        val x = math.sqrt(sq)
        // Welford
        k += 1
        val d = x - m
        m += d / k
        s += d * (x - m)
    if k < 5 then Float.NaN else math.sqrt(s / (k - 1)).toFloat
  }

  /** Channel `ch` over [from, to] in `cols` equal columns: per column, the
    * first, smallest, largest and last value, so a chart can draw every
    * sample's extremes however many fall in one pixel. A column after a
    * silence longer than `maxGap` starts a new line (`breakBefore`).
    */
  def envelope(ch: Int, from: Long, to: Long, out: LiveSeries.Envelope, maxGap: Long): Unit = synchronized {
    out.reset()
    val cols = out.cols
    val span = (to - from).toDouble
    var prevT = Long.MinValue
    var col = -1
    for i <- 0 until n do
      val k = at(i)
      val t = ts(k)
      if t >= from && t <= to then
        val v = vs(k * channels + ch)
        val c = math.min(cols - 1, ((t - from) / span * cols).toInt)
        if c != col then
          col = c
          if out.present(c) then
            // Out of order: fold into the column already started.
            ()
          else
            out.present(c) = true
            out.first(c) = v
            out.min(c) = v
            out.max(c) = v
            out.breakBefore(c) = prevT == Long.MinValue || t - prevT > maxGap
        if v < out.min(c) then out.min(c) = v
        if v > out.max(c) then out.max(c) = v
        out.last(c) = v
        prevT = t
  }

  /** Delivered rate over [from, ∞) by the samples' own times, in Hz; NaN
    * with fewer than 3 samples.
    */
  def rateHz(from: Long): Double = synchronized {
    var first = Long.MinValue
    var last = Long.MinValue
    var k = 0
    for i <- 0 until n do
      val t = ts(at(i))
      if t >= from then
        if k == 0 then first = t
        last = t
        k += 1
    if k < 3 || last <= first then Double.NaN else (k - 1) * 1e9 / (last - first)
  }

object LiveSeries:
  /** Per-column result of [[LiveSeries.envelope]], reused across frames. */
  final class Envelope(val cols: Int):
    val present = new Array[Boolean](cols)
    val breakBefore = new Array[Boolean](cols)
    val first = new Array[Float](cols)
    val min = new Array[Float](cols)
    val max = new Array[Float](cols)
    val last = new Array[Float](cols)
    def reset(): Unit =
      java.util.Arrays.fill(present, false)
      java.util.Arrays.fill(breakBefore, false)

  /** [lo, hi] widened in steps of `step` until it holds [dataLo, dataHi]:
    * a fixed scale that grows only when the data leaves it.
    */
  def axisRange(lo: Float, hi: Float, data: Option[(Float, Float)], step: Float): (Float, Float) =
    data match
      case Some((dl, dh)) =>
        val l = if dl < lo then (math.floor(dl / step) * step).toFloat else lo
        val h = if dh > hi then (math.ceil(dh / step) * step).toFloat else hi
        (l, h)
      case None => (lo, hi)

  /** A value with its sign always shown (U+2212 for minus), so columns of
    * values do not shift: 「+0.12」「−9.81」.
    */
  def signed(v: Double, digits: Int): String =
    if v.isNaN then "—"
    else
      val s = String.format(java.util.Locale.ROOT, s"%.${digits}f", math.abs(v))
      // -0.00 reads as +0.00.
      val neg = v < 0 && s.exists(c => c >= '1' && c <= '9')
      (if neg then "−" else "+") + s

/** The heading as the eight points of the compass. */
object Compass:
  val Points: Vector[String] = Vector("北", "北東", "東", "南東", "南", "南西", "西", "北西")

  /** Degrees in [0, 360). */
  def normalize(deg: Double): Double =
    val d = deg % 360.0
    if d < 0 then d + 360.0 else d

  def point(deg: Double): String = Points(((normalize(deg) + 22.5) / 45.0).toInt % 8)

  /** SensorManager.SENSOR_STATUS_* (3 high … 0 unreliable, −1 no contact). */
  def accuracy(status: Int): String = status match
    case 3 => "高"
    case 2 => "中"
    case 1 => "低"
    case _ => "要調整"
