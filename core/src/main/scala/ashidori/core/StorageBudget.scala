package ashidori.core

import java.io.{IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** The free space the app's large writes leave (design 6.8, 11.2). Three
  * separate promises, none of them a bound on the device's free space:
  *
  *  - Recorded data is never deleted before its copy is verified and
  *    durable (archive commits, rules A–C); a stop for room keeps the
  *    original or the committed copy.
  *  - Downloads and their extraction write through a [[SpaceGuard]] in
  *    outputs of at most [[SpaceGuard.Chunk]] (C, 1 MiB), each checked just
  *    before it is written against the target free space for logging `L`
  *    (a day at the mode's default or measured rate: an estimate, not a
  *    guarantee of logging time) and what the main process's other
  *    reservations will still write ([[StorageBudget]]).
  *  - Maintenance (file rewrites, bulk SQLite writes) uses the fixed
  *    stopping point `H` ([[Headroom]]). SQLite batches and the database
  *    copy of a backup are checked before, watched and checked after;
  *    no bound on what one of them consumes is claimed.
  *
  * Logging itself and its overflow files are never refused by L or H. When
  * logging runs short of space, large work is stopped and the logger's
  * emergency reserve released, and its batches go to overflow files
  * ([[EmergencyReserve]]); large work runs only while that reserve is ready.
  */
object Headroom:
  /** H: maintenance (archive rewrites, bulk SQLite writes, the visit index) stops below it. */
  val H: Long = 256L * 1000 * 1000
  /** M: what a bulk SQLite batch needs above H to start (an operating margin, not a bound). */
  val M: Long = 16L << 20

/** A check of the free space before each output of a large writer:
  * `free()` (the free space now) must leave `reserve()` (L or H), `held()`
  * (what the main process's other reservations will still write; 0 for
  * maintenance) and the output itself.
  *
  * In a model where L is fixed, the free space reflects each completed
  * output exactly, an output takes exactly its bytes and each writer has
  * one output in progress at a time, the outputs checked and not yet
  * reflected are at most N × C for N writers (StorageBudgetSuite). The
  * device is not that model (allocation units, metadata, buffers below,
  * SQLite, other apps): what is promised is where the checks are and how
  * large each output is, not a bound on the free space.
  *
  * `open()`: large work may run at all (the logger's emergency reserve is
  * ready and no emergency is in progress, [[EmergencyReserve]]); closed, every
  * check fails, so work stops at its next output or batch.
  */
final class SpaceGuard(free: () => Long, reserve: () => Long, held: () => Long = () => 0L, open: () => Boolean = () => true):
  /** The chunk `n` (at most [[SpaceGuard.Chunk]]) may be written now. */
  def allows(n: Long): Boolean = open() && free() - reserve() - held() >= n
  /** As [[allows]], or NoRoom. */
  def check(n: Long): Unit = if !allows(n) then throw StorageBudget.NoRoom()

object SpaceGuard:
  val Chunk: Int = 1 << 20

/** An output stream for a large writer, placed directly above the file:
  * every write is split into outputs of at most C (1 MiB), each checked
  * against the guard before it is written (a single row group, ZIP entry
  * or downloaded buffer larger than that included; what a compressor or a
  * buffer above writes at its flush or close comes through here too). A
  * single byte is checked on its own; flush and close write nothing here.
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

/** Sizes the batches of a bulk SQLite write (deleting archived rows, in
  * rows; importing old logs, in bytes of text) from what each made the
  * database and its WAL grow. An empirical control of the next batch's
  * load, not a bound: from `start` (64 rows), doubled only while a batch
  * grows them by less than 2 MiB, kept up to 4 MiB, made smaller at once
  * (in proportion) after a batch that grew them by more; between `min`
  * and `max` (512 rows: an operating value). Long rows, BLOBs and sparse
  * deletes may still change more in one batch.
  */
final class BatchSizer(start: Int = 64, min: Int = 1, max: Int = BatchSizer.MaxRows):
  private var n = math.max(min, math.min(max, start))
  def rows: Int = n
  /** A batch of `size` made the files grow by `grown` bytes. */
  def observe(size: Int, grown: Long): Unit =
    if grown > BatchSizer.Grow then
      n = math.max(min, math.min(n, (size.toLong * (BatchSizer.Grow / 2) / grown).toInt))
    else if grown < BatchSizer.Grow / 2 && size.toLong * 2 >= n then
      n = math.min(max, n.toLong * 2).toInt

object BatchSizer:
  val MaxRows = 512
  val Grow: Long = 4L << 20

/** One bulk SQLite write in batches (design 6.8): before each batch the
  * free space must leave H and M (`guard.allows(M)`), and after it again,
  * the last batch included; a shortfall after a batch keeps what it
  * committed, starts no next batch and is reported to the caller
  * ([[StorageBudget.NoRoom]]: deferred to a later run). No row is ever
  * dropped to go on. `batch(n)` does one transaction of up to `n`
  * rows and gives (rows done, rows asked, more left); `files()` is the
  * database and its WAL's size; each batch's growth and the free space
  * after it go to `seen`.
  */
object BulkLoop:
  final case class Batch(rows: Int, grown: Long, freeAfter: Long)

  def run(sizer: BatchSizer, guard: SpaceGuard, free: () => Long, files: () => Long,
          seen: Batch => Unit = _ => ())(batch: Int => (Int, Int, Boolean)): Long =
    var total = 0L
    var more = true
    while more do
      guard.check(Headroom.M)
      val before = files()
      val (done, asked, left) = batch(sizer.rows)
      val grown = files() - before
      sizer.observe(asked, grown)
      seen(Batch(asked, grown, free()))
      total += done
      more = left
      guard.check(Headroom.M)
    total

/** The main process's reservations of large writes above L. `gate()` as
  * [[SpaceGuard]]'s `open()`: closed, nothing is admitted and every reservation must stop.
  */
final class StorageBudget(free: () => Long, reserve: () => Long, gate: () => Boolean = () => true):
  final class Reservation private[StorageBudget] (private var bytes: Long, start: Long):
    private var written = start
    private var open = true

    /** `n` more bytes written. False once the write must stop. */
    def wrote(n: Long): Boolean = StorageBudget.this.synchronized {
      written += n
      okLocked
    }

    /** `total` bytes written in all so far (its rest shrinks; nothing decided). */
    def account(total: Long): Unit = StorageBudget.this.synchronized {
      written = math.max(written, total)
    }

    /** Whether a write whose estimate decided only its start goes on with an
      * output of `n`: the free space must leave L, the other reservations'
      * rest and `n`; its own estimate, used up or not, is not a reason to stop.
      */
    def continues(n: Long): Boolean = guard.allows(n)

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
        gate() && free() - others - reserve() >= rest && okLocked
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
    def guard: SpaceGuard = SpaceGuard(free, reserve, () => StorageBudget.this.synchronized(active.filterNot(_ eq this).map(_.rest).sum), gate)

    /** Gives the rest back. */
    def release(): Unit = StorageBudget.this.synchronized {
      open = false
      active = active.filterNot(_ eq this)
    }

  private var active = List.empty[Reservation]
  private def okLocked: Boolean = gate() && free() - active.map(_.rest).sum >= reserve()

  private def admit(bytes: Long, start: Long): Option[Reservation] = synchronized {
    if !gate() || free() - active.map(_.rest).sum - reserve() < math.max(0L, bytes - start) then None
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
  def available: Long = synchronized(if !gate() then 0L else math.max(0L, free() - active.map(_.rest).sum - reserve()))
  def busy: Boolean = synchronized(active.nonEmpty)

  /** The guard of this process's writes: L and every reservation's rest. */
  def guard: SpaceGuard = SpaceGuard(free, reserve, () => outstanding, gate)

object StorageBudget:
  /** L's floor: 256 MB, the monitoring threshold. */
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

  /** L, the target free space for logging: the larger of the floor and a
    * day at the higher of the measured rate and the mode's default. An
    * estimate (the rate is measured from the data's size, which compaction
    * shrinks), not a bound on what logging takes.
    */
  def loggingReserve(mode: LoggingMode, measured: Option[Long]): Long =
    math.max(MinReserve, math.max(measured.getOrElse(0L), defaultRate(mode)) * 24)

  /** A guard stopped a write, or deferred the next batch (too little free space). */
  final class NoRoom extends IOException("too little free space")

/** Old text logs imported in batches of at most 2,000 lines and a budget
  * of text (a longer line alone) that the importer adapts to what each
  * batch made the database grow ([[BatchSizer]]), each checked as a bulk
  * SQLite batch (design 6.8).
  */
object LegacyBatches:
  val MaxLines = 2000
  val MaxBytes: Long = SpaceGuard.Chunk.toLong
  /** A line longer than this (UTF-8) is not imported: the file is kept and shown as not imported. */
  val MaxLineBytes: Long = 64L * 1024

  /** Batches of at most `maxBytes()` bytes of text (asked before each
    * batch, so it can follow what the last batch made the database grow)
    * and 2,000 lines; a longer line alone.
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
