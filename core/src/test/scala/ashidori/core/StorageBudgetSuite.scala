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

  test("the floor file: L, the reservations and the time, written atomically and read back; missing or corrupt is None") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    assertEquals(ReserveFile.read(f), None)
    ReserveFile.write(f, Published(300_000_000L, 40_000_000L, 1_800_000_000_000L))
    assertEquals(ReserveFile.read(f), Some(Published(300_000_000L, 40_000_000L, 1_800_000_000_000L)))
    assert(!File(dir, "logging-reserve.tmp").exists)
    // The format of earlier builds (one or two numbers) is not trusted either.
    for bad <- Seq("", "garbage", "300000000", "300000000 1800000000000", "-5 0 1", "0 0 1", "1 -1 1", "12x 0 1", "1 2 3 4") do
      Files.write(f.toPath, bad.getBytes)
      assertEquals(ReserveFile.read(f), None, bad)
  }

  test("the other process's floor: the published one (reservations only while the main process lives), at least its own L") {
    val now = 1_800_000_000_000L
    val C = ReserveFile.Conservative
    assertEquals(C, 210L * 1000 * 1000 * 24)
    assert(C > StorageBudget.MinReserve)
    // Missing, unreadable or corrupt: the conservative L (never the 256 MB floor).
    assertEquals(ReserveFile.floor(None, now, true, 256_000_000L), C)
    assertEquals(ReserveFile.floor(None, now, false, 9_000_000_000L), 9_000_000_000L)
    val p = Published(300_000_000L, 1_500_000_000L, now)
    // Fresh or stale alike: the last floor while the main process lives;
    // only its L once it is gone (its reservations can write nothing more).
    for at <- Seq(now, now - 3_600_000L, now - 7L * 86_400_000) do
      assertEquals(ReserveFile.floor(Some(p.copy(at = at)), now, true, 100_000_000L), 1_800_000_000L)
      assertEquals(ReserveFile.floor(Some(p.copy(at = at)), now, false, 100_000_000L), 300_000_000L)
      // Its own L, measured from the record, when larger.
      assertEquals(ReserveFile.floor(Some(p.copy(at = at)), now, false, 700_000_000L), 700_000_000L)
    // A last floor above the conservative L is kept as it is (not lowered to it).
    val big = Published(C, 2_000_000_000L, now - 3_600_000L)
    assertEquals(ReserveFile.floor(Some(big), now, true, 0L), C + 2_000_000_000L)
    // From the future (a clock set back): as unreadable.
    assertEquals(ReserveFile.floor(Some(p.copy(at = now + 120_000)), now, true, 0L), C)
  }

  test("the main process's liveness: a lock held while it lives") {
    val f = File(Files.createTempDirectory("alive").toFile, "main-alive.lock")
    assert(!MainAlive.alive(f), "nobody holds it")
    val held = MainAlive.hold(f)
    assert(MainAlive.alive(f), "held")
    held.close()
    assert(!MainAlive.alive(f), "released when its holder ends")
  }

  test("a floor that cannot be published refuses the reservation, and the file is removed") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    var mode = LoggingMode.Balanced
    lazy val pub: FloorPublisher = FloorPublisher(f, () => mode, m => StorageBudget.loggingReserve(m, None), () => budget.outstanding)
    lazy val budget: StorageBudget = StorageBudget(() => 100_000_000_000L, () => StorageBudget.loggingReserve(mode, None), () => pub.publish())
    val r = budget.reserve(1_000_000_000L).get
    assertEquals(ReserveFile.read(f).map(p => (p.l, p.held)), Some((StorageBudget.loggingReserve(mode, None), 1_000_000_000L)))
    r.release()
    assertEquals(ReserveFile.read(f).map(_.held), Some(0L))
    dir.setWritable(false)
    try
      if !dir.canWrite then
        // The large write (a backup's database copy) never starts.
        assertEquals(budget.reserve(1_000_000_000L), None)
        assertEquals(budget.outstanding, 0L)
        // A mode change still takes effect; the floor file is gone (the other
        // process then uses the conservative L), and the failure is thrown.
        dir.setWritable(true)
        Files.createDirectory(File(dir, "logging-reserve.tmp").toPath)
        intercept[java.io.IOException](pub.changeMode(LoggingMode.Max) { mode = LoggingMode.Max })
        assertEquals(mode, LoggingMode.Max)
        assert(!f.exists)
        assertEquals(ReserveFile.floor(ReserveFile.read(f), System.currentTimeMillis(), true, 0L), ReserveFile.Conservative)
    finally dir.setWritable(true)
  }

  test("a mode change and the reservations' publications never leave an older mode's floor last") {
    val dir = Files.createTempDirectory("reserve").toFile
    val f = File(dir, "logging-reserve")
    @volatile var mode = LoggingMode.Eco
    val writes = java.util.concurrent.ConcurrentLinkedQueue[Long]()
    lazy val pub: FloorPublisher = FloorPublisher(f, () => mode, m => StorageBudget.loggingReserve(m, None), () => budget.outstanding,
      write = (file, p) => { writes.add(p.l); Thread.sleep(1); ReserveFile.write(file, p) })
    lazy val budget: StorageBudget = StorageBudget(() => Long.MaxValue / 4, () => StorageBudget.loggingReserve(mode, None), () => pub.publish())
    val lMax = StorageBudget.loggingReserve(LoggingMode.Max, None)
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val reservers = (0 until 4).map(_ => Thread(() => while !stop.get do budget.reserve(1000).foreach(_.release())))
    reservers.foreach(_.start())
    Thread.sleep(50)
    // The mode is stored only after its floor is written; a reservation's
    // publication in between would read the old mode without the lock.
    pub.changeMode(LoggingMode.Max) { Thread.sleep(30); mode = LoggingMode.Max }
    Thread.sleep(50)
    stop.set(true)
    reservers.foreach(_.join(5000))
    val seq = writes.toArray.toSeq.map(_.asInstanceOf[Long])
    val first = seq.indexOf(lMax)
    assert(first >= 0)
    assert(seq.drop(first).forall(_ == lMax), "an older mode's floor was written after the new one")
    assertEquals(ReserveFile.read(f).map(_.l), Some(lMax))
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

  test("old logs are imported in batches of at most the asked bytes of text and 2,000 lines") {
    val lines = (0 until 30_000).map(i => s"Raw,$i," + "x" * (if i % 1000 == 0 then 400 else 80))
    val batches = LegacyBatches(lines.iterator).toSeq
    assertEquals(batches.flatten, lines)
    assert(batches.forall(b => b.size <= 2000 && b.map(LegacyBatches.utf8).sum <= MiB))
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

  test("bulk SQLite batches: capped so that scattered rows change at most 16 MiB; sized by the measured growth") {
    assertEquals(SqliteBatch.maxRows(1), 1024)
    assertEquals(SqliteBatch.maxRows(2), 682)
    // Scattered deletes measured on SQLite (4 KiB pages, secure delete): 512
    // rows changed 3.54 MiB, 1,024 rows 6.28 MiB; the cap holds at 2 pages a row and more.
    assert(SqliteBatch.maxRows(1) * 2L * 2 * SqliteBatch.PageBytes <= SqliteBatch.Margin)
    val s = BatchSizer()
    // Small first.
    assertEquals(s.rows, 64)
    // Doubled while a batch grows the files by less than 2 MiB.
    s.observe(64, 200L * 1024)
    assertEquals(s.rows, 128)
    s.observe(128, 1L << 20)
    assertEquals(s.rows, 256)
    // Kept between 2 and 4 MiB.
    s.observe(256, 3L << 20)
    assertEquals(s.rows, 256)
    // Smaller at once after a batch over 4 MiB: half the limit's worth.
    s.observe(256, 8L << 20)
    assertEquals(s.rows, 64)
    // No growth (the WAL reused): it grows, but never past the cap.
    for _ <- 0 until 10 do s.observe(s.rows, 0)
    assertEquals(s.rows, SqliteBatch.maxRows(1))
    // A huge row: one at a time.
    s.observe(s.rows, 100L << 20)
    assertEquals(s.rows, 20)
    s.observe(20, 100L << 20)
    assertEquals(s.rows, 1)
    // A simulated scattered delete (6.3 KiB of pages a row): no batch over the 16 MiB margin.
    val t = BatchSizer()
    var left = 300_000
    var worst = 0L
    while left > 0 do
      val n = math.min(left, t.rows)
      val grown = n * 6_300L
      worst = math.max(worst, grown)
      t.observe(n, grown)
      left -= n
    assert(worst <= SqliteBatch.Margin, worst.toString)
    // The text of an import, in bytes.
    val u = BatchSizer(start = 64 * 1024, min = 4 * 1024, max = 1 << 20)
    u.observe(64 * 1024, 150L * 1024)
    assertEquals(u.rows, 128 * 1024)
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
    assertEquals(b.reserve(5_000), None)
    assertEquals(b.outstanding, 0L)
    // A truncated file has more to write again: published, or it stops.
    fail = false
    val d = b.reserveFile(5_000, 3_000).get
    fail = true
    assert(!d.at(1_000))
  }
