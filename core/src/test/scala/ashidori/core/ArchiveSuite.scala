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
