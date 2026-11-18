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

  /** The main process's reservations. */
  lazy val budget: StorageBudget = StorageBudget(() => free(), () => reserve())

  /** The maintenance process's guard: L read from [[reserveFile]] at every check. */
  lazy val maintenanceGuard: SpaceGuard = SpaceGuard(() => free(), () => ReserveFile.read(reserveFile))

  /** Debug builds: free bytes to pretend (DebugReceiver STORAGE_FREE); None: the real ones. */
  @volatile var debugFree: Option[Long] = None
  def free(): Long =
    IoCheck.offMain("free space")
    debugFree.filter(_ => debuggable).getOrElse(app.getFilesDir.getUsableSpace)

  @volatile private var reserveAt = 0L
  @volatile private var reserveMode: Option[LoggingMode] = None
  @volatile private var reserveValue = ReserveFile.Conservative
  @volatile private var written = -1L

  /** L of the main process: measured at most once a minute, and at once
    * when the logging mode changes; written to [[reserveFile]] when it
    * changes. In the maintenance process, the file's.
    */
  def reserve(): Long =
    if AshidoriApp.isMaintenanceProcess then ReserveFile.read(reserveFile)
    else synchronized {
      val now = System.currentTimeMillis()
      val mode = Settings.mode
      if now - reserveAt > 60_000 || !reserveMode.contains(mode) then
        reserveAt = now
        reserveMode = Some(mode)
        reserveValue = StorageBudget.loggingReserve(mode, LoggingRate.measure(now))
        if reserveValue != written then
          scala.util.Try(ReserveFile.write(reserveFile, reserveValue)).fold(
            e => Log.w("logging reserve not written", e),
            _ => written = reserveValue)
      reserveValue
    }

  /** The logging mode changed (or the app started): L measured and written at once. */
  def modeChanged(): Unit =
    if !AshidoriApp.isMaintenanceProcess then
      AshidoriApp.io.execute { () =>
        synchronized { reserveAt = 0L }
        scala.util.Try(reserve())
        // The shared reservation ledger of a development build before this one.
        for n <- Seq("storage-budget.json", "storage-budget.json.lock", "storage-budget.json.tmp") do File(app.getFilesDir, n).delete()
      }

  private def debuggable: Boolean =
    (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** 「空き容量不足（記録用の予備を除いて N MB 必要）」 */
  def shortage(need: Long): String = s"空き容量不足（記録用の予備を除いて ${math.max(1L, need / 1_000_000)} MB 必要）"

/** Debug builds: storage I/O on the main thread is logged (as StrictMode
  * would) and counted; the regions' code calls it before each such access.
  */
object IoCheck:
  @volatile var count = 0
  private lazy val debuggable =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  def offMain(what: String): Unit =
    if debuggable && android.os.Looper.myLooper() == android.os.Looper.getMainLooper then
      count += 1
      Log.w(s"main-thread storage I/O: $what", Throwable("here"))

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

