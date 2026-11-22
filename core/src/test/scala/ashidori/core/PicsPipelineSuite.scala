package ashidori.core

import java.time.{LocalDate, ZoneId}

/** The path from the callback to rows (design 4.6): nothing taken is left
  * out, a full queue is counted, the test follows the plan each round; and
  * the scan's stop, the start gate across loggers, the colours' freshness.
  */
class PicsPipelineSuite extends munit.FunSuite:
  private val zone = ZoneId.of("Asia/Tokyo")
  private val t0 = LocalDate.of(2026, 11, 20).atStartOfDay(zone).toInstant.toEpochMilli + 10 * 3_600_000L
  private def data(i: Int) =
    PicsFormat.unhex(f"580100FF000000CD0849${i & 0xff}%02X${(i >> 8) & 0xff}%02X000000000000000000000000").toOption.get.toIndexedSeq
  private def res(i: Int, at: Long = t0) = PicsResult(at + i, f"02:00:00:00:${i >> 8}%02X:${i & 0xff}%02X", 0x01ce, data(i), -60, "bal")

  test("a round takes at most its batch, the bound checked before taking: nothing taken is left out") {
    val p = PicsPipeline(zone, PicsRuns.Caps(newPerMinute = 10_000, totalBurst = 1e9, totalPerSec = 1e9, open = 10_000),
      queueSize = 10, batchMax = 3)
    (0 until 10).foreach(i => assert(p.offer(res(i), i)))
    val (b1, _) = p.step(0, t0, still = false, delayMs = 0, test = false)
    assertEquals(b1.map(_.elapsed), Vector(0L, 1L, 2L))
    assertEquals(p.queued, 7)
    val taken = b1 ++ Iterator.continually(p.step(0, t0, still = false, delayMs = 0, test = false)._1).takeWhile(_.nonEmpty).flatten
    assertEquals(taken.map(_.elapsed), (0L until 10L).toVector)
    assertEquals(p.queued, 0)
    // A full queue: what is not taken is counted.
    (0 until 15).foreach(i => p.offer(res(100 + i), i))
    Iterator.continually(p.step(0, t0, still = false, delayMs = 0, test = false)._1).takeWhile(_.nonEmpty).foreach(_ => ())
    assertEquals(p.runs.refusedTotal.get(PicsRuns.Refused.Queue), Some(5L))
  }

  test("the test follows the plan each round: stopped during a test and started again, the rows are normal") {
    val p = PicsPipeline(zone, PicsRuns.Caps(testRows = 5, newPerMinute = 10_000, totalBurst = 1e9, totalPerSec = 1e9))
    p.offer(res(0), 0)
    val (_, testRows) = p.step(0, t0, still = false, delayMs = 0, test = true)
    assert(p.runs.isTesting)
    assertEquals(testRows.map(_.n), Seq(1))
    // The logger stops in the test: the plan is reset, the next round ends the test.
    p.step(0, t0 + 1000, still = false, delayMs = 0, test = false)
    assert(!p.runs.isTesting)
    // After the restart, far more than the test's cap: none refused.
    (1 to 40).foreach(i => p.offer(res(i, t0 + 120_000), i))
    val (_, rows) = p.step(0, t0 + 120_100, still = false, delayMs = 0, test = false)
    val all = rows ++ p.runs.closeAll()
    assertEquals(all.size, 40)
    assertEquals(p.runs.refusedTotal.get(PicsRuns.Refused.Test), None)
  }

  test("the scan's stop: a batched scan is flushed, its results waited for, then stopped; the others stopped at once") {
    val order = scala.collection.mutable.ArrayBuffer.empty[String]
    val came = PicsScanStop.run(batched = true, () => order += "flush", ms => { order += s"wait $ms"; order += "results"; true },
      () => order += "stop")
    assert(came)
    assertEquals(order.toSeq, Seq("flush", s"wait ${PicsScanStop.TimeoutMs}", "results", "stop"))
    order.clear()
    // The results did not come in time: stopped anyway (the callback still takes them later).
    assert(!PicsScanStop.run(batched = true, () => order += "flush", _ => false, () => order += "stop"))
    assertEquals(order.toSeq, Seq("flush", "stop"))
    order.clear()
    assert(PicsScanStop.run(batched = false, () => order += "flush", _ => false, () => order += "stop"))
    assertEquals(order.toSeq, Seq("stop"))
  }

  test("the start gate is the process's: a logger stopped and started again starts no sooner than 10 s") {
    val g = PicsScanGate()
    assert(g.step(0, PicsScan.Opportunistic).start.isDefined)
    // The logger stops (its scan stopped) and a new one starts 3 s later with the same gate.
    assertEquals(g.step(1000, PicsScan.Off).stop, true)
    assertEquals(g.step(3000, PicsScan.Opportunistic), PicsScanGate.Decision(false, None, Some(10_000L)))
    assert(g.step(10_000, PicsScan.Opportunistic).start.isDefined)
  }

  test("the colours: shown for exactly 2 s after the reception, grey then, gone when receiving ends, the row for 10 min") {
    val at = 1_000_000L
    val v0 = PicsDisplay.at(at, receiving = true, signalsAt = at, lastAt = at).get
    assertEquals((v0.signals, v0.fresh, v0.nextAt), (true, true, at + 2000))
    assert(PicsDisplay.at(at + 1999, true, at, at).get.fresh)
    val v2 = PicsDisplay.at(at + 2000, true, at, at).get
    assertEquals((v2.signals, v2.fresh), (true, false))
    assertEquals(v2.nextAt, at + PicsScanPolicy.QuietMs + 1)
    // Receiving ended: no signals; the row until 10 min after the last reception.
    val v3 = PicsDisplay.at(at + 30_000, false, at, at).get
    assertEquals((v3.signals, v3.nextAt), (false, at + PicsDisplay.ShownMs + 1))
    assertEquals(PicsDisplay.at(at + PicsDisplay.ShownMs + 1, false, at, at), None)
  }
