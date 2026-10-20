package net.sksat.ashidori

import android.database.sqlite.{SQLiteDatabaseLockedException, SQLiteException}
import android.os.SystemClock
import java.util.concurrent.Executors
import scala.util.control.NonFatal
import ashidori.core.*

/** The logger's own path to SQLite: every recorded row (positions, motion
  * windows, battery, GNSS status, raw lines, detail sessions) is committed
  * on this thread, which nothing else uses, so it never waits behind UI
  * reads, exports or archiving.
  *
  * What is queued here is bounded ([[WriteBacklog]]): a batch over the cap
  * is dropped and noted in the lifecycle log rather than growing memory
  * until the process dies with everything queued. A batch that meets a
  * locked database (another process holding the write lock) is retried for
  * up to [[RetryMs]].
  *
  * The time of the last commit is what the stall check and 端末診断 use:
  * received is not saved.
  */
object LoggerWrites:
  private val exec = Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-writes"))
  private val backlog = WriteBacklog()
  val RetryMs: Long = 60_000L

  /** Wall time and uptime of the last commit (0: none in this process). */
  @volatile var lastCommitAt = 0L
  @volatile private var lastCommitUptime = SystemClock.uptimeMillis()
  @volatile private var stallReported = false

  /** Queues `write` (of `rows` rows) for the database. */
  def submit(rows: Int, raw: Boolean, what: String)(write: Db => Unit): Unit =
    val ok = backlog.synchronized(backlog.offer(rows, raw))
    if !ok then
      Log.w(s"write queue full: dropped $rows $what")
      note()
    else
      val queued = SystemClock.uptimeMillis()
      exec.execute { () =>
        var lost = false
        try commit(write)
        catch
          case NonFatal(e) =>
            lost = true
            Log.w(s"saving $what failed", e)
            LifeLog.add(LifeKind.Note, s"保存に失敗（$what ${rows} 行）: ${LifeLog.describe(e)}")
        finally
          backlog.synchronized(backlog.done(rows, raw, SystemClock.uptimeMillis() - queued, lost))
          note()
      }

  def fixes(fs: Seq[Fix]): Unit =
    if fs.nonEmpty then
      submit(fs.size, raw = false, "位置")(_.insertFixes(fs))

  private def commit(write: Db => Unit): Unit =
    val start = SystemClock.uptimeMillis()
    var pause = 200L
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
    lastCommitAt = System.currentTimeMillis()
    lastCommitUptime = SystemClock.uptimeMillis()
    if stallReported then
      stallReported = false
      LifeLog.add(LifeKind.Note, "記録の保存が再開しました")
      Alerts.clearStall()

  private def note(): Unit =
    backlog.synchronized(backlog.note(System.currentTimeMillis())).foreach(n => LifeLog.add(LifeKind.Note, s"保存の遅れ: $n"))

  /** Rows waiting to be committed. */
  def pending: Long = backlog.synchronized(backlog.pending)

  /** The logger (re)started: the stall clock starts now. */
  def markStart(): Unit =
    lastCommitUptime = math.max(lastCommitUptime, SystemClock.uptimeMillis())

  /** Called now and then while logging: reports (once) when nothing was
    * committed for longer than the plan explains ([[WriteStall]]).
    */
  def checkStall(plan: Option[SamplingPlan]): Unit =
    val threshold = WriteStall.thresholdMs(plan.map(_.intervalMs).getOrElse(0L), plan.map(_.maxBatchDelayMs).getOrElse(0L))
    if !stallReported && WriteStall.stalled(lastCommitUptime, SystemClock.uptimeMillis(), threshold) then
      stallReported = true
      val mins = (SystemClock.uptimeMillis() - lastCommitUptime) / 60_000
      LifeLog.add(LifeKind.WriteStall, s"${mins}分間、記録が保存されていません（待ち $pending 行）")
      Alerts.stall()
