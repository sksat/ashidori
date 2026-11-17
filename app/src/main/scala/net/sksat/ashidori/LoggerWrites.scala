package net.sksat.ashidori

import android.database.sqlite.{SQLiteDatabaseLockedException, SQLiteException}
import android.os.SystemClock
import java.io.{File, FileOutputStream}
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
  private lazy val stall = WriteStall.Monitor(Alerts.restoredStall())
  // The last change of the alert failed and is being tried again (logged once).
  @volatile private var stallRetry = false

  /** Queues `write` (of `rows` rows) for the database. Raw batches carry
    * their lines, so they can be spilled to a file.
    */
  def submit(rows: Int, raw: Boolean, what: String, lines: Seq[(Long, String)] = Nil)(write: Db => Unit): Unit =
    val now = SystemClock.uptimeMillis()
    backlog.synchronized(backlog.offer(rows, raw, what, now)) match
      case None =>
        if raw && lines.nonEmpty then
          // Over the cap: to a file, imported later (Overflow).
          try
            Overflow.spill(lines)
            backlog.synchronized(backlog.spill(lines.size))
          catch case NonFatal(e) =>
            Log.w(s"spilling $rows $what failed", e)
            lostNow(rows, s"$what（退避にも失敗: ${LifeLog.describe(e)}）")
        else lostNow(rows, s"$what（書き込み待ちが上限）")
        note()
      case Some(id) =>
        exec.execute { () =>
          var lost = false
          try commit(write)
          catch
            case NonFatal(e) =>
              lost = true
              Log.w(s"saving $what failed", e)
              lostNow(rows, s"$what: ${LifeLog.describe(e)}")
          finally
            val waited = SystemClock.uptimeMillis() - now
            if waited > 1000 then Log.i(s"write of $rows $what took $waited ms from hand-over")
            backlog.synchronized(backlog.done(id, rows, raw, SystemClock.uptimeMillis(), lost))
            note()
          if !lost then Overflow.importSome(() => backlog.synchronized(backlog.pending))
        }

  def fixes(fs: Seq[Fix]): Unit =
    if fs.nonEmpty then submit(fs.size, raw = false, "位置")(_.insertFixes(fs))

  def rawLines(batch: Seq[(Long, String)]): Unit =
    submit(batch.size, raw = true, "詳細ログ", batch)(_.insertRawLines(batch))

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

  /** Per kind (位置, 動き, 詳細ログ, ...): how long ago it was last saved in
    * this process, and for how long it has not been (ms), for 端末診断.
    */
  def progress(kinds: Seq[String]): Seq[(String, Option[Long], Option[Long])] =
    val now = SystemClock.uptimeMillis()
    backlog.synchronized {
      val unsaved = backlog.unsavedSince
      kinds.map(k => (k, backlog.lastSaved(k).map(now - _), unsaved.get(k).map(now - _)))
    }

  /** The logger (re)started: the stall clock starts now, and lines left in
    * overflow files are imported.
    */
  def markStart(): Unit =
    lastCommitUptime = math.max(lastCommitUptime, SystemClock.uptimeMillis())
    exec.execute(() => Overflow.importSome(() => backlog.synchronized(backlog.pending)))

  /** Called now and then while logging: reports (once) when nothing was
    * committed for longer than the plan explains, or when a batch has been
    * waiting too long (a position stall that battery rows saved meanwhile
    * would hide); see [[WriteStall]].
    */
  def checkStall(plan: Option[SamplingPlan]): Unit =
    val threshold = WriteStall.thresholdMs(plan.map(_.intervalMs).getOrElse(0L), plan.map(_.maxBatchDelayMs).getOrElse(0L))
    val now = SystemClock.uptimeMillis()
    // Per kind: a position batch that failed or waits stays visible however
    // many battery rows are saved meanwhile.
    val (oldest, unsaved, saved) = backlog.synchronized((backlog.oldestUnsaved, backlog.unsavedSince.keySet, backlog.savedKinds))
    val noCommit = WriteStall.stalled(lastCommitUptime, now, threshold)
    // Cleared only once every kind is saved again (not by any write).
    stall.synchronized(stall.update(noCommit, WriteStall.queuedTooLong(oldest.map(_._2), now), unsaved, saved)) match
      case Some(true) =>
        val detail =
          if noCommit then s"${(now - lastCommitUptime) / 60_000}分間、記録が保存されていません"
          else s"${oldest.get._1}が${(now - oldest.get._2) / 60_000}分前から保存されていません"
        if !stallRetry then LifeLog.add(LifeKind.WriteStall, s"$detail（待ち $pending 行）")
        stallRetry = !Alerts.stall(unsaved)
        if stallRetry then stall.synchronized(stall.failed())
      case Some(false) =>
        if !stallRetry then LifeLog.add(LifeKind.Note, "記録の保存が再開しました")
        stallRetry = !Alerts.clearStall()
        if stallRetry then stall.synchronized(stall.failed())
      case None => ()

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
      val f = File(dir, f"raw-${System.currentTimeMillis()}%013d-$seq%06d.txt")
      val tmp = File(f.getPath + ".tmp")
      val out = FileOutputStream(tmp)
      try
        val sb = StringBuilder()
        for (t, l) <- lines do sb.append(t).append('\t').append(l).append('\n')
        out.write(sb.toString.getBytes(UTF_8))
        out.getFD.sync()
      finally out.close()
      if !tmp.renameTo(f) then throw java.io.IOException(s"rename to $f failed")
      ArchiveStore.syncDir(dir)
    }

    /** Imports overflow files while the queue is short (on the write thread). */
    def importSome(pending: () => Long): Unit =
      val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(_.getName)
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
