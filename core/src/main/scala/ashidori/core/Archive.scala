package ashidori.core

import ashidori.core.parquet.*
import ColType.*

/** Columnar (Parquet) layout of everything ashidori logs. Finished days are
  * archived as one file per table per day (`<table>/YYYY-MM-DD.parquet`),
  * and finished months are merged into `<table>/YYYY-MM.parquet`.
  */
object Archive:

  /** One archived table: its columns and how a raw text line maps to a row. */
  final case class TableDef(name: String, columns: Seq[Column]):
    def colNames: Seq[String] = columns.map(_.name)

  val Fixes = TableDef("fixes", Seq(
    Column("time", Int64), Column("lat", Float64), Column("lon", Float64), Column("acc", Float32),
    Column("alt", Float64), Column("speed", Float32), Column("bearing", Float32), Column("provider", Str)))
  val Motion = TableDef("motion", Seq(
    Column("start", Int64), Column("end", Int64), Column("std", Float32), Column("mean", Float32), Column("steps", Int32)))
  val Battery = TableDef("battery", Seq(
    Column("time", Int64), Column("pct", Int32), Column("charging", Int32), Column("screen_on", Int32), Column("mode", Str)))
  val GnssStatus = TableDef("gnss_status", Seq(
    Column("time", Int64), Column("visible", Int32), Column("used", Int32), Column("top4", Float64), Column("l5", Int32),
    Column("env", Str), Column("acc", Float32)))

  // --- raw signals (from GnssLogger-format lines) ---------------------------

  private val RawInt64 = Set("utcTimeMillis", "TimeNanos", "FullBiasNanos", "ReceivedSvTimeNanos", "ChipsetElapsedRealtimeNanos")
  private val RawInt32 = Set("LeapSecond", "HardwareClockDiscontinuityCount", "Svid", "State", "AccumulatedDeltaRangeState",
    "MultipathIndicator", "ConstellationType")
  private val RawStr = Set("CodeType")

  private def typed(header: String, drop: Int = 1): Seq[Column] =
    header.split(',').drop(drop).toSeq.map { n =>
      val t = if RawInt64(n) then Int64 else if RawInt32(n) then Int32 else if RawStr(n) then Str else Float64
      Column(n, t)
    }

  /** GNSS measurements: one row per signal per epoch (GnssLogger "Raw"). */
  val GnssRaw = TableDef("gnss_raw", typed(GnssLogFormat.RawHeader))
  /** Satellite status (GnssLogger "Status"). */
  val GnssSat = TableDef("gnss_sat", Seq(
    Column("UnixTimeMillis", Int64), Column("SignalCount", Int32), Column("SignalIndex", Int32), Column("ConstellationType", Int32),
    Column("Svid", Int32), Column("CarrierFrequencyHz", Float64), Column("Cn0DbHz", Float64), Column("AzimuthDegrees", Float64),
    Column("ElevationDegrees", Float64), Column("UsedInFix", Int32), Column("HasAlmanacData", Int32),
    Column("HasEphemerisData", Int32), Column("BasebandCn0DbHz", Float64)))
  /** Navigation messages; `time` is the preceding record's time. */
  val GnssNav = TableDef("gnss_nav", Seq(
    Column("time", Int64), Column("Svid", Int32), Column("Type", Int32), Column("Status", Int32), Column("MessageId", Int32),
    Column("SubmessageId", Int32), Column("Data", Str)))
  val Nmea = TableDef("nmea", Seq(Column("time", Int64), Column("message", Str)))
  /** IMU samples during detail sessions: sensor is Accel, Gyro or Mag. */
  val Imu = TableDef("imu", Seq(
    Column("time", Int64), Column("elapsedRealtimeNanos", Int64), Column("sensor", Str),
    Column("x", Float32), Column("y", Float32), Column("z", Float32)))

  /** Tables built from raw lines (kind = first CSV field). */
  val RawTables: Seq[TableDef] = Seq(GnssRaw, GnssSat, GnssNav, Nmea, Imu)
  val All: Seq[TableDef] = Seq(Fixes, Motion, Battery, GnssStatus) ++ RawTables

  /** Which raw table a line belongs to. */
  def tableFor(kind: String): Option[TableDef] = kind match
    case "Raw"                     => Some(GnssRaw)
    case "Status"                  => Some(GnssSat)
    case "Nav"                     => Some(GnssNav)
    case "NMEA"                    => Some(Nmea)
    case "Accel" | "Gyro" | "Mag"  => Some(Imu)
    case _                         => None

  private def cell(s: String, t: ColType): Any =
    if s.isEmpty then null
    else t match
      case Int32   => s.toIntOption.orElse(s.toDoubleOption.map(_.toInt)).orNull
      case Int64   => s.toLongOption.orElse(s.toDoubleOption.map(_.toLong)).orNull
      case Float32 => s.toFloatOption.orNull
      case Float64 => s.toDoubleOption.orNull
      case Str     => s

  /** Converts a GnssLogger-format line to a typed row of its table.
    * `lastTime` is the time of the previous record (for navigation messages).
    */
  def rowOf(line: String, lastTime: Long): Option[(TableDef, Seq[Any])] =
    val a = line.split(",", -1)
    tableFor(a(0)).map {
      case t @ (GnssRaw | GnssSat) => t -> t.columns.zipWithIndex.map((c, i) => cell(a.lift(i + 1).getOrElse(""), c.tpe))
      case GnssNav =>
        GnssNav -> (lastTime +: (1 to 5).map(i => cell(a.lift(i).getOrElse(""), Int32)) :+ a.drop(6).mkString(" "))
      case Nmea =>
        Nmea -> Seq(a.last.toLongOption.getOrElse(lastTime), a.slice(1, a.length - 1).mkString(","))
      case Imu =>
        Imu -> Seq(cell(a(1), Int64), cell(a(2), Int64), a(0), cell(a(3), Float32), cell(a(4), Float32), cell(a(5), Float32))
      case other => other -> Nil
    }

  /** Back to a GnssLogger-format line (for the text export). */
  def lineOf(t: TableDef, row: IndexedSeq[Any]): String =
    def s(v: Any) = if v == null then "" else v.toString
    t match
      case GnssRaw | GnssSat =>
        (if t == GnssRaw then "Raw" else "Status") + "," + row.map(s).mkString(",")
      case GnssNav => ("Nav" +: row.drop(1).take(5).map(s)).mkString(",") + "," + s(row(6)).replace(' ', ',')
      case Nmea    => s"NMEA,${s(row(1))},${s(row(0))}"
      case Imu     => s"${s(row(2))},${s(row(0))},${s(row(1))},${s(row(3))},${s(row(4))},${s(row(5))}"
      case _       => row.map(s).mkString(",")

  def fixRow(f: Fix): Seq[Any] = Seq(f.time, f.lat, f.lon, f.accuracy, f.altitude, f.speed, f.bearing, f.provider)
  def fixOf(r: IndexedSeq[Any]): Fix =
    def fl(v: Any) = if v == null then Float.NaN else v.asInstanceOf[Float]
    def db(v: Any) = if v == null then Double.NaN else v.asInstanceOf[Double]
    Fix(r(0).asInstanceOf[Long], db(r(1)), db(r(2)), fl(r(3)), db(r(4)), fl(r(5)), fl(r(6)), Option(r(7)).map(_.toString).getOrElse(""))

  def motionRow(m: MotionWindow): Seq[Any] = Seq(m.start, m.end, m.accelStd, m.accelMean, m.steps)
  def motionOf(r: IndexedSeq[Any]): MotionWindow =
    def fl(v: Any) = if v == null then Float.NaN else v.asInstanceOf[Float]
    MotionWindow(r(0).asInstanceOf[Long], r(1).asInstanceOf[Long], fl(r(2)), fl(r(3)), Option(r(4)).map(_.asInstanceOf[Int]).getOrElse(0))

  /** Archive file names. */
  def dayFile(t: TableDef, day: java.time.LocalDate): String = s"${t.name}/$day.parquet"
  def monthFile(t: TableDef, month: java.time.YearMonth): String = s"${t.name}/$month.parquet"
