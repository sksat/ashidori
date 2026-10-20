package ashidori.core

import java.io.{ByteArrayOutputStream, File, FileOutputStream}
import java.nio.ByteBuffer
import java.nio.ByteOrder
import scala.util.Using
import ashidori.core.parquet.*

/** Structural checks here; a full read-back with pyarrow/DuckDB runs when
  * ASHIDORI_PARQUET_OUT is set (the file is left there for an external reader).
  */
class ParquetSuite extends munit.FunSuite:
  private val schema = Seq(
    Column("time", ColType.Int64),
    Column("lat", ColType.Float64),
    Column("acc", ColType.Float32),
    Column("svid", ColType.Int32),
    Column("provider", ColType.Str)
  )
  private def rows(n: Int) = (0 until n).map { i =>
    Seq(1_700_000_000_000L + i, 35.0 + i * 1e-5, if i % 7 == 0 then Float.NaN else 5f + i % 3, i % 32, if i % 5 == 0 then null else "gps")
  }

  private def writeTo(out: java.io.OutputStream, n: Int, group: Int): Unit =
    val w = ParquetWriter(out, schema, group)
    rows(n).foreach(w.write)
    w.close()

  test("file starts and ends with PAR1 and has a sane footer length") {
    val bo = ByteArrayOutputStream()
    writeTo(bo, 1234, 500)
    val bytes = bo.toByteArray
    assertEquals(String(bytes.take(4), "UTF-8"), "PAR1")
    assertEquals(String(bytes.takeRight(4), "UTF-8"), "PAR1")
    val footerLen = ByteBuffer.wrap(bytes, bytes.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt
    assert(footerLen > 0 && footerLen < bytes.length - 12, footerLen.toString)
  }

  test("RLE definition levels encode runs") {
    val enc = ParquetWriter.rle(Vector(true, true, true, false, true))
    // run(3,true), run(1,false), run(1,true): header=(count<<1), value byte
    assertEquals(enc.toSeq.map(_ & 0xff), Seq(6, 1, 2, 0, 2, 1), "rle runs")
  }

  test("reader round-trips what the writer wrote, across row groups and nulls") {
    val bo = ByteArrayOutputStream()
    writeTo(bo, 2345, 1000)
    val t = ParquetReader.read(bo.toByteArray)
    assertEquals(t.rows, 2345)
    assertEquals(t.columns, schema)
    val expected = rows(2345)
    for (row, i) <- t.rowIterator.zipWithIndex do
      val e = expected(i)
      assertEquals(row(0), e(0))
      assertEquals(row(1), e(1))
      e(2) match
        case f: Float if f.isNaN => assertEquals(row(2), null)
        case f                   => assertEquals(row(2), f)
      assertEquals(row(3), e(3))
      assertEquals(row(4), e(4))
  }

  test("a file is read one row group at a time, and its footer alone gives the row count") {
    val f = File.createTempFile("groups", ".parquet")
    f.deleteOnExit()
    Using.resource(FileOutputStream(f))(o => writeTo(o, 2345, 1000))
    val meta = ParquetReader.meta(f.getPath)
    assertEquals(meta.rows, 2345L)
    assertEquals(meta.groups.map(_.rows), Seq(1000, 1000, 345))
    assertEquals(meta.columns, schema)
    val whole = ParquetReader.readFile(f.getPath)
    Using.resource(ParquetReader.open(f.getPath)) { r =>
      assertEquals(r.groups, 3)
      assertEquals(r.readGroup(2).rows, 345)
      assertEquals(r.readGroup(1).rowIterator.next(), whole.rowIterator.drop(1000).next())
      assertEquals(r.rowIterator.toVector, whole.rowIterator.toVector)
    }
  }

  test("an empty file has no row groups") {
    val f = File.createTempFile("empty", ".parquet")
    f.deleteOnExit()
    Using.resource(FileOutputStream(f))(o => writeTo(o, 0, 1000))
    assertEquals(ParquetReader.meta(f.getPath).rows, 0L)
    Using.resource(ParquetReader.open(f.getPath))(r => assertEquals(r.rowIterator.size, 0))
  }

  test("write a file for external read-back when requested") {
    sys.env.get("ASHIDORI_PARQUET_OUT").foreach { path =>
      Using.resource(FileOutputStream(File(path)))(o => writeTo(o, 120_000, 50_000))
    }
  }

  test("row groups carry the time range; a window reads only the groups it overlaps") {
    val f = File.createTempFile("stats", ".parquet")
    f.deleteOnExit()
    Using.resource(FileOutputStream(f))(o => writeTo(o, 2345, 1000))
    val meta = ParquetReader.meta(f.getPath)
    val t0 = 1_700_000_000_000L
    assertEquals(meta.groups.map(_.time), Seq(Some((t0, t0 + 999)), Some((t0 + 1000, t0 + 1999)), Some((t0 + 2000, t0 + 2344))))
    assert(!meta.groups(0).overlaps(t0 + 1000, t0 + 1500))
    assert(meta.groups(1).overlaps(t0 + 1000, t0 + 1500))
    Using.resource(ParquetReader.open(f.getPath)) { r =>
      // Whole groups of the window: the second only.
      assertEquals(r.rowsBetween(t0 + 1200, t0 + 1300).map(_(0)).toVector, (t0 + 1000 until t0 + 2000).toVector)
      assertEquals(r.rowsBetween(t0 + 5000, t0 + 6000).size, 0)
    }
    // A file without statistics (earlier versions): every group may overlap.
    assert(ParquetReader.Group(10, Nil).overlaps(0L, 1L))
  }
