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

  private def median(v: Seq[Double]): Double =
    if v.isEmpty then Double.NaN
    else
      val s = v.sorted
      if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  def analyse(fixes: Seq[Fix], spans: Seq[EnergySpan], readings: Seq[BatteryReading]): Result =
    val sp = spans.filter(_.ms > 0).sortBy(_.start).toIndexedSeq
    val rd = readings.sortBy(_.time).toIndexedSeq
    var si = 0
    var ri = 0
    /** (screen on, charging, requested interval ms or -1) at time t; fixes come in time order. */
    def stateAt(t: Long): Option[(Boolean, Boolean, Long)] =
      while si < sp.size && sp(si).end <= t do si += 1
      if si < sp.size && sp(si).start <= t then
        val s = sp(si).state
        Some((s.screenOn, s.charging, s.gnssIntervalMs))
      else
        while ri + 1 < rd.size && rd(ri + 1).time <= t do ri += 1
        rd.lift(ri).filter(r => r.time <= t && t - r.time <= ReadingMaxAgeMs).map(r => (r.screenOn, r.charging, -1L))
    val groups = scala.collection.mutable.Map.empty[(Source, Boolean), (Vector[Double], Vector[Double], Vector[Double])]
      .withDefaultValue((Vector.empty, Vector.empty, Vector.empty))
    val last = scala.collection.mutable.Map.empty[Source, (Long, Boolean)]
    for f <- fixes.sortBy(_.time); src <- sourceOf(f.provider) do
      stateAt(f.time) match
        case Some((screen, false, req)) =>
          val k = (src, screen)
          val (iv, acc, rq) = groups(k)
          val gap = last.get(src).collect { case (t, s) if s == screen && f.time > t && f.time - t <= MaxGapMs => f.time - t }
          groups(k) = (
            gap.fold(iv)(iv :+ _ / 1000.0),
            if f.hasAccuracy then acc :+ f.accuracy.toDouble else acc,
            if req > 0 then rq :+ req / 1000.0 else rq)
          last(src) = (f.time, screen)
        case _ => last.remove(src)
    Result((for s <- Source.values.toSeq; on <- Seq(true, false) yield
      val (iv, acc, rq) = groups((s, on))
      (s, on) -> Option.when(iv.size >= MinIntervals)(Cell(iv.size, median(iv), acc.size, median(acc), median(rq)))
    ).toMap)

  def describe(r: Result): String =
    (for s <- Source.values.toSeq; on <- Seq(true, false) yield
      val k = s"${s.toString.toLowerCase}.${if on then "screenOn" else "screenOff"}"
      r(s, on).fold(s"$k=n/a (<$MinIntervals intervals)")(c =>
        f"$k: interval=${c.intervalS}%.1fs (n=${c.intervals}, requested ${c.requestedS}%.1fs) accuracy=${c.accuracyM}%.1fm (n=${c.fixes})")
    ).mkString("\n")
