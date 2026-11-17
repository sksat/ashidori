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

/** Altitude above mean sea level for fixes (Android 14+), filled in after
  * the fix is saved.
  *
  * Android reports the WGS84 ellipsoidal height; the geoid model of
  * `AltitudeConverter` gives the MSL altitude. It loads the model's tiles
  * from the platform's assets on first use in an area (disk I/O, which
  * can take seconds), so it never runs on the logger's thread: the fix is
  * saved as it came (MSL altitude null unless the platform gave one), and
  * this thread converts a copy and saves the altitudes a few seconds later
  * ([[SmallRecord.FixMsl]]). At most [[MaxQueued]] fixes wait; more are
  * left without (counted). A fix archived or deleted meanwhile is left as
  * it is.
  */
final class MslAltitude(ctx: Context, handler: Handler, onConverted: Seq[(Long, Double, Float)] => Unit):
  import MslAltitude.*
  private val exec = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
    java.util.concurrent.ArrayBlockingQueue[Runnable](MaxQueued), (r: Runnable) => Thread(r, "ashidori-msl"),
    (_: Runnable, _: java.util.concurrent.ThreadPoolExecutor) => skipped.incrementAndGet())
  private lazy val converter = android.location.altitude.AltitudeConverter()
  private val done = ArrayBuffer.empty[(Long, Double, Float)]
  private var loggedFirst = false
  /** Fixes left without an MSL altitude because too many waited. */
  val skipped = java.util.concurrent.atomic.AtomicLong()

  /** The MSL altitude the platform already gave the fix, if any (NaN otherwise). */
  def fromPlatform(l: Location): (Double, Float) =
    if Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude then
      (l.getMslAltitudeMeters, if l.hasMslAltitudeAccuracy then l.getMslAltitudeAccuracyMeters else Float.NaN)
    else (Double.NaN, Float.NaN)

  /** Converts a copy of `l` (whose fix is saved with time `time`) on this thread. */
  def request(l: Location, time: Long): Unit =
    if Build.VERSION.SDK_INT >= 34 && l.hasAltitude && !l.hasMslAltitude then
      val copy = Location(l)
      exec.execute { () =>
        val t0 = SystemClock.elapsedRealtimeNanos()
        try converter.addMslAltitudeToLocation(ctx, copy)
        catch
          case e: java.io.IOException      => Log.w("MSL altitude: model not loaded", e)
          case _: IllegalArgumentException => ()
        if !loggedFirst then
          loggedFirst = true
          Log.i(f"MSL altitude: first conversion took ${(SystemClock.elapsedRealtimeNanos() - t0) / 1e6}%.1f ms")
        if copy.hasMslAltitude then
          // Saved in batches: 50, or what is in [[FlushMs]] after the first.
          if done.isEmpty then handler.postDelayed(saveLater, FlushMs)
          done += ((time, copy.getMslAltitudeMeters, if copy.hasMslAltitudeAccuracy then copy.getMslAltitudeAccuracyMeters else Float.NaN))
        if done.size >= 50 then save()
      }

  private val saveLater: Runnable = () => scala.util.Try(exec.execute(() => save()))

  private def save(): Unit =
    if done.nonEmpty then
      val vs = done.toVector
      done.clear()
      LoggerWrites.small(SmallRecord.FixMsl(vs))
      onConverted(vs)

  /** Saves what is converted and ends the thread (the logger stopped). */
  def close(): Unit =
    scala.util.Try(exec.execute(() => save()))
    exec.shutdown()

object MslAltitude:
  val MaxQueued = 600
  val FlushMs: Long = 5_000L
