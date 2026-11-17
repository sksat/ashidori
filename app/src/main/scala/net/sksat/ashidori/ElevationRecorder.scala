package net.sksat.ashidori

import android.content.Context
import android.hardware.{Sensor, SensorEvent, SensorEventListener, SensorManager}
import android.location.Location
import android.os.{Build, Handler, SystemClock}
import scala.collection.mutable.ArrayBuffer
import ashidori.core.*

/** Barometric pressure for the elevation estimate ([[Elevation]]). Used on
  * the logger thread; rows are saved through [[LoggerWrites]], the logger's
  * own write path, as soon as a delivered batch is in.
  *
  * The barometer is a non-wakeup sensor read at 1 Hz with a long report
  * latency: the sensor hub keeps the readings and hands them over when the
  * device is awake anyway, so it never wakes the device. While moving the
  * latency follows the accelerometer's batching (deliveries coincide);
  * while still it is 60 s. [[PressureDecimator]] thins what is stored.
  * No permission is needed.
  */
final class PressureRecorder(sm: SensorManager, handler: Handler):
  private val sensor: Option[Sensor] =
    Option(sm.getDefaultSensor(Sensor.TYPE_PRESSURE, false)).orElse(Option(sm.getDefaultSensor(Sensor.TYPE_PRESSURE)))
      .filterNot(_.isWakeUpSensor)
  private val decimator = PressureDecimator()
  /** Samples not handed over yet: at most one delivered batch. */
  private val pending = ArrayBuffer.empty[PressureSample]
  /** Report latency registered, µs; -1 = not registered. */
  private var latencyUs = -1
  private var moving = false

  def available: Boolean = sensor.isDefined

  /** (Re-)registers for the current state; a no-op while nothing changed. */
  def update(moving: Boolean, accelBatchMs: Long): Unit =
    this.moving = moving
    val want = (if moving then math.min(math.max(accelBatchMs, 10_000L), 60_000L) else 60_000L).toInt * 1000
    sensor.foreach { s =>
      if want != latencyUs then
        if latencyUs >= 0 then sm.unregisterListener(listener, s)
        latencyUs = if sm.registerListener(listener, s, 1_000_000, want, handler) then want else -1
        if latencyUs < 0 then Log.w("barometer registration refused")
    }

  def stop(): Unit =
    sensor.foreach(s => if latencyUs >= 0 then sm.unregisterListener(listener, s))
    latencyUs = -1
    decimator.flush().foreach(pending += _)
    write()

  private def write(): Unit =
    if pending.nonEmpty then
      val ps = pending.toVector
      pending.clear()
      LoggerWrites.small(SmallRecord.Pressure(ps))

  private val writeNow: Runnable = () => write()

  private val listener = new SensorEventListener:
    override def onAccuracyChanged(sensor: Sensor, accuracy: Int): Unit = ()
    override def onSensorChanged(e: SensorEvent): Unit =
      val now = System.currentTimeMillis()
      val wall = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
      decimator.add(wall, e.values(0), moving).foreach { s =>
        pending += s
        // A batch is delivered in one go: hand it over right after its last
        // reading (the next message on this thread), not per reading.
        if !handler.hasCallbacks(writeNow) then handler.post(writeNow)
      }

/** Altitude above mean sea level for fixes (Android 14+).
  *
  * Android reports the WGS84 ellipsoidal height; the geoid model of
  * `AltitudeConverter` gives the MSL altitude. It loads the model's tiles
  * from the platform's assets on first use in an area (disk I/O, so never
  * on the main thread) and keeps them cached; later conversions are
  * arithmetic. A fix that already has an MSL altitude keeps it.
  */
final class MslAltitude(ctx: Context):
  private lazy val converter = android.location.altitude.AltitudeConverter()
  private var loggedFirst = false

  /** (MSL altitude, its accuracy), NaN where unknown. */
  def of(l: Location): (Double, Float) =
    if Build.VERSION.SDK_INT < 34 || !l.hasAltitude then (Double.NaN, Float.NaN)
    else
      if !l.hasMslAltitude then
        val t0 = SystemClock.elapsedRealtimeNanos()
        try converter.addMslAltitudeToLocation(ctx, l)
        catch
          case e: java.io.IOException          => Log.w("MSL altitude: model not loaded", e)
          case _: IllegalArgumentException     => ()
        if !loggedFirst then
          loggedFirst = true
          Log.i(f"MSL altitude: first conversion took ${(SystemClock.elapsedRealtimeNanos() - t0) / 1e6}%.1f ms")
      (if l.hasMslAltitude then l.getMslAltitudeMeters else Double.NaN,
        if l.hasMslAltitudeAccuracy then l.getMslAltitudeAccuracyMeters else Float.NaN)
