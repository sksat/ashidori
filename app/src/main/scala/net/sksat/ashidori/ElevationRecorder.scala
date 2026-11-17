package net.sksat.ashidori

import android.content.Context
import android.hardware.{Sensor, SensorEvent, SensorEventListener2, SensorManager}
import android.location.Location
import android.os.{Build, Handler, SystemClock}
import scala.collection.mutable.ArrayBuffer
import ashidori.core.*

/** Barometric pressure for the elevation estimate ([[Elevation]]). Used on
  * the logger thread; rows are saved through [[LoggerWrites]], the logger's
  * own write path, as soon as a delivered batch is in.
  *
  * The barometer is a non-wakeup sensor read at 1 Hz with a long report
  * latency (while moving that of the accelerometer's batching, 10 to 60 s;
  * still, 60 s). It never wakes the device for the barometer. What that
  * guarantees and what not:
  *  - Readings delivered to this process are kept: stored at once
  *    (decimated by [[PressureDecimator]]), or in an overflow file.
  *  - Readings not delivered are missing. While the device sleeps the
  *    sensor hub keeps them in its FIFO only until it is full, then
  *    overwrites the oldest; the latency does not wake the device to empty
  *    it. So a sleeping phone has gaps, of a length the FIFO's size
  *    decides (端末診断 reports it), and the data shows them as gaps.
  *  - Changing the latency or stopping first flushes the FIFO and waits
  *    for the flush to complete (or [[FlushTimeoutMs]]), so what the hub
  *    holds is delivered before the listener goes.
  * No permission is needed.
  */
final class PressureRecorder(sm: SensorManager, handler: Handler):
  import PressureRecorder.*
  private val sensor: Option[Sensor] =
    Option(sm.getDefaultSensor(Sensor.TYPE_PRESSURE, false)).orElse(Option(sm.getDefaultSensor(Sensor.TYPE_PRESSURE)))
      .filterNot(_.isWakeUpSensor)
  private val decimator = PressureDecimator()
  /** Samples not handed over yet: at most one delivered batch. */
  private val pending = ArrayBuffer.empty[PressureSample]
  /** Report latency registered, µs; -1 = not registered. */
  private var latencyUs = -1
  /** The latency the current state asks for, µs. */
  private var wantUs = -1
  private var moving = false
  private var flushing = false
  private var afterFlush = Vector.empty[() => Unit]
  private var reregisterQueued = false
  /** Flushes that did not complete in time (in this process). */
  var flushTimeouts = 0

  def available: Boolean = sensor.isDefined

  /** (Re-)registers for the current state; a no-op while nothing changed. A
    * change of latency waits for the FIFO's flush first.
    */
  def update(moving: Boolean, accelBatchMs: Long): Unit =
    this.moving = moving
    wantUs = (if moving then math.min(math.max(accelBatchMs, 10_000L), 60_000L) else 60_000L).toInt * 1000
    sensor.foreach { s =>
      if latencyUs < 0 && !flushing then register(s)
      else if wantUs != latencyUs && !reregisterQueued then
        reregisterQueued = true
        flushThen { () =>
          reregisterQueued = false
          if latencyUs >= 0 then sm.unregisterListener(listener, s)
          latencyUs = -1
          register(s)
        }
    }

  private def register(s: Sensor): Unit =
    latencyUs = if sm.registerListener(listener, s, 1_000_000, wantUs, handler) then wantUs else -1
    if latencyUs < 0 then Log.w("barometer registration refused")

  /** Flushes what the hub holds, keeps it, then unregisters and calls `done`
    * (at once without a barometer).
    */
  def stop(done: () => Unit): Unit =
    flushThen { () =>
      sensor.foreach(s => if latencyUs >= 0 then sm.unregisterListener(listener, s))
      latencyUs = -1
      decimator.flush().foreach(pending += _)
      write()
      done()
    }

  /** Runs `next` once the FIFO is flushed and its readings are in. */
  private def flushThen(next: () => Unit): Unit =
    afterFlush :+= next
    if !flushing then
      if latencyUs >= 0 && sm.flush(listener) then
        flushing = true
        handler.postDelayed(flushTimeout, FlushTimeoutMs)
      else flushed()

  private val flushTimeout: Runnable = () =>
    flushTimeouts += 1
    Log.w(s"barometer flush did not complete in $FlushTimeoutMs ms")
    flushed()

  private def flushed(): Unit =
    flushing = false
    handler.removeCallbacks(flushTimeout)
    write()
    val fs = afterFlush
    afterFlush = Vector.empty
    fs.foreach(_())

  private def write(): Unit =
    if pending.nonEmpty then
      val ps = pending.toVector
      pending.clear()
      LoggerWrites.small(SmallRecord.Pressure(ps))

  private val writeNow: Runnable = () => write()

  private val listener = new SensorEventListener2:
    override def onAccuracyChanged(sensor: Sensor, accuracy: Int): Unit = ()
    override def onFlushCompleted(sensor: Sensor): Unit = if flushing then flushed()
    override def onSensorChanged(e: SensorEvent): Unit =
      val now = System.currentTimeMillis()
      val wall = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
      decimator.add(wall, e.values(0), moving).foreach { s =>
        pending += s
        // A batch is delivered in one go: hand it over right after its last
        // reading (the next message on this thread), not per reading.
        if !handler.hasCallbacks(writeNow) then handler.post(writeNow)
      }

object PressureRecorder:
  val FlushTimeoutMs: Long = 5_000L

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
