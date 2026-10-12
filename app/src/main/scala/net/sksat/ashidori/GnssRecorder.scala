package net.sksat.ashidori

import android.content.Context
import android.hardware.SensorEvent
import android.location.*
import android.os.{Build, Handler, SystemClock}
import java.io.{BufferedWriter, File, FileWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import ashidori.core.GnssLogFormat
import ashidori.core.GnssLogFormat.row

/** Records raw signals while a detail session runs: GNSS measurements,
  * navigation messages, satellite status, NMEA, fixes, and IMU (accelerometer,
  * gyroscope, magnetometer). Lines use the GnssLogger text format and go to
  * SQLite (`raw_lines`) in batches; finished days are archived to Parquet.
  *
  * Callbacks arrive on the logger thread; batches are written on the I/O
  * thread so storage never blocks sensor delivery.
  */
final class GnssRecorder(ctx: Context, lm: LocationManager, handler: Handler, fullTracking: Boolean, val reason: String):
  val startedAt: Long = System.currentTimeMillis()
  /** Shown in the UI while recording. */
  val label: String = s"${java.time.Instant.ofEpochMilli(startedAt).atZone(AshidoriApp.zone).toLocalTime.withNano(0)} から"
  private val io = AshidoriApp.io
  private val exec: Executor = r => handler.post(r)
  @volatile private var closed = false
  var measurementEvents = 0L

  private val buffer = scala.collection.mutable.ArrayBuffer.empty[(Long, String)]
  private var lastTime = startedAt
  private var lastFlush = startedAt

  /** Lines without a time of their own (navigation messages) take the last one. */
  private def write(line: String): Unit =
    if !closed then
      ashidori.core.GnssLogMerge.timeOf(line).foreach(lastTime = _)
      buffer += ((lastTime, line))
      val now = System.currentTimeMillis()
      if buffer.size >= 2000 || now - lastFlush > 3_000 then flush()

  private def flush(): Unit =
    if buffer.nonEmpty then
      val batch = buffer.toVector
      buffer.clear()
      lastFlush = System.currentTimeMillis()
      io.execute(() => AshidoriApp.db.insertRawLines(batch))

  // IMU at 50 Hz (100 Hz when logging everything), alongside GNSS.
  private val sm = ctx.getSystemService(classOf[android.hardware.SensorManager])
  private val imuSensors = Seq(
    android.hardware.Sensor.TYPE_ACCELEROMETER -> "Accel",
    android.hardware.Sensor.TYPE_GYROSCOPE -> "Gyro",
    android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> "Mag"
  ).flatMap((t, name) => Option(sm.getDefaultSensor(t)).map(_ -> name))
  private val imuListener = new android.hardware.SensorEventListener:
    override def onAccuracyChanged(s: android.hardware.Sensor, a: Int): Unit = ()
    override def onSensorChanged(e: SensorEvent): Unit =
      imuSensors.find(_._1 == e.sensor).foreach { (_, name) =>
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
        write(row(name, wall, e.timestamp, e.values(0), e.values(1), e.values(2)))
      }

  private val measurementCb = new GnssMeasurementsEvent.Callback:
    override def onGnssMeasurementsReceived(ev: GnssMeasurementsEvent): Unit =
      measurementEvents += 1
      val c = ev.getClock
      val now = System.currentTimeMillis()
      ev.getMeasurements.forEach { m =>
        write(
          row(
            "Raw",
            now,
            c.getTimeNanos,
            if c.hasLeapSecond then c.getLeapSecond else null,
            if c.hasTimeUncertaintyNanos then c.getTimeUncertaintyNanos else Double.NaN,
            if c.hasFullBiasNanos then c.getFullBiasNanos else null,
            if c.hasBiasNanos then c.getBiasNanos else Double.NaN,
            if c.hasBiasUncertaintyNanos then c.getBiasUncertaintyNanos else Double.NaN,
            if c.hasDriftNanosPerSecond then c.getDriftNanosPerSecond else Double.NaN,
            if c.hasDriftUncertaintyNanosPerSecond then c.getDriftUncertaintyNanosPerSecond else Double.NaN,
            c.getHardwareClockDiscontinuityCount,
            m.getSvid,
            m.getTimeOffsetNanos,
            m.getState,
            m.getReceivedSvTimeNanos,
            m.getReceivedSvTimeUncertaintyNanos,
            m.getCn0DbHz,
            m.getPseudorangeRateMetersPerSecond,
            m.getPseudorangeRateUncertaintyMetersPerSecond,
            m.getAccumulatedDeltaRangeState,
            m.getAccumulatedDeltaRangeMeters,
            m.getAccumulatedDeltaRangeUncertaintyMeters,
            if m.hasCarrierFrequencyHz then m.getCarrierFrequencyHz else Float.NaN,
            null,
            null,
            null,
            m.getMultipathIndicator,
            if m.hasSnrInDb then m.getSnrInDb else Double.NaN,
            m.getConstellationType,
            if m.hasAutomaticGainControlLevelDb then m.getAutomaticGainControlLevelDb else Double.NaN,
            if m.hasBasebandCn0DbHz then m.getBasebandCn0DbHz else Double.NaN,
            if m.hasFullInterSignalBiasNanos then m.getFullInterSignalBiasNanos else Double.NaN,
            if m.hasFullInterSignalBiasUncertaintyNanos then m.getFullInterSignalBiasUncertaintyNanos else Double.NaN,
            if m.hasSatelliteInterSignalBiasNanos then m.getSatelliteInterSignalBiasNanos else Double.NaN,
            if m.hasSatelliteInterSignalBiasUncertaintyNanos then m.getSatelliteInterSignalBiasUncertaintyNanos
            else Double.NaN,
            if m.hasCodeType then m.getCodeType else null,
            if c.hasElapsedRealtimeNanos then c.getElapsedRealtimeNanos else null
          )
        )
      }
      LoggerState.rawMeasurementsSeen += ev.getMeasurements.size

  private val navCb = new GnssNavigationMessage.Callback:
    override def onGnssNavigationMessageReceived(m: GnssNavigationMessage): Unit =
      write(row("Nav", m.getSvid, m.getType, m.getStatus, m.getMessageId, m.getSubmessageId, m.getData.map(_ & 0xff).mkString(",")))

  private val nmea: OnNmeaMessageListener = (msg: String, ts: Long) =>
    write(row("NMEA", msg.trim, ts))

  def start(): Unit =
    try
      val req = GnssMeasurementRequest.Builder().setFullTracking(fullTracking).build()
      lm.registerGnssMeasurementsCallback(req, exec, measurementCb)
      lm.registerGnssNavigationMessageCallback(exec, navCb)
      lm.addNmeaListener(exec, nmea)
      val periodUs = if fullTracking then 10_000 else 20_000
      imuSensors.foreach((sensor, _) => sm.registerListener(imuListener, sensor, periodUs, handler))
    catch case e: SecurityException => Log.w("gnss recorder: no permission", e)

  def onStatus(st: GnssStatus): Unit =
    val now = System.currentTimeMillis()
    val n = st.getSatelliteCount
    for i <- 0 until n do
      write(
        row(
          "Status",
          now,
          n,
          i,
          st.getConstellationType(i),
          st.getSvid(i),
          if st.hasCarrierFrequencyHz(i) then st.getCarrierFrequencyHz(i) else Float.NaN,
          st.getCn0DbHz(i),
          st.getAzimuthDegrees(i),
          st.getElevationDegrees(i),
          st.usedInFix(i),
          st.hasAlmanacData(i),
          st.hasEphemerisData(i),
          if st.hasBasebandCn0DbHz(i) then st.getBasebandCn0DbHz(i) else Float.NaN
        )
      )

  def onFix(l: Location): Unit =
    write(
      row(
        "Fix",
        l.getProvider,
        l.getLatitude,
        l.getLongitude,
        if l.hasAltitude then l.getAltitude else Double.NaN,
        if l.hasSpeed then l.getSpeed else Float.NaN,
        if l.hasAccuracy then l.getAccuracy else Float.NaN,
        if l.hasBearing then l.getBearing else Float.NaN,
        l.getTime,
        if l.hasSpeedAccuracy then l.getSpeedAccuracyMetersPerSecond else Float.NaN,
        if l.hasBearingAccuracy then l.getBearingAccuracyDegrees else Float.NaN,
        l.getElapsedRealtimeNanos,
        if l.hasVerticalAccuracy then l.getVerticalAccuracyMeters else Float.NaN,
        l.isMock,
        null,
        null,
        null
      )
    )

  /** Stops callbacks and writes what is buffered. */
  def stop(): Unit =
    lm.unregisterGnssMeasurementsCallback(measurementCb)
    lm.unregisterGnssNavigationMessageCallback(navCb)
    lm.removeNmeaListener(nmea)
    sm.unregisterListener(imuListener)
    flush()
    closed = true
