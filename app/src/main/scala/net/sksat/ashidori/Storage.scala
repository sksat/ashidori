package net.sksat.ashidori

import android.content.Context
import java.io.File
import ashidori.core.*

/** The storage budget shared by every large write of the app (design 6.8):
  * the basemap and venue downloads and their extraction, the backups and
  * the region packs. Each reserves what it will write and reports what it
  * wrote; none starts or goes on once the free space would cut into the
  * logging reserve `L` (the logger itself never asks).
  */
object Storage:
  /** The free space of filesDir less the logging reserve `L`. Debug
    * builds can pretend less free space ([[debugFree]]).
    */
  lazy val budget: StorageBudget = StorageBudget(() => free(), () => reserve())
  /** Debug builds: free bytes to pretend (DebugReceiver REGIONS_FREE); None: the real ones. */
  @volatile var debugFree: Option[Long] = None
  def free(): Long = debugFree.filter(_ => debuggable).getOrElse(AshidoriApp.app.getFilesDir.getUsableSpace)

  @volatile private var reserveAt = 0L
  @volatile private var reserveMode: Option[LoggingMode] = None
  @volatile private var reserveValue = StorageBudget.MinReserve
  /** L, measured at most once a minute (the logger's data size is a walk of
    * its files), and at once when the logging mode changes.
    */
  def reserve(): Long =
    val now = System.currentTimeMillis()
    val mode = Settings.mode
    if now - reserveAt > 60_000 || !reserveMode.contains(mode) then
      reserveAt = now
      reserveMode = Some(mode)
      reserveValue = StorageBudget.loggingReserve(mode, LoggingRate.measure(now))
    reserveValue

  private def debuggable: Boolean =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** What a write in another process (archive maintenance in
    * `:maintenance`) must leave free: L, from the logging mode and the
    * stored rate (read only), plus room for one chunk it writes. That
    * process has no share in the budget; it stops once the free space
    * reaches this floor.
    */
  def floor(): Long =
    StorageBudget.loggingReserve(Settings.mode, LoggingRate.stored(System.currentTimeMillis())) + ChunkRoom
  /** Room for one chunk the maintenance writes between its checks. */
  val ChunkRoom: Long = 32L * 1000 * 1000

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

