package ashidori.core

/** User-selectable logging intensity. */
enum LoggingMode(val key: String, val label: String, val level: Int):
  case Eco extends LoggingMode("eco", "省電力", 0)
  case Balanced extends LoggingMode("balanced", "バランス", 1)
  case Precise extends LoggingMode("precise", "高精度", 2)
  /** Explicit "log everything": 1 Hz GNSS with raw measurements, all sensors. */
  case Max extends LoggingMode("max", "全力ログ", 3)

object LoggingMode:
  def fromKey(k: String): LoggingMode = values.find(_.key == k).getOrElse(Balanced)
  def ofLevel(l: Int): LoggingMode = values.find(_.level == l.max(0).min(3)).get

enum MotionState(val label: String):
  case Stationary extends MotionState("静止")
  case OnFoot extends MotionState("徒歩")
  case Vehicle extends MotionState("乗り物")
  case Unknown extends MotionState("不明")

/** Where positions come from. Ordered by power cost. */
enum LocationSource:
  /** no active requests; only piggy-back on other apps' fixes */
  case Passive
  /** fused provider in low-power / balanced mode (Wi-Fi, cell) */
  case Network
  /** GNSS chipset on */
  case Gnss

final case class PowerState(
    batteryPct: Int,
    charging: Boolean,
    powerSave: Boolean,
    /** measured battery drain while the screen was off, %/h; NaN if unknown */
    screenOffDrainPctPerHour: Double = Double.NaN
)

final case class PolicyInput(
    mode: LoggingMode,
    power: PowerState,
    motion: MotionState,
    speedMps: Double,
    now: Long,
    /** start time of the last raw GNSS detail session */
    lastDetailStart: Long,
    /** explicit "detail log now" request is active until this time */
    burstUntil: Long = 0L,
    /** indoor/outdoor estimate from satellite visibility */
    environment: Environment = Environment.Unknown,
    /** when the current motion state began */
    motionSince: Long = 0L
)

final case class SamplingPlan(
    effectiveMode: LoggingMode,
    source: LocationSource,
    intervalMs: Long,
    minDistanceM: Float,
    /** allow the platform to batch fixes and deliver them late (AP stays asleep) */
    maxBatchDelayMs: Long,
    /** record GnssMeasurement / GnssStatus / NMEA / navigation messages */
    rawGnss: Boolean,
    /** accelerometer sampling rate; 0 = off (significant-motion sensor only) */
    accelHz: Int,
    /** sensor FIFO batching latency */
    accelBatchMs: Long,
    reasons: List[String],
    /** re-plan after this many ms (a GNSS probe starts or ends); 0 = no deadline */
    replanInMs: Long = 0L
):
  def describe: String =
    val src = source match
      case LocationSource.Passive => "GNSS停止"
      case LocationSource.Network => s"省電力測位 ${intervalMs / 1000}s"
      case LocationSource.Gnss    => s"GNSS ${intervalMs / 1000.0}s"
    src + (if rawGnss then " + 詳細ログ" else "")

/** Decides how hard to log, trading accuracy for battery.
  *
  * The biggest saving is turning GNSS off while stationary: a significant
  * motion sensor (running on the sensor hub) wakes us up when the user starts
  * moving again. While moving, the interval scales with speed class, and fixes
  * and sensor samples are batched so the application processor can sleep.
  */
object SamplingPolicy:

  final case class Profile(
      footMs: Long,
      vehicleMs: Long,
      /** while stationary: None = passive only */
      stationaryMs: Option[Long],
      detailEveryMovingMs: Long,
      detailEveryStillMs: Long,
      detailDurationMs: Long,
      accelHz: Int,
      batchMs: Long,
      /** screen-off drain we accept before throttling, %/h */
      drainBudget: Double
  )

  private val Min = 60_000L
  /** Indoors, GNSS is tried briefly to notice stepping outside: right when
    * movement starts, every couple of minutes while moving, rarely while
    * still (you cannot walk out without moving first).
    */
  val IndoorProbeMovingEveryMs: Long = 2 * Min
  val IndoorProbeStillEveryMs: Long = 30 * Min
  val IndoorProbeMs: Long = 20_000L
  val profiles: Map[LoggingMode, Profile] = Map(
    LoggingMode.Eco -> Profile(30_000, 15_000, None, 6 * 60 * Min, 12 * 60 * Min, 30_000, 10, 120_000, 1.0),
    LoggingMode.Balanced -> Profile(10_000, 5_000, Some(15 * Min), 30 * Min, 3 * 60 * Min, 60_000, 20, 30_000, 2.0),
    LoggingMode.Precise -> Profile(2_000, 1_000, Some(5 * Min), 10 * Min, 60 * Min, 60_000, 50, 5_000, 4.0),
    LoggingMode.Max -> Profile(1_000, 1_000, Some(1_000), 0, 0, Long.MaxValue, 100, 0, Double.PositiveInfinity)
  )

  def plan(in: PolicyInput): SamplingPlan =
    val reasons = List.newBuilder[String]
    val p = in.power

    // 1. Adjust the user's mode for the power situation.
    var level = in.mode.level
    if p.charging then
      if level < LoggingMode.Precise.level then
        level += 1
        reasons += "充電中のため1段階上げ"
    else if p.batteryPct <= 5 then
      level = 0
      reasons += "電池残量わずか"
    else if in.mode != LoggingMode.Max then
      if p.batteryPct <= 15 || p.powerSave then
        level = 0
        reasons += (if p.powerSave then "省電力モード" else "電池残量15%以下")
      else if p.batteryPct <= 30 && level > 0 then
        level -= 1
        reasons += "電池残量30%以下"
    else if p.batteryPct <= 15 then
      level = LoggingMode.Precise.level
      reasons += "全力ログ中だが電池残量15%以下のため高精度に"
    val mode = LoggingMode.ofLevel(level)
    val prof = profiles(mode)

    // 2. Throttle when measured drain exceeds the budget for this mode.
    val drain = p.screenOffDrainPctPerHour
    val throttle =
      if p.charging || drain.isNaN || drain <= prof.drainBudget * 1.25 then 1.0
      else
        reasons += f"消費 $drain%.1f%%/h が目安 ${prof.drainBudget}%.1f%%/h 超"
        math.min(4.0, drain / prof.drainBudget)

    val burst = in.now < in.burstUntil
    if burst then reasons += "手動の詳細ログ中"

    // 3. Location source and interval from the motion state.
    // Indoors (and not riding something) GNSS mostly burns power for no fix:
    // use Wi-Fi/cell positioning and briefly try GNSS every few minutes to
    // notice stepping outside.
    val indoor = in.environment == Environment.Indoor
    val indoorWalk = indoor && in.motion != MotionState.Vehicle && in.motion != MotionState.Stationary
    val indoorStill = indoor && in.motion == MotionState.Stationary && prof.stationaryMs.isDefined
    // Probe phase counts from the motion change, so the first probe is immediate.
    val probeEvery = if indoorStill then IndoorProbeStillEveryMs else IndoorProbeMovingEveryMs
    val phase = math.floorMod(in.now - in.motionSince, probeEvery)
    val probing = phase < IndoorProbeMs
    val probeApplies = (indoorWalk || indoorStill) && mode != LoggingMode.Max && !burst
    if probeApplies then reasons += "屋内"
    val (source, baseInterval) =
      if mode == LoggingMode.Max || burst then (LocationSource.Gnss, 1_000L)
      else if indoorStill && probing then (LocationSource.Gnss, 1_000L)
      else if indoorWalk then
        if probing then (LocationSource.Gnss, 1_000L)
        else (LocationSource.Network, math.max(prof.footMs * 3, 15_000L))
      else
        in.motion match
          case MotionState.Stationary =>
            prof.stationaryMs match
              case Some(ms) => (LocationSource.Network, ms)
              case None     => (LocationSource.Passive, 30 * Min)
          case MotionState.OnFoot  => (LocationSource.Gnss, prof.footMs)
          case MotionState.Vehicle => (LocationSource.Gnss, prof.vehicleMs)
          case MotionState.Unknown => (LocationSource.Gnss, prof.footMs)
    val interval =
      if source == LocationSource.Gnss && !burst && mode != LoggingMode.Max then (baseInterval * throttle).toLong
      else baseInterval

    // 4. Raw GNSS detail sessions.
    val detailEvery =
      if in.motion == MotionState.Stationary then prof.detailEveryStillMs else prof.detailEveryMovingMs
    val sinceDetail = in.now - in.lastDetailStart
    val rawGnss =
      burst || mode == LoggingMode.Max ||
        (throttle < 2.0 && !(p.batteryPct <= 15 && !p.charging) && in.environment != Environment.Indoor &&
          (sinceDetail < prof.detailDurationMs || sinceDetail >= detailEvery))
    // A detail session needs the GNSS engine running.
    val finalSource = if rawGnss then LocationSource.Gnss else source
    val finalInterval = if rawGnss && finalSource != source then 1_000L else interval

    // 5. Accelerometer: off while stationary (the significant motion sensor
    //    wakes us), batched otherwise.
    val accelHz =
      if mode == LoggingMode.Max || burst then prof.accelHz
      else if in.motion == MotionState.Stationary then 0
      else prof.accelHz
    val batch =
      if mode == LoggingMode.Max || burst then 0L
      else if source == LocationSource.Gnss then math.min(prof.batchMs, finalInterval * 6)
      else prof.batchMs
    val minDist = in.motion match
      case MotionState.Stationary => 20f
      case _                      => 0f

    val replan =
      if probeApplies then if probing then IndoorProbeMs - phase else probeEvery - phase
      else 0L
    SamplingPlan(mode, finalSource, finalInterval, minDist, batch, rawGnss, accelHz, prof.batchMs.min(batch.max(1_000)),
      reasons.result(), replan)

/** Battery drain estimate from periodic samples, ignoring charging and screen-on time. */
object DrainEstimator:
  final case class Sample(time: Long, pct: Int, charging: Boolean, screenOn: Boolean)

  /** %/h over the last `windowMs`, or NaN when there is less than 30 min of usable data. */
  def screenOffDrain(samples: Seq[Sample], now: Long, windowMs: Long = 3 * 3_600_000L): Double =
    val recent = samples.filter(_.time >= now - windowMs).sortBy(_.time)
    var dur = 0L
    var drop = 0.0
    for case Seq(a, b) <- recent.sliding(2) if a.time < b.time do
      if !a.charging && !b.charging && !a.screenOn && !b.screenOn then
        dur += b.time - a.time
        drop += math.max(0, a.pct - b.pct)
    if dur < 30 * 60_000L then Double.NaN else drop / (dur / 3_600_000.0)
