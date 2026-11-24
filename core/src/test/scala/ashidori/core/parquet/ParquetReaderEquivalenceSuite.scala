package ashidori.core.parquet

import java.io.{ByteArrayOutputStream, File, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream
import scala.util.{Random, Try, Using}
import ashidori.core.Archive

/** The reader decodes exactly what the reader of app 0.24.0
  * ([[ReferenceParquetReader]]) decodes: the same values of the same boxed
  * types, NaN and -0.0 by their bits, and the same footer, on
  *  - every table and every layout of the archive, written by the current
  *    writer and by the PLAIN writer of the first archives;
  *  - the web viewer's fixtures;
  *  - files assembled here with what the writer never writes: several
  *    pages per chunk, empty pages, uncompressed pages, bit-packed levels,
  *    other DELTA_BINARY_PACKED block layouts, wrong page sizes in headers,
  *    GZIP of several members, dictionary pages.
  * A file one of them cannot read, the other cannot read either.
  */
class ParquetReaderEquivalenceSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(3, "min")

  // --- comparison -----------------------------------------------------------

  private def sameValue(a: Any, b: Any): Boolean = (a, b) match
    case (null, null)           => true
    case (x: Double, y: Double) => java.lang.Double.doubleToRawLongBits(x) == java.lang.Double.doubleToRawLongBits(y)
    case (x: Float, y: Float)   => java.lang.Float.floatToRawIntBits(x) == java.lang.Float.floatToRawIntBits(y)
    case (null, _) | (_, null)  => false
    case _                      => a.getClass == b.getClass && a == b

  private def sameRows(what: String, now: Iterator[IndexedSeq[Any]], old: Iterator[IndexedSeq[Any]]): Int =
    var n = 0
    while now.hasNext || old.hasNext do
      assert(now.hasNext && old.hasNext, s"$what: row count differs after $n rows")
      val a = now.next()
      val b = old.next()
      assertEquals(a.size, b.size, s"$what: row $n")
      for k <- a.indices do
        assert(sameValue(a(k), b(k)), s"$what: row $n column $k: ${a(k)} (${Option(a(k)).map(_.getClass)}) vs ${b(k)}")
      n += 1
    n

  private def sameTable(what: String, a: ParquetReader.Table, b: ReferenceParquetReader.Table): Unit =
    assertEquals(a.columns, b.columns, what)
    assertEquals(a.rows, b.rows, what)
    assertEquals(a.data.keySet, b.data.keySet, what)
    for (name, col) <- a.data do
      val ref = b.data(name)
      assertEquals(col.length, ref.length, s"$what: $name")
      for i <- col.indices do assert(sameValue(col(i), ref(i)), s"$what: $name($i): ${col(i)} vs ${ref(i)}")
    sameRows(what, a.rowIterator, b.rowIterator)

  private def metaOf(m: ParquetReader.Meta) =
    (m.columns, m.rows, m.keyValues, m.groups.map(g => (g.rows, g.time, g.chunks.map(c => (c.codec, c.offset, c.length, c.uncompressed, c.values, c.encoding)))))
  private def metaOf(m: ReferenceParquetReader.Meta) =
    (m.columns, m.rows, m.keyValues, m.groups.map(g => (g.rows, g.time, g.chunks.map(c => (c.codec, c.offset, c.length, c.uncompressed, c.values, c.encoding)))))

  private val dir = File(sys.props("java.io.tmpdir"), s"parquet-equivalence-${ProcessHandle.current().pid()}")

  override def afterAll(): Unit =
    Option(dir.listFiles).foreach(_.foreach(_.delete()))
    dir.delete()

  private var fileNo = 0
  private def saved(bytes: Array[Byte]): String =
    dir.mkdirs()
    fileNo += 1
    val f = File(dir, s"$fileNo.parquet")
    Using.resource(FileOutputStream(f))(_.write(bytes))
    f.getPath

  /** Every way the app reads a file, with both readers: the whole file,
    * the footer, and opened (as stored, and as `as` if given) row group by
    * row group, all rows, and the rows of time windows. The rows read.
    */
  private def same(what: String, bytes: Array[Byte], as: Seq[Column] = Nil, windows: Seq[(Long, Long)] = Nil): Int =
    sameTable(s"$what: read", ParquetReader.read(bytes), ReferenceParquetReader.read(bytes))
    val path = saved(bytes)
    assertEquals(metaOf(ParquetReader.meta(path)), metaOf(ReferenceParquetReader.meta(path)), what)
    var rows = 0
    for cols <- (Seq(Nil) ++ Option.when(as.nonEmpty)(as)) do
      Using.resource(ParquetReader.open(path, cols)) { a =>
        Using.resource(ReferenceParquetReader.open(path, cols)) { b =>
          assertEquals(a.columns, b.columns, what)
          assertEquals(a.dropped, b.dropped, what)
          for i <- 0 until a.groups do
            sameTable(s"$what: group $i as ${cols.size}", a.readGroup(i), b.readGroup(i))
            assertEquals(a.rawGroup(i).toSeq, b.rawGroup(i).toSeq, what)
          rows = sameRows(s"$what: rows", a.rowIterator, b.rowIterator)
          for (from, to) <- windows do sameRows(s"$what: [$from, $to)", a.rowsBetween(from, to), b.rowsBetween(from, to))
        }
      }
    rows

  /** Both read `bytes` alike, whole and opened, or neither can read it. */
  private def sameOrBothFail(what: String, bytes: Array[Byte]): Unit =
    val a = Try(ParquetReader.read(bytes))
    val b = Try(ReferenceParquetReader.read(bytes))
    (a.toOption, b.toOption) match
      case (Some(x), Some(y)) => sameTable(what, x, y)
      case (None, None)       => ()
      case _                  => fail(s"$what: now $a, before $b")
    val path = saved(bytes)
    val ar = Try(Using.resource(ParquetReader.open(path))(_.rowIterator.toVector))
    val br = Try(Using.resource(ReferenceParquetReader.open(path))(_.rowIterator.toVector))
    (ar.toOption, br.toOption) match
      case (Some(x), Some(y)) => sameRows(what, x.iterator, y.iterator)
      case (None, None)       => ()
      case _                  => fail(s"$what opened: now $ar, before $br")

  // --- synthetic rows -------------------------------------------------------

  private val edgeLongs = Array(Long.MinValue, Long.MaxValue, -1L, 0L, 1L, Int.MinValue.toLong, Int.MaxValue.toLong, 1L << 40)
  private val strings = Array("", "gps", "fused", "東京駅", "é🙂", "\u0000", "a,b", "x" * 300)

  /** A value of `c` in row `i`: about one in eight missing (null, or NaN in
    * a floating column), edge values among the first rows, an Int for some
    * of an Int64 column and a Float for some of a Float64 one, as the app
    * passes them.
    */
  private def value(c: Column, k: Int, i: Int, r: Random, time: Long): Any =
    if k == 0 && c.tpe == ColType.Int64 then if r.nextInt(50) == 0 then null else time
    else if r.nextInt(8) == 0 then
      c.tpe match
        case ColType.Float32 if r.nextBoolean() => Float.NaN
        case ColType.Float64 if r.nextBoolean() => Double.NaN
        case _                                  => null
    else c.tpe match
      case ColType.Int32 =>
        if i < edgeLongs.length then edgeLongs(i).toInt else if r.nextInt(4) == 0 then r.nextInt() else r.nextInt(100) - 50
      case ColType.Int64 =>
        if i < edgeLongs.length then edgeLongs((i + k) % edgeLongs.length)
        else if r.nextInt(5) == 0 then r.nextInt(1000) else if r.nextBoolean() then r.nextLong() else time * 1_000_000L + r.nextInt(1000)
      case ColType.Float32 =>
        if r.nextInt(20) == 0 then java.lang.Float.intBitsToFloat(r.nextInt()) match { case f if f.isNaN => -0.0f; case f => f }
        else r.nextFloat() * 100
      case ColType.Float64 =>
        if r.nextInt(10) == 0 then r.nextFloat()
        else if r.nextInt(20) == 0 then java.lang.Double.longBitsToDouble(r.nextLong()) match { case d if d.isNaN => -0.0; case d => d }
        else 35 + r.nextDouble()
      case ColType.Str => if r.nextInt(30) == 0 then r.nextString(r.nextInt(40)) else strings(r.nextInt(strings.length))

  private val t0 = 1_760_000_000_000L
  private def rows(cols: Seq[Column], n: Int, seed: Long): Vector[Seq[Any]] =
    val r = Random(seed)
    var time = t0
    Vector.tabulate(n) { i =>
      time += (if r.nextInt(10) == 0 then -r.nextInt(5000) else r.nextInt(3000))
      cols.zipWithIndex.map((c, k) => value(c, k, i, r, time))
    }

  private def written(cols: Seq[Column], rs: Seq[Seq[Any]], group: Int, meta: Seq[(String, String)] = Nil): Array[Byte] =
    val out = ByteArrayOutputStream()
    val w = ParquetWriter(out, cols, group, meta)
    rs.foreach(w.write)
    w.close()
    out.toByteArray

  private def writtenPlain(cols: Seq[Column], rs: Seq[Seq[Any]], group: Int): Array[Byte] =
    val out = ByteArrayOutputStream()
    val w = LegacyPlainParquetWriter(out, cols, group)
    rs.foreach(w.write)
    w.close()
    out.toByteArray

  private val counts = Seq(0, 1, 2, 127, 128, 129, 3000)
  private def groupsFor(n: Int): Seq[Int] = Seq(1, 7, 128, 1000).filter(g => g == 1000 || n / g <= 40)
  private def windows(seed: Long): Seq[(Long, Long)] =
    val r = Random(seed)
    Seq((Long.MinValue, Long.MaxValue), (t0, t0 + 1), (t0 + 100_000, t0 + 600_000)) ++
      Seq.fill(1) { val a = t0 + r.nextInt(3_000_000); (a, a + r.nextInt(2_000_000)) }

  // --- archive tables -------------------------------------------------------

  test("every table and layout of the archive, written by the current writer") {
    var files = 0
    for t <- Archive.All; (version, cols) <- Archive.layouts(t); n <- counts; group <- groupsFor(n) do
      val seed = (t.name.hashCode * 31L + version) * 1009 + n * 13 + group
      val meta = Seq(Archive.TableKey -> t.name, Archive.SchemaKey -> version.toString)
      val bytes = written(cols, rows(cols, n, seed), group, meta)
      assertEquals(same(s"${t.name} v$version n=$n group=$group", bytes, t.columns, windows(seed)), n)
      files += 1
    assert(files > 300, files.toString)
  }

  test("every table and layout of the archive, written by the PLAIN writer of the first archives") {
    for t <- Archive.All; (version, cols) <- Archive.layouts(t); n <- counts; group <- groupsFor(n) do
      val seed = (t.name.hashCode * 37L + version) * 1013 + n * 17 + group
      val bytes = writtenPlain(cols, rows(cols, n, seed), group)
      assertEquals(same(s"plain ${t.name} v$version n=$n group=$group", bytes, t.columns, windows(seed)), n)
  }

  test("a file of copied row groups (a month file merged from day files)") {
    val t = Archive.Fixes
    val days = (0 until 4).map(d => written(t.columns, rows(t.columns, 300 + d * 100, d), 128))
    val out = ByteArrayOutputStream()
    val w = ParquetWriter(out, t.columns, 128)
    rows(t.columns, 50, 99).foreach(w.write)
    for (d, k) <- days.zipWithIndex do
      Using.resource(ParquetReader.open(saved(d))) { r =>
        for i <- 0 until r.groups do w.copyGroup(r.rawGroup(i), r.meta.groups(i))
      }
      rows(t.columns, 3, k + 7).foreach(w.write)
    w.close()
    assertEquals(same("merged", out.toByteArray, t.columns, windows(5)), 50 + days.indices.map(d => 300 + d * 100 + 3).sum)
  }

  test("a column of only nulls, of only NaN, and of only one value") {
    for c <- ColType.values do
      val cols = Seq(Column("time", ColType.Int64), Column("v", c))
      for n <- Seq(1, 33, 129, 3000) do
        val missing: Any = c match
          case ColType.Float32 => Float.NaN
          case ColType.Float64 => Double.NaN
          case _               => null
        val one: Any = c match
          case ColType.Int32   => 7
          case ColType.Int64   => 7L
          case ColType.Float32 => 7f
          case ColType.Float64 => 7.0
          case ColType.Str     => "7"
        for v <- Seq(null, missing, one) do
          val rs = Vector.tabulate(n)(i => Seq(t0 + i, v))
          same(s"$c $v n=$n", written(cols, rs, 1000))
          same(s"plain $c $v n=$n", writtenPlain(cols, rs, 1000))
  }

  test("the web viewer's fixtures") {
    val fixtures = File("../server/web/test/fixtures").listFiles.toSeq.filter(_.getName.endsWith(".parquet"))
    assert(fixtures.size >= 3, fixtures.toString)
    for f <- fixtures do
      val bytes = java.nio.file.Files.readAllBytes(f.toPath)
      assert(same(f.getName, bytes) > 0, f.getName)
  }

  // --- files the writer never writes ----------------------------------------

  /** One data page of a column chunk: values (null for missing), how
    * they are encoded and how the page is stored.
    */
  private final case class Page(values: Seq[Any], encoding: Int = ParquetWriter.Plain, bitPacked: Boolean = false,
                                delta: (Int, Int) = (128, 4), sizeInHeader: Option[Int] = None, members: Int = 1,
                                countInHeader: Option[Int] = None, storedInHeader: Int => Int = identity)

  private def leBytes(v: Long, n: Int) = Array.tabulate[Byte](n)(k => (v >>> (8 * k)).toByte)

  /** Definition levels bit-packed (groups of 8, LSB first), as other
    * writers write them, in one run or (`split`) a run per 3 groups.
    */
  private def bitPacked(defined: Seq[Boolean]): Array[Byte] =
    val b = ByteArrayOutputStream()
    for part <- defined.grouped(24) do
      val groups = (part.size + 7) / 8
      Thrift.varint(b, (groups.toLong << 1) | 1)
      for g <- 0 until groups do
        var x = 0
        for bit <- 0 until 8 do if part.lift(g * 8 + bit).contains(true) then x |= 1 << bit
        b.write(x)
    b.toByteArray

  /** DELTA_BINARY_PACKED with `blockSize` values per block in `miniblocks`
    * miniblocks, bit by bit.
    */
  private def delta(values: Seq[Long], blockSize: Int, miniblocks: Int, typeBits: Int): Array[Byte] =
    val b = ByteArrayOutputStream()
    val perMini = blockSize / miniblocks
    Thrift.varint(b, blockSize.toLong)
    Thrift.varint(b, miniblocks.toLong)
    Thrift.varint(b, values.size.toLong)
    Thrift.varint(b, Thrift.zigzag(values.headOption.getOrElse(0L)))
    def wrap(d: Long) = if typeBits == 32 then d.toInt.toLong else d
    val deltas = values.indices.drop(1).map(i => wrap(values(i) - values(i - 1)))
    for block <- deltas.grouped(blockSize) do
      val min = block.min
      Thrift.varint(b, Thrift.zigzag(min))
      val minis = block.grouped(perMini).toVector.map(_.map(d => wrap(d - min) & (if typeBits == 32 then 0xffffffffL else -1L)))
      val widths = (0 until miniblocks).map(m => minis.lift(m).fold(0)(ms => ms.map(v => 64 - java.lang.Long.numberOfLeadingZeros(v)).max))
      widths.foreach(b.write)
      for (ms, m) <- minis.zipWithIndex do
        val w = widths(m)
        val bits = new Array[Byte](perMini * w / 8)
        for (v, j) <- ms.zipWithIndex; bit <- 0 until w if ((v >>> bit) & 1) == 1 do
          val pos = j * w + bit
          bits(pos >> 3) = (bits(pos >> 3) | (1 << (pos & 7))).toByte
        b.write(bits)
    b.toByteArray

  private def bodyOf(p: Page, tpe: ColType): Array[Byte] =
    val defined = p.values.map(_ != null)
    val levels = if p.bitPacked then bitPacked(defined) else ParquetWriter.rle(defined.toVector)
    val present = p.values.filter(_ != null)
    def bits(v: Any): Long = (tpe, v) match
      case (ColType.Float32, f: Float)  => java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL
      case (ColType.Float64, d: Double) => java.lang.Double.doubleToRawLongBits(d)
      case (_, i: Int)                  => i.toLong
      case (_, l: Long)                 => l
      case _                            => sys.error(s"$tpe $v")
    val values = p.encoding match
      case ParquetWriter.DeltaBinaryPacked =>
        delta(present.map(bits), p.delta._1, p.delta._2, if tpe == ColType.Int32 then 32 else 64)
      case ParquetWriter.ByteStreamSplit =>
        ParquetWriter.byteStreamSplit(present.map(bits).toVector, if tpe == ColType.Float32 then 4 else 8)
      case _ =>
        val b = ByteArrayOutputStream()
        for v <- present do tpe match
          case ColType.Int32 | ColType.Float32 => b.write(leBytes(bits(v), 4))
          case ColType.Int64 | ColType.Float64 => b.write(leBytes(bits(v), 8))
          case ColType.Str =>
            val s = v.toString.getBytes(UTF_8)
            b.write(leBytes(s.length, 4))
            b.write(s)
        b.toByteArray
    leBytes(levels.length, 4) ++ levels ++ values

  private def gzip(raw: Array[Byte], members: Int): Array[Byte] =
    val z = ByteArrayOutputStream()
    for part <- raw.grouped(math.max(1, (raw.length + members - 1) / members)).toSeq.padTo(members, Array.emptyByteArray) do
      val g = GZIPOutputStream(z)
      g.write(part)
      g.finish()
    z.toByteArray

  /** A file of `groups`, each a column chunk per column of (codec, pages). */
  private def assemble(cols: Seq[Column], groups: Seq[Seq[(Int, Seq[Page])]]): Array[Byte] =
    val out = ByteArrayOutputStream()
    out.write("PAR1".getBytes(UTF_8))
    val metas = for g <- groups yield
      val rows = g.head._2.map(_.values.size).sum
      val chunks = for ((codec, pages), c) <- g.zip(cols) yield
        assertEquals(pages.map(_.values.size).sum, rows)
        val offset = out.size.toLong
        var unzipped = 0L
        for p <- pages do
          val raw = bodyOf(p, c.tpe)
          val stored = if codec == 0 then raw else gzip(raw, p.members)
          val h = Thrift()
          h.struct {
            h.i32(1, 0)
            h.i32(2, p.sizeInHeader.getOrElse(raw.length))
            h.i32(3, p.storedInHeader(stored.length))
            h.structField(5) {
              h.i32(1, p.countInHeader.getOrElse(p.values.size))
              h.i32(2, p.encoding)
              h.i32(3, 3)
              h.i32(4, 3)
            }
          }
          out.write(h.bytes)
          unzipped += h.bytes.length + raw.length
          out.write(stored)
        (c, codec, offset, out.size - offset, unzipped, pages.head.encoding)
      (rows, chunks)
    val t = Thrift()
    t.struct {
      t.i32(1, 1)
      t.listField(2, Thrift.Struct, cols.size + 1) {
        t.struct { t.string(4, "schema"); t.i32(5, cols.size) }
        for c <- cols do t.struct { t.i32(1, c.tpe.physical); t.i32(3, 1); t.string(4, c.name) }
      }
      t.i64(3, metas.map(_._1.toLong).sum)
      t.listField(4, Thrift.Struct, metas.size) {
        for (rows, chunks) <- metas do
          t.struct {
            t.listField(1, Thrift.Struct, chunks.size) {
              for (c, codec, offset, length, unzipped, encoding) <- chunks do
                t.struct {
                  t.i64(2, offset)
                  t.structField(3) {
                    t.i32(1, c.tpe.physical)
                    t.listField(2, Thrift.I32, 2) { t.rawI32(encoding); t.rawI32(3) }
                    t.listField(3, Thrift.Binary, 1)(t.rawString(c.name))
                    t.i32(4, codec)
                    t.i64(5, rows)
                    t.i64(6, unzipped)
                    t.i64(7, length)
                    t.i64(9, offset)
                  }
                }
            }
            t.i64(2, chunks.map(_._5).sum)
            t.i64(3, rows)
          }
      }
    }
    val footer = t.bytes
    out.write(footer)
    out.write(leBytes(footer.length, 4))
    out.write("PAR1".getBytes(UTF_8))
    out.toByteArray

  private def column(tpe: ColType, n: Int, seed: Long): Seq[Any] =
    val r = Random(seed)
    Seq.tabulate(n)(i => value(Column("v", tpe), 1, i, r, t0 + i) match
      case f: Float if f.isNaN  => null
      case d: Double if d.isNaN => null
      case l: Long if tpe == ColType.Int32 => l.toInt
      case f: Float if tpe == ColType.Float64 => f.toDouble
      case v => v)

  private def encodings(tpe: ColType): Seq[Int] = tpe match
    case ColType.Int32 | ColType.Int64     => Seq(ParquetWriter.Plain, ParquetWriter.DeltaBinaryPacked)
    case ColType.Float32 | ColType.Float64 => Seq(ParquetWriter.Plain, ParquetWriter.ByteStreamSplit)
    case ColType.Str                       => Seq(ParquetWriter.Plain)

  test("several pages per chunk, empty pages, uncompressed chunks and bit-packed levels") {
    for tpe <- ColType.values; enc <- encodings(tpe); codec <- Seq(0, 2); bits <- Seq(false, true) do
      val cols = Seq(Column("v", tpe))
      val sizes = Seq(0, 1, 37, 0, 128, 129, 0, 500)
      var seed = 0L
      val pages = sizes.map { n => seed += 1; Page(column(tpe, n, seed * 7 + tpe.ordinal), enc, bitPacked = bits) }
      val groups = Seq(Seq(codec -> pages), Seq(codec -> pages.reverse.take(3)), Seq(codec -> Seq(Page(Nil, enc))))
      val what = s"$tpe enc=$enc codec=$codec bitPacked=$bits"
      assertEquals(same(what, assemble(cols, groups)), sizes.sum + sizes.takeRight(3).sum)
  }

  test("DELTA_BINARY_PACKED of other block layouts and of every bit width") {
    val r = Random(4242)
    for tpe <- Seq(ColType.Int32, ColType.Int64); (block, minis) <- Seq((128, 4), (256, 8), (32, 1), (64, 2), (512, 16)) do
      // Deltas of every width from 0 to 64 bits (32 for Int32), a miniblock each.
      val top = if tpe == ColType.Int32 then 32 else 64
      val values = (0 to top).flatMap { w =>
        Seq.fill(block / minis)(if w == 0 then 0L else if w == 64 then r.nextLong() else r.nextLong() & ((1L << w) - 1))
      }.scanLeft(0L)(_ + _).map(v => if tpe == ColType.Int32 then v.toInt else v)
      for n <- Seq(values.size, values.size - 1, block + 1, 1) do
        val vs = values.take(n).zipWithIndex.map((v, i) => if i % 11 == 5 then null else v)
        same(s"$tpe block=$block/$minis n=$n", assemble(Seq(Column("v", tpe)),
          Seq(Seq(2 -> Seq(Page(vs, ParquetWriter.DeltaBinaryPacked, delta = (block, minis)))))))
  }

  test("a page header's uncompressed size, wrong or missing, and GZIP of several members") {
    for tpe <- ColType.values; enc <- encodings(tpe); members <- Seq(1, 2, 5) do
      val vs = column(tpe, 3000, tpe.ordinal * 10 + members)
      for size <- Seq(None, Some(0), Some(1), Some(-1), Some(17), Some(Int.MaxValue), Some(1 << 30)) do
        same(s"$tpe enc=$enc members=$members size=$size", assemble(Seq(Column("v", tpe)),
          Seq(Seq(2 -> Seq(Page(vs, enc, sizeInHeader = size, members = members))))))
  }

  test("damaged or unsupported pages: both read them alike, or neither can") {
    val cols = Seq(Column("v", ColType.Int64))
    val vs = column(ColType.Int64, 300, 1)
    def one(p: Page, codec: Int = 2) = assemble(cols, Seq(Seq(codec -> Seq(p))))
    val good = one(Page(vs, ParquetWriter.DeltaBinaryPacked))
    sameOrBothFail("as written", good)
    // A dictionary-encoded data page (RLE_DICTIONARY, 8); SNAPPY (1).
    sameOrBothFail("RLE_DICTIONARY", one(Page(vs, 8)))
    sameOrBothFail("SNAPPY", one(Page(vs, ParquetWriter.DeltaBinaryPacked), codec = 1))
    // A dictionary page (type 2) in front of the data page.
    val h = Thrift()
    h.struct {
      h.i32(1, 2)
      h.i32(2, 4)
      h.i32(3, 4)
      h.structField(7) { h.i32(1, 1); h.i32(2, 0) }
    }
    sameOrBothFail("dictionary page", good.take(4) ++ h.bytes ++ Array[Byte](1, 0, 0, 0) ++ good.drop(4))
    // Page headers with another value count or stored size; the file cut
    // short anywhere.
    for codec <- Seq(0, 2); enc <- Seq(ParquetWriter.Plain, ParquetWriter.DeltaBinaryPacked) do
      for count <- Seq(0, 1, 299, 301, -1, Int.MaxValue) do
        sameOrBothFail(s"count $count codec $codec enc $enc", one(Page(vs, enc, countInHeader = Some(count)), codec))
      for d <- Seq(-1, 1, 100, 100_000, Int.MinValue) do
        sameOrBothFail(s"stored + $d codec $codec enc $enc", one(Page(vs, enc, storedInHeader = _ + d), codec))
    for n <- 0 until good.length by 3 do sameOrBothFail(s"cut at $n", good.take(n))
  }

  test("a DELTA_BINARY_PACKED miniblock wider than 64 bits is an error (garbage before)") {
    val b = ByteArrayOutputStream()
    // Blocks of 128 in 4 miniblocks, 2 values, first 0, min delta 0, widths 65 0 0 0.
    for v <- Seq(128L, 4L, 2L, 0L, 0L) do Thrift.varint(b, v)
    b.write(Array[Byte](65, 0, 0, 0))
    b.write(Array.fill[Byte](32 * 65 / 8)(-1))
    val levels = ParquetWriter.rle(Vector(true, true))
    val file = withBody(leBytes(levels.length, 4) ++ levels ++ b.toByteArray)
    assert(Try(ReferenceParquetReader.read(file)).isSuccess)
    intercept[IllegalArgumentException](ParquetReader.read(file))
  }

  test("a DELTA_BINARY_PACKED header of one value and absurd miniblocks reads as before") {
    // No block follows the first value, so the miniblock count is never used.
    for miniblocks <- Seq(4L, Int.MaxValue.toLong, 0L) do
      val b = ByteArrayOutputStream()
      for v <- Seq(128L, miniblocks, 1L, Thrift.zigzag(5)) do Thrift.varint(b, v)
      val levels = ParquetWriter.rle(Vector(true))
      sameOrBothFail(s"miniblocks $miniblocks", withBody(leBytes(levels.length, 4) ++ levels ++ b.toByteArray, rows = 1))
  }

  /** A file of one Int64 column of `rows` rows: one uncompressed
    * DELTA_BINARY_PACKED page, `body` as is.
    */
  private def withBody(body: Array[Byte], rows: Int = 2): Array[Byte] =
    val out = ByteArrayOutputStream()
    out.write("PAR1".getBytes(UTF_8))
    val h = Thrift()
    h.struct {
      h.i32(1, 0)
      h.i32(2, body.length)
      h.i32(3, body.length)
      h.structField(5) { h.i32(1, rows); h.i32(2, ParquetWriter.DeltaBinaryPacked); h.i32(3, 3); h.i32(4, 3) }
    }
    out.write(h.bytes)
    out.write(body)
    val size = (h.bytes.length + body.length).toLong
    val t = Thrift()
    t.struct {
      t.i32(1, 1)
      t.listField(2, Thrift.Struct, 2) {
        t.struct { t.string(4, "schema"); t.i32(5, 1) }
        t.struct { t.i32(1, ColType.Int64.physical); t.i32(3, 1); t.string(4, "v") }
      }
      t.i64(3, rows)
      t.listField(4, Thrift.Struct, 1) {
        t.struct {
          t.listField(1, Thrift.Struct, 1) {
            t.struct {
              t.i64(2, 4)
              t.structField(3) {
                t.i32(1, ColType.Int64.physical)
                t.listField(2, Thrift.I32, 2) { t.rawI32(ParquetWriter.DeltaBinaryPacked); t.rawI32(3) }
                t.listField(3, Thrift.Binary, 1)(t.rawString("v"))
                t.i32(4, 0)
                t.i64(5, rows)
                t.i64(6, size)
                t.i64(7, size)
                t.i64(9, 4)
              }
            }
          }
          t.i64(2, size)
          t.i64(3, rows)
        }
      }
    }
    out.write(t.bytes)
    out.write(leBytes(t.bytes.length, 4))
    out.write("PAR1".getBytes(UTF_8))
    out.toByteArray

