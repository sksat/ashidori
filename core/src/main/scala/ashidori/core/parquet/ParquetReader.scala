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

  def read(bytes: Array[Byte]): Table =
    require(bytes.length >= 12 && String(bytes.take(4), UTF_8) == "PAR1" && String(bytes.takeRight(4), UTF_8) == "PAR1", "not parquet")
    val footerLen = ByteBuffer.wrap(bytes, bytes.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt
    val meta = ThriftReader(bytes, bytes.length - 8 - footerLen).struct()
    val schemaEls = meta(2).asInstanceOf[Seq[Map[Int, Any]]]
    val columns = schemaEls.tail.map { e =>
      val tpe = ColType.values.find(_.physical == e(1).asInstanceOf[Long].toInt).get
      Column(e(4).asInstanceOf[String], tpe)
    }
    val total = meta(3).asInstanceOf[Long].toInt
    val out = columns.map(c => c.name -> new Array[Any](total)).toMap
    var base = 0
    for rg <- meta.getOrElse(4, Nil).asInstanceOf[Seq[Map[Int, Any]]] do
      val n = rg(3).asInstanceOf[Long].toInt
      for (chunk, c) <- rg(1).asInstanceOf[Seq[Map[Int, Any]]].zip(columns) do
        val cm = chunk(3).asInstanceOf[Map[Int, Any]]
        val codec = cm(4).asInstanceOf[Long].toInt
        var pos = cm(9).asInstanceOf[Long].toInt
        var filled = 0
        while filled < n do
          val tr = ThriftReader(bytes, pos)
          val ph = tr.struct()
          pos = tr.position
          val compressed = ph(3).asInstanceOf[Long].toInt
          val dph = ph(5).asInstanceOf[Map[Int, Any]]
          val count = dph(1).asInstanceOf[Long].toInt
          val page = bytes.slice(pos, pos + compressed)
          pos += compressed
          val body = codec match
            case 0 => page
            case 2 => GZIPInputStream(ByteArrayInputStream(page)).readAllBytes()
            case x => throw IllegalArgumentException(s"unsupported codec $x")
          decodePage(body, count, c.tpe, dph(2).asInstanceOf[Long].toInt, out(c.name), base + filled)
          filled += count
      base += n
    Table(columns, total, out)

  def readFile(path: String): Table =
    val f = RandomAccessFile(path, "r")
    try
      val bytes = new Array[Byte](f.length.toInt)
      f.readFully(bytes)
      read(bytes)
    finally f.close()

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

/** Thrift compact protocol reader: structs become Map[fieldId, value]
  * (i32/i64 as Long, binary as String, lists as Seq).
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
      val s = String(b, position, len, UTF_8)
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
