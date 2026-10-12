package ashidori.core

/** Accumulates raw accelerometer samples into [[MotionWindow]]s.
  *
  * Uses Welford's algorithm on |a| so that only O(1) state is kept regardless
  * of the sampling rate. Step counts come from the hardware step counter and
  * are attributed to the window in which they arrive.
  */
final class MotionAccumulator(windowMs: Long = 10_000L):
  private var start = -1L
  private var last = -1L
  private var n = 0L
  private var mean = 0.0
  private var m2 = 0.0
  private var steps = 0

  /** Adds one sample; returns a finished window when `t` crosses the boundary. */
  def add(t: Long, x: Float, y: Float, z: Float): Option[MotionWindow] =
    val done =
      if start >= 0 && t - start >= windowMs then flush()
      else None
    if start < 0 then start = t
    last = t
    val mag = math.sqrt(x * x + y * y + z * z)
    n += 1
    val d = mag - mean
    mean += d / n
    m2 += d * (mag - mean)
    done

  def addSteps(k: Int): Unit = steps += k

  /** Closes the current window, if it has enough samples to mean anything. */
  def flush(): Option[MotionWindow] =
    val w =
      if n >= 5 then
        Some(MotionWindow(start, math.max(last, start + 1), math.sqrt(m2 / (n - 1)).toFloat, (mean - MotionAccumulator.G).toFloat, steps))
      else if steps > 0 && start >= 0 then Some(MotionWindow(start, math.max(last, start + 1), Float.NaN, Float.NaN, steps))
      else None
    start = -1
    n = 0
    mean = 0
    m2 = 0
    steps = 0
    w

object MotionAccumulator:
  final val G = 9.80665

/** Estimates the coarse motion state from recent windows and speed, with
  * hysteresis so that a red light does not switch GNSS off.
  */
final class MotionStateEstimator(
    stillAfterMs: Long = 3 * 60_000L,
    stillAccelStd: Float = 0.25f
):
  private var state: MotionState = MotionState.Unknown
  private var stillSince = -1L

  def current: MotionState = state

  /** Feed a window and the latest known speed (NaN if none). Returns the new state. */
  def update(w: MotionWindow, speedMps: Double): MotionState =
    val still = (w.accelStd.isNaN || w.accelStd < stillAccelStd) && w.steps == 0 &&
      (speedMps.isNaN || speedMps < 0.5)
    if still then
      if stillSince < 0 then stillSince = w.start
      if w.end - stillSince >= stillAfterMs then state = MotionState.Stationary
    else
      stillSince = -1
      state =
        if !speedMps.isNaN && speedMps > 3.5 && w.cadencePerMin < 60 then MotionState.Vehicle
        else if w.steps > 0 || (!speedMps.isNaN && speedMps <= 3.5) then MotionState.OnFoot
        else if state == MotionState.Stationary || state == MotionState.Unknown then MotionState.Unknown
        else state
    state

  /** Called when the significant-motion sensor fires while stationary. */
  def wake(): MotionState =
    stillSince = -1
    if state == MotionState.Stationary then state = MotionState.Unknown
    state
