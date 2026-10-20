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
  @volatile private var stallReported = false

  /** Queues `write` (of `rows` rows) for the database. Raw batches carry
    * their lines, so they can be spilled to a file.
    */
  def submit(rows: Int, raw: Boolean, what: String, lines: Seq[(Long, String)] = Nil)(write: Db => Unit): Unit =
    val now = SystemClock.uptimeMillis()
    backlog.synchronized(backlog.offer(rows, raw, now)) match
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
    if stallReported then
      stallReported = false
      LifeLog.add(LifeKind.Note, "記録の保存が再開しました")
      Alerts.clearStall()

  /** Rows given up: on disk at once, and in the lifecycle log. */
  private def lostNow(rows: Int, what: String): Unit =
    Losses.add(rows)
    LifeLog.add(LifeKind.Note, s"$rows 行を保存できず: $what（累計 ${Losses.total} 行）")

  private def note(): Unit =
    backlog.synchronized(backlog.note(System.currentTimeMillis())).foreach(n => LifeLog.add(LifeKind.Note, s"保存の遅れ: $n"))

  /** Rows waiting to be committed. */
  def pending: Long = backlog.synchronized(backlog.pending)

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
    val oldest = backlog.synchronized(backlog.oldestQueued)
    val noCommit = WriteStall.stalled(lastCommitUptime, now, threshold)
    if !stallReported && (noCommit || WriteStall.queuedTooLong(oldest, now)) then
      stallReported = true
      val detail =
        if noCommit then s"${(now - lastCommitUptime) / 60_000}分間、記録が保存されていません"
        else s"${(now - oldest.get) / 60_000}分前からの記録が保存されていません"
      LifeLog.add(LifeKind.WriteStall, s"$detail（待ち $pending 行）")
      Alerts.stall()

  /** Count of rows given up, kept on disk (shown in 端末診断). */
  object Losses:
    private def file = File(Maintenance.dir, "write-losses")
    def total: Long = Try(String(java.nio.file.Files.readAllBytes(file.toPath), UTF_8).trim.toLong).getOrElse(0L)
    def add(rows: Int): Unit = synchronized(Try(Maintenance.write(file, (total + rows).toString)))

  /** Raw-line batches the queue had no room for, in files (one per batch,
    * fsynced) until imported. An import inserts a file's lines and records
    * it in one transaction ([[Db.insertLegacyChunk]]), so a crash in
    * between does not import it twice.
    */
  object Overflow:
    private def dir = File(AshidoriApp.app.getFilesDir, "overflow")
    private var seq = 0L

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
      for f <- files.take(20) if pending() < WriteBacklog.MaxRawRows / 2 do
        try
          val db = AshidoriApp.db
          val key = s"overflow|${f.getName}"
          if db.legacyImported(key) == 0 then
            val lines = String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).linesIterator.flatMap { l =>
              val i = l.indexOf('\t')
              if i <= 0 then None else l.take(i).toLongOption.map(_ -> l.drop(i + 1))
            }.toVector
            db.insertLegacyChunk(lines, key, lines.size.max(1).toLong)
          f.delete()
          db.legacyDone(key)
        catch case NonFatal(e) => Log.w(s"overflow import of $f failed", e)
