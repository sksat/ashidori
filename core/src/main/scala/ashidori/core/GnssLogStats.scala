package ashidori.core

/** Summary of one raw GNSS log (GnssLogger text format). */
final case class GnssLogStats(
    /** recorded time, summed over sessions */
    durationMs: Long,
    /** detail sessions in the file (a daily file holds several) */
    sessions: Int,
    /** measurement epochs (distinct TimeNanos) */
    epochs: Int,
    measurements: Int,
    /** constellation name -> (satellites, bands seen) */
    constellations: Map[String, (Int, Set[String])],
    meanCn0: Double,
    /** fraction of measurements with a valid accumulated delta range (carrier phase) */
    adrValidRatio: Double,
    fixes: Int,
    bestAccuracyM: Double
)

object GnssLogStats:
  private def constellation(t: Int): String = t match
    case 1 => "GPS"
    case 3 => "GLONASS"
    case 4 => "QZSS"
    case 5 => "BeiDou"
    case 6 => "Galileo"
    case 7 => "NavIC"
    case 2 => "SBAS"
    case _ => "?"

  private def band(hz: Double): String =
    if hz.isNaN || hz <= 0 then "?" else if hz > 1.55e9 then "L1" else if hz > 1.2e9 then "L2/E6" else "L5"

  /** Reads the header to find column positions, so the summary keeps working
    * if columns are added.
    */
  def summarize(lines: Iterator[String]): GnssLogStats =
    var rawCols = Map.empty[String, Int]
    var fixCols = Map.empty[String, Int]
    val epochs = scala.collection.mutable.HashSet.empty[String]
    val sats = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.Set[(Int, String)]]
    var n = 0
    var cn0Sum = 0.0
    var adr = 0
    var fixes = 0
    var best = Double.PositiveInfinity
    var tMin = Long.MaxValue
    var tMax = Long.MinValue
    var total = 0L
    var sessions = 0
    def closeSession(): Unit =
      if tMax >= tMin then total += tMax - tMin
      tMin = Long.MaxValue
      tMax = Long.MinValue
    def num(a: Array[String], i: Option[Int]): Double =
      i.filter(_ < a.length).flatMap(k => a(k).toDoubleOption).getOrElse(Double.NaN)
    for line <- lines do
      if line.startsWith("# Session") then
        closeSession()
        sessions += 1
      else if line.startsWith("# Raw,") then rawCols = line.drop(2).split(',').zipWithIndex.toMap
      else if line.startsWith("# Fix,") then fixCols = line.drop(2).split(',').zipWithIndex.toMap
      else if line.startsWith("Raw,") && rawCols.nonEmpty then
        val a = line.split(",", -1)
        n += 1
        epochs += a(rawCols("TimeNanos"))
        val t = num(a, rawCols.get("utcTimeMillis")).toLong
        tMin = math.min(tMin, t)
        tMax = math.max(tMax, t)
        val c = constellation(num(a, rawCols.get("ConstellationType")).toInt)
        val sv = num(a, rawCols.get("Svid")).toInt
        sats.getOrElseUpdate(c, scala.collection.mutable.Set.empty) += ((sv, band(num(a, rawCols.get("CarrierFrequencyHz")))))
        val cn0 = num(a, rawCols.get("Cn0DbHz"))
        if !cn0.isNaN then cn0Sum += cn0
        // ADR_STATE_VALID = 1
        val state = num(a, rawCols.get("AccumulatedDeltaRangeState"))
        if !state.isNaN && (state.toInt & 1) != 0 then adr += 1
      else if line.startsWith("Fix,") && fixCols.nonEmpty then
        val a = line.split(",", -1)
        fixes += 1
        val acc = num(a, fixCols.get("AccuracyMeters"))
        if !acc.isNaN then best = math.min(best, acc)
    closeSession()
    GnssLogStats(
      total,
      math.max(sessions, if n > 0 then 1 else 0),
      epochs.size,
      n,
      sats.view.mapValues(s => (s.map(_._1).size, s.map(_._2).toSet)).toMap,
      if n > 0 then cn0Sum / n else Double.NaN,
      if n > 0 then adr.toDouble / n else 0,
      fixes,
      if best.isInfinite then Double.NaN else best
    )
