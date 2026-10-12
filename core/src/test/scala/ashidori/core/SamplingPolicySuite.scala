package ashidori.core

class SamplingPolicySuite extends munit.FunSuite:
  private val normal = PowerState(80, charging = false, powerSave = false)
  private def in(
      mode: LoggingMode = LoggingMode.Balanced,
      power: PowerState = normal,
      motion: MotionState = MotionState.OnFoot,
      now: Long = 10_000_000L,
      lastDetail: Long = 9_000_000L,
      burstUntil: Long = 0L
  ) = PolicyInput(mode, power, motion, 1.3, now, lastDetail, burstUntil)

  test("stationary turns GNSS off and stops the accelerometer") {
    val p = SamplingPolicy.plan(in(mode = LoggingMode.Eco, motion = MotionState.Stationary))
    assertEquals(p.source, LocationSource.Passive)
    assertEquals(p.accelHz, 0)
    assert(!p.rawGnss)
  }

  test("balanced: vehicles sample faster than walking, both batched") {
    val walk = SamplingPolicy.plan(in(motion = MotionState.OnFoot))
    val car = SamplingPolicy.plan(in(motion = MotionState.Vehicle))
    assertEquals(walk.source, LocationSource.Gnss)
    assert(car.intervalMs < walk.intervalMs)
    assert(walk.maxBatchDelayMs > 0)
  }

  test("detail session runs periodically and for a limited duration") {
    val prof = SamplingPolicy.profiles(LoggingMode.Balanced)
    val now = 100 * 60_000L
    assert(SamplingPolicy.plan(in(now = now, lastDetail = now - prof.detailEveryMovingMs)).rawGnss)
    assert(SamplingPolicy.plan(in(now = now, lastDetail = now - 10_000)).rawGnss)
    assert(!SamplingPolicy.plan(in(now = now, lastDetail = now - prof.detailDurationMs - 1)).rawGnss)
  }

  test("max mode logs everything at 1 Hz even when stationary") {
    val p = SamplingPolicy.plan(in(mode = LoggingMode.Max, motion = MotionState.Stationary))
    assertEquals(p.source, LocationSource.Gnss)
    assertEquals(p.intervalMs, 1000L)
    assert(p.rawGnss)
    assertEquals(p.maxBatchDelayMs, 0L)
  }

  test("low battery degrades, charging upgrades") {
    val low = SamplingPolicy.plan(in(power = PowerState(12, false, false)))
    assertEquals(low.effectiveMode, LoggingMode.Eco)
    assert(!low.rawGnss)
    val charging = SamplingPolicy.plan(in(power = PowerState(50, true, false)))
    assertEquals(charging.effectiveMode, LoggingMode.Precise)
    val maxLow = SamplingPolicy.plan(in(mode = LoggingMode.Max, power = PowerState(12, false, false)))
    assertEquals(maxLow.effectiveMode, LoggingMode.Precise)
  }

  test("excess drain stretches the interval") {
    val ok = SamplingPolicy.plan(in(power = normal.copy(screenOffDrainPctPerHour = 1.5)))
    val hot = SamplingPolicy.plan(in(power = normal.copy(screenOffDrainPctPerHour = 6.0)))
    assert(hot.intervalMs > ok.intervalMs, s"$hot vs $ok")
  }

  test("burst request forces raw GNSS at 1 Hz") {
    val p = SamplingPolicy.plan(in(motion = MotionState.Stationary, burstUntil = 20_000_000L))
    assert(p.rawGnss)
    assertEquals(p.intervalMs, 1000L)
  }

  test("drain estimator ignores charging and screen-on periods") {
    import DrainEstimator.Sample
    val h = 3_600_000L
    val s = Seq(
      Sample(0, 90, false, false),
      Sample(h, 88, false, false), // 2%/h screen off
      Sample(h + 1, 88, false, true),
      Sample(2 * h, 70, false, true), // heavy screen-on use: ignored
      Sample(2 * h + 1, 70, false, false),
      Sample(3 * h, 68, false, false)
    )
    assertEqualsDouble(DrainEstimator.screenOffDrain(s, 3 * h, 4 * h), 2.0, 0.01)
  }

  test("drain estimator copes with zero or one sample") {
    import DrainEstimator.Sample
    assert(DrainEstimator.screenOffDrain(Nil, 0).isNaN)
    assert(DrainEstimator.screenOffDrain(Seq(Sample(0, 100, true, true)), 1000).isNaN)
  }

  test("motion estimator needs sustained stillness before going stationary") {
    val est = MotionStateEstimator(stillAfterMs = 60_000)
    def w(t: Long, std: Float, steps: Int = 0) = MotionWindow(t, t + 10_000, std, 0f, steps)
    assertEquals(est.update(w(0, 2.5f, 15), 1.4), MotionState.OnFoot)
    assertEquals(est.update(w(10_000, 0.05f), 0.0), MotionState.OnFoot)
    (2 to 7).foreach(i => est.update(w(i * 10_000L, 0.05f), 0.0))
    assertEquals(est.current, MotionState.Stationary)
    assertEquals(est.wake(), MotionState.Unknown)
    assertEquals(est.update(w(100_000, 0.5f), 12.0), MotionState.Vehicle)
  }

  test("accumulator computes stddev of magnitude") {
    val acc = MotionAccumulator(1000)
    (0 until 50).foreach(i => acc.add(i * 10L, 0f, 0f, if i % 2 == 0 then 9.0f else 11.0f))
    acc.addSteps(3)
    val w = acc.flush().get
    assertEqualsDouble(w.accelStd.toDouble, 1.0, 0.05)
    assertEquals(w.steps, 3)
  }
