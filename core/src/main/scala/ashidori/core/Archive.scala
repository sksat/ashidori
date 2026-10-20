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

  /** The detail log's copy of each position (GnssLogger "Fix"), with what
    * the `fixes` table does not keep: speed, bearing and vertical accuracy,
    * elapsed realtime, mock flag. The time (UnixTimeMillis) comes first.
    */
  val GnssFix = TableDef("gnss_fix", Seq(
    Column("UnixTimeMillis", Int64), Column("Provider", Str), Column("LatitudeDegrees", Float64),
    Column("LongitudeDegrees", Float64), Column("AltitudeMeters", Float64), Column("SpeedMps", Float32),
    Column("AccuracyMeters", Float32), Column("BearingDegrees", Float32), Column("SpeedAccuracyMps", Float32),
    Column("BearingAccuracyDegrees", Float32), Column("elapsedRealtimeNanos", Int64), Column("VerticalAccuracyMeters", Float32),
    Column("MockLocation", Int32), Column("NumberOfUsedSignals", Int32), Column("VerticalSpeedAccuracyMps", Float32),
    Column("SolutionType", Str)))

  /** Tables built from raw lines (kind = first CSV field). */
  val RawTables: Seq[TableDef] = Seq(GnssRaw, GnssSat, GnssNav, Nmea, Imu, GnssFix)
  val All: Seq[TableDef] = Seq(Fixes, Motion, Battery, GnssStatus) ++ RawTables

  /** Which raw table a line belongs to. */
  def tableFor(kind: String): Option[TableDef] = kind match
    case "Raw"                     => Some(GnssRaw)
    case "Status"                  => Some(GnssSat)
    case "Nav"                     => Some(GnssNav)
    case "NMEA"                    => Some(Nmea)
    case "Accel" | "Gyro" | "Mag"  => Some(Imu)
    case "Fix"                     => Some(GnssFix)
    case _                         => None

  private def cell(s: String, t: ColType): Any =
    if s.isEmpty then null
    else t match
      case Int32   => s.toIntOption.orElse(s.toDoubleOption.map(_.toInt))
        .orElse(s match { case "true" => Some(1); case "false" => Some(0); case _ => None }).orNull
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
      case GnssFix =>
        // Line order: Provider, Lat, Lon, Alt, Speed, Acc, Bearing, UnixTimeMillis, then the rest.
        def f(i: Int) = a.lift(i).getOrElse("")
        val order = Seq(8, 1, 2, 3, 4, 5, 6, 7) ++ (9 to 16)
        GnssFix -> GnssFix.columns.zip(order).map((c, i) => cell(f(i), c.tpe))
      case other => other -> Nil
    }

  /** A field that is present but not a value of its column's type. An empty
    * field is a missing value (null), not an error.
    */
  private def strictCell(s: String, t: ColType): Either[String, Any] =
    def integral(max: Double): Option[Long] =
      s.toDoubleOption.filter(d => !d.isNaN && d == math.rint(d) && math.abs(d) <= max).map(_.toLong)
    if s.isEmpty then Right(null)
    else
      val v: Option[Any] = t match
        case Int32 =>
          s.toIntOption.orElse(integral(Int.MaxValue.toDouble).map(_.toInt))
            .orElse(s match { case "true" => Some(1); case "false" => Some(0); case _ => None })
        case Int64   => s.toLongOption.orElse(integral(9.007199254740992e15))
        case Float32 => s.toFloatOption
        case Float64 => s.toDoubleOption
        case Str     => Some(s)
      v.toRight(s"'$s' is not ${t.toString}")

  /** Converts a GnssLogger-format line to a typed row of its table, or says
    * why it cannot without losing something: an unknown kind, more fields
    * than the table has, a field that does not parse, or no time. Archiving
    * keeps such lines as they are (in SQLite). `time` is the line's stored
    * time (the preceding record's for navigation messages).
    */
  def parse(line: String, time: Long): Either[String, (TableDef, IndexedSeq[Any])] =
    val a = line.split(",", -1)
    def cells(t: TableDef, fields: Seq[String]): Either[String, IndexedSeq[Any]] =
      val out = IndexedSeq.newBuilder[Any]
      var err: Option[String] = None
      for (c, s) <- t.columns.zip(fields) if err.isEmpty do
        strictCell(s, c.tpe).fold(e => err = Some(s"${c.name}: $e"), out += _)
      err.toLeft(out.result())
    def fields(n: Int, from: Int): Either[String, Seq[String]] =
      if a.length - from > n then Left(s"${a.length - from} fields, ${n} columns")
      else Right((from until from + n).map(i => a.lift(i).getOrElse("")))
    val row: Either[String, (TableDef, IndexedSeq[Any])] = tableFor(a(0)) match
      case None => Left(s"no table for '${a(0)}'")
      case Some(t @ (GnssRaw | GnssSat)) => fields(t.columns.size, 1).flatMap(cells(t, _)).map(t -> _)
      case Some(GnssNav) =>
        if a.length < 6 then Left("navigation message without its fields")
        else cells(GnssNav, time.toString +: (1 to 5).map(a(_)) :+ a.drop(6).mkString(" ")).map(GnssNav -> _)
      case Some(Nmea) =>
        if a.length < 3 then Left("NMEA without its time")
        else a.last.toLongOption.toRight("NMEA time").map(t => Nmea -> IndexedSeq(t, a.slice(1, a.length - 1).mkString(",")))
      case Some(Imu) =>
        if a.length != 6 then Left(s"${a.length} fields in an IMU line")
        else cells(Imu, Seq(a(1), a(2), a(0), a(3), a(4), a(5))).map(Imu -> _)
      case Some(GnssFix) =>
        fields(16, 1).flatMap { f =>
          val order = Seq(7, 0, 1, 2, 3, 4, 5, 6) ++ (8 to 15)
          cells(GnssFix, order.map(f))
        }.map(GnssFix -> _)
      case Some(other) => Left(s"no conversion for ${other.name}")
    row.filterOrElse((_, r) => r.nonEmpty && r(0) != null, "no time")

  /** Back to a GnssLogger-format line (for the text export). */
  def lineOf(t: TableDef, row: IndexedSeq[Any]): String =
    def s(v: Any) = if v == null then "" else v.toString
    t match
      case GnssRaw | GnssSat =>
        (if t == GnssRaw then "Raw" else "Status") + "," + row.map(s).mkString(",")
      case GnssNav => ("Nav" +: row.drop(1).take(5).map(s)).mkString(",") + "," + s(row(6)).replace(' ', ',')
      case Nmea    => s"NMEA,${s(row(1))},${s(row(0))}"
      case Imu     => s"${s(row(2))},${s(row(0))},${s(row(1))},${s(row(3))},${s(row(4))},${s(row(5))}"
      case GnssFix => ("Fix" +: (row.slice(1, 8) ++ Seq(row(0)) ++ row.drop(8)).map(s)).mkString(",")
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
