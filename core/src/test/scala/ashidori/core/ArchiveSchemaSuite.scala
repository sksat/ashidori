package ashidori.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.util.Using
import ashidori.core.ArchiveMerge.*
import ashidori.core.parquet.*

/** Archive files of an earlier layout (schema 1: up to app 0.21.0) read,
  * merge and month-merge into the current one without losing a value; a
  * file of a later layout is never rewritten.
  */
class ArchiveSchemaSuite extends munit.FunSuite:
  /** `battery` and `fixes` as app 0.21.0 wrote them. */
  private val BatteryV1 = Archive.Battery.columns.take(5)
  private val FixesV1 = Archive.Fixes.columns.take(8)

  private def file(columns: Seq[Column], rows: Seq[Seq[Any]], groupRows: Int = 1000): File =
    val f = File.createTempFile("schema", ".parquet")
    f.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { o =>
      val w = ParquetWriter(o, columns, groupRows)
      rows.foreach(w.write)
      w.close()
    }
    f

  private val t0 = 1_760_000_000_000L
  private def oldBattery(i: Int): Seq[Any] = Seq(t0 + i * 300_000L, 80 - i, 0, 1, "balanced")

  test("a file of the earlier layout reads in the current columns, nulls in the new ones") {
    val f = file(BatteryV1, (0 until 5).map(oldBattery))
    Using.resource(Archive.open(Archive.Battery, f.getPath)) { r =>
      assert(!r.asStored)
      assertEquals(r.dropped, Nil)
      assertEquals(r.columns, Archive.Battery.columns)
      val rows = r.rowIterator.toVector
      assertEquals(rows.size, 5)
      assert(rows.forall(_.size == Archive.Battery.columns.size))
      assertEquals(rows(2).take(5), oldBattery(2).toIndexedSeq)
      assert(rows(2).drop(5).forall(_ == null))
      val b = Archive.batteryOf(rows(2))
      assertEquals((b.pct, b.chargeUah, b.plug, b.tempC.isNaN), (78, None, None, true))
    }
    // Fixes of 0.21.0: no vertical accuracy or MSL altitude.
    val fx = file(FixesV1, Seq(Seq(t0, 35.0, 139.0, 5f, 40.0, 1f, 90f, "gps")))
    Using.resource(Archive.open(Archive.Fixes, fx.getPath)) { r =>
      val fix = Archive.fixOf(r.rowIterator.next())
      assertEquals((fix.lat, fix.altitude, fix.provider), (35.0, 40.0, "gps"))
      assert(fix.msl.isNaN && fix.altAccuracy.isNaN && fix.mslAccuracy.isNaN)
    }
  }

  test("late rows merge into a day file of the earlier layout: decoded, not copied, every value kept") {
    val old = file(BatteryV1, (0 until 3000).map(oldBattery), groupRows = 500)
    val out = File.createTempFile("merged", ".parquet")
    out.deleteOnExit()
    val late = BatteryReading(t0 + 3000 * 300_000L, 50, false, false, "balanced", Some(2_000_000L), Some(150_000),
      Some(160_000), Some(3900), 31.5f, Some("none"))
    val (written, copied) = Using.resource(Archive.open(Archive.Battery, old.getPath, whole = true)) { ex =>
      Using.resource(BufferedOutputStream(FileOutputStream(out))) { o =>
        val w = ParquetWriter(o, Archive.Battery.columns, 500)
        val m = FileMerge(Some(ex), w)
        m.push(Archive.batteryRow(late).toIndexedSeq)
        m.finish()
        w.close()
        (m.written, m.copied)
      }
    }
    assertEquals(written, 3001L)
    // The groups lie before the new row, but are of the other layout.
    assertEquals(copied, 0L)
    val back = ParquetReader.readFile(out.getPath)
    assertEquals(back.columns, Archive.Battery.columns)
    val rows = back.rowIterator.toVector
    assertEquals(rows.take(3000).map(_.take(5)), (0 until 3000).map(i => oldBattery(i).toIndexedSeq))
    assertEquals(Archive.batteryOf(rows.last), late)
  }

  test("a file with a column this version does not know is not rewritten") {
    val newer = Archive.Pressure.columns :+ Column("hpa_raw", ColType.Float32)
    val f = file(newer, Seq(Seq(t0, 1013.2f, 1013.25f)))
    // Read: the known columns.
    Using.resource(Archive.open(Archive.Pressure, f.getPath)) { r =>
      assertEquals(r.dropped.map(_.name), Seq("hpa_raw"))
      assertEquals(r.rowIterator.toVector, Vector(IndexedSeq[Any](t0, 1013.2f)))
    }
    // Rewrite (merge or month merge): refused, the file stays as it is.
    intercept[IllegalStateException](Archive.open(Archive.Pressure, f.getPath, whole = true))
    // A column of the same name but another type is not the same column.
    val retyped = file(Seq(Column("time", ColType.Int64), Column("hpa", ColType.Float64)), Seq(Seq(t0, 1013.2)))
    intercept[IllegalStateException](Archive.open(Archive.Pressure, retyped.getPath, whole = true))
    Using.resource(Archive.open(Archive.Pressure, retyped.getPath)) { r =>
      assertEquals(Archive.pressureOf(r.rowIterator.next()).hPa.isNaN, true)
    }
  }

  test("a file of the current layout is still copied as stored") {
    val rows = (0 until 2000).map(i => Archive.pressureRow(PressureSample(t0 + i * 5000L, 1000f + i * 0.01f)))
    val old = file(Archive.Pressure.columns, rows, groupRows = 500)
    val out = File.createTempFile("copied", ".parquet")
    out.deleteOnExit()
    val copied = Using.resource(Archive.open(Archive.Pressure, old.getPath, whole = true)) { ex =>
      assert(ex.asStored)
      Using.resource(BufferedOutputStream(FileOutputStream(out))) { o =>
        val w = ParquetWriter(o, Archive.Pressure.columns, 500)
        val m = FileMerge(Some(ex), w)
        m.push(Archive.pressureRow(PressureSample(t0 + 2000 * 5000L, 990f)).toIndexedSeq)
        m.finish()
        w.close()
        m.copied
      }
    }
    assertEquals(copied, 2000L)
  }

  test("energy spans and pressure samples round-trip through their rows") {
    val st = EnergyState(true, 1000L, "gps", false, false, true, false, "balanced", "max", "Walking", "Outdoor")
    val span = EnergySpan(t0, t0 + 60_000L, st)
    assertEquals(Archive.energyOf(Archive.energyRow(span).toIndexedSeq), span)
    val f = file(Archive.EnergyStates.columns, Seq(Archive.energyRow(span)))
    Using.resource(Archive.open(Archive.EnergyStates, f.getPath)) { r =>
      assertEquals(Archive.energyOf(r.rowIterator.next()), span)
    }
    val p = PressureSample(t0, 1008.5f)
    assertEquals(Archive.pressureOf(Archive.pressureRow(p).toIndexedSeq), p)
    // Every signal table is archived and merged by month; only the four of
    // 0.21.0 are uploaded.
    assertEquals(Archive.SignalTables.map(_.name).toSet,
      Set("fixes", "motion", "battery", "gnss_status", "energy_state", "pressure"))
    assertEquals(Archive.UploadedSignalTables.map(_.name), Seq("fixes", "motion", "battery", "gnss_status"))
    assert(Archive.SignalTables.forall(Archive.All.contains))
  }
