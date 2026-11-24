package ashidori.core.parquet

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream

/** Column types the writer supports. */
enum ColType(val physical: Int):
  case Int32 extends ColType(1)
  case Int64 extends ColType(2)
  case Float32 extends ColType(4)
  case Float64 extends ColType(5)
  case Str extends ColType(6)

/** A nullable column. */
final case class Column(name: String, tpe: ColType)

/** Minimal Parquet writer for exports: flat schema of optional columns,
  * PLAIN values, RLE definition levels, one GZIP-compressed data page per
  * column per row group. Enough for pandas / DuckDB / Polars / Arrow to read,
  * without the Hadoop-based parquet-java (too heavy for Android).
  *
  * Values: Int (Int32, Int64), Long (Int64), Float (Float32, Float64),
  * Double (Float64) or String (Str), or null (also NaN for floating
  * columns) for missing. A value of another type is an error, not converted:
  * a row of another layout (a column left out or shifted) does not write.
  *
  * `meta` goes into the footer's key-value metadata.
  */
final class ParquetWriter(out: OutputStream, schema: Seq[Column], rowGroupRows: Int = 50_000,
                          meta: Seq[(String, String)] = Nil):
  import ParquetWriter.*

  private val sink = CountingStream(out)
  sink.write(Magic)

  // Present values per column until the page is encoded.
  private val columns = schema.toArray
  private val strings = Array.fill(columns.length)(ByteArrayOutputStream())
  private val longs = Array.fill(columns.length)(LongValues())
  private val doubles = Array.fill(columns.length)(DoubleValues())
  private val defined = Array.fill(columns.length)(BooleanValues())
  private var rowsInGroup = 0
  private var totalRows = 0L
  // Min and max of the first column (the time) in this row group, written as
  // the chunk's statistics so a reader can skip groups outside a time window.
  private val timeStats = schema.headOption.exists(c => c.tpe == ColType.Int64 || c.tpe == ColType.Int32)
  private var minTime = Long.MaxValue
  private var maxTime = Long.MinValue
  // A group with a row without a time gets no statistics (such rows sort last).
  private var nullTime = false
  private val rowGroups = Vector.newBuilder[RowGroupMeta]

  def write(row: Seq[Any]): Unit =
    require(row.size == columns.length, s"row has ${row.size} values, schema ${schema.size}")
    // The whole row is checked before any of it is taken.
    val check = row.iterator
    var i = 0
    while check.hasNext do
      val v = check.next()
      val c = columns(i)
      if v != null then
        val ok = (c.tpe, v) match
          case (ColType.Int32, _: Int)                 => true
          case (ColType.Int64, _: Long | _: Int)       => true
          case (ColType.Float32, _: Float)             => true
          case (ColType.Float64, _: Double | _: Float) => true
          case (ColType.Str, _: String)                => true
          case _                                       => false
        if !ok then throw IllegalArgumentException(s"column ${c.name} (${c.tpe}): ${v.getClass.getSimpleName} $v")
      i += 1
    val values = row.iterator
    i = 0
    while values.hasNext do
      val v = values.next()
      val c = columns(i)
      val present = v match
        case null                  => false
        case d: Double if d.isNaN  => false
        case f: Float if f.isNaN   => false
        case _                     => true
      defined(i).add(present)
      if i == 0 && !present then nullTime = true
      if present then
        c.tpe match
          case ColType.Int32 | ColType.Int64 =>
            val l = long(v)
            longs(i).add(l)
            if i == 0 then
              if l < minTime then minTime = l
              if l > maxTime then maxTime = l
          case ColType.Float32 | ColType.Float64 => doubles(i).add(double(v))
          case ColType.Str =>
            val bytes = v.toString.getBytes(UTF_8)
            le(strings(i), bytes.length.toLong, 4)
            strings(i).write(bytes)
      i += 1
    rowsInGroup += 1
    if rowsInGroup >= rowGroupRows then flushGroup()

  /** Encoded values of column i and the encoding id used. */
  private def encodeValues(i: Int): (Array[Byte], Int) =
    schema(i).tpe match
      case ColType.Int32 => (deltaBinaryPackedArray(longs(i).data, longs(i).size, 32), DeltaBinaryPacked)
      case ColType.Int64 => (deltaBinaryPackedArray(longs(i).data, longs(i).size, 64), DeltaBinaryPacked)
      case ColType.Float32 =>
        (byteStreamSplitDoubles(doubles(i).data, doubles(i).size, 4), ByteStreamSplit)
      case ColType.Float64 => (byteStreamSplitDoubles(doubles(i).data, doubles(i).size, 8), ByteStreamSplit)
      case ColType.Str     => (strings(i).toByteArray, Plain)

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
        val levels = rleArray(defined(i).data, defined(i).size)
        val body = ByteArrayOutputStream()
        le(body, levels.length.toLong, 4)
        body.write(levels)
        val (encoded, encoding) = encodeValues(i)
        body.write(encoded)
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
            header.i32(2, encoding)
            header.i32(3, 3) // RLE
            header.i32(4, 3) // RLE
          }
        }
        val offset = sink.count
        val hdr = header.bytes
        sink.write(hdr)
        sink.write(zipped)
        val stats = Option.when(i == 0 && timeStats && !nullTime && minTime <= maxTime)((minTime, maxTime))
        ChunkMeta(c, offset, hdr.length + raw.length, hdr.length + zipped.length, rowsInGroup, encoding, stats)
      }
      rowGroups += RowGroupMeta(chunks, rowsInGroup)
      totalRows += rowsInGroup
      rowsInGroup = 0
      minTime = Long.MaxValue
      maxTime = Long.MinValue
      nullTime = false
      strings.foreach(_.reset())
      longs.foreach(_.clear())
      doubles.foreach(_.clear())
      defined.foreach(_.clear())

  /** Rows written so far, in finished row groups and the one being filled. */
  def rows: Long = totalRows + rowsInGroup

  /** Appends row group `g` of another file of the same schema as it is
    * stored (`bytes`: [[ParquetReader.FileReader.rawGroup]]), without
    * decoding it. Ends the row group being filled first.
    */
  def copyGroup(bytes: Array[Byte], g: ParquetReader.Group): Unit =
    require(g.chunks.size == schema.size, s"group of ${g.chunks.size} columns, schema ${schema.size}")
    require(bytes.length.toLong == g.end - g.start, "bytes are not the group")
    flushGroup()
    val base = sink.count
    // In writes of at most 1 MiB: a guarded stream below checks each before it is written.
    var at = 0
    while at < bytes.length do
      val n = math.min(bytes.length - at, CopyChunk)
      sink.write(bytes, at, n)
      at += n
    val chunks = schema.zip(g.chunks).zipWithIndex.map { case ((c, ch), i) =>
      ChunkMeta(c, base + (ch.offset - g.start), ch.uncompressed.toInt, ch.length.toInt, g.rows, ch.encoding,
        if i == 0 && timeStats then g.time else None, ch.codec)
    }
    rowGroups += RowGroupMeta(chunks, g.rows)
    totalRows += g.rows

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
                      t.rawI32(ch.encoding)
                      t.rawI32(3) // RLE (definition levels)
                    }
                    t.listField(3, Thrift.Binary, 1)(t.rawString(ch.column.name))
                    t.i32(4, ch.codec)
                    t.i64(5, ch.rows)
                    t.i64(6, ch.uncompressed)
                    t.i64(7, ch.compressed)
                    t.i64(9, ch.offset)
                    ch.stats.foreach { (lo, hi) =>
                      // Statistics: max_value, min_value (plain little-endian).
                      val width = if ch.column.tpe == ColType.Int32 then 4 else 8
                      def bytes(v: Long) = Array.tabulate[Byte](width)(k => ((v >>> (8 * k)) & 0xff).toByte)
                      t.structField(12) {
                        t.binary(5, bytes(hi))
                        t.binary(6, bytes(lo))
                      }
                    }
                  }
                }
            }
            t.i64(2, g.chunks.map(_.uncompressed.toLong).sum)
            t.i64(3, g.rows)
          }
      }
      if meta.nonEmpty then
        t.listField(5, Thrift.Struct, meta.size) {
          for (k, v) <- meta do
            t.struct {
              t.string(1, k)
              t.string(2, v)
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

object ParquetWriter:
  /** The largest single write of a copied row group. */
  val CopyChunk: Int = 1 << 20
  private val Magic = "PAR1".getBytes(UTF_8)

  private final case class ChunkMeta(column: Column, offset: Long, uncompressed: Int, compressed: Int, rows: Int, encoding: Int,
                                     stats: Option[(Long, Long)] = None, codec: Int = 2)

  val Plain = 0
  val DeltaBinaryPacked = 5
  val ByteStreamSplit = 9

  // Present values of a column until its page is encoded, unboxed. Cleared
  // after each row group, keeping the array for the next.
  private final class LongValues:
    var data = new Array[Long](128)
    var size = 0
    def add(v: Long): Unit =
      if size == data.length then data = java.util.Arrays.copyOf(data, data.length * 2)
      data(size) = v
      size += 1
    def clear(): Unit = size = 0

  private final class DoubleValues:
    var data = new Array[Double](128)
    var size = 0
    def add(v: Double): Unit =
      if size == data.length then data = java.util.Arrays.copyOf(data, data.length * 2)
      data(size) = v
      size += 1
    def clear(): Unit = size = 0

  private final class BooleanValues:
    var data = new Array[Boolean](128)
    var size = 0
    def add(v: Boolean): Unit =
      if size == data.length then data = java.util.Arrays.copyOf(data, data.length * 2)
      data(size) = v
      size += 1
    def clear(): Unit = size = 0

  /** BYTE_STREAM_SPLIT: byte k of every value, then byte k+1, ... Groups the
    * slowly-changing high bytes of time series so GZIP compresses them well.
    */
  def byteStreamSplit(bits: collection.IndexedSeq[Long], width: Int): Array[Byte] =
    val n = bits.size
    val out = new Array[Byte](n * width)
    var k = 0
    while k < width do
      var i = 0
      while i < n do
        out(k * n + i) = (bits(i) >>> (8 * k)).toByte
        i += 1
      k += 1
    out

  private def byteStreamSplitDoubles(values: Array[Double], n: Int, width: Int): Array[Byte] =
    val out = new Array[Byte](n * width)
    var i = 0
    while i < n do
      val bits = if width == 4 then java.lang.Float.floatToIntBits(values(i).toFloat).toLong & 0xffffffffL
                 else java.lang.Double.doubleToLongBits(values(i))
      var k = 0
      while k < width do
        out(k * n + i) = (bits >>> (8 * k)).toByte
        k += 1
      i += 1
    out

  /** DELTA_BINARY_PACKED (blocks of 128 values, 4 miniblocks of 32): stores
    * the first value and bit-packed deltas, ideal for timestamps and ids.
    */
  def deltaBinaryPacked(values: collection.IndexedSeq[Long], typeBits: Int): Array[Byte] =
    val data = new Array[Long](values.size)
    var i = 0
    while i < data.length do
      data(i) = values(i)
      i += 1
    deltaBinaryPackedArray(data, data.length, typeBits)

  private def deltaBinaryPackedArray(values: Array[Long], n: Int, typeBits: Int): Array[Byte] =
    val b = ByteArrayOutputStream()
    Thrift.varint(b, 128)
    Thrift.varint(b, 4)
    Thrift.varint(b, n.toLong)
    Thrift.varint(b, Thrift.zigzag(if n == 0 then 0L else values(0)))
    val deltas = new Array[Long](128)
    val widths = new Array[Int](4)
    var start = 1
    while start < n do
      val count = math.min(128, n - start)
      var minDelta = Long.MaxValue
      var i = 0
      while i < count do
        val d = values(start + i) - values(start + i - 1)
        val wrapped = if typeBits == 32 then d.toInt.toLong else d
        deltas(i) = wrapped
        if wrapped < minDelta then minDelta = wrapped
        i += 1
      Thrift.varint(b, Thrift.zigzag(minDelta))
      java.util.Arrays.fill(widths, 0)
      i = 0
      while i < count do
        val d = deltas(i) - minDelta
        val adjusted = if typeBits == 32 then d.toInt.toLong & 0xffffffffL else d
        deltas(i) = adjusted
        val width = 64 - java.lang.Long.numberOfLeadingZeros(adjusted)
        val m = i >>> 5
        if width > widths(m) then widths(m) = width
        i += 1
      i = 0
      while i < 4 do
        b.write(widths(i))
        i += 1
      var m = 0
      while m < (count + 31) / 32 do
        val width = widths(m)
        // Exactly 32 padded values; unused bits remain zero in the byte array.
        val packed = new Array[Byte](width * 4)
        val end = math.min(count, (m + 1) * 32)
        i = m * 32
        while i < end do
          val value = deltas(i)
          var bit = 0
          while bit < width do
            val pos = (i - m * 32) * width + bit
            val byteIndex = pos >>> 3
            val space = 8 - (pos & 7)
            val take = math.min(width - bit, space)
            val mask = (1L << take) - 1L
            packed(byteIndex) = (packed(byteIndex) | (((value >>> bit) & mask) << (pos & 7)).toInt).toByte
            bit += take
          i += 1
        b.write(packed)
        m += 1
      start += count
    b.toByteArray
  private final case class RowGroupMeta(chunks: Seq[ChunkMeta], rows: Int)

  private def le(b: ByteArrayOutputStream, v: Long, bytes: Int): Unit =
    for i <- 0 until bytes do b.write(((v >>> (8 * i)) & 0xff).toInt)

  /** RLE/bit-packing hybrid with bit width 1, using RLE runs only. */
  def rle(levels: collection.IndexedSeq[Boolean]): Array[Byte] =
    val data = new Array[Boolean](levels.size)
    var i = 0
    while i < data.length do
      data(i) = levels(i)
      i += 1
    rleArray(data, data.length)

  private def rleArray(levels: Array[Boolean], n: Int): Array[Byte] =
    val b = ByteArrayOutputStream()
    var i = 0
    while i < n do
      var j = i + 1
      while j < n && levels(j) == levels(i) do j += 1
      Thrift.varint(b, ((j - i).toLong << 1))
      b.write(if levels(i) then 1 else 0)
      i = j
    b.toByteArray

  private final class CountingStream(out: OutputStream) extends OutputStream:
    var count = 0L
    override def write(b: Int): Unit = { out.write(b); count += 1 }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = { out.write(b, off, len); count += len }
    override def flush(): Unit = out.flush()

/** Thrift compact protocol, just what Parquet metadata needs. */
private[parquet] final class Thrift:
  import Thrift.*
  private val b = ByteArrayOutputStream()
  private var lastId = List(0)

  def bytes: Array[Byte] = b.toByteArray

  private def fieldHeader(id: Int, tpe: Int): Unit =
    val delta = id - lastId.head
    if delta > 0 && delta <= 15 then b.write((delta << 4) | tpe)
    else
      b.write(tpe)
      varint(b, zigzag(id.toLong))
    lastId = id :: lastId.tail

  def i32(id: Int, v: Int): Unit = { fieldHeader(id, I32); varint(b, zigzag(v.toLong)) }
  def i64(id: Int, v: Long): Unit = { fieldHeader(id, I64); varint(b, zigzag(v)) }
  def string(id: Int, s: String): Unit = { fieldHeader(id, Binary); rawString(s) }
  def binary(id: Int, bytes: Array[Byte]): Unit =
    fieldHeader(id, Binary)
    varint(b, bytes.length.toLong)
    b.write(bytes)
  def rawI32(v: Int): Unit = varint(b, zigzag(v.toLong))
  def rawString(s: String): Unit =
    val bytes = s.getBytes(UTF_8)
    varint(b, bytes.length.toLong)
    b.write(bytes)

  /** A struct value (top level or list element). */
  def struct(body: => Unit): Unit =
    lastId = 0 :: lastId
    body
    b.write(0) // stop
    lastId = lastId.tail

  def structField(id: Int)(body: => Unit): Unit =
    fieldHeader(id, Struct)
    struct(body)

  def listField(id: Int, elem: Int, size: Int)(body: => Unit): Unit =
    fieldHeader(id, ListType)
    if size < 15 then b.write((size << 4) | elem)
    else
      b.write(0xf0 | elem)
      varint(b, size.toLong)
    body

private[parquet] object Thrift:
  val I32 = 5
  val I64 = 6
  val Binary = 8
  val ListType = 9
  val Struct = 12

  def zigzag(v: Long): Long = (v << 1) ^ (v >> 63)

  def varint(b: ByteArrayOutputStream, v0: Long): Unit =
    var v = v0
    while (v & ~0x7fL) != 0 do
      b.write(((v & 0x7f) | 0x80).toInt)
      v >>>= 7
    b.write(v.toInt)
