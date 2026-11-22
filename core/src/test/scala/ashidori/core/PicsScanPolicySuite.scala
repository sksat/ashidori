package ashidori.core

import PicsScanPolicy.*

/** When to scan for PICS (docs/design/pics.md 3.1): the four states, the
  * owner's speed rule, the still rules, the test, and the gate that starts
  * at most once in 10 s, stops at once, retries and reopens.
  */
class PicsScanPolicySuite extends munit.FunSuite:
  private val Min = 60_000L
  private val base = Input(now = 100 * Min, enabled = true, permission = true, bluetooth = true, logging = true,
    speed = Some(1.3), railOrAir = false, stillSince = None, near = None, lastQualifying = None, lastSignals = None)

  private def run(steps: Seq[Input], from: Plan = Initial): Seq[Plan] =
    steps.scanLeft(from)((p, in) => plan(in, p)).tail

  test("stopped before anything: permission, Bluetooth, logging, the switch") {
    for in <- Seq(base.copy(permission = false), base.copy(bluetooth = false), base.copy(logging = false),
                  base.copy(enabled = false)) do
      assertEquals(plan(in, Initial).state, State.Stopped, in)
      assertEquals(plan(in, Initial).scan, PicsScan.Off)
    assertEquals(plan(base, Initial).scan, PicsScan.Opportunistic)
  }

  test("speed, not the transport mode: up to 14 m/s; never in a train, subway or flight leg; unknown speed is walking") {
    assertEquals(plan(base.copy(speed = Some(14.0)), Initial).state, State.Opportunistic)
    assertEquals(plan(base.copy(speed = Some(14.1)), Initial).state, State.Stopped)
    // A car near an intersection.
    assertEquals(plan(base.copy(speed = Some(12.0), near = Some(Near(80, base.now))), Initial).state, State.Standby)
    assertEquals(plan(base.copy(speed = Some(3.0), railOrAir = true, near = Some(Near(10, base.now))), Initial).state, State.Stopped)
    assertEquals(plan(base.copy(speed = None, near = Some(Near(10, base.now))), Initial).state, State.Standby)
  }

  test("standby within 150 m (distance − accuracy) of a known site with a fix under 5 min; left at 250 m or an old fix") {
    val near = plan(base.copy(near = Some(Near(150, base.now))), Initial)
    assertEquals((near.state, near.scan), (State.Standby, PicsScan.LowPower))
    assertEquals(plan(base.copy(near = Some(Near(151, base.now))), Initial).state, State.Opportunistic)
    // Hysteresis: in standby, it holds below 250 m.
    assertEquals(plan(base.copy(near = Some(Near(249, base.now))), near).state, State.Standby)
    assertEquals(plan(base.copy(near = Some(Near(250, base.now))), near).state, State.Opportunistic)
    // The fix is over 5 minutes old.
    assertEquals(plan(base.copy(near = Some(Near(10, base.now - 5 * Min - 1))), near).state, State.Opportunistic)
  }

  test("receiving on a qualifying reception; out after 20 s of none, or 60 s without kind 2") {
    val t = base.now
    val got = plan(base.copy(near = Some(Near(10, t)), lastQualifying = Some(t), lastSignals = Some(t)), Initial)
    assertEquals((got.state, got.scan, got.reportDelayMs), (State.Receiving, PicsScan.Balanced, 0L))
    // Also from opportunistic (a site not in the list).
    assertEquals(plan(base.copy(lastQualifying = Some(t)), Initial).state, State.Receiving)
    // Screen off with offloaded batching: LOW_LATENCY requested, 5 s report delay.
    val off = plan(base.copy(lastQualifying = Some(t), screenOn = false, batching = true), got)
    assertEquals((off.scan, off.reportDelayMs), (PicsScan.BalancedBatch, 5_000L))
    assertEquals(plan(base.copy(lastQualifying = Some(t), screenOn = false), got).scan, PicsScan.Balanced)
    // 20 s without one: standby (still near).
    val quiet = plan(base.copy(now = t + 20_001, near = Some(Near(10, t)), lastQualifying = Some(t), lastSignals = Some(t)), got)
    assertEquals(quiet.state, State.Standby)
    // It does not come back for the old reception, only for a new one.
    assertEquals(plan(base.copy(now = t + 20_002, near = Some(Near(10, t)), lastQualifying = Some(t)), quiet).state, State.Standby)
    assertEquals(plan(base.copy(now = t + 21_000, near = Some(Near(10, t)), lastQualifying = Some(t + 20_500)), quiet).state,
      State.Receiving)
    // Kinds 0 and 1 but no kind 2 for 60 s: out, and back only for a kind 2.
    val noSig = run((1 to 61).map(s => base.copy(now = t + s * 1000L, lastQualifying = Some(t + s * 1000L), lastSignals = Some(t))), got)
    assertEquals(noSig.last.state, State.Opportunistic)
    assertEquals(noSig.last.why, "60 秒信号の受信なし")
    val t2 = t + 62_000L
    assertEquals(plan(base.copy(now = t2, lastQualifying = Some(t2), lastSignals = Some(t)), noSig.last).state, State.Opportunistic)
    assertEquals(plan(base.copy(now = t2, lastQualifying = Some(t2), lastSignals = Some(t2)), noSig.last).state, State.Receiving)
  }

  test("still: no new scan, receiving goes on up to 5 min, the opportunistic scan stays") {
    val t = base.now
    val receiving = plan(base.copy(lastQualifying = Some(t), lastSignals = Some(t)), Initial)
    val still = base.copy(stillSince = Some(t), near = Some(Near(0, t)))
    // Still at once: no standby, no receiving from nothing.
    assertEquals(plan(still.copy(lastQualifying = Some(t)), Initial).state, State.Opportunistic)
    // Receiving continues while still (a red light is shorter than the estimate's 3 min).
    val waits = run((1 to 299).map(s => still.copy(now = t + s * 1000L, lastQualifying = Some(t + s * 1000L), lastSignals = Some(t + s * 1000L))),
      receiving)
    assert(waits.forall(_.state == State.Receiving))
    val over = plan(still.copy(now = t + 5 * Min, lastQualifying = Some(t + 5 * Min), lastSignals = Some(t + 5 * Min)), waits.last)
    assertEquals((over.state, over.scan), (State.Opportunistic, PicsScan.Opportunistic))
    // Moving again: a new reception starts receiving.
    assertEquals(plan(base.copy(now = t + 6 * Min, lastQualifying = Some(t + 6 * Min)), over).state, State.Receiving)
  }

  test("the test: LOW_LATENCY for 60 s whatever the distance and motion; permission, Bluetooth and logging come first") {
    val t = base.now
    val test = base.copy(testUntil = Some(t + TestMs), speed = Some(30.0), railOrAir = true, stillSince = Some(0L), enabled = false)
    assertEquals((plan(test, Initial).state, plan(test, Initial).scan), (State.Test, PicsScan.LowLatency))
    assertEquals(plan(test.copy(now = t + TestMs), Initial).state, State.Stopped)
    assertEquals(plan(test.copy(bluetooth = false), Initial).state, State.Stopped)
    assertEquals(plan(test.copy(logging = false), Initial).state, State.Stopped)
  }

  test("the train classification: a fast smooth leg is rail; walking and a slow car are not") {
    val t0 = 1_760_000_000_000L
    def leg(speed: Double, n: Int = 120): Seq[Fix] =
      (0 until n).map(i => Fix(t0 + i * 5000L, 35.0 + i * speed * 5 / 111_000.0, 139.0, 10f, speed = speed.toFloat))
    val smooth = (0 until 60).map(i => MotionWindow(t0 + i * 10_000L, t0 + (i + 1) * 10_000L, 0.1f, 0f, 0))
    assert(railOrAir(leg(35), smooth, t0 + 600_000L))
    assert(railOrAir(leg(15), smooth, t0 + 600_000L))
    assert(!railOrAir(leg(1.3), (0 until 60).map(i => MotionWindow(t0 + i * 10_000L, t0 + (i + 1) * 10_000L, 2f, 0f, 18)), t0 + 600_000L))
    assert(!railOrAir(leg(10), (0 until 60).map(i => MotionWindow(t0 + i * 10_000L, t0 + (i + 1) * 10_000L, 1.2f, 0f, 0)), t0 + 600_000L))
    assert(!railOrAir(leg(35).take(3), smooth, t0 + 600_000L))
  }

  // --- the gate ---------------------------------------------------------------------------

  import PicsScanGate.*

  test("a start at most once in 10 s by every path; a change held is made after; a stop at once") {
    val g = PicsScanGate()
    val t = 1_000_000L
    assertEquals(g.step(t, PicsScan.Opportunistic), Decision(false, Some(PicsScan.Opportunistic), None))
    // The state changed 2 s later: held until 10 s after the start, the old scan kept meanwhile.
    assertEquals(g.step(t + 2000, PicsScan.LowPower), Decision(false, None, Some(t + 10_000)))
    assertEquals(g.registered.map(_._1), Some(PicsScan.Opportunistic))
    assertEquals(g.step(t + 10_000, PicsScan.LowPower), Decision(true, Some(PicsScan.LowPower), None))
    // Off: at once, whenever.
    assertEquals(g.step(t + 11_000, PicsScan.Off), Decision(true, None, None))
    assertEquals(g.step(t + 12_000, PicsScan.Off), Decision(false, None, None))
    // The test is a start like any other.
    assertEquals(g.step(t + 13_000, PicsScan.LowLatency).start, None)
    assertEquals(g.step(t + 20_000, PicsScan.LowLatency).start, Some(PicsScan.LowLatency))
    // Nothing to do: the same scan.
    assertEquals(g.step(t + 21_000, PicsScan.LowLatency), Decision(false, None, Some(t + 20_000 + ReopenMs)))
    // The report delay is part of the registration.
    assertEquals(g.step(t + 31_000, PicsScan.LowLatency, 5000).start, Some(PicsScan.LowLatency))
  }

  test("no more than 5 starts in any 30 s, whatever the requests") {
    val g = PicsScanGate()
    val rnd = scala.util.Random(5)
    val starts = scala.collection.mutable.ArrayBuffer.empty[Long]
    var t = 0L
    while t < 3_600_000L do
      val want = PicsScan.values(rnd.nextInt(PicsScan.values.length))
      if g.step(t, want).start.isDefined then starts += t
      if rnd.nextInt(20) == 0 then g.failed(t, rnd.nextInt(7))
      t += rnd.nextInt(3000)
    assert(starts.nonEmpty)
    for s <- starts do assert(starts.count(x => x >= s && x < s + 30_000) <= 3)
  }

  test("a failed start: 30 s after too frequent; otherwise 1 min doubling to 30 min, from the start again once it holds") {
    val g = PicsScanGate()
    var t = 0L
    g.step(t, PicsScan.Balanced)
    g.failed(t + 100, TooFrequently)
    assertEquals(g.registered, None)
    assertEquals(g.step(t + 20_000, PicsScan.Balanced), Decision(false, None, Some(t + 30_100)))
    assertEquals(g.step(t + 30_100, PicsScan.Balanced).start, Some(PicsScan.Balanced))
    // Other failures: 1, 2, 4, ... 30 min.
    t = 100_000L
    val gaps = (0 until 8).map { _ =>
      g.failed(t, 2)
      val at = g.retryTime.get
      assertEquals(g.step(at - 1, PicsScan.Balanced).start, None)
      assertEquals(g.step(at, PicsScan.Balanced).start, Some(PicsScan.Balanced))
      val gap = at - t
      t = at + 1
      gap
    }
    assertEquals(gaps.map(_ / 60_000L), Seq(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L))
    // It held a minute: the next failure waits 1 min again.
    g.step(t + 60_000, PicsScan.Balanced)
    g.failed(t + 60_001, 2)
    assertEquals(g.retryTime, Some(t + 60_001 + 60_000))
    assertEquals(g.failure.map(_._2), Some(2))
  }

  test("a receiving scan is reopened after 8 min (not the others); Bluetooth off loses the registration") {
    val g = PicsScanGate()
    g.step(0, PicsScan.Balanced)
    assertEquals(g.step(ReopenMs - 1, PicsScan.Balanced).start, None)
    assertEquals(g.step(ReopenMs, PicsScan.Balanced), Decision(true, Some(PicsScan.Balanced), Some(2 * ReopenMs)))
    g.step(ReopenMs + 20_000, PicsScan.LowPower)
    assertEquals(g.step(ReopenMs + 20_000 + 2 * ReopenMs, PicsScan.LowPower), Decision(false, None, None))
    g.lost()
    assertEquals(g.step(4 * ReopenMs, PicsScan.LowPower).start, Some(PicsScan.LowPower))
  }
