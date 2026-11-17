package ashidori.core

/** Does positioning get worse in the background? Median fix interval and
  * accuracy of the recorded fixes, split by screen on/off and by source.
  *
  * The screen state at a fix comes from the [[EnergySpan]] covering it, or,
  * for days before spans were recorded, from the latest battery reading at
  * most 10 minutes earlier. Fixes while charging are left out (the policy
  * logs harder then). An interval is the time since the previous fix of the
  * same source in the same screen state; gaps over 10 minutes are GNSS or
  * positioning being off on purpose (still), not a slow interval.
  */
object FixQuality:
  enum Source(val label: String):
    case Gnss extends Source("GNSS")
    case Network extends Source("省電力測位")

  def sourceOf(provider: String): Option[Source] = provider match
    case "gps"               => Some(Source.Gnss)
    case "fused" | "network" => Some(Source.Network)
    case _                   => None

  /** Medians of one group; `requestedS` is the median requested interval (NaN without spans). */
  final case class Cell(intervals: Int, intervalS: Double, fixes: Int, accuracyM: Double, requestedS: Double)

  /** None where there are fewer than [[MinIntervals]] intervals. */
  final case class Result(cells: Map[(Source, Boolean), Option[Cell]]):
    def apply(source: Source, screenOn: Boolean): Option[Cell] = cells.getOrElse((source, screenOn), None)

  val MinIntervals = 20
  private val MaxGapMs = 10 * 60_000L
  private val ReadingMaxAgeMs = 10 * 60_000L

  /** A median over a stream in fixed memory: log-spaced bins over
    * [`lo`, `hi`] (about 3 % wide), each with its count and sum. The median
    * is the mean of the values in the bin that holds it: exact when they
    * are all equal, within the bin's width otherwise.
    */
  final class StreamMedian(lo: Double = 0.01, hi: Double = 1e6, bins: Int = 512):
    private val counts = new Array[Long](bins)
    private val sums = new Array[Double](bins)
    private val logLo = math.log(lo)
    private val scale = bins / (math.log(hi) - logLo)
    var n = 0L
    def add(v: Double): Unit =
      if !v.isNaN then
        val i = ((math.log(math.max(v, lo)) - logLo) * scale).toInt.max(0).min(bins - 1)
        counts(i) += 1
        sums(i) += v
        n += 1
    def median: Double =
      if n == 0 then Double.NaN
      else
        val target = (n - 1) / 2
        var cum = 0L
        var i = 0
        while cum + counts(i) <= target do
          cum += counts(i)
          i += 1
        sums(i) / counts(i)

  /** The analysis over fixes fed in time order ([[add]]), in fixed memory
    * whatever their number: per source and screen state, three
    * [[StreamMedian]]s. The spans and readings (days of them) are held.
    */
  final class Aggregator(spans: Seq[EnergySpan], readings: Seq[BatteryReading]):
    private val sp = spans.filter(_.ms > 0).sortBy(_.start).toIndexedSeq
    private val rd = readings.sortBy(_.time).toIndexedSeq
    private var si = 0
    private var ri = 0
    private final class Group:
      val iv = StreamMedian()
      val acc = StreamMedian()
      val rq = StreamMedian()
    private val groups = (for s <- Source.values.toSeq; on <- Seq(true, false) yield (s, on) -> Group()).toMap
    private val last = scala.collection.mutable.Map.empty[Source, (Long, Boolean)]
    private var lastTime = Long.MinValue

    /** (screen on, charging, requested interval ms or -1) at time t; fixes come in time order. */
    private def stateAt(t: Long): Option[(Boolean, Boolean, Long)] =
      while si < sp.size && sp(si).end <= t do si += 1
      if si < sp.size && sp(si).start <= t then
        val s = sp(si).state
        Some((s.screenOn, s.charging, s.gnssIntervalMs))
      else
        while ri + 1 < rd.size && rd(ri + 1).time <= t do ri += 1
        rd.lift(ri).filter(r => r.time <= t && t - r.time <= ReadingMaxAgeMs).map(r => (r.screenOn, r.charging, -1L))

    /** The next fix; one out of time order is left out. */
    def add(f: Fix): Unit =
      if f.time >= lastTime then
        lastTime = f.time
        for src <- sourceOf(f.provider) do
          stateAt(f.time) match
            case Some((screen, false, req)) =>
              val g = groups((src, screen))
              last.get(src).collect { case (t, s) if s == screen && f.time > t && f.time - t <= MaxGapMs => f.time - t }
                .foreach(gap => g.iv.add(gap / 1000.0))
              if f.hasAccuracy then g.acc.add(f.accuracy.toDouble)
              if req > 0 then g.rq.add(req / 1000.0)
              last(src) = (f.time, screen)
            case _ => last.remove(src)

    def result: Result =
      Result(groups.map { case (k, g) =>
        k -> Option.when(g.iv.n >= MinIntervals)(Cell(g.iv.n.toInt, g.iv.median, g.acc.n.toInt, g.acc.median, g.rq.median))
      })

  def analyse(fixes: Seq[Fix], spans: Seq[EnergySpan], readings: Seq[BatteryReading]): Result =
    val a = Aggregator(spans, readings)
    fixes.sortBy(_.time).foreach(a.add)
    a.result

  def describe(r: Result): String =
    (for s <- Source.values.toSeq; on <- Seq(true, false) yield
      val k = s"${s.toString.toLowerCase}.${if on then "screenOn" else "screenOff"}"
      r(s, on).fold(s"$k=n/a (<$MinIntervals intervals)")(c =>
        f"$k: interval=${c.intervalS}%.1fs (n=${c.intervals}, requested ${c.requestedS}%.1fs) accuracy=${c.accuracyM}%.1fm (n=${c.fixes})")
    ).mkString("\n")
