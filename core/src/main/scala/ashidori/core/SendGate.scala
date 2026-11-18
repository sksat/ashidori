package ashidori.core

import scala.collection.mutable

/** Whether work may send to an external service now: the per-destination
  * rules ([[Policy]]), the pause over them, and the permits taken under them
  * (docs/design/outbound-and-offline.md 4).
  *
  * Every send holds a [[Permit]] taken right before it ([[permit]]) and
  * checked again before each retry, redirect and read ([[valid]]). A permit
  * is valid only in this process ([[epoch]]), while nothing is paused,
  * while the rule allows its mode, and while its destination's generation
  * is unchanged. A generation is a random 64-bit value, new on every change
  * of that destination's rule (its level included) and on every pause or
  * resume, and stored with the rules: work that outlives the process
  * (WorkManager) keeps the generation it was asked under and takes a new
  * permit only if it is still current ([[permitFor]]).
  *
  * An explicit request to show an online map is remembered per map surface
  * as a [[Consent]] ([[remember]]): which map, of which destination, at
  * which level. It lasts across leaving the screen and restarts until the
  * user switches back ([[forget]]), the destination's rule changes, or
  * sending is paused. While a map of a consenting surface is on screen, it
  * holds a grant ([[grant]]): a permit given to that one view.
  * [[networkAllowed]] (the map library's process-wide switch) follows the
  * automatic map rules and the grants as a second line.
  *
  * Changes are stored through `persist` in one write, then the listeners
  * hear which destinations changed. Thread-safe.
  */
final class SendGate(initial: SendGate.State, persist: SendGate.State => Unit = _ => (),
                     rnd: java.util.Random = java.security.SecureRandom(),
                     val epoch: Epoch = Epoch.random(),
                     revokeWaitMs: Long = SendGate.RevokeWaitMs):
  import SendGate.{Change, Revoker, State}
  private var state = initial
  private val grants = mutable.Map.empty[AnyRef, Permit]
  private val listeners = mutable.LinkedHashSet.empty[Set[Dest] => Unit]
  private val revokers = mutable.LinkedHashSet.empty[Revoker]

  def policy: Policy = synchronized(state.policy)
  def rule(d: Dest): Rule = synchronized(state.policy.rule(d))
  def paused: Boolean = synchronized(state.paused)
  /** Why the stored rules were not used as they are, until the user changes one. */
  def problem: Option[Policy.Problem] = synchronized(state.problem)
  def generation(d: Dest): Long = synchronized(state.gens(d))
  /** What is stored now. */
  def snapshot: State = synchronized(state)

  /** When `d` may be sent to now: its rule's, or never while paused. */
  def when(d: Dest): When = synchronized(if state.paused then When.Never else state.policy.rule(d).when)

  private def allows(d: Dest, mode: Mode): Boolean = !state.paused && state.policy.rule(d).allows(mode)

  /** Sets `d`'s rule (one it offers, [[Policy.valid]]). A change, also of the
    * level only, gives `d` a new generation and ends `d`'s grants and
    * consents; it is stored, then the listeners hear of `d`. Returns whether
    * it changed.
    */
  def setRule(d: Dest, r: Rule): Boolean = changeRule(d, r) != Change.Unchanged

  /** [[setRule]], with what became of the sends in flight ([[Change]]). */
  def changeRule(d: Dest, r: Rule): Change =
    require(Policy.valid(d, r), s"$d does not offer $r")
    val changed = synchronized {
      if state.policy.rules.get(d).contains(r) && state.problem.isEmpty then false
      else
        state = state.copy(policy = state.policy.updated(d, r), problem = None,
          gens = state.gens.updated(d, fresh(state.gens(d))),
          consents = state.consents.filterNot(_._2.dest == d))
        grants.filterInPlace((_, p) => p.dest != d)
        persist(state)
        true
    }
    if changed then revoke(Set(d)) else Change.Unchanged

  /** Starts or ends the pause (design 4.5): the rules stay, but every
    * destination is treated as never while it lasts. Either way every
    * destination gets a new generation and every grant ends; starting it
    * also ends every consent, so an explicit send does not come back by
    * itself when it ends.
    */
  def setPaused(p: Boolean): Boolean = changePaused(p) != Change.Unchanged

  /** [[setPaused]], with what became of the sends in flight ([[Change]]). */
  def changePaused(p: Boolean): Change =
    val changed = synchronized {
      if state.paused == p then false
      else
        state = state.copy(paused = p, gens = state.gens.map((d, g) => d -> fresh(g)),
          consents = if p then Map.empty else state.consents)
        grants.clear()
        persist(state)
        true
    }
    if changed then revoke(Dest.values.toSet) else Change.Unchanged

  private def fresh(old: Long): Long =
    var g = rnd.nextLong()
    while g == old do g = rnd.nextLong()
    g

  /** After a change (permits of `ds` already invalid): every revoker aborts
    * what it has in flight for `ds`, all against one deadline; then the
    * listeners hear of it. [[Change.Done]] when every aborted send ended by
    * the deadline, else [[Change.Stopping]] with those still ending (their
    * abort goes on).
    */
  private def revoke(ds: Set[Dest]): Change =
    val deadline = System.nanoTime() + revokeWaitMs * 1_000_000L
    val pending = synchronized(revokers.toVector).flatMap(r => try r(ds, deadline) catch case _: Exception => Nil)
    synchronized(listeners.toVector).foreach(_(ds))
    val all = java.util.concurrent.CompletableFuture.allOf(pending*)
    val left = math.max(0L, deadline - System.nanoTime())
    try all.get(left, java.util.concurrent.TimeUnit.NANOSECONDS) catch case _: Exception => ()
    val open = pending.count(!_.isDone)
    if open == 0 then Change.Done else Change.Stopping(open, all.thenApply(_ => ()))

  /** Aborts what is in flight on a change ([[Revoker]]). Returns the function that stops it. */
  def onRevoke(f: Revoker): () => Unit =
    synchronized(revokers += f)
    () => synchronized(revokers -= f)

  /** Called after every change with the destinations it concerns. Returns the function that stops it. */
  def onChange(f: Set[Dest] => Unit): () => Unit =
    synchronized(listeners += f)
    () => synchronized(listeners -= f)

  /** A permit to send to `d` in `mode` now, or None if the rule does not allow it. */
  def permit(d: Dest, mode: Mode): Option[Permit] = synchronized {
    Option.when(allows(d, mode))(Permit(epoch, d, state.gens(d), mode, state.policy.rule(d).level))
  }

  /** Work asked for under generation `gen` (e.g. stored with a WorkManager
    * job): a new permit if that generation is still `d`'s and the rule
    * allows `mode`, else None (the work then ends without sending).
    */
  def permitFor(d: Dest, mode: Mode, gen: Long): Option[Permit] = synchronized {
    if state.gens(d) != gen then None else permit(d, mode)
  }

  /** `p` may still be used to send: this process, not paused, the rule
    * still allows its mode, and its destination's generation is unchanged.
    */
  def valid(p: Permit): Boolean = synchronized {
    p.epoch == epoch && state.gens(p.dest) == p.gen && allows(p.dest, p.mode)
  }

  // ---- consents and grants (map surfaces) ----

  /** The user explicitly chose the online map `map` (of destination `d`) on
    * `surface`: remembered, at `d`'s level, while `d` is sent to on action.
    */
  def remember(surface: String, map: String, d: Dest): Unit = synchronized {
    if Dest.Tiles(d) && !state.paused && state.policy.rule(d).when == When.OnAction then
      val c = Consent(map, d, state.policy.rule(d).level)
      if !state.consents.get(surface).contains(c) then
        state = state.copy(consents = state.consents.updated(surface, c))
        persist(state)
  }

  /** The user switched `surface` back to the on-device map. */
  def forget(surface: String): Unit = synchronized {
    if state.consents.contains(surface) then
      state = state.copy(consents = state.consents - surface)
      persist(state)
  }

  /** The online map consented to on `surface`, while its destination is
    * sent to on action at the level it was given at.
    */
  def consent(surface: String): Option[Consent] = synchronized {
    state.consents.get(surface).filter(c =>
      !state.paused && state.policy.rule(c.dest).when == When.OnAction && state.policy.rule(c.dest).level == c.level)
  }

  /** Every remembered consent (what is stored). */
  def consents: Map[String, Consent] = synchronized(state.consents)

  /** `owner`, a map of a consenting surface, is on screen with permit `p`. */
  def grant(owner: AnyRef, p: Permit): Unit = synchronized {
    if valid(p) then grants(owner) = p else grants -= owner
  }

  /** `owner` no longer shows the online map (switched back, or left the screen). */
  def revoke(owner: AnyRef): Unit = synchronized(grants -= owner)

  /** The permit `owner` was granted, while it is valid. */
  def granted(owner: AnyRef): Option[Permit] = synchronized(grants.get(owner).filter(valid))

  /** `owner` may show `d`'s online map: `d` is sent to automatically, or it holds a grant for `d`. */
  def online(owner: AnyRef, d: Dest): Boolean = synchronized {
    allows(d, Mode.Auto) || granted(owner).exists(_.dest == d)
  }

  /** The map library may use the network: some map destination is sent to
    * automatically, or some view holds a valid grant.
    */
  def networkAllowed: Boolean = synchronized {
    Dest.Tiles.exists(allows(_, Mode.Auto)) || grants.values.exists(valid)
  }

  // ---- queued work ----

  /** The generations of `dests` when work was asked for. */
  def ticket(dests: Set[Dest]): SendGate.Ticket = synchronized(SendGate.Ticket(epoch, dests.map(d => d -> state.gens(d)).toMap))

  /** No rule of the ticket's destinations changed since it was taken (in this process). */
  def current(t: SendGate.Ticket): Boolean = synchronized(t.epoch == epoch && t.gens.forall((d, g) => state.gens(d) == g))

/** Map snapshots, rendered one at a time in the order asked for.
  *
  * A job picks its base map right before it starts (taking a permit then),
  * not when it was queued, and a job queued before a change of the rules of
  * `dests` never starts ([[invalidate]] drops it; its ticket would also be
  * stale). `H` is the handle of a running job (e.g. its snapshotter), which
  * [[invalidate]] gives back to be cancelled. While the screen showing the
  * snapshots is leaving or away ([[hold]] until [[release]]), no job
  * starts: views leaving one by one end their jobs, and the queue does not
  * go on to the next one meanwhile. Not thread-safe: use from one thread
  * (the main thread).
  */
final class SnapshotQueue[H](gate: SendGate, dests: Set[Dest] = Dest.Tiles):
  private final case class Job(ticket: SendGate.Ticket, start: () => Option[H])
  private val queue = mutable.Queue.empty[Job]
  private var running: Option[H] = None
  private var busy = false
  private var held = false

  def queued: Int = queue.size
  def isBusy: Boolean = busy
  def isHeld: Boolean = held

  /** Queues a job. `start()` begins rendering and returns its handle, or
    * None when it ended at once (failed, nothing to do); the job then calls
    * [[finished]] with that handle when it is done. While held it waits for
    * [[release]].
    */
  def enqueue(start: () => Option[H]): Unit =
    queue.enqueue(Job(gate.ticket(dests), start))
    if !busy && !held then next()

  /** The job with handle `h` is done; a handle that is no longer the running
    * one (cancelled by [[invalidate]] or [[hold]]) is ignored. While held,
    * the next job waits for [[release]].
    */
  def finished(h: H): Unit =
    if running.contains(h) then
      running = None
      busy = false
      if !held then next()

  /** The screen leaves (before its views do): drops queued jobs and returns
    * the running one to be cancelled; no job starts until [[release]].
    */
  def hold(): Option[H] =
    held = true
    invalidate()

  /** The screen is shown again: queued jobs start. */
  def release(): Unit =
    if held then
      held = false
      if !busy then next()

  /** A rule of `dests` changed: drops queued jobs and returns the running one to be cancelled. */
  def invalidate(): Option[H] =
    queue.clear()
    val r = running
    running = None
    busy = false
    r

  /** Drops queued jobs (e.g. the screen reloaded); a running one finishes. */
  def clearQueued(): Unit = queue.clear()

  private def next(): Unit =
    busy = false
    while !busy && !held && queue.nonEmpty do
      val job = queue.dequeue()
      if gate.current(job.ticket) then
        busy = true
        try
          running = job.start()
          if running.isEmpty then busy = false
        catch
          case _: Exception =>
            running = None
            busy = false

/** The map consents of versions before per-destination settings
  * (`mapConsents`): read once to migrate them, and written for those
  * versions ([[ExternalSettings]]).
  */
object MapConsents:
  /** A stored value as read back. */
  enum Stored[+A]:
    case Missing
    /** Present but of the wrong type or unreadable. */
    case Bad
    case Ok(value: A)

  private val Version = 1

  /** One versioned JSON record; values are escaped, so no URL can forge another entry. */
  def encode(consents: Map[String, String]): String =
    ujson.write(ujson.Obj(
      "v" -> Version,
      "consents" -> ujson.Arr(consents.toSeq.sorted.map((s, m) => ujson.Obj("surface" -> s, "map" -> m))*)
    ))

  /** The record's (surface, map) pairs, or None if it is not a well-formed record. */
  def decode(json: String): Option[Seq[(String, String)]] =
    try
      val o = ujson.read(json).obj
      if o.get("v").flatMap(_.numOpt).contains(Version.toDouble) then
        o.get("consents").map(_.arr.toSeq.map { e =>
          val r = e.obj
          (r("surface").str, r("map").str)
        })
      else None
    catch case _: Exception => None

  /** The old setting and consents as the old version read them: a setting
    * that is missing or unreadable is on with no consents, a consent record
    * that is not exactly what [[encode]] writes gives none, and entries of
    * an unknown surface or a map no longer valid are dropped.
    */
  def load(block: Stored[Boolean], consents: Stored[String],
           surfaces: Set[String], validMap: String => Boolean): (Boolean, Map[String, String]) =
    block match
      case Stored.Ok(false) => (false, Map.empty)
      case Stored.Ok(true) =>
        val pairs = consents match
          case Stored.Ok(json) => decode(json).filter(ps => encode(ps.toMap) == json).getOrElse(Nil)
          case _               => Nil
        if pairs.map(_._1).distinct.size != pairs.size then (true, Map.empty)
        else (true, pairs.filter((s, m) => surfaces(s) && validMap(m)).toMap)
      case _ => (true, Map.empty)

object SendGate:
  /** How long a change waits for the sends it aborted to end, in all. */
  val RevokeWaitMs = 3_000L

  /** Called on a change with its destinations and the common deadline
    * (`System.nanoTime`): starts aborting what it has in flight for them
    * and returns one future per aborted send, completed when that send has
    * ended (no further byte from it). The abort goes on after the deadline
    * until they all completed.
    */
  type Revoker = (Set[Dest], Long) => Seq[java.util.concurrent.CompletableFuture[Unit]]

  /** What a change of the rules or the pause did to the sends in flight. */
  enum Change:
    /** Nothing changed. */
    case Unchanged
    /** Every send of the destinations concerned has ended: none sends any more. */
    case Done
    /** `pending` sends were still ending at the deadline; `done` completes when they have. */
    case Stopping(pending: Int, done: java.util.concurrent.CompletableFuture[Unit])

  /** What is stored together (design 3.4): the rules (written only while
    * readable or changed by the user: `problem` is None), consents,
    * generations and the pause.
    */
  final case class State(policy: Policy, consents: Map[String, Consent], gens: Map[Dest, Long],
                         paused: Boolean = false, problem: Option[Policy.Problem] = None)

  /** The generations of some destinations when work was asked for. */
  final case class Ticket(epoch: Epoch, gens: Map[Dest, Long])

  /** Tries `attempt` on each of `targets` in order until one succeeds.
    * `allowed` is checked right before each try: once it is false, nothing
    * more is tried and the result is [[Blocked]].
    */
  def firstSuccess[T, A](targets: Seq[T], allowed: () => Boolean)(attempt: T => A): Either[Stop, A] =
    val errors = mutable.ArrayBuffer.empty[Throwable]
    val it = targets.iterator
    while it.hasNext do
      val t = it.next()
      if !allowed() then return Left(Blocked)
      try return Right(attempt(t))
      catch case e: Exception => errors += e
    Left(Failed(errors.toSeq))

  sealed trait Stop
  /** The rule (or its generation) no longer allows sending. */
  case object Blocked extends Stop
  /** Every target failed. */
  final case class Failed(errors: Seq[Throwable]) extends Stop
