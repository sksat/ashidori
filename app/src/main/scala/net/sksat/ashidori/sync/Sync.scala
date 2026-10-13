package net.sksat.ashidori.sync

import android.content.Context
import android.os.Build
import androidx.work.*
import java.io.File
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import scala.util.{Try, Using}
import net.sksat.ashidori.*
import ashidori.core.sync.*

/** Sync configuration. Secrets live in [[SecureStore]].
  *
  * The registration (URL, device id, token) and the master key are read only
  * once they are on disk ([[DurablePrefs]]): while one is being saved, or
  * after its save failed, it reads as absent, so neither the screen nor a
  * sync job counts the device as paired or ready on a value a restart would
  * lose.
  */
object SyncConfig:
  private val durable = DurablePrefs("sync")
  private def prefs = durable.raw

  def serverUrl: String = durable.getString("url").getOrElse("")
  def deviceId: String = durable.getString("deviceId").getOrElse("")
  def mobileData: Boolean = prefs.getBoolean("mobileData", true)
  def mobileData_=(b: Boolean): Unit = prefs.edit().putBoolean("mobileData", b).apply()
  def rawOnlyWhileCharging: Boolean = prefs.getBoolean("rawCharging", false)
  def rawOnlyWhileCharging_=(b: Boolean): Unit = prefs.edit().putBoolean("rawCharging", b).apply()
  def lastSync: Long = prefs.getLong("lastSync", 0L)
  def lastSync_=(t: Long): Unit = prefs.edit().putLong("lastSync", t).apply()
  def lastError: String = prefs.getString("lastError", "")
  def lastError_=(s: String): Unit = prefs.edit().putString("lastError", s).apply()

  def token: Option[String] = SecureStore.get("token").map(String(_, UTF_8))
  def masterKey: Option[Array[Byte]] = SecureStore.get("master")

  /** Sets the key of the registration `reg`, once. Refuses (false) when a key
    * is already set or the registration changed since the key step began, so
    * a confirmed key is never replaced. Returns true only once the key is on
    * disk; a failed write throws, the key then reads as absent and sync stays
    * off. Blocking: call off the main thread.
    */
  def confirmKey(k: Array[Byte], reg: String): Boolean = synchronized {
    if reg.isEmpty || reg != registration || masterKey.isDefined then false
    else
      SecureStore.put("master", k)
      true
  }

  /** This device created its user (self-registration), so no other device has a key yet. */
  def newAccount: Boolean = durable.getBoolean("newAccount", false)

  def paired: Boolean = serverUrl.nonEmpty && deviceId.nonEmpty && token.isDefined
  def ready: Boolean = paired && masterKey.isDefined

  /** Identifies the registration (server URL and device id). Sync progress
    * and peer state are keyed by it, so state of an earlier registration is
    * never used for a later one. Empty when not registered.
    */
  def registration: String =
    val (u, d) = (serverUrl, deviceId)
    if u.isEmpty || d.isEmpty then "" else Crypto.sha256Hex(s"$u\n$d".getBytes(UTF_8)).take(16)

  /** Stores the registration, each part checked to be on disk; throws
    * [[java.io.IOException]] otherwise. Blocking: call off the main thread.
    */
  def savePairing(url: String, deviceId: String, token: String, newAccount: Boolean = false): Unit =
    // The token first: the URL and id make the registration count.
    SecureStore.put("token", token.getBytes(UTF_8))
    try durable.commit("url" -> url.stripSuffix("/"), "deviceId" -> deviceId, "newAccount" -> newAccount)
    catch case e: java.io.IOException => throw java.io.IOException("登録情報を保存できませんでした", e)

  /** Unpairs this device. Blocking: call off the main thread.
    *
    * Invalidates any registration attempt first (none of its results is saved
    * afterwards), cancels the sync jobs and waits for a running one to end,
    * then clears the registration, its secrets, all sync progress (seq
    * counters, watermarks, handed-over archive files), unsent batches and
    * peer state. A later registration therefore uploads everything afresh,
    * sealed for its own device id.
    */
  def forget(ctx: Context): Unit =
    Registration.reset()
    SyncScheduler.cancelAll(ctx)
    SyncLock.run {
      durable.clear()
      SecureStore.remove("token")
      SecureStore.remove("master")
      AshidoriApp.db.clearSync()
      Uploader.deleteLegacyPending()
      PeerStatus.clear()
    }

/** Serializes all sync work (periodic small, sync now, raw logs, unpairing):
  * runs never interleave, so seq claims and outbox sends cannot race.
  */
object SyncLock:
  private val lock = java.util.concurrent.locks.ReentrantLock()

  def run[A](f: => A): A =
    lock.lock()
    try f
    finally lock.unlock()

  /** None when another run kept the lock for `waitMs`. */
  def tryRun[A](waitMs: Long)(f: => A): Option[A] =
    if !lock.tryLock(waitMs, TimeUnit.MILLISECONDS) then None
    else
      try Some(f)
      finally lock.unlock()

/** A sync run's fixed view of the registration, taken under [[SyncLock]]. */
final case class SyncRun(reg: String, deviceId: String, batchKey: Array[Byte], client: SyncClient, stopped: () => Boolean):
  def checkStopped(): Unit = if stopped() then throw SyncRun.Stopped()

object SyncRun:
  final class Stopped extends RuntimeException("stopped")

  /** None when sync is not set up (or the key is not confirmed yet). */
  def current(stopped: () => Boolean = () => false): Option[SyncRun] =
    val (url, dev) = (SyncConfig.serverUrl, SyncConfig.deviceId)
    for
      token <- SyncConfig.token
      key <- SyncConfig.masterKey
      if url.nonEmpty && dev.nonEmpty
    yield SyncRun(SyncConfig.registration, dev, Crypto.batchKey(key), SyncClient(url, Some(token)), stopped)

/** A registration request whose token is already stored. Retrying it with
  * the same token is idempotent on the server.
  * `kind` is "account" (`POST /v1/accounts`) or "pair" (`POST /v1/devices/register`).
  */
final case class PendingRegistration(url: String, kind: String, code: String, name: String, token: String):
  def host: String = Try(java.net.URI(url).getHost).toOption.flatMap(Option(_)).getOrElse(url)

/** The server refused a registration for good (a 4xx other than 408/429). */
final class RegistrationRejected(msg: String) extends RuntimeException(msg)

/** What the registration screen shows: the stored request, and whether an
  * attempt to send it is in flight.
  */
final case class RegistrationState(pending: Option[PendingRegistration], sending: Boolean)

/** Outcomes of registration attempts, delivered on the main thread. Results
  * carry the id of their attempt (0: not tied to one); a screen ignores those
  * that are not [[Registration.isLatest]], e.g. of an attempt cancelled since.
  */
enum RegistrationEvent:
  /** The state changed; redraw. */
  case Changed
  /** Attempt `attempt` paired this device. */
  case Paired(attempt: Long, newAccount: Boolean)
  /** Attempt `attempt` (or starting / cancelling one) failed. */
  case Failed(attempt: Long, message: String)

/** Device registration with a client-generated token: an app-wide state
  * machine, independent of any screen instance.
  *
  * The token (32 random bytes, base64url) is stored, and checked to be on
  * disk, before the request is sent. A lost response is retried with the
  * same token and request; the server answers with the same device id. The
  * pending request is discarded only when the server definitely refused it
  * or the user cancelled it.
  *
  * Each send is an attempt with its own id. `current` is the attempt in
  * flight: its id while it can still be cancelled, minus its id while its
  * answer is being taken in, 0 when there is none. Taking in an answer first
  * moves `current` from id to -id; cancelling moves it from id to 0. Both are
  * one compare-and-set on the same value, so exactly one of them wins: once
  * [[cancel]] returned true, nothing of that attempt is saved, and once the
  * answer is being taken in, a cancel reports that it came too late.
  *
  * Threads: every storage step (storing the request, saving the pairing,
  * cancelling, resetting) runs on the single `local` executor, in order, and
  * never does network I/O. Requests go out on the `network` executor, whose
  * answers are handed back to `local`. The main thread never blocks: the
  * screen calls [[start]], [[retry]] and [[cancel]] and observes [[state]].
  */
object Registration:
  private val Key = "pendingRegistration"
  private val ids = java.util.concurrent.atomic.AtomicLong(0)
  private val current = java.util.concurrent.atomic.AtomicLong(0)
  /** The newest attempt started, or a newer id after a cancel or reset. */
  private val latest = java.util.concurrent.atomic.AtomicLong(0)
  /** The stored request, cached once read. None: not read yet. */
  private val cached = java.util.concurrent.atomic.AtomicReference[Option[Option[PendingRegistration]]](None)
  /** Storage steps, in the order they were asked for. */
  private val local = java.util.concurrent.Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-registration"))
  /** Requests to the server; one per attempt, so a stuck one blocks nothing else. */
  private val network = java.util.concurrent.Executors.newCachedThreadPool(r => Thread(r, "ashidori-registration-net"))

  private def load(): Option[PendingRegistration] =
    Try(SecureStore.get(Key)).toOption.flatten.flatMap { b =>
      Try {
        val js = ujson.read(String(b, UTF_8))
        PendingRegistration(js("url").str, js("kind").str, js("code").str, js("name").str, js("token").str)
      }.toOption
    }

  /** The stored request. Takes no lock (read from disk on first use). */
  def pending: Option[PendingRegistration] =
    cached.get.getOrElse {
      val p = load()
      // A writer that stored a newer value in the meantime wins.
      cached.compareAndSet(None, Some(p))
      cached.get.get
    }

  def state: RegistrationState = RegistrationState(pending, current.get != 0)

  /** Results of `attempt` are still wanted: no attempt started, and no cancel
    * or reset happened, after it. Attempt 0 (not tied to one) always is.
    */
  def isLatest(attempt: Long): Boolean = attempt == 0 || attempt == latest.get

  // --- observers (main thread) ---

  private val listeners = scala.collection.mutable.LinkedHashSet.empty[RegistrationEvent => Unit]
  private lazy val main = android.os.Handler(android.os.Looper.getMainLooper)

  /** Main thread only. Returns the function that stops observing. */
  def observe(f: RegistrationEvent => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  private def emit(e: RegistrationEvent): Unit = main.post(() => listeners.toVector.foreach(_(e)))

  // --- storage (on `local` only) ---

  private def store(p: Option[PendingRegistration]): Unit =
    p match
      case Some(r) =>
        SecureStore.put(Key, ujson.write(ujson.Obj("url" -> r.url, "kind" -> r.kind, "code" -> r.code, "name" -> r.name,
          "token" -> r.token)).getBytes(UTF_8))
      case None => SecureStore.remove(Key)
    cached.set(Some(p))

  /** Runs `f` on `local` and waits for it. Blocking: never on the main thread or on `local`. */
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
  private def begin(id: Long, url: String, kind: String, code: String, name: String): PendingRegistration =
    if current.get != id then throw IllegalStateException("登録をやめました")
    if SyncConfig.paired || pending.isDefined then throw IllegalStateException("登録の処理がすでに進んでいます")
    val p = PendingRegistration(url.stripSuffix("/"), kind, code, name, Crypto.randomToken())
    store(Some(p))
    p

  /** Sends `p`; network errors are retried a few times. Blocking, on `network`. */
  private def send(p: PendingRegistration): String =
    val c = SyncClient(p.url, None)
    def attempt(n: Int): String =
      try if p.kind == "account" then c.createAccount(p.name, p.token) else c.register(p.code, p.name, p.token)
      catch
        case e: java.io.IOException if n < 3 =>
          Log.w(s"registration attempt $n failed", e)
          Thread.sleep(1000L * n * n)
          attempt(n + 1)
    attempt(1)

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
            SyncConfig.savePairing(p.url, deviceId, p.token, newAccount = p.kind == "account")
            // Paired now; a request left over would only be cleared by unpairing.
            Try(store(None)).failed.foreach(e => Log.w("pending registration not cleared", e))
            Some(deviceId)
          case scala.util.Failure(e: RegistrationRejected) =>
            store(None)
            throw e
          case scala.util.Failure(e) => throw e
      finally current.compareAndSet(-id, 0)

  /** Sends attempt `id` of `p` on the network executor; its answer is taken in on `local`. */
  private def run(id: Long, p: PendingRegistration): Unit =
    network.execute { () =>
      val answer = Try(send(p))
      local.execute { () =>
        Try(finish(id, p, answer)) match
          case scala.util.Success(Some(_)) => emit(RegistrationEvent.Paired(id, p.kind == "account"))
          case scala.util.Success(None)    => () // cancelled meanwhile: dropped
          case scala.util.Failure(e)       => emit(RegistrationEvent.Failed(id, e.getMessage))
        emit(RegistrationEvent.Changed)
      }
    }

  /** Starts a registration: stores the request, then sends it. Non-blocking. */
  def start(url: String, kind: String, code: String, name: String): Unit =
    claim() match
      case None => emit(RegistrationEvent.Failed(0, "登録の処理がすでに進んでいます"))
      case Some(id) =>
        emit(RegistrationEvent.Changed)
        local.execute { () =>
          Try(begin(id, url, kind, code, name)) match
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

  /** Gives up the request. Non-blocking. Returns false when the answer of the
    * attempt in flight is already being taken in (too late to cancel it).
    * Otherwise no result of that attempt is saved from now on, and the stored
    * request is removed on `local`, after every step asked for before.
    */
  def cancel(): Boolean =
    var v = current.get
    while v > 0 && !current.compareAndSet(v, 0) do v = current.get
    latest.set(ids.incrementAndGet())
    emit(RegistrationEvent.Changed)
    local.execute { () =>
      Try(store(None)).failed.foreach(e => emit(RegistrationEvent.Failed(0, e.getMessage)))
      emit(RegistrationEvent.Changed)
    }
    v >= 0

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

  /** Registers on the calling thread and returns the device id (debug hook). Blocking. */
  def registerBlocking(url: String, kind: String, code: String, name: String): String =
    val id = claim().getOrElse(throw IllegalStateException("登録の処理がすでに進んでいます"))
    try
      val p = onLocal(begin(id, url, kind, code, name))
      val answer = Try(send(p))
      onLocal(finish(id, p, answer)).getOrElse(throw IllegalStateException("登録をやめました"))
    finally release(id)


/** The server refused an upload because the user's storage quota is full.
  * This persists until the quota is raised, so it is not retried with backoff.
  */
final class QuotaExceeded extends RuntimeException("サーバの保存容量が上限に達しています")

/** The server listed a batch but does not serve it. */
final class BatchMissing(what: String) extends RuntimeException(s"batch $what not found")

/** Minimal HTTP client for the ashidori server. Blocking; call off the main thread. */
final class SyncClient(base: String, token: Option[String]):
  final case class Response(code: Int, body: Array[Byte]):
    def text: String = String(body, UTF_8)
    def json: ujson.Value = ujson.read(text)

  /** `maxBytes` caps a successful response body: reading aborts with
    * [[LimitExceeded]] once it is passed. Error bodies are cut to 4 KiB.
    */
  def request(method: String, path: String, body: Array[Byte] = null, contentType: String = "application/json",
              headers: Map[String, String] = Map.empty, maxBytes: Int = SyncClient.DefaultMax): Response =
    val c = URL(base + path).openConnection().asInstanceOf[HttpURLConnection]
    try
      c.setRequestMethod(method)
      c.setConnectTimeout(15_000)
      c.setReadTimeout(60_000)
      c.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName}")
      token.foreach(t => c.setRequestProperty("Authorization", s"Bearer $t"))
      headers.foreach((k, v) => c.setRequestProperty(k, v))
      if body != null then
        c.setDoOutput(true)
        c.setRequestProperty("Content-Type", contentType)
        c.setFixedLengthStreamingMode(body.length)
        Using.resource(c.getOutputStream)(_.write(body))
      val code = c.getResponseCode
      val bytes =
        if code >= 400 then Option(c.getErrorStream).fold(Array.emptyByteArray)(in => Using.resource(in)(_.readNBytes(4096)))
        else
          if c.getContentLengthLong > maxBytes then throw LimitExceeded(s"$method $path response", maxBytes)
          Option(c.getInputStream).fold(Array.emptyByteArray)(in =>
            Using.resource(in)(Limits.readAtMost(_, maxBytes, s"$method $path response")))
      Response(code, bytes)
    finally c.disconnect()

  /** A 4xx that a retry will not change; 408 and 429 are worth retrying. */
  private def refused(code: Int): Boolean = code >= 400 && code < 500 && code != 408 && code != 429

  /** Registers this device with a pairing code and the client-chosen device
    * `token`. A retry with the same token and code returns the same device
    * id. Returns the device id.
    */
  def register(code: String, name: String, token: String): String =
    val r = request("POST", "/v1/devices/register",
      ujson.write(ujson.Obj("code" -> code, "name" -> name, "token" -> token)).getBytes(UTF_8))
    r.code match
      case 200 | 201 => r.json("deviceId").str
      case 401 => throw RegistrationRejected("ペアリングコードが正しくないか、有効期限が切れています")
      case 429 => throw RuntimeException("登録の試行回数が上限に達しました。時間をおいて再度お試しください")
      case c if refused(c) => throw RegistrationRejected(s"登録に失敗しました ($c): ${r.text.take(200)}")
      case c => throw RuntimeException(s"登録に失敗しました ($c): ${r.text.take(200)}")

  /** Who may create users on this server: "admin" or "open". Anything else
    * (including an older server without `/v1/info`) counts as "admin".
    */
  def registration(): String =
    val r = request("GET", "/v1/info")
    if r.code == 200 && Try(r.json("registration").str).toOption.contains("open") then "open" else "admin"

  /** Creates a user with this device as its first device (open registration
    * only), with the client-chosen `token`; idempotent like [[register]].
    * Returns the device id.
    */
  def createAccount(deviceName: String, token: String): String =
    val r = request("POST", "/v1/accounts", ujson.write(ujson.Obj("deviceName" -> deviceName, "token" -> token)).getBytes(UTF_8))
    r.code match
      case 200 | 201 => r.json("deviceId").str
      case 403 => throw RegistrationRejected("このサーバでは新規登録を受け付けていません")
      case 429 => throw RuntimeException("登録の試行回数が上限に達しました。時間をおいて再度お試しください")
      case c if refused(c) => throw RegistrationRejected(s"登録に失敗しました ($c): ${r.text.take(200)}")
      case c => throw RuntimeException(s"登録に失敗しました ($c): ${r.text.take(200)}")

  def createPairing(): (String, Long) =
    val r = request("POST", "/v1/pairing", "{}".getBytes(UTF_8))
    if r.code != 200 && r.code != 201 then throw RuntimeException(s"HTTP ${r.code}")
    (r.json("code").str, r.json("expiresAt").num.toLong)

  /** Returns true when the server has the blob (newly or already). */
  def putBatch(stream: String, seq: Long, blob: Array[Byte]): Boolean =
    val r = request("PUT", s"/v1/batches/$stream/$seq", blob, "application/octet-stream",
      Map("X-Content-SHA256" -> Crypto.sha256Hex(blob)))
    r.code match
      case 200 | 201 => true
      case 409 => throw IllegalStateException(s"batch $stream/$seq conflicts with a different blob on the server")
      case 507 => throw QuotaExceeded()
      case c => throw RuntimeException(s"upload $stream/$seq failed: HTTP $c ${r.text.take(200)}")

  /** One page of the user's batch metadata after `cursor`, and the cursor to
    * continue from (None at the end). The page is capped in bytes and rows,
    * and malformed rows are refused.
    */
  def listBatches(after: Long, limit: Int): (Vector[BatchMeta], Option[Long]) =
    val r = request("GET", s"/v1/batches?after=$after&limit=$limit", maxBytes = SyncClient.MaxListing)
    if r.code != 200 then throw RuntimeException(s"list batches failed: HTTP ${r.code}")
    val js = r.json
    val items = js("items").arr
    if items.size > limit then throw LimitExceeded("listing rows", limit)
    (items.map(BatchMeta.fromJson).toVector, js.obj.get("next").flatMap(_.numOpt).map(_.toLong))

  /** The blob of another device's batch, as stored (still encrypted), of at most `maxBytes`. */
  def getBatch(deviceId: String, stream: String, seq: Long, maxBytes: Int): Array[Byte] =
    val r = request("GET", s"/v1/batches/$deviceId/$stream/$seq", maxBytes = maxBytes)
    if r.code == 404 then throw BatchMissing(s"$deviceId/$stream/$seq")
    if r.code != 200 then throw RuntimeException(s"fetch $deviceId/$stream/$seq failed: HTTP ${r.code}")
    r.body

  /** The user's devices: `[{id, name, createdAt, lastSeenAt, revoked}]`. */
  def devices(): ujson.Value =
    val r = request("GET", "/v1/devices")
    if r.code != 200 then throw RuntimeException(s"HTTP ${r.code}")
    r.json

object SyncClient:
  /** Response caps: small JSON (devices, info, registration), and one listing page (≤1000 rows). */
  val DefaultMax: Int = 1024 * 1024
  val MaxListing: Int = 1024 * 1024

/** Builds and uploads encrypted batches through the outbox (`sync_outbox`).
  *
  * Building a batch claims its seq, stores the ciphertext and records the
  * source progress (change-counter watermark, archive part handed over) in
  * one small transaction; the ciphertext is sealed before, outside it.
  * Sending deletes the row only after the server accepted it. A crash at
  * any point therefore neither reuses a seq for other bytes nor counts data
  * as sent that the server does not have.
  *
  * Rows carry a constraint class: "small" rows go out with the small-stream
  * job, "raw" rows only with the raw-log job (unmetered network).
  * Call only inside [[SyncLock]], with a [[SyncRun]].
  */
object Uploader:
  private val MaxRows = 5000
  private val MaxPlain = 4 * 1024 * 1024
  /** No more archive parts of a class are sealed while the outbox holds
    * more than this of that class unsent: the outbox does not grow
    * without bound next to the archive.
    */
  private val MaxOutboxBytes = 64L * 1024 * 1024
  val Small = "small"
  val Raw = "raw"

  /** Pending files of versions before the outbox. */
  private def legacyPendingDir: File = File(AshidoriApp.app.getFilesDir, "sync-pending")

  def deleteLegacyPending(): Unit =
    Option(legacyPendingDir.listFiles()).toSeq.flatten.foreach(_.delete())
    legacyPendingDir.delete()

  /** Discards the sync state of versions before the outbox, and rebuilds.
    *
    * That state (pending files in `sync-pending/`, unprefixed `seq:`, `up:`
    * and `updated:` rows) does not say which registration it belongs to:
    * an older version could unpair from server A, register with B and keep
    * A's pending blobs and marks. So none of it is sent or trusted. Pending
    * blobs are deleted unsent; the `up:` and `updated:` marks are dropped,
    * so every archive file and editable table is sealed again from the
    * source data for `run`'s registration.
    *
    * Only the seq numbers are kept, as a floor: each stream continues above
    * the highest legacy counter and pending file, so no new batch reuses a
    * slot an older version may have filled (a `409` would block the stream).
    * Skipped seqs are harmless gaps. Nothing older than this version is ever
    * sent.
    */
  private def discardLegacy(run: SyncRun): Unit =
    val files = Option(legacyPendingDir.listFiles()).toSeq.flatten
    // Only the names `<stream>-<seq>.bin` are used; the contents are never read.
    val pendingFloors = files.flatMap { f =>
      val n = f.getName.stripSuffix(".bin")
      val i = n.lastIndexOf('-')
      if i <= 0 then None else n.drop(i + 1).toLongOption.map(seq => n.take(i) -> (seq + 1))
    }
    AshidoriApp.db.discardLegacySync(run.reg, pendingFloors)
    // After the floors are committed: a crash before this only repeats it.
    deleteLegacyPending()

  private def jsonl(records: Seq[ujson.Value]): Array[Byte] =
    Crypto.gzip(records.map(ujson.write(_)).mkString("", "\n", "\n").getBytes(UTF_8))

  /** Seals `plain` for the stream's next seq of `run`'s registration (outside
    * any transaction), then stores it with `progress` in one small transaction.
    */
  private def enqueue(run: SyncRun, cls: String, stream: String, plain: Array[Byte], progress: Seq[(String, Long)],
                      remove: Seq[String] = Nil): Unit =
    val db = AshidoriApp.db
    val seq = db.nextSeq(run.reg, stream)
    val blob = Crypto.seal(run.batchKey, Crypto.aad(run.deviceId, stream, seq), plain)
    db.enqueueSealed(run.reg, run.deviceId, cls, stream, seq, blob, progress, remove)

  /** Upload the small streams. Returns the number of batches sent.
    * Editable data (places, check-ins, corrections) plus the archived
    * signal tables that are small (positions, motion, battery, GNSS status).
    * Logged signals are sent as the day's encrypted Parquet files once the
    * day is archived, not row by row.
    */
  def uploadSmall(run: SyncRun): Int =
    discardLegacy(run)
    var sent = flush(run, Small)
    for stream <- Seq("places", "checkins", "overrides") do
      val table = if stream == "overrides" then "mode_overrides" else stream
      sent += drain(run, stream, table)
    sent += uploadArchive(run, Seq(ashidori.core.Archive.Fixes, ashidori.core.Archive.Motion, ashidori.core.Archive.Battery,
      ashidori.core.Archive.GnssStatus), Small)
    // Last, so it only goes out once everything else made it.
    sent + uploadStatus(run)

  /** One `status` record per run: how this device is doing, for the others. */
  private def uploadStatus(run: SyncRun): Int =
    enqueue(run, Small, DeviceStatus.Stream, jsonl(Seq(StatusReport.current().toJson)), Nil)
    flush(run, Small)

  /** Raw GNSS / IMU archives: large, so only on unmetered networks. */
  def uploadRawLogs(run: SyncRun): Int =
    discardLegacy(run)
    flush(run, Raw) + uploadArchive(run, ashidori.core.Archive.RawTables, Raw)

  /** `fstat` of an open archive file: size, and device, inode and modification
    * time (ns) to tell its versions apart.
    */
  private def fstat(fd: java.io.FileDescriptor): FileStat =
    val st = android.system.Os.fstat(fd)
    FileStat(st.st_size, Seq(st.st_dev, st.st_ino, st.st_mtim.tv_sec, st.st_mtim.tv_nsec))

  /** Sends archived files not sent yet, as `archive` batches of ≤3 MiB parts.
    * A month file is skipped when every day it merged was already sent.
    *
    * Each file is opened once ([[ArchiveSnapshot]]): its size, its generation
    * and every part come from that one open descriptor, so a file replaced
    * meanwhile (by rename) does not mix versions. Each part is read, encoded
    * and sealed on its own, then committed with its seq and the progress
    * (`upgen:`, `uppart:`, `upbytes:`) in a small transaction, and sent. The
    * file counts as handed over (`up:`) only with its last part, and only if
    * the parts add up to the size of that generation. A run that stopped
    * part-way resumes at the recorded part if the file is still the same
    * generation; otherwise it starts over from part 0 with new seqs, and its
    * records carry the new `gen`.
    */
  private def uploadArchive(run: SyncRun, tables: Seq[ashidori.core.Archive.TableDef], cls: String): Int =
    val db = AshidoriApp.db
    var sent = 0
    def key(kind: String, t: ashidori.core.Archive.TableDef, name: String) = s"${run.reg}|$kind:${t.name}/$name"
    val files = for t <- tables; f <- AshidoriApp.archive.archivedFiles(t) yield (t, f)
    val it = files.iterator
    var full = false
    while it.hasNext && !full do
      val (t, f) = it.next()
      run.checkStopped()
      val name = f.getName
      val up = key("up", t, name)
      val (genKey, partKey, bytesKey) = (key("upgen", t, name), key("uppart", t, name), key("upbytes", t, name))
      val isMonth = name.length == "YYYY-MM.parquet".length
      val alreadyCovered =
        db.syncValue(up) != 0 ||
          (isMonth && {
            val month = java.time.YearMonth.parse(name.take(7))
            (1 to month.lengthOfMonth).forall(d => db.syncValue(key("up", t, s"${month.atDay(d)}.parquet")) != 0)
          })
      // Merged into a month file or deleted meanwhile: nothing to send.
      val snapshot =
        if alreadyCovered then None
        else
          try Some(ArchiveSnapshot.open(f, fstat))
          catch case _: java.io.FileNotFoundException => None
      snapshot.foreach { snap =>
        try
          val stored = Option.when(db.syncValue(genKey) != 0)(
            ArchiveUpload.Progress(db.syncValue(genKey), db.syncValue(partKey), db.syncValue(bytesKey)))
          var p = ArchiveUpload.resume(stored, snap.generation)
          var done = false
          while !done && !full do
            run.checkStopped()
            ArchiveUpload.nextPart(snap, p) match
              case None =>
                // Only an empty file ends here: other files end with their last part.
                if ArchiveUpload.complete(p, snap.size) then db.updateSync(Seq(up -> 1L), Seq(genKey, partKey, bytesKey))
                else throw IllegalStateException(s"${t.name}/$name: ${p.bytes} bytes enqueued of ${snap.size}")
                done = true
              case Some(_) if db.outboxBytes(run.reg, cls) > MaxOutboxBytes =>
                Log.w(s"outbox over ${MaxOutboxBytes >> 20} MiB: archive upload paused")
                full = true
              case Some(part) =>
                val plain = jsonl(Seq(ujson.Obj("file" -> s"${t.name}/$name", "gen" -> java.lang.Long.toHexString(snap.generation),
                  "part" -> ujson.Num(part.index.toDouble), "parts" -> ujson.Num(part.parts.toDouble),
                  "data" -> java.util.Base64.getEncoder.encodeToString(part.data))))
                // nextPart checked that the last part completes this generation.
                if part.last then enqueue(run, cls, "archive", plain, Seq(up -> 1L), Seq(genKey, partKey, bytesKey))
                else enqueue(run, cls, "archive", plain,
                  Seq(genKey -> snap.generation, partKey -> part.next.part, bytesKey -> part.next.bytes))
                p = part.next
                sent += flush(run, cls)
                done = part.last
        finally snap.close()
      }
    sent

  /** Seals changed rows of an editable table until it is drained, sending as it goes. */
  private def drain(run: SyncRun, stream: String, table: String): Int =
    val db = AshidoriApp.db
    val mark = s"${run.reg}|rev:$stream"
    var sent = 0
    var rows = db.rowsChangedAfter(table, db.syncValue(mark), MaxRows)
    while rows.nonEmpty do
      run.checkStopped()
      // Respect the plaintext size cap.
      var size = 0
      val batch = rows.takeWhile { (_, o) => size += ujson.write(o).length + 1; size <= MaxPlain }
      val use = if batch.isEmpty then rows.take(1) else batch
      enqueue(run, Small, stream, jsonl(use.map(_._2)), Seq(mark -> use.map(_._1).max))
      sent += flush(run, Small)
      rows = db.rowsChangedAfter(table, db.syncValue(mark), MaxRows)
    sent

  /** Sends the registration's outbox rows of class `cls`, oldest first. */
  private def flush(run: SyncRun, cls: String): Int =
    val db = AshidoriApp.db
    var sent = 0
    for e <- db.outbox(run.reg, Set(cls)) do
      run.checkStopped()
      if e.deviceId != run.deviceId then
        // Sealed for another device id: it would not decrypt. Never sent.
        Log.w(s"dropping outbox ${e.stream}/${e.seq} of another device")
        db.deleteOutbox(e.id)
      else
        run.client.putBatch(e.stream, e.seq, db.outboxBlob(e.id))
        db.deleteOutbox(e.id)
        sent += 1
    sent

/** How long a job waits for another sync run before it retries later. */
private val LockWaitMs = 3 * 60_000L

class SmallSyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    SyncLock.tryRun(LockWaitMs) {
      SyncRun.current(() => isStopped).fold(ListenableWorker.Result.success()) { run =>
        Try(Uploader.uploadSmall(run)).fold(
          e => {
            if !e.isInstanceOf[SyncRun.Stopped] then
              Log.w("sync failed", e)
              SyncConfig.lastError = e.getMessage
            SyncScheduler.onFailure(e)
          },
          _ => {
            SyncConfig.lastSync = System.currentTimeMillis()
            SyncConfig.lastError = ""
            // Other devices' status: best effort, never fails the upload.
            try PeerStatus.pull(run)
            catch case e: Exception => Log.w("peer status failed", e)
            ListenableWorker.Result.success()
          }
        )
      }
    }.getOrElse(ListenableWorker.Result.retry())

class RawLogSyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    SyncLock.tryRun(LockWaitMs) {
      SyncRun.current(() => isStopped).fold(ListenableWorker.Result.success()) { run =>
        Try(Uploader.uploadRawLogs(run)).fold(
          e => {
            if !e.isInstanceOf[SyncRun.Stopped] then Log.w("raw log sync failed", e)
            // Only a full quota is shown: other raw-log errors are transient,
            // and the small-stream job reports the connection state.
            if e.isInstanceOf[QuotaExceeded] then SyncConfig.lastError = e.getMessage
            SyncScheduler.onFailure(e)
          },
          _ => ListenableWorker.Result.success()
        )
      }
    }.getOrElse(ListenableWorker.Result.retry())

object SyncScheduler:
  private val SmallWork = "sync-small"
  private val RawWork = "sync-raw"
  val SyncNow = "sync-now"

  /** A full quota does not clear by retrying soon: end this run without
    * backoff retries and let the next periodic run try again.
    */
  def onFailure(e: Throwable): ListenableWorker.Result =
    if e.isInstanceOf[QuotaExceeded] then ListenableWorker.Result.failure() else ListenableWorker.Result.retry()

  /** Cancels every sync job and waits until WorkManager recorded it. A job
    * already running stops at its next batch; [[SyncLock]] waits for it.
    */
  def cancelAll(ctx: Context): Unit =
    val wm = WorkManager.getInstance(ctx)
    Seq(SmallWork, RawWork, SyncNow).map(wm.cancelUniqueWork).foreach(op => Try(op.getResult.get(30, TimeUnit.SECONDS)))

  def schedule(ctx: Context): Unit =
    val wm = WorkManager.getInstance(ctx)
    if !SyncConfig.ready then
      wm.cancelUniqueWork(SmallWork)
      wm.cancelUniqueWork(RawWork)
    else
      val small = Constraints.Builder()
        .setRequiredNetworkType(if SyncConfig.mobileData then NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()
      wm.enqueueUniquePeriodicWork(
        SmallWork,
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequest.Builder(classOf[SmallSyncWorker], 1, TimeUnit.HOURS)
          .setConstraints(small)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
          .build()
      )
      val raw = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(SyncConfig.rawOnlyWhileCharging)
        .setRequiresBatteryNotLow(true)
        .build()
      wm.enqueueUniquePeriodicWork(
        RawWork,
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequest.Builder(classOf[RawLogSyncWorker], 6, TimeUnit.HOURS)
          .setConstraints(raw)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
          .build()
      )

  /** Small streams now, on any network: the user asked for it. Raw-log rows
    * are never sent by this job.
    */
  def syncNow(ctx: Context): Unit =
    val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    WorkManager.getInstance(ctx).enqueueUniqueWork(
      SyncNow,
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequest.Builder(classOf[SmallSyncWorker]).setConstraints(c).build()
    )

  def deviceName: String = s"${Build.MANUFACTURER} ${Build.MODEL}"
