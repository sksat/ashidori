package ashidori.core

import munit.FunSuite
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class CompactionEquivalenceSuite extends FunSuite:
  private val values = Vector("", "1", "-0", "+5", "1.0", "1e3", "NaN", "Infinity",
    "0x10", "0x1p3", " 1", "1 ", "3000000000", "9007199254740993", "1.5",
    "true", "false", "abc", "漢字", "1d", "1f", "-Infinity")
  private val kinds = Vector("Raw", "Status", "Nav", "NMEA", "Accel", "Gyro", "Mag", "Fix", "unknown")

  private def compare(line: String, time: Long): Unit =
    val old = CompactionOld.parseOld(line, time)
    val now = Archive.parse(line, time)
    assertEquals(now.isRight, old.isRight, line)
    (old, now) match
      case (Left(a), Left(b)) => assertEquals(b, a, line)
      case (Right((ta, ra)), Right((tb, rb))) =>
        assertEquals(tb, ta, line)
        assertEquals(rb.size, ra.size, line)
        for i <- ra.indices do
          // NaN by its bits (NaN == NaN is false).
          val same = (rb(i), ra(i)) match
            case (x: Double, y: Double) => java.lang.Double.doubleToRawLongBits(x) == java.lang.Double.doubleToRawLongBits(y)
            case (x: Float, y: Float)   => java.lang.Float.floatToRawIntBits(x) == java.lang.Float.floatToRawIntBits(y)
            case (x, y)                 => x == y
          assert(same, s"$line: column $i: ${rb(i)} vs ${ra(i)}")
          assertEquals(Option(rb(i)).map(_.getClass), Option(ra(i)).map(_.getClass), s"$line: class $i")
      case _ => fail(line)

  test("parse preserves rows, boxed types, and errors on adversarial and randomized lines") {
    val rnd = Random(71)
    for kind <- kinds do
      val width = Archive.tableFor(kind).map(_.columns.size).getOrElse(8)
      for count <- Vector(0, 1, 2, 5, 6, width - 1, width, width + 1, width + 8).filter(_ >= 0) do
        for v <- values do
          val fields = Array.fill(count)(v)
          compare((kind +: fields).mkString(","), 123456789L)
        for _ <- 0 until 80 do
          val fields = Array.fill(count)(values(rnd.nextInt(values.size)))
          compare((kind +: fields).mkString(","), rnd.nextLong())
    for line <- Vector("NMEA,a,b,c,not-a-time", "NMEA,,", "NMEA,a,b,1",
      "Nav", "Nav,1,2,3,4", "Nav,1,2,3,4,5", "Nav,1,2,3,4,5,tail,more",
      "Raw,", "Status,", "Fix,") do compare(line, 17L)
  }

  test("sampler keeps identical samples after every prefix") {
    val rnd = Random(91)
    for max <- Vector(1, 2, 4, 8); run <- 0 until 20 do
      val old = CompactionOld.ChunkSamplerOld(max)
      val now = ChunkSampler(max)
      for i <- 0 until 200 do
        val row: ArchiveMerge.Row = Vector[Any](rnd.nextInt(9).toLong, i, if i % 7 == 0 then null else rnd.nextDouble())
        old.add(row); now.add(row)
        assertEquals(now.count, old.count)
        assertEquals(now.range, old.range)
        assertEquals(now.samples, old.samples)
  }

  test("distinct and reorder preserve order, repeats, and late rows") {
    val rnd = Random(101)
    for run <- 0 until 80 do
      val rows = Vector.tabulate(300)(i => Vector[Any](rnd.nextInt(30).toLong, i % 9))
      val oldDistinct = ArrayBuffer.empty[ArchiveMerge.Row]
      val newDistinct = ArrayBuffer.empty[ArchiveMerge.Row]
      val od = CompactionOld.DistinctOld(oldDistinct += _)
      val nd = ArchiveMerge.Distinct(newDistinct += _)
      rows.sortBy(ArchiveMerge.key).foreach { r => od(r); nd(r) }
      assertEquals(newDistinct.toVector, oldDistinct.toVector)
      assertEquals(nd.written, od.written)
      assertEquals(nd.dropped, od.dropped)
      val oldOut = ArrayBuffer.empty[ArchiveMerge.Row]
      val newOut = ArrayBuffer.empty[ArchiveMerge.Row]
      val oldLate = ArrayBuffer.empty[ArchiveMerge.Row]
      val newLate = ArrayBuffer.empty[ArchiveMerge.Row]
      val or = CompactionOld.ReorderOld[ArchiveMerge.Row](10L, ArchiveMerge.key, oldOut += _, oldLate += _)
      val nr = ArchiveMerge.Reorder[ArchiveMerge.Row](10L, ArchiveMerge.key, newOut += _, newLate += _)
      rows.foreach { r =>
        val watermark = rnd.nextInt(35).toLong
        or.push(r, watermark); nr.push(r, watermark)
      }
      or.flush(); nr.flush()
      assertEquals(newOut.toVector, oldOut.toVector)
      assertEquals(newLate.toVector, oldLate.toVector)
      assertEquals(nr.late, or.late)
  }
