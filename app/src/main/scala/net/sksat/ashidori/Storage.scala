package net.sksat.ashidori

import android.content.Context
import java.io.File
import ashidori.core.*

/** The logging reserve against the app's large writes (design 6.8).
  *
  * The guarantee: the free space never goes below the logging reserve L by
  * more than (number of large writers running at once) × 1 MiB. Every large
  * writer, in either process, writes through a [[SpaceGuard]]: each write
  * in chunks of at most 1 MiB, each checked against the free space now
  * before it is written.
  *
  *  - Main process (downloads, extractions, backups, region packs): a
  *    reservation in [[budget]] (in memory) first, then every chunk is
  *    checked against L and what the other reservations will still write.
  *  - Maintenance process (compaction, the monthly merge, cutting deleted
  *    ranges, the import of old logs): every chunk is checked against L as
  *    the main process last wrote it to [[reserveFile]] ([[maintenanceGuard]]).
  *
  * The main process computes L (the logging mode and the measured rate) and
  * writes it whenever it changes; a file that cannot be read gives the most
  * conservative L ([[ReserveFile.Conservative]]).
  */
object Storage:
  private def app = AshidoriApp.app
  lazy val reserveFile: File = File(app.getFilesDir, "logging-reserve")
  /** Held locked by the main process while it lives ([[MainAlive]]). */
  lazy val aliveFile: File = File(app.getFilesDir, "main-alive.lock")

  /** The main process's reservations; each admission (refused if it cannot
    * be published), truncation and release publishes the floor.
    */
  lazy val budget: StorageBudget = StorageBudget(() => free(), () => reserve(), () => publisher.publish())

  /** The floor file's writer (main process): every publication and mode change under one lock. */
  lazy val publisher: FloorPublisher =
    FloorPublisher(reserveFile, () => Settings.mode, lFor, () => budget.outstanding,
      write = (f, x) =>
        try ReserveFile.write(f, x)
        catch case e: Exception =>
          Log.w("logging reserve not published; removed (the other process uses the conservative L)", e)
          throw e)

  /** The guard of this process's bulk writes that hold no reservation (SQLite batches). */
  def guard: SpaceGuard =
    if AshidoriApp.isMaintenanceProcess then maintenanceGuard else budget.guard

  /** The maintenance process's guard ([[maintenanceFloor]]). */
  lazy val maintenanceGuard: SpaceGuard = SpaceGuard(() => free(), () => maintenanceFloor())

  @volatile private var aliveAt = 0L
  @volatile private var aliveValue = true
  /** In the main process itself: true, and the lock file is never opened
    * there again (closing another descriptor of it would drop the lock).
    */
  private def mainAlive: Boolean =
    if !AshidoriApp.isMaintenanceProcess then return true
    val now = android.os.SystemClock.elapsedRealtime()
    if now - aliveAt > 2_000 then
      aliveValue = MainAlive.alive(aliveFile)
      aliveAt = now
    aliveValue

  /** The floor of the maintenance process ([[ReserveFile.floor]]): the main
    * process's published floor (its reservations only while it lives), or
    * the conservative L if the file cannot be read, and at least its own L.
    */
  def maintenanceFloor(): Long =
    ReserveFile.floor(ReserveFile.read(reserveFile), System.currentTimeMillis(), mainAlive, ownL())

  @volatile private var ownAt = 0L
  @volatile private var ownValue = StorageBudget.MinReserve
  /** L the maintenance process derives itself: a day at the higher of what
    * the logger recorded in the last hour (rows and text in the database)
    * and the rate the main process last measured, at least 最高精度's
    * default when neither can be read; measured at most every 10 minutes.
    */
  private def ownL(): Long =
    val now = System.currentTimeMillis()
    if now - ownAt > 10L * 60 * 1000 then
      ownAt = now
      ownValue = scala.util.Try {
        val rate = math.max(AshidoriApp.db.recentLoggingBytesPerHour(now), LoggingRate.stored(now).getOrElse(0L))
        StorageBudget.loggingReserve(LoggingMode.Eco, Some(rate))
      }.getOrElse(ReserveFile.Conservative)
    ownValue

  /** Debug builds: free bytes to pretend (DebugReceiver STORAGE_FREE); None: the real ones. */
  @volatile var debugFree: Option[Long] = None
  def free(): Long =
    IoCheck.offMain("free space")
    debugFree.filter(_ => debuggable).getOrElse(app.getFilesDir.getUsableSpace)

  @volatile private var reserveAt = 0L
  @volatile private var reserveMode: Option[LoggingMode] = None
  @volatile private var reserveValue = ReserveFile.Conservative

  /** L of the main process for its mode (measured at most once a minute,
    * at once for another mode). In the maintenance process, its floor.
    */
  def reserve(): Long =
    if AshidoriApp.isMaintenanceProcess then maintenanceFloor()
    else lFor(Settings.mode)

  private def lFor(mode: LoggingMode): Long = synchronized {
    val now = System.currentTimeMillis()
    if now - reserveAt > 60_000 || !reserveMode.contains(mode) then
      reserveAt = now
      reserveMode = Some(mode)
      reserveValue = StorageBudget.loggingReserve(mode, LoggingRate.measure(now))
    reserveValue
  }

  /** One thread for the floor's writes and the mode changes (never the main thread). */
  private lazy val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
    val t = Thread(r, "ashidori-storage")
    t.setDaemon(true)
    t
  }

  @volatile private var alive: java.nio.channels.FileChannel = null

  /** The main process at start: its liveness lock taken (kept for its
    * life), then the floor published, and again every
    * [[ReserveFile.HeartbeatMs]] when the measured L changed (and at least
    * that often).
    */
  def start(): Unit =
    if !AshidoriApp.isMaintenanceProcess then
      val t = Thread(() =>
        try alive = MainAlive.hold(aliveFile)
        catch case e: Exception => Log.w("main liveness lock not taken", e)
      , "ashidori-alive")
      t.setDaemon(true)
      t.start()
      executor.scheduleWithFixedDelay(() => scala.util.Try(publisher.refresh(ReserveFile.HeartbeatMs)), 0,
        ReserveFile.HeartbeatMs / 2, java.util.concurrent.TimeUnit.MILLISECONDS)
      executor.execute { () =>
        // The shared reservation ledger of a development build before this one.
        for n <- Seq("storage-budget.json", "storage-budget.json.lock", "storage-budget.json.tmp") do File(app.getFilesDir, n).delete()
      }

  /** A new logging mode, off the main thread: under the publisher's lock the
    * floor of the new mode is written, then the mode is stored (it takes
    * effect then); then `after` runs on the main thread.
    */
  def changeMode(m: LoggingMode, after: () => Unit = () => ()): Unit =
    executor.execute { () =>
      scala.util.Try(publisher.changeMode(m)(Settings.storeMode(m)))
      app.getMainExecutor.execute(() => after())
    }

  private def debuggable: Boolean =
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

