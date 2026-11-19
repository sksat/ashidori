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
  *  - Logging and its overflow files: never refused by L or H.
  */
object Storage:
  private def app = AshidoriApp.app

  /** The main process's reservations of downloads and backups (in memory). */
  lazy val budget: StorageBudget = StorageBudget(() => free(), () => reserve())

  /** The guard of maintenance, in either process: archive rewrites, bulk
    * SQLite writes, the visit index (the fixed stopping point H; no other
    * process's reservations are read).
    */
  lazy val guard: SpaceGuard = SpaceGuard(() => free(), () => Headroom.H)

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

