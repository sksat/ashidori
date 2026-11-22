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

  test("a file of an unknown layout or a newer version is an error, never nulls") {
    // A column this version does not know: neither read nor rewritten.
    val newer = Archive.Pressure.columns :+ Column("hpa_raw", ColType.Float32)
    val f = file(newer, Seq(Seq(t0, 1013.2f, 1013.25f)))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, f.getPath))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, f.getPath, whole = true))
    // A column of the same name but another type.
    val retyped = file(Seq(Column("time", ColType.Int64), Column("hpa", ColType.Float64)), Seq(Seq(t0, 1013.2)))
    intercept[ParquetReader.TypeMismatch](Archive.open(Archive.Pressure, retyped.getPath))
    // A column left out that no version of the table lacked.
    val partial = file(Seq(Column("time", ColType.Int64)), Seq(Seq(t0)))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, partial.getPath))
    // The footer names the table and its version.
    def tagged(t: Archive.TableDef, cols: Seq[Column], kv: Seq[(String, String)]): File =
      val g = File.createTempFile("tagged", ".parquet")
      g.deleteOnExit()
      Using.resource(BufferedOutputStream(FileOutputStream(g))) { o =>
        val w = ParquetWriter(o, cols, 100, kv)
        w.close()
      }
      g
    val v5 = tagged(Archive.Pressure, Archive.Pressure.columns, Seq(Archive.TableKey -> "pressure", Archive.SchemaKey -> "5"))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, v5.getPath))
    // energy_state of schema 4 has ble; one named 4 without it is not schema 4.
    val noBle = tagged(Archive.EnergyStates, Archive.EnergyStates.columns.take(16),
      Seq(Archive.TableKey -> "energy_state", Archive.SchemaKey -> "4"))
    intercept[Archive.LayoutError](Archive.open(Archive.EnergyStates, noBle.getPath))
    val other = tagged(Archive.Pressure, Archive.Pressure.columns, Seq(Archive.TableKey -> "motion", Archive.SchemaKey -> "3"))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, other.getPath))
    // Battery of schema 1 named as schema 2: its columns are not schema 2's.
    val mislabeled = tagged(Archive.Battery, BatteryV1, Seq(Archive.TableKey -> "battery", Archive.SchemaKey -> "2"))
    intercept[Archive.LayoutError](Archive.open(Archive.Battery, mislabeled.getPath))
    val halfTagged = tagged(Archive.Pressure, Archive.Pressure.columns, Seq(Archive.TableKey -> "pressure"))
    intercept[Archive.LayoutError](Archive.open(Archive.Pressure, halfTagged.getPath))
  }

  test("the writer puts the table and version in the footer, and refuses a value of another type") {
    val g = File.createTempFile("written", ".parquet")
    g.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(g))) { o =>
      val w = Archive.writer(Archive.Pressure, o, 100)
      w.write(Archive.pressureRow(PressureSample(t0, 1001f)))
      w.close()
    }
    assertEquals(ParquetReader.meta(g.getPath).keyValues,
      Map(Archive.TableKey -> "pressure", Archive.SchemaKey -> Archive.SchemaVersion.toString))
    Using.resource(Archive.open(Archive.Pressure, g.getPath))(r => assertEquals(r.rowIterator.size, 1))
    val w = ParquetWriter(java.io.ByteArrayOutputStream(), Archive.Battery.columns)
    // A row of the earlier layout padded wrongly: a string where a number goes.
    intercept[IllegalArgumentException](w.write(Seq(t0, 80, 0, 1, "balanced", "none", null, null, null, null, null)))
    intercept[IllegalArgumentException](w.write(Seq(t0, 80L, 0, 1, "balanced", null, null, null, null, null, null)))
    intercept[IllegalArgumentException](w.write(Seq(t0, 80, 0, 1, "balanced")))
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
    // Every signal table is archived, merged by month and uploaded.
    assertEquals(Archive.SignalTables.map(_.name).toSet,
      Set("fixes", "motion", "battery", "gnss_status", "energy_state", "pressure", "pics_adv"))
    assertEquals(Archive.UploadedSignalTables, Archive.SignalTables)
    assert(Archive.SignalTables.forall(Archive.All.contains))
  }

  test("schema 4: energy_state files of schema 2 and 3 read with ble null (never off), and merge with rows that have it") {
    val st = EnergyState(true, 1000L, "gps", false, false, true, false, "balanced", "max", "Walking", "Outdoor")
    val old = EnergySpan(t0, t0 + 60_000L, st)
    // As schema 2 and 3 wrote it: 16 columns (schema 3 with its footer).
    val v2 = file(Archive.EnergyStates.columns.take(16), Seq(Archive.energyRow(old).take(16)))
    val v3 = File.createTempFile("ver3", ".parquet")
    v3.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(v3))) { o =>
      val w = ParquetWriter(o, Archive.EnergyStates.columns.take(16), 100,
        Seq(Archive.TableKey -> "energy_state", Archive.SchemaKey -> "3"))
      w.write(Archive.energyRow(old).take(16))
      w.close()
    }
    for f <- Seq(v2, v3) do
      Using.resource(Archive.open(Archive.EnergyStates, f.getPath)) { r =>
        val row = r.rowIterator.next()
        assertEquals(row.size, 17)
        assertEquals(row(16), null)
        assertEquals(Archive.energyOf(row).state.ble, None)
      }
    // A late span with ble merged into the schema 2 file: decoded, both kept, each as it was.
    val late = EnergySpan(t0 + 60_000L, t0 + 120_000L, st.copy(ble = Some("bal")))
    val out = File.createTempFile("merged", ".parquet")
    out.deleteOnExit()
    Using.resource(Archive.open(Archive.EnergyStates, v2.getPath, whole = true)) { ex =>
      Using.resource(BufferedOutputStream(FileOutputStream(out))) { o =>
        val w = Archive.writer(Archive.EnergyStates, o, 100)
        val m = FileMerge(Some(ex), w)
        m.push(Archive.energyRow(late).toIndexedSeq)
        m.finish()
        w.close()
        assertEquals(m.copied, 0L)
      }
    }
    Using.resource(Archive.open(Archive.EnergyStates, out.getPath)) { r =>
      assertEquals(r.rowIterator.map(Archive.energyOf).toVector, Vector(old, late))
    }
    // The repair fits SQLite's rows of before the upgrade (ble NULL) to a 16-column file; one with ble is not fitted.
    val hot = Archive.energyRow(old).toIndexedSeq
    assertEquals(CommitRepair.fit(hot, 16), hot.take(16))
    val withBle = Archive.energyRow(late).toIndexedSeq
    assertEquals(CommitRepair.fit(withBle, 16), withBle)
  }

  test("schema 4: pics_adv rows round-trip, nulls where a packet was short") {
    val full = PicsAdv(t0, t0 + 4800, "02:00:00:00:00:01", Some("00CD0849"), Some(2), Some(255), Some(3), 2, 25, -61, t0 + 200,
      -88, "580102FF000000CD08494351FFFFFFFF0000000000000000", "bal")
    val short = PicsAdv(t0 + 10, t0 + 10, "02:00:00:00:00:02", None, Some(2), None, None, 0, 1, -70, t0 + 10, -70, "580102", "debug")
    val folded = PicsAdv.fold(Seq(full, full.copy(start = t0 + 5000, end = t0 + 9000, rssiMaxAt = t0 + 5000,
      data = "580102FF000000CD08493351FFFFFFFF0000000000000000")))
    assertEquals(folded.parts, 2)
    val f = File.createTempFile("pics", ".parquet")
    f.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { o =>
      val w = Archive.writer(Archive.PicsAdvs, o, 100)
      Seq(full, short, folded).foreach(a => w.write(Archive.picsRow(a)))
      w.close()
    }
    assertEquals(ParquetReader.meta(f.getPath).keyValues.get(Archive.SchemaKey), Some("4"))
    Using.resource(Archive.open(Archive.PicsAdvs, f.getPath)) { r =>
      val rows = r.rowIterator.toVector
      assertEquals(rows.map(Archive.picsOf), Vector(full, short, folded))
      assertEquals(Archive.picsOf(rows(2)).expand.map(_.data.slice(20, 24)), Seq("4351", "3351"))
      // The values a file gives are the values a row from SQLite has (the repair's hash compares them).
      assertEquals(rows.map(ArchiveMerge.hash), Vector(full, short, folded).map(a => ArchiveMerge.hash(Archive.picsRow(a).toIndexedSeq)))
    }
    assertEquals(Archive.layouts(Archive.PicsAdvs), Seq(4 -> Archive.PicsAdvs.columns))
    // The 14 columns of unreleased builds (before the fold), named schema 4:
    // no layout of this version, an error (set aside by the app), never nulls.
    val unreleased = File.createTempFile("pics14", ".parquet")
    unreleased.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(unreleased))) { o =>
      val w = ParquetWriter(o, Archive.PicsAdvs.columns.take(14), 100,
        Seq(Archive.TableKey -> "pics_adv", Archive.SchemaKey -> "4"))
      w.write(Archive.picsRow(full).take(14))
      w.close()
    }
    intercept[Archive.LayoutError](Archive.open(Archive.PicsAdvs, unreleased.getPath))
    intercept[Archive.LayoutError](Archive.open(Archive.PicsAdvs, unreleased.getPath, whole = true))
  }
