package ashidori.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable.ArrayBuffer
import scala.util.Using
import ashidori.core.ArchiveMerge.*
import ashidori.core.parquet.*

class ArchiveMergeSuite extends munit.FunSuite:
  private def r(vs: Any*): Row = vs.toIndexedSeq

  test("hash: equal rows hash alike, NaN like null, Int like Long, Float like Double") {
    assertEquals(hash(r(1L, 2.5, "a", null)), hash(r(1L, 2.5, "a", null)))
    assertEquals(hash(r(1L, Double.NaN)), hash(r(1L, null)))
    assertEquals(hash(r(1L, Float.NaN)), hash(r(1L, null)))
    assertEquals(hash(r(1L, 7)), hash(r(1L, 7L)))
    assertEquals(hash(r(1L, 0.5f)), hash(r(1L, 0.5)))
    assertNotEquals(hash(r(1L, "a", "b")), hash(r(1L, "b", "a")))
    assertNotEquals(hash(r(1L, null)), hash(r(1L, 0L)))
    assertNotEquals(hash(r(1L, "")), hash(r(1L, null)))
    // Distinct for a realistic run of IMU rows.
    val hs = (0 until 100_000).map(i => hash(r(1_700_000_000_000L + i / 100, 123_456_789L + i * 10_000L, "Accel", 0.1f * i, 9.8f, 0f)))
    assertEquals(hs.distinct.size, hs.size)
  }

  test("key: time of the first column, Long.MaxValue without one") {
    assertEquals(key(r(5L, "x")), 5L)
    assertEquals(key(r(null, "x")), Long.MaxValue)
    assertEquals(key(IndexedSeq.empty), Long.MaxValue)
  }

  test("Distinct drops exact repeats at the same time and keeps everything else") {
    val out = ArrayBuffer.empty[Row]
    val d = Distinct(out += _)
    Seq(r(1L, "a"), r(1L, "b"), r(1L, "a"), r(2L, "a"), r(2L, "a"), r(3L, "c"), r(3L, Double.NaN), r(3L, null)).foreach(d(_))
    // (NaN is not equal to itself: compare the text.)
    assertEquals(out.toSeq.map(_.toString), Seq(r(1L, "a"), r(1L, "b"), r(2L, "a"), r(3L, "c"), r(3L, Double.NaN)).map(_.toString))
    assertEquals(d.written, 5L)
    assertEquals(d.dropped, 3L)
  }

  test("merge keeps time order, puts existing rows first at equal times, and drops repeats") {
    val existing = Iterator(r(1L, "e1"), r(3L, "e3"), r(3L, "x"), r(7L, "e7"), r(null, "late"))
    val pushed = Iterator(r(0L, "n0"), r(3L, "x"), r(3L, "n3"), r(5L, "n5"), r(7L, "e7"), r(9L, "n9"))
    val out = ArrayBuffer.empty[Row]
    val n = merge(existing, pushed, out += _)
    assertEquals(out.toSeq, Seq(r(0L, "n0"), r(1L, "e1"), r(3L, "e3"), r(3L, "x"), r(3L, "n3"), r(5L, "n5"),
      r(7L, "e7"), r(9L, "n9"), r(null, "late")))
    assertEquals(n, out.size.toLong)
  }

  test("merging the same rows again changes nothing (a retried day)") {
    val rows = (0 until 1000).map(i => r(i / 7L, s"v$i", i))
    val once = ArrayBuffer.empty[Row]
    merge(Iterator.empty, rows.iterator, once += _)
    val twice = ArrayBuffer.empty[Row]
    merge(once.iterator, rows.iterator, twice += _)
    assertEquals(twice.toSeq, rows)
    // Part of the day already archived, the rest still in SQLite.
    val half = ArrayBuffer.empty[Row]
    merge(rows.take(400).iterator, rows.iterator, half += _)
    assertEquals(half.toSeq, rows)
  }

  test("pages reads a (time, rowid) ordered table chunk by chunk, ties included") {
    // (time, rowid) of a table: many rows share a time.
    val table = (0 until 10_007).map(i => (1000L + i / 13, i.toLong)).sortBy(identity)
    def fetch(c: Cursor) =
      table.filter((t, id) => t > c.time || (t == c.time && id > c.rowid)).take(500).toIndexedSeq
    var maxChunk = 0
    val all = pages(1000L, fetch, (t: (Long, Long)) => Cursor(t._1, t._2)).flatMap { c => maxChunk = maxChunk max c.size; c }.toVector
    assertEquals(all, table.toVector)
    assertEquals(maxChunk, 500)
    assertEquals(pages(0L, _ => IndexedSeq.empty[(Long, Long)], (t: (Long, Long)) => Cursor(t._1, t._2)).size, 0)
  }

  test("a large day streams through the writer and back by row groups") {
    val schema = Archive.Imu.columns
    val f = File.createTempFile("imu", ".parquet")
    f.deleteOnExit()
    val n = 120_000
    def row(i: Int): Row = r(1_700_000_000_000L + i / 3, 1_000_000L * i, Seq("Accel", "Gyro", "Mag")(i % 3), 0.01f * i,
      if i % 11 == 0 then Float.NaN else 9.8f, -1f)
    // Existing file: every third row, written in an earlier run.
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { o =>
      val w = ParquetWriter(o, schema, 5000)
      (0 until n by 3).foreach(i => w.write(row(i)))
      w.close()
    }
    val meta = ParquetReader.meta(f.getPath)
    assertEquals(meta.rows, 40_000L)
    assertEquals(meta.groups.size, 8)
    val g = File.createTempFile("imu2", ".parquet")
    g.deleteOnExit()
    val written = Using.resource(ParquetReader.open(f.getPath)) { existing =>
      Using.resource(BufferedOutputStream(FileOutputStream(g))) { o =>
        val w = ParquetWriter(o, schema, 7000)
        val k = merge(existing.rowIterator, (0 until n).iterator.map(row), w.write)
        w.close()
        k
      }
    }
    assertEquals(written, n.toLong)
    assertEquals(ParquetReader.meta(g.getPath).rows, n.toLong)
    Using.resource(ParquetReader.open(g.getPath)) { back =>
      assertEquals(back.groups, (n + 6999) / 7000)
      assertEquals(back.readGroup(0).rows, 7000)
      for (got, i) <- back.rowIterator.zipWithIndex do
        val e = row(i)
        assertEquals(hash(got), hash(e), s"row $i")
        assertEquals(got(0), e(0))
        assertEquals(got(2), e(2))
    }
    // The whole-file reader agrees.
    assertEquals(ParquetReader.readFile(g.getPath).rows, n)
  }

  test("Reorder puts nearly ordered rows in order, keeping the order of equal times") {
    val out = ArrayBuffer.empty[Row]
    val late = ArrayBuffer.empty[Row]
    val ro = Reorder[Row](100L, key, out += _, late += _)
    // (row time, stored time): gyro-like rows stored with the previous record's time.
    val in = Seq((1000L, "a", 1000L), (1003L, "g", 1000L), (1001L, "b", 1001L), (1001L, "c", 1001L), (995L, "m", 1001L),
      (1200L, "d", 1200L), (1500L, "e", 1500L))
    for (k, v, w) <- in do ro.push(r(k, v), w)
    // Up to 1500 - 100: everything before 1400 is out, in time order.
    assertEquals(out.map(_(1)).toSeq, Seq("m", "a", "b", "c", "g", "d"))
    ro.flush()
    assertEquals(out.map(_(1)).toSeq, Seq("m", "a", "b", "c", "g", "d", "e"))
    assertEquals(ro.late, 0L)
    // A row older than what was passed on: not written out of order, handed back.
    ro.push(r(10L, "x"), 1600L)
    assertEquals(late.map(_(1)).toSeq, Seq("x"))
    assertEquals(out.last(1), "e")
    assertEquals(ro.late, 1L)
  }

  test("reordered rows merge with an archived copy without repeats") {
    // Stored order (by the time of the previous record) differs from row order.
    val rows = (0 until 3000).map(i => (r(1000L + i * 3 + (if i % 3 == 1 then 2 else 0), s"v$i"), 1000L + (i / 3) * 9))
    def sorted(src: Seq[(Row, Long)]) =
      val out = ArrayBuffer.empty[Row]
      val ro = Reorder[Row](60_000L, key, out += _)
      src.foreach((row, w) => ro.push(row, w))
      ro.flush()
      out.toSeq
    val once = sorted(rows)
    assertEquals(once.map(key), once.map(key).sorted)
    val again = ArrayBuffer.empty[Row]
    merge(once.iterator, sorted(rows).iterator, again += _)
    assertEquals(again.toSeq, once)
  }

  test("FileMerge copies the archived groups before the new rows as stored, and decodes only where they meet") {
    val schema = Archive.Imu.columns
    def row(i: Int): Row = r(1_700_000_000_000L + i, 1_000L * i, "Accel", 0.5f * i, if i % 7 == 0 then Float.NaN else 1f, -1f)
    val old = File.createTempFile("old", ".parquet")
    old.deleteOnExit()
    // Archived: rows 0..9999 in groups of 3000 (the last group ends at 9999).
    Using.resource(BufferedOutputStream(FileOutputStream(old))) { o =>
      val w = ParquetWriter(o, schema, 3000)
      (0 until 10_000).foreach(i => w.write(row(i)))
      w.close()
    }
    val out = File.createTempFile("new", ".parquet")
    out.deleteOnExit()
    // New: 9000..14999 (overlapping the last 1000 archived rows, which repeat).
    val (written, copied) = Using.resource(ParquetReader.open(old.getPath)) { ex =>
      Using.resource(BufferedOutputStream(FileOutputStream(out))) { o =>
        val w = ParquetWriter(o, schema, 4000)
        val m = FileMerge(Some(ex), w)
        (9_000 until 15_000).foreach(i => m.push(row(i)))
        m.finish()
        w.close()
        (m.written, m.copied)
      }
    }
    assertEquals(written, 15_000L)
    // Groups 0..2 (rows 0..8999) lie before row 9000: copied, not decoded.
    assertEquals(copied, 9_000L)
    val meta = ParquetReader.meta(out.getPath)
    assertEquals(meta.rows, 15_000L)
    assertEquals(meta.groups.take(3).map(_.time), Seq(Some((1_700_000_000_000L, 1_700_000_002_999L)),
      Some((1_700_000_003_000L, 1_700_000_005_999L)), Some((1_700_000_006_000L, 1_700_000_008_999L))))
    Using.resource(ParquetReader.open(out.getPath)) { back =>
      for (got, i) <- back.rowIterator.zipWithIndex do assertEquals(hash(got), hash(row(i)), s"row $i")
    }
    assertEquals(ParquetReader.readFile(out.getPath).rows, 15_000)
  }

  test("FileMerge after the last new row copies the rest; without statistics it decodes") {
    val schema = Seq(parquet.Column("time", parquet.ColType.Int64), parquet.Column("v", parquet.ColType.Str))
    val old = File.createTempFile("old2", ".parquet")
    old.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(old))) { o =>
      val w = ParquetWriter(o, schema, 10)
      (0 until 50).foreach(i => w.write(Seq(100L + i, s"o$i")))
      w.write(Seq(null, "no time"))
      w.close()
    }
    val out = File.createTempFile("new2", ".parquet")
    out.deleteOnExit()
    Using.resource(ParquetReader.open(old.getPath)) { ex =>
      Using.resource(BufferedOutputStream(FileOutputStream(out))) { o =>
        val w = ParquetWriter(o, schema, 10)
        val m = FileMerge(Some(ex), w)
        m.push(r(5L, "early"))
        m.finish()
        w.close()
        // Groups after the new row: copied, but not the one with a row of no time.
        assertEquals(m.copied, 40L)
        assertEquals(m.written, 52L)
      }
    }
    val back = ParquetReader.readFile(out.getPath).rowIterator.map(_(1)).toVector
    assertEquals(back.head, "early")
    assertEquals(back.last, "no time")
    assertEquals(back.size, 52)
  }
