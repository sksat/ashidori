package ashidori.core

import scala.collection.mutable

/** Streaming merge of archive rows, for writing a day or month file without
  * holding its rows in memory.
  *
  * Rows are ordered by their first column (the time; a row without one sorts
  * last). Both inputs are in that order: an archive file (written sorted) and
  * new rows (read from SQLite in time order). Exact repeats are dropped:
  * equal rows have the same time, so only the hashes of the rows at the
  * current time are kept, whatever the size of the file.
  */
object ArchiveMerge:
  type Row = IndexedSeq[Any]

  /** The sort key: the time in the first column, or Long.MaxValue. */
  def key(r: Row): Long =
    if r.isEmpty then Long.MaxValue
    else
      r(0) match
        case l: Long => l
        case i: Int  => i.toLong
        case _       => Long.MaxValue

  /** 64-bit hash of a row's values. Numbers hash by value (Int and Long
    * alike; Float and Double alike), NaN like null, as the Parquet writer
    * stores NaN as null.
    */
  def hash(r: Row): Long =
    var h = 0x2545f4914f6cdd1dL ^ r.size
    var i = 0
    while i < r.size do
      val v: Long = r(i) match
        case null                  => 0x1L
        case d: Double if d.isNaN  => 0x1L
        case f: Float if f.isNaN   => 0x1L
        case l: Long               => mix(l ^ 0x2L)
        case n: Int                => mix(n.toLong ^ 0x2L)
        case d: Double             => mix(java.lang.Double.doubleToLongBits(d) ^ 0x3L)
        case f: Float              => mix(java.lang.Double.doubleToLongBits(f.toDouble) ^ 0x3L)
        case s: String             => string(s)
        case other                 => mix(other.##.toLong)
      h = mix(h * 31 + v)
      i += 1
    h

  /** splitmix64 finalizer. */
  private def mix(x0: Long): Long =
    var x = x0
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL
    x ^ (x >>> 31)

  private def string(s: String): Long =
    var h = 0xcbf29ce484222325L // FNV-1a 64
    var i = 0
    while i < s.length do
      h = (h ^ s.charAt(i)) * 0x100000001b3L
      i += 1
    mix(h ^ 0x4L)

  /** Whether two rows hold the same values (as [[hash]] sees them: Int like
    * Long, Float like Double, NaN like null).
    */
  def same(a: Row, b: Row): Boolean =
    def norm(v: Any): Any = v match
      case d: Double if d.isNaN => null
      case f: Float if f.isNaN  => null
      case n: Int               => n.toLong
      case f: Float             => f.toDouble
      case other                => other
    a.size == b.size && a.indices.forall(i => norm(a(i)) == norm(b(i)))

  /** Passes rows on to `out`, dropping a row equal to one already passed
    * with the same time: rows of the current time are kept by their
    * [[hash]], and compared value by value when hashes match. Expects rows
    * in time order.
    */
  final class Distinct(out: Row => Unit):
    private var current = Long.MinValue
    private val seen = mutable.HashMap.empty[Long, List[Row]]
    var written = 0L
    var dropped = 0L

    def apply(r: Row): Unit =
      val k = key(r)
      if k != current then
        current = k
        seen.clear()
      val h = hash(r)
      val same = seen.getOrElse(h, Nil)
      if same.exists(ArchiveMerge.same(_, r)) then dropped += 1
      else
        seen(h) = r :: same
        out(r)
        written += 1

  /** Merges `existing` (sorted) with rows pushed in time order: before each
    * pushed row, the existing rows up to its time are written (existing rows
    * first at equal times). [[finish]] writes the rest. Exact repeats are
    * dropped.
    */
  final class Merge(existing: Iterator[Row], out: Row => Unit):
    private val buffered = existing.buffered
    val distinct = Distinct(out)

    def push(r: Row): Unit =
      val k = key(r)
      while buffered.hasNext && key(buffered.head) <= k do distinct(buffered.next())
      distinct(r)

    def finish(): Unit =
      while buffered.hasNext do distinct(buffered.next())

    def written: Long = distinct.written

  /** Puts rows that arrive almost in time order into order: rows are held
    * until the `watermark` (the time the input has reached) is more than
    * `windowMs` past them. Detail-log lines are read in the order of their
    * stored time, and a gyroscope or magnetometer line stored by older
    * versions has the time of the record before it, up to seconds off its
    * own. Rows of equal time keep their order. Memory: the rows of one
    * window. A row older than one already passed on would break the order:
    * it goes to `tooLate` instead (the caller keeps it for a later, sorted
    * pass), and [[late]] counts them.
    */
  final class Reorder[T](windowMs: Long, keyOf: T => Long, out: T => Unit, tooLate: T => Unit = (_: T) => ()):
    private val held = mutable.PriorityQueue.empty[(Long, Long, T)](using
      Ordering.by[(Long, Long, T), (Long, Long)](x => (x._1, x._2)).reverse)
    private var seq = 0L
    private var emitted = Long.MinValue
    var late = 0L

    def push(r: T, watermark: Long): Unit =
      val k = keyOf(r)
      if k < emitted then
        late += 1
        tooLate(r)
      else
        held.enqueue((k, seq, r))
        seq += 1
      release(if watermark == Long.MinValue then Long.MinValue else watermark - windowMs)

    private def release(upTo: Long): Unit =
      while held.nonEmpty && held.head._1 < upTo do
        val (k, _, r) = held.dequeue()
        emitted = k
        out(r)

    /** Passes on everything held. */
    def flush(): Unit =
      while held.nonEmpty do
        val (k, _, r) = held.dequeue()
        emitted = k
        out(r)

  /** [[Merge]] of an archive file with new rows into `writer`, copying the
    * file's row groups that lie wholly before the next new row as they are
    * stored (by their time statistics), without decoding them. Archiving a
    * long day goes in portions, each rewriting the day's file: so the part
    * archived before costs a copy, not a decode and encode per portion.
    * Groups without statistics (files of earlier versions) are decoded, and
    * so are all groups of a file of an earlier layout (fewer columns than
    * the writer: see [[Archive.open]]). New rows come in time order (see
    * [[Reorder]]).
    */
  final class FileMerge(existing: Option[parquet.ParquetReader.FileReader], writer: parquet.ParquetWriter):
    // Would lose the values of the columns left out.
    require(existing.forall(_.dropped.isEmpty), "existing file has columns the rows leave out")
    private val groups = existing.map(_.meta.groups).getOrElse(Nil)
    private var next = 0
    private var decoded: BufferedIterator[Row] = Iterator.empty.buffered
    private val distinct = Distinct(r => writer.write(r))
    var copied = 0L

    /** Passes on the existing rows up to time `k` (all of them for Long.MaxValue). */
    private def upTo(k: Long, all: Boolean): Unit =
      var more = true
      while more do
        while decoded.hasNext && (all || key(decoded.head) <= k) do distinct(decoded.next())
        if decoded.hasNext || next >= groups.size then more = false
        else
          val g = groups(next)
          val reader = existing.get
          if reader.asStored && g.time.exists((_, hi) => all || hi < k) then
            writer.copyGroup(reader.rawGroup(next), g)
            copied += g.rows
          else decoded = reader.readGroup(next).rowIterator.buffered
          next += 1

    /** Pushed rows written (not dropped as a repeat). */
    var added = 0L

    def push(r: Row): Unit =
      upTo(key(r), all = false)
      val before = distinct.written
      distinct(r)
      added += distinct.written - before

    def finish(): Unit = upTo(Long.MaxValue, all = true)

    /** Rows written: copied and passed on. */
    def written: Long = copied + distinct.written

  /** `a` and `b` (each sorted) merged into `out`, without repeats. */
  def merge(a: Iterator[Row], b: Iterator[Row], out: Row => Unit): Long =
    val m = Merge(a, out)
    b.foreach(m.push)
    m.finish()
    m.written

  /** Position in a (time, rowid) ordered scan: the last row read. */
  final case class Cursor(time: Long, rowid: Long)

  /** Keyset paging over a table read in (time, rowid) order: `fetch(c)`
    * returns the next rows after `c` (at most a chunk), `pos` the position of
    * a row. Ends at the first empty chunk. Only one chunk is held at a time.
    */
  def pages[A](from: Long, fetch: Cursor => IndexedSeq[A], pos: A => Cursor): Iterator[IndexedSeq[A]] =
    var at = Cursor(from, Long.MinValue)
    var done = false
    Iterator.continually {
      val chunk = if done then IndexedSeq.empty else fetch(at)
      if chunk.isEmpty then done = true else at = pos(chunk.last)
      chunk
    }.takeWhile(_.nonEmpty)
