package net.sksat.ashidori

import java.io.{BufferedOutputStream, File, FileOutputStream, RandomAccessFile}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import scala.collection.mutable
import scala.util.{Try, Using}
import ashidori.core.*
import ashidori.core.parquet.*

/** Columnar archive of finished days.
  *
  * Today's data lives in SQLite (cheap appends, quick queries). Once a day
  * is over its rows are written to `<table>/YYYY-MM-DD.parquet`, checked
  * against the file's footer, and only then deleted from SQLite. When a
  * month is over, the day files of the small tables are merged into
  * `<table>/YYYY-MM.parquet`; the raw signal tables keep their day files.
  * Reads cover both.
  *
  * Archiving runs in its own process (see [[Maintenance]]) and streams: a
  * day of raw lines is read from SQLite a chunk at a time and written row
  * group by row group, merged with what an earlier run archived, so memory
  * stays flat however long the day's detail log is. It only ever deletes
  * rows it has written, verified and made durable. Each commit (files
  * renamed into place, then rows deleted) is recorded first
  * ([[CommitIntent]]), so a crash in the middle is finished at the next
  * start rather than leaving rows both archived and in SQLite.
  *
  * Readers take a table's lock shared, commits take it exclusive (both
  * processes): a reader sees each row once, in the archive or in SQLite.
  */
final class ArchiveStore(val root: File, zone: ZoneId):
  import ArchiveStore.*
  ensureDir(root)

  private def file(rel: String) = File(root, rel)

  private def day(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate
  def startOfDay(d: LocalDate): Long = d.atStartOfDay(zone).toInstant.toEpochMilli
  private def startOf(d: LocalDate): Long = startOfDay(d)

  /** Files that may hold rows of `t` in [from, to). */
  private def filesFor(t: Archive.TableDef, from: Long, to: Long): Seq[File] =
    val dir = File(root, t.name)
    if !dir.exists then Nil
    else
      val first = day(math.max(from, 0L))
      val last = day(math.min(to, System.currentTimeMillis() + 86_400_000L) - 1)
      val months = Iterator.iterate(YearMonth.from(first))(_.plusMonths(1)).takeWhile(!_.isAfter(YearMonth.from(last)))
        .map(m => file(Archive.monthFile(t, m))).filter(_.exists).toSeq
      val days =
        if java.time.temporal.ChronoUnit.DAYS.between(first, last) > 400 then
          // Long ranges: list the directory instead of probing every day.
          Option(dir.listFiles()).toSeq.flatten.filter(f => isDayFile(f))
            .filter { f =>
              val d = LocalDate.parse(f.getName.take(10))
              !d.isBefore(first) && !d.isAfter(last)
            }
        else Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).map(d => file(Archive.dayFile(t, d))).filter(_.exists).toSeq
      months ++ days

  /** Archived rows of `t` with the time column (first) in [from, to), in
    * time order. Files are read a row group at a time, skipping groups
    * outside the window (by their time statistics), so only the rows asked
    * for are held, however large the file (a month of positions). Call
    * within [[reading]] together with the read of SQLite.
    */
  def read(t: Archive.TableDef, from: Long, to: Long): Vector[IndexedSeq[Any]] =
    filesFor(t, from, to).flatMap { f =>
      Try(Using.resource(ParquetReader.open(f.getPath))(_.rowsBetween(from, to).filter(inWindow(from, to)).toVector))
        .getOrElse(Vector.empty)
    }.toVector.sortBy(ArchiveMerge.key)

  private def inWindow(from: Long, to: Long)(r: IndexedSeq[Any]): Boolean =
    val k = ArchiveMerge.key(r)
    k >= from && k < to

  /** Archived rows of `t` in [from, to) in time order, read a row group at
    * a time (for exports of the large raw tables). Close the iterator's
    * files with `close`. Call within [[reading]].
    */
  def stream(t: Archive.TableDef, from: Long, to: Long): (Iterator[IndexedSeq[Any]], () => Unit) =
    val readers = filesFor(t, from, to).flatMap(f => Try(ParquetReader.open(f.getPath)).toOption)
    val sorted = readers.map(_.rowsBetween(from, to).filter(inWindow(from, to)))
      .foldLeft(Iterator.empty[IndexedSeq[Any]])((a, b) => mergeSorted(a, b))
    (sorted, () => readers.foreach(r => Try(r.close())))

  /** Most recent archived row of `t`, if any. */
  def lastRow(t: Archive.TableDef): Option[IndexedSeq[Any]] =
    archivedFiles(t).lastOption.flatMap { f =>
      Try(Using.resource(ParquetReader.open(f.getPath)) { r =>
        if r.groups == 0 then None else r.readGroup(r.groups - 1).rowIterator.toSeq.lastOption
      }).toOption.flatten
    }

  /** Days that have archived rows of `t` (from file names). */
  def archivedFiles(t: Archive.TableDef): Seq[File] =
    Option(File(root, t.name).listFiles()).toSeq.flatten.filter(_.getName.endsWith(".parquet")).sortBy(_.getName)

  // --- locks ----------------------------------------------------------------------------

  private val locks = Archive.All.map(t => t.name -> TableLock(File(root, s".lock-${t.name}"))).toMap

  /** Runs `f` with the tables' locks held shared: no commit replaces their
    * files or deletes their rows from SQLite meanwhile. A commit left
    * unfinished by a process that died is finished first (or `f` does not
    * run: the error is thrown).
    */
  def reading[A](tables: Archive.TableDef*)(f: => A): A =
    val names = tables.map(_.name).distinct.sorted
    var out: Option[A] = None
    while out.isEmpty do
      // Only a commit of these tables holds them up (not one of others).
      if pendingTouches(names) then resolvePending()
      locked(names, exclusive = false, {
        // A commit of these tables that died after we checked: finish it first.
        if !pendingTouches(names) then out = Some(f)
      })
    out.get

  /** Runs `f` with the tables' locks held shared, without finishing an
    * unfinished commit first (the rescue backup: files as they are).
    */
  def holding[A](tables: Archive.TableDef*)(f: => A): A = locked(tables.map(_.name).distinct.sorted, exclusive = false, f)

  private val tmpLock = TmpLock(File(root, ".lock-tmp"))

  /** Runs `f` while no tmp file is being written (by either process): a
    * Rewrite holds this from creating its tmp file until it is complete.
    */
  def holdingTmps[A](f: => A): A =
    tmpLock.lock()
    try f
    finally tmpLock.unlock()

  /** Runs `f` with the tables' locks held exclusive (commits). */
  private def writing[A](tables: Seq[String])(f: => A): A = locked(tables.distinct.sorted, exclusive = true, f)

  /** Whether the commit record on disk names this archive file. */
  private def intentNames(path: String): Boolean =
    intentFile.exists &&
      Try(String(java.nio.file.Files.readAllBytes(intentFile.toPath), UTF_8)).toOption.flatMap(CommitIntent.decode)
        .forall(_.files.exists(_.path == path))

  /** Whether an unfinished commit (its record on disk) concerns any of `names`. */
  private def pendingTouches(names: Seq[String]): Boolean =
    intentFile.exists && {
      val text = Try(String(java.nio.file.Files.readAllBytes(intentFile.toPath), UTF_8)).toOption
      text.flatMap(CommitIntent.decode).forall(i => tablesOf(i).exists(names.contains))
    }

  /** Finishes an unfinished commit, if there is one (throws if it cannot). */
  def resolvePending(): Unit = if intentFile.exists then recover(AshidoriApp.db)

  private def locked[A](names: Seq[String], exclusive: Boolean, f: => A): A = names match
    case Seq() => f
    case n +: rest =>
      val l = locks(n)
      if exclusive then l.write(locked(rest, exclusive, f)) else l.read(locked(rest, exclusive, f))

  // --- writing --------------------------------------------------------------------

  /** One archive file being rewritten: the existing rows (streamed) merged
    * with the new ones into `<dest>.tmp`.
    */
  private final class Rewrite(val t: Archive.TableDef, val dest: File, tooLate: ((IndexedSeq[Any], Long)) => Unit):
    ensureDir(dest.getParentFile)
    val tmp = File(dest.getParentFile, dest.getName + ".tmp")
    private val existing = if dest.exists then Some(ParquetReader.open(dest.getPath)) else None
    // What the file held before: a published new version is told from it
    // by a repair (CommitRepair).
    private val prev = existing.fold(CommitIntent.Prev.Absent)(e => CommitIntent.Prev.File(e.rows, dest.length))
    private val sampler = ChunkSampler()
    private var range: Option[(Long, Long)] = None
    // Held until closed: no rescue backup takes a tmp file while it is
    // being written ([[holdingTmps]]).
    private val fos =
      tmpLock.lock()
      // A new file, never one truncated in place: a hard link to an old one
      // (the rescue backup's staging) keeps what it had.
      try
        if tmp.exists && !tmp.delete() then throw java.io.IOException(s"cannot replace $tmp")
        FileOutputStream(tmp)
      catch
        case e: Throwable =>
          tmpLock.unlock()
          existing.foreach(x => Try(x.close()))
          throw e
    private val out = BufferedOutputStream(fos, 1 << 16)
    private val writer = ParquetWriter(out, t.columns, groupRows(t))
    // Archived groups wholly before the new rows are copied as stored.
    val merge = ArchiveMerge.FileMerge(existing, writer)
    // New rows come in the order of their stored time, which for some raw
    // lines is seconds off the row's own: put them in order first. A row
    // later than that goes back (with its rowid) for a sorted pass.
    private val reorder = ArchiveMerge.Reorder[(IndexedSeq[Any], Long)](ReorderMs, r => ArchiveMerge.key(r._1),
      r => { sampler.add(r._1); merge.push(r._1) }, tooLate)
    private var closed = false
    var size = 0L

    /** A new row (and its rowid); `watermark`: the stored time the input has reached. */
    def push(r: IndexedSeq[Any], rowid: Long, watermark: Long): Unit = reorder.push((r, rowid), watermark)

    /** Writes the rest, makes it durable and checks the row count in the
      * new file's footer.
      */
    def finish(): Long =
      reorder.flush()
      merge.finish()
      writer.close()
      // On disk before anything depends on it (the rename, then deleting
      // the rows from SQLite).
      out.flush()
      fos.getFD.sync()
      close()
      val m = ParquetReader.meta(tmp.getPath)
      val n = m.rows
      if n != merge.written then
        throw IllegalStateException(s"archive verify failed for ${t.name}/${dest.getName}: $n != ${merge.written}")
      size = tmp.length
      // The time range of the file, from its groups' statistics (unchecked
      // if a group has none).
      range = Option.when(m.groups.nonEmpty && m.groups.forall(_.time.isDefined))(
        (m.groups.map(_.time.get._1).min, m.groups.map(_.time.get._2).max))
      n

    def target(rows: Long): CommitIntent.Target =
      CommitIntent.Target(root.toPath.relativize(dest.toPath).toString, rows, size,
        Some(sampler.check(prev, carried = merge.written - merge.added, added = merge.added, range)))

    def close(): Unit =
      if !closed then
        closed = true
        Try(out.close())
        existing.foreach(e => Try(e.close()))
        tmpLock.unlock()

    /** Drops the new version, unless a commit record names it: from then on
      * only finishing the commit (recovery) may use or remove it.
      */
    def abort(): Unit =
      close()
      if !intentNames(root.toPath.relativize(dest.toPath).toString) then tmp.delete()

  // --- commits ----------------------------------------------------------------------------

  private def intentFile = File(Maintenance.dir, "commit")

  /** Replaces the files and deletes the rows of `intent`, under the locks of
    * its tables: the record first (durable), then each rename (directory
    * synced), then the deletion, then the record is removed.
    */
  private def commit(db: Db, intent: CommitIntent, tables: Seq[String], check: () => Unit = () => ()): Unit =
    resolvePending()
    writing(tables) {
      // Never over an unfinished commit's record (its recovery failed).
      if intentFile.exists then throw IllegalStateException("an earlier archive commit is unfinished")
      // Under the lock: SQLite still holds exactly the rows written.
      check()
      Maintenance.write(intentFile, CommitIntent.encode(intent))
      for (t, i) <- intent.files.zipWithIndex do
        if i == 1 then debugFailPoint("fail-second-rename")
        if i >= 1 then
          debugBreakRest(intent, i, "stuck-delete-tmp", corrupt = false)
          debugBreakRest(intent, i, "stuck-corrupt-tmp", corrupt = true)
        rename(t.path)
      debugCrashPoint("crash-after-rename")
      runDelete(db, intent.delete)
      clearIntent()
    }

  /** Debug builds: the process dies here once if `files/maintenance/<name>`
    * exists (to test recovery from a crash in a commit).
    */
  private def debugCrashPoint(name: String): Unit =
    val app = AshidoriApp.app
    if (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 then
      val f = File(Maintenance.dir, name)
      if f.delete() then
        Log.w(s"debug: dying at $name")
        android.os.Process.killProcess(android.os.Process.myPid())

  /** Debug builds: an I/O error here once if `files/maintenance/<name>`
    * exists (to test a commit that fails after part of it is published).
    */
  private def debugFailPoint(name: String): Unit =
    val app = AshidoriApp.app
    if (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
      File(Maintenance.dir, name).delete()
    then throw java.io.IOException(s"debug: failing at $name")

  /** Debug builds: once if `files/maintenance/<name>` exists, before the
    * rename of the file at `i` (the number in it, 1 if none; at the last
    * file if there are fewer), the tmp files not renamed yet are deleted (or
    * cut in half: no footer) and the commit fails, as if they had been lost
    * after the first renames: a commit that recovery cannot finish (to test
    * [[CommitRepair]]).
    */
  private def debugBreakRest(intent: CommitIntent, i: Int, name: String, corrupt: Boolean): Unit =
    val app = AshidoriApp.app
    val f = File(Maintenance.dir, name)
    if (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && f.exists then
      val at = Try(String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).trim.toInt).getOrElse(1).max(1)
        .min(intent.files.size - 1)
      if i == at && f.delete() then
        for t <- intent.files.drop(i) do
          val tmp = tmpOf(t.path)
          if corrupt then Using.resource(RandomAccessFile(tmp, "rw"))(f => f.setLength(f.length / 2))
          else tmp.delete()
          Log.w(s"debug: ${if corrupt then "cut" else "deleted"} ${t.path}.tmp")
        throw java.io.IOException(s"debug: failing at $name")

  /** `<path>.tmp` over `<path>`, atomically and durably (a reader or an
    * upload that opened the old file keeps reading it whole).
    */
  private def rename(rel: String): Unit =
    val dest = file(rel)
    java.nio.file.Files.move(File(dest.getPath + ".tmp").toPath, dest.toPath,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    syncDir(dest.getParentFile)

  private def runDelete(db: Db, d: CommitIntent.Delete): Unit = d match
    case CommitIntent.Delete.Hot(table, from, to, max) => db.deleteArchived(table, from, to, max)
    case CommitIntent.Delete.Raw(from, to, max, after, upTo, kept) => db.deleteRawPortion(from, to, max, after, upTo, kept)
    case CommitIntent.Delete.Ids(ids) => db.deleteRawById(ids)
    case CommitIntent.Delete.Files(paths) =>
      paths.foreach(p => file(p).delete())
      paths.map(p => file(p).getParentFile).distinct.foreach(syncDir)

  private def tablesOf(i: CommitIntent): Seq[String] =
    val fromFiles = i.files.map(_.path.takeWhile(_ != '/'))
    i.delete match
      case _: CommitIntent.Delete.Raw | _: CommitIntent.Delete.Ids => fromFiles ++ Archive.RawTables.map(_.name)
      case CommitIntent.Delete.Hot(t, _, _, _) => fromFiles :+ t
      case CommitIntent.Delete.Files(ps) => fromFiles ++ ps.map(_.takeWhile(_ != '/'))

  /** Finishes a commit a crash interrupted (see [[CommitIntent]]). Call at
    * the start of each process that writes or reads the archive.
    *
    * A commit that cannot be finished ([[CommitIntent.Recovery.Stuck]]) is
    * resolved table by table ([[CommitRepair]]) in the maintenance process
    * (it reads whole files): automatically where no file has to be
    * quarantined, and also quarantining when `manual` (「整理を修復」).
    * Until it is resolved this throws [[CommitStuck]]: nothing reads or
    * commits its tables; the rows are all still in SQLite.
    */
  def recover(db: Db, manual: Boolean = false, progress: String => Unit = _ => ()): Unit =
    def text = intentText
    text.foreach { s =>
      CommitIntent.decode(s) match
        case None =>
          Log.w("commit record cut short: dropped (nothing was renamed or deleted)")
          intentFile.delete()
        case Some(intent) =>
          // Known to wait for the user: not verified again at every read.
          if !manual && CommitStatus.waitsForUser(s) then throw CommitStuck()
          // The other process may be recovering it too: act only if the
          // record is still there once the locks are held.
          writing(tablesOf(intent)) {
            if text.contains(s) then finishCommit(db, intent, manual, progress)
          }
    }

  private def intentText: Option[String] = Try(String(java.nio.file.Files.readAllBytes(intentFile.toPath), UTF_8)).toOption

  /** (rows in the footer, or -1 if it does not read, size) of a file, if it exists. */
  private def state(f: File) = Option.when(f.exists)(Try(ParquetReader.meta(f.getPath).rows).getOrElse(-1L) -> f.length)

  private def tmpOf(rel: String) = File(file(rel).getPath + ".tmp")

  private def finishCommit(db: Db, intent: CommitIntent, manual: Boolean, progress: String => Unit): Unit =
    val disk = intent.files.map(t => t.path -> CommitIntent.OnDisk(state(file(t.path)), state(tmpOf(t.path)))).toMap
    // Whatever the case, it reads whole files and SQLite's rows of the
    // chunk before deleting anything: done in the maintenance process,
    // asked for now.
    if !AshidoriApp.isMaintenanceProcess then
      Maintenance.requestRepair()
      throw CommitStuck()
    // A repair that began is continued, whatever the files look like now.
    val result = CommitRepair.resume(intent, disk) match
      case CommitIntent.Recovery.Finish(renames) =>
        // A tmp file goes over its previous file only once it holds exactly
        // the previous file's rows and the chunk's rows still in SQLite (a
        // multiset): its footer's count and size say nothing of its body.
        progress("一時ファイルを検証中")
        val checked = verifyTmps(db, intent, renames)
        val bad = checked.collect { case (p, Left(why)) => p -> why }
        if bad.nonEmpty && !manual then
          // Automatically: nothing renamed, moved or deleted.
          val steps = intent.files.filter(t => bad.exists(_._1 == t.path)).map(t =>
            CommitRepair.Step(t, CommitRepair.State.NotPublished,
              CommitRepair.Action.Quarantine(s"一時ファイルを検証できない（${bad.find(_._1 == t.path).get._2}）", onlyCopy = false)))
          CommitRepair.Result.NeedsUser(steps)
        else
          for (p, why) <- bad do
            // By hand: the tmp file is set aside; its previous file and the
            // rows in SQLite stay.
            setAside(intent, intent.files.find(_.path == p).get, s"一時ファイルを検証できない（$why）")
            LifeLog.add(LifeKind.ArchiveRepair, s"$p: 一時ファイルを検証できないため隔離（$why）。以前の版と端末内の行は保持")
          checked.collect { case (p, Right(())) => p }.foreach(rename)
          // A rename seen on disk may not be durable yet (the process died
          // before its directory was synced): every file and directory the
          // commit relies on is synced before anything is deleted.
          for t <- intent.files if file(t.path).exists do
            syncFile(file(t.path))
            syncDir(file(t.path).getParentFile)
          // Rows are deleted only once every one is found in its file.
          repair(db, intent, manual, progress, recovering = true)
      case CommitIntent.Recovery.Drop =>
        // Nothing published: a tmp file goes only if the file in place reads
        // whole as recorded; otherwise it is kept aside.
        repair(db, intent, manual, progress, recovering = true)
      case CommitIntent.Recovery.Stuck =>
        // Part published, the rest lost: resolved table by table.
        repair(db, intent, manual, progress)
    result match
      case CommitRepair.Result.Resolved(_) =>
        LifeLog.add(LifeKind.Archive, s"中断された整理を解決（${if manual then "整理を修復" else "自動"}）")
      case CommitRepair.Result.NeedsUser(steps) =>
        intentText.foreach(CommitStatus.mark(_, steps))
        LifeLog.add(LifeKind.ArchiveRepair,
          "中断された整理を自動では解決できない（記録は端末内に保持、整理を停止。端末診断の「整理を修復」で解決）")
        throw CommitStuck()

  /** Removes the commit record, durably. */
  private def clearIntent(): Unit =
    intentFile.delete()
    syncDir(intentFile.getParentFile)
    CommitStatus.clear()

  /** Where a repair of `intent` moves files: `quarantine/<commit id>/`. */
  private def quarantineDir(intent: CommitIntent) = File(root, s"$QuarantineDir/${CommitRepair.id(intent)}")

  /** `from` to `to`, atomically and durably (both directories synced). */
  private def moveDurably(from: File, to: File): Unit =
    ensureDir(to.getParentFile)
    java.nio.file.Files.move(from.toPath, to.toPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    syncDir(to.getParentFile)
    syncDir(from.getParentFile)

  /** Moves the tmp file of `t` to quarantine (its attention mark first). */
  private def setAside(intent: CommitIntent, t: CommitIntent.Target, why: String, onlyCopy: Boolean = false): Unit =
    val q = File(File(quarantineDir(intent), t.path).getPath + ".tmp")
    if tmpOf(t.path).exists then
      Attention.add(Attention.Entry(System.currentTimeMillis(), t.path, root.toPath.relativize(q.toPath).toString, onlyCopy, why))
      moveDurably(tmpOf(t.path), q)

  private def whole(path: String)(check: ParquetReader.FileReader => Either[String, Unit]): Either[String, Unit] =
    Try(ParquetReader.open(file(path).getPath)).fold(
      e => Left(s"開けない: ${LifeLog.describe(e)}"),
      r => try check(r) finally r.close())

  /** [[CommitRepair.verifyPublish]] of the tmp files of `paths`: each
    * against the rows of its previous file (in place) and the chunk's rows
    * of its table still in SQLite, at most [[RowBag.Cap]] rows each.
    */
  private def verifyTmps(db: Db, intent: CommitIntent, paths: Seq[String]): Seq[(String, Either[String, Unit])] =
    val targets = intent.files.filter(t => paths.contains(t.path))
    val bags: Either[String, Map[String, RowBag]] =
      Try {
        val bags = targets.map(t => CommitRepair.tableOf(t.path) -> RowBag()).toMap
        for t <- targets if file(t.path).exists do
          Using.resource(ParquetReader.open(file(t.path).getPath))(_.rowIterator.foreach(bags(CommitRepair.tableOf(t.path)).add))
        forEachChunkRow(db, intent.delete)((table, row) => bags.get(table).foreach(_.add(row)))
        bags
      }.toEither.left.map {
        case e: RowBag.TooLarge => CommitRepair.TooLargeMark + e.getMessage
        case e => s"以前の版か端末内の行を読めない: ${LifeLog.describe(e)}"
      }
    targets.map { t =>
      t.path -> bags.flatMap(b => Try(ParquetReader.open(tmpOf(t.path).getPath)).fold(
        e => Left(s"開けない: ${LifeLog.describe(e)}"),
        r => try CommitRepair.verifyPublish(t, r.rows, r.rowIterator, b(CommitRepair.tableOf(t.path))) finally r.close()))
    }

  /** [[CommitRepair]] of a commit that cannot be finished, under its tables' locks. */
  private def repair(db: Db, recorded: CommitIntent, manual: Boolean, progress: String => Unit,
                     recovering: Boolean = false): CommitRepair.Result =
    // A record of 0.21.0 has no checks: completed from SQLite (which still
    // holds every row of the commit) and stored before anything changes.
    val intent =
      if recorded.files.forall(_.check.isDefined) then recorded
      else
        progress("未完了の整理の記録を補っています")
        val full = completeChecks(db, recorded)
        Maintenance.write(intentFile, CommitIntent.encode(full))
        LifeLog.add(LifeKind.ArchiveRepair, "未完了の整理の記録（0.21.0 の形式）を端末内の行から補完")
        full
    val qdir = quarantineDir(intent)
    def move(from: File, to: File): Unit = moveDurably(from, to)
    // The chunk's rows still in SQLite (or day files of a month merge), per
    // table, read once for every published file; at most RowBag.Cap each.
    lazy val chunks: Either[String, Map[String, RowBag]] =
      progress("端末内の行を読んでいます")
      Try {
        val idx = intent.files.map(t => CommitRepair.tableOf(t.path) -> RowBag()).toMap
        forEachChunkRow(db, intent.delete)((table, row) => idx.get(table).foreach(_.add(row)))
        idx
      }.toEither.left.map {
        case e: RowBag.TooLarge => CommitRepair.TooLargeMark + e.getMessage
        case e => s"端末内の行を読めない: ${LifeLog.describe(e)}"
      }
    CommitRepair.run(intent, new CommitRepair.Ops:
      def dest(path: String) = state(file(path))
      def tmpExists(path: String) = tmpOf(path).exists
      def quarantined(path: String) = File(qdir, path).exists
      def verify(t: CommitIntent.Target) =
        chunks.flatMap { idx =>
          progress(s"検証中: ${t.path}")
          whole(t.path)(r => CommitRepair.verify(t, r.rows, r.rowIterator, idx.get(CommitRepair.tableOf(t.path))))
        }
      def verifyPrevious(t: CommitIntent.Target) =
        if !file(t.path).exists then Right(())
        else
          progress(s"以前の版を検証中: ${t.path}")
          val expected = t.check.map(_.prev).collect { case CommitIntent.Prev.File(rows, _) => rows }
          whole(t.path)(r => CommitRepair.verifyWhole(r.rows, r.rowIterator, expected))
      def quarantine(t: CommitIntent.Target, why: String, onlyCopy: Boolean) =
        val q = File(qdir, t.path)
        Attention.add(Attention.Entry(System.currentTimeMillis(), t.path, root.toPath.relativize(q.toPath).toString, onlyCopy, why))
        // The tmp file first: once the file is gone the table reads as quarantined.
        if tmpOf(t.path).exists then move(tmpOf(t.path), File(q.getPath + ".tmp"))
        if file(t.path).exists then move(file(t.path), q)
      def setAsideTmp(t: CommitIntent.Target, why: String) = setAside(intent, t, why)
      def dropTmp(path: String) =
        if tmpOf(path).delete() then syncDir(file(path).getParentFile)
      def begin() =
        // Durable before the first change; 0.21.0 stops at a record so
        // marked. Only a commit of several files (raw lines) needs it: with
        // one file, what a repair leaves (a file moved aside) reads to the
        // normal rules as a commit to drop or finish, which loses nothing;
        // and 0.21.0 reads the small tables on its logger and screen
        // threads, where the guard's exception is not caught.
        if intent.files.size > 1 && !intentText.flatMap(CommitIntent.decode).exists(_.repairing) then
          Maintenance.write(intentFile, CommitIntent.encode(intent.copy(repairing = true)))
          debugCrashPoint("crash-after-begin")
      def sync(paths: Seq[String]) =
        for p <- paths do
          syncFile(file(p))
          syncDir(file(p).getParentFile)
      def deleteRows(tables: Set[String]) =
        progress(s"重複した行を削除しています: ${tables.toSeq.sorted.mkString("、")}")
        deleteChunk(db, intent.delete, tables)
      def clearIntent() = ArchiveStore.this.clearIntent()
      def log(line: String) =
        LifeLog.add(LifeKind.ArchiveRepair, line)
        progress(line)
    , manual, recovering)

  /** The raw lines of a portion ([[ArchiveSql.rawPortion]]), a chunk at a
    * time: (rowid, time, line).
    */
  private def portionLines(db: Db, d: CommitIntent.Delete.Raw)(f: Vector[(Long, Long, String)] => Unit): Unit =
    var at = d.after
    var more = true
    while more do
      val lines = db.rawPortionLines(d.from, d.to, d.max, d.after, d.upTo, at, ChunkLines)
      if lines.isEmpty then more = false
      else
        f(lines)
        at = ArchiveMerge.Cursor(lines.last._2, lines.last._1)

  /** Deletes the rows of a commit's chunk that belong to `tables` (by
    * name), within the bounds a normal commit deletes ([[runDelete]]).
    */
  private def deleteChunk(db: Db, d: CommitIntent.Delete, tables: Set[String]): Long = d match
    case CommitIntent.Delete.Hot(table, from, to, max) => if tables(table) then db.deleteArchived(table, from, to, max) else 0L
    case raw: CommitIntent.Delete.Raw =>
      val kept = raw.kept.toSet
      var n = 0L
      portionLines(db, raw)(lines => n += db.deleteRawById(CommitRepair.rawRowids(lines, kept, tables)))
      n
    case CommitIntent.Delete.Ids(ids) => db.deleteRawById(CommitRepair.rawRowids(db.rawLinesById(ids), _ => false, tables))
    case CommitIntent.Delete.Files(paths) =>
      val gone = paths.filter(p => tables(CommitRepair.tableOf(p)))
      runDelete(db, CommitIntent.Delete.Files(gone))
      gone.size.toLong

  /** Every row of a commit's chunk still in SQLite (or in the day files of
    * a month merge), with its table: the rows a normal commit deletes
    * ([[runDelete]]) that convert to a row. Throws if a file does not read.
    */
  private def forEachChunkRow(db: Db, d: CommitIntent.Delete)(f: (String, IndexedSeq[Any]) => Unit): Unit =
    def parsed(lines: Iterable[(Long, Long, String)], kept: Long => Boolean): Unit =
      for (rowid, time, line) <- lines if !kept(rowid) do Archive.parse(line, time).foreach((t, row) => f(t.name, row))
    d match
      case raw: CommitIntent.Delete.Raw =>
        val kept = raw.kept.toSet
        portionLines(db, raw)(parsed(_, kept))
      case CommitIntent.Delete.Ids(ids) => parsed(db.rawLinesById(ids), _ => false)
      case CommitIntent.Delete.Hot(table, from, to, max) =>
        Archive.All.find(_.name == table).foreach(t => db.hotRows(t, from, to, max).foreach(r => f(table, r.toIndexedSeq)))
      case CommitIntent.Delete.Files(paths) =>
        for p <- paths if file(p).exists do
          Using.resource(ParquetReader.open(file(p).getPath))(_.rowIterator.foreach(f(CommitRepair.tableOf(p), _)))

  /** A record of 0.21.0 with what [[CommitRepair.verify]] needs, from the
    * chunk's rows (still all in SQLite, or in the day files of a month
    * merge). Its previous files stay unknown: such a record is repaired
    * only by hand.
    */
  private def completeChecks(db: Db, i: CommitIntent): CommitIntent =
    val samplers = i.files.map(t => CommitRepair.tableOf(t.path) -> ChunkSampler()).toMap
    forEachChunkRow(db, i.delete)((table, row) => samplers.get(table).foreach(_.add(row)))
    i.copy(files = i.files.map { t =>
      if t.check.isDefined then t
      else
        val s = samplers(CommitRepair.tableOf(t.path))
        t.copy(check = Some(s.check(CommitIntent.Prev.Unknown, carried = t.rows - s.count, added = s.count, range = None)))
    })

  /** 「整理を修復」 (in the maintenance process): finishes or resolves an
    * unfinished commit, quarantining what does not verify. Returns whether
    * there was one.
    */
  def repairNow(db: Db, progress: String => Unit): Boolean =
    val had = intentFile.exists
    recover(db, manual = true, progress)
    had

  /** An unfinished commit is recorded (finished, resolved or waiting for the user). */
  def commitPending: Boolean = intentFile.exists

  /** Archives one finished day: the small tables at once, the raw lines in
    * portions (each written, verified and committed before the next). Ends
    * early, not done, at `until` or at `stop`, after committing the portion
    * in progress; the rest is done by the next run.
    *
    * Raw lines that do not convert ([[Archive.parse]]) stay in SQLite. Rows
    * out of order by more than the reorder window are archived in a sorted
    * pass at the end (up to [[MaxLate]]; more stay for the next run).
    */
  def compactDay(db: Db, d: LocalDate, stop: () => Boolean, progress: String => Unit, until: Long = Long.MaxValue,
                 portionMs: Long = PortionMs, chunk: Int = ChunkLines): DayResult =
    val from = startOf(d)
    val to = startOf(d.plusDays(1))
    // Rows inserted from here on (rowid above these) are left for the next run.
    val max = db.maxRowids()
    // Leftovers of a run that died while writing.
    for t <- Archive.All if !intentNames(Archive.dayFile(t, d)) do File(root, Archive.dayFile(t, d) + ".tmp").delete()
    for t <- Seq(Archive.Fixes, Archive.Motion, Archive.Battery, Archive.GnssStatus) if !stop() do
      val table = t.name
      val rows = db.hotRows(t, from, to, max(table))
      if rows.nonEmpty then
        progress(s"$d ${t.name}")
        val rw = Rewrite(t, file(Archive.dayFile(t, d)), _ => ())
        try
          rows.foreach { r =>
            val row = r.toIndexedSeq
            rw.push(row, 0L, ArchiveMerge.key(row))
          }
          val n = rw.finish()
          // Exactly the rows written, or nothing: a row changed meanwhile
          // (deleted by the user) leaves the day for the next run.
          commit(db, CommitIntent(Seq(rw.target(n)), CommitIntent.Delete.Hot(table, from, to, max(table))), Seq(table), () =>
            val now = db.countArchived(table, from, to, max(table))
            if now != rows.size then throw IllegalStateException(s"$table $d changed while archiving: $now != ${rows.size}"))
        catch
          case e: Throwable =>
            rw.abort()
            throw e
    var done = false
    var at = ArchiveMerge.Cursor(from, Long.MinValue)
    var total = 0L
    var unconverted = 0L
    // Rows too far out of order, for the sorted pass: rowids.
    val late = mutable.ArrayBuffer.empty[Long]
    while !done && !stop() && System.currentTimeMillis() < until do
      // One portion: chunks until the time is up or the day is read.
      val start = at
      val open = mutable.LinkedHashMap.empty[Archive.TableDef, Rewrite]
      // Lines that became no row stay in SQLite: never delete what was not archived.
      val kept = mutable.ArrayBuffer.empty[Long]
      var lateHere = 0
      def tooLate(r: (IndexedSeq[Any], Long)): Unit =
        kept += r._2
        lateHere += 1
        if late.size < MaxLate then late += r._2
      val portionEnd = math.min(until, System.currentTimeMillis() + portionMs)
      var read = 0L
      try
        var more = true
        while more && System.currentTimeMillis() < portionEnd && read < PortionLines && kept.size < MaxKept && !stop() do
          val lines = db.rawLinesAfter(from, to, max("raw_lines"), at, chunk)
          if lines.isEmpty then more = false
          else
            for (rowid, time, line) <- lines do
              // A line without a time of its own (navigation messages) has
              // the preceding record's, stored with it.
              Archive.parse(line, time) match
                case Right((t, row)) =>
                  open.getOrElseUpdate(t, Rewrite(t, file(Archive.dayFile(t, d)), tooLate)).push(row, rowid, time)
                case Left(why) =>
                  if kept.isEmpty then Log.w(s"archive $d: raw line kept in SQLite: $why")
                  kept += rowid
            val (rowid, time, _) = lines.last
            at = ArchiveMerge.Cursor(time, rowid)
            read += lines.size
            if lines.size < chunk then more = false
            progress(s"$d 生データ ${total + read} 行")
        // Stopped (by the OS) or not: what was read is written out, so every
        // run makes progress however short the time it is given.
        if !more then done = true
        if read > 0 then
          val targets = open.values.map(rw => rw.target(rw.finish())).toSeq
          commit(db, CommitIntent(targets, CommitIntent.Delete.Raw(from, to, max("raw_lines"), start, at, kept.toSeq)),
            Archive.RawTables.map(_.name), () =>
              val now = db.countRawPortion(from, to, max("raw_lines"), start, at)
              if now != read then throw IllegalStateException(s"raw lines of $d changed while archiving: $now != $read"))
          total += read - kept.size
          unconverted += kept.size - lateHere
      catch
        case e: Throwable =>
          open.values.foreach(_.abort())
          throw e
    // The rows that came too late for the window, sorted and merged in.
    if done && late.nonEmpty && !stop() then
      progress(s"$d 順序の遅れた ${late.size} 行")
      val rows = db.rawLinesById(late.toSeq).flatMap((rowid, time, line) =>
        Archive.parse(line, time).toOption.map((t, row) => (t, row, rowid))).sortBy((_, row, _) => ArchiveMerge.key(row))
      val open = mutable.LinkedHashMap.empty[Archive.TableDef, Rewrite]
      try
        for (t, row, rowid) <- rows do
          open.getOrElseUpdate(t, Rewrite(t, file(Archive.dayFile(t, d)), _ => throw IllegalStateException("sorted rows came late")))
            .push(row, rowid, ArchiveMerge.key(row))
        val targets = open.values.map(rw => rw.target(rw.finish())).toSeq
        commit(db, CommitIntent(targets, CommitIntent.Delete.Ids(rows.map(_._3))), Archive.RawTables.map(_.name))
        total += rows.size
      catch
        case e: Throwable =>
          open.values.foreach(_.abort())
          throw e
    DayResult(done, total, unconverted)

  /** Merges the day files of the small tables of finished months into one
    * file per month (row group by row group). The raw tables keep their day
    * files: a month of them would be too large to export or upload as one.
    */
  def mergeMonths(db: Db, today: LocalDate, stop: () => Boolean = () => false): Unit =
    val current = YearMonth.from(today)
    for t <- MonthTables if !stop() do
      val days = archivedFiles(t).filter(isDayFile)
      for (month, files) <- days.groupBy(f => YearMonth.parse(f.getName.take(7))).toSeq.sortBy(_._1) if month.isBefore(current) && !stop() do
        val rw = Rewrite(t, file(Archive.monthFile(t, month)), _ => ())
        try
          val readers = files.sortBy(_.getName).map(f => ParquetReader.open(f.getPath))
          try readers.foreach(_.rowIterator.foreach(r => rw.push(r, 0L, ArchiveMerge.key(r))))
          finally readers.foreach(r => Try(r.close()))
          val n = rw.finish()
          commit(db, CommitIntent(Seq(rw.target(n)), CommitIntent.Delete.Files(files.map(f => root.toPath.relativize(f.toPath).toString))),
            Seq(t.name))
        catch
          case e: Throwable =>
            rw.abort()
            throw e

  /** Pre-Parquet text logs (one file per session or day) go into raw_lines,
    * from where the normal compaction archives them.
    */
  def migrateLegacyLogs(db: Db, dir: File): Int =
    val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt"))
    var n = 0
    for f <- files do
      // Each chunk is inserted with the count of lines imported so far, in
      // one transaction: an import cut short resumes after the last chunk.
      val key = s"${f.getName}|${f.length}|${f.lastModified}"
      var done = db.legacyImported(key)
      Using.resource(scala.io.Source.fromFile(f, "UTF-8")) { src =>
        var last = f.lastModified()
        var seen = 0L
        src.getLines().filterNot(_.startsWith("#")).filter(_.nonEmpty).grouped(5000).foreach { chunk =>
          val timed = chunk.map { l =>
            GnssLogMerge.timeOf(l).foreach(last = _)
            (last, l)
          }
          val fresh = timed.drop(math.max(0L, done - seen).toInt)
          seen += timed.size
          if fresh.nonEmpty then
            done = seen
            db.insertLegacyChunk(fresh, key, done)
            n += fresh.size
        }
      }
      f.delete()
      db.legacyDone(key)
    n

  /** Days with rows still in SQLite that are ready to archive: over for at
    * least [[SettleMs]], so no late batch of the logger can still arrive.
    */
  def dueDays(db: Db, now: Long): List[LocalDate] =
    val today = day(now)
    db.oldestHot().map(day).toList.flatMap { first =>
      Iterator.iterate(first)(_.plusDays(1))
        .takeWhile(d => d.isBefore(today) && startOf(d.plusDays(1)) + SettleMs <= now).toList
    }

  /** Total archive size in bytes (for the data screen). */
  def sizeBytes: Long =
    def walk(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(walk).sum else f.length
    walk(root)

object ArchiveStore:
  /** Files a repair moved aside, under the archive root: `quarantine/<commit id>/<table>/<file>`. */
  val QuarantineDir = "quarantine"

  /** An unfinished commit is not resolved yet: its tables are neither read
    * nor archived until it is (by the maintenance process, asked for at
    * once, or by 「整理を修復」 when it waits for the user).
    */
  final class CommitStuck extends IllegalStateException("an interrupted archive commit cannot be finished")

  /** How a day's archiving ended: `done` (nothing of it left in SQLite but
    * what does not convert), or more is left; raw lines archived, and lines
    * that do not convert (kept in SQLite).
    */
  final case class DayResult(done: Boolean, lines: Long, unconverted: Long)

  /** Raw lines read from SQLite at a time (a few hundred bytes each: well
    * inside one 2 MB cursor window).
    */
  val ChunkLines = 4000
  /** How far out of order new rows may come (see [[ArchiveMerge.Reorder]]):
    * gyroscope and magnetometer lines stored by earlier versions are up to
    * a few seconds off.
    */
  val ReorderMs: Long = 60_000L
  /** Raw lines kept in SQLite per portion (they are excluded from its
    * deletion by rowid); a portion ends early when it reaches this.
    */
  val MaxKept = 10_000
  /** Rows later than the reorder window archived in the sorted pass of one run. */
  val MaxLate = 10_000
  /** One portion of a long day: written, verified and deleted from SQLite
    * before the next. Archived row groups before the new rows are copied as
    * stored, so a portion costs about its own rows; they are bounded so
    * that a job stopped by the OS commits its portion quickly.
    */
  val PortionMs: Long = 2 * 60_000L
  val PortionLines: Long = 2_000_000L
  /** A day is archived once it has been over this long. */
  val SettleMs: Long = 3_600_000L

  val MonthTables: Seq[Archive.TableDef] = Seq(Archive.Fixes, Archive.Motion, Archive.Battery, Archive.GnssStatus)

  /** fsync of a directory: a rename or a new entry in it is durable after
    * this. Throws: whatever depends on it must not happen.
    */
  def syncDir(d: File): Unit =
    val fd = android.system.Os.open(d.getPath, android.system.OsConstants.O_RDONLY, 0)
    try android.system.Os.fsync(fd)
    finally android.system.Os.close(fd)

  /** fsync of a file (not the database: see [[Db]]). Throws. */
  def syncFile(f: File): Unit =
    val fd = android.system.Os.open(f.getPath, android.system.OsConstants.O_RDONLY, 0)
    try android.system.Os.fsync(fd)
    finally android.system.Os.close(fd)

  /** Creates `d` (and its parents) durably: each new directory's parent is synced. */
  def ensureDir(d: File): Unit =
    if !d.isDirectory then
      Option(d.getParentFile).foreach(ensureDir)
      if !d.mkdir() && !d.isDirectory then throw java.io.IOException(s"cannot create $d")
      Option(d.getParentFile).foreach(syncDir)

  def isDayFile(f: File): Boolean = f.getName.length == "YYYY-MM-DD.parquet".length && f.getName.endsWith(".parquet")

  /** Rows per row group: about 400,000 values, so a group buffered by the
    * writer (boxed values) or decoded by the reader stays around 10 MB.
    */
  def groupRows(t: Archive.TableDef): Int = (400_000 / t.columns.size).max(2048).min(50_000)

  /** Two time-ordered iterators merged (no deduplication). */
  def mergeSorted(a: Iterator[IndexedSeq[Any]], b: Iterator[IndexedSeq[Any]]): Iterator[IndexedSeq[Any]] =
    if !a.hasNext then b
    else if !b.hasNext then a
    else
      val x = a.buffered
      val y = b.buffered
      new Iterator[IndexedSeq[Any]]:
        def hasNext = x.hasNext || y.hasNext
        def next() =
          if !y.hasNext then x.next()
          else if !x.hasNext then y.next()
          else if ArchiveMerge.key(x.head) <= ArchiveMerge.key(y.head) then x.next()
          else y.next()

  /** A lock shared by the processes (a file lock) and reentrant for the
    * threads of this one: held by each writer of a tmp file, and by the
    * rescue backup. Lock and unlock on the same thread.
    */
  private final class TmpLock(f: File):
    private val local = java.util.concurrent.locks.ReentrantLock()
    private var held: Option[(RandomAccessFile, java.nio.channels.FileLock)] = None

    def lock(): Unit =
      local.lock()
      if local.getHoldCount == 1 then
        try
          val raf = RandomAccessFile(f, "rw")
          try held = Some((raf, raf.getChannel.lock()))
          catch
            case e: Throwable =>
              Try(raf.close())
              throw e
        catch
          case e: Throwable =>
            local.unlock()
            throw e

    def unlock(): Unit =
      if local.getHoldCount == 1 then
        held.foreach((raf, l) => { Try(l.release()); Try(raf.close()) })
        held = None
      local.unlock()

  /** One table's lock, shared by the processes (a file lock) and the
    * threads of this one: shared for readers, exclusive for commits. A JVM
    * may hold one file lock per file, so this process's readers share one.
    */
  private final class TableLock(f: File):
    private val rw = java.util.concurrent.locks.ReentrantReadWriteLock()
    private var held: Option[(RandomAccessFile, java.nio.channels.FileLock)] = None
    private var readers = 0

    def read[A](body: => A): A =
      rw.readLock().lock()
      try
        synchronized {
          if readers == 0 then
            val raf = RandomAccessFile(f, "rw")
            held = Some((raf, raf.getChannel.lock(0, Long.MaxValue, true)))
          readers += 1
        }
        try body
        finally synchronized {
          readers -= 1
          if readers == 0 then
            held.foreach((raf, l) => { Try(l.release()); Try(raf.close()) })
            held = None
        }
      finally rw.readLock().unlock()

    def write[A](body: => A): A =
      rw.writeLock().lock()
      try
        Using.resource(RandomAccessFile(f, "rw")) { raf =>
          val l = raf.getChannel.lock()
          try body
          finally l.release()
        }
      finally rw.writeLock().unlock()
