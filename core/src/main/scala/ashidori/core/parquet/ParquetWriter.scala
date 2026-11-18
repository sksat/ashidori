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
  private val strings = schema.map(_ => ByteArrayOutputStream())
  private val longs = schema.map(_ => scala.collection.mutable.ArrayBuffer.empty[Long])
  private val doubles = schema.map(_ => scala.collection.mutable.ArrayBuffer.empty[Double])
  private val defined = schema.map(_ => scala.collection.mutable.ArrayBuffer.empty[Boolean])
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
    require(row.size == schema.size, s"row has ${row.size} values, schema ${schema.size}")
    // The whole row is checked before any of it is taken.
    for (v, c) <- row.zip(schema) if v != null do
      val ok = (c.tpe, v) match
        case (ColType.Int32, _: Int)                 => true
        case (ColType.Int64, _: Long | _: Int)       => true
        case (ColType.Float32, _: Float)             => true
        case (ColType.Float64, _: Double | _: Float) => true
        case (ColType.Str, _: String)                => true
        case _                                       => false
      if !ok then throw IllegalArgumentException(s"column ${c.name} (${c.tpe}): ${v.getClass.getSimpleName} $v")
    for ((v, c), i) <- row.zip(schema).zipWithIndex do
      val present = v match
        case null                  => false
        case d: Double if d.isNaN  => false
        case f: Float if f.isNaN   => false
        case _                     => true
      defined(i) += present
      if i == 0 && !present then nullTime = true
      if present then
        c.tpe match
          case ColType.Int32 | ColType.Int64 =>
            val l = long(v)
            longs(i) += l
            if i == 0 then
              if l < minTime then minTime = l
              if l > maxTime then maxTime = l
          case ColType.Float32 | ColType.Float64 => doubles(i) += double(v)
          case ColType.Str =>
            val bytes = v.toString.getBytes(UTF_8)
            le(strings(i), bytes.length.toLong, 4)
            strings(i).write(bytes)
    rowsInGroup += 1
    if rowsInGroup >= rowGroupRows then flushGroup()

  /** Encoded values of column i and the encoding id used. */
  private def encodeValues(i: Int): (Array[Byte], Int) =
    schema(i).tpe match
      case ColType.Int32 => (deltaBinaryPacked(longs(i), 32), DeltaBinaryPacked)
      case ColType.Int64 => (deltaBinaryPacked(longs(i), 64), DeltaBinaryPacked)
      case ColType.Float32 =>
        (byteStreamSplit(doubles(i).map(d => java.lang.Float.floatToIntBits(d.toFloat).toLong & 0xffffffffL), 4), ByteStreamSplit)
      case ColType.Float64 => (byteStreamSplit(doubles(i).map(java.lang.Double.doubleToLongBits), 8), ByteStreamSplit)
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
        val levels = rle(defined(i))
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

  /** BYTE_STREAM_SPLIT: byte k of every value, then byte k+1, ... Groups the
    * slowly-changing high bytes of time series so GZIP compresses them well.
    */
  def byteStreamSplit(bits: collection.IndexedSeq[Long], width: Int): Array[Byte] =
    val n = bits.size
    val out = new Array[Byte](n * width)
    for i <- 0 until n; k <- 0 until width do out(k * n + i) = ((bits(i) >>> (8 * k)) & 0xff).toByte
    out

  /** DELTA_BINARY_PACKED (blocks of 128 values, 4 miniblocks of 32): stores
    * the first value and bit-packed deltas, ideal for timestamps and ids.
    */
  def deltaBinaryPacked(values: collection.IndexedSeq[Long], typeBits: Int): Array[Byte] =
    val b = ByteArrayOutputStream()
    val blockSize = 128
    val miniblocks = 4
    val perMini = blockSize / miniblocks
    Thrift.varint(b, blockSize.toLong)
    Thrift.varint(b, miniblocks.toLong)
    Thrift.varint(b, values.size.toLong)
    Thrift.varint(b, Thrift.zigzag(if values.isEmpty then 0L else values(0)))
    def wrap(d: Long) = if typeBits == 32 then d.toInt.toLong else d
    val deltas = (1 until values.size).map(i => wrap(values(i) - values(i - 1)))
    for block <- deltas.grouped(blockSize) do
      val minDelta = block.min
      Thrift.varint(b, Thrift.zigzag(minDelta))
      val minis = block.grouped(perMini).toVector
      val adjusted = minis.map(_.map(d => wrap(d - minDelta) & (if typeBits == 32 then 0xffffffffL else -1L)))
      val widths = (0 until miniblocks).map { m =>
        adjusted.lift(m).map(ms => ms.map(v => 64 - java.lang.Long.numberOfLeadingZeros(v)).max).getOrElse(0)
      }
      widths.foreach(w => b.write(w))
      for (ms, m) <- adjusted.zipWithIndex do
        val w = widths(m)
        // Bit-pack 32 values (pad the last miniblock with zeros), LSB first.
        val padded = ms ++ Seq.fill(perMini - ms.size)(0L)
        var acc = BigInt(0)
        var nbits = 0
        val bytes = ByteArrayOutputStream()
        for v <- padded do
          acc |= BigInt(v) << nbits
          nbits += w
          while nbits >= 8 do
            bytes.write((acc & 0xff).toInt)
            acc >>= 8
            nbits -= 8
        if nbits > 0 then bytes.write((acc & 0xff).toInt)
        b.write(bytes.toByteArray)
    b.toByteArray
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
