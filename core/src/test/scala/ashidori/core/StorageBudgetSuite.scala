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

  test("the output wrapper: every output at most C, each checked just before it is written; a compressor's trailer at close too") {
    val events = scala.collection.mutable.ArrayBuffer.empty[String]
    val guard = SpaceGuard(() => { events += "check"; Long.MaxValue / 2 }, () => 0L)
    val sink = new OutputStream:
      def write(b: Int): Unit = events += "out 1"
      override def write(b: Array[Byte], off: Int, len: Int): Unit = events += s"out $len"
      override def flush(): Unit = events += "flush"
      override def close(): Unit = events += "close"
    val g = GuardedOutputStream(sink, guard)
    // A compressor with a buffer above the wrapper, as the backup's ZIP.
    val zip = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(g, 64 * 1024))
    zip.putNextEntry(java.util.zip.ZipEntry("a"))
    val rnd = scala.util.Random(7)
    val data = new Array[Byte](5 * MiB)
    rnd.nextBytes(data)
    zip.write(data)
    zip.closeEntry()
    zip.flush()
    val beforeClose = events.size
    zip.close()
    // Every output is preceded by its own check, and none is over C.
    val outs = events.toSeq.zipWithIndex.filter(_._1.startsWith("out "))
    assert(outs.nonEmpty)
    for (e, k) <- outs do
      assert(e.stripPrefix("out ").toInt <= SpaceGuard.Chunk, e)
      assertEquals(events(k - 1), "check", s"output $k not checked just before it")
    // The central directory and the end record are written at close: checked too.
    assert(events.drop(beforeClose).exists(_.startsWith("out ")))
    assertEquals(events.last, "close")
  }

  test("the model: N writers on one free space, each with one output in progress, take at most N × C below the reserve") {
    // The model of the guarantee for files (not the device: no allocation
    // units, buffers, SQLite or other apps): the free space reflects each
    // completed output exactly, each output takes its bytes, L is fixed.
    for n <- Seq(1, 2, 4) do
      val L = 100L * MiB
      val free = java.util.concurrent.atomic.AtomicLong(L + 9L * MiB + 123)
      val lowest = java.util.concurrent.atomic.AtomicLong(Long.MaxValue)
      // All check, then all write (the worst order), every round.
      val barrier = java.util.concurrent.CyclicBarrier(n)
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
      val ts = (0 until n).map(_ => Thread(writer()))
      ts.foreach(_.start())
      ts.foreach(_.join(10_000))
      assert(lowest.get < L + MiB, s"$n writers: stopped early, ${lowest.get - L}")
      assert(lowest.get >= L - n.toLong * MiB, s"$n writers: ${(L - lowest.get) / MiB.toDouble} MiB below L")
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

  test("bulk SQLite batches: 64 rows first, at most 512, doubled under 2 MiB of growth, smaller at once over 4 MiB") {
    val s = BatchSizer()
    assertEquals(s.rows, 64)
    s.observe(64, 200L * 1024)
    assertEquals(s.rows, 128)
    s.observe(128, 1L << 20)
    assertEquals(s.rows, 256)
    // Kept between 2 and 4 MiB.
    s.observe(256, 3L << 20)
    assertEquals(s.rows, 256)
    // Smaller at once after a batch over 4 MiB.
    s.observe(256, 8L << 20)
    assertEquals(s.rows, 64)
    // No growth (the WAL reused): it grows, never past the explicit cap.
    for _ <- 0 until 10 do s.observe(s.rows, 0)
    assertEquals(s.rows, 512)
    // A huge row: one at a time, never none.
    s.observe(512, 100L << 20)
    assertEquals(s.rows, 10)
    s.observe(10, 100L << 20)
    assertEquals(s.rows, 1)
    s.observe(1, 100L << 20)
    assertEquals(s.rows, 1)
    // The text of an import, in bytes.
    val u = BatchSizer(start = 64 * 1024, min = 4 * 1024, max = 1 << 20)
    u.observe(64 * 1024, 150L * 1024)
    assertEquals(u.rows, 128 * 1024)
  }

  test("bulk SQLite writes: long rows, BLOBs and sparse deletes with a long reader may exceed 16 MiB; after a shortfall no batch starts, nothing is dropped") {
    // A simulated table: each row's growth (a long reader keeps the WAL from
    // being reused: every batch grows the files by what it changed).
    val rows = (0 until 3000).map(i => if i == 700 then 40L << 20 else if i % 500 == 0 then 6L << 20 else 6_300L)
    val H = 1_000L << 20
    var free = H + (60L << 20)
    var files = 0L
    var deleted = 0
    val batches = scala.collection.mutable.ArrayBuffer.empty[BulkLoop.Batch]
    val guard = SpaceGuard(() => free, () => H)
    val sizer = BatchSizer()
    val r = scala.util.Try(BulkLoop.run(sizer, guard, () => free, () => files, b => batches += b) { n =>
      val take = rows.slice(deleted, deleted + n)
      val grown = take.sum
      files += grown
      free -= grown
      deleted += take.size
      (take.size, n, deleted < rows.size)
    })
    // A batch with the 40 MiB row consumed more than M: allowed (no bound claimed).
    assert(batches.exists(_.grown > Headroom.M), batches.map(_.grown).max.toString)
    // It stopped on a shortfall, after the batch that caused it, and started none after it.
    assert(r.failed.toOption.exists(_.isInstanceOf[StorageBudget.NoRoom]), r.toString)
    val last = batches.last
    assert(last.freeAfter - H < Headroom.M, last.toString)
    assert(batches.init.forall(_.freeAfter - H >= Headroom.M))
    // Every row is either deleted or still there (none skipped to go on).
    assertEquals(deleted, batches.map(_.rows).sum min rows.size)
    // Each next batch after a large one was smaller.
    for (a, b) <- batches.zip(batches.drop(1)) if a.grown > BatchSizer.Grow do assert(b.rows < a.rows || b.rows == 1, s"$a then $b")
    // With room, a later run resumes where it stopped and finishes.
    free = 10_000L << 20
    BulkLoop.run(sizer, guard, () => free, () => files) { n =>
      val take = rows.slice(deleted, deleted + n)
      free -= take.sum
      deleted += take.size
      (take.size, n, deleted < rows.size)
    }
    assertEquals(deleted, rows.size)
    // Below H + M before the first batch: none starts.
    var ran = false
    intercept[StorageBudget.NoRoom](BulkLoop.run(BatchSizer(), SpaceGuard(() => H + Headroom.M - 1, () => H), () => 0L, () => 0L) { _ => ran = true; (1, 1, false) })
    assert(!ran)
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

  test("a backup's estimate decides its start only: used up, it goes on while the free space leaves L, the others and n") {
    var free = 10_000L << 20
    val L = 1_000L << 20
    val b = StorageBudget(() => free, () => L)
    val r = b.reserve(100L << 20).get
    // It wrote more than it estimated (the copy, a WAL kept by a reader).
    r.account(150L << 20)
    assertEquals(r.outstanding, 0L)
    free = L + (5L << 20)
    assert(r.continues(SpaceGuard.Chunk), "stopped only because its own estimate is used up")
    // Its own rest is not held against it either.
    val s = b.reserve(1L << 20).get
    s.release()
    val t = b.reserve(0).get
    r.account(10L << 20)
    assert(r.continues(SpaceGuard.Chunk))
    // Another reservation's rest is.
    free = L + (60L << 20)
    val other = b.reserve((59L << 20) + (1L << 19)).get
    assert(!r.continues(SpaceGuard.Chunk), "the other reservation's rest must stay free")
    other.release()
    assert(r.continues(SpaceGuard.Chunk))
    // Below L + n: it stops.
    free = L + 1000
    assert(!r.continues(SpaceGuard.Chunk))
    t.release()
    r.release()
  }

  test("bulk SQLite writes: a shortfall after the last batch is reported too, and its rows stay done") {
    val H = 1_000L << 20
    var free = H + (20L << 20)
    var left = 100
    val seen = scala.collection.mutable.ArrayBuffer.empty[BulkLoop.Batch]
    val r = scala.util.Try(BulkLoop.run(BatchSizer(), SpaceGuard(() => free, () => H), () => free, () => 0L, seen += _) { n =>
      val k = math.min(n, left)
      left -= k
      // The last batch takes 10 MiB: H + M is not left after it.
      if left == 0 then free -= 10L << 20
      (k, n, left > 0)
    })
    assertEquals(left, 0, "every row done")
    assertEquals(seen.size, 2)
    assert(seen.last.freeAfter - H < Headroom.M)
    assert(r.failed.toOption.exists(_.isInstanceOf[StorageBudget.NoRoom]), s"not reported: $r")
    // With room after the last batch: done, nothing reported.
    free = H + (100L << 20)
    left = 10
    assertEquals(BulkLoop.run(BatchSizer(), SpaceGuard(() => free, () => H), () => free, () => 0L) { n =>
      val k = math.min(n, left); left -= k; (k, n, left > 0)
    }, 10L)
  }

  test("old logs imported through the bulk loop: each batch's growth and the free space after it recorded; stopped before and after") {
    val H = 1_000L << 20
    var free = H + (40L << 20)
    var files = 0L
    val lines = (0 until 200_000).map(i => s"Raw,$i," + "x" * 100)
    val sizer = BatchSizer(start = 64 * 1024, min = 4 * 1024, max = LegacyBatches.MaxBytes.toInt)
    var budget = sizer.rows.toLong
    val batches = LegacyBatches(lines.iterator, () => budget)
    val imported = scala.collection.mutable.ArrayBuffer.empty[String]
    val seen = scala.collection.mutable.ArrayBuffer.empty[BulkLoop.Batch]
    val r = scala.util.Try(BulkLoop.run(sizer, SpaceGuard(() => free, () => H), () => free, () => files, seen += _) { size =>
      budget = size.toLong
      val chunk = batches.next()
      val text = chunk.map(LegacyBatches.utf8).sum
      imported ++= chunk
      // The database grows by twice the text.
      files += text * 2
      free -= text * 2
      (chunk.size, text.toInt, batches.hasNext)
    })
    // Every batch recorded with its growth and the free space after it.
    assert(seen.nonEmpty)
    for (b, k) <- seen.zipWithIndex do
      assert(b.grown > 0, b.toString)
      assert(b.freeAfter <= H + (40L << 20), b.toString)
    assertEquals(seen.map(_.grown).sum, files)
    // The text budget grew from 64 KiB while batches grew the files by little.
    assert(seen.map(_.rows).max > 64 * 1024, seen.map(_.rows).toString)
    // It stopped on a shortfall (after a batch); what was imported is a prefix, nothing skipped.
    assert(r.failed.toOption.exists(_.isInstanceOf[StorageBudget.NoRoom]))
    assertEquals(imported.toSeq, lines.take(imported.size))
    assert(seen.last.freeAfter - H < Headroom.M)
  }

