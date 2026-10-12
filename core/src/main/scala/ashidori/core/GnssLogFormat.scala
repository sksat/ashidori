package ashidori.core

/** Text log format compatible with Google's GnssLogger app, so that detail
  * logs can be processed with existing tools (GPS Measurement Tools,
  * gnss_lib_py, RTKLIB converters, ...).
  */
object GnssLogFormat:
  val RawHeader: String =
    "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos," +
      "DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State," +
      "ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
      "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters," +
      "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty," +
      "MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos," +
      "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos," +
      "CodeType,ChipsetElapsedRealtimeNanos"
  val FixHeader: String =
    "Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees," +
      "UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters," +
      "MockLocation,NumberOfUsedSignals,VerticalSpeedAccuracyMps,SolutionType"
  val StatusHeader: String =
    "Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid,CarrierFrequencyHz,Cn0DbHz," +
      "AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData,BasebandCn0DbHz"
  val NavHeader: String = "Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)"
  val AccelHeader: String = "Accel,utcTimeMillis,elapsedRealtimeNanos,AccelXMps2,AccelYMps2,AccelZMps2"
  val NmeaHeader: String = "NMEA,Message,UnixTimeMillis"

  def header(version: String, platform: String, manufacturer: String, model: String): String =
    val lines = Seq(
      "",
      "Header Description:",
      "",
      s"Version: ashidori $version Platform: $platform Manufacturer: $manufacturer Model: $model",
      "",
      RawHeader,
      "",
      FixHeader,
      "",
      StatusHeader,
      "",
      AccelHeader,
      "",
      NavHeader,
      "",
      NmeaHeader,
      ""
    )
    lines.map(l => if l.isEmpty then "#" else s"# $l").mkString("", "\n", "\n")

  /** Formats one CSV row; NaN / null become empty fields as GnssLogger does. */
  def row(fields: Any*): String =
    val sb = StringBuilder()
    var first = true
    for f <- fields do
      if !first then sb.append(',')
      first = false
      f match
        case null                    => ()
        case d: Double if d.isNaN    => ()
        case fl: Float if fl.isNaN   => ()
        case d: Double               => sb.append(d)
        case fl: Float               => sb.append(fl)
        case b: Boolean              => sb.append(if b then 1 else 0)
        case o: Option[?]            => o.foreach(sb.append)
        case other                   => sb.append(other)
    sb.toString
