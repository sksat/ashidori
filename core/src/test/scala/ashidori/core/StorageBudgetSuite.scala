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

  test("the floor from its file: written atomically with its time and read back; missing, corrupt or stale gives the conservative L") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    val now = 1_800_000_000_000L
    assertEquals(ReserveFile.read(f, now), ReserveFile.Conservative)
    ReserveFile.write(f, 300_000_000L, now)
    assertEquals(ReserveFile.read(f, now), 300_000_000L)
    assertEquals(ReserveFile.read(f, now + ReserveFile.StaleMs), 300_000_000L)
    assert(!File(dir, "logging-reserve.tmp").exists)
    // Not refreshed by the heartbeat for longer than StaleMs (the main
    // process gone or its writes failing): the conservative L.
    assertEquals(ReserveFile.read(f, now + ReserveFile.StaleMs + 1), ReserveFile.Conservative)
    assert(ReserveFile.HeartbeatMs * 2 < ReserveFile.StaleMs)
    // From the future (a clock set back): not trusted either.
    assertEquals(ReserveFile.read(f, now - 120_000), ReserveFile.Conservative)
    for bad <- Seq("", "garbage", "-5 1", "0 1", "12x 1", "300000000", s"300000000 $now 7") do
      Files.write(f.toPath, bad.getBytes)
      assertEquals(ReserveFile.read(f, now), ReserveFile.Conservative, bad)
    // Never the floor: the conservative value is 最高精度's day.
    assertEquals(ReserveFile.Conservative, 210L * 1000 * 1000 * 24)
    assert(ReserveFile.Conservative > StorageBudget.MinReserve)
  }

  test("a floor that cannot be written throws (no step's failure is swallowed)") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    ReserveFile.write(f, 300_000_000L, 1L)
    dir.setWritable(false)
    try
      if !dir.canWrite then
        intercept[java.io.IOException](ReserveFile.write(f, 400_000_000L, 2L))
        assertEquals(String(Files.readAllBytes(f.toPath)).trim, "300000000 1")
    finally dir.setWritable(true)
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

  test("old logs are imported in batches of at most the asked bytes of text and 5,000 lines") {
    val lines = (0 until 30_000).map(i => s"Raw,$i," + "x" * (if i % 1000 == 0 then 400 else 80))
    val batches = LegacyBatches(lines.iterator).toSeq
    assertEquals(batches.flatten, lines)
    assert(batches.forall(b => b.size <= 5000 && b.map(LegacyBatches.utf8).sum <= MiB))
    val big = Seq("a" * 10, "b" * (2 * MiB), "c" * 10)
    assertEquals(LegacyBatches(big.iterator).toSeq.map(_.size), Seq(1, 1, 1))
    // The budget is asked before each batch (it follows the measured growth).
    var asked = 0
    val limits = Iterator(64L * 1024, 16L * 1024, 256L * 1024)
    var limit = 0L
    val sized = LegacyBatches(lines.iterator, () => { asked += 1; limit = if limits.hasNext then limits.next() else limit; limit })
      .take(3).toSeq
    assertEquals(asked, 3)
    assertEquals(sized.map(_.map(LegacyBatches.utf8).sum <= 256L * 1024), Seq(true, true, true))
    assert(sized(1).map(LegacyBatches.utf8).sum <= 16L * 1024)
    // A line beyond any record (UTF-8 bytes, not characters).
    assert(LegacyBatches.utf8("あ" * 30_000) > LegacyBatches.MaxLineBytes)
    assert(("あ" * 30_000).length < LegacyBatches.MaxLineBytes)
  }

  test("bulk SQLite batches follow the measured growth of the files: about 1 MiB of pages each") {
    val s = BatchSizer()
    // Cautious first: 64 rows.
    assertEquals(s.rows, 64)
    // 64 rows grew the files by 108 KiB (1.7 KiB a row): 2 KiB a row assumed at least.
    s.observe(64, 108L * 1024)
    assertEquals(s.rows, 512)
    // Wide rows (8 KiB each): fewer.
    s.observe(512, 512L * 8192)
    assertEquals(s.rows, 128)
    // The WAL was reused (no growth): the assumed rate, never more rows than that.
    s.observe(128, 0)
    assertEquals(s.rows, 512)
    s.observe(512, 0)
    assertEquals(s.rows, 512)
    // A simulated table: every batch changes rows x 1.7 KiB (the measured
    // deletion of raw lines) and grows the files by that; none over 1 MiB
    // after the first, which is smaller.
    val t = BatchSizer()
    var left = 200_000
    while left > 0 do
      val n = math.min(left, t.rows)
      val grown = n * 1741L
      assert(grown <= MiB, s"$n rows grew $grown")
      t.observe(n, grown)
      left -= n
    // Rows of 20 MiB in all (a few huge ones): one at a time.
    val u = BatchSizer()
    u.observe(64, 64L * 20 * MiB)
    assertEquals(u.rows, 1)
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

  test("each admitted reservation is published before it is given out, each release after; a failed publication refuses it") {
    val events = scala.collection.mutable.ArrayBuffer.empty[String]
    var fail = false
    lazy val b: StorageBudget = StorageBudget(() => 100_000L, () => 10_000L, () => {
      events += s"publish ${b.outstanding}"
      if fail then throw java.io.IOException("not written")
    })
    val r = b.reserve(5_000).get
    assertEquals(events.toSeq, Seq("publish 5000"))
    r.release()
    r.release()
    assertEquals(events.toSeq, Seq("publish 5000", "publish 0"))
    fail = true
    intercept[java.io.IOException](b.reserve(5_000))
    assertEquals(b.outstanding, 0L)
  }
