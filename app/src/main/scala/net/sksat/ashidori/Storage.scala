package net.sksat.ashidori

import android.content.Context
import java.io.File
import ashidori.core.*

/** The storage budget shared by every large write of the app (design 6.8),
  * in both of its processes: the basemap and venue downloads and their
  * extraction, the backups and the region packs (main process), and
  * archive maintenance (`:maintenance`: compaction, the monthly merge,
  * cutting deleted ranges, the import of old logs). Each reserves what it
  * will write and reports what it writes; none starts or goes on once the
  * free space would cut into the logging reserve `L` (the logger itself
  * never asks).
  *
  * The reservations and L live in `files/storage-budget.json`, read and
  * written under a file lock by both processes ([[BudgetLedger.Shared]]);
  * a reservation of a process that died is dropped. The main process
  * computes L (the logging mode and the measured rate) and publishes it
  * there whenever it changes; the maintenance process reads it from there
  * at every report of its writes (at least every MiB).
  */
object Storage:
  private def app = AshidoriApp.app
  lazy val ledgerFile: File = File(app.getFilesDir, "storage-budget.json")

  lazy val budget: StorageBudget =
    StorageBudget(() => free(), () => ownReserve(), BudgetLedger.Shared(ledgerFile), owner, alive)

  /** This process: the boot, its pid and its start (clock ticks since boot, /proc/<pid>/stat). */
  lazy val owner: String = s"${bootCount()}:${android.os.Process.myPid()}:${startTicks(android.os.Process.myPid()).getOrElse(0L)}"

  private def bootCount(): Int =
    scala.util.Try(android.provider.Settings.Global.getInt(app.getContentResolver, android.provider.Settings.Global.BOOT_COUNT)).getOrElse(-1)

  /** Field 22 of /proc/<pid>/stat (after the command in parentheses). */
  private def startTicks(pid: Int): Option[Long] =
    scala.util.Try {
      val st = String(java.nio.file.Files.readAllBytes(File(s"/proc/$pid/stat").toPath))
      st.substring(st.lastIndexOf(')') + 2).split(' ')(19).toLong
    }.toOption

  /** A reservation's owner is alive: this boot, and its pid still the same process. */
  private def alive(o: String): Boolean = o.split(':') match
    case Array(boot, pid, start) =>
      boot.toIntOption.contains(bootCount()) &&
        pid.toIntOption.flatMap(startTicks).exists(t => start.toLongOption.contains(t))
    case _ => false

  /** Debug builds: free bytes to pretend (DebugReceiver STORAGE_FREE); None: the real ones. */
  @volatile var debugFree: Option[Long] = None
  def free(): Long = debugFree.filter(_ => debuggable).getOrElse(app.getFilesDir.getUsableSpace)

  @volatile private var reserveAt = 0L
  @volatile private var reserveMode: Option[LoggingMode] = None
  @volatile private var reserveValue = StorageBudget.MinReserve

  /** This process's own L: in the main process measured (at most once a
    * minute, and at once when the logging mode changes) and published; in
    * the maintenance process only the floor (the published one counts).
    */
  private def ownReserve(): Long =
    if AshidoriApp.isMaintenanceProcess then StorageBudget.MinReserve
    else
      val now = System.currentTimeMillis()
      val mode = Settings.mode
      if now - reserveAt > 60_000 || !reserveMode.contains(mode) then
        reserveAt = now
        reserveMode = Some(mode)
        reserveValue = StorageBudget.loggingReserve(mode, LoggingRate.measure(now))
        // Outside the ledger's lock (this is called under it): published next.
        val v = reserveValue
        AshidoriApp.io.execute(() => scala.util.Try(budget.publish(v)))
      reserveValue

  /** L now (published or this process's). */
  def reserve(): Long = budget.loggingReserve

  /** The main process: the logging mode changed (or the app started):
    * L is measured and published at once.
    */
  def modeChanged(): Unit =
    if !AshidoriApp.isMaintenanceProcess then
      AshidoriApp.io.execute { () =>
        reserveAt = 0L
        scala.util.Try(budget.publish(ownReserve()))
      }

  private def debuggable: Boolean =
    (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** 「空き容量不足（記録用の予備を除いて N MB 必要）」 */
  def shortage(need: Long): String = s"空き容量不足（記録用の予備を除いて ${math.max(1L, need / 1_000_000)} MB 必要）"

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

