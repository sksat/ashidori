package ashidori.core

/** Features describing one homogeneous piece of a trip. Speeds in m/s. */
final case class LegFeatures(
    durationMs: Long,
    distanceM: Double,
    medianSpeed: Double,
    p90Speed: Double,
    maxSpeed: Double,
    /** steps per minute from the step counter or accelerometer; NaN when unknown */
    cadence: Double,
    /** mean accelerometer magnitude stddev; NaN when unknown */
    accelStd: Double,
    /** fraction of time spent below 1 m/s */
    stopRatio: Double,
    /** fraction of time that falls in GNSS gaps (e.g. underground) */
    gapRatio: Double
):
  def avgSpeed: Double = if durationMs > 0 then distanceM / (durationMs / 1000.0) else 0.0
  def hasMotion: Boolean = !cadence.isNaN

/** Rule-based transport mode classification.
  *
  * Deliberately simple and explainable: every rule is a threshold that can be
  * tuned from real logs, and the user can correct the result in the UI.
  */
object ModeClassifier:
  import TransportMode.*

  def classify(f: LegFeatures): (TransportMode, Double) =
    if f.durationMs <= 0 then (Unknown, 0.0)
    else if f.medianSpeed > 45 || (f.p90Speed > 70 && f.avgSpeed > 30) then (Flying, 0.85)
    // Mostly without GNSS: a subway ride is short; a long gap (phone off,
    // no signal for hours) says nothing about how we moved.
    else if f.gapRatio > 0.5 && (f.durationMs > 2 * 3_600_000L || f.distanceM > 80_000) then (Unknown, 0.2)
    else if f.gapRatio > 0.5 && f.avgSpeed >= 4 && f.avgSpeed <= 35 then (Subway, 0.55)
    else if f.medianSpeed < 0.4 && f.stopRatio > 0.8 && (!f.hasMotion || f.cadence < 10) then
      (Still, 0.6)
    else if f.hasMotion && f.cadence >= 30 && f.medianSpeed < 3.5 then
      if f.cadence >= 135 && f.medianSpeed >= 2.2 then (Running, 0.75) else (Walking, 0.85)
    else if f.medianSpeed < 1.9 && f.p90Speed < 3.0 then (Walking, if f.hasMotion then 0.6 else 0.7)
    else if f.medianSpeed < 8.5 && f.p90Speed < 12.5 && looksLikeCycling(f) then (Cycling, 0.6)
    else if f.medianSpeed < 3.0 then (Walking, 0.4)
    else vehicle(f)

  private def looksLikeCycling(f: LegFeatures): Boolean =
    if f.hasMotion then f.cadence < 30 && (f.accelStd.isNaN || f.accelStd >= 0.8)
    else f.stopRatio < 0.3

  private def vehicle(f: LegFeatures): (TransportMode, Double) =
    // Express trains sustain speeds that cars rarely do (esp. in Japan).
    if f.p90Speed >= 31 then (Train, 0.65)
    // Rail is smooth: very little vibration at speed.
    else if !f.accelStd.isNaN && f.accelStd < 0.25 && f.medianSpeed > 8 then (Train, 0.5)
    else if f.stopRatio > 0.3 && f.p90Speed < 15 then (Bus, 0.35)
    else (Car, 0.5)

  /** Coarse per-interval class used for splitting a trip into legs before the
    * leg-level classification above.
    */
  enum Coarse:
    case Still, Foot, Mid, Vehicle, Gap

  def coarse(speed: Double, cadence: Double, gap: Boolean): Coarse =
    if gap then Coarse.Gap
    else if speed < 0.5 && (cadence.isNaN || cadence < 10) then Coarse.Still
    else if !cadence.isNaN && cadence >= 30 && speed < 3.5 then Coarse.Foot
    else if speed < 2.0 then Coarse.Foot
    else if speed < 7.0 then Coarse.Mid
    else Coarse.Vehicle
