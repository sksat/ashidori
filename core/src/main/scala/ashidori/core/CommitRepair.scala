package ashidori.core

import scala.collection.mutable
import ashidori.core.ArchiveMerge.Row
import ashidori.core.CommitIntent.{Check, OnDisk, Prev, Recovery, Target}

/** Resolves an archive commit that recovery cannot finish
  * ([[CommitIntent.Recovery.Stuck]]): some files were renamed into place,
  * and the tmp file of another is gone or damaged.
  *
  * Rows are deleted from SQLite only after every file of a commit is
  * published, so such a commit still holds everything: a published file has
  * the previous rows plus the chunk (which SQLite also still has), and a
  * table whose tmp file is broken has its previous file and its rows in
  * SQLite. Each table is resolved on its own:
  *
  *  - published, and its file verifies against the record and holds every
  *    chunk row still in SQLite (by a 64-bit hash of all columns, [[verify]]): the
  *    file is synced and the chunk's rows of that table are deleted from
  *    SQLite;
  *  - not published, its previous file in place and read whole with the
  *    recorded rows: the stray tmp file is deleted and its rows stay in
  *    SQLite, for the next run to archive (a table without a previous file
  *    likewise: its tmp holds only the chunk). If the previous version was
  *    not recorded (0.21.0), the tmp file is moved to quarantine instead;
  *  - anything else (a published file that does not verify, a previous
  *    file that does not read whole, a file that is neither version): the
  *    file and its tmp file are moved to quarantine, never deleted; the
  *    rows stay in SQLite and the day is marked for attention. If they held
  *    earlier rows, the quarantined files may be their only copy.
  *
  * The record is removed only after every table is resolved. Each step is
  * idempotent, so a run cut short at any point is completed by the next
  * one (a row already deleted from SQLite is no longer checked for). Before
  * the first change the record is marked as being repaired ([[Ops.begin]]):
  * recovery then goes on with the repair ([[resume]]). Run automatically,
  * it resolves only when nothing has to be quarantined and the record is
  * not of 0.21.0; otherwise it waits for the user (「まとめを修復」).
  */
object CommitRepair:
  /** Where one table of the commit stands. */
  enum State:
    /** Its new version is in place (the recorded rows and size). */
    case Published
    /** Its previous version is in place: nothing of the commit reached it. */
    case NotPublished
    /** Moved to quarantine by an earlier repair. */
    case Quarantined
    /** Neither the previous nor the new version (damaged). */
    case Unknown

  /** `dest`: (rows in the footer, or -1 if unreadable, and size) of the
    * file, if it exists; `tmp`: its tmp file exists; `quarantined`: a
    * repair moved it already.
    */
  def classify(t: Target, dest: Option[(Long, Long)], tmp: Boolean, quarantined: Boolean): State =
    if dest.contains((t.rows, t.size)) then State.Published
    else if dest.isEmpty && quarantined then State.Quarantined
    else t.check.map(_.prev) match
      case Some(Prev.File(r, s)) => if dest.contains((r, s)) then State.NotPublished else State.Unknown
      case Some(Prev.Absent)     => if dest.isEmpty then State.NotPublished else State.Unknown
      // Not recorded (0.21.0): a readable file other than the new version
      // is taken for the previous one (a rename is atomic). No file and a
      // tmp file: the tmp file may hold the only copy of earlier rows.
      case _ =>
        dest match
          case Some((rows, _)) => if rows >= 0 then State.NotPublished else State.Unknown
          case None            => if tmp then State.Unknown else State.NotPublished

  /** Whether the previous version of `t` was recorded (not a record of 0.21.0). */
  def previousKnown(t: Target): Boolean = t.check.exists(_.prev != Prev.Unknown)

  /** What the repair does with one table. */
  enum Action:
    /** Verified, every chunk row found in it: delete the chunk's rows of the
      * table from SQLite.
      */
    case DeleteRows
    /** Not published, its previous file read whole: delete the stray tmp
      * file, keep the rows. `setAside`: the previous version was not
      * recorded (a record of 0.21.0), so the tmp file is moved to
      * quarantine instead of deleted.
      */
    case DropTmp(setAside: Boolean)
    /** Move the file and any tmp file to quarantine, keep the rows.
      * `onlyCopy`: they hold earlier rows that may exist nowhere else.
      */
    case Quarantine(why: String, onlyCopy: Boolean)

  final case class Step(target: Target, state: State, action: Action)

  /** Whether a quarantined file of `t` holds rows archived before this commit. */
  def holdsPrevious(t: Target): Boolean =
    t.check.forall(c => c.carried > 0 || c.prev != Prev.Absent)

  /** The action for each table. A tmp file is deleted only when it cannot
    * hold the only good copy of anything: the table had no file before (the
    * tmp holds only the chunk, which SQLite has), or its previous file
    * reads whole with the recorded rows.
    */
  def plan(i: CommitIntent, ops: Ops): Seq[Step] =
    i.files.map { t =>
      val tmp = ops.tmpExists(t.path)
      val dest = ops.dest(t.path)
      val st = classify(t, dest, tmp, ops.quarantined(t.path))
      val action = st match
        case State.Published =>
          ops.verify(t) match
            case Right(()) => Action.DeleteRows
            case Left(why) => Action.Quarantine(why, holdsPrevious(t))
        case State.NotPublished =>
          t.check.map(_.prev) match
            case Some(Prev.Absent) => Action.DropTmp(setAside = false)
            case _ if dest.isEmpty && !tmp => Action.DropTmp(setAside = false)
            case prev =>
              ops.verifyPrevious(t) match
                case Right(()) => Action.DropTmp(setAside = tmp && !prev.exists(_.isInstanceOf[Prev.File]))
                case Left(why) => Action.Quarantine(s"以前の版が読めない（$why）", onlyCopy = true)
        case State.Quarantined  => Action.Quarantine("隔離済み", holdsPrevious(t))
        case State.Unknown      => Action.Quarantine("以前の版でも新しい版でもない", holdsPrevious(t))
      Step(t, st, action)
    }

  /** The side effects, each durable when it returns and idempotent. */
  trait Ops:
    def dest(path: String): Option[(Long, Long)]
    def tmpExists(path: String): Boolean
    def quarantined(path: String): Boolean
    /** [[verify]] of the published file, with every chunk row of its table
      * still in SQLite ([[ChunkIndex]]).
      */
    def verify(t: Target): Either[String, Unit]
    /** [[verifyWhole]] of the file in place (the previous version), if any. */
    def verifyPrevious(t: Target): Either[String, Unit]
    /** Records the attention mark of `path`, then moves its tmp file and
      * the file (whichever exist) to quarantine.
      */
    def quarantine(t: Target, why: String, onlyCopy: Boolean): Unit
    /** Records the attention mark of `path`, then moves its tmp file (if
      * any) to quarantine; the file itself stays.
      */
    def setAsideTmp(t: Target, why: String): Unit
    /** Deletes `<path>.tmp` if it exists. */
    def dropTmp(path: String): Unit
    /** Stores the record with `repairing` set (see [[CommitIntent]]): from
      * then on recovery only continues the repair ([[resume]]), and 0.21.0
      * stops at it.
      */
    def begin(): Unit
    /** Syncs these files and their directories (a rename seen may not be durable yet). */
    def sync(paths: Seq[String]): Unit
    /** Deletes the chunk's rows of these tables (by name) from SQLite. */
    def deleteRows(tables: Set[String]): Long
    def clearIntent(): Unit
    def log(line: String): Unit

  enum Result:
    /** Every table resolved and the record removed. */
    case Resolved(steps: Seq[Step])
    /** Automatic run: a file would be quarantined, or the record is of
      * 0.21.0. Nothing was changed.
      */
    case NeedsUser(steps: Seq[Step])

  def tableOf(path: String): String = path.takeWhile(_ != '/')

  /** Names a commit (its quarantine directory): the same before and after
    * its record is completed with checks or marked as being repaired.
    */
  def id(i: CommitIntent): String =
    val crc = java.util.zip.CRC32()
    val bare = i.copy(files = i.files.map(_.copy(check = None)), repairing = false)
    crc.update(CommitIntent.encode(bare).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    f"${crc.getValue}%08x"

  /** What recovery does with a commit record: once a repair began, only the
    * repair goes on. It may have moved or deleted files the normal rules
    * would misread (a quarantined file looks like a commit that published
    * nothing, and dropping that deletes tmp files).
    */
  def resume(i: CommitIntent, disk: Map[String, OnDisk]): Recovery =
    if i.repairing then Recovery.Stuck else CommitIntent.recovery(i, disk)

  /** Whether the record is of 0.21.0 (completed by guesswork: the previous
    * files are unknown): resolved only by hand.
    */
  def legacy(i: CommitIntent): Boolean = i.files.exists(t => !previousKnown(t))

  /** Resolves the commit `i`. `recovering`: it is one the normal rules
    * would finish (its tmp files renamed already) or drop, which 0.21.0
    * did on its own: then a record of 0.21.0 is resolved automatically too
    * (rows are deleted only once found in full, tmp files whose previous
    * version is unknown are set aside). A stuck record of 0.21.0 is
    * resolved only by hand.
    */
  def run(i: CommitIntent, ops: Ops, manual: Boolean, recovering: Boolean = false): Result =
    val steps = plan(i, ops)
    // Automatically: never move a file (one moved by an earlier repair is
    // only marked again), and not a stuck record of 0.21.0.
    if !manual && ((legacy(i) && !recovering) ||
        steps.exists(s => s.action.isInstanceOf[Action.Quarantine] && s.state != State.Quarantined)) then
      Result.NeedsUser(steps)
    else
      ops.begin()
      for s <- steps do
        s.action match
          case Action.Quarantine(why, only) =>
            ops.quarantine(s.target, why, only)
            ops.log(s"${s.target.path}: 隔離（$why）。行は端末内に保持" + (if only then "。以前の記録はこのファイルにのみ残る" else ""))
          case Action.DropTmp(false) =>
            ops.dropTmp(s.target.path)
            ops.log(s"${s.target.path}: 置き換え前のため一時ファイルを削除。行は端末内に保持し、次回まとめ直す")
          case Action.DropTmp(true) =>
            ops.setAsideTmp(s.target, "置き換え前の一時ファイル（以前の版を確認できない）")
            ops.log(s"${s.target.path}: 置き換え前。一時ファイルを隔離し、行は端末内に保持し、次回まとめ直す")
          case Action.DeleteRows => ()
      val verified = steps.collect { case s if s.action == Action.DeleteRows => s.target }
      if verified.nonEmpty then
        // The rows go only once the files holding them are durable.
        ops.sync(verified.map(_.path))
        val n = ops.deleteRows(verified.map(t => tableOf(t.path)).toSet)
        ops.log(s"${verified.map(_.path).mkString("、")}: 検証済み（${verified.map(_.rows).sum} 行、端末内の行をすべて照合）。" +
          s"重複していた $n 行を端末内から削除")
      ops.clearIntent()
      ops.log("未完了のまとめの記録を消去")
      Result.Resolved(steps)

  /** Checks a published file against its target: the footer's rows (and,
    * when recorded, previous + chunk rows), every row group decodes to as
    * many rows in time order, the time range of the file and of the chunk,
    * the sampled rows of the chunk are present, and every row of `chunk`
    * (the chunk's rows still in SQLite, by [[ArchiveMerge.hash]] of all
    * columns) is in the file. `rows`: the file's rows, decoded group by
    * group (an error while decoding fails the check).
    *
    * The rows carried over from the previous file are checked by count
    * only: once the new version is in place the previous one is gone.
    */
  def verify(t: Target, footerRows: Long, rows: => Iterator[Row], chunk: Option[ChunkIndex] = None): Either[String, Unit] =
    try
      if footerRows != t.rows then return Left(s"フッタの行数 $footerRows（記録は ${t.rows}）")
      t.check.foreach { c =>
        if c.carried + c.added != footerRows then return Left(s"行数 $footerRows が以前の ${c.carried} とまとめた ${c.added} の和でない")
      }
      val wanted = mutable.HashMap.empty[Long, mutable.Set[Long]]
      for c <- t.check; (k, h) <- c.samples do wanted.getOrElseUpdate(k, mutable.Set.empty) += h
      var n = 0L
      var lo = Long.MaxValue
      var hi = Long.MinValue
      var last = Long.MinValue
      for r <- rows do
        val k = ArchiveMerge.key(r)
        if k < last then return Left(s"時刻順でない行（${n + 1} 行目）")
        last = k
        lo = math.min(lo, k)
        hi = math.max(hi, k)
        n += 1
        val h = ArchiveMerge.hash(r)
        chunk.foreach(_.mark(h))
        wanted.get(k).foreach { hs =>
          hs -= h
          if hs.isEmpty then wanted.remove(k)
        }
      if n != footerRows then return Left(s"読める行 $n（フッタは $footerRows）")
      val range = Option.when(n > 0)((lo, hi))
      for c <- t.check do
        if c.range.isDefined && c.range != range then return Left(s"時刻の範囲 ${range.getOrElse("-")}（記録は ${c.range.get}）")
        for (a, b) <- c.addedRange do
          if range.forall((l, h) => l > a || h < b) then return Left("まとめた行の時刻の範囲が含まれない")
      if wanted.nonEmpty then return Left(s"まとめた行のうち ${wanted.values.map(_.size).sum} 行が見つからない")
      chunk.map(_.missing).filter(_ > 0) match
        case Some(m) => Left(s"端末内の $m 行がファイルにない")
        case None    => Right(())
    catch case e: Exception => Left(s"読めない: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  /** Checks a file in place (the previous version of a table not
    * published): every row group decodes, in time order, to as many rows as
    * its footer says and as `expected` (recorded), if known.
    */
  def verifyWhole(footerRows: Long, rows: => Iterator[Row], expected: Option[Long]): Either[String, Unit] =
    try
      if expected.exists(_ != footerRows) then return Left(s"フッタの行数 $footerRows（記録は ${expected.get}）")
      var n = 0L
      var last = Long.MinValue
      for r <- rows do
        val k = ArchiveMerge.key(r)
        if k < last then return Left(s"時刻順でない行（${n + 1} 行目）")
        last = k
        n += 1
      if n != footerRows then Left(s"読める行 $n（フッタは $footerRows）") else Right(())
    catch case e: Exception => Left(s"読めない: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  /** Rowids of raw lines of a commit's chunk that belong to `tables`:
    * lines that convert to a row of one of them ([[Archive.parse]]) and
    * that the portion wrote (not `kept`).
    */
  def rawRowids(lines: Iterable[(Long, Long, String)], kept: Long => Boolean, tables: Set[String]): Seq[Long] =
    lines.iterator.collect {
      case (rowid, time, line) if !kept(rowid) && Archive.parse(line, time).toOption.exists((t, _) => tables(t.name)) => rowid
    }.toSeq

/** What a [[CommitIntent.Check]] records about the rows of a chunk, gathered
  * as they are written: their count, time range and a few of them (the
  * first, the last and up to 2 × `max` evenly spread), in bounded memory.
  */
final class ChunkSampler(max: Int = 8):
  private var stride = 1L
  private var seen = 0L
  private val kept = mutable.ArrayBuffer.empty[(Long, Long)]
  private var lastRow: Option[(Long, Long)] = None
  private var lo = Long.MaxValue
  private var hi = Long.MinValue

  def add(r: Row): Unit =
    val k = ArchiveMerge.key(r)
    lo = math.min(lo, k)
    hi = math.max(hi, k)
    if seen % stride == 0 then
      kept += ((k, ArchiveMerge.hash(r)))
      if kept.size >= 2 * max then
        // Keep every other one and sample half as often.
        val half = kept.indices.filter(_ % 2 == 0).map(kept)
        kept.clear()
        kept ++= half
        stride *= 2
      lastRow = None
    else lastRow = Some((k, ArchiveMerge.hash(r)))
    seen += 1

  def count: Long = seen
  def range: Option[(Long, Long)] = Option.when(seen > 0)((lo, hi))
  def samples: Seq[(Long, Long)] = (kept ++ lastRow).toSeq

  def check(prev: Prev, carried: Long, added: Long, range: Option[(Long, Long)]): Check =
    Check(prev, carried, added, range, this.range, samples)

/** The rows of a commit's chunk still in SQLite, for checking that a
  * published file holds every one of them: their [[ArchiveMerge.hash]]es
  * (of all columns), sorted, and which were found. Eight bytes per row (a
  * portion is at most [[ArchiveStore]]'s 2 million lines). A row repeated
  * in the chunk is one entry: archiving keeps one of equal rows.
  */
final class ChunkIndex:
  private var hashes = new Array[Long](1024)
  private var n = 0
  private var sealedSize = -1
  private var found: java.util.BitSet = null
  /** Rows added (repeats included). */
  var rows = 0L

  def add(r: Row): Unit = addHash(ArchiveMerge.hash(r))

  def addHash(h: Long): Unit =
    require(sealedSize < 0, "sealed")
    if n == hashes.length then hashes = java.util.Arrays.copyOf(hashes, n * 2)
    hashes(n) = h
    n += 1
    rows += 1

  private def seal(): Unit =
    if sealedSize < 0 then
      java.util.Arrays.sort(hashes, 0, n)
      var u = 0
      for i <- 0 until n do
        if u == 0 || hashes(u - 1) != hashes(i) then
          hashes(u) = hashes(i)
          u += 1
      sealedSize = u
      found = java.util.BitSet(u)

  /** A row of the file with hash `h`: whether the chunk has it. */
  def mark(h: Long): Boolean =
    seal()
    val i = java.util.Arrays.binarySearch(hashes, 0, sealedSize, h)
    if i >= 0 then found.set(i)
    i >= 0

  /** Distinct rows of the chunk not found (so far). */
  def missing: Int =
    seal()
    sealedSize - found.cardinality()
