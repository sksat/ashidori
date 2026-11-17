package ashidori.core

import java.io.{BufferedOutputStream, File, FileOutputStream, RandomAccessFile}
import scala.collection.mutable
import scala.util.Using
import ashidori.core.ArchiveMerge.Row
import ashidori.core.CommitIntent.*
import ashidori.core.CommitRepair.*
import ashidori.core.parquet.*

class CommitRepairSuite extends munit.FunSuite:
  private def r(t: Long, v: String): Row = IndexedSeq(t, v)

  // --- a commit over three tables, in memory ------------------------------------------------

  /** A file: its rows, what its footer says and its size; `decodeFails`:
    * the footer reads but a row group does not.
    */
  final case class FakeFile(rows: Vector[Row], footer: Long, size: Long, decodeFails: Boolean = false)
  private def fileOf(rows: Vector[Row]) = FakeFile(rows, rows.size, rows.size * 10L + 7)

  final class Crash extends RuntimeException("crash")

  /** The archive files, tmp files, quarantine, SQLite and the commit record.
    * Files are only ever moved between the maps (rule A): [[files]] counts
    * them.
    */
  final class World(tables: Seq[String], old: Map[String, Vector[Row]], chunk: Map[String, Vector[Row]], cap: Int = RowBag.Cap,
                    delete: Delete = Delete.Raw(0, 10_000, 99, ArchiveMerge.Cursor(0, 0), ArchiveMerge.Cursor(5_000, 50), Nil)):
    def path(t: String) = s"$t/2026-11-10.parquet"
    val dest = mutable.Map.empty[String, FakeFile]
    val tmp = mutable.Map.empty[String, FakeFile]
    val quarantine = mutable.Map.empty[String, FakeFile]
    val quarantineTmp = mutable.Map.empty[String, FakeFile]
    val attention = mutable.LinkedHashMap.empty[String, Boolean]
    // SQLite: the chunk's rows and some of later portions, per table.
    val sqlite = mutable.Map.from(tables.map(t => t -> (chunk(t) :+ r(9_000, s"$t-later"))))
    val log = mutable.ArrayBuffer.empty[String]
    var crashAt = -1
    private var ops = 0
    for t <- tables if old(t).nonEmpty do dest(path(t)) = fileOf(old(t))

    private def newRows(t: String) = (old(t) ++ chunk(t)).sortBy(ArchiveMerge.key)
    val intent: CommitIntent = CommitIntent(tables.map { t =>
      val f = fileOf(newRows(t))
      val s = ChunkSampler(max = 2)
      chunk(t).foreach(s.add)
      val prev = if old(t).isEmpty then Prev.Absent else Prev.File(old(t).size, fileOf(old(t)).size)
      Target(path(t), f.footer, f.size, Some(s.check(prev, old(t).size, chunk(t).size,
        Some((newRows(t).map(ArchiveMerge.key).min, newRows(t).map(ArchiveMerge.key).max)))))
    }, delete)
    var record: Option[CommitIntent] = Some(intent)
    for t <- tables do tmp(path(t)) = fileOf(newRows(t))

    /** Every file there is, wherever it is. */
    def files: Int = dest.size + tmp.size + quarantine.size + quarantineTmp.size

    /** The commit renamed these tables' files, then stopped. */
    def renamed(ts: String*): Unit = for t <- ts do dest(path(t)) = tmp.remove(path(t)).get

    private def step(): Unit =
      ops += 1
      if ops == crashAt then throw Crash()

    private def iter(f: FakeFile) =
      if f.decodeFails then f.rows.iterator.take(1) ++ Iterator(throw java.io.IOException("bad page")) else f.rows.iterator

    /** The steps taken, in order (begin, moves, deletions, clear). */
    val trail = mutable.ArrayBuffer.empty[String]

    val opsImpl: Ops = new Ops:
      def dest(p: String) = World.this.dest.get(p).map(f => (f.footer, f.size))
      def tmpExists(p: String) = tmp.contains(p)
      def quarantined(p: String) = World.this.quarantine.contains(p)
      def verify(t: Target) = World.this.dest.get(t.path) match
        case None => Left("なし")
        case Some(f) =>
          // The chunk's rows still in SQLite, every one to be found.
          val table = CommitRepair.tableOf(t.path)
          scala.util.Try {
            val idx = RowBag(cap)
            sqlite(table).filter(chunk(table).contains).foreach(idx.add)
            idx
          }.toEither.left.map(_ => CommitRepair.TooLargeMark).flatMap(idx => CommitRepair.verify(t, f.footer, iter(f), Some(idx)))
      def verifyPrevious(t: Target) = World.this.dest.get(t.path) match
        case None => Right(())
        case Some(f) => CommitRepair.verifyWhole(f.footer, iter(f), t.check.map(_.prev).collect { case Prev.File(r, _) => r })
      def begin() =
        step()
        if !record.exists(_.repairing) then trail += "begin"
        record = record.map(_.copy(repairing = true))
      def markVerified(paths: Set[String]) =
        step()
        record = record.map(r => r.copy(verified = r.verified ++ paths))
      def setAsideTmp(t: Target, why: String, alert: Boolean) =
        if tmp.contains(t.path) then
          step()
          attention.getOrElseUpdate(t.path + ".tmp", alert)
          step()
          tmp.remove(t.path).foreach(quarantineTmp(t.path) = _)
          trail += s"tmp ${t.path}"
      def quarantine(t: Target, why: String, onlyCopy: Boolean) =
        setAsideTmp(t, why, alert = true)
        if World.this.dest.contains(t.path) then
          step()
          attention.getOrElseUpdate(t.path, onlyCopy)
          step()
          World.this.dest.remove(t.path).foreach(World.this.quarantine(t.path) = _)
          trail += s"file ${t.path}"
      def sync(paths: Seq[String]) = step()
      def deleteRows(ts: Set[String]) =
        // Table by table (a crash may come between them).
        var n = 0L
        for t <- ts.toSeq.sorted do
          step()
          val before = sqlite(t).size
          sqlite(t) = sqlite(t).filterNot(chunk(t).contains)
          n += before - sqlite(t).size
          trail += s"rows $t"
        n
      def clearIntent() =
        step()
        record = None
        trail += "clear"
      def log(line: String) = World.this.log += line

    /** Recovery of the record on disk, as the app does it: every case
      * through the repair (nothing if there is no record).
      */
    def repair(manual: Boolean): Option[Result] = record.map { i =>
      val disk = i.files.map(t => t.path -> OnDisk(dest.get(t.path).map(f => (f.footer, f.size)),
        tmp.get(t.path).map(f => (f.footer, f.size)))).toMap
      resume(i, disk) match
        case Recovery.Finish(_) | Recovery.Drop => CommitRepair.run(i, opsImpl, manual, recovering = true)
        case Recovery.Stuck => CommitRepair.run(i, opsImpl, manual)
    }

    /** What 0.21.0 would do with the record now (its recovery, as it was). */
    def as021: Option[Recovery] = record.map { i =>
      CommitIntent.recovery(i, i.files.map(t => t.path -> OnDisk(dest.get(t.path).map(f => (f.footer, f.size)),
        tmp.get(t.path).map(f => (f.footer, f.size)))).toMap)
    }

    /** Each table's rows: in its archive file and in SQLite. */
    def rows(t: String): Vector[Row] = dest.get(path(t)).map(_.rows).getOrElse(Vector.empty) ++ sqlite(t)

  private val tables = Seq("gnss_raw", "imu", "nmea")
  private val old = Map(
    "gnss_raw" -> Vector(r(10, "g0"), r(20, "g1")),
    "imu" -> Vector(r(11, "i0")),
    "nmea" -> Vector.empty[Row])
  private val chunk = Map(
    "gnss_raw" -> (100L until 140L).map(t => r(t, s"g$t")).toVector,
    "imu" -> (100L until 160L).map(t => r(t, s"i$t")).toVector,
    "nmea" -> (100L until 120L).map(t => r(t, s"n$t")).toVector)

  /** Every row of every table, between its file, SQLite and quarantine. */
  private def assertComplete(w: World): Unit =
    for t <- tables do
      val all = w.rows(t) ++ w.quarantine.get(w.path(t)).map(_.rows).getOrElse(Vector.empty)
      val expected = (old(t) ++ chunk(t) :+ r(9_000, s"$t-later")).sortBy(ArchiveMerge.key)
      assertEquals(all.distinct.sortBy(ArchiveMerge.key), expected, t)

  /** No row both in a table's file and in SQLite. */
  private def assertNoDuplicates(w: World): Unit =
    for t <- tables do
      val inFile = w.dest.get(w.path(t)).map(_.rows.toSet).getOrElse(Set.empty)
      assertEquals(w.sqlite(t).filter(inFile), Vector.empty, t)

  /** Rule A: no data file is gone (moved only), and no tmp file is left
    * where a commit could publish it.
    */
  private def assertNothingDeleted(w: World, files: Int): Unit =
    assertEquals(w.files, files)

  test("all renamed: every table verifies, its chunk leaves SQLite, the record goes") {
    val w = World(tables, old, chunk)
    w.renamed(tables*)
    val files = w.files
    val res = w.repair(manual = false).get
    assert(res.isInstanceOf[Result.Resolved], res)
    assertEquals(w.record, None)
    for t <- tables do
      assertEquals(w.sqlite(t), Vector(r(9_000, s"$t-later")))
      assertEquals(w.rows(t).size, old(t).size + chunk(t).size + 1)
    assertNoDuplicates(w)
    assertComplete(w)
    assertNothingDeleted(w, files)
    assertEquals(w.trail.head, "begin")
  }

  test("some renamed, the rest's tmp missing or broken: tmp files set aside (never deleted), published tables resolved") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.tmp.remove(w.path("imu"))
    w.tmp(w.path("nmea")) = w.tmp(w.path("nmea")).copy(footer = -1)
    assertEquals(w.as021, Some(Recovery.Stuck))
    val files = w.files
    val steps = w.repair(manual = false).get match
      case Result.Resolved(s) => s
      case other => fail(s"$other")
    assertEquals(steps.map(s => (s.state, s.action)), Seq(
      (State.Published, Action.DeleteRows), (State.NotPublished, Action.Keep), (State.NotPublished, Action.Keep)))
    assertEquals(w.record, None)
    // Rule A: the broken tmp is kept aside, not deleted.
    assertEquals(w.tmp.keySet.toSet, Set.empty[String])
    assert(w.quarantineTmp.contains(w.path("nmea")))
    assertNothingDeleted(w, files)
    // gnss_raw: in its file only; imu, nmea: old file intact, chunk still in SQLite.
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.dest(w.path("imu")).rows, old("imu"))
    assertNoDuplicates(w)
    assertComplete(w)
    // Rule B: the mark before the first move.
    assertEquals(w.trail.take(2).toSeq, Seq("begin", s"tmp ${w.path("nmea")}"))
  }

  test("a commit that would be dropped or finished: its tmp files are set aside, never published nor deleted") {
    // Nothing renamed, every tmp good (0.21.0: finish them all).
    val w = World(tables, old, chunk)
    assert(w.as021.exists(_.isInstanceOf[Recovery.Finish]))
    val files = w.files
    val good = w.tmp.toMap
    assert(w.repair(manual = false).get.isInstanceOf[Result.Resolved])
    assertEquals(w.quarantineTmp.toMap, good)
    // The previous files stay; every row stays in SQLite for the next run.
    for t <- tables do assertEquals(w.sqlite(t).size, chunk(t).size + 1)
    assertEquals(w.dest.get(w.path("imu")).map(_.rows), Some(old("imu")))
    assertNothingDeleted(w, files)
    assertComplete(w)
  }

  test("previous file changed in value only (it reads whole), its good tmp: the tmp is kept, not deleted") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.tmp.remove(w.path("nmea"))
    // imu's previous file: same footer, size and order, other values.
    val f = w.dest(w.path("imu"))
    w.dest(w.path("imu")) = f.copy(rows = f.rows.map(r => IndexedSeq(r(0), "changed")))
    val goodTmp = w.tmp(w.path("imu"))
    val files = w.files
    assert(w.repair(manual = false).get.isInstanceOf[Result.Resolved])
    // The only good copy of imu's earlier rows is kept (in quarantine).
    assertEquals(w.quarantineTmp.get(w.path("imu")), Some(goodTmp))
    assertNothingDeleted(w, files)
  }

  test("a published file that does not verify: never moved automatically; quarantined by hand, rows kept") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw", "imu")
    w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
    w.tmp.remove(w.path("nmea"))
    val files = w.files
    val before = (w.dest.clone(), w.sqlite.clone())
    // Automatically: no data file moved, no row deleted, as often as it is tried.
    for _ <- 1 to 3 do assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    assertEquals((w.dest, w.sqlite), before)
    assert(w.record.exists(_.repairing))
    // By hand.
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(w.record, None)
    assertEquals(w.dest.get(w.path("imu")), None)
    assertEquals(w.quarantine(w.path("imu")).rows, (old("imu") ++ chunk("imu")).sortBy(ArchiveMerge.key))
    assertEquals(w.attention.get(w.path("imu")), Some(true))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    assertNoDuplicates(w)
    assertComplete(w)
    assertNothingDeleted(w, files)
  }

  test("a published file whose rows other than the samples differ: nothing deleted, kept for the user") {
    val w = World(tables, old, chunk)
    w.renamed(tables*)
    val samples = w.intent.files.find(_.path == w.path("imu")).get.check.get.samples.map(_._1).toSet
    val victim = chunk("imu").find(r => !samples(ArchiveMerge.key(r))).get
    val f = w.dest(w.path("imu"))
    w.dest(w.path("imu")) = f.copy(rows = f.rows.map(r => if r == victim then r(0).asInstanceOf[Long] +: IndexedSeq("forged") else r))
    val steps = w.repair(manual = false).get match
      case Result.NeedsUser(s) => s
      case other => fail(s"$other")
    assert(steps.exists(s => s.target.path == w.path("imu") && s.action.isInstanceOf[Action.Quarantine]), steps)
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assert(w.sqlite("imu").contains(victim))
    assert(w.quarantine.contains(w.path("imu")))
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
  }

  test("not published, previous file damaged though its footer reads, tmp good: both kept") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.tmp.remove(w.path("nmea"))
    w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
    val goodTmp = w.tmp(w.path("imu"))
    assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    // The tmp is out of the way at once, kept, and shown to the user.
    assertEquals(w.quarantineTmp.get(w.path("imu")), Some(goodTmp))
    assertEquals(w.attention.get(w.path("imu") + ".tmp"), Some(true))
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assert(w.quarantine.contains(w.path("imu")))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
  }

  test("a file that is neither the previous nor the new version is quarantined, not trusted") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.dest(w.path("imu")) = FakeFile(Vector.empty, -1, 3)
    w.tmp.remove(w.path("imu"))
    val steps = w.repair(manual = false).get match
      case Result.NeedsUser(s) => s
      case other => fail(s"$other")
    assertEquals(steps.map(_.state), Seq(State.Published, State.Unknown, State.NotPublished))
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assert(w.quarantine.contains(w.path("imu")))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
  }

  test("a crash at any step of a repair (the mark, each move, each deletion): the next run ends the same, nothing lost") {
    def scenario(): World =
      val w = World(tables, old, chunk)
      w.renamed("gnss_raw", "imu")
      w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
      w.tmp(w.path("nmea")) = w.tmp(w.path("nmea")).copy(footer = -1)
      w
    val clean = scenario()
    val files = clean.files
    clean.repair(manual = true)
    def snapshot(w: World) = (w.dest.toMap, w.tmp.toMap, w.quarantine.toMap, w.quarantineTmp.toMap, w.sqlite.toMap,
      w.record, w.attention.toMap)
    var k = 1
    var crashed = true
    while crashed do
      val w = scenario()
      w.crashAt = k
      crashed =
        try { w.repair(manual = true); false }
        catch case _: Crash => true
      if crashed then
        assertComplete(w)
        assertNothingDeleted(w, files)
        // Rule B: once anything moved, the record says a repair began.
        if w.quarantineTmp.nonEmpty || w.quarantine.nonEmpty then assert(w.record.exists(_.repairing), s"step $k")
        w.crashAt = -1
        w.repair(manual = false)
        assertComplete(w)
        w.repair(manual = true)
        assertEquals(snapshot(w), snapshot(clean), s"crash at step $k")
        assertNoDuplicates(w)
      k += 1
    assert(k > 8, s"only ${k - 1} steps")
  }

  test("once a repair began, recovery continues it: a quarantined file is not a commit to drop") {
    def scenario() =
      val w = World(tables, old, chunk)
      w.renamed("imu")
      w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
      w.tmp(w.path("gnss_raw")) = w.tmp(w.path("gnss_raw")).copy(footer = -1)
      w
    // Die right after imu's file is moved.
    val k = (1 to 30).find { k =>
      val w = scenario()
      w.crashAt = k
      try { w.repair(manual = true); false }
      catch case _: Crash => w.quarantine.contains(w.path("imu"))
    }.get
    val w = scenario()
    w.crashAt = k
    intercept[Crash](w.repair(manual = true))
    assert(w.record.exists(_.repairing))
    // By the normal rules this now reads as a commit that published nothing.
    assertEquals(w.as021, Some(Recovery.Drop))
    val disk = tables.map(t => w.path(t) -> OnDisk(w.dest.get(w.path(t)).map(f => (f.footer, f.size)),
      w.tmp.get(w.path(t)).map(f => (f.footer, f.size)))).toMap
    assertEquals(resume(w.intent.copy(repairing = true), disk), Recovery.Stuck)
    // And 0.21.0's drop would find no tmp file to delete: all set aside.
    assertEquals(w.tmp.keySet.toSet, Set.empty[String])
    w.crashAt = -1
    assert(w.repair(manual = false).get.isInstanceOf[Result.Resolved])
    assertEquals(w.record, None)
    assertComplete(w)
  }

  test("0.21.0 after any step of a repair of a small table: it drops or finishes, losing nothing") {
    // A commit of battery rows (one file; no guard: 0.21.0 reads that table
    // on threads that do not catch the guard's exception).
    def scenario(published: Boolean) =
      val w = World(Seq("battery"), Map("battery" -> Vector(r(1, "b0"))), Map("battery" -> (100L until 130L).map(t => r(t, s"b$t")).toVector),
        delete = Delete.Hot("battery", 0, 10_000, 99))
      if published then w.renamed("battery")
      w
    for published <- Seq(false, true); k <- 1 to 12 do
      val w = scenario(published)
      val files = w.files
      w.crashAt = k
      val crashed = try { w.repair(manual = false); false } catch case _: Crash => true
      if crashed then
        // What 0.21.0 does with the record: drop (deleting tmp files, none
        // left once moved) or finish (deleting the chunk's rows, which the
        // published file holds); never stuck, never a tmp published.
        w.as021 match
          case Some(Recovery.Drop) =>
            assert(w.tmp.isEmpty || w.trail.isEmpty, s"step $k: a tmp file 0.21.0 would delete is still there after a move")
          case Some(Recovery.Finish(renames)) =>
            assert(renames.isEmpty || w.trail.forall(_ == "begin"), s"step $k: 0.21.0 would publish a tmp file after a move")
          case other => fail(s"step $k: $other")
        assertNothingDeleted(w, files)
        assert(!CommitIntent.encode(w.record.get).contains(CommitIntent.GuardDir))
  }

  test("a record of 0.21.0 (no check): a readable file other than the new version is the previous one") {
    val t = Target("imu/2026-11-10.parquet", 100, 1000)
    assertEquals(classify(t, Some((100, 1000)), quarantined = false), State.Published)
    assertEquals(classify(t, Some((40, 400)), quarantined = false), State.NotPublished)
    assertEquals(classify(t, None, quarantined = false), State.NotPublished)
    assertEquals(classify(t, Some((-1, 400)), quarantined = false), State.Unknown)
    assertEquals(classify(t, None, quarantined = true), State.Quarantined)
    val c = Check(Prev.File(40, 400), 40, 60, None, None, Nil)
    assertEquals(classify(t.copy(check = Some(c)), Some((40, 400)), quarantined = false), State.NotPublished)
    assertEquals(classify(t.copy(check = Some(c)), Some((41, 400)), quarantined = false), State.Unknown)
    assertEquals(classify(t.copy(check = Some(c)), None, quarantined = false), State.Unknown)
    assertEquals(classify(t.copy(check = Some(c.copy(prev = Prev.Absent))), None, quarantined = false), State.NotPublished)
    assertEquals(classify(t.copy(check = Some(c.copy(prev = Prev.Absent))), Some((40, 400)), quarantined = false), State.Unknown)
  }

  test("a record of 0.21.0 (no check): never resolved automatically when stuck; by hand only after every row is found") {
    def legacy() =
      val w = World(tables, old, chunk)
      w.renamed("gnss_raw")
      w.tmp.remove(w.path("imu"))
      w.record = Some(w.intent.copy(files = w.intent.files.map(_.copy(check = None))))
      w
    val w = legacy()
    for _ <- 1 to 2 do assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    assertEquals(w.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
    // Its tmp files are out of the way (and shown: earlier rows unknown).
    assert(w.quarantineTmp.contains(w.path("nmea")))
    assertEquals(w.attention.get(w.path("nmea") + ".tmp"), Some(true))
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    assertNoDuplicates(w)
    assertComplete(w)
    val v = legacy()
    val f = v.dest(v.path("gnss_raw"))
    v.dest(v.path("gnss_raw")) = f.copy(rows = f.rows.map(r => if r == chunk("gnss_raw")(7) then IndexedSeq(107L, "x") else r))
    assert(v.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(v.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
    assert(v.quarantine.contains(v.path("gnss_raw")))
  }

  test("downgrade: a raw record being repaired stops 0.21.0 on its guard; a small table's record reads as before") {
    val w = World(tables, old, chunk)
    val marked = CommitIntent.encode(w.intent.copy(repairing = true))
    assertEquals(CommitIntent.decode(marked), Some(w.intent.copy(repairing = true)))
    assertEquals(id(w.intent.copy(repairing = true)), id(w.intent))
    val (paths21, delete21) = OldDecoder.decode(marked).get
    val tables21 = (paths21.map(_.takeWhile(_ != '/')) ++ Archive.RawTables.map(_.name))
    assert(tables21.contains(CommitIntent.GuardDir))
    // Its locks exist only for Archive.All: taking the guard's throws
    // before it reads a file, deletes a tmp or removes the record.
    val locks = Archive.All.map(t => t.name -> t).toMap
    intercept[NoSuchElementException](tables21.distinct.sorted.foreach(locks(_)))
    // A small table's record: the marker after the deletion, unseen by 0.21.0.
    val hot = CommitIntent(Seq(Target("battery/2026-11-10.parquet", 31, 317)), Delete.Hot("battery", 0, 10, 9), repairing = true)
    val text = CommitIntent.encode(hot)
    assertEquals(CommitIntent.decode(text), Some(hot))
    assertEquals(OldDecoder.decode(text), Some((Seq("battery/2026-11-10.parquet"), "hot")))
  }

  /** The record decoder of 0.21.0 (bce5e55, CommitIntent.decode), as it was. */
  object OldDecoder:
    def decode(s: String): Option[(Seq[String], String)] =
      val lines = s.linesIterator.toVector
      if !lines.lastOption.contains("end") then None
      else
        scala.util.Try {
          val files = lines.filter(_.startsWith("file\t")).map(_.split('\t')).map(a => (a(1), a(2).toLong, a(3).toLong))
          val d = lines.filterNot(l => l.startsWith("file\t") || l == "end").head.split("\t", -1)
          require(Set("hot", "raw", "ids", "files")(d(0)))
          d(0) match
            case "hot" => (d(1), d(2).toLong, d(3).toLong, d(4).toLong)
            case _     => ()
          (files.map(_._1), d(0))
        }.toOption

  test("readers during an unfinished commit (rule C): chunk rows left out only for a file that passed the full check") {
    val battery = CommitIntent(Seq(Target("battery/2026-11-10.parquet", 31, 317)), Delete.Hot("battery", 100, 200, 9))
    // In place but not checked: nothing left out, equal rows shown once.
    assertEquals(exclusion(battery, _ => true), ReadExclusion(Map.empty, Set.empty, raw = false, Set("battery")))
    // In place and checked (recorded in the record): the chunk's rows in SQLite are left out.
    val checked = battery.copy(verified = Set("battery/2026-11-10.parquet"))
    assertEquals(exclusion(checked, _ => true), ReadExclusion(Map("battery" -> (100L, 200L, 9L)), Set.empty, raw = false))
    // Not (or no longer) in place: SQLite is read whole.
    assertEquals(exclusion(checked, _ => false).hot, Map.empty[String, (Long, Long, Long)])
    // A month file in place and checked: its day files are left out; not checked: kept.
    val month = CommitIntent(Seq(Target("fixes/2026-10.parquet", 9, 90)), Delete.Files(Seq("fixes/2026-10-01.parquet")))
    assertEquals(exclusion(month.copy(verified = Set("fixes/2026-10.parquet")), _ => true).files, Set("fixes/2026-10-01.parquet"))
    assertEquals(exclusion(month, _ => true).files, Set.empty[String])
    // Raw lines: readers that cannot leave them out wait.
    assert(exclusion(CommitIntent(Nil, Delete.Ids(Seq(1))), _ => true).raw)
  }

  /** What a reader shows of `w`'s battery table: the file in place and
    * SQLite as ArchiveStore.reading and Db do (rule C).
    */
  private def readBattery(w: World): Vector[Row] =
    val t = w.intent.files.head
    val ex = w.record.map(i => exclusion(i, p => w.dest.get(p).exists(f => (f.footer, f.size) == (t.rows, t.size))))
      .getOrElse(ReadExclusion(Map.empty, Set.empty, raw = false))
    val hot = w.sqlite("battery").filterNot(r => ex.hot.get("battery").exists((f, to, _) => ArchiveMerge.key(r) >= f && ArchiveMerge.key(r) < to))
    val f = w.dest.get(w.path("battery"))
    // A file that does not decode reads as nothing (the reader skips it).
    val archived = f.filterNot(_.decodeFails).map(_.rows).getOrElse(Vector.empty)
    val all = archived ++ hot
    if ex.dedupe("battery") then distinctRows(all)(identity) else all

  test("readers: every row once, unpublished, published unchecked, checked, after the deletion") {
    val chunkRows = (100L until 130L).map(t => r(t, s"b$t")).toVector
    val w = World(Seq("battery"), Map("battery" -> Vector(r(1, "b0"))), Map("battery" -> chunkRows),
      delete = Delete.Hot("battery", 100, 200, 99))
    val every = (Vector(r(1, "b0")) ++ chunkRows :+ r(9_000, "battery-later")).toSet
    def check(when: String) =
      val read = readBattery(w)
      assertEquals(read.size, read.distinct.size, when)
      assertEquals(read.toSet, every, when)
    check("not published")
    w.renamed("battery")
    check("published, not checked")
    w.record = w.record.map(_.copy(verified = Set(w.path("battery"))))
    check("published and checked")
    w.sqlite("battery") = w.sqlite("battery").filterNot(chunkRows.contains)
    check("chunk rows deleted")
  }

  test("readers: a replaced file damaged with the same count and size hides no row that SQLite still has") {
    val chunkRows = (100L until 130L).map(t => r(t, s"b$t")).toVector
    for damage <- Seq("decode", "values") do
      val w = World(Seq("battery"), Map("battery" -> Vector(r(1, "b0"))), Map("battery" -> chunkRows),
        delete = Delete.Hot("battery", 100, 200, 99))
      w.renamed("battery")
      val f = w.dest(w.path("battery"))
      w.dest(w.path("battery")) =
        if damage == "decode" then f.copy(decodeFails = true)
        else f.copy(rows = f.rows.map(r => IndexedSeq(r(0), "garbage")))
      // Automatic repair: it does not verify, nothing deleted, not marked checked.
      assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser], damage)
      assertEquals(w.record.get.verified, Set.empty[String], damage)
      val read = readBattery(w)
      // Every chunk row of SQLite, exactly once.
      for row <- chunkRows do assertEquals(read.count(_ == row), 1, s"$damage $row")
      assert(read.contains(r(9_000, "battery-later")))
      // By hand: the file is quarantined, the rows stay; readers see SQLite.
      assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved], damage)
      assert(w.quarantine.contains(w.path("battery")), damage)
      for row <- chunkRows do assertEquals(readBattery(w).count(_ == row), 1, s"$damage after $row")
  }

  test("distinct rows: equal rows once (NaN like null), others kept, in order") {
    val xs = Vector(r(1, "a"), r(1, "a"), r(2, "b"), IndexedSeq(3L, Double.NaN), IndexedSeq(3L, null), r(1, "c"))
    assertEquals(distinctRows(xs)(identity).map(_.toString), Vector(r(1, "a"), r(2, "b"), IndexedSeq(3L, Double.NaN), r(1, "c")).map(_.toString))
  }

  // --- moves that never replace a file -------------------------------------------------------

  private def tmpDir() = java.nio.file.Files.createTempDirectory("move")
  private def writeFile(p: java.nio.file.Path, text: String) =
    java.nio.file.Files.createDirectories(p.getParent)
    java.nio.file.Files.writeString(p, text)

  /** What a move synced, and the record of where it moved (as in the
    * commit record: kept across a crash).
    */
  final case class Synced(files: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty,
                          dirs: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty,
                          record: mutable.Map[String, String] = mutable.Map.empty):
    def fresh = Synced(record = record)
  private def moveWith(from: java.nio.file.Path, to: java.nio.file.Path, s: Synced, crash: String => Unit = _ => ()) =
    SafeMove.move(from, to, s.record.get(from.toString).map(java.nio.file.Path.of(_)), t => s.record(from.toString) = t.toString,
      p => java.nio.file.Files.createDirectories(p), p => s.files += p.toString, p => s.dirs += p.toString, crash)

  test("safe move: the name recorded before the rename; synced after; never over another file") {
    val d = tmpDir()
    val a = d.resolve("imu/x.parquet.tmp")
    val q = d.resolve("quarantine/c1-aaaa/imu/x.parquet.tmp")
    writeFile(a, "tmp")
    val s = Synced()
    assertEquals(moveWith(a, q, s, at => assertEquals(s.record.get(a.toString), Some(q.toString), at)), Some(q))
    assert(!java.nio.file.Files.exists(a))
    assertEquals(java.nio.file.Files.readString(q), "tmp")
    assertEquals(s.files.toSeq, Seq(q.toString))
    assertEquals(s.dirs.toSet, Set(q.getParent.toString, a.getParent.toString))
    // Another file where it would go: kept, the new one goes beside it.
    writeFile(a, "another")
    val q1 = q.resolveSibling("x.parquet.tmp.1")
    assertEquals(moveWith(a, q, Synced()), Some(q1))
    assertEquals(java.nio.file.Files.readString(q), "tmp")
    assertEquals(java.nio.file.Files.readString(q1), "another")
    // Nothing to move, nothing recorded.
    assertEquals(moveWith(d.resolve("none"), d.resolve("q/none"), Synced()), None)
  }

  test("safe move: cut short right after a rename to `.1`, the retry finds that file by the record and syncs it") {
    val d = tmpDir()
    val a = d.resolve("imu/x.parquet.tmp")
    val q = d.resolve("quarantine/c1-aaaa/imu/x.parquet.tmp")
    val q1 = q.resolveSibling("x.parquet.tmp.1")
    writeFile(q, "an earlier file")
    writeFile(a, "tmp")
    val first = Synced()
    intercept[Crash](moveWith(a, q, first, at => if at == "moved" then throw Crash()))
    assertEquals(first.files.toSeq, Nil)
    assertEquals(first.record.toMap, Map(a.toString -> q1.toString))
    // The retry: the source gone, the recorded `.1` there (not `to`).
    val s = first.fresh
    assertEquals(moveWith(a, q, s), Some(q1))
    assertEquals(java.nio.file.Files.readString(q1), "tmp")
    assertEquals(java.nio.file.Files.readString(q), "an earlier file")
    assertEquals(s.files.toSeq, Seq(q1.toString))
    assertEquals(s.dirs.toSet, Set(q.getParent.toString, a.getParent.toString))
  }

  test("safe move: cut short after the name was recorded, before the rename: the retry moves to the recorded name") {
    val d = tmpDir()
    val a = d.resolve("imu/x.parquet.tmp")
    val q = d.resolve("quarantine/c1-aaaa/imu/x.parquet.tmp")
    writeFile(a, "tmp")
    val s = Synced()
    s.record(a.toString) = q.resolveSibling("x.parquet.tmp.3").toString
    assertEquals(moveWith(a, q, s), Some(q.resolveSibling("x.parquet.tmp.3")))
    // A file took the recorded name meanwhile: another name, recorded again.
    writeFile(a, "again")
    assertEquals(moveWith(a, q, s), Some(q))
    assertEquals(s.record(a.toString), q.toString)
  }

  test("a chunk archived again and cut short again: each repair moves to its own directory, the earlier copy kept") {
    val d = tmpDir()
    val i = CommitIntent(Seq(Target("imu/x.parquet", 10, 100)), Delete.Ids(Seq(1)))
    val first = i.copy(repairTag = "aaaa1111")
    val second = i.copy(repairTag = "bbbb2222")
    assertEquals(id(first), id(second))
    def dir(c: CommitIntent) = d.resolve(s"quarantine/${id(c)}-${c.repairTag}")
    writeFile(d.resolve("imu/x.parquet.tmp"), "first")
    moveWith(d.resolve("imu/x.parquet.tmp"), dir(first).resolve("imu/x.parquet.tmp"), Synced())
    writeFile(d.resolve("imu/x.parquet.tmp"), "second")
    moveWith(d.resolve("imu/x.parquet.tmp"), dir(second).resolve("imu/x.parquet.tmp"), Synced())
    assertEquals(java.nio.file.Files.readString(dir(first).resolve("imu/x.parquet.tmp")), "first")
    assertEquals(java.nio.file.Files.readString(dir(second).resolve("imu/x.parquet.tmp")), "second")
    // The tag and the files checked are kept with the record (0.21.0 reads past them).
    val kept = second.copy(repairing = true, verified = Set("imu/x.parquet"),
      moves = Map("imu/x.parquet.tmp" -> s"quarantine/${id(second)}-bbbb2222/imu/x.parquet.tmp.1"))
    assertEquals(id(kept), id(i))
    assertEquals(CommitIntent.decode(CommitIntent.encode(kept)), Some(kept))
    assertEquals(OldDecoder.decode(CommitIntent.encode(kept)).map(_._2), Some("ids"))
  }

  // --- verifying a real file -------------------------------------------------------------------

  private val cols = Seq(Column("time", ColType.Int64), Column("v", ColType.Str))

  private def write(f: File, rows: Seq[Row], groupRows: Int = 16): Unit =
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { out =>
      val w = ParquetWriter(out, cols, groupRows)
      rows.foreach(w.write)
      w.close()
    }

  private def check(f: File, t: Target): Either[String, Unit] =
    Using.resource(ParquetReader.open(f.getPath))(rd => verify(t, rd.rows, rd.rowIterator))

  test("verify: the footer, every group, the time ranges and the sampled chunk rows") {
    val dir = java.nio.file.Files.createTempDirectory("repair").toFile
    val prev = (0L until 50L).map(i => r(i, s"p$i"))
    val added = (50L until 130L).map(i => r(i, s"a$i"))
    val f = File(dir, "x.parquet")
    write(f, prev ++ added)
    val s = ChunkSampler()
    added.foreach(s.add)
    val t = Target("x", 130, f.length, Some(s.check(Prev.File(50, 999), 50, 80, Some((0L, 129L)))))
    assertEquals(check(f, t), Right(()))
    // The footer or the sum does not match.
    assert(check(f, t.copy(rows = 131)).isLeft)
    assert(check(f, t.copy(check = t.check.map(_.copy(carried = 49)))).isLeft)
    // Another time range.
    assert(check(f, t.copy(check = t.check.map(_.copy(range = Some((0L, 130L)))))).isLeft)
    assert(check(f, t.copy(check = t.check.map(_.copy(addedRange = Some((50L, 140L)))))).isLeft)
    // A chunk row missing (a sample of another row).
    assert(check(f, t.copy(check = t.check.map(c => c.copy(samples = c.samples :+ (60L, ArchiveMerge.hash(r(60, "zz"))))))).isLeft)
    // Damaged in the middle (a row group no longer decodes, or decodes to other values).
    val g = File(dir, "y.parquet")
    java.nio.file.Files.copy(f.toPath, g.toPath)
    Using.resource(RandomAccessFile(g, "rw")) { raf =>
      raf.seek(g.length / 3)
      raf.write(Array.fill[Byte](64)(0x5a))
    }
    assert(check(g, t.copy(size = g.length)).isLeft)
  }

  test("sampler: bounded, first and last rows always, evenly spread") {
    val s = ChunkSampler(max = 4)
    val rows = (0L until 1000L).map(i => r(i, s"v$i"))
    rows.foreach(s.add)
    assertEquals(s.count, 1000L)
    assertEquals(s.range, Some((0L, 999L)))
    val keys = s.samples.map(_._1)
    assert(keys.size <= 9, keys)
    assertEquals(keys.head, 0L)
    assertEquals(keys.last, 999L)
    assert(keys.sliding(2).forall(p => p(1) > p(0)), keys)
    assertEquals(s.samples.map(_._2), keys.map(k => ArchiveMerge.hash(rows(k.toInt))))
    val one = ChunkSampler()
    one.add(r(5, "x"))
    assertEquals(one.samples, Seq((5L, ArchiveMerge.hash(r(5, "x")))))
  }

  test("raw lines of a chunk to delete: only those that became rows of the verified tables, not the kept") {
    val lines = Seq(
      (1L, 1000L, "Accel,1000,1,0.1,0.2,9.8"),
      (2L, 1000L, "NMEA,$GPGGA,1,2,3,1000"),
      (3L, 1000L, "Accel,1000,2,0.1,0.2,9.8"),
      (4L, 1000L, "Bogus,1"),
      (5L, 1001L, "Gyro,1001,3,0.1,0.2,0.3"))
    assertEquals(rawRowids(lines, Set(3L), Set("imu")), Seq(1L, 5L))
    assertEquals(rawRowids(lines, Set.empty[Long], Set("nmea", "imu")), Seq(1L, 2L, 3L, 5L))
    assertEquals(rawRowids(lines, Set.empty[Long], Set.empty), Nil)
  }

  test("the repair's id is the same before and after the record is completed with checks") {
    val i = CommitIntent(Seq(Target("imu/2026-11-10.parquet", 10, 100)), Delete.Ids(Seq(1, 2)))
    val full = i.copy(files = i.files.map(_.copy(check = Some(Check(Prev.Unknown, 4, 6, None, Some((1L, 2L)), Seq((1L, -5L)))))))
    assertEquals(id(i), id(full))
    assertNotEquals(id(i), id(i.copy(delete = Delete.Ids(Seq(1, 3)))))
  }

  test("multiset: a missing row and an extra copy of another do not cancel out; equal rows are kept once") {
    val t = Target("x", 4, 0)
    def bag(rows: Row*) =
      val b = RowBag()
      rows.foreach(b.add)
      b
    val a = r(1, "a"); val b = r(2, "b"); val c = r(3, "c")
    // Expected a, b, c (and a again: equal rows, archived once).
    assertEquals(verify(t.copy(rows = 3), 3, Iterator(a, b, c), None, Some(bag(a, a, b, c))), Right(()))
    // b missing, a twice: the count is right, the rows are not.
    assert(verify(t.copy(rows = 4), 4, Iterator(a, a, a, c), None, Some(bag(a, b, c))).isLeft)
    assert(verify(t.copy(rows = 3), 3, Iterator(a, a, c), None, Some(bag(a, b, c))).isLeft)
    // A row from nowhere.
    assert(verify(t.copy(rows = 4), 4, Iterator(a, b, c, r(4, "d")), None, Some(bag(a, b, c))).isLeft)
    // Repeats the sources had (an old file with a repeat, copied as stored).
    assertEquals(verify(t.copy(rows = 4), 4, Iterator(a, a, b, c), None, Some(bag(a, a, b, c))), Right(()))
    // Published files: every chunk row at least once, repeats counted.
    val chunkBag = bag(b, b, c)
    assertEquals(verify(t.copy(rows = 3), 3, Iterator(a, b, c), Some(chunkBag)), Right(()))
    assert(verify(t.copy(rows = 2), 2, Iterator(a, b), Some(bag(b, b, c))).isLeft)
  }


  test("a cap on the rows compared: over it nothing is done automatically") {
    val small = RowBag(cap = 3)
    (1 to 3).foreach(i => small.add(r(i, "x")))
    intercept[RowBag.TooLarge](small.add(r(4, "x")))
    // Too many chunk rows to compare: the published file is not trusted.
    val w = World(tables, old, chunk, cap = 30)
    w.renamed("gnss_raw", "imu")
    w.tmp.remove(w.path("nmea"))
    val steps = w.repair(manual = false).get match
      case Result.NeedsUser(s) => s
      case other => fail(s"$other")
    assert(steps.exists(s => s.target.path == w.path("imu") && s.action.isInstanceOf[Action.Quarantine]), steps)
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
  }
