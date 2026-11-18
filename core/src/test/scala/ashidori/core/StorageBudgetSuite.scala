package ashidori.core

import java.io.File
import java.nio.file.Files

class StorageBudgetSuite extends munit.FunSuite:
  private def ledgerFile(): File = File(Files.createTempDirectory("budget").toFile, "storage-budget.json")

  test("two processes on one ledger: each counts the other's reservations") {
    val f = ledgerFile()
    var free = 10_000L
    val main = StorageBudget(() => free, () => 8_000L, BudgetLedger.Shared(f), owner = "main")
    val maint = StorageBudget(() => free, () => 0L, BudgetLedger.Shared(f), owner = "maintenance")
    // Before the main process publishes L, the other one has only its own floor.
    assertEquals(maint.loggingReserve, 0L)
    main.publish(8_000)
    // The maintenance process now reads L from the ledger.
    assertEquals(maint.loggingReserve, 8_000L)
    val a = main.reserve(1_500).get
    // 10,000 - 1,500 (the other process's reservation) - 8,000
    assertEquals(maint.reserve(501), None)
    val b = maint.reserve(500).get
    assertEquals(main.available, 0L)
    a.release()
    assertEquals(main.available, 1_500L)
    b.release()
    assertEquals(BudgetLedger.decode(String(Files.readAllBytes(f.toPath))).map(_.entries.size), Some(0))
  }

  test("a reservation of a process that died is dropped under the lock") {
    val f = ledgerFile()
    val dead = StorageBudget(() => 10_000L, () => 8_000L, BudgetLedger.Shared(f), owner = "boot1:123:old")
    dead.reserve(2_000).get
    // Seen from a process that checks liveness: that owner is gone.
    val alive = Set("boot1:456:new")
    val now = StorageBudget(() => 10_000L, () => 8_000L, BudgetLedger.Shared(f), owner = "boot1:456:new", alive = alive)
    assertEquals(now.available, 2_000L)
    assertEquals(now.entries.map(_.owner), Seq.empty)
    assert(now.reserve(2_000).nonEmpty)
  }

  test("L raised by the main process during a long write (over 32 MB) stops it within a MiB") {
    val f = ledgerFile()
    val dir = f.getParentFile
    val out = File(dir, "month.parquet.tmp")
    def free = 2_000_000_000L - out.length
    val main = StorageBudget(() => free, () => 256_000_000L, BudgetLedger.Shared(f), owner = "main")
    main.publish(256_000_000L)
    val maint = StorageBudget(() => free, () => 0L, BudgetLedger.Shared(f), owner = "maintenance")
    val r = maint.reserve(48_000_000L).get
    val buf = new Array[Byte](64 * 1024)
    val stream = BudgetedOutputStream(java.io.FileOutputStream(out), r)
    var raisedAt = -1L
    val e = intercept[StorageBudget.NoRoom] {
      try
        while out.length < 48_000_000L do
          stream.write(buf)
          // The user switches to 最高精度 at 33 MB: L becomes 5.04 GB.
          if raisedAt < 0 && out.length >= 33_000_000L then
            raisedAt = out.length
            main.publish(StorageBudget.loggingReserve(LoggingMode.Max, None))
      finally stream.close()
    }
    assert(raisedAt > 32_000_000L)
    assert(out.length - raisedAt <= (1L << 20) + buf.length, s"${out.length - raisedAt}")
    r.release()
  }

  test("a ledger that cannot be read counts as empty; many threads never lose a reservation") {
    val f = ledgerFile()
    Files.write(f.toPath, "{not json".getBytes)
    val b = StorageBudget(() => 1_000L, () => 0L, BudgetLedger.Shared(f), owner = "main")
    assertEquals(b.available, 1_000L)
    val got = java.util.concurrent.ConcurrentLinkedQueue[Object]()
    val threads = (0 until 8).map(_ => Thread(() => (0 until 50).foreach(_ => b.reserve(1).foreach(got.add))))
    threads.foreach(_.start())
    threads.foreach(_.join())
    // 1,000 bytes, 400 asked one by one: all fit, and the ledger holds every one.
    assertEquals(got.size, 400)
    assertEquals(b.available, 600L)
    // A second instance (another process) sees them too.
    assertEquals(StorageBudget(() => 1_000L, () => 0L, BudgetLedger.Shared(f), owner = "other").available, 600L)
  }

  test("a write that grows its reservation is admitted only while it fits") {
    var free = 10_000L
    val b = StorageBudget(() => free, () => 8_000L)
    val r = b.reserve(1_000).get
    assert(r.grow(1_000))
    assert(!r.grow(1))
    val other = b.reserve(1)
    assertEquals(other, None)
    free -= 2_000
    assert(r.wrote(2_000))
    r.release()
  }
