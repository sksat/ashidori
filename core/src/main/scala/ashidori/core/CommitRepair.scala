package ashidori.core

import scala.collection.mutable
import ashidori.core.ArchiveMerge.Row
import ashidori.core.CommitIntent.{Check, Delete, OnDisk, Prev, Recovery, Target}

/** Resolves an archive commit a crash or failure left unfinished, table by
  * table: one some of whose files were renamed into place while the tmp
  * file of another is gone or damaged ([[CommitIntent.Recovery.Stuck]]),
  * and also one the normal rules would finish or drop.
  *
  * Rows are deleted from SQLite only after every file of a commit is
  * published, so such a commit still holds everything: a published file has
  * the previous rows plus the chunk (which SQLite also still has), and a
  * table not published has its previous file and its rows in SQLite.
  *
  * Three rules (docs/recording.md):
  *
  *  A. A repair never deletes a data file. Every tmp file is moved to
  *     quarantine (whether or not it is needed: that its content equals
  *     what the other copies hold cannot be proven), so is a file that does
  *     not verify. The only deletion is of the chunk's rows in SQLite of a
  *     table whose published file holds every one of them ([[verify]]).
  *     Recovered tmp files are never published: a table not published is
  *     archived again from its previous file and SQLite by the next run.
  *  B. Before its first change a repair marks the record ([[Ops.begin]]):
  *     recovery then only continues the repair ([[resume]]), and 0.21.0
  *     stops at a record of raw lines (see [[CommitIntent]]). The first
  *     changes move the tmp files aside, so that 0.21.0, finding them gone,
  *     cannot publish one.
  *  C. (The app's readers never fail on a commit being repaired.)
  *
  * The record is removed only after every table is resolved. Each step is
  * idempotent, so a run cut short at any point is completed by the next
  * one (a row already deleted from SQLite is no longer checked for). Run
  * automatically it moves the tmp files aside and resolves what needs no
  * file to be quarantined, unless the record is a stuck one of 0.21.0;
  * otherwise it waits for the user (「整理を修復」).
  */
object CommitRepair:
  /** Where one table of the commit stands. */
  enum State:
    /** Its new version is in place (the recorded rows and size). */
    case Published
    /** Its previous version is in place (or it had none): nothing of the
      * commit reached it.
      */
    case NotPublished
    /** Moved to quarantine by an earlier repair. */
    case Quarantined
    /** Neither the previous nor the new version (damaged). */
    case Unknown

  /** `dest`: (rows in the footer, or -1 if unreadable, and size) of the
    * file, if it exists; `quarantined`: a repair moved it already.
    */
  def classify(t: Target, dest: Option[(Long, Long)], quarantined: Boolean): State =
    if dest.contains((t.rows, t.size)) then State.Published
    else if dest.isEmpty && quarantined then State.Quarantined
    else t.check.map(_.prev) match
      case Some(Prev.File(r, s)) => if dest.contains((r, s)) then State.NotPublished else State.Unknown
      case Some(Prev.Absent)     => if dest.isEmpty then State.NotPublished else State.Unknown
      // Not recorded (0.21.0): a readable file other than the new version
      // is taken for the previous one (a rename is atomic).
      case _ => if dest.forall(_._1 >= 0) then State.NotPublished else State.Unknown

  /** Whether the previous version of `t` was recorded (not a record of 0.21.0). */
  def previousKnown(t: Target): Boolean = t.check.exists(_.prev != Prev.Unknown)

  /** What the repair does with one table (besides moving its tmp file aside). */
  enum Action:
    /** Verified, every chunk row found in it: delete the chunk's rows of the
      * table from SQLite.
      */
    case DeleteRows
    /** Not published, its previous file reads whole: keep the rows in
      * SQLite (archived again by the next run).
      */
    case Keep
    /** Move the file to quarantine, keep the rows. `onlyCopy`: it holds
      * earlier rows that may exist nowhere else.
      */
    case Quarantine(why: String, onlyCopy: Boolean)

  final case class Step(target: Target, state: State, action: Action)

  /** Whether a quarantined file of `t` holds rows archived before this commit. */
  def holdsPrevious(t: Target): Boolean =
    t.check.forall(c => c.carried > 0 || c.prev != Prev.Absent)

  /** The action for each table, and for its tmp file (if any) whether it
    * may hold earlier rows that its previous file does not (the previous
    * file is not shown intact): reads files, changes nothing.
    */
  def plan(i: CommitIntent, ops: Ops): Seq[Step] =
    i.files.map { t =>
      val dest = ops.dest(t.path)
      val st = classify(t, dest, ops.quarantined(t.path))
      val action = st match
        case State.Published =>
          ops.verify(t) match
            case Right(()) => Action.DeleteRows
            case Left(why) => Action.Quarantine(why, holdsPrevious(t))
        case State.NotPublished =>
          if dest.isEmpty then Action.Keep
          else
            ops.verifyPrevious(t) match
              case Right(()) => Action.Keep
              case Left(why) => Action.Quarantine(s"以前の版が読めない（$why）", onlyCopy = true)
        case State.Quarantined  => Action.Quarantine("隔離済み", holdsPrevious(t))
        case State.Unknown      => Action.Quarantine("以前の版でも新しい版でもない", holdsPrevious(t))
      Step(t, st, action)
    }

  /** Whether the tmp file of a step may hold the only good copy of earlier
    * rows: its table had a previous file that is not shown intact.
    */
  def tmpMayBeOnlyCopy(s: Step): Boolean = s.state match
    case State.NotPublished => s.action != Action.Keep || !previousKnown(s.target)
    case _                  => holdsPrevious(s.target)

  /** The side effects, each durable when it returns and idempotent. */
  trait Ops:
    def dest(path: String): Option[(Long, Long)]
    def tmpExists(path: String): Boolean
    def quarantined(path: String): Boolean
    /** [[verify]] of the published file, with every chunk row of its table
      * still in SQLite ([[RowBag]]).
      */
    def verify(t: Target): Either[String, Unit]
    /** [[verifyWhole]] of the file in place (the previous version), if any. */
    def verifyPrevious(t: Target): Either[String, Unit]
    /** Stores the record with `repairing` set (see [[CommitIntent]]): from
      * then on recovery only continues the repair ([[resume]]), and 0.21.0
      * stops at a record of raw lines.
      */
    def begin(): Unit
    /** Stores in the record that these published files passed the full
      * check (readers then leave their chunk's rows in SQLite out).
      */
    def markVerified(paths: Set[String]): Unit
    /** Records the attention mark of the tmp file of `t` (`alert`: shown to
      * the user, it may hold the only copy of some rows), then moves it to
      * quarantine ([[SafeMove]]): if it exists, or if it was moved and the
      * move may not be durable yet.
      */
    def setAsideTmp(t: Target, why: String, alert: Boolean): Unit
    /** Records the attention mark of `path`, then moves the file to
      * quarantine (its tmp file was moved already).
      */
    def quarantine(t: Target, why: String, onlyCopy: Boolean): Unit
    /** Syncs these files and their directories (a rename seen may not be durable yet). */
    def sync(paths: Seq[String]): Unit
    /** Deletes the chunk's rows of these tables (by name) from SQLite. */
    def deleteRows(tables: Set[String]): Long
    def clearIntent(): Unit
    def log(line: String): Unit

  enum Result:
    /** Every table resolved and the record removed. */
    case Resolved(steps: Seq[Step])
    /** Automatic run: a file would be quarantined, or the record is a
      * stuck one of 0.21.0. The tmp files are set aside; nothing else was
      * changed.
      */
    case NeedsUser(steps: Seq[Step])

  def tableOf(path: String): String = path.takeWhile(_ != '/')

  /** Names a commit: the same before and after its record is completed
    * with checks or marked as being repaired.
    */
  def id(i: CommitIntent): String =
    val crc = java.util.zip.CRC32()
    val bare = i.copy(files = i.files.map(_.copy(check = None)), repairing = false, repairTag = "", verified = Set.empty)
    crc.update(CommitIntent.encode(bare).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    f"${crc.getValue}%08x"

  /** What recovery does with a commit record: once a repair began, only the
    * repair goes on. It may have moved files the normal rules would
    * misread (a quarantined file looks like a commit that published
    * nothing).
    */
  def resume(i: CommitIntent, disk: Map[String, OnDisk]): Recovery =
    if i.repairing then Recovery.Stuck else CommitIntent.recovery(i, disk)

  /** Whether the record is of 0.21.0 (completed by guesswork: the previous
    * files are unknown): resolved only by hand when stuck.
    */
  def legacy(i: CommitIntent): Boolean = i.files.exists(t => !previousKnown(t))

  /** Resolves the commit `i` (see above). `recovering`: it is one the normal
    * rules would finish or drop, which 0.21.0 did on its own: then a record
    * of 0.21.0 is resolved automatically too (rows are deleted only once
    * found in full).
    */
  def run(i: CommitIntent, ops: Ops, manual: Boolean, recovering: Boolean = false): Result =
    val steps = plan(i, ops)
    // B: the mark first; then A: every tmp file out of the way (also each
    // one a cut-short run moved: its move made durable).
    ops.begin()
    for s <- steps do
      val there = ops.tmpExists(s.target.path)
      val alert = tmpMayBeOnlyCopy(s)
      ops.setAsideTmp(s.target, if alert then "整理途中の一時ファイル（以前の記録を含む可能性あり）" else "整理途中の一時ファイル", alert)
      if there then ops.log(s"${s.target.path}: 整理途中の一時ファイルを隔離（削除しない）")
    // Readers leave chunk rows out only for files that passed the full check.
    val passed = steps.collect { case s if s.action == Action.DeleteRows => s.target.path }.toSet
    if passed.nonEmpty then ops.markVerified(passed)
    // Automatically: never move a data file (one moved by an earlier repair
    // is only marked again), and not a stuck record of 0.21.0.
    if !manual && ((legacy(i) && !recovering) ||
        steps.exists(s => s.action.isInstanceOf[Action.Quarantine] && s.state != State.Quarantined)) then
      Result.NeedsUser(steps)
    else
      for s <- steps do
        s.action match
          case Action.Quarantine(why, only) =>
            ops.quarantine(s.target, why, only)
            ops.log(s"${s.target.path}: 隔離（$why）。行は端末内に保持" + (if only then "。以前の記録はこのファイルにのみ残る" else ""))
          case Action.Keep =>
            ops.log(s"${s.target.path}: 置き換え前。行は端末内に保持し、次回の整理で作り直す")
          case Action.DeleteRows => ()
      val verified = steps.collect { case s if s.action == Action.DeleteRows => s.target }
      if verified.nonEmpty then
        // The rows go only once the files holding them are durable.
        ops.sync(verified.map(_.path))
        val n = ops.deleteRows(verified.map(t => tableOf(t.path)).toSet)
        ops.log(s"${verified.map(_.path).mkString("、")}: 検証済み（${verified.map(_.rows).sum} 行、端末内の行をすべて照合）。" +
          s"重複していた $n 行を端末内から削除")
      ops.clearIntent()
      ops.log("未完了の整理の記録を消去")
      Result.Resolved(steps)

  /** What a reader of the archive leaves out while a commit of its tables
    * is unfinished, so that it reads each row once (rule C): for a table
    * whose new file is in place, the chunk's rows still in SQLite
    * (`hot`: table → (from, to, max rowid)) and the day files a month file
    * replaces (`files`). `raw`: a commit of raw lines, whose rows in SQLite
    * cannot be told apart by a query: a reader of the raw tables waits
    * (「整理待ち」) instead.
    */
  final case class ReadExclusion(hot: Map[String, (Long, Long, Long)], files: Set[String], raw: Boolean,
                                 dedupe: Set[String] = Set.empty)

  /** Only a new file that is in place and passed the repair's full check
    * (recorded in the record: `verified`) has the chunk's rows left out of
    * SQLite: a file not (yet) checked might lack some. Then both copies are
    * read and equal rows shown once (`dedupe`), so nothing that has a good
    * copy is hidden.
    */
  def exclusion(i: CommitIntent, published: String => Boolean): ReadExclusion =
    val tables = i.files.map(t => tableOf(t.path)).toSet
    val done = i.files.filter(t => published(t.path) && i.verified.contains(t.path)).map(t => tableOf(t.path)).toSet
    i.delete match
      case Delete.Hot(t, from, to, max) =>
        ReadExclusion(if done(t) then Map(t -> (from, to, max)) else Map.empty, Set.empty, raw = false, tables -- done)
      case Delete.Files(ps) =>
        ReadExclusion(Map.empty, ps.filter(p => done(tableOf(p))).toSet, raw = false, tables -- done)
      case _ => ReadExclusion(Map.empty, Set.empty, raw = true)

  /** `xs` without repeats of an equal row (by [[ArchiveMerge.hash]], then
    * value), in their order: what a reader shows while two copies of some
    * rows are read.
    */
  def distinctRows[A](xs: Seq[A])(row: A => Row): Vector[A] =
    val seen = mutable.HashMap.empty[Long, List[Row]]
    xs.iterator.filter { x =>
      val r = row(x)
      val h = ArchiveMerge.hash(r)
      val same = seen.getOrElse(h, Nil)
      if same.exists(ArchiveMerge.same(_, r)) then false
      else
        seen(h) = r :: same
        true
    }.toVector

  /** Checks a published file against its target: the footer's rows (and,
    * when recorded, previous + chunk rows), every row group decodes to as
    * many rows in time order, the time range of the file and of the chunk,
    * the sampled rows of the chunk are present, and every row of `chunk`
    * (the chunk's rows still in SQLite, by [[ArchiveMerge.hash]] of all
    * columns) is in the file. `rows`: the file's rows, decoded group by
    * group (an error while decoding fails the check).
    *
    * The rows carried over from the previous file are checked by count
    * only: once the new version is in place the previous one is gone. That
    * is the best check left for a file already published.
    *
    * `expected`: when given, the file must hold exactly these rows as a
    * multiset ([[RowBag]]): each one at least once (equal rows are kept
    * once by archiving) and no more often than given, and no other row.
    */
  def verify(t: Target, footerRows: Long, rows: => Iterator[Row], chunk: Option[RowBag] = None,
             expected: Option[RowBag] = None): Either[String, Unit] =
    try
      if footerRows != t.rows then return Left(s"フッタの行数 $footerRows（記録は ${t.rows}）")
      t.check.foreach { c =>
        if c.carried + c.added != footerRows then return Left(s"行数 $footerRows が以前の ${c.carried} と区切りの ${c.added} の和でない")
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
        expected.foreach(_.mark(h))
        wanted.get(k).foreach { hs =>
          hs -= h
          if hs.isEmpty then wanted.remove(k)
        }
      if n != footerRows then return Left(s"読める行 $n（フッタは $footerRows）")
      val range = Option.when(n > 0)((lo, hi))
      for c <- t.check do
        if c.range.isDefined && c.range != range then return Left(s"時刻の範囲 ${range.getOrElse("-")}（記録は ${c.range.get}）")
        for (a, b) <- c.addedRange do
          if range.forall((l, h) => l > a || h < b) then return Left("区切りの行の時刻の範囲が含まれない")
      if wanted.nonEmpty then return Left(s"区切りの行のうち ${wanted.values.map(_.size).sum} 行が見つからない")
      for c <- chunk if c.missing > 0 do return Left(s"端末内の ${c.missing} 行がファイルにない")
      for e <- expected do
        if e.foreign > 0 then return Left(s"以前の版にも端末内にもない行が ${e.foreign} 行ある")
        if e.missing > 0 then return Left(s"以前の版か端末内の ${e.missing} 行がない")
        if e.overfull > 0 then return Left(s"${e.overfull} 種類の行が多すぎる")
      Right(())
    catch
      case e: RowBag.TooLarge => Left(TooLargeMark + Option(e.getMessage).getOrElse(""))
      case e: Exception => Left(s"読めない: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  /** Prefix of a check that was not made: too many rows to compare. */
  val TooLargeMark = "照合する行が多すぎる: "

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

/** A multiset of rows by their [[ArchiveMerge.hash]] (of all columns),
  * for checking an archive file against the rows it must hold: the hashes
  * sorted with how often each was added, and how often each is seen in the
  * file. At most `cap` rows ([[RowBag.Cap]]): beyond that [[add]] throws
  * [[RowBag.TooLarge]], and the caller takes the safe path (no automatic
  * action). Eight bytes per row added, plus 8 per distinct row once sealed.
  */
final class RowBag(cap: Int = RowBag.Cap):
  private var hashes = new Array[Long](1024)
  private var n = 0
  // Once sealed: distinct hashes, how often each was added, how often seen.
  private var distinct = 0
  private var expected: Array[Int] = null
  private var seen: Array[Int] = null
  /** Rows marked that are not in the bag. */
  var foreign = 0L

  def rows: Long = n

  def add(r: Row): Unit = addHash(ArchiveMerge.hash(r))

  def addHash(h: Long): Unit =
    require(expected == null, "sealed")
    if n >= cap then throw RowBag.TooLarge(cap)
    if n == hashes.length then hashes = java.util.Arrays.copyOf(hashes, math.min(cap, n * 2).max(n + 1))
    hashes(n) = h
    n += 1

  private def seal(): Unit =
    if expected == null then
      java.util.Arrays.sort(hashes, 0, n)
      val counts = new Array[Int](n.max(1))
      var u = 0
      for i <- 0 until n do
        if u > 0 && hashes(u - 1) == hashes(i) then counts(u - 1) += 1
        else
          hashes(u) = hashes(i)
          counts(u) = 1
          u += 1
      distinct = u
      expected = counts
      seen = new Array[Int](u.max(1))

  /** A row of the file with hash `h`: whether the bag has it (counted). */
  def mark(h: Long): Boolean =
    seal()
    val i = java.util.Arrays.binarySearch(hashes, 0, distinct, h)
    if i >= 0 then seen(i) += 1 else foreign += 1
    i >= 0

  /** Distinct rows of the bag not seen in the file. */
  def missing: Int =
    seal()
    (0 until distinct).count(seen(_) == 0)

  /** Distinct rows seen more often than the bag has them. */
  def overfull: Int =
    seal()
    (0 until distinct).count(i => seen(i) > expected(i))

object RowBag:
  /** A portion is at most [[ArchiveStore]]'s 2 million lines. */
  val Cap = 2_000_000
  final class TooLarge(cap: Int) extends RuntimeException(s"more than $cap rows to compare")

/** A move that never replaces a file (rule A of [[CommitRepair]]): `from`
  * is renamed to `to`, or to `to.1`, `to.2`, ... if a file is there (none
  * is ever replaced), then the moved file, its directory and the source's
  * directory are synced. Repeated after it was cut short (the rename done,
  * not yet synced), it syncs the moved file and both directories again.
  * Hard links are not used: an app may not make them on Android.
  * `crash` marks the point after the rename (for tests). The file moved
  * to, if there was anything.
  */
object SafeMove:
  import java.nio.file.{Files, LinkOption, Path}

  private def exists(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

  /** `to`, then `to.1`, `to.2`, ... */
  private def names(to: Path): Iterator[Path] =
    Iterator(to) ++ Iterator.from(1).map(n => to.resolveSibling(s"${to.getFileName}.$n"))

  def move(from: Path, to: Path, ensureDir: Path => Unit, syncFile: Path => Unit, syncDir: Path => Unit,
           crash: String => Unit = _ => ()): Option[Path] =
    if exists(from) then
      ensureDir(to.getParent)
      val target = names(to).find(p => !exists(p)).get
      // Without REPLACE_EXISTING: refused if a file appeared there meanwhile.
      Files.move(from, target)
      crash("moved")
      syncFile(target)
      syncDir(target.getParent)
      syncDir(from.getParent)
      Some(target)
    else
      // Moved before (perhaps not synced): sync what is there.
      val moved = names(to).takeWhile(exists).toVector
      moved.foreach(syncFile)
      if moved.nonEmpty then
        syncDir(to.getParent)
        syncDir(from.getParent)
      moved.lastOption
