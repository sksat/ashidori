package ashidori.core.sim

import ashidori.core.*
import TrajectorySimulator.Truth

/** Compares a built timeline against simulator ground truth. */
object Evaluation:

  final case class Report(
      truthStays: Int,
      detectedVisits: Int,
      matchedStays: Int,
      /** detected visits that do not correspond to any true stay */
      spuriousVisits: Int,
      /** fraction of true moving time whose detected mode equals the truth */
      modeAccuracy: Double,
      /** like modeAccuracy but Car~Bus and Train~Subway count as equal */
      familyAccuracy: Double,
      /** confusion: (truth, detected) -> milliseconds */
      confusion: Map[(TransportMode, TransportMode), Long]
  ):
    def stayRecall: Double = if truthStays == 0 then 1.0 else matchedStays.toDouble / truthStays
    def summary: String =
      val conf = confusion.toSeq.sortBy(-_._2).take(8).map { case ((t, d), ms) =>
        s"${t.key}->${d.key}:${ms / 60000}min"
      }
      f"stays $matchedStays/$truthStays (spurious $spuriousVisits), mode ${modeAccuracy * 100}%.1f%% family ${familyAccuracy * 100}%.1f%% [${conf.mkString(", ")}]"

  def family(m: TransportMode): TransportMode = m match
    case TransportMode.Bus    => TransportMode.Car
    case TransportMode.Subway => TransportMode.Train
    case other                => other

  /** Stays shorter than this are not expected to be detected. */
  val MinExpectedStayMs: Long = 6 * 60_000L

  def evaluate(truth: Seq[Truth], segments: Seq[Segment], maxDistM: Double = 200): Report =
    val visits = segments.collect { case v: Visit => v }
    val expected = truth.filter(t => t.isStay && t.end - t.start >= MinExpectedStayMs)
    def overlaps(t: Truth, v: Visit) = math.min(t.end, v.end) - math.max(t.start, v.start) > 0
    def near(t: Truth, v: Visit) = t.at.exists(_.distanceTo(v.latLng) <= maxDistM)
    val matched = expected.count(t => visits.exists(v => overlaps(t, v) && near(t, v)))
    val spurious = visits.count(v => !truth.exists(t => t.isStay && overlaps(t, v) && near(t, v)))

    // Time-weighted mode agreement over true moving periods.
    val legs = segments.collect { case t: Trip => t.legs }.flatten
    val confusion = scala.collection.mutable.Map.empty[(TransportMode, TransportMode), Long].withDefaultValue(0L)
    var total = 0L
    var good = 0L
    var goodFamily = 0L
    for t <- truth if !t.isStay do
      val span = t.end - t.start
      var covered = 0L
      for l <- legs do
        val ov = math.min(t.end, l.end) - math.max(t.start, l.start)
        if ov > 0 then
          covered += ov
          confusion((t.mode, l.mode)) += ov
          if l.mode == t.mode then good += ov
          if family(l.mode) == family(t.mode) then goodFamily += ov
      // Moving time that ended up inside a visit (or nowhere) counts as wrong.
      if covered < span then confusion((t.mode, TransportMode.Still)) += span - covered
      total += span
    Report(
      expected.size,
      visits.size,
      matched,
      spurious,
      if total == 0 then 1.0 else good.toDouble / total,
      if total == 0 then 1.0 else goodFamily.toDouble / total,
      confusion.toMap
    )
