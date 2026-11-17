package ashidori.core

import java.time.{Instant, ZoneId}
import scala.collection.mutable.ArrayBuffer

/** A row of `energy_state`: open (`end` None: the current state) or closed.
  * `endUnknown`: the state was last confirmed at `end`; when it really ended
  * is not known (the process ended without closing it).
  */
final case class EnergyRow(start: Long, end: Option[Long], state: EnergyState, endUnknown: Boolean = false):
  def span: EnergySpan = EnergySpan(start, end.getOrElse(start), state, !endUnknown)

/** The last record of state changes applied: the recorder (`token`, one
  * per process, ordered by start time), its last change applied (`seq`),
  * and when its state was last confirmed and stored (`confirmedAt`).
  */
final case class EnergyCheckpoint(token: String, seq: Long, confirmedAt: Long)

/** How a record of state changes ([[SmallRecord.Energy]]) changes the
  * `energy_state` table. Pure: the app runs it in one transaction with the
  * open rows and the checkpoint it reads, the tests against a model.
  *
  *  - Changes at or below the checkpoint's `seq` of the same recorder were
  *    applied before: a record sent again (a retry, a copy imported from
  *    its overflow file) changes nothing twice.
  *  - The first record of a new recorder (a new process) closes what the
  *    previous one left open at the previous checkpoint's `confirmedAt`,
  *    marked `endUnknown`; with no checkpoint, at its start (zero length,
  *    still marked unknown, never mistaken for a real zero-length span).
  *  - A record of an older recorder than the checkpoint's is ignored: its
  *    process is over and its spans were closed when the next one started.
  *  - The open row is closed at every local midnight up to `confirmedAt`
  *    (and reopened at midnight), so no row stays open across days.
  *  - Rows are never updated: the open row is replaced (deleted, and the
  *    result inserted with new rowids; see [[DbSchema.archivable]]).
  *  - Nothing is written inside a deleted range ([[deleted]]): rows are cut
  *    at its edges.
  */
object EnergyLog:
  /** `replaceOpen`: delete the open rows and insert `insert` (closed and
    * open rows); otherwise only the checkpoint changes.
    */
  final case class Change(replaceOpen: Boolean, insert: Seq[EnergyRow], checkpoint: EnergyCheckpoint)

  private def midnightOf(t: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(t).atZone(zone).toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli

  /** Closed rows of [start, end), cut at local midnights; the last one marked unknown if asked. */
  private def closed(start: Long, end: Long, state: EnergyState, unknown: Boolean, zone: ZoneId): Seq[EnergyRow] =
    val parts = EnergySpan.splitDays(EnergySpan(start, math.max(start, end), state), zone)
    parts.zipWithIndex.map((p, i) => EnergyRow(p.start, Some(p.end), state, unknown && i == parts.size - 1))

  /** Rows cut out of the deleted ranges. An open row starting inside one
    * starts at its end, or is dropped if that end is not reached yet.
    */
  def clip(rows: Seq[EnergyRow], deleted: Seq[(Long, Long)], now: Long): Seq[EnergyRow] =
    deleted.foldLeft(rows) { case (rs, (a, b)) =>
      rs.flatMap { r =>
        val end = r.end.getOrElse(Long.MaxValue)
        if end <= a || r.start >= b then Seq(r)
        else
          val before = Option.when(r.start < a)(r.copy(end = Some(a), endUnknown = false))
          val after =
            if end <= b then None
            else if r.end.isEmpty then Option.when(b <= now)(r.copy(start = b))
            else Some(r.copy(start = b))
          // A zero-length row marked unknown stays (it records the restart).
          before.toSeq ++ after.toSeq
      }
    }

  def apply(open: Seq[EnergyRow], cp: Option[EnergyCheckpoint], r: SmallRecord.Energy, zone: ZoneId,
            deleted: Seq[(Long, Long)] = Nil): Option[Change] =
    if cp.exists(_.token > r.token) then return None
    val same = cp.exists(_.token == r.token)
    val out = ArrayBuffer.empty[EnergyRow]
    var cur: Vector[EnergyRow] = open.toVector
    var changed = false
    def closeAll(at: Long, unknown: Boolean): Unit =
      for o <- cur do out ++= closed(o.start, at, o.state, unknown, zone)
      if cur.nonEmpty then changed = true
      cur = Vector.empty
    if !same then
      // The previous process's open rows: confirmed until its checkpoint.
      val at = cp.map(_.confirmedAt).getOrElse(Long.MinValue)
      for o <- cur do out ++= closed(o.start, math.max(o.start, at), o.state, unknown = true, zone)
      if cur.nonEmpty then changed = true
      cur = Vector.empty
    var seq = if same then cp.get.seq else 0L
    var confirmed = if same then cp.get.confirmedAt else Long.MinValue
    for t <- r.transitions.sortBy(_.seq) if t.seq > seq do
      val start = (cur.map(_.start) :+ t.at).max
      closeAll(start, unknown = false)
      t.state.foreach { s =>
        cur = Vector(EnergyRow(start, None, s))
        changed = true
      }
      seq = t.seq
      confirmed = math.max(confirmed, t.at)
    confirmed = math.max(confirmed, r.confirmedAt)
    // Still in the state at `confirmed`: closed at each midnight before it.
    cur = cur.flatMap { o =>
      val m = midnightOf(confirmed, zone)
      if o.start < m then
        out ++= closed(o.start, m, o.state, unknown = false, zone)
        changed = true
        Seq(EnergyRow(m, None, o.state))
      else Seq(o)
    }
    // No open row (a deleted range took it) while the recorder is in a state.
    if cur.isEmpty && !changed && r.current.isDefined && open.isEmpty && same then
      cur = Vector(EnergyRow(confirmed, None, r.current.get))
      changed = true
    val rows = clip((out ++ cur).toSeq, deleted, confirmed)
    val clipped = rows != (out ++ cur).toSeq
    Some(Change(changed || clipped, if changed || clipped then rows else Nil,
      EnergyCheckpoint(r.token, seq, if same then math.max(cp.get.confirmedAt, confirmed) else confirmed)))

/** The recorder's side: the state changes not confirmed as stored yet, in
  * order. Every record it hands over carries all of them, so a record that
  * is lost (dropped, failed) is covered by the next one, and one that comes
  * late is a no-op ([[EnergyLog]]). At most `maxPending` are kept; older
  * ones beyond that are dropped and counted.
  */
final class EnergyTracker(val token: String, maxPending: Int = 2000):
  private var seq = 0L
  private var state: Option[EnergyState] = None
  private val pending = ArrayBuffer.empty[EnergyTransition]
  @volatile private var confirmedSeq = 0L
  /** Changes dropped over `maxPending`. */
  var dropped = 0L

  def current: Option[EnergyState] = state

  /** Records a change to `st` (None: stopped); false if it is the same state. */
  def update(now: Long, st: Option[EnergyState]): Boolean =
    if state == st then false
    else
      state = st
      seq += 1
      pending += EnergyTransition(seq, now, st)
      if pending.size > maxPending then
        val n = pending.size - maxPending
        pending.remove(0, n)
        dropped += n
      true

  /** The record to hand over now: every change not confirmed, and `now`
    * as the time the current state is confirmed.
    */
  def record(now: Long): SmallRecord.Energy =
    val c = confirmedSeq
    pending.filterInPlace(_.seq > c)
    SmallRecord.Energy(token, pending.toVector, now, state)

  /** The record holding changes up to `seq` is stored (any thread). */
  def confirmed(upTo: Long): Unit = synchronized { if upTo > confirmedSeq then confirmedSeq = upTo }

  def unconfirmed: Int = pending.count(_.seq > confirmedSeq)

object EnergyTracker:
  /** A token that sorts after those of earlier processes. */
  def newToken(now: Long): String = f"$now%013d-${java.util.UUID.randomUUID().toString.take(8)}"
