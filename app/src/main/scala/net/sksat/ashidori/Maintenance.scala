package net.sksat.ashidori

import android.app.job.{JobInfo, JobParameters, JobScheduler, JobService}
import android.content.{ComponentName, Context}
import android.os.{PersistableBundle, Process, SystemClock}
import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try
import scala.util.control.NonFatal
import ashidori.core.*

/** Archive maintenance (migration of old text logs, archiving finished days,
  * merging months) runs in its own process, `:maintenance`, as a JobService:
  * an OutOfMemoryError or a crash there cannot take the logger down, and its
  * memory limit is its own. The two processes share only files and the
  * SQLite database (WAL; each process has its own connections, and
  * maintenance keeps its write transactions short).
  *
  * When: not at app start. While charging and idle, or else from 30 minutes
  * after the main process started (time to make a backup first), or at once
  * when the user asks (「記録を再整理」). A job runs for at most
  * [[BudgetMs]] and is rescheduled if there is more to do.
  *
  * State shared between the processes lives in files under
  * `files/maintenance/`: the attempt record of each day ([[CompactionGuard]]),
  * the progress line, and the crash-loop switch.
  */
object Maintenance:
  private val JobIdle = 0x7a5d0001
  private val JobDelayed = 0x7a5d0002
  private val JobManual = 0x7a5d0003
  private val JobRepair = 0x7a5d0004
  val MinUptimeMs: Long = 30 * 60_000L
  /** Below JobScheduler's 10 minutes, so a job ends on its own. */
  val BudgetMs: Long = 8 * 60_000L

  private def ctx: Context = AshidoriApp.app
  def dir: File = File(ctx.getFilesDir, "maintenance").tap(_.mkdirs())

  extension (f: File) private def tap(g: File => Unit): File = { g(f); f }

  private def job(id: Int): JobInfo.Builder =
    JobInfo.Builder(id, ComponentName(ctx, classOf[ArchiveJobService]))

  /** Queues the automatic runs (idempotent: a pending job is kept). */
  def schedule(c: Context): Unit =
    if SafeMode.maintenanceStopped || AshidoriApp.newerDatabase.isDefined then return
    val js = c.getSystemService(classOf[JobScheduler])
    if js.getPendingJob(JobIdle) == null then
      js.schedule(job(JobIdle).setRequiresCharging(true).setRequiresDeviceIdle(true).build())
    if js.getPendingJob(JobDelayed) == null then
      val wait = math.max(0L, MinUptimeMs - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime))
      js.schedule(job(JobDelayed).setMinimumLatency(wait).setOverrideDeadline(wait + 6 * 3_600_000L).build())

  /** Runs now, also for days skipped after failures; clears the crash-loop
    * switch (the user asked for it).
    */
  def runNow(c: Context): Unit =
    SafeMode.resumeMaintenance()
    val extras = PersistableBundle()
    extras.putBoolean("manual", true)
    c.getSystemService(classOf[JobScheduler]).schedule(job(JobManual).setExtras(extras).setOverrideDeadline(0).build())
    Progress.set("開始待ち")

  @volatile private var repairAsked = 0L

  /** An unfinished commit cannot be finished (seen in the main process):
    * the maintenance process resolves it now where it can do so safely
    * ([[ArchiveStore.recover]]), not 30 minutes later. At most once an hour
    * from one process.
    */
  def requestRepair(): Unit =
    val now = SystemClock.elapsedRealtime()
    if !SafeMode.maintenanceStopped && (repairAsked == 0L || now - repairAsked > 3_600_000L) then
      repairAsked = now
      Try {
        val js = ctx.getSystemService(classOf[JobScheduler])
        if js.getPendingJob(JobRepair) == null then
          val extras = PersistableBundle()
          extras.putString("repair", "auto")
          js.schedule(job(JobRepair).setExtras(extras).setOverrideDeadline(0).build())
      }.failed.foreach(e => Log.w("scheduling the archive repair failed", e))

  /** 「整理を修復」: resolves an unfinished commit table by table,
    * quarantining files that do not verify ([[CommitRepair]]).
    */
  def repairNow(c: Context): Unit =
    val extras = PersistableBundle()
    extras.putString("repair", "manual")
    extras.putBoolean("manual", true)
    c.getSystemService(classOf[JobScheduler]).schedule(job(JobRepair).setExtras(extras).setOverrideDeadline(0).build())
    Progress.set("修復の開始待ち")

  /** Runs a repair (auto or manual, see above). Call in the maintenance
    * process, off the main thread.
    */
  def repair(manual: Boolean): Unit =
    if AshidoriApp.newerDatabase.isDefined then return
    if !running.compareAndSet(false, true) then return
    try
      val db = AshidoriApp.db
      val archive = AshidoriApp.archive
      Progress.set(if manual then "修復を開始しました" else "未完了の整理を確認しています")
      val result =
        try
          if manual then
            val repaired = archive.repairNow(db, l => Progress.set(l))
            // Files it quarantined are no longer read: the 「訪れた日」 index
            // may hold stays of them (emptied and built again).
            if repaired then VisitDays.markStale("repair")
            if !repaired then "未完了の整理はありません"
            else if Attention.all().exists(!_.seen) then "修復しました（隔離したファイルの行は復元していません）"
            else "修復しました"
          else
            val had = archive.commitPending
            archive.recover(db, progress = l => Progress.set(l))
            if had then "中断された整理を解決しました" else "未完了の整理はありません"
        catch
          case _: ArchiveStore.CommitStuck => "自動では解決できません（「整理を修復」で解決）"
          case NonFatal(e) =>
            Log.w("archive repair failed", e)
            LifeLog.add(LifeKind.ArchiveRepair, s"整理の修復に失敗（記録は端末内に保持）: ${LifeLog.describe(e)}")
            s"修復できませんでした: ${LifeLog.describe(e)}"
      Progress.set(result, running = false)
      // Archiving stopped while the commit was unfinished: queued again.
      if !archive.commitPending then schedule(ctx)
    finally running.set(false)

  // --- progress (written by the maintenance process, shown by the UI) ---------------------

  object Progress:
    private def file = File(dir, "progress")
    /** Running jobs refresh it at least this often. */
    val FreshMs = 15_000L

    @volatile private var lastSet = 0L

    /** At most once a second while running (it is written durably). */
    def set(line: String, running: Boolean = true): Unit =
      val now = System.currentTimeMillis()
      if !running || now - lastSet >= 1000 then
        lastSet = now
        Try(write(file, s"$now\t${if running then 1 else 0}\t$line"))

    /** (when, running, line); running only while recently refreshed. */
    def get(): Option[(Long, Boolean, String)] =
      Try(String(java.nio.file.Files.readAllBytes(file.toPath), UTF_8)).toOption.flatMap { s =>
        s.split('\t') match
          case Array(t, r, line*) =>
            t.toLongOption.map(time => (time, r == "1" && System.currentTimeMillis() - time < FreshMs, line.mkString("\t")))
          case _ => None
      }

  /** Writes `text` to `f` atomically and durably (survives the process
    * dying right after).
    */
  def write(f: File, text: String): Unit =
    val tmp = File(f.getPath + ".tmp")
    val out = FileOutputStream(tmp)
    try
      out.write(text.getBytes(UTF_8))
      out.getFD.sync()
    finally out.close()
    if !tmp.renameTo(f) then throw java.io.IOException(s"rename to $f failed")
    ArchiveStore.syncDir(f.getParentFile)

  /** The app build: changes with every install or update. */
  def build(c: Context = ctx): Long =
    c.getPackageManager.getPackageInfo(c.getPackageName, 0).lastUpdateTime

  // --- per-day attempts ------------------------------------------------------------------

  object Guard:
    private def file(d: LocalDate) = File(dir, s"day-$d")

    def get(d: LocalDate): Option[CompactionGuard.State] =
      Try(String(java.nio.file.Files.readAllBytes(file(d).toPath), UTF_8)).toOption.flatMap(CompactionGuard.decode)

    def put(d: LocalDate, s: CompactionGuard.State): Unit = write(file(d), CompactionGuard.encode(s))

    def clear(d: LocalDate): Unit = file(d).delete()

    /** Days held back after failures (for 端末診断). */
    def skipped(): Seq[LocalDate] =
      Option(dir.listFiles()).toSeq.flatten.map(_.getName).filter(n => n.startsWith("day-") && !n.endsWith(".tmp"))
        .flatMap(n => Try(LocalDate.parse(n.drop(4))).toOption)
        .filter(d => get(d).exists(_.attempts >= CompactionGuard.MaxAttempts)).sorted

  /** Days whose archiving finished (what is left in SQLite does not
    * convert): their files are complete, for the upload.
    */
  object Complete:
    private def file(d: LocalDate) = File(dir, s"complete-$d")
    def mark(d: LocalDate): Unit = Try(write(file(d), "1"))
    def clear(d: LocalDate): Unit = file(d).delete()
    def is(d: LocalDate): Boolean = file(d).exists

  // --- crash-loop switch -----------------------------------------------------------------

  /** Set when the main process crashed repeatedly ([[CrashLoop]]): neither
    * maintenance nor sync runs on its own until the app is updated, or the
    * user resumes it (each on its own: 「記録を再整理」, 「同期を再開」 in
    * 端末診断). Jobs already queued are cancelled, and check it again when
    * they start.
    */
  object SafeMode:
    private def maintenanceFile = File(dir, "safe-mode")
    private def syncFile = File(dir, "safe-mode-sync")
    private def resetFile = File(dir, "safe-mode-reset")
    private def setIn(f: File): Boolean =
      Try(String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).trim.toLong).toOption.contains(build())

    def maintenanceStopped: Boolean = setIn(maintenanceFile)
    def syncStopped: Boolean = setIn(syncFile)
    def active: Boolean = maintenanceStopped || syncStopped

    def set(c: Context): Unit =
      Try(write(maintenanceFile, build().toString))
      Try(write(syncFile, build().toString))
      Try(Seq(JobIdle, JobDelayed, JobManual, JobRepair).foreach(c.getSystemService(classOf[JobScheduler]).cancel))
      Try(net.sksat.ashidori.sync.SyncScheduler.cancelAll(c))

    private def reset(): Unit = Try(write(resetFile, System.currentTimeMillis().toString))

    def resumeMaintenance(): Unit =
      maintenanceFile.delete()
      reset()

    def resumeSync(c: Context): Unit =
      syncFile.delete()
      reset()
      net.sksat.ashidori.sync.SyncScheduler.schedule(c)

    /** Crashes before this do not count again. */
    def resetAt: Long =
      Try(String(java.nio.file.Files.readAllBytes(resetFile.toPath), UTF_8).trim.toLong).getOrElse(0L)

  // --- the run ---------------------------------------------------------------------------

  private val running = AtomicBoolean(false)

  /** Runs due maintenance until done, `stop` or the budget. Returns true if
    * work is left. Call in the maintenance process, off the main thread.
    */
  def run(manual: Boolean, stop: () => Boolean): Boolean =
    // A database of a newer app version is never opened here (Db.onDowngrade):
    // nothing to archive until the app is updated.
    if AshidoriApp.newerDatabase.isDefined then
      Progress.set("データベースが新しい版のため整理しない", running = false)
      return false
    if !running.compareAndSet(false, true) then return false
    val deadline = System.currentTimeMillis() + BudgetMs
    val halt = () => stop() || System.currentTimeMillis() > deadline
    var left = false
    try
      val db = AshidoriApp.db
      val archive = AshidoriApp.archive
      // A commit a crash interrupted is resolved first; one waiting for the
      // user stops the run (said so in the progress line).
      try archive.recover(db, progress = l => Progress.set(l))
      catch
        case _: ArchiveStore.CommitStuck =>
          // Not a failure of this run: nothing to archive until it is resolved.
          Progress.set("記録の整理が停止（要確認）", running = false)
          return false
      // Ranges the user deleted: cut out of the archive files first (their
      // SQLite rows are gone already). One left pending is cut again next time.
      for (id, from, to) <- db.archiveDeletionsPending() if !halt() do
        try
          if archive.deleteRange(db, from, to, halt, l => Progress.set(l)) then db.markArchiveDeleted(id)
          else left = true
        catch case NonFatal(e) =>
          LifeLog.add(LifeKind.Note, s"削除した期間をアーカイブから除けず（次回再試行）: ${LifeLog.describe(e)}")
          Log.w("cutting a deleted range out of the archive failed", e)
      val legacy = Option(ctx.getExternalFilesDir(null)).map(File(_, "gnss"))
      legacy.foreach(d => Try(archive.migrateLegacyLogs(db, d)).failed.foreach(e => Log.w("legacy log migration failed", e)))
      val now = System.currentTimeMillis()
      val charging = ctx.getSystemService(classOf[android.os.BatteryManager]).isCharging
      val b = build()
      // Debug builds: a shorter portion from `files/maintenance/portion-ms`
      // (to test archiving a day in several portions).
      val debuggable = (ctx.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
      val portionMs =
        if !debuggable then ArchiveStore.PortionMs
        else Try(String(java.nio.file.Files.readAllBytes(File(dir, "portion-ms").toPath), UTF_8).trim.toLong)
          .getOrElse(ArchiveStore.PortionMs)
      for d <- archive.dueDays(db, now) if !halt() do
        CompactionGuard.decide(Guard.get(d), b, System.currentTimeMillis(), charging, manual) match
          case CompactionGuard.Decision.Skip(log, state) =>
            if log then
              Guard.put(d, state)
              LifeLog.add(LifeKind.ArchiveSkip,
                s"$d: 整理に${state.attempts}回失敗したため保留（データは端末内に保持。充電中・アプリ更新後・「記録を再整理」で再試行）")
          case CompactionGuard.Decision.Run(next, retry) =>
            val before = Guard.get(d)
            // On disk before the work: if the process dies in it, the next
            // run sees the attempt.
            Guard.put(d, next)
            Complete.clear(d)
            // Before rows of the day leave SQLite: the 「訪れた日」 index marks
            // the day (one short write of its own file; never throws, never
            // waits for the index: on failure it is marked stale instead).
            VisitDays.beforeArchiving(d)
            if retry then LifeLog.add(LifeKind.Archive, s"$d: 保留していた整理を再試行")
            val t0 = System.currentTimeMillis()
            try
              val r = archive.compactDay(db, d, stop, line => Progress.set(line), until = deadline, portionMs = portionMs)
              if r.unconverted > 0 then LifeLog.add(LifeKind.Note, s"$d: 生データのうち ${r.unconverted} 行は変換できないため端末内に残す")
              if r.done then
                Guard.clear(d)
                Complete.mark(d)
                val s = (System.currentTimeMillis() - t0) / 1000
                if s >= 60 || retry then LifeLog.add(LifeKind.Archive, s"$d: 整理完了（生データ ${r.lines} 行・${s}秒）")
              else
                // Stopped or out of time: not a failure; the rest is done next time.
                before.fold(Guard.clear(d))(Guard.put(d, _))
                left = true
            catch
              case e: OutOfMemoryError =>
                // The day's buffers are unreachable by now; this process
                // stays usable for the log line.
                LifeLog.add(LifeKind.Note, s"$d: 整理でメモリ不足（データは端末内に保持）")
                Log.w(s"archiving $d: out of memory", e)
              case NonFatal(e) =>
                LifeLog.add(LifeKind.Note, s"$d: 整理に失敗（データは端末内に保持）: ${LifeLog.describe(e)}")
                Log.w(s"archiving $d failed; kept in SQLite", e)
      if !halt() then
        try archive.mergeMonths(db, LocalDate.now(AshidoriApp.zone), halt)
        catch
          case e: OutOfMemoryError => Log.w("monthly merge: out of memory", e)
          case NonFatal(e) => Log.w("monthly merge failed", e)
      if halt() then left = true
      Progress.set(if left then "中断（続きは次回）" else "完了", running = false)
      // Then a bounded run of the 「訪れた日」 index (fewer days on battery).
      // Never a reason to run again: it is built while its list is open too.
      if !halt() then VisitDays.maintain(charging, halt)
      left
    finally running.set(false)

/** Runs [[Maintenance.run]] in the `:maintenance` process. */
class ArchiveJobService extends JobService:
  @volatile private var stopped = false

  override def onStartJob(params: JobParameters): Boolean =
    stopped = false
    val manual = params.getExtras.getBoolean("manual", false)
    val repair = Option(params.getExtras.getString("repair"))
    // Switched off after repeated crashes (a job queued before): not run.
    if Maintenance.SafeMode.maintenanceStopped && !manual then return false
    // A database of a newer app version: not opened (no repair either).
    if AshidoriApp.newerDatabase.isDefined then return false
    val t = Thread(() =>
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      val left =
        try
          repair match
            case Some(_) =>
              Maintenance.repair(manual)
              false
            case None => Maintenance.run(manual, () => stopped)
        catch
          case e: Throwable =>
            Log.w("maintenance failed", e)
            false
      // More to do (out of time): JobScheduler runs this job again after a
      // pause. When stopped, onStopJob asked for that already.
      if !stopped then jobFinished(params, left)
    , "ashidori-maintenance")
    t.start()
    true

  override def onStopJob(params: JobParameters): Boolean =
    stopped = true
    // Reschedule: what was not finished is done next time.
    true
