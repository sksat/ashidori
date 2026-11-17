package ashidori.core

import java.time.{Instant, ZoneId}
import scala.collection.mutable.ArrayBuffer

/** A row of `energy_state`: open (`end` None: the current state) or closed.
  * `endUnknown`: the state was last confirmed at `end`; when it really ended
  * is not known (the process ended without closing it). `boot` and `seq`
  * name the state change that started it (kept in SQLite only); `id` is the
  * rowid (0 for a row not stored yet).
  */
final case class EnergyRow(start: Long, end: Option[Long], state: EnergyState, endUnknown: Boolean = false,
                           boot: Long = 0L, seq: Long = 0L, id: Long = 0L):
  def span: EnergySpan = EnergySpan(start, end.getOrElse(start), state, !endUnknown, boot, seq, open = end.isEmpty)

/** What one recorder (`boot`: one per process, numbered in start order by
  * a counter kept on disk) has had stored: its last change applied (`seq`)
  * and when its state was last confirmed and stored (`confirmedAt`).
  */
final case class EnergyCheckpoint(boot: Long, seq: Long, confirmedAt: Long)

/** How a record of state changes ([[SmallRecord.Energy]]) changes the
  * `energy_state` table. Pure: the app runs it in one transaction with what
  * it reads ([[Before]]), the tests against a model.
  *
  * The history is each recorder's changes in `seq` order, the recorders in
  * `boot` order. Each recorder's rows depend only on its own changes and
  * checkpoint, so records may arrive in any order:
  *  - Changes at or below the recorder's checkpoint `seq` were applied
  *    before: a record sent again, or a copy imported from its overflow
  *    file, changes nothing twice.
  *  - Changes that come late are inserted where they belong: the
  *    recorder's last row is re-closed at the first of them, and the rest
  *    follow. A record of an earlier recorder that comes after a later one
  *    stored its rows is applied the same way, never dropped.
  *  - A recorder that is not the latest is over: its last row is closed at
  *    its `confirmedAt`, marked `endUnknown`. When a later recorder stores
  *    its first record, the earlier ones' open rows are closed so.
  *  - A change is never earlier than the one before it of the same
  *    recorder (a clock that went back starts it at the previous one's
  *    time instead). Recorders are ordered by their counter, not by clock
  *    times, so a clock going back across a restart does not reorder them.
  *  - The latest recorder's open row is closed at every local midnight up
  *    to `confirmedAt` (and reopened at midnight): no row stays open across
  *    days.
  *  - Rows are never updated: a row that changes is deleted and inserted
  *    anew with a new rowid ([[DbSchema.archivable]]).
  *  - Nothing is written inside a deleted range: rows are cut at its edges.
  */
object EnergyLog:
  /** The table as far as a record of one recorder touches it. `tail`: the
    * recorder's last row (by seq, then start); `newest`: no later recorder
    * stored anything; `olderOpen`: open rows of earlier recorders, with
    * their recorders' `confirmedAt` if known.
    */
  final case class Before(tail: Option[EnergyRow], checkpoint: Option[EnergyCheckpoint], newest: Boolean,
                          olderOpen: Seq[(EnergyRow, Option[Long])] = Nil)

  /** One span per piece of a state change: the rows of the same origin
    * (`boot`, `seq`) and the same start can be there twice, an earlier and
    * a later version (the archive kept a row that was closed again after it
    * was read). The later one wins: a closed one over an open one, one with
    * a known end over one whose end is unknown, then the later end (a later
    * confirmation). Rows of no origin (`boot` 0, before origins were kept)
    * and rows of different origins are all kept, even with the same start.
    */
  def select(spans: Seq[EnergySpan]): Vector[EnergySpan] =
    def rank(s: EnergySpan) = (if s.open then 0 else if s.endKnown then 2 else 1, s.end)
    val (known, legacy) = spans.partition(_.boot != 0)
    (known.groupBy(s => (s.boot, s.seq, s.start)).values.map(_.maxBy(rank)).toVector ++ legacy.distinct.toVector)
      .sortBy(s => (s.start, s.end))

  /** Rows to delete (by id), rows to insert, and the recorder's new checkpoint. */
  final case class Change(remove: Seq[Long], insert: Seq[EnergyRow], checkpoint: EnergyCheckpoint)

  private def midnightOf(t: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(t).atZone(zone).toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli

  /** Closed rows of [r.start, end), cut at local midnights; the last one marked unknown if asked. */
  private def closed(r: EnergyRow, end: Long, unknown: Boolean, zone: ZoneId): Seq[EnergyRow] =
    val parts = EnergySpan.splitDays(EnergySpan(r.start, math.max(r.start, end), r.state), zone)
    parts.zipWithIndex.map((p, i) => r.copy(start = p.start, end = Some(p.end), endUnknown = unknown && i == parts.size - 1, id = 0L))

  /** Rows cut out of the deleted ranges. An open row starting inside one
    * starts at its end, or is dropped if that end is not reached yet.
    */
  def clip(rows: Seq[EnergyRow], deleted: Seq[(Long, Long)], now: Long): Seq[EnergyRow] =
    deleted.foldLeft(rows) { case (rs, (a, b)) =>
      rs.flatMap { r =>
        val end = r.end.getOrElse(Long.MaxValue)
        if end <= a || r.start >= b then Seq(r)
        else
          val before = Option.when(r.start < a)(r.copy(end = Some(a), endUnknown = false, id = 0L))
          val after =
            if end <= b then None
            else if r.end.isEmpty then Option.when(b <= now)(r.copy(start = b, id = 0L))
            else Some(r.copy(start = b, id = 0L))
          before.toSeq ++ after.toSeq
      }
    }

  def apply(before: Before, r: SmallRecord.Energy, zone: ZoneId, deleted: Seq[(Long, Long)] = Nil): Change =
    val b = r.boot
    val seq0 = before.checkpoint.map(_.seq).getOrElse(0L)
    val conf0 = before.checkpoint.map(_.confirmedAt).getOrElse(Long.MinValue)
    val ts = r.transitions.filter(_.seq > seq0).sortBy(_.seq)
    val confirmedIn = Seq(conf0, r.confirmedAt) ++ ts.map(_.at)
    // The row new changes or a later confirmation extend: the recorder's
    // open row, or its last row closed only provisionally (unknown end).
    val tail = before.tail
    val start: Option[EnergyRow] = tail.filter(t => t.end.isEmpty ||
      (t.endUnknown && (ts.nonEmpty || (!before.newest && confirmedIn.max > t.end.get))))
    val out = ArrayBuffer.empty[EnergyRow]
    var cur = start
    var lastAt = tail.map(_.start).getOrElse(Long.MinValue)
    for t <- ts do
      val at = math.max(t.at, lastAt)
      cur.foreach(c => out ++= closed(c, at, unknown = false, zone))
      cur = t.state.map(s => EnergyRow(at, None, s, boot = b, seq = t.seq))
      lastAt = at
    val confirmed = (confirmedIn :+ lastAt).max
    val remove = ArrayBuffer.empty[Long]
    start.foreach(s => if s.id != 0 then remove += s.id)
    if before.newest then
      // Still in the state at `confirmed`: closed at each midnight before it.
      cur = cur.map { o =>
        val m = midnightOf(confirmed, zone)
        if o.start < m then
          out ++= closed(o, m, unknown = false, zone)
          o.copy(start = m, id = 0L)
        else o
      }
      // The earlier recorders are over.
      for (o, at) <- before.olderOpen do
        remove += o.id
        out ++= closed(o, at.getOrElse(o.start), unknown = true, zone)
      // No open row (a deleted range took it) while the recorder is in a state.
      if cur.isEmpty && ts.isEmpty && tail.forall(_.end.isDefined) && r.current.isDefined then
        cur = Some(EnergyRow(confirmed, None, r.current.get, boot = b, seq = seq0))
    else
      // A later recorder exists: this one's last state lasted at least until `confirmed`.
      cur.foreach(c => out ++= closed(c, confirmed, unknown = true, zone))
      cur = None
    val rows = clip((out ++ cur).toSeq, deleted, confirmed)
    val cp = EnergyCheckpoint(b, ts.lastOption.fold(seq0)(_.seq), confirmed)
    // Nothing changed but the checkpoint (a heartbeat): leave the rows alone.
    if remove.size == 1 && start.exists(s => rows == Seq(s)) && before.olderOpen.isEmpty then Change(Nil, Nil, cp)
    else if remove.isEmpty && rows.isEmpty then Change(Nil, Nil, cp)
    else Change(remove.toSeq, rows.map(_.copy(id = 0L)), cp)

/** The recorder's side: the state changes not confirmed as stored yet, in
  * order. Every record it hands over carries all of them, so a record that
  * is lost (dropped, failed) is covered by the next one, and one that comes
  * late is a no-op ([[EnergyLog]]). At most `maxPending` are kept; older
  * ones beyond that are dropped and counted.
  */
final class EnergyTracker(val boot: Long, maxPending: Int = 2000):
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
    SmallRecord.Energy(boot, pending.toVector, now, state)

  /** The record holding changes up to `seq` is stored (any thread). */
  def confirmed(upTo: Long): Unit = synchronized { if upTo > confirmedSeq then confirmedSeq = upTo }

  def unconfirmed: Int = pending.count(_.seq > confirmedSeq)
