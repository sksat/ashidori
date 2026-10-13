package ashidori.core

import scala.collection.mutable

/** Whether work may send positions to an external service now, under
  * 「位置情報を外部サービスに送らない」 (`block`).
  *
  * Every change of the setting starts a new generation. Work started
  * without a user action (automatic: map snapshots, venue prefetch, the
  * venue search a sheet starts by itself) takes a [[ticket]] when it is
  * asked for and checks it right before each send: it may send only with
  * the setting off and its ticket of the current generation. So work asked
  * for before a change never sends after it.
  *
  * An explicit request to show the online map is a grant to one owner (a
  * map view) for the current generation. It lets that owner send, nobody
  * else, and ends when the owner revokes it (it left the screen) or the
  * setting changes. [[networkAllowed]] (the map library's process-wide
  * switch) only follows the grants as a second line: what keeps other work
  * from sending is that it does not use an online style or ask a server.
  *
  * Thread-safe.
  */
final class SendGate(initialBlock: Boolean):
  private var blocked = initialBlock
  private var gen = 0L
  private val grants = mutable.Map.empty[AnyRef, Long]
  private val listeners = mutable.LinkedHashSet.empty[() => Unit]

  def block: Boolean = synchronized(blocked)
  def generation: Long = synchronized(gen)

  /** Sets the setting. A change starts a new generation, ends every grant
    * and then tells the listeners (to drop queued work and abort running
    * work). Returns whether it changed.
    */
  def setBlock(b: Boolean): Boolean =
    val changed = synchronized {
      if b == blocked then false
      else
        blocked = b
        gen += 1
        grants.clear()
        true
    }
    if changed then synchronized(listeners.toVector).foreach(_())
    changed

  /** Called after every change of the setting. Returns the function that stops it. */
  def onChange(f: () => Unit): () => Unit =
    synchronized(listeners += f)
    () => synchronized(listeners -= f)

  /** Taken when automatic work is asked for. */
  def ticket(): Long = generation

  /** Automatic work with `ticket` may send now. */
  def automatic(ticket: Long): Boolean = synchronized(!blocked && ticket == gen)

  /** Work asked for under `ticket` is still of the current generation
    * (whatever it may send then is decided by the policy at that time).
    */
  def current(ticket: Long): Boolean = synchronized(ticket == gen)

  /** `owner` explicitly asked to show the online map. */
  def grant(owner: AnyRef): Unit = synchronized(grants(owner) = gen)

  /** `owner` no longer shows the online map (switched back, or left the screen). */
  def revoke(owner: AnyRef): Unit = synchronized(grants -= owner)

  /** `owner` holds a grant of the current generation. */
  def granted(owner: AnyRef): Boolean = synchronized(grants.get(owner).contains(gen))

  /** `owner` may show the online map: the setting is off, or it holds a grant. */
  def online(owner: AnyRef): Boolean = synchronized(!blocked || grants.get(owner).contains(gen))

  /** The map library may use the network: the setting is off, or some view holds a grant. */
  def networkAllowed: Boolean = synchronized(!blocked || grants.values.exists(_ == gen))

/** Map snapshots, rendered one at a time in the order asked for.
  *
  * A job is started with the setting as it is right before it starts
  * (`block`), so it picks its base map then, not when it was queued, and a
  * job queued before a change of the setting never starts ([[invalidate]]
  * drops it; its ticket would also be stale). `H` is the handle of a
  * running job (e.g. its snapshotter), which [[invalidate]] gives back to
  * be cancelled. Not thread-safe: use from one thread (the main thread).
  */
final class SnapshotQueue[H](gate: SendGate):
  private final case class Job(ticket: Long, start: Boolean => Option[H])
  private val queue = mutable.Queue.empty[Job]
  private var running: Option[H] = None
  private var busy = false

  def queued: Int = queue.size
  def isBusy: Boolean = busy

  /** Queues a job. `start(block)` begins rendering and returns its handle,
    * or None when it ended at once (failed, nothing to do); the job then
    * calls [[finished]] with that handle when it is done.
    */
  def enqueue(start: Boolean => Option[H]): Unit =
    queue.enqueue(Job(gate.ticket(), start))
    if !busy then next()

  /** The job with handle `h` is done; a handle that is no longer the running
    * one (cancelled by [[invalidate]]) is ignored.
    */
  def finished(h: H): Unit =
    if running.contains(h) then
      running = None
      next()

  /** The setting changed: drops queued jobs and returns the running one to
    * be cancelled.
    */
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
    while !busy && queue.nonEmpty do
      val job = queue.dequeue()
      if gate.current(job.ticket) then
        busy = true
        try
          running = job.start(gate.block)
          if running.isEmpty then busy = false
        catch
          case _: Exception =>
            running = None
            busy = false

object SendGate:
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
  /** The setting (or its generation) no longer allows sending. */
  case object Blocked extends Stop
  /** Every target failed. */
  final case class Failed(errors: Seq[Throwable]) extends Stop
