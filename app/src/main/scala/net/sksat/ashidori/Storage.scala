package net.sksat.ashidori

import android.content.Context
import java.io.File
import ashidori.core.*

/** The free space the app's large writes leave (design 6.8; the promises
  * are in [[ashidori.core.Headroom]]).
  *
  *  - Main process, downloads and their extraction: a reservation in
  *    [[budget]] (in memory) first; every output of at most 1 MiB is then
  *    checked against L (the target free space for logging) and what the
  *    other reservations will still write. Backups reserve likewise to
  *    decide whether they start.
  *  - Maintenance, in either process (archive rewrites, bulk SQLite writes,
  *    the visit index): the fixed stopping point H ([[guard]]); no process
  *    reads another's reservations.
  *  - Logging and its overflow files: never refused by L or H. Short of
  *    space, the emergency path ([[emergency]], plan C stage 2): large work
  *    stopped, the reserve R released, batches to overflow files. Large
  *    work of either process runs only while R is ready ([[largeWorkOpen]]).
  */
object Storage:
  private def app = AshidoriApp.app

  /** The main process's reservations of downloads and backups (in memory). */
  lazy val budget: StorageBudget = StorageBudget(() => free(), () => reserve(), () => largeWorkOpen())

  /** The guard of maintenance, in either process: archive rewrites, bulk
    * SQLite writes, the visit index (the fixed stopping point H; no other
    * process's reservations are read).
    */
  lazy val guard: SpaceGuard = SpaceGuard(() => free(), () => Headroom.H, open = () => largeWorkOpen())

  // --- the logger's emergency reserve (plan C stage 2) ----------------------------------

  /** Large work other than logging may start or go on: the emergency
    * reserve is ready and no emergency is in progress. The main process
    * knows ([[emergency]]); the maintenance process reads the reserve file
    * (present at its full length), at its start and at each boundary.
    */
  def largeWorkOpen(): Boolean =
    if AshidoriApp.isMaintenanceProcess then ReserveFiles.onDisk() == Emergency.OnDisk.Full
    else emergency.largeWorkAllowed

  /** Why large work is deferred, when it is (shown). */
  val DeferredText = "記録用の緊急予備がないため延期"

  /** The reserve and the emergency state: the main process only (it alone
    * creates and releases R). Read from the files on first use.
    */
  lazy val emergency: EmergencyReserve =
    require(!AshidoriApp.isMaintenanceProcess, "the reserve belongs to the main process")
    val e = debugLong("debug-emergency-e").getOrElse(Emergency.E)
    val sustain = debugLong("debug-emergency-sustain-ms").getOrElse(Emergency.SustainMs)
    val r = EmergencyReserve(ReserveFiles, () => android.os.SystemClock.elapsedRealtime(),
      // Large work stops by the gate closing (its next output, batch or
      // watcher check: largeWorkOpen) and, in the maintenance process, by the
      // released file; nothing is waited for.
      () => Log.w("emergency: large work asked to stop"),
      ev => monitor.execute(() => onEvent(ev)), e = e, sustainMs = sustain)
    r.restore()
    r

  private lazy val monitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => Thread(r, "ashidori-reserve"))

  /** Every 5 s: the recovery clock, or the first creation of R. */
  private def watch(): Unit =
    monitor.scheduleWithFixedDelay(() =>
      try if emergency.tick(LoggerWrites.logging) then emergency.allocate()
      catch case scala.util.control.NonFatal(e) => Log.w("emergency reserve check failed", e)
    , 1, 5, java.util.concurrent.TimeUnit.SECONDS)

  @volatile private var allocationFailures = 0
  private def onEvent(e: Emergency.Event): Unit = e match
    case Emergency.Event.Entered(reason, detail, first, released, failure) =>
      val rel = released.fold("予備なし")(b => f"予備を解放（空き +${b / 1048576.0}%.1f MiB）")
      LifeLog.add(LifeKind.Emergency,
        (if first then "記録を退避ファイルへ切り替え" else "容量不足が継続") + s": ${reason.label}" +
          (if detail.nonEmpty then s"（$detail）" else "") + s"・$rel" + failure.fold("")(f => s"・解放に失敗: $f"))
      if first then Alerts.emergency(true)
    case Emergency.Event.Created(_) =>
      allocationFailures = 0
      Log.i("emergency reserve created")
      // Maintenance deferred for want of it may run now.
      Maintenance.schedule(app)
    case Emergency.Event.Recovered(_) =>
      allocationFailures = 0
      LifeLog.add(LifeKind.Emergency, "空き容量が回復: 予備を再確保し、通常の保存に復帰")
      Alerts.emergency(false)
      LoggerWrites.resumeImport()
      LoggerWrites.Losses.flush()
      Maintenance.schedule(app)
    case Emergency.Event.AllocationFailed(why) =>
      allocationFailures += 1
      Log.w(s"emergency reserve allocation failed: $why")
      if allocationFailures == 1 then LifeLog.add(LifeKind.Emergency, s"記録用の緊急予備を確保できず: $why")
    case Emergency.Event.AllocationCancelled(g) => Log.i(s"emergency reserve allocation $g cancelled")

  /** Another process's or worker's failure for lack of space (ENOSPC,
    * SQLITE_FULL), reported by the main process's work: the emergency state.
    */
  def noticeFailure(e: Throwable, what: String): Unit =
    if !AshidoriApp.isMaintenanceProcess && (NoSpace.full(e) || NoSpace.noSpace(e)) then
      emergency.enter(Emergency.Reason.Reported, s"$what: ${LifeLog.describe(e)}")

  /** The reserve file in the app's files (not a cache directory), on the
    * same file system as the database. Allocated in an allocation file by
    * `posix_fallocate` (or written whole where that is not supported),
    * synced, then renamed into place: never sparse, never half published.
    */
  object ReserveFiles extends Emergency.Files:
    import android.system.{ErrnoException, Os, OsConstants}
    private def dir = app.getFilesDir
    def file: File = File(dir, "emergency-reserve")
    private def tmp = File(dir, "emergency-reserve.alloc")
    private var out: java.io.RandomAccessFile = null

    def free(): Long = Storage.free()
    def onDisk(): Emergency.OnDisk =
      if !file.exists then Emergency.OnDisk.Absent
      else if file.length >= Emergency.R then Emergency.OnDisk.Full
      else Emergency.OnDisk.Released
    def discardTmp(): Unit = synchronized {
      if out != null then
        scala.util.Try(out.close())
        out = null
      if tmp.exists && !tmp.delete() then throw java.io.IOException(s"cannot delete $tmp")
    }
    def allocateTmp(from: Long, n: Long): Unit = synchronized {
      if out == null then out = java.io.RandomAccessFile(tmp, "rw")
      try Os.posix_fallocate(out.getFD, from, n)
      catch case e: ErrnoException if e.errno == OsConstants.EOPNOTSUPP || e.errno == OsConstants.ENOSYS =>
        // Written whole instead (zeros take blocks: not sparse).
        val buf = new Array[Byte](1 << 20)
        out.seek(from)
        var left = n
        while left > 0 do
          val k = math.min(left, buf.length.toLong).toInt
          out.write(buf, 0, k)
          left -= k
      debugKill("reserve-allocate")
    }
    def syncTmp(): Unit = synchronized {
      if out == null then throw java.io.IOException("no allocation in progress")
      out.getFD.sync()
      out.close()
      out = null
      debugKill("reserve-sync")
    }
    def publish(): Unit =
      debugKill("reserve-publish")
      Os.rename(tmp.getPath, file.getPath)
      ArchiveStore.syncDir(dir)
    def release(): Long =
      debugKill("reserve-release")
      if debugFile("debug-reserve-release-fails").exists then throw java.io.IOException("debug: release fails")
      scala.util.Using.resource(java.io.RandomAccessFile(file, "rw")) { f =>
        f.setLength(0)
        f.getFD.sync()
      }
      debugKill("reserve-released")
      file.length
    def remove(): Boolean =
      val ok = file.delete() || !file.exists
      scala.util.Try(ArchiveStore.syncDir(dir))
      ok
    def markReleased(): Unit =
      if file.createNewFile() then scala.util.Try(ArchiveStore.syncDir(dir))

  // --- debug builds: knobs of the emergency path's tests ---------------------------------

  private def debugFile(name: String): File = File(Maintenance.dir, name)
  private def debugLong(name: String): Option[Long] =
    if !debuggable then None
    else scala.util.Try(String(java.nio.file.Files.readAllBytes(debugFile(name).toPath), java.nio.charset.StandardCharsets.UTF_8).trim.toLong).toOption

  /** Debug builds: the process is killed here once if `files/maintenance/debug-kill-<point>` exists (removed first). */
  def debugKill(point: String): Unit =
    if debuggable then
      val f = debugFile(s"debug-kill-$point")
      if f.exists && f.delete() then
        Log.w(s"debug: killed at $point")
        android.os.Process.killProcess(android.os.Process.myPid())


  /** Debug builds: free bytes to pretend (DebugReceiver STORAGE_FREE); None: the real ones. */
  @volatile var debugFree: Option[Long] = None
  def free(): Long =
    IoCheck.offMain("free space")
    debugFree.filter(_ => debuggable).getOrElse(app.getFilesDir.getUsableSpace)

  @volatile private var reserveAt = 0L
  @volatile private var reserveMode: Option[LoggingMode] = None
  @volatile private var reserveValue = StorageBudget.loggingReserve(LoggingMode.Max, None)

  /** L of the main process for the logging mode now: measured at most once
    * a minute, and at once when the mode changed (the next check uses it).
    */
  def reserve(): Long = synchronized {
    val now = System.currentTimeMillis()
    val mode = Settings.mode
    if now - reserveAt > 60_000 || !reserveMode.contains(mode) then
      reserveAt = now
      reserveMode = Some(mode)
      reserveValue = StorageBudget.loggingReserve(mode, LoggingRate.measure(now))
    reserveValue
  }

  /** The main process at start, off the main thread: files of earlier
    * development builds that nothing reads any more (the floor file, the
    * liveness lock, the shared ledger) are removed; a failure changes nothing.
    */
  def start(): Unit =
    if !AshidoriApp.isMaintenanceProcess then
      AshidoriApp.io.execute { () =>
        val dir = app.getFilesDir
        for n <- Seq("logging-reserve", "logging-reserve.tmp", "main-alive.lock",
                     "storage-budget.json", "storage-budget.json.lock", "storage-budget.json.tmp") do
          scala.util.Try(File(dir, n).delete())
      }
      // The emergency reserve: its state read from the files (a restart does
      // not leave an emergency), then created or recovered in the background.
      monitor.execute(() => scala.util.Try(emergency).failed.foreach(e => Log.w("emergency reserve state", e)))
      watch()

  def debuggable: Boolean =
    (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** 「空き容量不足（記録用の予備を除いて N MB 必要）」 */
  def shortage(need: Long): String = s"空き容量不足（記録用の予備を除いて ${math.max(1L, need / 1_000_000)} MB 必要）"

/** Debug builds: storage I/O on the main thread is logged and counted, in
  * two ways. [[offMain]] marks the accesses of the regions, the free space,
  * the floor and the installed basemap and venue data (logged as
  * `main-thread storage I/O: <what>`); StrictMode ([[install]]) catches
  * every file and database access through the platform's I/O on the main
  * thread, of any code (logged as `main-thread disk (StrictMode)` with the
  * app's first frame). Neither sees I/O in native code (MapLibre).
  */
object IoCheck:
  @volatile var count = 0
  @volatile var strict = 0
  private lazy val debuggable =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  def offMain(what: String): Unit =
    if debuggable && android.os.Looper.myLooper() == android.os.Looper.getMainLooper then
      count += 1
      Log.w(s"main-thread storage I/O: $what", Throwable("here"))

  /** Debug builds: StrictMode on the main thread, disk reads and writes logged (never a crash). */
  def install(): Unit =
    if debuggable then
      android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
        .detectDiskReads().detectDiskWrites()
        .penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor(), v => {
          strict += 1
          val app = v.getStackTrace.find(_.getClassName.startsWith("net.sksat.ashidori")).fold("?")(f =>
            s"${f.getClassName.stripPrefix("net.sksat.ashidori.")}.${f.getMethodName}:${f.getLineNumber}")
          Log.w(s"main-thread disk (StrictMode) #$strict: ${v.getClass.getSimpleName} at $app")
        }).build())

/** The logger's write rate, from samples of the size of its data (the
  * database, its WAL, the archive and the overflow files) kept in prefs.
  */
object LoggingRate:
  private def prefs = AshidoriApp.app.getSharedPreferences("regions", Context.MODE_PRIVATE)
  private def size(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(size).sum else f.length

  def loggerBytes(): Long =
    val db = AshidoriApp.app.getDatabasePath("ashidori.db")
    Seq(db, File(db.getPath + "-wal"), File(AshidoriApp.app.getFilesDir, "archive"), LoggerWrites.Overflow.dir).map(size).sum

  private def samples(): Seq[(Long, Long)] =
    Option(prefs.getString("loggerSamples", "")).toSeq.flatMap(_.split(';')).flatMap { s =>
      s.split(',') match
        case Array(t, b) => scala.util.Try(t.toLong -> b.toLong).toOption
        case _ => None
    }

  /** The rate from the stored samples, adding none (another process reads it). */
  def stored(now: Long): Option[Long] = StorageBudget.measuredRate(samples(), now)

  /** Adds a sample (at most one per 10 minutes, the last 24 kept) and returns the rate. */
  def measure(now: Long): Option[Long] =
    val old = samples()
    val now2 = if old.lastOption.exists(_._1 > now - 600_000) then old else (old :+ (now -> loggerBytes())).takeRight(24)
    if now2 ne old then prefs.edit().putString("loggerSamples", now2.map((t, b) => s"$t,$b").mkString(";")).apply()
    StorageBudget.measuredRate(now2, now)


/** Failures for lack of space: SQLITE_FULL, and ENOSPC of a file write. */
object NoSpace:
  private def chain(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(Option(e))(_.flatMap(x => Option(x.getCause))).takeWhile(_.isDefined).map(_.get)
      .flatMap(x => Iterator(x) ++ x.getSuppressed.iterator)
  def full(e: Throwable): Boolean = chain(e).exists(_.isInstanceOf[android.database.sqlite.SQLiteFullException])
  def noSpace(e: Throwable): Boolean = chain(e).exists {
    case x: android.system.ErrnoException => x.errno == android.system.OsConstants.ENOSPC
    case x: java.io.IOException => Option(x.getMessage).exists(m => m.contains("ENOSPC") || m.contains("No space left"))
    case _ => false
  }
