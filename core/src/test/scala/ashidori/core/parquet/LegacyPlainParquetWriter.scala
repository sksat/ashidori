// The writer as of 6671892, before DELTA_BINARY_PACKED and BYTE_STREAM_SPLIT
// (74f4e65): PLAIN values, no statistics, no key-value metadata. Verbatim
// apart from its name; Column, ColType and Thrift are the current ones
// (unchanged since). ParquetReaderEquivalenceSuite reads files written by it.
package ashidori.core.parquet

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream

final class LegacyPlainParquetWriter(out: OutputStream, schema: Seq[Column], rowGroupRows: Int = 50_000):
  import LegacyPlainParquetWriter.*

  private val sink = CountingStream(out)
  sink.write(Magic)

  private val values = schema.map(_ => ByteArrayOutputStream())
  private val defined = schema.map(_ => scala.collection.mutable.ArrayBuffer.empty[Boolean])
  private var rowsInGroup = 0
  private var totalRows = 0L
  private val rowGroups = Vector.newBuilder[RowGroupMeta]

  def write(row: Seq[Any]): Unit =
    require(row.size == schema.size, s"row has ${row.size} values, schema ${schema.size}")
    for ((v, c), i) <- row.zip(schema).zipWithIndex do
      val present = v match
        case null                  => false
        case d: Double if d.isNaN  => false
        case f: Float if f.isNaN   => false
        case _                     => true
      defined(i) += present
      if present then plain(values(i), c.tpe, v)
    rowsInGroup += 1
    if rowsInGroup >= rowGroupRows then flushGroup()

  private def plain(b: ByteArrayOutputStream, t: ColType, v: Any): Unit =
    t match
      case ColType.Int32   => le(b, long(v).toInt.toLong, 4)
      case ColType.Int64   => le(b, long(v), 8)
      case ColType.Float32 => le(b, java.lang.Float.floatToIntBits(double(v).toFloat).toLong, 4)
      case ColType.Float64 => le(b, java.lang.Double.doubleToLongBits(double(v)), 8)
      case ColType.Str =>
        val bytes = v.toString.getBytes(UTF_8)
        le(b, bytes.length.toLong, 4)
        b.write(bytes)

  private def long(v: Any): Long = v match
    case i: Int     => i.toLong
    case l: Long    => l
    case f: Float   => f.toLong
    case d: Double  => d.toLong
    case b: Boolean => if b then 1L else 0L
    case s: String  => s.toLong
    case other      => throw IllegalArgumentException(s"not a number: $other")

  private def double(v: Any): Double = v match
    case i: Int    => i.toDouble
    case l: Long   => l.toDouble
    case f: Float  => f.toDouble
    case d: Double => d
    case s: String => s.toDouble
    case other     => throw IllegalArgumentException(s"not a number: $other")

  private def flushGroup(): Unit =
    if rowsInGroup > 0 then
      val chunks = schema.zipWithIndex.map { (c, i) =>
        // Page body: definition levels (length-prefixed RLE) then values.
        val levels = rle(defined(i))
        val body = ByteArrayOutputStream()
        le(body, levels.length.toLong, 4)
        body.write(levels)
        values(i).writeTo(body)
        val raw = body.toByteArray
        val zipped =
          val z = ByteArrayOutputStream()
          val g = GZIPOutputStream(z)
          g.write(raw)
          g.close()
          z.toByteArray
        val header = Thrift()
        header.struct {
          header.i32(1, 0) // DATA_PAGE
          header.i32(2, raw.length)
          header.i32(3, zipped.length)
          header.structField(5) {
            header.i32(1, rowsInGroup)
            header.i32(2, 0) // PLAIN
            header.i32(3, 3) // RLE
            header.i32(4, 3) // RLE
          }
        }
        val offset = sink.count
        val hdr = header.bytes
        sink.write(hdr)
        sink.write(zipped)
        ChunkMeta(c, offset, hdr.length + raw.length, hdr.length + zipped.length, rowsInGroup)
      }
      rowGroups += RowGroupMeta(chunks, rowsInGroup)
      totalRows += rowsInGroup
      rowsInGroup = 0
      values.foreach(_.reset())
      defined.foreach(_.clear())

  /** Writes the footer; does not close the underlying stream. */
  def close(): Unit =
    flushGroup()
    val t = Thrift()
    t.struct {
      t.i32(1, 1) // version
      t.listField(2, Thrift.Struct, schema.size + 1) {
        t.struct {
          t.string(4, "schema")
          t.i32(5, schema.size)
        }
        for c <- schema do
          t.struct {
            t.i32(1, c.tpe.physical)
            t.i32(3, 1) // OPTIONAL
            t.string(4, c.name)
            if c.tpe == ColType.Str then t.i32(6, 0) // UTF8
          }
      }
      t.i64(3, totalRows)
      val groups = rowGroups.result()
      t.listField(4, Thrift.Struct, groups.size) {
        for g <- groups do
          t.struct {
            t.listField(1, Thrift.Struct, g.chunks.size) {
              for ch <- g.chunks do
                t.struct {
                  t.i64(2, ch.offset)
                  t.structField(3) {
                    t.i32(1, ch.column.tpe.physical)
                    t.listField(2, Thrift.I32, 2) {
                      t.rawI32(0) // PLAIN
                      t.rawI32(3) // RLE
                    }
                    t.listField(3, Thrift.Binary, 1)(t.rawString(ch.column.name))
                    t.i32(4, 2) // GZIP
                    t.i64(5, ch.rows)
                    t.i64(6, ch.uncompressed)
                    t.i64(7, ch.compressed)
                    t.i64(9, ch.offset)
                  }
                }
            }
            t.i64(2, g.chunks.map(_.uncompressed.toLong).sum)
            t.i64(3, g.rows)
          }
      }
      t.string(6, "ashidori")
    }
    val footer = t.bytes
    sink.write(footer)
    val len = ByteArrayOutputStream()
    le(len, footer.length.toLong, 4)
    sink.write(len.toByteArray)
    sink.write(Magic)
    sink.flush()

object LegacyPlainParquetWriter:
  private val Magic = "PAR1".getBytes(UTF_8)

  private final case class ChunkMeta(column: Column, offset: Long, uncompressed: Int, compressed: Int, rows: Int)
  private final case class RowGroupMeta(chunks: Seq[ChunkMeta], rows: Int)

  private def le(b: ByteArrayOutputStream, v: Long, bytes: Int): Unit =
    for i <- 0 until bytes do b.write(((v >>> (8 * i)) & 0xff).toInt)

  /** RLE/bit-packing hybrid with bit width 1, using RLE runs only. */
  def rle(levels: collection.IndexedSeq[Boolean]): Array[Byte] =
    val b = ByteArrayOutputStream()
    var i = 0
    while i < levels.size do
      var j = i
      while j < levels.size && levels(j) == levels(i) do j += 1
      Thrift.varint(b, ((j - i).toLong << 1))
      b.write(if levels(i) then 1 else 0)
      i = j
    b.toByteArray

  private final class CountingStream(out: OutputStream) extends OutputStream:
    var count = 0L
    override def write(b: Int): Unit = { out.write(b); count += 1 }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = { out.write(b, off, len); count += len }
    override def flush(): Unit = out.flush()

