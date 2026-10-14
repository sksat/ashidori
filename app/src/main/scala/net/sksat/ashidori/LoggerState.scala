package net.sksat.ashidori

import android.os.{Handler, Looper}
import scala.collection.mutable
import ashidori.core.*

/** One satellite signal from GnssStatus, for the live sky plot. */
final case class SatSignal(
    constellation: Int,
    svid: Int,
    cn0: Float,
    azimuth: Float,
    elevation: Float,
    usedInFix: Boolean,
    carrierHz: Float
):
  /** L5/E5a/B2a band (~1176 MHz) vs L1/E1/B1 (~1575 MHz) etc. */
  def band: String =
    if carrierHz.isNaN || carrierHz <= 0 then "?"
    else if carrierHz > 1.55e9 then "L1"
    else if carrierHz > 1.2e9 then "L2"
    else "L5"

/** Which motion sensors the logger is using right now. */
final case class SensorState(
    /** accelerometer for motion detection; 0 = off */
    accelHz: Int = 0,
    /** how long samples wait in the sensor hub before delivery */
    accelBatchMs: Long = 0,
    /** waiting for the significant-motion interrupt */
    motionTrigger: Boolean = false,
    /** step detector + rotation vector for indoor dead reckoning */
    pdr: Boolean = false,
    /** raw accelerometer / gyroscope / magnetometer rate in a detail log; 0 = not recording */
    imuHz: Int = 0,
    /** when the accelerometer was last turned on (0 = off) */
    accelSince: Long = 0L
)

/** Live state published by the logger service and observed by the UI. All
  * reads/writes happen on the main thread.
  */
object LoggerState:
  var running: Boolean = false
  var plan: Option[SamplingPlan] = None
  var motion: MotionState = MotionState.Unknown
  var environment: Environment = Environment.Unknown
  var lastFix: Option[Fix] = None
  /** Latest fixes, including ones not yet written to the DB (writes are
    * batched for battery), so today's timeline can show them live.
    */
  @volatile var recentFixes: Vector[Fix] = Vector.empty
  var lastWindow: Option[MotionWindow] = None
  /** Windows of the last 10 minutes, including ones not yet in the DB. */
  var recentWindows: Vector[MotionWindow] = Vector.empty
  /** When [[motion]] last changed. */
  var motionSince: Long = 0L
  var satellites: Vector[SatSignal] = Vector.empty
  /** When [[satellites]] last arrived; 0 = never since the receiver turned on. */
  var satellitesAt: Long = 0L
  var detailFile: Option[String] = None
  var detailStartedAt: Long = 0L
  var drainPctPerHour: Double = Double.NaN
  var battery: Int = -1
  var fixesToday: Int = 0
  var rawMeasurementsSeen: Long = 0
  var stepsSession: Int = 0
  /** Step-counter deliveries of the last few minutes: (time of the newest
    * step in the delivery, steps). Batched by the sensor hub, so the newest
    * entry can be minutes old unless [[flushSensors]] asked for it.
    */
  var recentSteps: Vector[(Long, Int)] = Vector.empty
  /** When the step counter's count was last known current (a delivery or a
    * completed flush); 0 = not yet.
    */
  var stepsAt: Long = 0L
  /** Steps since local midnight (DB at service start + counted since); -1 = unknown yet. */
  var stepsToday: Int = -1
  /** Latest gyroscope magnitude in rad/s with its time, while a detail log samples it. */
  @volatile var gyro: Option[(Long, Float)] = None
  /** Asks the logger to deliver batched sensor samples now (set by the service). */
  @volatile var flushSensors: () => Unit = () => ()
  @volatile var sensors: SensorState = SensorState()
  /** Raw IMU samples written by detail logs since the service started. */
  @volatile var imuSamples: Long = 0

  private val listeners = mutable.LinkedHashSet.empty[() => Unit]
  private val main = Handler(Looper.getMainLooper)

  def observe(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  /** Notify observers; safe to call from any thread. */
  def changed(): Unit = main.post(() => listeners.toVector.foreach(_()))

  def onMain(f: => Unit): Unit = main.post(() => f)
