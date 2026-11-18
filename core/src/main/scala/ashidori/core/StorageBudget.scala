package ashidori.core

import java.io.{File, IOException, OutputStream}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import scala.util.Try

/** Guards the logging reserve against the app's large writes (design 6.8).
  *
  * The guarantee for files: the free space never goes below the logging reserve `L`
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

/** The floor published by the main process for the other one: L of the
  * logging mode in effect and what the main process's reservations will
  * still write, kept apart, and when it was written.
  */
final case class Published(l: Long, held: Long, at: Long):
  def floor: Long = l + held

/** The floor file. The main process writes it ([[FloorPublisher]]) as a
  * temporary file, synced, renamed, the directory synced; a failure of any
  * step (the directory's sync included) is thrown. Another process (archive
  * maintenance) reads it at every check ([[floor]]).
  */
object ReserveFile:
  /** L of 最高精度 at its default rate: what nothing measured can exceed by default. */
  val Conservative: Long = StorageBudget.loggingReserve(LoggingMode.Max, None)
  val HeartbeatMs: Long = 10L * 60 * 1000

  def write(f: File, p: Published): Unit =
    f.getParentFile.mkdirs()
    val tmp = File(f.getPath + ".tmp")
    val ch = FileChannel.open(tmp.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    try
      ch.write(java.nio.ByteBuffer.wrap(s"${p.l} ${p.held} ${p.at}\n".getBytes(UTF_8)))
      ch.force(true)
    finally ch.close()
    Files.move(tmp.toPath, f.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    // The rename is durable once the directory is synced; a failure is the caller's to report.
    val d = FileChannel.open(f.getParentFile.toPath, StandardOpenOption.READ)
    try d.force(true) finally d.close()

  /** The file's content; None if it is missing, cannot be read, or is not three numbers as written. */
  def read(f: File): Option[Published] =
    Try {
      val Array(l, h, t) = String(Files.readAllBytes(f.toPath), UTF_8).trim.split(' ')
      Published(l.toLong, h.toLong, t.toLong)
    }.toOption.filter(p => p.l > 0 && p.held >= 0 && p.at > 0)

  /** The floor another process uses: the larger of what the main process
    * published and `ownL` (its own L, measured from the record). Missing,
    * unreadable or corrupt: [[Conservative]] (also when from the future: a
    * clock set back). However old, a readable file holds the last floor the
    * main process published (every raise is published before it takes
    * effect): its L stands. Its reservations stand while the main process
    * lives (`mainAlive`: it holds its lock, [[MainAlive]]); once it is gone,
    * they can write nothing more and are dropped.
    */
  def floor(p: Option[Published], now: Long, mainAlive: => Boolean, ownL: Long): Long =
    p.filter(_.at <= now + 60_000) match
      case None => math.max(Conservative, ownL)
      case Some(x) => math.max(if mainAlive then x.floor else x.l, ownL)

/** The main process's liveness, for the other process: it holds a lock on a
  * file for as long as it lives (the system releases it when the process
  * ends, however it ends). Another process that can take the lock knows it
  * is gone.
  */
object MainAlive:
  /** Takes the lock (waiting for an ending process that still holds it) and keeps it. */
  def hold(f: File): FileChannel =
    f.getParentFile.mkdirs()
    val ch = FileChannel.open(f.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    ch.lock()
    ch

  /** Whether another process holds the lock (true also if it cannot be told). */
  def alive(f: File): Boolean =
    try
      val ch = FileChannel.open(f.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val l = ch.tryLock()
        if l == null then true
        else
          l.release()
          false
      finally ch.close()
    catch
      // Held in this process (a test).
      case _: java.nio.channels.OverlappingFileLockException => true
      case _: Exception => true

/** Publishes the floor ([[ReserveFile]]): L of the mode in effect and what
  * the reservations hold. One lock orders every publication and every mode
  * change: a reservation's publication reads the mode inside it, and a mode
  * change publishes the floor of the new mode, then stores the mode (it
  * takes effect then), before any other publication. A failure removes the
  * file (the other process then uses the conservative L) and is thrown.
  */
final class FloorPublisher(file: File, mode: () => LoggingMode, lOf: LoggingMode => Long, held: () => Long,
                           now: () => Long = () => System.currentTimeMillis(),
                           write: (File, Published) => Unit = ReserveFile.write):
  @volatile private var last: Option[Published] = None

  private def put(m: LoggingMode): Unit =
    val p = Published(lOf(m), held(), now())
    try
      write(file, p)
      last = Some(p)
    catch case e: Exception =>
      last = None
      Try(file.delete())
      throw e

  /** The floor of the mode in effect, now; thrown if it could not be written. */
  def publish(): Unit = synchronized(put(mode()))

  /** As [[publish]], unless the same floor was written less than `maxAgeMs` ago. */
  def refresh(maxAgeMs: Long): Unit = synchronized {
    val m = mode()
    val t = now()
    if !last.exists(p => p.l == lOf(m) && p.held == held() && t - p.at < maxAgeMs) then put(m)
  }

  /** A new mode: its floor published, then `store` (the mode takes effect).
    * Stored also if the floor could not be written (it was removed: the
    * other process uses the conservative L); the failure is then thrown.
    */
  def changeMode(m: LoggingMode)(store: => Unit): Unit = synchronized {
    val failed = Try(put(m)).failed.toOption
    store
    failed.foreach(throw _)
  }

/** Bulk SQLite writes that are not the record (deleting archived rows,
  * importing old logs): what a batch may change is known only afterwards,
  * so a batch is bounded twice. Its rows are capped so that even if every
  * row is on its own pages it changes at most [[Margin]] (16 MiB) of pages:
  * a row's table page and one page per index, twice over for interior and
  * free-list pages, of [[PageBytes]]. Within that cap, [[BatchSizer]] sizes
  * the batches by what they made the files grow. Before each batch, the
  * free space must leave the floor and [[Margin]].
  */
object SqliteBatch:
  val Margin: Long = 16L << 20
  /** A batch that grew the files by more than this makes the next one smaller at once. */
  val Grow: Long = 4L << 20
  val PageBytes: Long = 4096
  /** Rows of a table with `indexes` indexes that change at most [[Margin]] of pages, however scattered. */
  def maxRows(indexes: Int): Int = (Margin / (PageBytes * (1 + indexes) * 2)).toInt

/** Sizes the batches of a bulk SQLite write, in rows or bytes of text, by
  * what each made the database and its WAL grow: from `start`, doubled only
  * while a batch grows them by less than half [[SqliteBatch.Grow]] (so the
  * next stays under it), kept between that and [[SqliteBatch.Grow]], made
  * smaller at once (in proportion) after a batch that grew them by more;
  * between `min` and `max`. A batch that did not grow them (SQLite reused
  * the WAL or freed pages) counts as small: `max` bounds it.
  */
final class BatchSizer(start: Int = 64, min: Int = 1, max: Int = SqliteBatch.maxRows(1)):
  private var n = math.max(min, math.min(max, start))
  def rows: Int = n
  /** A batch of `size` made the files grow by `grown` bytes. */
  def observe(size: Int, grown: Long): Unit =
    if grown > SqliteBatch.Grow then
      n = math.max(min, math.min(n, (size.toLong * (SqliteBatch.Grow / 2) / grown).toInt))
    else if grown < SqliteBatch.Grow / 2 && size.toLong * 2 >= n then
      n = math.min(max, n.toLong * 2).toInt

/** The main process's reservations of its large writes (downloads,
  * extractions, backups), in memory only: a write of `r` bytes starts only
  * if `F - ΣO - L >= r` (F the free space now, O each reservation's
  * unwritten rest, L the logging reserve). Each write itself goes through a
  * [[SpaceGuard]] with `held` = [[outstanding]]. Releasing cannot fail.
  * Thread-safe.
  */
final class StorageBudget(free: () => Long, reserve: () => Long, changed: () => Unit = () => ()):
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
    def at(size: Long): Boolean =
      val (ok, grew) = StorageBudget.this.synchronized {
        if size < written then
          written = size
          val others = active.filterNot(_ eq this).map(_.rest).sum
          (free() - others - reserve() >= rest && okLocked, true)
        else
          written = size
          (okLocked, false)
      }
      // More to write again: published before it is written (or it stops).
      ok && (!grew || Try(changed()).isSuccess)

    private[StorageBudget] def rest: Long = if open then math.max(0L, bytes - written) else 0L
    def outstanding: Long = StorageBudget.this.synchronized(rest)

    /** The guard of this reservation's writes: L and what the other
      * reservations will still write (each chunk of its own is checked as
      * it comes, so its own rest is not counted twice).
      */
    def guard: SpaceGuard = SpaceGuard(free, reserve, () => StorageBudget.this.synchronized(active.filterNot(_ eq this).map(_.rest).sum))

    /** Gives the rest back; then the floor is published again (`changed`, outside the lock). */
    def release(): Unit =
      val was = StorageBudget.this.synchronized {
        val o = open
        open = false
        active = active.filterNot(_ eq this)
        o
      }
      if was then try changed() catch case _: Exception => ()

  private var active = List.empty[Reservation]
  private def okLocked: Boolean = free() - active.map(_.rest).sum >= reserve()

  /** Admitted reservations are published (`changed`) before they are given
    * out, so another process sees them in the floor before anything is
    * written; one whose publication fails is refused.
    */
  private def admit(bytes: Long, start: Long): Option[Reservation] =
    val r = synchronized {
      if free() - active.map(_.rest).sum - reserve() < math.max(0L, bytes - start) then None
      else
        val x = Reservation(bytes, start)
        active = x :: active
        Some(x)
    }
    // Not published: refused (the floor file is gone; the other process uses
    // the conservative L, which does not include this reservation).
    r.filter { x =>
      try { changed(); true }
      catch case _: Exception =>
        x.release()
        false
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

/** Old text logs imported in batches of at most 2,000 lines and a budget
  * of text (a longer line alone) that the importer adapts to what each
  * batch made the database grow, so that each insert is checked against the
  * free space before it (design 6.8).
  */
object LegacyBatches:
  /** With 1 MiB of text, at most [[SqliteBatch.Margin]] of pages (the raw lines' table and time index). */
  val MaxLines = 2000
  val MaxBytes: Long = SpaceGuard.Chunk.toLong
  /** A line longer than this (UTF-8) is no NMEA or GNSS record: left out of an import. */
  val MaxLineBytes: Long = 64L * 1024

  /** Batches of at most `maxBytes()` bytes of text (asked before each
    * batch, so it can follow what the last batch made the database grow)
    * and 5,000 lines; a longer line alone.
    */
  def apply(lines: Iterator[String], maxBytes: () => Long = () => MaxBytes): Iterator[Seq[String]] = new Iterator[Seq[String]]:
    private val it = lines.buffered
    def hasNext: Boolean = it.hasNext
    def next(): Seq[String] =
      val limit = maxBytes()
      val b = scala.collection.mutable.ArrayBuffer.empty[String]
      var bytes = 0L
      while it.hasNext && b.size < MaxLines && (b.isEmpty || bytes + utf8(it.head) <= limit) do
        val l = it.next()
        bytes += utf8(l)
        b += l
      b.toSeq

  def utf8(s: String): Long = s.getBytes(UTF_8).length.toLong
