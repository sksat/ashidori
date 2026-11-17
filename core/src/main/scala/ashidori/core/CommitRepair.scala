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
  *  - published, and its file verifies against the record ([[verify]]):
  *    the file is synced and the chunk's rows of that table are deleted
  *    from SQLite;
  *  - not published (its previous file is in place): the stray tmp file is
  *    deleted and its rows stay in SQLite, for the next run to archive. If
  *    the previous version was not recorded (0.21.0), the tmp file is
  *    moved to quarantine instead;
  *  - published but the file does not verify, or a file that is neither the
  *    previous nor the new version: the file (and a tmp file, if any) is
  *    moved to quarantine, never deleted; the rows stay in SQLite and the
  *    day is marked for attention. If it held the previous rows, the
  *    quarantined file is now their only copy.
  *
  * The record is removed only after every table is resolved. Each step is
  * idempotent and depends only on the files and the record (not on what
  * SQLite still holds), so a run cut short at any point is completed by the
  * next one. Before the first change the repair is marked as begun
  * ([[Ops.begin]]): recovery then goes on with it ([[resume]]). Run automatically, it resolves only when no file would be
  * quarantined; quarantining waits for the user (「まとめを修復」).
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

  /** Whether the previous version of `t` was identified (recorded and found). */
  def previousKnown(t: Target): Boolean = t.check.exists(_.prev != Prev.Unknown)

  /** What the repair does with one table. */
  enum Action:
    /** Verified: delete the chunk's rows of the table from SQLite. */
    case DeleteRows
    /** Not published: delete the stray tmp file, keep the rows. `setAside`:
      * the previous version was not identified (a record of 0.21.0), so the
      * tmp file is moved to quarantine instead of deleted.
      */
    case DropTmp(setAside: Boolean)
    /** Move the file (and any tmp file) to quarantine, keep the rows.
      * `onlyCopy`: the file holds the previous rows, which exist nowhere
      * else once it is moved.
      */
    case Quarantine(why: String, onlyCopy: Boolean)

  final case class Step(target: Target, state: State, action: Action)

  /** Whether a quarantined file of `t` holds rows archived before this commit. */
  def holdsPrevious(t: Target): Boolean =
    t.check.forall(c => c.carried > 0 || c.prev != Prev.Absent)

  /** The action for each table; `verify` reads a published file. */
  def plan(i: CommitIntent, dest: String => Option[(Long, Long)], tmp: String => Boolean, quarantined: String => Boolean,
           verify: Target => Either[String, Unit]): Seq[Step] =
    i.files.map { t =>
      val st = classify(t, dest(t.path), tmp(t.path), quarantined(t.path))
      val action = st match
        case State.Published =>
          verify(t) match
            case Right(()) => Action.DeleteRows
            case Left(why) => Action.Quarantine(why, holdsPrevious(t))
        case State.NotPublished => Action.DropTmp(setAside = !previousKnown(t) && tmp(t.path))
        case State.Quarantined  => Action.Quarantine("隔離済み", holdsPrevious(t))
        case State.Unknown      => Action.Quarantine("以前の版でも新しい版でもない", holdsPrevious(t))
      Step(t, st, action)
    }

  /** The side effects, each durable when it returns and idempotent. */
  trait Ops:
    def dest(path: String): Option[(Long, Long)]
    def tmpExists(path: String): Boolean
    def quarantined(path: String): Boolean
    def verify(t: Target): Either[String, Unit]
    /** Records the attention mark of `path`, then moves the file and its tmp
      * file (whichever exist) to quarantine.
      */
    def quarantine(t: Target, why: String, onlyCopy: Boolean): Unit
    /** Records the attention mark of `path`, then moves its tmp file (if
      * any) to quarantine; the file itself stays.
      */
    def setAsideTmp(t: Target, why: String): Unit
    /** Deletes `<path>.tmp` if it exists. */
    def dropTmp(path: String): Unit
    /** Records durably that a repair of this commit began: from then on
      * recovery continues the repair (never finishes or drops the commit
      * by the normal rules, see [[resume]]).
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
    /** Automatic run: a file would be quarantined. Nothing was changed. */
    case NeedsUser(steps: Seq[Step])

  def tableOf(path: String): String = path.takeWhile(_ != '/')

  /** Names a commit (its quarantine directory): the same before and after
    * its record is completed with checks.
    */
  def id(i: CommitIntent): String =
    val crc = java.util.zip.CRC32()
    crc.update(CommitIntent.encode(i.copy(files = i.files.map(_.copy(check = None)))).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    f"${crc.getValue}%08x"

  /** What recovery does with a commit record: once a repair began
    * (`repairing`), only the repair goes on; it may have moved or deleted
    * files the normal rules would misread (a quarantined file looks like a
    * commit that published nothing, and dropping that deletes tmp files).
    */
  def resume(i: CommitIntent, disk: Map[String, OnDisk], repairing: Boolean): Recovery =
    if repairing then Recovery.Stuck else CommitIntent.recovery(i, disk)

  def run(i: CommitIntent, ops: Ops, manual: Boolean): Result =
    val steps = plan(i, ops.dest, ops.tmpExists, ops.quarantined, ops.verify)
    // Automatically: never move a file (one moved by an earlier repair is
    // only marked again).
    if !manual && steps.exists(s => s.action.isInstanceOf[Action.Quarantine] && s.state != State.Quarantined) then
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
        ops.log(s"${verified.map(_.path).mkString("、")}: 検証済み（${verified.map(_.rows).sum} 行）。重複していた $n 行を端末内から削除")
      ops.clearIntent()
      ops.log("未完了のまとめの記録を消去")
      Result.Resolved(steps)

  /** Checks a published file against its target: the footer's rows (and,
    * when recorded, previous + chunk rows), every row group decodes to as
    * many rows in time order, the time range of the file and of the chunk,
    * and the sampled rows of the chunk are present. `rows`: the file's rows,
    * decoded group by group (an error while decoding fails the check).
    */
  def verify(t: Target, footerRows: Long, rows: => Iterator[Row]): Either[String, Unit] =
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
        wanted.get(k).foreach { hs =>
          hs -= ArchiveMerge.hash(r)
          if hs.isEmpty then wanted.remove(k)
        }
      if n != footerRows then return Left(s"読める行 $n（フッタは $footerRows）")
      val range = Option.when(n > 0)((lo, hi))
      for c <- t.check do
        if c.range.isDefined && c.range != range then return Left(s"時刻の範囲 ${range.getOrElse("-")}（記録は ${c.range.get}）")
        for (a, b) <- c.addedRange do
          if range.forall((l, h) => l > a || h < b) then return Left("まとめた行の時刻の範囲が含まれない")
      if wanted.nonEmpty then Left(s"まとめた行のうち ${wanted.values.map(_.size).sum} 行が見つからない")
      else Right(())
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
