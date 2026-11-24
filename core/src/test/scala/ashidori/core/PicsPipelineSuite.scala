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

  test("the scan's stop: a batched scan is flushed and stopped after the whole window; the others stopped at once") {
    val order = scala.collection.mutable.ArrayBuffer.empty[String]
    PicsScanStop.run(batched = true, () => order += "flush", ms => order += s"window $ms", () => order += "stop")
    assertEquals(order.toSeq, Seq("flush", s"window ${PicsScanStop.TimeoutMs}", "stop"))
    order.clear()
    PicsScanStop.run(batched = false, () => order += "flush", ms => order += s"window $ms", () => order += "stop")
    assertEquals(order.toSeq, Seq("stop"))
  }

  test("the flush window: a batch delivered first does not end it; the flushed results arriving late are in it") {
    // As the scanner runs it: deliveries on the callback's thread, the stop when the window is over.
    val flushAt = 10_000L
    val w = PicsScanStop.FlushWindow(flushAt)
    val taken = scala.collection.mutable.ArrayBuffer.empty[String]
    val deliveries = Seq(flushAt + 10 -> "batch of before the flush", flushAt + 1_500 -> "the flushed results")
    var stoppedAt = -1L
    var t = flushAt
    while stoppedAt < 0 do
      deliveries.filter(_._1 == t).foreach { (at, what) => w.delivered(at); taken += what }
      if w.over(t) then stoppedAt = t
      t += 10
    assertEquals(taken.toSeq, deliveries.map(_._2))
    assertEquals(w.deliveries, 2)
    assertEquals(stoppedAt, flushAt + PicsScanStop.TimeoutMs)
    // Never earlier, whatever came.
    assert(!w.over(flushAt + PicsScanStop.TimeoutMs - 1))
  }

  test("the start gate is the process's: a logger stopped and started again starts no sooner than 10 s") {
    val g = PicsScanGate()
    assert(g.step(0, PicsScan.Opportunistic).start.isDefined)
    // The logger stops (its scan stopped) and a new one starts 3 s later with the same gate.
    assertEquals(g.step(1000, PicsScan.Off).stop, true)
    assertEquals(g.step(3000, PicsScan.Opportunistic), PicsScanGate.Decision(false, None, Some(10_000L)))
    assert(g.step(10_000, PicsScan.Opportunistic).start.isDefined)
  }

  test("the colours: shown for exactly 2 s after the reception, grey then, none 20 s after it") {
    val at = 1_000_000L
    val v0 = PicsDisplay.at(at, signalsAt = at)
    assertEquals((v0.signals, v0.fresh, v0.nextAt), (true, true, at + 2000))
    assert(PicsDisplay.at(at + 1999, at).fresh)
    val v2 = PicsDisplay.at(at + 2000, at)
    assertEquals((v2.signals, v2.fresh), (true, false))
    assertEquals(v2.nextAt, at + PicsScanPolicy.QuietMs + 1)
    val v3 = PicsDisplay.at(at + PicsScanPolicy.QuietMs + 1, at)
    assertEquals((v3.signals, v3.fresh, v3.nextAt), (false, false, Long.MaxValue))
    assertEquals(PicsDisplay.at(at, 0L).signals, false)
  }
