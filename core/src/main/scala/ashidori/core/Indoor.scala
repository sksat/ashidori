package ashidori.core

/** Whether the sky is visible, as far as GNSS can tell. */
enum Environment(val label: String):
  case Unknown extends Environment("不明")
  case Outdoor extends Environment("屋外")
  case Indoor extends Environment("屋内")

/** One summary of GnssStatus: what the receiver sees right now. */
final case class GnssSnapshot(
    time: Long,
    /** satellites seen (distinct constellation + svid) */
    visible: Int,
    /** satellites used in the fix */
    used: Int,
    /** mean C/N0 of the four strongest signals, dB-Hz (0 when none) */
    top4Cn0: Double,
    /** signals on the L5/E5a band */
    l5: Int
)

object GnssSnapshot:
  /** Builds a snapshot from (constellation, svid, cn0, usedInFix, isL5) signals. */
  def of(time: Long, signals: Seq[(Int, Int, Float, Boolean, Boolean)]): GnssSnapshot =
    val sats = signals.map(s => (s._1, s._2)).distinct
    val used = signals.filter(_._4).map(s => (s._1, s._2)).distinct
    val top = signals.map(_._3.toDouble).sorted.reverse.take(4)
    GnssSnapshot(time, sats.size, used.size, if top.isEmpty then 0 else top.sum / top.size, signals.count(_._5))

/** Indoor/outdoor from satellite visibility, with hysteresis so a moment
  * under a bridge or next to a tall building does not flip it.
  *
  * Indoors the strongest signals are weak (roof attenuation of 10-30 dB) and
  * few satellites are usable; outdoors several satellites are above ~30 dB-Hz.
  */
final class IndoorDetector(
    enterIndoorMs: Long = 60_000,
    exitIndoorMs: Long = 20_000,
    noFixIndoorMs: Long = 60_000
):
  private var state = Environment.Unknown
  private var indoorSince = -1L
  private var outdoorSince = -1L
  private var gnssOnSince = -1L
  private var lastFix = -1L

  def current: Environment = state

  private def indoorish(s: GnssSnapshot) = s.used < 4 || s.top4Cn0 < 25
  private def outdoorish(s: GnssSnapshot) = s.used >= 6 && s.top4Cn0 >= 30

  def onGnssStarted(t: Long): Unit = if gnssOnSince < 0 then gnssOnSince = t
  def onGnssStopped(): Unit = gnssOnSince = -1
  def onFix(t: Long): Unit = lastFix = t

  def update(s: GnssSnapshot): Environment =
    val noFix = gnssOnSince >= 0 && s.time - math.max(gnssOnSince, lastFix) >= noFixIndoorMs
    if indoorish(s) || noFix then
      outdoorSince = -1
      if indoorSince < 0 then indoorSince = s.time
      if s.time - indoorSince >= enterIndoorMs then state = Environment.Indoor
    else if outdoorish(s) then
      indoorSince = -1
      if outdoorSince < 0 then outdoorSince = s.time
      if state != Environment.Indoor || s.time - outdoorSince >= exitIndoorMs then state = Environment.Outdoor
    else
      // In between (urban canyon, window seat): keep the current state.
      indoorSince = -1
      outdoorSince = -1
    state
