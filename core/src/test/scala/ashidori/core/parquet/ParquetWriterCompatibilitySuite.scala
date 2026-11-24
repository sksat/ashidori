package ashidori.core.parquet

import java.io.{ByteArrayOutputStream, File, FileOutputStream}
import scala.util.Random
import scala.util.Using

/** The writer writes the bytes of the writer of 0.24.0
  * ([[ReferenceParquetWriter]], verbatim apart from its names) for every
  * input that writer encodes correctly.
  *
  * It cannot encode a DELTA_BINARY_PACKED miniblock of 64 bits (Int64
  * values whose deltas within a block of 128 span 2^63 or more, e.g.
  * Long.MinValue next to Long.MaxValue): it packs with a BigInt, and a
  * delta with the top bit set is a negative BigInt whose sign bits fill the
  * rest of the miniblock, so the values after it in the miniblock read
  * back wrong. There the current writer writes the correct encoding
  * instead: every value reads back as written, with the current reader and
  * with that of 0.24.0.
  */
class ParquetWriterCompatibilitySuite extends munit.FunSuite:
  private val schema = Seq(
    Column("time", ColType.Int64), Column("i32", ColType.Int32),
    Column("i64", ColType.Int64), Column("f32", ColType.Float32),
    Column("f64", ColType.Float64), Column("text", ColType.Str))
  private val wideEdge = Array(Long.MinValue, Long.MaxValue, -1L, 0L, 1L, 1L << 40)
  private val tameEdge = Array(Int.MinValue.toLong, Int.MaxValue.toLong, -1L, 0L, 1L, 1L << 40)

  /** Rows of every type with nulls, NaN and edge values; `wide`: Int64
    * values over the whole range (else within ±2^60, so that no delta
    * needs 64 bits).
    */
  private def rows(n: Int, seed: Int, wide: Boolean = true): Vector[Seq[Any]] =
    val r = Random(seed)
    val edge = if wide then wideEdge else tameEdge
    def long() = if wide then r.nextLong() else r.nextLong() >> 4
    Vector.tabulate(n) { i =>
      def missing = r.nextInt(7) == 0
      val time: Any = if missing then null else if i < edge.length then edge(i) else if i % 3 == 0 then r.nextInt() else long()
      val i32: Any = if missing then null else if i < edge.length then edge(i).toInt else r.nextInt()
      val i64: Any = if missing then null else if i < edge.length then edge(edge.length - 1 - i) else if i % 2 == 0 then r.nextInt() else long()
      val f32: Any = if missing then null else if i % 11 == 0 then Float.NaN else java.lang.Float.intBitsToFloat(r.nextInt())
      val f64: Any = if missing then null else if i % 13 == 0 then Double.NaN else if i % 2 == 0 then r.nextFloat() else java.lang.Double.longBitsToDouble(r.nextLong())
      val str: Any = if missing then null else Array("", "東京", "é🙂", "ascii", "\u0000")(i % 5)
      Seq(time, i32, i64, f32, f64, str)
    }

  private def encode(rs: Seq[Seq[Any]], group: Int, reference: Boolean, cols: Seq[Column] = schema): Array[Byte] =
    val out = ByteArrayOutputStream()
    if reference then
      val w = ReferenceParquetWriter(out, cols, group, Seq("origin" -> "東京"))
      rs.foreach(w.write)
      w.close()
    else
      val w = ParquetWriter(out, cols, group, Seq("origin" -> "東京"))
      rs.foreach(w.write)
      w.close()
    out.toByteArray

  /** Whether DELTA_BINARY_PACKED of `vs` has a miniblock of 64 bits: a
    * delta less its block's smallest that has the top bit set.
    */
  private def needs64(vs: Seq[Long]): Boolean =
    val deltas = vs.indices.drop(1).map(i => vs(i) - vs(i - 1))
    deltas.grouped(128).exists { b =>
      val min = b.min
      b.exists(d => d - min < 0)
    }

  /** Whether some Int64 column chunk of rows `rs` in groups of `group` needs 64 bits. */
  private def needs64(rs: Seq[Seq[Any]], group: Int, cols: Seq[Column]): Boolean =
    rs.grouped(math.max(group, 1)).exists(g =>
      cols.indices.filter(cols(_).tpe == ColType.Int64).exists(k =>
        needs64(g.map(_(k)).collect { case l: Long => l; case i: Int => i.toLong })))

  /** A value as a reader returns it: Int64 a Long, Float64 a Double, NaN null. */
  private def stored(c: Column, v: Any): Any = (c.tpe, v) match
    case (_, null)                       => null
    case (_, d: Double) if d.isNaN       => null
    case (_, f: Float) if f.isNaN        => null
    case (ColType.Int64, i: Int)         => i.toLong
    case (ColType.Float64, f: Float)     => f.toDouble
    case _                               => v

  private def same(a: Any, b: Any): Boolean = (a, b) match
    case (x: Double, y: Double) => java.lang.Double.doubleToRawLongBits(x) == java.lang.Double.doubleToRawLongBits(y)
    case (x: Float, y: Float)   => java.lang.Float.floatToRawIntBits(x) == java.lang.Float.floatToRawIntBits(y)
    case _                      => a == null && b == null || a != null && b != null && a.getClass == b.getClass && a == b

  /** Whether both readers read `bytes` back as `rs`. */
  private def readsBack(bytes: Array[Byte], rs: Seq[Seq[Any]], cols: Seq[Column]): Boolean =
    val expected = rs.map(r => cols.zip(r).map(stored))
    def ok(rows: Iterator[IndexedSeq[Any]]) =
      val got = rows.toVector
      got.size == expected.size && got.zip(expected).forall((g, e) => g.size == e.size && g.zip(e).forall(same))
    ok(ParquetReader.read(bytes).rowIterator) && ok(ReferenceParquetReader.read(bytes).rowIterator)

  /** The rule above for one input: the current writer's file reads back
    * as written, and is the bytes of 0.24.0's unless a miniblock needs 64
    * bits. Whether 0.24.0's file of it reads back wrong.
    */
  private def check(what: String, rs: Seq[Seq[Any]], group: Int, cols: Seq[Column] = schema): Boolean =
    val now = encode(rs, group, false, cols)
    val before = encode(rs, group, true, cols)
    assert(readsBack(now, rs, cols), s"$what: does not read back")
    if needs64(rs, group, cols) then !readsBack(before, rs, cols)
    else
      assertEquals(now.toSeq, before.toSeq, what)
      false

  test("complete stored files: the same bytes, except where 0.24.0 encoded wrong") {
    var wrong = 0
    for n <- Seq(0, 1, 127, 128, 129, 1001, 2100); group <- Seq(1, 128, 500); wide <- Seq(true, false) do
      if check(s"rows=$n group=$group wide=$wide", rows(n, n + group, wide), group) then wrong += 1
    // Most wide inputs of more than a few rows read back wrong from 0.24.0's file.
    assert(wrong >= 10, wrong.toString)
  }

  test("Int64 columns of every delta width up to 64 bits") {
    val r = Random(5150)
    val cols = Seq(Column("v", ColType.Int64))
    for bits <- 0 to 64; n <- Seq(2, 33, 129, 300) do
      val vs = Vector.tabulate(n)(_ => if bits == 64 then r.nextLong() else if bits == 0 then 7L else r.nextLong() >>> (64 - bits))
      check(s"bits=$bits n=$n", vs.map(Seq(_)), 1000, cols)
  }

  test("encoders match the saved implementation on random and edge values") {
    val r = Random(92810)
    for n <- Seq(0, 1, 2, 31, 32, 33, 127, 128, 129, 1001); bits <- Seq(32, 64); wide <- Seq(true, false) do
      val edge = if wide then wideEdge else tameEdge
      val xs = Vector.tabulate(n)(i => if i < edge.length then edge(i) else if wide then r.nextLong() else r.nextLong() >> 4)
      // Int32 columns hold Ints: their deltas wrap to 32 bits and never need 64.
      if bits == 32 || !needs64(xs) then
        assertEquals(ParquetWriter.deltaBinaryPacked(xs, bits).toSeq,
          ReferenceParquetWriter.deltaBinaryPacked(xs, bits).toSeq, s"delta n=$n bits=$bits")
      for width <- Seq(4, 8) do
        assertEquals(ParquetWriter.byteStreamSplit(xs, width).toSeq,
          ReferenceParquetWriter.byteStreamSplit(xs, width).toSeq)
      val levels = Vector.fill(n)(r.nextBoolean())
      assertEquals(ParquetWriter.rle(levels).toSeq, ReferenceParquetWriter.rle(levels).toSeq)
  }

  test("a copied group has identical bytes and footer") {
    // Groups copied from a file of 0.24.0 as stored, even where it encoded wrong.
    for (source, prefix) <- Seq(encode(rows(265, 31), 128, true) -> rows(7, 9, wide = false),
                                encode(rows(265, 31, wide = false), 128, true) -> rows(7, 9, wide = false)) do
      val file = File.createTempFile("parquet-reference", ".parquet")
      try
        Using.resource(FileOutputStream(file))(_.write(source))
        Using.resource(ParquetReader.open(file.getPath)) { reader =>
          val actual = ByteArrayOutputStream()
          val expected = ByteArrayOutputStream()
          val a = ParquetWriter(actual, schema, 128, Seq("origin" -> "東京"))
          val b = ReferenceParquetWriter(expected, schema, 128, Seq("origin" -> "東京"))
          prefix.foreach { row => a.write(row); b.write(row) }
          for i <- reader.meta.groups.indices do
            val group = reader.meta.groups(i)
            val raw = reader.rawGroup(i)
            a.copyGroup(raw, group)
            b.copyGroup(raw, group)
          a.close()
          b.close()
          assertEquals(actual.toByteArray.toSeq, expected.toByteArray.toSeq)
        }
      finally file.delete()
  }

  test("invalid value rejects the entire row") {
    val a = ByteArrayOutputStream()
    val b = ByteArrayOutputStream()
    val newer = ParquetWriter(a, schema, 2)
    val older = ReferenceParquetWriter(b, schema, 2)
    val bad = Seq[Any](1L, 3, 4L, 5f, 6.0, 8)
    intercept[IllegalArgumentException](newer.write(bad))
    intercept[IllegalArgumentException](older.write(bad))
    val good = Seq[Any](1L, 3, 4L, 5f, 6.0, "ok")
    newer.write(good)
    older.write(good)
    newer.close()
    older.close()
    assertEquals(a.toByteArray.toSeq, b.toByteArray.toSeq)
  }

  private def both(cols: Seq[Column], rs: Seq[Seq[Any]], group: Int, meta: Seq[(String, String)]): (Array[Byte], Array[Byte]) =
    val a = ByteArrayOutputStream()
    val b = ByteArrayOutputStream()
    val newer = ParquetWriter(a, cols, group, meta)
    val older = ReferenceParquetWriter(b, cols, group, meta)
    rs.foreach { r => newer.write(r); older.write(r) }
    newer.close()
    older.close()
    (a.toByteArray, b.toByteArray)

  /** A value of `c` as the app passes it: an Int for some of an Int64
    * column, a Float for some of a Float64 one, null or NaN for missing.
    */
  private def value(c: Column, r: Random, time: Long, first: Boolean): Any =
    if first then time
    else if r.nextInt(9) == 0 then (if c.tpe == ColType.Float64 && r.nextBoolean() then Double.NaN else null)
    else c.tpe match
      case ColType.Int32   => if r.nextInt(5) == 0 then r.nextInt() else r.nextInt(64)
      case ColType.Int64   => if r.nextInt(4) == 0 then r.nextInt(1000) else time * 1000 + r.nextInt(5000)
      case ColType.Float32 => if r.nextInt(30) == 0 then Float.NaN else r.nextFloat() * 90
      case ColType.Float64 => if r.nextInt(4) == 0 then r.nextFloat() else 35 + r.nextDouble()
      case ColType.Str     => Array("gps", "fused", "", "東京", "x" * 200)(r.nextInt(5))

  test("every table and layout of the archive, in the app's row groups and small ones") {
    for t <- ashidori.core.Archive.All; (version, cols) <- ashidori.core.Archive.layouts(t); n <- Seq(0, 1, 129, 5000) do
      val r = Random(t.name.hashCode + version * 7 + n)
      var time = 1_760_000_000_000L
      val rs = Vector.tabulate(n) { _ =>
        time += r.nextInt(3000) - 200
        cols.zipWithIndex.map((c, k) => value(c, r, time, k == 0))
      }
      val meta = Seq(ashidori.core.Archive.TableKey -> t.name, ashidori.core.Archive.SchemaKey -> version.toString)
      for group <- Seq((400_000 / cols.size).max(2048).min(50_000), 333) do
        val (now, before) = both(cols, rs, group, meta)
        assertEquals(now.toSeq, before.toSeq, s"${t.name} v$version n=$n group=$group")
  }

  test("a Max-mode detail session, parsed as compaction parses it") {
    val byTable = ashidori.core.bench.CompactionBench.lines(120)
      .map((time, line) => ashidori.core.Archive.parse(line, time).toOption.get).groupBy(_._1)
    assertEquals(byTable.keySet, Set(ashidori.core.Archive.GnssRaw, ashidori.core.Archive.GnssSat, ashidori.core.Archive.Nmea,
      ashidori.core.Archive.Imu, ashidori.core.Archive.GnssFix))
    for (t, rows) <- byTable; group <- Seq((400_000 / t.columns.size).max(2048).min(50_000), 1000) do
      val (now, before) = both(t.columns, rows.map(_._2), group, Nil)
      assertEquals(now.toSeq, before.toSeq, s"${t.name} group=$group")
  }
