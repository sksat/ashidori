package ashidori.core

import Emergency.*

class EmergencyReserveSuite extends munit.FunSuite:
  private val MiB = 1L << 20
  // A process killed: nothing of it runs on (not caught as a failure, Try included).
  private final class Killed(at: String) extends scala.util.control.ControlThrowable(s"killed at $at")
  private def killed(body: => Any): Unit =
    try
      body
      fail("not killed")
    catch case _: Killed => ()

  /** A file system: the free space, the reserve file, the allocation file and the emergency mark. */
  private final class Disk(var free0: Long):
    var reserve: Option[Long] = None
    var tmp: Option[Long] = None
    var marker = false
    var releaseFails = false
    var removeFails = false
    var allocFails = false
    var markFails = false
    var unmarkFails = false
    var killAt: Option[String] = None
    var onAllocate: Long => Unit = _ => ()
    var onSync: () => Unit = () => ()
    def kill(p: String): Unit = if killAt.contains(p) then throw Killed(p)
    val files: Files = new Files:
      def free(): Long = free0
      def onDisk(): OnDisk = reserve match
        case None => OnDisk.Absent
        case Some(n) if n >= R => OnDisk.Full
        case _ => OnDisk.Released
      def discardTmp(): Unit =
        tmp.foreach(free0 += _)
        tmp = None
      def allocateTmp(from: Long, n: Long): Unit =
        onAllocate(from)
        if allocFails then throw java.io.IOException("ENOSPC (No space left on device)")
        tmp = Some(tmp.getOrElse(0L) + n)
        free0 -= n
        kill("allocate")
      def syncTmp(): Unit =
        onSync()
        kill("sync")
      def publish(): Unit =
        kill("publish-before")
        reserve.foreach(free0 += _)
        reserve = tmp
        tmp = None
        kill("publish-after")
      def release(): Long =
        kill("release-before")
        if releaseFails then throw java.io.IOException("EIO")
        reserve.foreach(free0 += _)
        reserve = Some(0L)
        kill("release-after")
        0L
      def remove(): Boolean =
        if removeFails then false
        else
          reserve.foreach(free0 += _)
          reserve = None
          kill("remove-after")
          true
      def mark(): Unit =
        kill("mark-before")
        if markFails then throw java.io.IOException("ENOSPC (No space left on device)")
        marker = true
        kill("mark-after")
      def marked: Boolean = marker
      def unmark(): Unit =
        kill("unmark-before")
        if unmarkFails then throw java.io.IOException("EIO")
        marker = false

  private final class World(disk: Disk):
    var now = 0L
    var stops = 0
    val events = scala.collection.mutable.ArrayBuffer.empty[Event]
    val res = EmergencyReserve(disk.files, () => now, () => stops += 1, events += _)
    /** One tick, and the allocation if it gave a permit. */
    def step(logging: Boolean): Boolean = res.tick(logging).exists(res.allocate)

  private val Plenty = ResumeFree + 10 * MiB

  private def created(free: Long = Plenty): (Disk, World) =
    val d = Disk(free)
    val w = World(d)
    assert(w.step(logging = true))
    (d, w)

  test("R is allocated whole, synced and only then published; large work waits for it") {
    val d = Disk(Plenty)
    val w = World(d)
    assert(!w.res.largeWorkAllowed)
    assertEquals(w.res.status, Status.None)
    var syncedWith = Option.empty[Long]
    d.onSync = () => syncedWith = d.tmp
    assert(w.step(logging = true))
    assertEquals(syncedWith, Some(R))
    assertEquals(d.reserve, Some(R))
    assertEquals(d.tmp, None)
    assertEquals(d.free0, Plenty - R)
    assert(w.res.largeWorkAllowed)
    assertEquals(w.res.status, Status.Ready)
    assert(w.events.contains(Event.Created(0)))
  }

  test("R is created the first time from R + E + 16 MiB, not below; logging goes to SQLite meanwhile (予備なし)") {
    val d = Disk(CreateFree - 1)
    val w = World(d)
    assertEquals(w.res.tick(logging = true), None)
    assertEquals(w.res.status, Status.None)
    assertEquals(w.res.route(), Route.Sqlite(probe = false))
    assert(!w.res.largeWorkAllowed)
    d.free0 = CreateFree
    assert(w.step(logging = true))
    assert(w.res.largeWorkAllowed)
    assertEquals(d.free0, CreateFree - R)
  }

  test("the free space falling below E plus a step while R is created cancels it, without entering the emergency state") {
    val d = Disk(CreateFree)
    val w = World(d)
    d.onAllocate = from => if from == 2 * Step then d.free0 = E + Step - 1
    assert(!w.step(logging = true))
    assertEquals(d.tmp, None)
    assertEquals(d.reserve, None)
    assert(!w.res.active)
  }

  test("F below E before a write: the mark first, large work stopped once, R released (its amount), batches to overflow") {
    val (d, w) = created()
    d.free0 = E - 1
    d.killAt = None
    val r0 = d.reserve
    assertEquals(w.res.route(), Route.Overflow)
    assert(d.marker)
    assertEquals(w.stops, 1)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.free0, E - 1 + R)
    assert(!w.res.largeWorkAllowed)
    assertEquals(w.res.status, Status.Released)
    w.events.toSeq.collect { case e: Event.Entered => e } match
      case Seq(e: Event.Entered) =>
        assertEquals(e.reason, Reason.LowSpace)
        assertEquals(e.released, Some(R))
        assertEquals(e.failure, None)
      case other => fail(s"$other")
    // Renewed while in it: not stopped or released again.
    assertEquals(w.res.route(), Route.Overflow)
    assertEquals(w.stops, 1)
  }

  test("SQLITE_FULL, an overflow ENOSPC or a report enters it the same way") {
    for reason <- Seq(Reason.SqliteFull, Reason.OverflowFull, Reason.Reported) do
      val (d, w) = created()
      w.res.enter(reason, "x")
      assert(d.marker)
      assertEquals(d.reserve, Some(0L))
      assertEquals(w.res.route(), Route.Overflow)
  }

  test("a release that fails removes R instead; both failing leave R whole, and the mark still restores the state, R released then") {
    val (d, w) = created()
    d.releaseFails = true
    w.res.enter(Reason.SqliteFull)
    assertEquals(d.reserve, None)
    assert(d.marker)
    assert(World(d).res.active)
    val (d2, w2) = created()
    d2.releaseFails = true
    d2.removeFails = true
    w2.res.enter(Reason.SqliteFull)
    assertEquals(d2.reserve, Some(R))
    assertEquals(w2.events.toSeq.collect { case e: Event.Entered => (e.released, e.failure) },
      Seq[(Option[Long], Option[String])]((Some(0L), Some("解放に失敗: EIO、削除にも失敗"))))
    assertEquals(w2.res.route(), Route.Overflow)
    assert(!w2.res.largeWorkAllowed)
    // A restart: the mark restores it, and R (whole) is released now.
    d2.releaseFails = false
    d2.removeFails = false
    val w3 = World(d2)
    assert(w3.res.active)
    assertEquals(d2.reserve, Some(0L))
    assert(!w3.res.largeWorkAllowed)
  }

  test("a mark that cannot be written for want of room is written with what R gave back; a released R alone restores it too") {
    val (d, w) = created()
    d.markFails = true
    d.free0 = E - 1
    w.res.route()
    assert(!d.marker)
    assertEquals(d.reserve, Some(0L))
    assert(w.events.toSeq.collect { case e: Event.Entered => e.failure }.flatten.head.startsWith("印を書けず"))
    // A restart: the truncated R restores it, and the mark is written then.
    d.markFails = false
    val w2 = World(d)
    assert(w2.res.active)
    assert(d.marker)
  }

  test("without R (never created) a shortage still enters, and the mark restores it after a restart") {
    val d = Disk(E - 1)
    val w = World(d)
    assertEquals(w.res.route(), Route.Overflow)
    assert(d.marker)
    d.free0 = Plenty
    val w2 = World(d)
    assert(w2.res.active)
  }

  test("a restart alone never leaves the emergency state; a full R without the mark is ready; a left allocation file is removed") {
    val (d, w) = created()
    w.res.enter(Reason.SqliteFull)
    d.free0 = Plenty * 4
    val w2 = World(d)
    assert(w2.res.active)
    assertEquals(w2.res.route(), Route.Overflow)
    val d3 = Disk(Plenty)
    d3.reserve = Some(R)
    d3.tmp = Some(7 * MiB)
    val w3 = World(d3)
    assert(w3.res.largeWorkAllowed)
    assertEquals(d3.tmp, None)
  }

  /** In the emergency state with `free` free at time 0. */
  private def inEmergency(free: Long): (Disk, World) =
    val (d, w) = created()
    w.res.enter(Reason.SqliteFull)
    d.free0 = free
    (d, w)

  test("recovery: a momentary increase does not resume; 60 s sustained, one batch in SQLite, then R again and the mark removed") {
    val (d, w) = inEmergency(Plenty)
    assertEquals(w.res.tick(logging = true), None)
    w.now = 30_000
    d.free0 = ResumeFree - 1
    assertEquals(w.res.tick(logging = true), None)
    // Up again at 40 s: the clock starts over.
    w.now = 40_000
    d.free0 = Plenty
    assertEquals(w.res.tick(logging = true), None)
    w.now = 99_000
    assertEquals(w.res.tick(logging = true), None)
    assertEquals(w.res.route(), Route.Overflow)
    w.now = 100_000
    // Sustained: the next batch tries SQLite (one only), still in the state.
    assertEquals(w.res.tick(logging = true), None)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
    assertEquals(w.res.route(), Route.Overflow)
    assertEquals(w.res.tick(logging = true), None)
    w.res.probeSaved()
    assert(w.res.active)
    assert(d.marker)
    assert(w.step(logging = true))
    assert(!w.res.active)
    assert(w.res.largeWorkAllowed)
    assertEquals(d.reserve, Some(R))
    assert(!d.marker)
    assertEquals(w.res.route(), Route.Sqlite(probe = false))
    assert(w.events.exists { case _: Event.Recovered => true; case _ => false })
  }

  test("recovery without logging needs no batch") {
    val (d, w) = inEmergency(Plenty)
    assert(!w.step(logging = false))
    w.now = 60_000
    assert(w.step(logging = false))
    assert(!w.res.active)
  }

  test("a re-entry right after the recovery decision voids its permit: no return to normal without 60 s and a batch again") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = true)
    w.now = 60_000
    w.res.tick(logging = true)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
    w.res.probeSaved()
    val p = w.res.tick(logging = true)
    assert(p.isDefined)
    // Between the decision and the allocation: a new shortage.
    w.res.enter(Reason.OverflowFull)
    assert(!w.res.allocate(p.get))
    assert(w.res.active)
    assert(d.marker)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.tmp, None)
    // The clock and the batch again.
    w.now = 61_000
    assertEquals(w.res.tick(logging = true), None)
    w.now = 121_000
    assertEquals(w.res.tick(logging = true), None)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
  }

  test("the probe batch failing with SQLITE_FULL renews the state and the clock") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = true)
    w.now = 60_000
    w.res.tick(logging = true)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
    w.res.enter(Reason.SqliteFull)
    w.now = 61_000
    assertEquals(w.res.tick(logging = true), None)
    assertEquals(w.res.route(), Route.Overflow)
    w.now = 121_000
    w.res.tick(logging = true)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
  }

  /** In the emergency state, 60 s sustained, not logging: the permit to allocate. */
  private def permitted(): (Disk, World, Permit) =
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = false)
    w.now = 60_000
    val p = w.res.tick(logging = false)
    assert(p.isDefined)
    (d, w, p.get)

  test("re-allocation failure: R not published, nothing left, still in the state, the clock starts over") {
    val (d, w, p) = permitted()
    d.allocFails = true
    assert(!w.res.allocate(p))
    assert(w.res.active)
    assertEquals(d.tmp, None)
    assertEquals(d.reserve, Some(0L))
    assert(w.events.exists { case _: Event.AllocationFailed => true; case _ => false })
    d.allocFails = false
    w.now = 61_000
    assertEquals(w.res.tick(logging = false), None)
  }

  test("re-allocation racing a new shortage: cancelled between steps, never published") {
    val (d, w, p) = permitted()
    d.onAllocate = from => if from == Step then w.res.enter(Reason.OverflowFull)
    assert(!w.res.allocate(p))
    assert(w.res.active)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.tmp, None)
    assert(w.events.exists { case _: Event.AllocationCancelled => true; case _ => false })
  }

  test("a shortage after the sync and before the publication: the stale allocation does not publish") {
    val (d, w, p) = permitted()
    d.onSync = () => w.res.enter(Reason.Reported)
    assert(!w.res.allocate(p))
    assert(w.res.active)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.tmp, None)
  }

  test("the free space falling below E plus a step while R is allocated again cancels it") {
    val (d, w, p) = permitted()
    d.onAllocate = from => if from == 2 * Step then d.free0 = E
    assert(!w.res.allocate(p))
    assert(w.res.active)
    assertEquals(d.tmp, None)
  }

  test("a mark that cannot be removed after recovery is removed later (tidy); until then a restart restores the emergency, the safe side") {
    val (d, w, p) = permitted()
    d.unmarkFails = true
    assert(w.res.allocate(p))
    assert(!w.res.active)
    assert(d.marker)
    assert(World(d).res.active)
    // (The restarted world released R again.) In this process: removed at the next tidy.
    val (d2, w2, p2) = permitted()
    d2.unmarkFails = true
    assert(w2.res.allocate(p2))
    d2.unmarkFails = false
    w2.res.tidy()
    assert(!d2.marker)
  }

  test("killed at each boundary of creation, publication, entry and release: the restart decides from the files") {
    def create(d: Disk, w: World): Unit = w.step(logging = true)
    // Creation: allocated in part or whole, not renamed: never ready, nothing left.
    for at <- Seq("allocate", "sync", "publish-before") do
      val d = Disk(Plenty)
      d.killAt = Some(at)
      killed(create(d, World(d)))
      d.killAt = None
      val w2 = World(d)
      assertEquals(w2.res.status, Status.None, at)
      assertEquals(d.tmp, None, at)
      assertEquals(d.free0, Plenty, at)
    // Renamed: complete (allocated and synced before), ready.
    locally {
      val d = Disk(Plenty)
      d.killAt = Some("publish-after")
      killed(create(d, World(d)))
      d.killAt = None
      assert(World(d).res.largeWorkAllowed)
      assertEquals(d.reserve, Some(R))
    }
    // Entry: killed after the mark, after the truncation, after the removal
    // (the release failed): the restart restores the emergency in each case.
    for (at, releaseFails) <- Seq(("mark-after", false), ("release-before", false), ("release-after", false), ("remove-after", true)) do
      val d = Disk(Plenty)
      create(d, World(d))
      val w = World(d)
      d.free0 = E - 1
      d.releaseFails = releaseFails
      d.killAt = Some(at)
      killed(w.res.route())
      d.killAt = None
      d.releaseFails = false
      d.free0 = Plenty
      val w2 = World(d)
      assert(w2.res.active, at)
      assertEquals(w2.res.route(), Route.Overflow, at)
      assert(d.reserve.forall(_ == 0L), s"$at: R released after the restart")
    // Killed before the mark: nothing changed on disk, R whole and ready; the
    // first write sees F < E and enters.
    locally {
      val d = Disk(Plenty)
      create(d, World(d))
      val w = World(d)
      d.free0 = E - 1
      d.killAt = Some("mark-before")
      killed(w.res.route())
      d.killAt = None
      val w2 = World(d)
      assert(w2.res.largeWorkAllowed)
      assertEquals(w2.res.route(), Route.Overflow)
      assert(d.marker)
    }
    // Re-allocation killed before its publication, or after it before the
    // mark is removed: still in the emergency after the restart.
    for at <- Seq("allocate", "sync", "publish-before", "unmark-before") do
      val (d, w, p) = permitted()
      d.killAt = Some(at)
      killed(w.res.allocate(p))
      d.killAt = None
      val w2 = World(d)
      assert(w2.res.active, at)
      assertEquals(d.tmp, None, at)
  }

  // --- the way of one batch ----------------------------------------------------------------

  /** SQLite (committed batch ids), overflow files (batch ids) and the lost ones. */
  private final class Store(val disk: Disk):
    val db = scala.collection.mutable.ArrayBuffer.empty[Int]
    val overflow = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    val lost = scala.collection.mutable.ArrayBuffer.empty[Int]
    // What a batch's SQLite write takes.
    var sqliteNeeds = 2 * MiB
    var spillNeeds = 1 * MiB

    final class Full extends RuntimeException("SQLITE_FULL")
    final class NoSpace extends java.io.IOException("ENOSPC")

    def ops(id: Int): EmergencyPath.Ops = new EmergencyPath.Ops:
      def commit(): Unit =
        // Applied only as a whole: a failure in an INSERT or at COMMIT
        // leaves nothing (Db.tx; checked on the device for both).
        if disk.free0 < sqliteNeeds then throw Full()
        disk.free0 -= sqliteNeeds
        db += id
      def spill(): Unit =
        val name = s"raw-$id.txt"
        if !overflow.contains(name) then
          if disk.free0 < spillNeeds then throw NoSpace()
          disk.free0 -= spillNeeds
          overflow(name) = id
      def lost(why: String): Unit = Store.this.lost += id
      def full(e: Throwable): Boolean = e.isInstanceOf[Full]
      def noSpace(e: Throwable): Boolean = e.isInstanceOf[NoSpace]
      def describe(e: Throwable): String = e.getMessage

    /** Imports the overflow files (each once, then deleted). */
    def importAll(): Unit =
      for (name, id) <- overflow.toSeq do
        db += id
        overflow.remove(name)

    def check(ids: Range): Unit =
      val all = db.toSeq ++ overflow.values ++ lost
      assertEquals(all.sorted, ids.toSeq, "every batch exactly once in SQLite, overflow or lost")

  test("the disk fills (SQLITE_FULL): batches go to overflow and are imported after recovery, none lost or twice") {
      val (d, w) = created(ResumeFree + 40 * MiB)
      val s = Store(d)
      // 200 batches of 100 KiB fit in what R gives back.
      s.spillNeeds = 100 * 1024
      val outcomes = (1 to 200).map { id =>
        // Another app takes room meanwhile.
        if id == 20 then d.free0 = 3 * MiB
        EmergencyPath.persist(w.res, s.ops(id))
      }
      assert(w.res.active)
      assert(outcomes.contains(EmergencyPath.Outcome.Spilled))
      // The batch that met SQLITE_FULL went to overflow (not retried in SQLite).
      assert(s.overflow.values.toSeq.contains(20) || s.overflow.values.toSeq.min <= 20)
      // Space comes back: 60 s, a batch in SQLite, R again; then the import.
      d.free0 = Plenty
      w.res.tick(logging = true)
      w.now = 60_000
      w.res.tick(logging = true)
      assertEquals(EmergencyPath.persist(w.res, s.ops(201)), EmergencyPath.Outcome.Saved)
      val p = w.res.tick(logging = true)
      assert(p.isDefined)
      assert(w.res.allocate(p.get))
      assertEquals(EmergencyPath.persist(w.res, s.ops(202)), EmergencyPath.Outcome.Saved)
      // New records first: 201 and 202 are in before any imported batch.
      val before = s.db.toSeq
      s.importAll()
      assertEquals(s.db.toSeq.take(before.size), before)
      assert(s.lost.isEmpty)
      s.check(1 to 202)
    }

  test("nothing can be saved: SQLite and overflow both full after R is released; each batch counted lost once") {
    val (d, w) = created(ResumeFree)
    val s = Store(d)
    d.free0 = 0
    s.spillNeeds = R + MiB
    val outcomes = (1 to 10).map(id => EmergencyPath.persist(w.res, s.ops(id)))
    assertEquals(outcomes.toSet, Set(EmergencyPath.Outcome.Lost))
    assertEquals(d.reserve, Some(0L))
    s.check(1 to 10)
    assertEquals(s.lost.toSeq, 1 to 10)
  }

  test("an overflow write failing for lack of space (queue full, SQLite fine) enters the state, releases R and retries once") {
    val (d, w) = created(ResumeFree)
    val s = Store(d)
    d.free0 = E + MiB / 2
    s.spillNeeds = E + MiB
    assertEquals(EmergencyPath.spill(w.res, s.ops(1), "書き込み待ちが上限"), EmergencyPath.Outcome.Spilled)
    assert(w.res.active)
    s.check(1 to 1)
  }
