package ashidori.core.parquet

import java.io.{ByteArrayInputStream, RandomAccessFile}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream
import scala.collection.mutable.ArrayBuffer

/** Reads files written by [[ParquetWriter]]: flat optional columns, PLAIN
  * values, RLE definition levels, data page v1, GZIP or uncompressed. Not a
  * general Parquet reader.
  */
object ParquetReader:

  final case class Table(columns: Seq[Column], rows: Int, data: Map[String, Array[Any]]):
    def col(name: String): Array[Any] = data(name)
    /** Rows as value sequences in schema order (null for missing). */
    def rowIterator: Iterator[IndexedSeq[Any]] =
      val arrays = columns.map(c => data(c.name)).toIndexedSeq
      Iterator.range(0, rows).map(i => arrays.map(_(i)))

  /** Footer of a file: the schema, the total row count and where each row
    * group's column chunks lie (absolute byte offsets).
    */
  final case class Meta(columns: Seq[Column], rows: Long, groups: Seq[Group])

  /** One row group: its row count, and per column the chunk's codec, start
    * and length.
    */
  /** `time`: min and max of the first column, when the file has statistics. */
  final case class Group(rows: Int, chunks: Seq[Chunk], time: Option[(Long, Long)] = None):
    /** Whether the group may hold times in [from, to). */
    def overlaps(from: Long, to: Long): Boolean = time.forall((lo, hi) => hi >= from && lo < to)
    def start: Long = chunks.map(_.offset).minOption.getOrElse(0L)
    def end: Long = chunks.map(c => c.offset + c.length).maxOption.getOrElse(0L)

  /** A column chunk: codec, where its (one) data page starts, its size
    * compressed and not, its values and their encoding.
    */
  final case class Chunk(codec: Int, offset: Long, length: Long, uncompressed: Long = 0, values: Long = 0, encoding: Int = 0)

  private def parseMeta(bytes: Array[Byte], at: Int): Meta =
    val meta = ThriftReader(bytes, at).struct()
    val schemaEls = meta(2).asInstanceOf[Seq[Map[Int, Any]]]
    val columns = schemaEls.tail.map { e =>
      val tpe = ColType.values.find(_.physical == e(1).asInstanceOf[Long].toInt).get
      Column(e(4).toString, tpe)
    }
    def le(b: Array[Byte]): Long = b.indices.foldLeft(0L)((acc, k) => acc | ((b(k) & 0xffL) << (8 * k)))
    def signed(v: Long, width: Int): Long = if width == 4 then v.toInt.toLong else v
    val groups = meta.getOrElse(4, Nil).asInstanceOf[Seq[Map[Int, Any]]].map { rg =>
      val metas = rg(1).asInstanceOf[Seq[Map[Int, Any]]].map(_(3).asInstanceOf[Map[Int, Any]])
      val chunks = metas.map { cm =>
        val encoding = cm.get(2).collect { case es: Seq[?] => es }.flatMap(_.headOption).map(_.asInstanceOf[Long].toInt).getOrElse(0)
        Chunk(cm(4).asInstanceOf[Long].toInt, cm(9).asInstanceOf[Long], cm(7).asInstanceOf[Long],
          cm.get(6).map(_.asInstanceOf[Long]).getOrElse(0L), cm.get(5).map(_.asInstanceOf[Long]).getOrElse(0L), encoding)
      }
      val time = metas.headOption.flatMap(_.get(12)).collect { case st: Map[?, ?] =>
        val m = st.asInstanceOf[Map[Int, Any]]
        for
          case hi: Bin <- m.get(5)
          case lo: Bin <- m.get(6)
          if (hi.bytes.length == 8 || hi.bytes.length == 4) && lo.bytes.length == hi.bytes.length
        yield (signed(le(lo.bytes), lo.bytes.length), signed(le(hi.bytes), hi.bytes.length))
      }.flatten
      Group(rg(3).asInstanceOf[Long].toInt, chunks, time)
    }
    Meta(columns, meta(3).asInstanceOf[Long], groups)

  private def footerLength(tail: Array[Byte]): Int =
    require(String(tail, tail.length - 4, 4, UTF_8) == "PAR1", "not parquet")
    ByteBuffer.wrap(tail, tail.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt

  /** Decodes row group `g` into `out` at row `base`. `bytes` holds the file
    * from absolute offset `origin` on.
    */
  private def decodeGroup(bytes: Array[Byte], origin: Long, columns: Seq[Column], g: Group, out: Seq[Array[Any]], base: Int): Unit =
    for ((chunk, c), k) <- g.chunks.zip(columns).zipWithIndex do
      var pos = (chunk.offset - origin).toInt
      var filled = 0
      while filled < g.rows do
        val tr = ThriftReader(bytes, pos)
        val ph = tr.struct()
        pos = tr.position
        val compressed = ph(3).asInstanceOf[Long].toInt
        val dph = ph(5).asInstanceOf[Map[Int, Any]]
        val count = dph(1).asInstanceOf[Long].toInt
        val page = java.util.Arrays.copyOfRange(bytes, pos, pos + compressed)
        pos += compressed
        val body = chunk.codec match
          case 0 => page
          case 2 => GZIPInputStream(ByteArrayInputStream(page)).readAllBytes()
          case x => throw IllegalArgumentException(s"unsupported codec $x")
        decodePage(body, count, c.tpe, dph(2).asInstanceOf[Long].toInt, out(k), base + filled)
        filled += count

  def read(bytes: Array[Byte]): Table =
    require(bytes.length >= 12 && String(bytes.take(4), UTF_8) == "PAR1", "not parquet")
    val footerLen = footerLength(bytes)
    val meta = parseMeta(bytes, bytes.length - 8 - footerLen)
    val total = meta.rows.toInt
    val arrays = meta.columns.map(_ => new Array[Any](total))
    var base = 0
    for g <- meta.groups do
      decodeGroup(bytes, 0L, meta.columns, g, arrays, base)
      base += g.rows
    Table(meta.columns, total, meta.columns.map(_.name).zip(arrays).toMap)

  def readFile(path: String): Table =
    val f = RandomAccessFile(path, "r")
    try
      val bytes = new Array[Byte](f.length.toInt)
      f.readFully(bytes)
      read(bytes)
    finally f.close()

  /** A file opened for reading one row group at a time: memory stays at
    * about one group however large the file is.
    *
    * Rows come in [[columns]]: the columns asked for when it was opened, or
    * else the file's own. A column asked for that the file does not have
    * (by name and type) reads as null; a column of the file not asked for
    * is left out ([[dropped]]).
    */
  final class FileReader private[ParquetReader] (raf: RandomAccessFile, val meta: Meta, as: Seq[Column]) extends AutoCloseable:
    val columns: Seq[Column] = if as.isEmpty then meta.columns else as
    /** Rows come as stored: row groups can be copied as they are ([[rawGroup]]). */
    val asStored: Boolean = columns == meta.columns
    /** Columns of the file that rows leave out. */
    def dropped: Seq[Column] = meta.columns.filterNot(columns.contains)
    def rows: Long = meta.rows
    def groups: Int = meta.groups.size

    /** Row group `i` alone. */
    /** The bytes of row group `i` as stored (its column chunks, in order). */
    def rawGroup(i: Int): Array[Byte] =
      val g = meta.groups(i)
      val bytes = new Array[Byte]((g.end - g.start).toInt)
      raf.seek(g.start)
      raf.readFully(bytes)
      bytes

    def readGroup(i: Int): Table =
      val g = meta.groups(i)
      val bytes = new Array[Byte]((g.end - g.start).toInt)
      raf.seek(g.start)
      raf.readFully(bytes)
      val arrays = meta.columns.map(_ => new Array[Any](g.rows))
      decodeGroup(bytes, g.start, meta.columns, g, arrays, 0)
      val stored = meta.columns.zip(arrays).toMap
      Table(columns, g.rows, columns.map(c => c.name -> stored.getOrElse(c, new Array[Any](g.rows))).toMap)

    /** Every row in file order, decoding one row group at a time. */
    def rowIterator: Iterator[IndexedSeq[Any]] =
      Iterator.range(0, groups).flatMap(i => readGroup(i).rowIterator)

    /** The rows of the groups that may hold times in [from, to) (all groups
      * of a file without statistics), one group at a time. Not filtered
      * by row.
      */
    def rowsBetween(from: Long, to: Long): Iterator[IndexedSeq[Any]] =
      Iterator.range(0, groups).filter(i => meta.groups(i).overlaps(from, to)).flatMap(i => readGroup(i).rowIterator)

    def close(): Unit = raf.close()

  /** Opens a file; its rows in `as` (matched by name and type) if given. */
  def open(path: String, as: Seq[Column] = Nil): FileReader =
    val raf = RandomAccessFile(path, "r")
    try FileReader(raf, readMeta(raf), as)
    catch
      case e: Throwable =>
        raf.close()
        throw e

  private def readMeta(raf: RandomAccessFile): Meta =
    val len = raf.length
    require(len >= 12, "not parquet")
    val tail = new Array[Byte](8)
    raf.seek(len - 8)
    raf.readFully(tail)
    val footerLen = footerLength(tail)
    require(footerLen > 0 && footerLen <= len - 12, "bad footer length")
    val footer = new Array[Byte](footerLen)
    raf.seek(len - 8 - footerLen)
    raf.readFully(footer)
    parseMeta(footer, 0)

  /** The footer of a file alone (row counts, groups), without reading data. */
  def meta(path: String): Meta =
    val raf = RandomAccessFile(path, "r")
    try readMeta(raf)
    finally raf.close()

  private def decodePage(body: Array[Byte], count: Int, t: ColType, encoding: Int, into: Array[Any], at: Int): Unit =
    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
    val levelsLen = bb.getInt()
    val defined = rleDecode(body, 4, levelsLen, count)
    val start = 4 + levelsLen
    val present = defined.count(identity)
    encoding match
      case ParquetWriter.DeltaBinaryPacked =>
        val vs = deltaDecode(body, start, present)
        var k = 0
        for i <- 0 until count do
          into(at + i) =
            if !defined(i) then null
            else
              val v = vs(k)
              k += 1
              if t == ColType.Int32 then v.toInt else v
        return
      case ParquetWriter.ByteStreamSplit =>
        val width = if t == ColType.Float32 then 4 else 8
        var k = 0
        for i <- 0 until count do
          into(at + i) =
            if !defined(i) then null
            else
              var bits = 0L
              for j <- 0 until width do bits |= (body(start + j * present + k) & 0xffL) << (8 * j)
              k += 1
              if width == 4 then java.lang.Float.intBitsToFloat(bits.toInt) else java.lang.Double.longBitsToDouble(bits)
        return
      case _ => ()
    bb.position(start)
    for i <- 0 until count do
      into(at + i) =
        if !defined(i) then null
        else
          t match
            case ColType.Int32   => bb.getInt()
            case ColType.Int64   => bb.getLong()
            case ColType.Float32 => bb.getFloat()
            case ColType.Float64 => bb.getDouble()
            case ColType.Str =>
              val len = bb.getInt()
              val s = String(body, bb.position(), len, UTF_8)
              bb.position(bb.position() + len)
              s

  private def deltaDecode(b: Array[Byte], start: Int, expected: Int): Array[Long] =
    var p = start
    def varint(): Long =
      var r = 0L
      var shift = 0
      var more = true
      while more do
        val x = b(p) & 0xff
        p += 1
        r |= (x & 0x7f).toLong << shift
        shift += 7
        more = (x & 0x80) != 0
      r
    def zigzag(): Long =
      val v = varint()
      (v >>> 1) ^ -(v & 1)
    val blockSize = varint().toInt
    val miniblocks = varint().toInt
    val total = varint().toInt
    val out = new Array[Long](total)
    if total == 0 then return out
    out(0) = zigzag()
    var n = 1
    val perMini = blockSize / miniblocks
    while n < total do
      val minDelta = zigzag()
      val widths = (0 until miniblocks).map(_ => b(p + 0) & 0xff).toArray
      for m <- 0 until miniblocks do widths(m) = b(p + m) & 0xff
      p += miniblocks
      var m = 0
      while m < miniblocks && n < total do
        val w = widths(m)
        var bitPos = 0L
        val base = p
        for _ <- 0 until perMini do
          var v = 0L
          for bit <- 0 until w do
            val pos = bitPos + bit
            if ((b(base + (pos >> 3).toInt) >> (pos & 7).toInt) & 1) == 1 then v |= 1L << bit
          bitPos += w
          if n < total then
            out(n) = out(n - 1) + minDelta + v
            n += 1
        p += perMini * w / 8
        m += 1
    out

  /** RLE/bit-packed hybrid, bit width 1. */
  private def rleDecode(b: Array[Byte], start: Int, len: Int, count: Int): Array[Boolean] =
    val out = ArrayBuffer.empty[Boolean]
    var p = start
    val end = start + len
    while p < end && out.size < count do
      var header = 0L
      var shift = 0
      var more = true
      while more do
        val x = b(p) & 0xff
        p += 1
        header |= (x & 0x7f).toLong << shift
        shift += 7
        more = (x & 0x80) != 0
      if (header & 1) == 0 then
        val run = (header >>> 1).toInt
        val v = (b(p) & 1) == 1
        p += 1
        for _ <- 0 until run do out += v
      else
        val groups = (header >>> 1).toInt
        for g <- 0 until groups; bit <- 0 until 8 do out += ((b(p + g) >> bit) & 1) == 1
        p += groups
    out.take(count).toArray

/** A Thrift binary value: bytes, or (toString) the UTF-8 string. */
private final class Bin(val bytes: Array[Byte]):
  override def toString: String = String(bytes, UTF_8)

/** Thrift compact protocol reader: structs become Map[fieldId, value]
  * (i32/i64 as Long, binary as [[Bin]], lists as Seq).
  */
private final class ThriftReader(b: Array[Byte], start: Int):
  var position: Int = start

  private def byte(): Int =
    val v = b(position) & 0xff
    position += 1
    v

  private def varint(): Long =
    var r = 0L
    var shift = 0
    var more = true
    while more do
      val x = byte()
      r |= (x & 0x7f).toLong << shift
      shift += 7
      more = (x & 0x80) != 0
    r

  private def zigzag(): Long =
    val v = varint()
    (v >>> 1) ^ -(v & 1)

  private def value(tpe: Int): Any = tpe match
    case 1 => true
    case 2 => false
    case 3 => byte().toLong
    case 4 | 5 | 6 => zigzag()
    case 7 =>
      val v = ByteBuffer.wrap(b, position, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble
      position += 8
      v
    case 8 =>
      val len = varint().toInt
      val s = Bin(java.util.Arrays.copyOfRange(b, position, position + len))
      position += len
      s
    case 9 | 10 =>
      val h = byte()
      val size = if (h >> 4) == 15 then varint().toInt else h >> 4
      val elem = h & 0x0f
      (0 until size).map(_ => if elem == 1 || elem == 2 then byte() == 1 else value(elem))
    case 12 => struct()
    case x  => throw IllegalArgumentException(s"thrift type $x")

  def struct(): Map[Int, Any] =
    val fields = Map.newBuilder[Int, Any]
    var last = 0
    var done = false
    while !done do
      val h = byte()
      if h == 0 then done = true
      else
        val tpe = h & 0x0f
        val delta = h >> 4
        val id = if delta != 0 then last + delta else zigzag().toInt
        last = id
        fields += id -> value(tpe)
    fields.result()
