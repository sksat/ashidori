package ashidori.core

import java.io.ByteArrayOutputStream
import ashidori.core.parquet.*

class ArchiveSuite extends munit.FunSuite:
  private def roundTrip(t: Archive.TableDef, rows: Seq[Seq[Any]]): ParquetReader.Table =
    val bo = ByteArrayOutputStream()
    val w = ParquetWriter(bo, t.columns)
    rows.foreach(w.write)
    w.close()
    ParquetReader.read(bo.toByteArray)

  test("raw GNSS lines become typed rows and back") {
    val cols = GnssLogFormat.RawHeader.split(',').drop(1)
    val values = cols.map {
      case "utcTimeMillis"     => "1791801000000"
      case "TimeNanos"         => "123456789012"
      case "FullBiasNanos"     => "-1418000000000000000"
      case "Svid"              => "12"
      case "ConstellationType" => "1"
      case "Cn0DbHz"           => "38.5"
      case "CodeType"          => "C"
      case _                   => ""
    }
    val line = ("Raw" +: values.toSeq).mkString(",")
    val (t, row) = Archive.rowOf(line, 0).get
    assertEquals(t, Archive.GnssRaw)
    val back = roundTrip(t, Seq(row))
    val r = back.rowIterator.next()
    assertEquals(r(back.columns.indexWhere(_.name == "FullBiasNanos")), -1418000000000000000L)
    assertEquals(r(back.columns.indexWhere(_.name == "Svid")), 12)
    assertEquals(r(back.columns.indexWhere(_.name == "Cn0DbHz")), 38.5)
    assertEquals(r(back.columns.indexWhere(_.name == "CodeType")), "C")
    assertEquals(Archive.lineOf(t, r), line)
  }

  test("IMU, NMEA and navigation lines") {
    val (ti, imu) = Archive.rowOf("Gyro,1000,555,0.1,0.2,0.3", 0).get
    assertEquals(ti, Archive.Imu)
    assertEquals(imu, Seq(1000L, 555L, "Gyro", 0.1f, 0.2f, 0.3f))
    val (tn, nmea) = Archive.rowOf("NMEA,$GPGGA,123,4.5,N,2000", 0).get
    assertEquals(tn, Archive.Nmea)
    assertEquals(nmea, Seq(2000L, "$GPGGA,123,4.5,N"))
    val (tv, nav) = Archive.rowOf("Nav,3,257,1,0,2,10,20,30", 1500).get
    assertEquals(tv, Archive.GnssNav)
    assertEquals(nav.head, 1500L)
    assertEquals(Archive.lineOf(tv, nav.toIndexedSeq), "Nav,3,257,1,0,2,10,20,30")
  }

  test("fixes and motion round-trip through Parquet") {
    val fixes = Seq(Fix(1, 35.1, 139.2, 5f, 40, 1.2f, 90f, "gps"), Fix(2, 35.2, 139.3, provider = "fused"))
    val t = roundTrip(Archive.Fixes, fixes.map(Archive.fixRow))
    assertEquals(t.rowIterator.map(Archive.fixOf).toSeq.map(_.toString), fixes.map(_.toString))
    val m = MotionWindow(0, 10_000, 1.5f, Float.NaN, 12)
    val back = Archive.motionOf(roundTrip(Archive.Motion, Seq(Archive.motionRow(m))).rowIterator.next())
    assertEquals(back.steps, 12)
    assert(back.accelMean.isNaN)
  }

  /** One line of every kind the detail recorder writes (as GnssRecorder
    * formats them, NaN and null as empty fields).
    */
  private val recorderLines: Seq[String] =
    import GnssLogFormat.row
    val t = 1_791_801_000_000L
    val raw = Seq.fill[Any](GnssLogFormat.RawHeader.split(',').length - 1)("").updated(0, t).updated(10, 7).updated(34, "C")
    Seq(
      row(("Raw" +: raw)*),
      row("Status", t, 40, 0, 1, 7, 1.57542e9, 38.5f, 120f, 45f, true, true, false, Float.NaN),
      row("Nav", 7, 257, 1, 3, 1, "1,2,3"),
      row("NMEA", "$GPGGA,1,2", t),
      row("Accel", t, 123L, 0.1f, 0.2f, 9.8f),
      row("Gyro", t, 124L, 0.01f, 0.02f, 0.03f),
      row("Mag", t, 125L, 20f, -5f, 40f),
      row("Fix", "gps", 35.1, 139.2, 40.5, 1.5f, 4f, 90f, t, 0.3f, 2f, 555L, 3f, false, null, null, null)
    )

  test("every kind the recorder writes has a table, and no line kind is lost") {
    val kinds = recorderLines.map(_.takeWhile(_ != ','))
    assertEquals(kinds.toSet, Set("Raw", "Status", "Nav", "NMEA", "Accel", "Gyro", "Mag", "Fix"))
    val lines = (0 until 50).flatMap(_ => recorderLines)
    val before = lines.groupBy(_.takeWhile(_ != ',')).view.mapValues(_.size).toMap
    val rows = lines.map(l => Archive.rowOf(l, 1_791_801_000_000L))
    assert(rows.forall(_.isDefined), "a line had no table")
    rows.flatten.foreach((t, r) => assertEquals(r.size, t.columns.size, t.name))
    val after = rows.flatten.groupBy(_._1).view.mapValues(_.size).toMap
    assertEquals(after(Archive.GnssRaw), before("Raw"))
    assertEquals(after(Archive.GnssSat), before("Status"))
    assertEquals(after(Archive.GnssNav), before("Nav"))
    assertEquals(after(Archive.Nmea), before("NMEA"))
    assertEquals(after(Archive.Imu), before("Accel") + before("Gyro") + before("Mag"))
    assertEquals(after(Archive.GnssFix), before("Fix"))
    assertEquals(after.keySet, Archive.RawTables.toSet)
  }

  test("detail-log Fix lines keep every field and turn back into the same line") {
    val line = recorderLines.last
    val (t, row) = Archive.rowOf(line, 0).get
    assertEquals(t, Archive.GnssFix)
    val back = roundTrip(t, Seq(row)).rowIterator.next()
    assertEquals(back(0), 1_791_801_000_000L)
    assertEquals(back(1), "gps")
    assertEquals(back(10), 555L)
    assertEquals(back(12), 0)
    assertEquals(Archive.lineOf(t, back), line)
  }

  test("parse: every recorder line converts like rowOf; a missing value is null, a bad one an error") {
    for l <- recorderLines do
      val (t, row) = Archive.parse(l, 1_791_801_000_000L).fold(e => fail(s"$l: $e"), identity)
      val (t2, row2) = Archive.rowOf(l, 1_791_801_000_000L).get
      assertEquals(t, t2)
      assertEquals(row.map(String.valueOf), row2.toIndexedSeq.map(String.valueOf))
    // Missing values are fine.
    assert(Archive.parse("Accel,1000,555,,0.2,", 0).isRight)
    // A value that does not parse, or a lossy one, is an error.
    assert(Archive.parse("Accel,1000,555,x,0.2,0.3", 0).isLeft)
    assert(Archive.parse("Status,1000,40,1.5,1,7,1.5e9,38,1,2,1,1,1,", 0).isLeft) // SignalIndex 1.5
    assertEquals(Archive.parse("Status,1000,40,2.0,1,7,1.5e9,38,1,2,1,1,1,", 0).map(_._2(2)), Right(2))
    // No time, too many fields, unknown kinds.
    assert(Archive.parse("Accel,,555,0.1,0.2,0.3", 0).isLeft)
    assert(Archive.parse("Accel,1000,555,0.1,0.2,0.3,9", 0).isLeft)
    assert(Archive.parse(recorderLines.last.replace(",1791801000000,", ",,"), 0).isLeft)
    assert(Archive.parse(recorderLines.head + ",extra", 0).isLeft)
    assert(Archive.parse("NMEA,GPGGA,1,x", 0).isLeft)
    assert(Archive.parse("OrientationDeg,1,2,3,4", 0).isLeft)
    assert(Archive.parse("UncalAccel,1000,2,3,4,5,6,7", 0).isLeft)
  }
