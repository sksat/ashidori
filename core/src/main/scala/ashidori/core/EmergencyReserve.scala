package ashidori.core

/** The logger's emergency reserve R and the emergency state (design 6.8,
  * plan C stage 2).
  *
  * R is a file of [[Emergency.R]] bytes really allocated (never sparse) in a
  * non-cache directory beside the records. It is "ready" only once it is
  * allocated, synced and published (renamed into place); large work other
  * than logging (downloads, backups, maintenance) starts and goes on only
  * while it is ready and no emergency is marked. Only the main process
  * creates and releases it; the maintenance process reads the files (R at
  * full length, no emergency mark) at its start and at each boundary.
  *
  * The emergency state is entered when the free space F is below
  * [[Emergency.E]] before a logging or overflow write, when a logging
  * transaction fails with SQLITE_FULL, when an overflow write fails for lack
  * of space, or on a report from other work ([[enter]]): its own durable
  * mark is written first, large work is asked to stop (`stopLarge`), R is
  * truncated to 0 and synced (removed if that fails), and the logger writes
  * its batches to overflow files instead of SQLite ([[route]]).
  *
  * On disk the state is the mark, independent of R: a restart restores the
  * emergency state when the mark exists, whatever became of R (a release
  * that failed leaves R whole: it is released again); a released R (shorter
  * than R) without the mark is restored as an emergency too (the mark could
  * not be written). Recovery ([[tick]], [[allocate]]): F at or above
  * H + 2R for [[Emergency.SustainMs]] without a break, then (if logging)
  * one new batch saved to SQLite, then R allocated again and synced; only
  * then is it published, the mark removed and the emergency state left. The
  * decision and the start of the allocation are one step under the lock,
  * which hands out a permit naming the generation; every [[enter]] bumps
  * the generation, so an allocation decided before it never publishes.
  */
object Emergency:
  /** R: the reserve. */
  val R: Long = 64L << 20
  /** E: below this free space the emergency state is entered. */
  val E: Long = 32L << 20
  /** How long F must stay at or above [[ResumeFree]] before recovery. */
  val SustainMs: Long = 60_000L
  /** Allocation step: a stale or cancelled allocation stops between steps. */
  val Step: Long = 4L << 20
  /** F needed to recover from an emergency: H + 2R. */
  val ResumeFree: Long = Headroom.H + 2 * R
  /** F needed to create R the first time: R + E and a margin of 16 MiB (an operating value). */
  val CreateFree: Long = R + E + (16L << 20)

  enum Reason(val label: String):
    case LowSpace extends Reason("空き容量が 32 MiB 未満")
    case SqliteFull extends Reason("データベースの書き込みが容量不足")
    case OverflowFull extends Reason("退避ファイルの書き込みが容量不足")
    case Reported extends Reason("ほかの処理が容量不足を検出")

  /** The reserve file on disk: none (never created), at full length, or shorter (released). */
  enum OnDisk:
    case Absent, Full, Released

  /** What the reserve's files and the free space are (the app: the file system). */
  trait Files:
    def free(): Long
    def onDisk(): OnDisk
    /** Removes the allocation file of an earlier attempt (absent: nothing). */
    def discardTmp(): Unit
    /** Allocates `[from, from + n)` of the allocation file really (not sparse). */
    def allocateTmp(from: Long, n: Long): Unit
    def syncTmp(): Unit
    /** Renames the allocation file over the reserve and syncs the directory. */
    def publish(): Unit
    /** Truncates the reserve to 0 and syncs it; its length afterwards. */
    def release(): Long
    /** Removes the reserve (when it cannot be truncated). */
    def remove(): Boolean
    /** Writes the emergency mark durably (the file and its directory synced); throws. */
    def mark(): Unit
    def marked: Boolean
    /** Removes the emergency mark durably; throws. */
    def unmark(): Unit
    /** Renames R itself to the mark and syncs the directory (when the mark
      * cannot be written: the rename takes no room); throws.
      */
    def markByRename(): Unit
    /** The mark's length (R renamed to it is long). */
    def markLength: Long
    /** Truncates the mark to 0 and syncs it; its length afterwards; throws. */
    def releaseMark(): Long

  /** What happened, for the lifecycle log, the alert and the status. */
  enum Event:
    /** Entered (`first`) or renewed: `released` bytes the free space grew by, `failure` of the mark or the release. */
    case Entered(reason: Reason, detail: String, first: Boolean, released: Option[Long], failure: Option[String])
    case Created(gen: Long)
    case Recovered(gen: Long)
    case AllocationFailed(why: String)
    case AllocationCancelled(gen: Long)

  /** The way of one logging batch. */
  enum Route:
    /** To SQLite; `probe`: the batch that decides recovery. */
    case Sqlite(probe: Boolean)
    /** Directly to an overflow file. */
    case Overflow

  /** The state the app shows. */
  enum Status(val label: String):
    case Ready extends Status("確保済み")
    case None extends Status("予備なし")
    case Allocating extends Status("確保中")
    case Released extends Status("解放（記録を退避中）")

  /** Leave to allocate R, given with the decision ([[EmergencyReserve.tick]]). */
  final case class Permit(gen: Long)

/** The reserve and the emergency state of the main process (see [[Emergency]]).
  * Thread-safe: the logger's write thread calls [[route]] and [[enter]], a
  * background thread [[tick]] and [[allocate]], other work [[enter]].
  */
final class EmergencyReserve(files: Emergency.Files, clock: () => Long, stopLarge: () => Unit,
                             events: Emergency.Event => Unit = _ => (),
                             r: Long = Emergency.R, e: Long = Emergency.E, resumeFree: Long = Emergency.ResumeFree,
                             sustainMs: Long = Emergency.SustainMs, step: Long = Emergency.Step,
                             createFree: Long = Emergency.CreateFree):
  import Emergency.*

  private var emergency = false
  private var ready = false
  private var gen = 0L
  private var sustainSince = -1L
  // 0: none; 1: the next batch tries SQLite; 3: taken by a batch; 2: it was saved there.
  private var probe = 0
  private var allocating = false
  private var restored = false

  /** Reads the state from the files: the mark, or a released R, is the
    * emergency state (an R left whole is released again); a full R without
    * the mark is ready; nothing is "not created". An allocation file left by
    * a process that died is removed (never published).
    */
  def restore(): Unit = synchronized {
    if !restored then
      restored = true
      scala.util.Try(files.discardTmp())
      val disk = files.onDisk()
      if scala.util.Try(files.marked).getOrElse(true) || disk == OnDisk.Released then
        emergency = true
        // Not released before (a release that failed, or the process died first).
        if disk == OnDisk.Full then releaseLocked()
        // R renamed to the mark and not truncated yet: truncated now (the mark stays).
        if scala.util.Try(files.markLength).getOrElse(0L) > 0 then releaseMarkLocked()
        // Released without the mark (it could not be written): written now.
        if !scala.util.Try(files.marked).getOrElse(true) then scala.util.Try(files.mark())
      else if disk == OnDisk.Full then ready = true
  }

  private def restoredLocked(): Unit = if !restored then restore()

  def active: Boolean = synchronized { restoredLocked(); emergency }
  /** Large work other than logging may start and go on. */
  def largeWorkAllowed: Boolean = synchronized { restoredLocked(); ready && !emergency }
  def status: Status = synchronized {
    restoredLocked()
    if emergency then Status.Released else if ready then Status.Ready
    else if allocating then Status.Allocating else Status.None
  }
  def generation: Long = synchronized(gen)

  /** Before a logging batch: its way. F below E enters (or renews) the
    * emergency state; in it, batches go to overflow, but for the one batch
    * that decides recovery.
    */
  def route(): Route =
    val low = files.free() < e
    synchronized {
      restoredLocked()
      if low then enterLocked(Reason.LowSpace, "")
      if !emergency then Route.Sqlite(probe = false)
      else if probe == 1 then
        probe = 3 // taken: one batch only
        Route.Sqlite(probe = true)
      else Route.Overflow
    }

  /** Before an overflow write outside [[route]]: F below E enters (or renews) the emergency state. */
  def checkSpace(): Unit =
    if files.free() < e then enter(Reason.LowSpace)

  /** The batch of a probe was saved in SQLite: recovery may allocate R. */
  def probeSaved(): Unit = synchronized { if emergency && probe == 3 then probe = 2 }

  /** The batch of a probe failed for another reason than space: the next one tries. */
  def probeFailed(): Unit = synchronized { if emergency && probe == 3 then probe = 1 }

  /** Enters the emergency state, or renews it (the recovery clock starts
    * again, a permit or an allocation in progress is void): the mark first,
    * then large work asked to stop, then R released (truncated to 0 and
    * synced; removed if that fails).
    */
  def enter(reason: Reason, detail: String = ""): Unit = synchronized {
    restoredLocked()
    enterLocked(reason, detail)
  }

  private def enterLocked(reason: Reason, detail: String): Unit =
    val first = !emergency
    emergency = true
    gen += 1
    sustainSince = -1
    probe = 0
    if allocating then scala.util.Try(files.discardTmp())
    // The mark before anything else: a restart restores the state whatever
    // happens to R below, or if the process dies in between.
    val markFailure =
      if !first && scala.util.Try(files.marked).getOrElse(false) then None
      else scala.util.Try(files.mark()).failed.toOption.map(describe)
    if first then scala.util.Try(stopLarge())
    val disk = scala.util.Try(files.onDisk()).getOrElse(OnDisk.Full)
    // No mark could be written: R itself becomes the mark by a rename (no
    // room needed), so a mark exists before R shrinks; then it is truncated.
    val renamed = markFailure.isDefined && disk != OnDisk.Absent && scala.util.Try(files.markByRename()).isSuccess
    val (released, releaseFailure) =
      if renamed then releaseMarkLocked()
      else if disk == OnDisk.Full || ready then releaseLocked() else (None, None)
    // A mark that failed for want of room: tried again with what R gave back.
    val markFailure2 = if renamed then None else markFailure.flatMap(m => scala.util.Try(files.mark()).failed.toOption.map(_ => m))
    ready = false
    val failure = (markFailure2.map(m => s"印を書けず: $m").toSeq ++ releaseFailure).reduceOption(_ + "・" + _)
    // Logged once per entry, and for a renewal that changed something.
    if first || released.isDefined then events(Event.Entered(reason, detail, first, released, failure))

  private def describe(ex: Throwable): String = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)

  /** Releases R: truncated (its length checked), else removed, but only
    * while the mark exists (without it, R is the last record of the state).
    * (bytes the free space grew by, failure).
    */
  private def releaseLocked(): (Option[Long], Option[String]) =
    val before = files.free()
    var failure: Option[String] = None
    def removeIfMarked(): Boolean =
      if !scala.util.Try(files.marked).getOrElse(false) then
        failure = failure.map(_ + "、印がないため削除せず")
        true
      else scala.util.Try(files.remove()).getOrElse(false)
    val ok =
      try
        val left = files.release()
        if left != 0 then
          failure = Some(s"切り詰め後も $left バイト")
          removeIfMarked()
        else true
      catch case ex: Exception =>
        failure = Some(describe(ex))
        removeIfMarked()
    if !ok then failure = failure.map(_ + "、削除にも失敗")
    (Some(files.free() - before), failure.map(f => s"解放に失敗: $f"))

  /** Truncates R renamed to the mark; never removes it (the last mark). */
  private def releaseMarkLocked(): (Option[Long], Option[String]) =
    val before = files.free()
    val failure =
      try
        val left = files.releaseMark()
        Option.when(left != 0)(s"印にした予備の切り詰め後も $left バイト")
      catch case ex: Exception => Some(s"印にした予備を切り詰められず: ${describe(ex)}")
    (Some(files.free() - before), failure.map(f => s"解放に失敗: $f"))

  /** Called every few seconds: the recovery clock, or the first creation of
    * R. A permit is the decision to allocate now, registered under the same
    * lock (the allocation has begun: [[allocate]] must follow); any [[enter]]
    * after it voids it. With `logging` (batches are coming), recovery first
    * tries one batch in SQLite.
    */
  def tick(logging: Boolean): Option[Permit] =
    val f = files.free()
    synchronized {
      restoredLocked()
      if allocating then None
      else if !emergency then
        if !ready && f >= createFree then begin() else None
      else if f < resumeFree then
        sustainSince = -1
        if probe != 2 then probe = 0
        None
      else
        val now = clock()
        if sustainSince < 0 then sustainSince = now
        if now - sustainSince < sustainMs then None
        else if logging && probe != 2 then
          if probe == 0 then probe = 1
          None
        else begin()
    }

  private def begin(): Option[Permit] =
    allocating = true
    Some(Permit(gen))

  /** Allocates R under `permit` and publishes it if nothing entered the
    * emergency state since the permit (generation unchanged). F falling
    * below E plus a step while allocating cancels it (the logger sees the
    * shortage itself before its next write). True once published.
    */
  def allocate(permit: Permit): Boolean =
    val g = permit.gen
    val valid = synchronized {
      restoredLocked()
      allocating && gen == g
    }
    def stale = synchronized(gen != g)
    def cancel(): Boolean =
      scala.util.Try(files.discardTmp())
      synchronized { sustainSince = -1; if probe == 2 then probe = 0 }
      events(Event.AllocationCancelled(g))
      false
    try
      if !valid then cancel()
      else
        files.discardTmp()
        var at = 0L
        var cancelled = false
        while at < r && !cancelled do
          if stale || files.free() < e + step then cancelled = true
          else
            val n = math.min(step, r - at)
            files.allocateTmp(at, n)
            at += n
        if cancelled then cancel()
        else
          files.syncTmp()
          synchronized {
            if gen != g then cancel()
            else
              try files.publish()
              catch case ex: Exception =>
                // Possibly in place already: released again (never left ready
                // on disk while this process says it is not).
                scala.util.Try(files.discardTmp())
                if emergency && scala.util.Try(files.onDisk()).toOption.contains(OnDisk.Full) then scala.util.Try(files.release())
                throw ex
              val was = emergency
              ready = true
              emergency = false
              probe = 0
              sustainSince = -1
              // The mark last: a restart before this still restores the
              // emergency (and releases R again: the safe side).
              if was then
                scala.util.Try(files.unmark()).failed.foreach(ex => events(Event.AllocationFailed(s"印を消せず: ${describe(ex)}")))
              events(if was then Event.Recovered(g) else Event.Created(g))
              true
          }
    catch case ex: Exception =>
      scala.util.Try(files.discardTmp())
      synchronized { sustainSince = -1; if probe == 2 then probe = 0 }
      events(Event.AllocationFailed(describe(ex)))
      false
    finally synchronized { allocating = false }

  /** Out of the emergency state but its mark still on disk (its removal failed): removed again. */
  def tidy(): Unit = synchronized {
    if restored && !emergency && ready && scala.util.Try(files.marked).getOrElse(false) then scala.util.Try(files.unmark())
  }

/** One logging batch's way to disk (plan C stage 2): SQLite, or an overflow
  * file, or counted lost; see [[EmergencyReserve]].
  */
object EmergencyPath:
  enum Outcome:
    case Saved, Spilled, Lost

  trait Ops:
    /** The batch in one SQLite transaction (retries of a locked database inside). */
    def commit(): Unit
    /** The batch to its overflow file (idempotent: [[OverflowBatch]]). */
    def spill(): Unit
    /** The batch counted lost (`why`), at once on disk and in the log. */
    def lost(why: String): Unit
    /** A failure of SQLite for lack of space (SQLITE_FULL). */
    def full(e: Throwable): Boolean
    /** A failure of a file write for lack of space (ENOSPC). */
    def noSpace(e: Throwable): Boolean
    def describe(e: Throwable): String

  def persist(reserve: EmergencyReserve, ops: Ops): Outcome =
    reserve.route() match
      case Emergency.Route.Overflow => spill(reserve, ops, "記録の退避中")
      case Emergency.Route.Sqlite(probe) =>
        try
          ops.commit()
          if probe then reserve.probeSaved()
          Outcome.Saved
        catch case e: Exception =>
          if ops.full(e) then
            // Never retried in SQLite (it would use up the room released):
            // the transaction ended (Db.tx) and the batch goes to overflow.
            reserve.enter(Emergency.Reason.SqliteFull, ops.describe(e))
          else if probe then reserve.probeFailed()
          spill(reserve, ops, ops.describe(e))

  /** The batch to overflow; one failing for lack of space enters the
    * emergency state (R released) and is tried once more, then lost.
    */
  def spill(reserve: EmergencyReserve, ops: Ops, why: String): Outcome =
    try
      reserve.checkSpace()
      ops.spill()
      Outcome.Spilled
    catch case e: Exception =>
      if ops.noSpace(e) then
        reserve.enter(Emergency.Reason.OverflowFull, ops.describe(e))
        try
          ops.spill()
          Outcome.Spilled
        catch case e2: Exception =>
          ops.lost(s"$why、退避にも失敗: ${ops.describe(e2)}")
          Outcome.Lost
      else
        ops.lost(s"$why、退避にも失敗: ${ops.describe(e)}")
        Outcome.Lost
