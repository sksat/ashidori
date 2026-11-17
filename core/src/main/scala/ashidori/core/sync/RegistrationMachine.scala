package ashidori.core.sync

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{Executor, ExecutorService}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.util.Try

/** A registration request whose token is already stored. Retrying it with
  * the same token is idempotent on the server.
  * `kind` is "account" (`POST /v1/accounts`: a new user, this device its
  * first) or "pair" (`POST /v1/devices/register`: a device added to a user).
  * `code` is the pairing code ("pair"), or the invite code ("account"; empty
  * for open registration without an invite). `username` is the username the
  * person chose ("account" only; empty in requests stored by older versions,
  * which the server recovers by the token alone).
  */
final case class PendingRegistration(url: String, kind: String, code: String, name: String, token: String,
                                     username: String = ""):
  def host: String = Try(java.net.URI(url).getHost).toOption.flatMap(Option(_)).getOrElse(url)

  def toBytes: Array[Byte] =
    ujson.write(ujson.Obj("url" -> url, "kind" -> kind, "code" -> code, "name" -> name, "token" -> token,
      "username" -> username)).getBytes(UTF_8)

object PendingRegistration:
  def fromBytes(b: Array[Byte]): Option[PendingRegistration] =
    Try {
      val js = ujson.read(String(b, UTF_8))
      PendingRegistration(js("url").str, js("kind").str, js("code").str, js("name").str, js("token").str,
        js.obj.get("username").map(_.str).getOrElse(""))
    }.toOption

/** The server refused a registration for good (a 4xx other than 408/429). */
class RegistrationRejected(msg: String) extends RuntimeException(msg)

/** The server refused a new account because another user has `username`
  * (409). Final like any refusal: the person chooses another username, which
  * is a new request with a new token.
  */
final class UsernameTakenError(val username: String) extends RegistrationRejected(s"ユーザ名「$username」はすでに使われています")

/** What the registration screen shows: the stored request, and whether an
  * attempt to send it is in flight.
  */
final case class RegistrationState(pending: Option[PendingRegistration], sending: Boolean)

/** Outcomes of registration attempts. Results carry the id of their attempt
  * (0: not tied to one); a screen ignores those that are not
  * [[RegistrationMachine.isLatest]], e.g. of an attempt cancelled since.
  */
enum RegistrationEvent:
  /** The state changed; redraw. */
  case Changed
  /** Attempt `attempt` paired this device. */
  case Paired(attempt: Long, newAccount: Boolean)
  /** Attempt `attempt` (or starting / cancelling one) failed. */
  case Failed(attempt: Long, message: String)
  /** Attempt `attempt` was refused because `username` is taken; the request is dropped. */
  case UsernameTaken(attempt: Long, username: String)

/** What [[RegistrationMachine]] stores. Every method blocks and returns
  * once the change is on disk, or throws.
  */
trait RegistrationStorage:
  def load(): Option[PendingRegistration]
  def store(p: Option[PendingRegistration]): Unit
  /** Already registered, or an unpairing is under way: no new request. */
  def busy: Boolean
  def savePairing(p: PendingRegistration, deviceId: String): Unit

/** Device registration with a client-generated token: an app-wide state
  * machine, independent of any screen instance.
  *
  * The token (`randomToken`) is stored, and checked to be on disk, before
  * the request is sent (`send`). A lost response is retried with the same
  * token and request; the server answers with the same device id. The
  * pending request is discarded only when the server definitely refused it
  * or the user cancelled it.
  *
  * Each send is an attempt with its own id. `current` is the attempt in
  * flight: its id while it can still be cancelled, minus its id while its
  * answer is being taken in, 0 when there is none. Taking in an answer first
  * moves `current` from id to -id; cancelling moves it from id to 0. Both are
  * one compare-and-set on the same value, so exactly one of them wins: once
  * [[cancel]] returned true, nothing of that attempt is saved, and once the
  * answer is being taken in, a cancel reports that it came too late and
  * changes nothing (the request stays, a failed save is reported and can be
  * retried).
  *
  * Threads: every storage step (storing the request, saving the pairing,
  * cancelling, resetting) runs on the single `local` executor, in order, and
  * never does network I/O. Requests go out on `network`, whose answers are
  * handed back to `local`. [[start]], [[retry]] and [[cancel]] never block.
  */
final class RegistrationMachine(
    storage: RegistrationStorage,
    send: PendingRegistration => String,
    randomToken: () => String,
    emit: RegistrationEvent => Unit,
    local: ExecutorService,
    network: Executor
):
  private val ids = AtomicLong(0)
  private val current = AtomicLong(0)
  /** The newest attempt started, or a newer id after a cancel or reset. */
  private val latest = AtomicLong(0)
  /** The stored request, cached once read. None: not read yet. */
  private val cached = AtomicReference[Option[Option[PendingRegistration]]](None)

  /** The stored request. Takes no lock (read from disk on first use). */
  def pending: Option[PendingRegistration] =
    cached.get.getOrElse {
      val p = Try(storage.load()).toOption.flatten
      // A writer that stored a newer value in the meantime wins.
      cached.compareAndSet(None, Some(p))
      cached.get.get
    }

  def state: RegistrationState = RegistrationState(pending, current.get != 0)

  /** Results of `attempt` are still wanted: no attempt started, and no
    * successful cancel or reset happened, after it. Attempt 0 (not tied to
    * one) always is.
    */
  def isLatest(attempt: Long): Boolean = attempt == 0 || attempt == latest.get

  // --- storage (on `local` only) ---

  private def store(p: Option[PendingRegistration]): Unit =
    storage.store(p)
    cached.set(Some(p))

  /** Runs `f` on `local` and waits for it. Blocking: never on `local`. */
  private def onLocal[A](f: => A): A =
    try local.submit(() => f).get()
    catch case e: java.util.concurrent.ExecutionException => throw e.getCause

  /** Claims a new attempt id, unless one is in flight. Non-blocking. */
  private def claim(): Option[Long] =
    val id = ids.incrementAndGet()
    if current.compareAndSet(0, id) then
      latest.set(id)
      Some(id)
    else None

  /** Ends attempt `id` if it is still the current one (not taken in, not cancelled). */
  private def release(id: Long): Unit =
    if current.compareAndSet(id, 0) then emit(RegistrationEvent.Changed)

  /** Stores a new request with a fresh token, checked to be on disk. On `local`. */
  private def begin(id: Long, url: String, kind: String, code: String, name: String, username: String): PendingRegistration =
    if current.get != id then throw IllegalStateException("登録を中止しました")
    if storage.busy || pending.isDefined then throw IllegalStateException("登録の処理がすでに進んでいます")
    val p = PendingRegistration(url.stripSuffix("/"), kind, code, name, randomToken(), username)
    store(Some(p))
    p

  /** Takes in the answer to attempt `id`, unless it was cancelled or reset
    * meanwhile. On `local`. Returns the device id when this attempt paired
    * the device, None when its answer was dropped.
    */
  private def finish(id: Long, p: PendingRegistration, answer: Try[String]): Option[String] =
    // From here on the attempt cannot be cancelled; a cancel that came first wins.
    if !current.compareAndSet(id, -id) then None
    else
      try
        answer match
          case scala.util.Success(deviceId) =>
            storage.savePairing(p, deviceId)
            // Paired now; a request left over would only be cleared by unpairing.
            Try(store(None))
            Some(deviceId)
          case scala.util.Failure(e: RegistrationRejected) =>
            store(None)
            throw e
          case scala.util.Failure(e) => throw e
      finally current.compareAndSet(-id, 0)

  /** Sends attempt `id` of `p` on `network`; its answer is taken in on `local`. */
  private def run(id: Long, p: PendingRegistration): Unit =
    network.execute { () =>
      val answer = Try(send(p))
      local.execute { () =>
        Try(finish(id, p, answer)) match
          case scala.util.Success(Some(_)) => emit(RegistrationEvent.Paired(id, p.kind == "account"))
          case scala.util.Success(None)    => () // cancelled meanwhile: dropped
          case scala.util.Failure(e: UsernameTakenError) => emit(RegistrationEvent.UsernameTaken(id, e.username))
          case scala.util.Failure(e)       => emit(RegistrationEvent.Failed(id, e.getMessage))
        emit(RegistrationEvent.Changed)
      }
    }

  /** Starts a registration: stores the request, then sends it. Non-blocking. */
  def start(url: String, kind: String, code: String, name: String, username: String = ""): Unit =
    claim() match
      case None => emit(RegistrationEvent.Failed(0, "登録の処理がすでに進んでいます"))
      case Some(id) =>
        emit(RegistrationEvent.Changed)
        local.execute { () =>
          Try(begin(id, url, kind, code, name, username)) match
            case scala.util.Success(p) =>
              emit(RegistrationEvent.Changed)
              run(id, p)
            case scala.util.Failure(e) =>
              release(id)
              emit(RegistrationEvent.Failed(id, e.getMessage))
        }

  /** Sends the stored request again, with the same token. Non-blocking. */
  def retry(): Unit =
    claim() match
      case None => emit(RegistrationEvent.Failed(0, "登録の処理がすでに進んでいます"))
      case Some(id) =>
        emit(RegistrationEvent.Changed)
        local.execute { () =>
          pending match
            case Some(p) if current.get == id => run(id, p)
            case _ =>
              release(id)
              emit(RegistrationEvent.Failed(id, "登録の処理がありません"))
        }

  /** Gives up the request. Non-blocking.
    *
    * Returns false, and changes nothing, when the answer of the attempt in
    * flight is already being taken in (too late to cancel it): the request
    * stays, and the outcome of that attempt (paired, or a failed save that
    * can be retried) is still reported. Otherwise no result of that attempt
    * is saved from now on, and the stored request is removed on `local`,
    * after every step asked for before.
    */
  def cancel(): Boolean =
    var v = current.get
    while v > 0 && !current.compareAndSet(v, 0) do v = current.get
    if v < 0 then false
    else
      latest.set(ids.incrementAndGet())
      emit(RegistrationEvent.Changed)
      local.execute { () =>
        Try(store(None)).failed.foreach(e => emit(RegistrationEvent.Failed(0, e.getMessage)))
        emit(RegistrationEvent.Changed)
      }
      true

  /** Forgets any request and invalidates any attempt (unpairing): once this
    * returned, no result of an earlier attempt is saved. Blocking.
    */
  def reset(): Unit =
    latest.set(ids.incrementAndGet())
    // On `local` no answer is being taken in, so `current` is not negative there.
    onLocal {
      current.set(0)
      store(None)
    }
    emit(RegistrationEvent.Changed)

  /** Registers on the calling thread and returns the device id. Blocking. */
  def registerBlocking(url: String, kind: String, code: String, name: String, username: String = ""): String =
    val id = claim().getOrElse(throw IllegalStateException("登録の処理がすでに進んでいます"))
    try
      val p = onLocal(begin(id, url, kind, code, name, username))
      val answer = Try(send(p))
      onLocal(finish(id, p, answer)).getOrElse(throw IllegalStateException("登録を中止しました"))
    finally release(id)
