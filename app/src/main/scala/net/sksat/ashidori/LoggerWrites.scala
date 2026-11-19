package net.sksat.ashidori

import android.database.sqlite.{SQLiteDatabaseLockedException, SQLiteException}
import android.os.SystemClock
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import scala.util.Try
import scala.util.control.NonFatal
import ashidori.core.*

/** The logger's own path to SQLite: every recorded row (positions, motion
  * windows, battery, GNSS status, raw lines, detail sessions) is committed
  * on this thread, which nothing else uses, so it never waits behind UI
  * reads, exports or archiving.
  *
  * What is queued here is bounded ([[WriteBacklog]]). A batch of raw lines
  * over the cap is written to a file (fsynced) instead of being queued, and
  * imported once the queue has drained: memory stays bounded and nothing is
  * dropped. The other rows are tiny and capped at days of positions.
  *
  * Every batch has an overflow file of its own, named when it is handed
  * over ([[OverflowFiles.Batch]]): written at most once whatever the retries.
  * A batch goes to it instead of SQLite (plan C stage 2, [[EmergencyPath]]):
  * in the emergency state ([[Storage.emergency]]: F below E before the
  * write, SQLITE_FULL, an overflow write without room), where R is released
  * first and SQLite is not tried again; when SQLite refused it for another
  * reason after the retries (a locked database for longer than [[RetryMs]]);
  * and when the queue is full. Only a batch that cannot be kept that way
  * either is lost, counted at once on disk ([[Losses]]) and in the lifecycle
  * log. The last-saved time moves only with a commit: received is not saved.
  */
object LoggerWrites:
  private val exec = Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-writes"))
  private val backlog = WriteBacklog()
  val RetryMs: Long = 60_000L
  /** Attempts of a batch SQLite refused (not locked) before it goes to a file. */
  val Attempts = 3

  /** Wall time of the last commit (0: none in this process). */
  @volatile var lastCommitAt = 0L
  @volatile private var lastCommitUptime = SystemClock.uptimeMillis()
  // An alert raised before a restart stays until saving is seen to work
  // again in this process.
  private lazy val stall = WriteStall.Alert(WriteStall.Monitor(Alerts.restoredStall()), Alerts.StallEffects)

  @volatile private var lastSubmitUptime = Long.MinValue / 2
  /** Batches are coming (the logger is on and running, or one was handed
    * over in the last minute): recovery from the emergency state waits for
    * one saved in SQLite ([[EmergencyReserve.tick]]).
    */
  def logging: Boolean =
    Settings.loggingEnabled && LoggerState.running || SystemClock.uptimeMillis() - lastSubmitUptime < 60_000L

  /** Rows of this process: committed to SQLite, kept in overflow files. */
  private val savedRows = java.util.concurrent.atomic.AtomicLong()
  private val spilledRows = java.util.concurrent.atomic.AtomicLong()

  /** Queues `write` (of `rows` rows) for the database; its
    * overflow file (`file`). See the object's comment for where it ends up.
    */
  def submit(rows: Int, raw: Boolean, kind: WriteKind, file: OverflowFiles.Batch)(write: Db => Unit): Unit =
    val now = SystemClock.uptimeMillis()
    lastSubmitUptime = now
    val what = kind.label
    def ops: EmergencyPath.Ops = new EmergencyPath.Ops:
      def commit(): Unit =
        try
          debugFailWrite(kind)
          LoggerWrites.commit(write)
        catch case e: Exception =>
          Log.w(s"saving $rows $what failed", e)
          throw e
      def spill(): Unit =
        file.write()
        spilledRows.addAndGet(rows)
        backlog.synchronized(backlog.spill(rows))
      def lost(why: String): Unit = lostNow(rows, s"$what（$why）")
      def full(e: Throwable): Boolean = NoSpace.full(e)
      def noSpace(e: Throwable): Boolean = NoSpace.noSpace(e)
      def describe(e: Throwable): String = LifeLog.describe(e)
    backlog.synchronized(backlog.offer(rows, raw, kind.key, now)) match
      case None =>
        // The queue is full: to the file at once (on the caller's thread).
        Log.w(s"write queue full; spilling $rows $what")
        val r = Try(EmergencyPath.spill(Storage.emergency, ops, "書き込み待ちが上限"))
        r.failed.foreach(e => lostNow(rows, s"$what（書き込み待ちが上限、退避にも失敗: ${LifeLog.describe(e)}）"))
        note()
      case Some(id) =>
        exec.execute { () =>
          var outcome = EmergencyPath.Outcome.Lost
          try outcome = EmergencyPath.persist(Storage.emergency, ops)
          catch case NonFatal(e) =>
            // The path itself failed (not expected): the file still, else lost.
            Log.w(s"saving $what failed", e)
            outcome =
              try { ops.spill(); EmergencyPath.Outcome.Spilled }
              catch case NonFatal(e2) =>
                lostNow(rows, s"$what（${LifeLog.describe(e)}、退避にも失敗: ${LifeLog.describe(e2)}）")
                EmergencyPath.Outcome.Lost
          finally
            val waited = SystemClock.uptimeMillis() - now
            if waited > 1000 then Log.i(s"write of $rows $what took $waited ms from hand-over")
            outcome match
              case EmergencyPath.Outcome.Saved =>
                savedRows.addAndGet(rows)
                // Rows lost while their count could not be written: written now.
                if Losses.uncounted > 0 then Losses.flush()
              case _ => ()
            backlog.synchronized(backlog.done(id, rows, raw, SystemClock.uptimeMillis(),
              lost = outcome == EmergencyPath.Outcome.Lost, failed = outcome != EmergencyPath.Outcome.Saved))
            note()
          if outcome == EmergencyPath.Outcome.Saved then importOverflow()
        }

  /** A batch of small rows, kept in a file if it cannot be saved now.
    * `onSaved` runs on the write thread once it is committed (not when it
    * went to a file).
    */
  def small(r: SmallRecord, onSaved: () => Unit = () => ()): Unit =
    submit(r.rows, raw = false, r.kind, SmallOverflow.batch(r)) { db =>
      SmallOverflow.apply(db, r)
      onSaved()
    }

  /** Deletes [from, to) ([[Db.deleteRange]]) on the write thread, after
    * every batch queued before it; `done` gets the outcome (on that thread).
    */
  def deleteRange(from: Long, to: Long)(done: scala.util.Try[Unit] => Unit): Unit =
    exec.execute(() => done(Try(commit(_.deleteRange(from, to)))))

  /** Imports overflow files (on the write thread), never in the emergency
    * state; new batches first: after each file it yields to any batch
    * waiting ([[Overflow.importSome]]).
    */
  private def importOverflow(): Unit =
    if !Storage.emergency.active then
      Overflow.importSome(() => backlog.synchronized(backlog.pending))
      SmallOverflow.importSome(() => backlog.synchronized(backlog.pending))

  /** The emergency state ended: the overflow files are imported (behind any batch queued). */
  def resumeImport(): Unit = exec.execute(() => importOverflow())

  def fixes(fs: Seq[Fix]): Unit =
    if fs.nonEmpty then small(SmallRecord.Fixes(fs))

  def rawLines(batch: Seq[(Long, String)]): Unit =
    submit(batch.size, raw = true, WriteKind.RawLines, Overflow.batch(batch))(_.insertRawLines(batch))

  /** Debug builds: writes of `kind` fail while `files/maintenance/debug-fail-write-<key>`
    * exists (to test the stall alert per kind).
    */
  private def debugFailWrite(kind: WriteKind): Unit =
    val app = AshidoriApp.app
    if (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 then
      Db.debugTx = true
      if File(Maintenance.dir, s"debug-fail-write-${kind.key}").exists then
        throw java.io.IOException(s"debug: failing writes of ${kind.key}")

  /** One batch in SQLite: a locked database is waited for (up to
    * [[RetryMs]]), another refusal tried [[Attempts]] times. SQLITE_FULL is
    * never tried again here (it would use up what R gave back): it goes out
    * at once, its transaction ended by [[Db]], for the emergency path.
    */
  private def commit(write: Db => Unit): Unit =
    val start = SystemClock.uptimeMillis()
    var pause = 200L
    var failures = 0
    var done = false
    while !done do
      try
        write(AshidoriApp.db)
        done = true
      catch
        case e: SQLiteDatabaseLockedException if SystemClock.uptimeMillis() - start < RetryMs =>
          Log.w(s"database locked; retrying in $pause ms")
          Thread.sleep(pause)
          pause = math.min(pause * 2, 5_000L)
        case e: SQLiteException if !e.isInstanceOf[SQLiteDatabaseLockedException] && !NoSpace.full(e) &&
            failures + 1 < Attempts && Storage.free() >= Emergency.E =>
          failures += 1
          Log.w(s"write failed (attempt $failures); retrying", e)
          Thread.sleep(500L * failures)
    lastCommitAt = System.currentTimeMillis()
    lastCommitUptime = SystemClock.uptimeMillis()

  /** Rows given up: on disk at once, and in the lifecycle log. */
  private def lostNow(rows: Int, what: String): Unit =
    Losses.add(rows)
    LifeLog.add(LifeKind.Note, s"$rows 行を保存できず: $what（累計 ${Losses.total} 行）")

  private def note(): Unit =
    backlog.synchronized(backlog.note(System.currentTimeMillis())).foreach(n => LifeLog.add(LifeKind.Note, s"保存の遅れ: $n"))

  /** Rows waiting to be committed. */
  def pending: Long = backlog.synchronized(backlog.pending)

  /** Per kind: its label, how long ago it was last saved in this process,
    * and for how long it has not been (ms), for 端末診断.
    */
  def progress(kinds: Seq[WriteKind]): Seq[(String, Option[Long], Option[Long])] =
    val now = SystemClock.uptimeMillis()
    backlog.synchronized {
      val unsaved = backlog.unsavedSince
      kinds.map(k => (k.label, backlog.lastSaved(k.key).map(now - _), unsaved.get(k.key).map(now - _)))
    }

  /** The logger (re)started: the stall clock starts now, and lines left in
    * overflow files are imported.
    */
  def markStart(): Unit =
    lastCommitUptime = math.max(lastCommitUptime, SystemClock.uptimeMillis())
    exec.execute(() => importOverflow())

  /** Called now and then while logging: reports (once) when nothing was
    * committed for longer than the plan explains, or when a batch has been
    * waiting too long (a position stall that battery rows saved meanwhile
    * would hide); see [[WriteStall]].
    */
  def checkStall(plan: Option[SamplingPlan]): Unit =
    val threshold = WriteStall.thresholdMs(plan.map(_.intervalMs).getOrElse(0L), plan.map(_.maxBatchDelayMs).getOrElse(0L))
    val now = SystemClock.uptimeMillis()
    val noCommit = WriteStall.stalled(lastCommitUptime, now, threshold)
    // Per kind: a position batch that failed or waits stays visible however
    // many battery rows are saved meanwhile. Cleared only once every kind
    // it names is saved again with nothing of it unsaved (not by any
    // write); one check at a time, a failed notification or save tried
    // again (WriteStall.Alert.checkBacklog).
    stall.checkBacklog(backlog, noCommit, now) { (up, oldest) =>
      if up then
        val detail =
          if noCommit then s"${(now - lastCommitUptime) / 60_000}分間、記録が保存されていません"
          else s"${WriteKind.label(oldest.get._1)}が${(now - oldest.get._2) / 60_000}分前から保存されていません"
        LifeLog.add(LifeKind.WriteStall, s"$detail（待ち $pending 行）")
      else LifeLog.add(LifeKind.Note, "記録の保存が再開しました")
    }

  /** Count of rows given up, kept on disk (shown in 端末診断). */
  object Losses:
    private def file = File(Maintenance.dir, "write-losses")
    /** Rows lost that could not be counted on disk (shown as well). */
    @volatile var uncounted = 0L
    def total: Long = Try(String(java.nio.file.Files.readAllBytes(file.toPath), UTF_8).trim.toLong).getOrElse(0L)
    def add(rows: Int): Unit = synchronized {
      try
        Maintenance.write(file, (total + uncounted + rows).toString)
        uncounted = 0
      catch case NonFatal(e) =>
        uncounted += rows
        Log.w(s"counting lost rows on disk failed: ${LifeLog.describe(e)}")
        LifeLog.add(LifeKind.Note, s"失われた行数をファイルに記録できず（$rows 行）: ${LifeLog.describe(e)}")
    }
    /** Writes the rows not counted on disk yet (when there is room again). */
    def flush(): Unit = synchronized {
      if uncounted > 0 then
        try
          Maintenance.write(file, (total + uncounted).toString)
          LifeLog.add(LifeKind.Note, s"失われた行数を記録（$uncounted 行、累計 $total 行）")
          uncounted = 0
        catch case NonFatal(e) => Log.w(s"counting lost rows on disk failed again: ${LifeLog.describe(e)}")
    }


  /** Names the overflow files of this process apart from any other's
    * (start time and pid), so a batch's name is its own across restarts.
    */
  private[ashidori] lazy val processTag: String =
    java.lang.Long.toString(System.currentTimeMillis(), 36) + "p" + android.os.Process.myPid()

  /** Whether the import may take another file: the first while the queue
    * is short, the next only while no batch waits (new records first), and
    * none in the emergency state.
    */
  private[ashidori] def importMayGo(taken: Int, pending: () => Long, cap: Long): Boolean =
    !Storage.emergency.active && (if taken == 0 then pending() < cap / 2 else pending() == 0)

  /** Raw-line batches kept in files (one per batch, fsynced) until imported.
    * An import inserts a file's lines and records it in one transaction
    * ([[Db.insertLegacyChunk]]), so a crash in between does not import it twice.
    */
  object Overflow:
    def dir: File = File(AshidoriApp.app.getFilesDir, "overflow")
    private val seq = java.util.concurrent.atomic.AtomicLong()
    /** Held by an import of a file, and by a backup while it takes its copy. */
    val lock = java.util.concurrent.locks.ReentrantLock()

    /** The overflow file of a batch of raw lines (named now, written when needed). */
    def batch(lines: Seq[(Long, String)]): OverflowFiles.Batch =
      val name = f"raw-${System.currentTimeMillis()}%013d-$processTag-${seq.incrementAndGet()}%06d${OverflowFiles.Completed}"
      OverflowFiles.Batch(dir.toPath, name, () => {
        val sb = StringBuilder()
        for (t, l) <- lines do sb.append(t).append('\t').append(l).append('\n')
        sb.toString.getBytes(UTF_8)
      }, d => ArchiveStore.syncDir(d.toFile), crash = debugCrash, prepare = () => ArchiveStore.ensureDir(dir))

    /** Writes `lines` to an overflow file now (debug builds' SPILL). */
    def spill(lines: Seq[(Long, String)]): Unit = batch(lines).write()

    /** Imports overflow files (on the write thread): see [[importMayGo]]. */
    def importSome(pending: () => Long): Unit =
      val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(OverflowFiles.Completed)).sortBy(_.getName)
      var taken = 0
      // A backup holds the lock while it copies the database: the import waits
      // for the next commit then, the logger's writes never wait for it.
      for f <- files.take(20) if importMayGo(taken, pending, WriteBacklog.MaxRawRows) && lock.tryLock() do
        taken += 1
        try
          val db = AshidoriApp.db
          val key = s"overflow|${f.getName}"
          OverflowImport.run(new OverflowImport.Ops:
            def marked = db.legacyImported(key) != 0
            def insertMarked(): Unit =
              val lines = String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).linesIterator.flatMap { l =>
                val i = l.indexOf('\t')
                if i <= 0 then None else l.take(i).toLongOption.map(_ -> l.drop(i + 1))
              }.toVector
              db.insertLegacyChunk(lines, key, lines.size.max(1).toLong)
            def deleteFile(): Boolean = f.delete() || !f.exists
            def syncDir(): Unit = ArchiveStore.syncDir(dir)
            def unmark(): Unit = db.legacyDone(key)
          ) match
            case OverflowImport.Result.Kept => Log.w(s"overflow $f imported but not deleted; kept marked")
            case _ => ()
        catch case NonFatal(e) =>
          Log.w(s"overflow import of $f failed", e)
          Storage.noticeFailure(e, "退避ファイルの取り込み")
        finally lock.unlock()

  /** Debug builds: the process killed after an overflow file's write, fsync or rename ([[Storage.debugKill]]). */
  private[ashidori] val debugCrash: String => Unit = p => Storage.debugKill(s"overflow-$p")

  /** Counts for 端末診断: rows saved to SQLite and kept in overflow files by
    * this process, overflow files waiting to be imported, rows waiting (not
    * yet persisted), rows given up (and those not counted on disk).
    */
  final case class Counts(saved: Long, spilled: Long, waitingFiles: Int, pending: Long, lost: Long, uncounted: Long)
  def counts(): Counts =
    val waiting = Option(Overflow.dir.listFiles()).toSeq.flatten.count(f => OverflowFiles.inBackup(f.getName).contains(false))
    Counts(savedRows.get, spilledRows.get, waiting, pending, Losses.total, Losses.uncounted)
