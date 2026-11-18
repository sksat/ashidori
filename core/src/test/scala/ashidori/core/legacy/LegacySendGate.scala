// The rules before per-destination settings (465b8b1), kept as the reference of
// the equivalence test (OutboundEquivalenceSuite). Not used by the app.
package ashidori.core.legacy

import scala.collection.mutable

/** Whether work may send positions to an external service now, under
  * 「位置情報を外部サービスに送らない」 (`block`).
  *
  * Every change of the setting starts a new generation. Work started
  * without a user action (automatic: map snapshots) takes a [[ticket]] when it is
  * asked for and checks it right before each send: it may send only with
  * the setting off and its ticket of the current generation. So work asked
  * for before a change never sends after it.
  *
  * An explicit request to show the online map is remembered per map
  * surface (e.g. the map tab, the period view) as a consent
  * ([[remember]]): which online map the user chose there. It lasts across
  * leaving the screen and app restarts ([[consents]] is what the app
  * stores), until the user switches back ([[forget]]) or the setting
  * changes. A consent is never shared between surfaces, and automatic work
  * does not look at it.
  *
  * While a map of a consenting surface is on screen, it holds a grant
  * ([[grant]]): transient, to that one view (owner), for the current
  * generation, and ended when the view leaves the screen ([[revoke]]) or the
  * setting changes. [[networkAllowed]] (the map library's process-wide
  * switch) follows the grants as a second line: what keeps other work from
  * sending is that it does not use an online style or ask a server.
  *
  * Thread-safe.
  */
final class SendGate(initialBlock: Boolean, initialConsents: Map[String, String] = Map.empty,
                     persist: (Boolean, Map[String, String]) => Unit = (_, _) => ()):
  private var blocked = initialBlock
  private var gen = 0L
  private val grants = mutable.Map.empty[AnyRef, Long]
  /** Surface -> the online map consented to there. Only kept with the setting on. */
  private var remembered: Map[String, String] = if initialBlock then initialConsents else Map.empty
  private val listeners = mutable.LinkedHashSet.empty[() => Unit]

  def block: Boolean = synchronized(blocked)
  def generation: Long = synchronized(gen)

  /** Sets the setting. A change starts a new generation, ends every grant
    * and every remembered consent (stored together with the setting through
    * `persist`, so neither outlives the other), and then tells the listeners
    * (to drop queued work and abort running work). Returns whether it changed.
    */
  def setBlock(b: Boolean): Boolean =
    val changed = synchronized {
      if b == blocked then false
      else
        blocked = b
        gen += 1
        grants.clear()
        remembered = Map.empty
        persist(blocked, remembered)
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

  /** The user explicitly chose the online map `map` on `surface` (setting on). */
  def remember(surface: String, map: String): Unit = synchronized {
    if blocked && !remembered.get(surface).contains(map) then
      remembered += surface -> map
      persist(blocked, remembered)
  }

  /** The user switched `surface` back to the on-device map. */
  def forget(surface: String): Unit = synchronized {
    if remembered.contains(surface) then
      remembered -= surface
      persist(blocked, remembered)
  }

  /** The online map consented to on `surface`, while the setting is on. */
  def consent(surface: String): Option[String] = synchronized(if blocked then remembered.get(surface) else None)

  /** Every remembered consent (what the app stores). */
  def consents: Map[String, String] = synchronized(remembered)

  /** `owner`, a map of a consenting surface, is on screen. */
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

/** The stored setting and map consents ([[SendGate]]'s `persist`). */
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

  /** The setting and consents to start with. Fails safe: a setting that is
    * missing or unreadable is on with no consents (whatever consents are
    * stored), and a consent record that is unreadable or not in the exact
    * form [[encode]] writes (e.g. a key or surface given twice) gives no
    * consents. Entries for an unknown surface or a map that
    * is no longer valid (e.g. a changed custom URL) are dropped.
    */
  def load(block: Stored[Boolean], consents: Stored[String],
           surfaces: Set[String], validMap: String => Boolean): (Boolean, Map[String, String]) =
    block match
      case Stored.Ok(false) => (false, Map.empty)
      case Stored.Ok(true) =>
        val pairs = consents match
          // Canonical only: what decodes must encode back to exactly the
          // stored text (no repeated JSON keys, no repeated surfaces, no
          // extra fields), so no reading picks one of two values.
          case Stored.Ok(json) => decode(json).filter(ps => encode(ps.toMap) == json).getOrElse(Nil)
          case _               => Nil
        if pairs.map(_._1).distinct.size != pairs.size then (true, Map.empty)
        else (true, pairs.filter((s, m) => surfaces(s) && validMap(m)).toMap)
      case _ => (true, Map.empty)

object SendGate:
  /** The settings switch is the positive 「オンライン地図と地名検索」 (on = use
    * them), shown inverted over the stored `block` (key
    * "blockExternalLocation", true = do not send), whose meaning and default
    * stay as they were. Only the switch maps on/off.
    */
  def switchOn(block: Boolean): Boolean = !block
  /** The `block` value a position of the switch stores. */
  def blockFor(switchOn: Boolean): Boolean = !switchOn

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
