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
  * A failed batch (SQLite refused it, or a locked database for longer than
  * [[RetryMs]]) is retried; if it still fails it is counted as lost, at once
  * on disk ([[Losses]]) and in the lifecycle log. The last-saved time moves
  * only with a commit: received is not saved.
  */
object LoggerWrites:
  private val exec = Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-writes"))
  private val backlog = WriteBacklog()
  val RetryMs: Long = 60_000L
  /** Attempts of a batch SQLite refused (not locked) before it counts as lost. */
  val Attempts = 3

  /** Wall time of the last commit (0: none in this process). */
  @volatile var lastCommitAt = 0L
  @volatile private var lastCommitUptime = SystemClock.uptimeMillis()
  // An alert raised before a restart stays until saving is seen to work
  // again in this process.
  private lazy val stall = WriteStall.Alert(WriteStall.Monitor(Alerts.restoredStall()), Alerts.StallEffects)

  /** Queues `write` (of `rows` rows) for the database. A batch that cannot
    * be saved (the queue is full, or SQLite still refuses it after the
    * retries) is kept in a file and imported later: raw batches by their
    * `lines` ([[Overflow]]), small ones by `spill` ([[SmallOverflow]]).
    * Only a batch that cannot be kept that way either is lost (counted).
    */
  def submit(rows: Int, raw: Boolean, kind: WriteKind, lines: Seq[(Long, String)] = Nil,
             spill: Option[() => Unit] = None)(write: Db => Unit): Unit =
    val now = SystemClock.uptimeMillis()
    val what = kind.label
    /** To a file instead; false if there is no way or it failed (then counted lost). */
    def keep(why: String): Boolean =
      if raw && lines.nonEmpty || spill.isDefined then
        try
          if raw && lines.nonEmpty then Overflow.spill(lines) else spill.get()
          backlog.synchronized(backlog.spill(rows))
          true
        catch case NonFatal(e) =>
          Log.w(s"spilling $rows $what failed", e)
          lostNow(rows, s"$what（$why、退避にも失敗: ${LifeLog.describe(e)}）")
          false
      else
        lostNow(rows, s"$what（$why）")
        false
    backlog.synchronized(backlog.offer(rows, raw, kind.key, now)) match
      case None =>
        keep("書き込み待ちが上限")
        note()
      case Some(id) =>
        exec.execute { () =>
          var failed = false
          var lost = false
          try
            debugFailWrite(kind)
            commit(write)
          catch
            case NonFatal(e) =>
              failed = true
              Log.w(s"saving $what failed", e)
              lost = !keep(LifeLog.describe(e))
          finally
            val waited = SystemClock.uptimeMillis() - now
            if waited > 1000 then Log.i(s"write of $rows $what took $waited ms from hand-over")
            backlog.synchronized(backlog.done(id, rows, raw, SystemClock.uptimeMillis(), lost, failed))
            note()
          if !failed then importOverflow()
        }

  /** A batch of small rows, kept in a file if it cannot be saved now.
    * `onSaved` runs on the write thread once it is committed (not when it
    * went to a file).
    */
  def small(r: SmallRecord, onSaved: () => Unit = () => ()): Unit =
    submit(r.rows, raw = false, r.kind, spill = Some(() => SmallOverflow.spill(r))) { db =>
      SmallOverflow.apply(db, r)
      onSaved()
    }

  /** Deletes [from, to) ([[Db.deleteRange]]) on the write thread, after
    * every batch queued before it; `done` gets the outcome (on that thread).
    */
  def deleteRange(from: Long, to: Long)(done: scala.util.Try[Unit] => Unit): Unit =
    exec.execute(() => done(Try(commit(_.deleteRange(from, to)))))

  private def importOverflow(): Unit =
    Overflow.importSome(() => backlog.synchronized(backlog.pending))
    SmallOverflow.importSome(() => backlog.synchronized(backlog.pending))

  def fixes(fs: Seq[Fix]): Unit =
    if fs.nonEmpty then small(SmallRecord.Fixes(fs))

  def rawLines(batch: Seq[(Long, String)]): Unit =
    submit(batch.size, raw = true, WriteKind.RawLines, batch)(_.insertRawLines(batch))

  /** Debug builds: writes of `kind` fail while `files/maintenance/debug-fail-write-<key>`
    * exists (to test the stall alert per kind).
    */
  private def debugFailWrite(kind: WriteKind): Unit =
    val app = AshidoriApp.app
    if (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
        File(Maintenance.dir, s"debug-fail-write-${kind.key}").exists then
      throw java.io.IOException(s"debug: failing writes of ${kind.key}")

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
        case e: SQLiteException if !e.isInstanceOf[SQLiteDatabaseLockedException] && failures + 1 < Attempts =>
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
        Log.w("counting lost rows on disk failed", e)
        LifeLog.add(LifeKind.Note, s"失われた行数をファイルに記録できず（$rows 行）: ${LifeLog.describe(e)}")
    }

  /** Raw-line batches the queue had no room for, in files (one per batch,
    * fsynced) until imported. An import inserts a file's lines and records
    * it in one transaction ([[Db.insertLegacyChunk]]), so a crash in
    * between does not import it twice.
    */
  object Overflow:
    def dir: File = File(AshidoriApp.app.getFilesDir, "overflow")
    private var seq = 0L
    /** Held by an import of a file, and by a backup while it takes its copy. */
    val lock = java.util.concurrent.locks.ReentrantLock()

    def spill(lines: Seq[(Long, String)]): Unit = synchronized {
      ArchiveStore.ensureDir(dir)
      seq += 1
      val sb = StringBuilder()
      for (t, l) <- lines do sb.append(t).append('\t').append(l).append('\n')
      // Completed only by the rename (never written again): see OverflowFiles.
      OverflowFiles.write(dir.toPath, f"raw-${System.currentTimeMillis()}%013d-$seq%06d.txt", sb.toString.getBytes(UTF_8),
        d => ArchiveStore.syncDir(d.toFile))
    }

    /** Imports overflow files while the queue is short (on the write thread). */
    def importSome(pending: () => Long): Unit =
      val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(OverflowFiles.Completed)).sortBy(_.getName)
      // A backup holds the lock while it copies the database: the import waits
      // for the next commit then, the logger's writes never wait for it.
      for f <- files.take(20) if pending() < WriteBacklog.MaxRawRows / 2 && lock.tryLock() do
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
        catch case NonFatal(e) => Log.w(s"overflow import of $f failed", e)
        finally lock.unlock()
