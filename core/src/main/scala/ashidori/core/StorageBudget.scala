package ashidori.core

import java.io.{File, IOException, OutputStream}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import scala.util.Try

/** Where the budget's reservations and the logging reserve live: in this
  * process ([[BudgetLedger.Memory]]), or in a small file every process of
  * the app uses under a file lock ([[BudgetLedger.Shared]]).
  */
trait BudgetLedger:
  /** Runs `f` on the ledger as it is now and stores what it returns, as one step. */
  def transact[A](f: BudgetLedger.State => (BudgetLedger.State, A)): A
  /** How many bytes a reservation may write between its reports (and
    * between its readings of the others and of L).
    */
  def flushBytes: Long

object BudgetLedger:
  /** One reservation: `owner` (a process, [[StorageBudget]]), `bytes` it may
    * write in all, `written` so far.
    */
  final case class Entry(id: String, owner: String, bytes: Long, written: Long):
    def outstanding: Long = math.max(0L, bytes - written)

  /** `reserve`: the logging reserve L as the main process last published it. */
  final case class State(reserve: Option[Long], entries: Vector[Entry])
  val Empty: State = State(None, Vector.empty)

  final class Memory extends BudgetLedger:
    def flushBytes: Long = 0L
    private var s = Empty
    def transact[A](f: State => (State, A)): A = synchronized {
      val (n, a) = f(s)
      s = n
      a
    }

  /** The ledger in `file`, written whole under an exclusive lock of
    * `<file>.lock` (FileChannel.lock between processes, a monitor between
    * the threads of one) and replaced by rename. A file that cannot be read
    * counts as empty.
    */
  final class Shared(file: File, val flushBytes: Long = 1L << 20) extends BudgetLedger:
    private val lockFile = File(file.getPath + ".lock")
    private val monitor = Shared.monitor(file)
    def transact[A](f: State => (State, A)): A = monitor.synchronized {
      file.getParentFile.mkdirs()
      val ch = FileChannel.open(lockFile.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = ch.lock()
        try
          val now = Try(String(Files.readAllBytes(file.toPath), UTF_8)).toOption.flatMap(decode).getOrElse(Empty)
          val (n, a) = f(now)
          if n != now then
            val tmp = File(file.getPath + ".tmp")
            Files.write(tmp.toPath, encode(n).getBytes(UTF_8))
            Files.move(tmp.toPath, file.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          a
        finally lock.release()
      finally ch.close()
    }

  object Shared:
    private val monitors = scala.collection.mutable.Map.empty[String, Object]
    private def monitor(f: File): Object = synchronized(monitors.getOrElseUpdate(f.getCanonicalPath, new Object))

  val Format = "ashidori storage budget v1"

  def encode(s: State): String =
    val o = ujson.Obj("format" -> Format,
      "entries" -> ujson.Arr(s.entries.map(e => ujson.Obj("id" -> e.id, "owner" -> e.owner,
        "bytes" -> e.bytes.toDouble, "written" -> e.written.toDouble))*))
    s.reserve.foreach(r => o("reserve") = r.toDouble)
    ujson.write(o)

  def decode(json: String): Option[State] =
    Try {
      val o = ujson.read(json).obj
      require(o("format").str == Format)
      State(o.get("reserve").map(_.num.toLong),
        o("entries").arr.map(e => Entry(e("id").str, e("owner").str, e("bytes").num.toLong, e("written").num.toLong)).toVector)
    }.toOption

/** The storage budget of the app's large writes (design 6.8), shared by all
  * of them and by the app's processes when its ledger is shared: the main
  * process (the basemap and venue downloads and extractions, the backups,
  * the region packs) and the maintenance process (compaction, the monthly
  * merge, cutting deleted ranges, the import of old logs).
  *
  * A write of `r` bytes starts only if `F - ΣO - L >= r`, and stops while it
  * runs once `F - ΣO < L`, where `F` is the free space now (`free`), `O`
  * each reservation's unwritten rest and `L` the logging reserve: the
  * larger of this process's own (`reserve`) and the one the main process
  * published in the ledger ([[publish]]), read at every check.
  *
  * Reservations carry their `owner` (a process: id and start); entries of
  * owners no longer alive (`alive`) are dropped under the lock, so a
  * process that died leaves nothing reserved. A reservation reports its
  * writes to a shared ledger at least every MiB (and at every truncation),
  * reading the others and L then; between, it checks the free space now
  * against what the ledger said last.
  * Thread-safe.
  */
final class StorageBudget(free: () => Long, reserve: () => Long, ledger: BudgetLedger = BudgetLedger.Memory(),
                          owner: String = "local", alive: String => Boolean = _ => true):
  import BudgetLedger.{Entry, State}
  private val ids = java.util.concurrent.atomic.AtomicLong()

  private def pruned(s: State): State =
    val keep = s.entries.filter(e => e.owner == owner || alive(e.owner))
    if keep.size == s.entries.size then s else s.copy(entries = keep)
  private def l(s: State): Long = math.max(reserve(), s.reserve.getOrElse(0L))
  private def outstanding(s: State): Long = s.entries.map(_.outstanding).sum
  private def read[A](f: State => A): A = ledger.transact { s0 =>
    val s = pruned(s0)
    (s, f(s))
  }

  /** A reservation of a write of `bytes` in all, `start` of them written already. */
  final class Reservation private[StorageBudget] (val id: String, private var bytes: Long, start: Long):
    private var written = start
    private var flushed = start
    private var cached = (0L, 0L) // others' outstanding, L
    private var open = true

    private def refresh(s: State): Unit =
      cached = (s.entries.filterNot(_.id == id).map(_.outstanding).sum, l(s))
    private[StorageBudget] def seen(s: State): Unit = synchronized(refresh(s))
    /** Writes this reservation's state to the ledger and reads the others'. */
    private def flush(): Unit =
      ledger.transact { s0 =>
        val s = pruned(s0)
        val n = s.copy(entries = s.entries.map(e => if e.id == id then e.copy(bytes = bytes, written = written) else e))
        refresh(n)
        (n, ())
      }
      flushed = written
    private def okNow: Boolean = free() - cached._1 - math.max(0L, bytes - written) >= cached._2

    /** `n` more bytes written. False once the write must stop: the free
      * space would cut into the logging reserve.
      */
    def wrote(n: Long): Boolean = synchronized {
      written += n
      if math.abs(written - flushed) >= ledger.flushBytes then flush()
      okNow
    }

    /** For a file being brought to `bytes`: it is `size` bytes long now. A
      * file that became shorter (a restart from the start, a discarded
      * partial file) has more to write again: admitted like a new
      * reservation, against what the others hold now. False once the write
      * must stop.
      */
    def at(size: Long): Boolean = synchronized {
      if size < written then
        written = size
        flush()
        free() - cached._1 - math.max(0L, bytes - written) >= cached._2
      else
        written = size
        if written - flushed >= ledger.flushBytes then flush()
        okNow
    }

    /** `n` more bytes to write than reserved (more input came): admitted
      * like a new reservation. False (nothing changed) if it does not fit.
      */
    def grow(n: Long): Boolean = synchronized {
      ledger.transact { s0 =>
        val s = pruned(s0)
        val others = s.entries.filterNot(_.id == id).map(_.outstanding).sum
        if free() - others - math.max(0L, bytes - written) - l(s) < n then
          refresh(s)
          (s, false)
        else
          bytes += n
          val ns = s.copy(entries = s.entries.map(e => if e.id == id then e.copy(bytes = bytes, written = written) else e))
          refresh(ns)
          flushed = written
          (ns, true)
      }
    }

    def outstanding: Long = synchronized(if open then math.max(0L, bytes - written) else 0L)

    def release(): Unit = synchronized {
      if open then
        open = false
        ledger.transact(s => (s.copy(entries = s.entries.filterNot(_.id == id)), ()))
    }

  private def admit(bytes: Long, start: Long): Option[Reservation] =
    val id = s"$owner#${ids.incrementAndGet()}#${System.nanoTime()}"
    ledger.transact { s0 =>
      val s = pruned(s0)
      if free() - outstanding(s) - l(s) < math.max(0L, bytes - start) then (s, None)
      else
        val x = Reservation(id, bytes, start)
        val n = s.copy(entries = s.entries :+ Entry(id, owner, bytes, start))
        x.seen(n)
        (n, Some(x))
    }

  /** A reservation of `r` bytes, or None: `r` does not fit above the
    * logging reserve and what the other writes (of any process) will still take.
    */
  def reserve(r: Long): Option[Reservation] = admit(r, 0L)

  /** A reservation for a file brought to `target` bytes from `have` (a
    * resumed download): what is left. Report its size with
    * [[Reservation.at]] after every write, a truncation included.
    */
  def reserveFile(target: Long, have: Long): Option[Reservation] = admit(target, math.min(have, target))

  /** The main process publishes L (its logging mode and measured rate) for every process. */
  def publish(reserveBytes: Long): Unit =
    ledger.transact { s0 =>
      val s = pruned(s0)
      if s.reserve.contains(reserveBytes) then (s, ()) else (s.copy(reserve = Some(reserveBytes)), ())
    }

  /** L now: the larger of this process's and the published one. */
  def loggingReserve: Long = read(l)

  /** The free space, less what reservations will still write, stays at or above the reserve. */
  def ok: Boolean = read(s => free() - outstanding(s) >= l(s))
  /** What a new reservation may take now. */
  def available: Long = read(s => math.max(0L, free() - outstanding(s) - l(s)))
  def busy: Boolean = read(_.entries.nonEmpty)
  /** Reservations of every process (for diagnostics). */
  def entries: Seq[Entry] = read(_.entries)


/** An output stream that reports what it writes to a reservation (every
  * write, through [[StorageBudget.Reservation.wrote]]) and fails with
  * [[StorageBudget.NoRoom]] once the budget says stop: whatever writes
  * through it (Parquet, ZIP) stops before it cuts into the logging reserve.
  */
final class BudgetedOutputStream(out: OutputStream, room: StorageBudget#Reservation) extends OutputStream:
  def write(b: Int): Unit =
    out.write(b)
    if !room.wrote(1) then throw StorageBudget.NoRoom()
  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    out.write(b, off, len)
    if !room.wrote(len) then throw StorageBudget.NoRoom()
  override def flush(): Unit = out.flush()
  override def close(): Unit = out.close()

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

  /** The budget stopped a write (it would cut into the logging reserve). */
  final class NoRoom extends IOException("no room above the logging reserve")
