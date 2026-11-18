package ashidori.core

import java.io.{ByteArrayOutputStream, File, OutputStream}
import java.nio.file.Files
import ashidori.core.parquet.*

class StorageBudgetSuite extends munit.FunSuite:
  private val MiB = 1 << 20

  test("one write larger than 1 MiB is split into chunks, each checked before it is written") {
    val events = scala.collection.mutable.ArrayBuffer.empty[String]
    val guard = SpaceGuard(() => { events += "check"; Long.MaxValue / 2 }, () => 0L)
    val sink = new OutputStream:
      def write(b: Int): Unit = events += "write 1"
      override def write(b: Array[Byte], off: Int, len: Int): Unit = events += s"write $len"
    val g = GuardedOutputStream(sink, guard)
    g.write(new Array[Byte](3 * MiB + 100))
    assertEquals(events.toSeq, Seq("check", s"write $MiB", "check", s"write $MiB", "check", s"write $MiB", "check", "write 100"))
    assertEquals(g.checks, 4)
    // A chunk that does not fit is not written.
    var free = 2L * MiB
    val stop = GuardedOutputStream(ByteArrayOutputStream(), SpaceGuard(() => free, () => MiB + 10L))
    intercept[StorageBudget.NoRoom](stop.write(new Array[Byte](MiB)))
  }

  test("L from its file: written atomically and read back; missing, unreadable or corrupt gives the conservative L") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    assertEquals(ReserveFile.read(f), ReserveFile.Conservative)
    ReserveFile.write(f, 300_000_000L)
    assertEquals(ReserveFile.read(f), 300_000_000L)
    assert(!File(dir, "logging-reserve.tmp").exists)
    for bad <- Seq("", "garbage", "-5", "0", "12x") do
      Files.write(f.toPath, bad.getBytes)
      assertEquals(ReserveFile.read(f), ReserveFile.Conservative, bad)
    // Never the floor: the conservative value is 最高精度's day.
    assertEquals(ReserveFile.Conservative, 210L * 1000 * 1000 * 24)
    assert(ReserveFile.Conservative > StorageBudget.MinReserve)
  }

  test("two writers racing each other on one free space: below L by at most 2 MiB") {
    val L = 100L * MiB
    val free = java.util.concurrent.atomic.AtomicLong(L + 9L * MiB + 123)
    val lowest = java.util.concurrent.atomic.AtomicLong(Long.MaxValue)
    // Both check, then both write (the worst order), every round.
    val barrier = java.util.concurrent.CyclicBarrier(2)
    def writer(): Runnable = () =>
      val sink = new OutputStream:
        def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
        override def write(b: Array[Byte], off: Int, len: Int): Unit =
          try barrier.await(2, java.util.concurrent.TimeUnit.SECONDS) catch case _: Exception => ()
          val now = free.addAndGet(-len)
          lowest.accumulateAndGet(now, math.min)
      val g = GuardedOutputStream(sink, SpaceGuard(() => free.get, () => L))
      try while true do g.write(new Array[Byte](MiB))
      catch case _: StorageBudget.NoRoom => barrier.reset()
    val ts = Seq(Thread(writer()), Thread(writer()))
    ts.foreach(_.start())
    ts.foreach(_.join(10_000))
    assert(lowest.get < L + MiB, s"both wrote down to the reserve: ${lowest.get - L}")
    assert(lowest.get >= L - 2L * MiB, s"${(L - lowest.get) / MiB.toDouble} MiB below L")
  }

  test("old logs are imported in batches of at most 1 MiB of text and 5,000 lines") {
    val lines = (0 until 30_000).map(i => s"Raw,$i," + "x" * (if i % 1000 == 0 then 400 else 80))
    val batches = LegacyBatches(lines.iterator).toSeq
    assertEquals(batches.flatten, lines)
    assert(batches.forall(b => b.size <= 5000 && b.map(LegacyBatches.utf8).sum <= MiB))
    val big = Seq("a" * 10, "b" * (2 * MiB), "c" * 10)
    assertEquals(LegacyBatches(big.iterator).toSeq.map(_.size), Seq(1, 1, 1))
  }

  test("a copied row group larger than 1 MiB is written in checked chunks of at most 1 MiB") {
    val schema = Seq(Column("time", ColType.Int64), Column("v", ColType.Float64), Column("s", ColType.Str))
    val f = Files.createTempFile("group", ".parquet").toFile
    val out = java.io.FileOutputStream(f)
    val w = ParquetWriter(out, schema, rowGroupRows = 400_000)
    val rnd = scala.util.Random(1)
    (0 until 200_000).foreach(i => w.write(Seq(1_700_000_000_000L + i * 7919L % 100_000, rnd.nextDouble(), rnd.alphanumeric.take(12).mkString)))
    w.close()
    out.close()
    val r = ParquetReader.open(f.getPath)
    try
      val bytes = r.rawGroup(0)
      assert(bytes.length > 2 * MiB, bytes.length.toString)
      val writes = scala.collection.mutable.ArrayBuffer.empty[Int]
      val sink = new OutputStream:
        def write(b: Int): Unit = writes += 1
        override def write(b: Array[Byte], off: Int, len: Int): Unit = writes += len
      val g = GuardedOutputStream(sink, SpaceGuard(() => Long.MaxValue / 2, () => 0L))
      val w2 = ParquetWriter(g, schema)
      val before = g.checks
      w2.copyGroup(bytes, r.meta.groups(0))
      assert(writes.forall(_ <= MiB), writes.max.toString)
      assert(g.checks - before >= (bytes.length + MiB - 1) / MiB)
      // With no room, the copy stops before its first chunk is written.
      val none = GuardedOutputStream(ByteArrayOutputStream(), SpaceGuard(() => 0L, () => 0L))
      val w3 = ParquetWriter(new OutputStream { def write(b: Int): Unit = () }, schema)
      intercept[StorageBudget.NoRoom](ParquetWriter(none, schema).copyGroup(bytes, r.meta.groups(0)))
    finally r.close()
  }

  test("main-process reservations: a restart from the start is admitted again; release always works") {
    var free = 10_000L
    val b = StorageBudget(() => free, () => 8_000L)
    val r = b.reserveFile(1_000, 600).get
    assertEquals(b.outstanding, 400L)
    // Its guard counts only the others' reservations: its own chunks are checked as they come.
    assert(r.guard.allows(2_000))
    val other = b.reserve(1_500).get
    assert(!r.guard.allows(600))
    r.release()
    r.release()
    other.release()
    assertEquals(b.outstanding, 0L)
  }
