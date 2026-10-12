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

/** Records raw GNSS data (measurements, navigation messages, status, NMEA,
  * fixes) to a GnssLogger-compatible text file while a detail session runs.
  *
  * Callbacks arrive on the logger thread; formatted lines are handed to the
  * shared I/O thread so file writes never block sensor delivery.
  */
final class GnssRecorder(ctx: Context, lm: LocationManager, handler: Handler, fullTracking: Boolean, val reason: String):
  val startedAt: Long = System.currentTimeMillis()
  val file: File =
    val dir = File(ctx.getExternalFilesDir(null), "gnss")
    dir.mkdirs()
    // One file per day: detail sessions are short and frequent, so a file
    // per session left dozens of fragments. Sessions are appended and
    // separated by a comment line (GnssLogger tools skip comments).
    File(dir, GnssRecorder.fileName(java.time.LocalDate.now(AshidoriApp.zone)))
  private val existed = file.exists && file.length > 0
  private val out = BufferedWriter(FileWriter(file, true), 64 * 1024)
  private val io = AshidoriApp.io
  private val exec: Executor = r => handler.post(r)
  @volatile private var closed = false
  var measurementEvents = 0L

  private def write(line: String): Unit =
    io.execute { () =>
      if !closed then
        out.write(line)
        out.write('\n')
    }

  io.execute { () =>
    if !existed then out.write(GnssLogFormat.header(BuildInfo.versionName, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL))
    out.write(s"# Session ${java.time.Instant.ofEpochMilli(startedAt)} reason=$reason fullTracking=$fullTracking\n")
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

  def onAccel(e: SensorEvent): Unit =
    val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
    write(row("Accel", wall, e.timestamp, e.values(0), e.values(1), e.values(2)))

  /** Stops callbacks and closes the file. Returns the file. */
  def stop(): File =
    lm.unregisterGnssMeasurementsCallback(measurementCb)
    lm.unregisterGnssNavigationMessageCallback(navCb)
    lm.removeNmeaListener(nmea)
    io.execute { () =>
      closed = true
      out.close()
    }
    file

object GnssRecorder:
  def fileName(day: java.time.LocalDate): String =
    s"ashidori_gnss_${day.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.txt"
