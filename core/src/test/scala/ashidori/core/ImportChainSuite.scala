package ashidori.core

/** The import of overflow files on the writer's queue ([[ImportChain]]) and
  * the count of rows given up ([[LossCounter]]).
  */
class ImportChainSuite extends munit.FunSuite:
  /** The writer's queue (FIFO), a clock for the delayed runs, the files, and what was written in order. */
  private final class World(files0: Seq[String]):
    val queue = scala.collection.mutable.Queue.empty[Runnable]
    val delayed = scala.collection.mutable.ArrayBuffer.empty[(Long, Runnable)]
    var files = files0.toVector
    val written = scala.collection.mutable.ArrayBuffer.empty[String]
    var pendingBatches = 0
    var stop = false
    var fails = Set.empty[String]
    var busy = false
    val chain = ImportChain(queue.enqueue(_), (r, ms) => delayed += ((ms, r)), () => pendingBatches, () => stop,
      skip => ImportChain.order(files).find(f => !skip(f)),
      f =>
        if busy then ImportChain.Step.Busy
        else if fails(f) then ImportChain.Step.Failed
        else
          written += f
          files = files.filterNot(_ == f)
          ImportChain.Step.Imported)
    /** A new batch handed over: queued (counted pending until it runs). */
    def batch(name: String): Unit =
      pendingBatches += 1
      queue.enqueue(() => { written += name; pendingBatches -= 1 })
    def drain(max: Int = 10_000): Unit =
      var n = 0
      while queue.nonEmpty && n < max do
        queue.dequeue().run()
        n += 1

  private def names(n: Int, kind: String = "raw", ext: String = ".txt", from: Int = 0) =
    (from until from + n).map(i => f"$kind-${1_000_000L + i}%013d-p1-$i%06d$ext")

  test("one file per step: a batch handed over while the import runs is written before the next file") {
    val w = World(names(5))
    w.chain.request()
    w.queue.dequeue().run()
    assertEquals(w.written.toSeq, Seq(names(5).head))
    // Two batches arrive: they are queued before the next step runs.
    w.batch("new-1")
    w.batch("new-2")
    w.drain()
    assertEquals(w.written.toSeq.take(3), Seq(names(5).head, "new-1", "new-2"))
    assertEquals(w.written.size, 7)
  }

  test("a step finding batches waiting imports nothing: no first file goes anyway, raw or small") {
    val w = World(names(3) ++ names(3, "small", ".rec", from = 10))
    w.batch("new-1")
    w.chain.request()
    // The step is behind new-1 here; queue a batch the step itself will find waiting.
    w.queue.dequeue().run() // new-1
    w.batch("new-2")
    w.drain()
    val firstImport = w.written.indexWhere(n => n.startsWith("raw") || n.startsWith("small"))
    assert(w.written.indexOf("new-2") < firstImport, w.written.toString)
  }

  test("the files are imported oldest first across raw and small batches") {
    val raw = names(2)
    val small = names(2, "small", ".rec", from = 1)
    val w = World(raw ++ small)
    w.chain.request()
    w.drain()
    assertEquals(w.written.toSeq, Seq(raw(0), raw(1), small(0), small(1)).sortBy(_.dropWhile(_ != '-')))
  }

  test("logging stopped: the import goes on past the old caps (20 raw, 50 small) until no file is left") {
    val w = World(names(120) ++ names(130, "small", ".rec", from = 200))
    w.chain.request()
    w.drain()
    assertEquals(w.written.size, 250)
    assert(w.files.isEmpty)
  }

  test("a file that fails is skipped, the rest imported; it is tried again after a doubling backoff, never in a tight loop") {
    val w = World(names(4))
    w.fails = Set(names(4)(1))
    w.chain.request()
    w.drain()
    assertEquals(w.written.toSeq, Seq(names(4)(0), names(4)(2), names(4)(3)))
    // One delayed retry, nothing queued meanwhile.
    assertEquals(w.delayed.map(_._1).toSeq, Seq(ImportChain.FirstBackoffMs))
    assert(w.queue.isEmpty)
    // Still failing: the next wait is twice as long.
    val (_, r) = w.delayed.remove(0)
    r.run()
    w.drain()
    assertEquals(w.delayed.map(_._1).toSeq, Seq(2 * ImportChain.FirstBackoffMs))
    // Then it works.
    w.fails = Set.empty
    val (_, r2) = w.delayed.remove(0)
    r2.run()
    w.drain()
    assert(w.files.isEmpty)
    assert(w.delayed.isEmpty)
  }

  test("a file held by a backup is tried again later; the emergency state ends the chain") {
    val w = World(names(3))
    w.busy = true
    w.chain.request()
    w.drain()
    assertEquals(w.delayed.map(_._1).toSeq, Seq(ImportChain.BusyMs))
    w.busy = false
    w.stop = true
    val (_, r) = w.delayed.remove(0)
    r.run()
    w.drain()
    assert(w.written.isEmpty)
    w.stop = false
    w.chain.request()
    w.drain()
    assertEquals(w.written.size, 3)
  }

  test("requests while a step is queued post nothing more") {
    val w = World(names(2))
    w.chain.request()
    w.chain.request()
    w.chain.request()
    assertEquals(w.queue.size, 1)
  }

  // --- the count of rows given up ------------------------------------------------------------

  /** A file whose rename may succeed and the directory sync after it fail. */
  private final class LossFile:
    var onDisk = 100L
    var failBeforeRename = false
    var failAfterRename = false
    def write(t: Long): Unit =
      if failBeforeRename then throw java.io.IOException("ENOSPC")
      onDisk = t
      if failAfterRename then throw java.io.IOException("fsync of the directory")

  test("a write that failed after its rename is written again as the same total, not the rows added twice") {
    val f = LossFile()
    val c = LossCounter(() => f.onDisk, f.write)
    f.failAfterRename = true
    assert(c.add(10).isDefined)
    assertEquals(f.onDisk, 110L)
    assertEquals(c.total, 110L)
    assertEquals(c.uncounted, 0L)
    assert(c.unwritten)
    f.failAfterRename = false
    assert(c.add(5).isEmpty)
    assertEquals(f.onDisk, 115L)
    assert(!c.unwritten)
  }

  test("a write that failed before its rename keeps the rows uncounted; a flush writes the same total") {
    val f = LossFile()
    val c = LossCounter(() => f.onDisk, f.write)
    f.failBeforeRename = true
    assert(c.add(10).isDefined)
    assert(c.add(7).isDefined)
    assertEquals(f.onDisk, 100L)
    assertEquals(c.uncounted, 17L)
    assertEquals(c.total, 117L)
    f.failBeforeRename = false
    assert(c.flush().isEmpty)
    assert(c.flush().isEmpty)
    assertEquals(f.onDisk, 117L)
    assertEquals(c.uncounted, 0L)
  }
