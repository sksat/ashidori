package ashidori.core

class LifeLogSuite extends munit.FunSuite:
  import LifeKind.*

  test("events round-trip through one line, tabs and line breaks flattened") {
    val e = LifeEvent(1_700_000_000_000L, ForegroundFailed, "SecurityException:\tnot\nallowed")
    val line = LifeEvent.encode(e)
    assert(!line.contains('\n'))
    assertEquals(line.count(_ == '\t'), 2)
    assertEquals(LifeEvent.decode(line), Some(e.copy(detail = "SecurityException: not allowed")))
    assertEquals(LifeEvent.decode(LifeEvent.encode(LifeEvent(5L, Boot))), Some(LifeEvent(5L, Boot, "")))
  }

  test("broken or unknown lines are skipped") {
    assertEquals(LifeEvent.decode(""), None)
    assertEquals(LifeEvent.decode("abc\tstart\tx"), None)
    assertEquals(LifeEvent.decode("12\tnosuchkind\tx"), None)
  }

  test("the ring keeps the newest entries once the file holds twice the limit") {
    val lines = (1 to 1200).map(_.toString)
    assert(!LifeEvent.needsCompaction(1199, 600))
    assert(LifeEvent.needsCompaction(1200, 600))
    assertEquals(LifeEvent.compact(lines, 600), (601 to 1200).map(_.toString))
  }

  test("steps reported one at a time still wake the stationary logger") {
    // The old rule needed >= 10 steps in one delivery: a walk reported step
    // by step never woke it.
    val w = StepWake()
    val wakes = (0 until 12).map(i => w.add(1_000L * i, 1))
    assertEquals(wakes.indexWhere(identity), 9)
  }

  test("a burst in one delivery wakes at once; scattered steps do not") {
    assert(StepWake().add(0L, 25))
    val w = StepWake()
    // 3 steps every 5 minutes: shuffling around at home.
    assert((0 until 20).forall(i => !w.add(i * 5 * 60_000L, 3)))
    assert(!w.add(0L, 0))
  }

  test("after a wake the count starts over") {
    val w = StepWake()
    assert(w.add(0L, 10))
    assert(!w.add(1_000L, 9))
    assert(w.add(2_000L, 1))
    w.reset()
    assert(!w.add(3_000L, 9))
  }

  test("gaps include the edges and need the minimum length") {
    val m = 60_000L
    val gaps = RecordingGaps.find(Seq(50 * m, 10 * m, 12 * m), 0L, 100 * m, 30 * m)
    assertEquals(gaps, Vector((12 * m, 50 * m), (50 * m, 100 * m)))
    assertEquals(RecordingGaps.find(Nil, 0L, 10 * m, 30 * m), Vector.empty)
    assertEquals(RecordingGaps.find(Nil, 0L, 40 * m, 30 * m), Vector((0L, 40 * m)))
  }

  test("gaps are explained from the lifecycle log") {
    val h = 3_600_000L
    val events = Seq(
      LifeEvent(0L, ServiceStart, "start"),
      LifeEvent(1 * h, StillEnter),
      LifeEvent(5 * h, StillExit, "sigmotion"),
      // killed, and the sticky restart could not go foreground
      LifeEvent(10 * h, ProcessExit, "LOW_MEMORY"),
      LifeEvent(10 * h + 1000, ForegroundFailed, "ForegroundServiceStartNotAllowedException"),
      LifeEvent(14 * h, ServiceStart, "start"),
      LifeEvent(20 * h, ServiceStop, "user"),
      LifeEvent(30 * h, ServiceStart, "start")
    )
    val gaps = Seq((1 * h + 60_000, 5 * h), (9 * h, 14 * h), (14 * h + 60_000, 15 * h), (21 * h, 30 * h))
    val out = RecordingGaps.explain(gaps, events)
    assertEquals(out.map(_.cause), Vector(GapCause.Still, GapCause.ServiceDown, GapCause.Unknown, GapCause.Stopped))
    assertEquals(out(1).events.map(_.kind), Vector(ProcessExit, ForegroundFailed))
  }

  test("a service already down at the gap's start explains it") {
    val events = Seq(LifeEvent(0L, ServiceStart), LifeEvent(10L, Crash, "IllegalStateException"))
    assertEquals(RecordingGaps.explain(Seq((20L, 1000L)), events).map(_.cause), Vector(GapCause.ServiceDown))
  }

  test("platform codes have names") {
    assertEquals(PlatformCodes.exitReason(3), "LOW_MEMORY")
    assertEquals(PlatformCodes.exitReason(99), "REASON_99")
    assertEquals(PlatformCodes.importance(125), "FOREGROUND_SERVICE")
    assertEquals(PlatformCodes.standbyBucket(45), "RESTRICTED")
    assert(PlatformCodes.exitIsProblem(4) && !PlatformCodes.exitIsProblem(16))
    assert(LifeEvent(0L, ProcessExit, "LOW_MEMORY importance=FOREGROUND_SERVICE").problem)
    assert(!LifeEvent(0L, ProcessExit, "PACKAGE_UPDATED importance=CACHED").problem)
    assert(LifeEvent(0L, Crash).problem && !LifeEvent(0L, StillEnter).problem)
  }
