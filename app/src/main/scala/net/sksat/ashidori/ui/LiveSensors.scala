package net.sksat.ashidori.ui

import android.hardware.{Sensor, SensorEvent, SensorEventListener, SensorManager}
import android.os.{Handler, HandlerThread, SystemClock}
import ashidori.core.{Compass, LiveSeries}

/** The phone's sensors for the live display only: registered while the
  * display is on screen, unregistered as soon as it is not. Separate from
  * the logger's own listeners, which this never touches.
  *
  * Samples arrive on a sensor thread and go into ring buffers that the UI
  * reads when it draws. All times are event times (elapsedRealtimeNanos).
  */
final class LiveSensors(sm: SensorManager):
  import LiveSensors.*

  /** Non-wake-up variant where there is one: display sensors never wake the phone. */
  private def find(t: Int): Option[Sensor] =
    Option(sm.getDefaultSensor(t, false)).orElse(Option(sm.getDefaultSensor(t)))

  val accelSensor: Option[Sensor] = find(Sensor.TYPE_ACCELEROMETER)
  val gyroSensor: Option[Sensor] = find(Sensor.TYPE_GYROSCOPE)
  val magSensor: Option[Sensor] = find(Sensor.TYPE_MAGNETIC_FIELD)
  val rotationSensor: Option[Sensor] = find(Sensor.TYPE_ROTATION_VECTOR)
  val pressureSensor: Option[Sensor] = find(Sensor.TYPE_PRESSURE)
  val stepDetector: Option[Sensor] = find(Sensor.TYPE_STEP_DETECTOR)
  val stepCounter: Option[Sensor] = find(Sensor.TYPE_STEP_COUNTER)

  /** Where the heading comes from: the fused rotation vector, else the
    * magnetometer with the accelerometer's gravity.
    */
  val headingSource: HeadingSource =
    if rotationSensor.isDefined then HeadingSource.Rotation
    else if magSensor.isDefined && accelSensor.isDefined then HeadingSource.MagAccel
    else HeadingSource.None

  val accel = LiveSeries(3, 1024)
  val gyro = LiveSeries(3, 1024)
  /** One sample per heading event: the azimuth in degrees. */
  val heading = LiveSeries(1, 256)
  /** Pressure in hPa over the last minute (10 Hz). */
  val pressure = LiveSeries(1, 1024)
  /** One sample per detected step (or step count increase). */
  val steps = LiveSeries(1, 256)

  /** How late each event is delivered (ns), per stream. */
  val accelLatency = LiveSeries(1, 256)
  val gyroLatency = LiveSeries(1, 256)

  @volatile var headingAccuracy: Int = -2
  /** Step sensors could not be registered (no permission). */
  @volatile var stepsDenied = false

  /** The thread events arrive on: one per period on screen, so nothing
    * outlives the display.
    */
  private var thread: Option[HandlerThread] = None

  private var active = false
  def isActive: Boolean = active

  // State of the accelerometer + magnetometer fallback (sensor thread only).
  private val gravity = new Array[Float](3)
  private var hasGravity = false
  private val rot = new Array[Float](9)
  private val orient = new Array[Float](3)
  private var lastCount = -1f

  private val listener = new SensorEventListener:
    override def onAccuracyChanged(s: Sensor, a: Int): Unit =
      if isHeadingSensor(s) then headingAccuracy = a
    override def onSensorChanged(e: SensorEvent): Unit =
      val t = e.timestamp
      val late = SystemClock.elapsedRealtimeNanos() - t
      val v = e.values
      e.sensor.getType match
        case Sensor.TYPE_ACCELEROMETER =>
          accel.add(t, v(0), v(1), v(2))
          accelLatency.add(t, late.toFloat)
          if headingSource == HeadingSource.MagAccel then
            // Gravity: the accelerometer low-passed, so walking does not swing the needle.
            if !hasGravity then { System.arraycopy(v, 0, gravity, 0, 3); hasGravity = true }
            else for i <- 0 until 3 do gravity(i) += 0.1f * (v(i) - gravity(i))
        case Sensor.TYPE_GYROSCOPE =>
          gyro.add(t, v(0), v(1), v(2))
          gyroLatency.add(t, late.toFloat)
        case Sensor.TYPE_ROTATION_VECTOR =>
          SensorManager.getRotationMatrixFromVector(rot, v)
          SensorManager.getOrientation(rot, orient)
          heading.add(t, Compass.normalize(math.toDegrees(orient(0))).toFloat)
          if headingAccuracy == -2 then headingAccuracy = e.accuracy
        case Sensor.TYPE_MAGNETIC_FIELD =>
          if hasGravity && SensorManager.getRotationMatrix(rot, null, gravity, v) then
            SensorManager.getOrientation(rot, orient)
            heading.add(t, Compass.normalize(math.toDegrees(orient(0))).toFloat)
          if headingAccuracy == -2 then headingAccuracy = e.accuracy
        case Sensor.TYPE_PRESSURE =>
          pressure.add(t, v(0))
        case Sensor.TYPE_STEP_DETECTOR =>
          steps.add(t, 1f)
        case Sensor.TYPE_STEP_COUNTER =>
          // The first event is the running total, not a step.
          if lastCount >= 0 && v(0) > lastCount then steps.add(t, v(0) - lastCount)
          lastCount = v(0)
        case _ => ()

  private def isHeadingSensor(s: Sensor): Boolean = headingSource match
    case HeadingSource.Rotation => s.getType == Sensor.TYPE_ROTATION_VECTOR
    case HeadingSource.MagAccel => s.getType == Sensor.TYPE_MAGNETIC_FIELD
    case HeadingSource.None     => false

  /** Registers every display sensor. Main thread. */
  def start(): Unit = if !active then
    active = true
    hasGravity = false
    lastCount = -1f
    val t = HandlerThread("live-sensors")
    t.start()
    thread = Some(t)
    val handler = Handler(t.getLooper)
    def reg(s: Option[Sensor], periodUs: Int): Boolean =
      s.exists(x => sm.registerListener(listener, x, periodUs, 0, handler))
    reg(accelSensor, AccelPeriodUs)
    reg(gyroSensor, AccelPeriodUs)
    headingSource match
      case HeadingSource.Rotation => reg(rotationSensor, HeadingPeriodUs)
      case HeadingSource.MagAccel => reg(magSensor, HeadingPeriodUs)
      case HeadingSource.None     => ()
    reg(pressureSensor, PressurePeriodUs)
    // The step detector marks each step when it happens; the counter only
    // when its count goes up, a little later.
    val stepOk =
      if stepDetector.isDefined then reg(stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
      else reg(stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
    stepsDenied = (stepDetector.isDefined || stepCounter.isDefined) && !stepOk

  /** Unregisters everything. Main thread. */
  def stop(): Unit = if active then
    active = false
    sm.unregisterListener(listener)
    thread.foreach(_.quitSafely())
    thread = None

  /** Delivered rate of `s` over the last 2 s, NaN when nothing came. */
  def rate(s: LiveSeries, now: Long): Double = s.rateHz(now - 2_000_000_000L)

  /** Mean delivery latency in ms over the last 2 s of the busiest stream. */
  def latencyMs(now: Long): Double =
    val from = now - 2_000_000_000L
    val src = if accelLatency.lastTime >= from then accelLatency else gyroLatency
    src.mean(from, 0) / 1e6

object LiveSensors:
  enum HeadingSource:
    case Rotation, MagAccel, None

  /** 50 Hz: enough to see each step, and unbatched (latency 0). */
  val AccelPeriodUs = 20_000
  val HeadingPeriodUs = 40_000
  val PressurePeriodUs = 100_000
