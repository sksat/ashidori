package ashidori.core

import java.io.{File, IOException, OutputStream}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import scala.util.Try

/** Guards the logging reserve against the app's large writes (design 6.8).
  *
  * The guarantee: the free space never goes below the logging reserve `L`
  * by more than (number of large writers running at once) × 1 MiB. Every
  * large writer, in either process, writes through a [[SpaceGuard]]: each
  * write is split into chunks of at most [[SpaceGuard.Chunk]] (1 MiB), and
  * before each chunk the free space now must leave `L`, what the main
  * process's reservations still hold ([[StorageBudget]], main process
  * only) and the chunk itself. Otherwise the write stops
  * ([[StorageBudget.NoRoom]]). A check sees what the other writers wrote
  * before it; what they write between two checks is at most their own
  * chunk each, hence the bound.
  *
  * `free`: the free space now; `reserve`: L now; `held`: bytes the main
  * process's reservations will still write (0 in another process).
  */
final class SpaceGuard(free: () => Long, reserve: () => Long, held: () => Long = () => 0L):
  /** The chunk `n` (at most [[SpaceGuard.Chunk]]) may be written now. */
  def allows(n: Long): Boolean = free() - reserve() - held() >= n
  /** As [[allows]], or NoRoom. */
  def check(n: Long): Unit = if !allows(n) then throw StorageBudget.NoRoom()

object SpaceGuard:
  val Chunk: Int = 1 << 20

/** An output stream for a large writer: every write is split into chunks
  * of at most 1 MiB, each checked against the guard before it is written
  * (a single row group, ZIP entry or downloaded buffer larger than that
  * included). A single byte is checked on its own.
  */
final class GuardedOutputStream(out: OutputStream, guard: SpaceGuard) extends OutputStream:
  /** Checks done (for tests). */
  var checks = 0

  def write(b: Int): Unit =
    guard.check(1)
    checks += 1
    out.write(b)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    var at = off
    var left = len
    while left > 0 do
      val n = math.min(left, SpaceGuard.Chunk)
      guard.check(n)
      checks += 1
      out.write(b, at, n)
      at += n
      left -= n

  override def flush(): Unit = out.flush()
  override def close(): Unit = out.close()

/** L for every process: the main process writes it to a small file
  * whenever the logging mode or the measured rate changes (a temporary
  * file, synced, renamed, the directory synced); another process (archive
  * maintenance) reads it at every check. A file that is missing, cannot be
  * read or is not a number gives the most conservative L ([[Conservative]]),
  * never the floor.
  */
object ReserveFile:
  /** L of 最高精度 at its default rate: what nothing measured can exceed by default. */
  val Conservative: Long = StorageBudget.loggingReserve(LoggingMode.Max, None)

  def write(f: File, bytes: Long): Unit =
    f.getParentFile.mkdirs()
    val tmp = File(f.getPath + ".tmp")
    val ch = FileChannel.open(tmp.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    try
      ch.write(java.nio.ByteBuffer.wrap(s"$bytes\n".getBytes(UTF_8)))
      ch.force(true)
    finally ch.close()
    Files.move(tmp.toPath, f.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    // The rename is durable once the directory is synced (where the platform allows it).
    Try {
      val d = FileChannel.open(f.getParentFile.toPath, StandardOpenOption.READ)
      try d.force(true) finally d.close()
    }

  def read(f: File): Long =
    Try(String(Files.readAllBytes(f.toPath), UTF_8).trim.toLong).toOption.filter(_ > 0).getOrElse(Conservative)

/** The main process's reservations of its large writes (downloads,
  * extractions, backups), in memory only: a write of `r` bytes starts only
  * if `F - ΣO - L >= r` (F the free space now, O each reservation's
  * unwritten rest, L the logging reserve). Each write itself goes through a
  * [[SpaceGuard]] with `held` = [[outstanding]]. Releasing cannot fail.
  * Thread-safe.
  */
final class StorageBudget(free: () => Long, reserve: () => Long):
  final class Reservation private[StorageBudget] (private var bytes: Long, start: Long):
    private var written = start
    private var open = true

    /** `n` more bytes written. False once the write must stop. */
    def wrote(n: Long): Boolean = StorageBudget.this.synchronized {
      written += n
      okLocked
    }

    /** For a file being brought to `bytes`: it is `size` bytes long now. A
      * file that became shorter (a restart from the start, a discarded
      * partial file) has more to write again: admitted like a new
      * reservation, against what the others hold now. False once the write
      * must stop.
      */
    def at(size: Long): Boolean = StorageBudget.this.synchronized {
      if size < written then
        written = size
        val others = active.filterNot(_ eq this).map(_.rest).sum
        free() - others - reserve() >= rest && okLocked
      else
        written = size
        okLocked
    }

    private[StorageBudget] def rest: Long = if open then math.max(0L, bytes - written) else 0L
    def outstanding: Long = StorageBudget.this.synchronized(rest)

    /** The guard of this reservation's writes: L and what the other
      * reservations will still write (each chunk of its own is checked as
      * it comes, so its own rest is not counted twice).
      */
    def guard: SpaceGuard = SpaceGuard(free, reserve, () => StorageBudget.this.synchronized(active.filterNot(_ eq this).map(_.rest).sum))

    def release(): Unit = StorageBudget.this.synchronized {
      open = false
      active = active.filterNot(_ eq this)
    }

  private var active = List.empty[Reservation]
  private def okLocked: Boolean = free() - active.map(_.rest).sum >= reserve()

  private def admit(bytes: Long, start: Long): Option[Reservation] = synchronized {
    if free() - active.map(_.rest).sum - reserve() < math.max(0L, bytes - start) then None
    else
      val x = Reservation(bytes, start)
      active = x :: active
      Some(x)
  }

  /** A reservation of `r` bytes, or None: `r` does not fit above the
    * logging reserve and what the other writes will still take.
    */
  def reserve(r: Long): Option[Reservation] = admit(r, 0L)

  /** A reservation for a file brought to `target` bytes from `have` (a
    * resumed download): what is left. Report its size with
    * [[Reservation.at]] after every write, a truncation included.
    */
  def reserveFile(target: Long, have: Long): Option[Reservation] = admit(target, math.min(have, target))

  /** What the reservations will still write. */
  def outstanding: Long = synchronized(active.map(_.rest).sum)
  /** The free space, less what reservations will still write, stays at or above the reserve. */
  def ok: Boolean = synchronized(okLocked)
  /** What a new reservation may take now. */
  def available: Long = synchronized(math.max(0L, free() - active.map(_.rest).sum - reserve()))
  def busy: Boolean = synchronized(active.nonEmpty)

  /** The guard of this process's writes: L and every reservation's rest. */
  def guard: SpaceGuard = SpaceGuard(free, reserve, () => outstanding)

object StorageBudget:
  /** The reserve's floor: 256 MB, the monitoring threshold. */
  val MinReserve: Long = 256L * 1000 * 1000
  /** Bytes per hour the logger writes in each mode when no rate is measured
    * (design 6.8; Max: raw lines, journal and output, detail-log-format.md).
    */
  def defaultRate(m: LoggingMode): Long = m match
    case LoggingMode.Eco => 2L * 1000 * 1000
    case LoggingMode.Balanced => 5L * 1000 * 1000
    case LoggingMode.Precise => 20L * 1000 * 1000
    case LoggingMode.Max => 210L * 1000 * 1000

  /** The logger's measured rate from samples (time ms, bytes of its data):
    * the growth from the oldest sample of the last `windowMs` to the newest,
    * if they are at least an hour apart. Shrinking (compaction) counts as none.
    */
  def measuredRate(samples: Seq[(Long, Long)], now: Long, windowMs: Long = 6L * 3600 * 1000): Option[Long] =
    val in = samples.filter((t, _) => t >= now - windowMs && t <= now).sortBy(_._1)
    for
      a <- in.headOption
      b <- in.lastOption
      if b._1 - a._1 >= 3600L * 1000
    yield math.max(0L, (b._2 - a._2) * 3600L * 1000 / (b._1 - a._1))

  /** L: the larger of the floor and a day of logging at the higher of the
    * measured rate and the mode's default.
    */
  def loggingReserve(mode: LoggingMode, measured: Option[Long]): Long =
    math.max(MinReserve, math.max(measured.getOrElse(0L), defaultRate(mode)) * 24)

  /** The guard stopped a write (it would cut into the logging reserve). */
  final class NoRoom extends IOException("no room above the logging reserve")

/** Old text logs imported in batches of at most 5,000 lines and 1 MiB of
  * text (a longer line alone), so that each insert is checked against the
  * free space before it (design 6.8).
  */
object LegacyBatches:
  val MaxLines = 5000
  val MaxBytes: Long = SpaceGuard.Chunk.toLong

  def apply(lines: Iterator[String]): Iterator[Seq[String]] = new Iterator[Seq[String]]:
    private val it = lines.buffered
    def hasNext: Boolean = it.hasNext
    def next(): Seq[String] =
      val b = scala.collection.mutable.ArrayBuffer.empty[String]
      var bytes = 0L
      while it.hasNext && b.size < MaxLines && (b.isEmpty || bytes + utf8(it.head) <= MaxBytes) do
        val l = it.next()
        bytes += utf8(l)
        b += l
      b.toSeq

  def utf8(s: String): Long = s.getBytes(UTF_8).length.toLong
