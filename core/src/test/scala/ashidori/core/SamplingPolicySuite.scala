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

  test("indoors on foot: network positioning with short GNSS probes, no detail log") {
    val base = in(motion = MotionState.OnFoot).copy(environment = Environment.Indoor)
    val off = SamplingPolicy.plan(base.copy(now = SamplingPolicy.IndoorProbeMovingEveryMs * 10 + 60_000))
    assertEquals(off.source, LocationSource.Network)
    assert(!off.rawGnss)
    val probe = SamplingPolicy.plan(base.copy(now = SamplingPolicy.IndoorProbeMovingEveryMs * 10 + 5_000))
    assertEquals(probe.source, LocationSource.Gnss)
    // Riding a train underground is not "indoors on foot".
    assertEquals(SamplingPolicy.plan(base.copy(motion = MotionState.Vehicle, now = 99_000_000L + 60_000)).source, LocationSource.Gnss)
  }

  test("indoor state is a reason code, separate from its display label") {
    val base = in(motion = MotionState.OnFoot).copy(environment = Environment.Indoor)
    val indoor = SamplingPolicy.plan(base.copy(power = PowerState(80, charging = true, powerSave = false)))
    assert(indoor.indoorProbing)
    assert(indoor.reasons.contains(PlanReason.Indoor))
    // The mode note leaves the indoor state out but keeps the other reasons.
    assertEquals(indoor.modeReasons, List(PlanReason.Charging))
    assertEquals(PlanReason.Indoor.label, "屋内")
    // Outdoors, in 最高精度 or during a manual burst there is no indoor state.
    assert(!SamplingPolicy.plan(in(motion = MotionState.OnFoot)).indoorProbing)
    assert(!SamplingPolicy.plan(base.copy(mode = LoggingMode.Max)).indoorProbing)
    val burst = SamplingPolicy.plan(base.copy(burstUntil = base.now + 60_000))
    assert(!burst.indoorProbing)
    assertEquals(burst.modeReasons, List(PlanReason.ManualBurst))
    // Labels are display text only: each reason has its own.
    val labels = List(PlanReason.Charging, PlanReason.BatteryCritical, PlanReason.PowerSave, PlanReason.BatteryLow15,
      PlanReason.BatteryLow30, PlanReason.MaxCapped, PlanReason.ManualBurst, PlanReason.Indoor,
      PlanReason.DrainOverBudget(3.0, 2.0)).map(_.label)
    assertEquals(labels.distinct.size, labels.size)
  }

  test("indoor detector needs sustained weak signals and recovers outdoors") {
    val d = IndoorDetector(enterIndoorMs = 30_000, exitIndoorMs = 10_000)
    def weak(t: Long) = GnssSnapshot(t, 8, 2, 18, 0)
    def strong(t: Long) = GnssSnapshot(t, 25, 14, 38, 4)
    assertEquals(d.update(strong(0)), Environment.Outdoor)
    assertEquals(d.update(weak(1_000)), Environment.Outdoor)
    assertEquals(d.update(weak(20_000)), Environment.Outdoor)
    assertEquals(d.update(weak(31_000)), Environment.Indoor)
    assertEquals(d.update(strong(32_000)), Environment.Indoor)
    assertEquals(d.update(strong(43_000)), Environment.Outdoor)
  }

  test("indoor detector treats a long time without a fix as indoor") {
    val d = IndoorDetector(noFixIndoorMs = 60_000, enterIndoorMs = 0)
    d.onGnssStarted(0)
    // Many satellites seen but none usable for a minute: still no fix.
    assertEquals(d.update(GnssSnapshot(70_000, 10, 0, 22, 0)), Environment.Indoor)
  }

  test("snapshot summarises signals") {
    val s = GnssSnapshot.of(0, Seq((1, 3, 40f, true, false), (1, 3, 35f, true, true), (6, 11, 30f, false, false), (3, 7, 20f, false, false), (5, 2, 10f, false, false)))
    assertEquals(s.visible, 4)
    assertEquals(s.used, 1)
    assertEqualsDouble(s.top4Cn0, (40 + 35 + 30 + 20) / 4.0, 1e-9)
    assertEquals(s.l5, 1)
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

  test("indoors: probe GNSS at once when movement starts, often while moving, rarely while still") {
    val t0 = 50 * 60_000L
    def at(motion: MotionState, dt: Long) =
      SamplingPolicy.plan(PolicyInput(LoggingMode.Balanced, normal, motion, 1.0, t0 + dt, t0 + dt - 30_000,
        environment = Environment.Indoor, motionSince = t0))
    assertEquals(at(MotionState.OnFoot, 0).source, LocationSource.Gnss)
    assertEquals(at(MotionState.OnFoot, 60_000).source, LocationSource.Network)
    assertEquals(at(MotionState.OnFoot, SamplingPolicy.IndoorProbeMovingEveryMs + 1_000).source, LocationSource.Gnss)
    assertEquals(at(MotionState.OnFoot, 60_000).replanInMs, SamplingPolicy.IndoorProbeMovingEveryMs - 60_000)
    assertEquals(at(MotionState.Stationary, SamplingPolicy.IndoorProbeMovingEveryMs + 1_000).source, LocationSource.Network)
    assertEquals(at(MotionState.Stationary, SamplingPolicy.IndoorProbeStillEveryMs + 1_000).source, LocationSource.Gnss)
  }
