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

  /** The archive files, tmp files, quarantine, SQLite and the commit record. */
  final class World(tables: Seq[String], old: Map[String, Vector[Row]], chunk: Map[String, Vector[Row]], cap: Int = RowBag.Cap):
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
    }, Delete.Raw(0, 10_000, 99, ArchiveMerge.Cursor(0, 0), ArchiveMerge.Cursor(5_000, 50), Nil))
    var record: Option[CommitIntent] = Some(intent)
    for t <- tables do tmp(path(t)) = fileOf(newRows(t))

    /** The commit renamed these tables' files, then stopped. */
    def renamed(ts: String*): Unit = for t <- ts do dest(path(t)) = tmp.remove(path(t)).get

    private def step(): Unit =
      ops += 1
      if ops == crashAt then throw Crash()

    private def iter(f: FakeFile) =
      if f.decodeFails then f.rows.iterator.take(1) ++ Iterator(throw java.io.IOException("bad page")) else f.rows.iterator

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
      def quarantine(t: Target, why: String, onlyCopy: Boolean) =
        step()
        attention.getOrElseUpdate(t.path, onlyCopy)
        step()
        tmp.remove(t.path).foreach(quarantineTmp(t.path) = _)
        step()
        World.this.dest.remove(t.path).foreach(World.this.quarantine(t.path) = _)
      def setAsideTmp(t: Target, why: String) =
        step()
        attention.getOrElseUpdate(t.path + ".tmp", false)
        step()
        tmp.remove(t.path).foreach(quarantineTmp(t.path) = _)
      def dropTmp(p: String) =
        step()
        tmp.remove(p)
      def begin() =
        step()
        record = record.map(_.copy(repairing = true))
      def sync(paths: Seq[String]) = step()
      def deleteRows(ts: Set[String]) =
        // Table by table (a crash may come between them).
        var n = 0L
        for t <- ts.toSeq.sorted do
          step()
          val before = sqlite(t).size
          sqlite(t) = sqlite(t).filterNot(chunk(t).contains)
          n += before - sqlite(t).size
        n
      def clearIntent() =
        step()
        record = None
      def log(line: String) = World.this.log += line

    /** Recovery of the record on disk, as the app does it: finish, drop, or
      * repair (nothing if there is no record).
      */
    def repair(manual: Boolean): Option[Result] = record.map { i =>
      val disk = i.files.map(t => t.path -> OnDisk(dest.get(t.path).map(f => (f.footer, f.size)),
        tmp.get(t.path).map(f => (f.footer, f.size)))).toMap
      resume(i, disk) match
        case Recovery.Finish(renames) =>
          // Each tmp file checked against its previous file and the chunk's
          // rows before it goes over the previous file (ArchiveStore).
          val checked = renames.map { p =>
            val table = CommitRepair.tableOf(p)
            val t = i.files.find(_.path == p).get
            val f = tmp(p)
            p -> scala.util.Try {
              val bag = RowBag(cap)
              dest.get(p).foreach(_.rows.foreach(bag.add))
              sqlite(table).filter(chunk(table).contains).foreach(bag.add)
              bag
            }.toEither.left.map(_ => CommitRepair.TooLargeMark).flatMap(bag => CommitRepair.verifyPublish(t, f.footer, iter(f), bag))
          }
          val bad = checked.collect { case (p, Left(why)) => p }
          if bad.nonEmpty && !manual then Result.NeedsUser(Nil)
          else
            bad.foreach(p => quarantineTmp(p) = tmp.remove(p).get)
            checked.collect { case (p, Right(())) => p }.foreach(p => dest(p) = tmp.remove(p).get)
            CommitRepair.run(i, opsImpl, manual, recovering = true)
        case Recovery.Drop => CommitRepair.run(i, opsImpl, manual, recovering = true)
        case Recovery.Stuck => CommitRepair.run(i, opsImpl, manual)
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

  /** Every row of every table exactly once, between its file, SQLite and quarantine. */
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

  test("all renamed: every table verifies, its chunk leaves SQLite, the record goes") {
    val w = World(tables, old, chunk)
    w.renamed(tables*)
    val res = w.repair(manual = false).get
    assert(res.isInstanceOf[Result.Resolved], res)
    assertEquals(w.record, None)
    for t <- tables do
      assertEquals(w.sqlite(t), Vector(r(9_000, s"$t-later")))
      assertEquals(w.rows(t).size, old(t).size + chunk(t).size + 1)
    assertNoDuplicates(w)
    assertComplete(w)
  }

  test("some renamed, the rest's tmp missing or broken: published tables resolved, the others kept for the next run") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.tmp.remove(w.path("imu"))
    w.tmp(w.path("nmea")) = w.tmp(w.path("nmea")).copy(footer = -1)
    assertEquals(CommitIntent.recovery(w.intent, tables.map(t => w.path(t) ->
      OnDisk(w.dest.get(w.path(t)).map(f => (f.footer, f.size)), w.tmp.get(w.path(t)).map(f => (f.footer, f.size)))).toMap),
      Recovery.Stuck)
    val steps = w.repair(manual = false).get match
      case Result.Resolved(s) => s
      case other => fail(s"$other")
    assertEquals(steps.map(s => (s.state, s.action)), Seq(
      (State.Published, Action.DeleteRows), (State.NotPublished, Action.DropTmp(false)), (State.NotPublished, Action.DropTmp(false))))
    assertEquals(w.record, None)
    assertEquals(w.tmp.keySet.toSet, Set.empty[String])
    // gnss_raw: in its file only; imu, nmea: old file intact, chunk still in SQLite.
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.dest(w.path("imu")).rows, old("imu"))
    assertEquals(w.dest.get(w.path("nmea")), None)
    assertNoDuplicates(w)
    assertComplete(w)
    assert(w.log.last.contains("消去"), w.log)
  }

  test("a published file that does not verify: never moved automatically; quarantined by hand, rows kept") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw", "imu")
    w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
    w.tmp.remove(w.path("nmea"))
    val before = (w.dest.clone(), w.tmp.clone(), w.sqlite.clone())
    // Automatically: nothing changes, as often as it is tried.
    for _ <- 1 to 3 do assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    assertEquals((w.dest, w.tmp, w.sqlite), before)
    assertEquals(w.record, Some(w.intent))
    // By hand.
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(w.record, None)
    assertEquals(w.dest.get(w.path("imu")), None)
    // The quarantined file holds the previous rows (their only copy) and the chunk.
    assertEquals(w.quarantine(w.path("imu")).rows, (old("imu") ++ chunk("imu")).sortBy(ArchiveMerge.key))
    assertEquals(w.attention.toMap, Map(w.path("imu") -> true))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    assertNoDuplicates(w)
    assertComplete(w)
  }

  test("a file that is neither the previous nor the new version is quarantined, not trusted") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    // imu: something else entirely (the footer does not read), its tmp gone.
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

  test("a crash at any step of a repair: the next run completes it with the same result") {
    def scenario(): World =
      val w = World(tables, old, chunk)
      w.renamed("gnss_raw", "imu")
      w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
      w.tmp(w.path("nmea")) = w.tmp(w.path("nmea")).copy(footer = -1)
      w
    val clean = scenario()
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
        // Cut short: nothing is lost meanwhile.
        assertComplete(w)
        w.crashAt = -1
        // Automatic runs in between change nothing more than they may.
        w.repair(manual = false)
        assertComplete(w)
        w.repair(manual = true)
        assertEquals(snapshot(w), snapshot(clean), s"crash at step $k")
        assertNoDuplicates(w)
      k += 1
    assert(k > 6, s"only ${k - 1} steps")
    // And once more on the stale record: nothing changes.
    clean.record = Some(clean.intent)
    val again = snapshot(clean).copy(_6 = None)
    clean.repair(manual = true)
    assertEquals(snapshot(clean), again)
  }

  test("a record of 0.21.0 (no check): a readable file other than the new version is the previous one") {
    val t = Target("imu/2026-11-10.parquet", 100, 1000)
    assertEquals(classify(t, Some((100, 1000)), tmp = false, quarantined = false), State.Published)
    assertEquals(classify(t, Some((40, 400)), tmp = true, quarantined = false), State.NotPublished)
    assertEquals(classify(t, None, tmp = false, quarantined = false), State.NotPublished)
    // No file but a tmp file: it may hold the only copy of earlier rows.
    assertEquals(classify(t, None, tmp = true, quarantined = false), State.Unknown)
    assertEquals(classify(t, Some((-1, 400)), tmp = false, quarantined = false), State.Unknown)
    assertEquals(classify(t, None, tmp = false, quarantined = true), State.Quarantined)
    val c = Check(Prev.File(40, 400), 40, 60, None, None, Nil)
    assertEquals(classify(t.copy(check = Some(c)), Some((40, 400)), tmp = true, quarantined = false), State.NotPublished)
    assertEquals(classify(t.copy(check = Some(c)), Some((41, 400)), tmp = true, quarantined = false), State.Unknown)
    assertEquals(classify(t.copy(check = Some(c)), None, tmp = true, quarantined = false), State.Unknown)
    assertEquals(classify(t.copy(check = Some(c.copy(prev = Prev.Absent))), None, tmp = true, quarantined = false), State.NotPublished)
    assertEquals(classify(t.copy(check = Some(c.copy(prev = Prev.Absent))), Some((40, 400)), tmp = false, quarantined = false),
      State.Unknown)
    // Not identified: its tmp file is set aside, not deleted.
    val w = World(Seq("imu"), Map("imu" -> Vector(r(11, "i0"))), Map("imu" -> Vector(r(100, "i100"))))
    val legacyTarget = w.intent.files.head.copy(check = Some(c.copy(prev = Prev.Unknown)))
    assertEquals(plan(CommitIntent(Seq(legacyTarget), Delete.Ids(Nil)), w.opsImpl).map(_.action), Seq(Action.DropTmp(setAside = true)))
  }

  test("once a repair began, recovery continues it: quarantining the last published file is not a commit to drop") {
    val w = World(tables, old, chunk)
    w.renamed("imu")
    w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
    w.tmp(w.path("gnss_raw")) = w.tmp(w.path("gnss_raw")).copy(footer = -1)
    // Dies right after moving imu's file (begin, gnss_raw's tmp, then imu: mark, tmp, file).
    w.crashAt = 6
    intercept[Crash](w.repair(manual = true))
    assert(w.quarantine.contains(w.path("imu")))
    assert(w.record.exists(_.repairing))
    // By the normal rules this now reads as a commit that published nothing.
    val disk = tables.map(t => w.path(t) -> OnDisk(w.dest.get(w.path(t)).map(f => (f.footer, f.size)),
      w.tmp.get(w.path(t)).map(f => (f.footer, f.size)))).toMap
    assertEquals(CommitIntent.recovery(w.intent, disk), Recovery.Drop)
    assertEquals(resume(w.intent.copy(repairing = true), disk), Recovery.Stuck)
    w.crashAt = -1
    assert(w.repair(manual = false).get.isInstanceOf[Result.Resolved])
    assertEquals(w.record, None)
    assertComplete(w)
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

  test("a published file whose rows other than the samples differ: nothing deleted, kept for the user") {
    val w = World(tables, old, chunk)
    w.renamed(tables*)
    // One chunk row that is not a sample replaced by another (same time,
    // same counts, same range): only the full comparison sees it.
    val samples = w.intent.files.find(_.path == w.path("imu")).get.check.get.samples.map(_._1).toSet
    val victim = chunk("imu").find(r => !samples(ArchiveMerge.key(r))).get
    val f = w.dest(w.path("imu"))
    w.dest(w.path("imu")) = f.copy(rows = f.rows.map(r => if r == victim then r(0).asInstanceOf[Long] +: IndexedSeq("forged") else r))
    val steps = w.repair(manual = false).get match
      case Result.NeedsUser(s) => s
      case other => fail(s"$other")
    assert(steps.exists(s => s.target.path == w.path("imu") && s.action.isInstanceOf[Action.Quarantine]), steps)
    // Nothing changed automatically.
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.record, Some(w.intent))
    // By hand: imu's file is quarantined (kept), its rows stay in SQLite.
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assert(w.sqlite("imu").contains(victim))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assert(w.quarantine.contains(w.path("imu")))
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
  }

  test("not published, previous file damaged though its footer reads, tmp good: the tmp is kept") {
    val w = World(tables, old, chunk)
    w.renamed("gnss_raw")
    w.tmp.remove(w.path("nmea"))
    // imu: the previous file's footer matches the record, its rows do not decode.
    w.dest(w.path("imu")) = w.dest(w.path("imu")).copy(decodeFails = true)
    val goodTmp = w.tmp(w.path("imu"))
    val steps = w.repair(manual = false).get match
      case Result.NeedsUser(s) => s
      case other => fail(s"$other")
    assertEquals(steps.find(_.target.path == w.path("imu")).map(_.state), Some(State.NotPublished))
    assertEquals(w.tmp.get(w.path("imu")), Some(goodTmp))
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    // Both moved aside, nothing deleted; the rows stay in SQLite.
    assertEquals(w.quarantineTmp.get(w.path("imu")), Some(goodTmp))
    assert(w.quarantine.contains(w.path("imu")))
    assertEquals(w.attention.get(w.path("imu")), Some(true))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
  }

  test("a record of 0.21.0 (no check): never resolved automatically; by hand only after every row is found") {
    def legacy() =
      val w = World(tables, old, chunk)
      w.renamed("gnss_raw")
      w.tmp.remove(w.path("imu"))
      w.record = Some(w.intent.copy(files = w.intent.files.map(_.copy(check = None))))
      w
    val w = legacy()
    for _ <- 1 to 2 do assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    assertEquals(w.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
    assert(w.tmp.contains(w.path("nmea")))
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(w.sqlite("gnss_raw"), Vector(r(9_000, "gnss_raw-later")))
    // nmea: its previous version unknown, so its tmp is set aside, not deleted.
    assert(w.quarantineTmp.contains(w.path("nmea")))
    assertNoDuplicates(w)
    assertComplete(w)
    // A row of the published file differing from SQLite: nothing deleted.
    val v = legacy()
    val f = v.dest(v.path("gnss_raw"))
    v.dest(v.path("gnss_raw")) = f.copy(rows = f.rows.map(r => if r == chunk("gnss_raw")(7) then IndexedSeq(107L, "x") else r))
    assert(v.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(v.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
    assert(v.quarantine.contains(v.path("gnss_raw")))
  }

  test("downgrade: 0.21.0 decodes a record being repaired but stops on its guard table, keeping it") {
    val w = World(tables, old, chunk)
    val marked = CommitIntent.encode(w.intent.copy(repairing = true))
    assertEquals(CommitIntent.decode(marked), Some(w.intent.copy(repairing = true)))
    assertEquals(id(w.intent.copy(repairing = true)), id(w.intent))
    // 0.21.0's decoder and the tables it locks (ArchiveStore.tablesOf of 0.21.0).
    val old21 = OldDecoder.decode(marked).get
    val tables21 = old21._1.map(_.takeWhile(_ != '/'))
    assert(tables21.contains(CommitIntent.GuardDir))
    // Its locks exist only for Archive.All: taking the guard's throws
    // before it reads a file, deletes a tmp or removes the record.
    val locks = Archive.All.map(t => t.name -> t).toMap
    intercept[NoSuchElementException](tables21.distinct.sorted.foreach(locks(_)))
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
          (files.map(_._1), d(0))
        }.toOption

  test("finishing a commit: a tmp file with a damaged body (same count and size) never replaces the previous file") {
    def scenario() =
      val w = World(tables, old, chunk)
      w.renamed("gnss_raw")
      // imu's tmp: the right footer and size, one row changed.
      val f = w.tmp(w.path("imu"))
      w.tmp(w.path("imu")) = f.copy(rows = f.rows.map(r => if r == chunk("imu")(30) then IndexedSeq(130L, "zz") else r))
      w
    val w = scenario()
    val oldImu = w.dest(w.path("imu"))
    assert(w.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    // Automatically: nothing renamed, nothing deleted.
    assertEquals(w.dest(w.path("imu")), oldImu)
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    assertEquals(w.sqlite("gnss_raw").size, chunk("gnss_raw").size + 1)
    // By hand: the damaged tmp is set aside; the previous file and the rows stay.
    assert(w.repair(manual = true).get.isInstanceOf[Result.Resolved])
    assertEquals(w.dest(w.path("imu")), oldImu)
    assert(w.quarantineTmp.contains(w.path("imu")))
    assertEquals(w.sqlite("imu").size, chunk("imu").size + 1)
    // nmea's good tmp was published and its rows deleted, as gnss_raw's.
    assertEquals(w.sqlite("nmea"), Vector(r(9_000, "nmea-later")))
    assertNoDuplicates(w)
    assertComplete(w)
    // A tmp whose body fails to decode likewise.
    val v = World(tables, old, chunk)
    v.renamed("gnss_raw")
    v.tmp(v.path("imu")) = v.tmp(v.path("imu")).copy(decodeFails = true)
    assert(v.repair(manual = false).get.isInstanceOf[Result.NeedsUser])
    assertEquals(v.dest(v.path("imu")), oldImu)
  }

  test("multiset: a missing row and an extra copy of another do not cancel out; equal rows are kept once") {
    val t = Target("x", 4, 0)
    def bag(rows: Row*) =
      val b = RowBag()
      rows.foreach(b.add)
      b
    val a = r(1, "a"); val b = r(2, "b"); val c = r(3, "c")
    // Expected a, b, c (and a again: equal rows, archived once).
    assertEquals(verifyPublish(t.copy(rows = 3), 3, Iterator(a, b, c), bag(a, a, b, c)), Right(()))
    // b missing, a twice: the count is right, the rows are not.
    assert(verifyPublish(t.copy(rows = 4), 4, Iterator(a, a, a, c), bag(a, b, c)).isLeft)
    assert(verifyPublish(t.copy(rows = 3), 3, Iterator(a, a, c), bag(a, b, c)).isLeft)
    // A row from nowhere.
    assert(verifyPublish(t.copy(rows = 4), 4, Iterator(a, b, c, r(4, "d")), bag(a, b, c)).isLeft)
    // Repeats the sources had (an old file with a repeat, copied as stored).
    assertEquals(verifyPublish(t.copy(rows = 4), 4, Iterator(a, a, b, c), bag(a, a, b, c)), Right(()))
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

