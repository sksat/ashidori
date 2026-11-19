package ashidori.core

import Emergency.*

class EmergencyReserveSuite extends munit.FunSuite:
  private val MiB = 1L << 20
  private final class Killed(at: String) extends Error(s"killed at $at")

  /** A file system: the free space, the reserve file and the allocation file. */
  private final class Disk(var free0: Long):
    var reserve: Option[Long] = None
    var tmp: Option[Long] = None
    var releaseFails = false
    var removeFails = false
    var allocFails = false
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
          true
      def markReleased(): Unit = if reserve.isEmpty then reserve = Some(0L)

  private final class World(disk: Disk):
    var now = 0L
    var stops = 0
    val events = scala.collection.mutable.ArrayBuffer.empty[Event]
    val res = EmergencyReserve(disk.files, () => now, () => stops += 1, events += _)

  private val Plenty = ResumeFree + 10 * MiB

  private def created(free: Long = Plenty): (Disk, World) =
    val d = Disk(free)
    val w = World(d)
    assert(w.res.tick(logging = true))
    assert(w.res.allocate())
    (d, w)

  test("R is allocated whole, synced and only then published; large work waits for it") {
    val d = Disk(Plenty)
    val w = World(d)
    assert(!w.res.largeWorkAllowed)
    assertEquals(w.res.status, Status.None)
    var syncedWith = Option.empty[Long]
    d.onSync = () => syncedWith = d.tmp
    assert(w.res.tick(logging = true))
    assert(w.res.allocate())
    assertEquals(syncedWith, Some(R))
    assertEquals(d.reserve, Some(R))
    assertEquals(d.tmp, None)
    assertEquals(d.free0, Plenty - R)
    assert(w.res.largeWorkAllowed)
    assertEquals(w.res.status, Status.Ready)
    assert(w.events.contains(Event.Created(0)))
  }

  test("R is not created below H + 2R; logging goes to SQLite meanwhile (予備なし)") {
    val d = Disk(ResumeFree - 1)
    val w = World(d)
    assert(!w.res.tick(logging = true))
    assertEquals(w.res.status, Status.None)
    assertEquals(w.res.route(), Route.Sqlite(probe = false))
    assert(!w.res.largeWorkAllowed)
  }

  test("F below E before a write: large work stopped once, R released (its amount), batches to overflow") {
    val (d, w) = created()
    d.free0 = E - 1
    assertEquals(w.res.route(), Route.Overflow)
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

  test("SQLITE_FULL or an overflow ENOSPC enters it the same way") {
    for reason <- Seq(Reason.SqliteFull, Reason.OverflowFull, Reason.Reported) do
      val (d, w) = created()
      w.res.enter(reason, "x")
      assertEquals(d.reserve, Some(0L))
      assertEquals(w.res.route(), Route.Overflow)
  }

  test("a release that fails removes the file instead; both failing is reported, and the state is entered anyway") {
    val (d, w) = created()
    d.releaseFails = true
    w.res.enter(Reason.SqliteFull)
    // Removed, and an empty file left as the mark: still released after a restart.
    assertEquals(d.reserve, Some(0L))
    assert(World(d).res.active)
    assertEquals(w.events.toSeq.collect { case e: Event.Entered => (e.released, e.failure) }, Seq[(Option[Long], Option[String])]((Some(R), Some("EIO"))))
    val (d2, w2) = created()
    d2.releaseFails = true
    d2.removeFails = true
    w2.res.enter(Reason.SqliteFull)
    assertEquals(d2.reserve, Some(R))
    assertEquals(w2.events.toSeq.collect { case e: Event.Entered => (e.released, e.failure) }, Seq[(Option[Long], Option[String])]((Some(0L), Some("EIO、削除にも失敗"))))
    assertEquals(w2.res.route(), Route.Overflow)
    assert(!w2.res.largeWorkAllowed)
  }

  test("without R (never created) a shortage still enters, and leaves an empty file as its mark") {
    val d = Disk(E - 1)
    val w = World(d)
    assertEquals(w.res.route(), Route.Overflow)
    assertEquals(d.reserve, Some(0L))
    // A restart: still in it (decided from the file).
    d.free0 = Plenty
    val w2 = World(d)
    assert(w2.res.active)
  }

  test("a restart alone never leaves the emergency state; a full R is ready; a left allocation file is removed") {
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

  test("recovery: a momentary increase does not resume; 60 s sustained, one batch in SQLite, then R again") {
    val (d, w) = inEmergency(Plenty)
    assert(!w.res.tick(logging = true))
    w.now = 30_000
    d.free0 = ResumeFree - 1
    assert(!w.res.tick(logging = true))
    // Up again at 40 s: the clock starts over.
    w.now = 40_000
    d.free0 = Plenty
    assert(!w.res.tick(logging = true))
    w.now = 99_000
    assert(!w.res.tick(logging = true))
    assertEquals(w.res.route(), Route.Overflow)
    w.now = 100_000
    // Sustained: the next batch tries SQLite (one only), still in the state.
    assert(!w.res.tick(logging = true))
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
    assertEquals(w.res.route(), Route.Overflow)
    assert(!w.res.tick(logging = true))
    w.res.probeSaved()
    assert(w.res.active)
    assert(w.res.tick(logging = true))
    assert(w.res.allocate())
    assert(!w.res.active)
    assert(w.res.largeWorkAllowed)
    assertEquals(d.reserve, Some(R))
    assertEquals(w.res.route(), Route.Sqlite(probe = false))
    assert(w.events.exists { case _: Event.Recovered => true; case _ => false })
  }

  test("recovery without logging needs no batch") {
    val (d, w) = inEmergency(Plenty)
    assert(!w.res.tick(logging = false))
    w.now = 60_000
    assert(w.res.tick(logging = false))
    assert(w.res.allocate())
    assert(!w.res.active)
  }

  test("the probe batch failing with SQLITE_FULL renews the state and the clock") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = true)
    w.now = 60_000
    w.res.tick(logging = true)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
    w.res.enter(Reason.SqliteFull)
    w.now = 61_000
    assert(!w.res.tick(logging = true))
    assertEquals(w.res.route(), Route.Overflow)
    w.now = 121_000
    w.res.tick(logging = true)
    assertEquals(w.res.route(), Route.Sqlite(probe = true))
  }

  test("re-allocation failure: R not published, nothing left, still in the state, the clock starts over") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = false)
    w.now = 60_000
    assert(w.res.tick(logging = false))
    d.allocFails = true
    assert(!w.res.allocate())
    assert(w.res.active)
    assertEquals(d.tmp, None)
    assertEquals(d.reserve, Some(0L))
    assert(w.events.exists { case _: Event.AllocationFailed => true; case _ => false })
    d.allocFails = false
    w.now = 61_000
    assert(!w.res.tick(logging = false))
  }

  test("re-allocation racing a new shortage: cancelled between steps, never published") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = false)
    w.now = 60_000
    assert(w.res.tick(logging = false))
    // The logger sees a shortage while the second step is allocated.
    d.onAllocate = from => if from == Step then w.res.enter(Reason.OverflowFull)
    assert(!w.res.allocate())
    assert(w.res.active)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.tmp, None)
    assert(w.events.exists { case _: Event.AllocationCancelled => true; case _ => false })
  }

  test("a shortage after the sync and before the publication: the stale allocation does not publish") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = false)
    w.now = 60_000
    assert(w.res.tick(logging = false))
    d.onSync = () => w.res.enter(Reason.Reported)
    assert(!w.res.allocate())
    assert(w.res.active)
    assertEquals(d.reserve, Some(0L))
    assertEquals(d.tmp, None)
  }

  test("the free space falling below E while R is allocated cancels it (a new shortage)") {
    val (d, w) = inEmergency(Plenty)
    w.res.tick(logging = false)
    w.now = 60_000
    assert(w.res.tick(logging = false))
    d.onAllocate = from => if from == 2 * Step then d.free0 = E
    assert(!w.res.allocate())
    assert(w.res.active)
    assertEquals(d.tmp, None)
  }

  test("killed at each boundary of creation, publication and release: the restart decides from the files") {
    def afterKill(at: String)(run: (Disk, World) => Unit): (Disk, World) =
      val d = Disk(Plenty)
      val w = World(d)
      d.killAt = Some(at)
      intercept[Killed](run(d, w))
      d.killAt = None
      (d, World(d))
    def create(d: Disk, w: World): Unit = { w.res.tick(logging = true); w.res.allocate() }
    // Creation: allocated in part or whole, not renamed: never ready, nothing left.
    for at <- Seq("allocate", "sync", "publish-before") do
      val (d, w2) = afterKill(at)(create)
      assertEquals(w2.res.status, Status.None, at)
      assertEquals(d.tmp, None, at)
      assertEquals(d.free0, Plenty, at)
    // Renamed: complete (allocated and synced before), ready.
    locally {
      val (d, w2) = afterKill("publish-after")(create)
      assert(w2.res.largeWorkAllowed)
      assertEquals(d.reserve, Some(R))
    }
    // Release: not truncated yet, ready again; the first write sees F < E and enters.
    locally {
      val d = Disk(Plenty)
      val w = World(d)
      create(d, w)
      d.free0 = E - 1
      d.killAt = Some("release-before")
      intercept[Killed](w.res.route())
      d.killAt = None
      val w2 = World(d)
      assert(w2.res.largeWorkAllowed)
      assertEquals(w2.res.route(), Route.Overflow)
      assertEquals(d.reserve, Some(0L))
    }
    // Truncated: the released state survives the restart.
    locally {
      val d = Disk(Plenty)
      val w = World(d)
      create(d, w)
      d.free0 = E - 1
      d.killAt = Some("release-after")
      intercept[Killed](w.res.route())
      d.killAt = None
      d.free0 = Plenty
      val w2 = World(d)
      assert(w2.res.active)
      assertEquals(w2.res.route(), Route.Overflow)
    }
    // Re-allocation killed before its publication: still released after the restart.
    for at <- Seq("allocate", "sync", "publish-before") do
      val (d, w) = inEmergency(Plenty)
      w.res.tick(logging = false)
      w.now = 60_000
      assert(w.res.tick(logging = false))
      d.killAt = Some(at)
      intercept[Killed](w.res.allocate())
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
      assert(w.res.tick(logging = true))
      assert(w.res.allocate())
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
