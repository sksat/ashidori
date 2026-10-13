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

/** Sync configuration. Secrets live in [[SecureStore]]. */
object SyncConfig:
  private def prefs = AshidoriApp.app.getSharedPreferences("sync", Context.MODE_PRIVATE)

  def serverUrl: String = prefs.getString("url", "")
  def deviceId: String = prefs.getString("deviceId", "")
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
    * disk; a failed write throws, and sync must then stay off.
    * Blocking: call off the main thread.
    */
  def confirmKey(k: Array[Byte], reg: String): Boolean = synchronized {
    if reg.isEmpty || reg != registration || masterKey.isDefined then false
    else
      SecureStore.put("master", k)
      true
  }

  /** This device created its user (self-registration), so no other device has a key yet. */
  def newAccount: Boolean = prefs.getBoolean("newAccount", false)

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
    if !prefs.edit().putString("url", url.stripSuffix("/")).putString("deviceId", deviceId)
        .putBoolean("newAccount", newAccount).commit()
    then throw java.io.IOException("登録情報を保存できませんでした")

  /** Unpairs this device. Blocking: call off the main thread.
    *
    * Cancels the sync jobs and waits for a running one to end, then clears
    * the registration, its secrets, all sync progress (seq counters,
    * watermarks, handed-over archive files), unsent batches and peer state.
    * A later registration therefore uploads everything afresh, sealed for
    * its own device id.
    */
  def forget(ctx: Context): Unit =
    SyncScheduler.cancelAll(ctx)
    SyncLock.run {
      prefs.edit().clear().commit()
      SecureStore.remove("token")
      SecureStore.remove("master")
      Registration.reset()
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
    if !SyncConfig.ready then None
    else Some(SyncRun(SyncConfig.registration, SyncConfig.deviceId, Crypto.batchKey(SyncConfig.masterKey.get),
      SyncClient(SyncConfig.serverUrl, SyncConfig.token), stopped))

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

/** Outcomes of registration attempts, delivered on the main thread. */
enum RegistrationEvent:
  /** The state changed; redraw. */
  case Changed
  /** The current attempt paired this device. */
  case Paired(newAccount: Boolean)
  /** The current attempt (or starting / cancelling one) failed. */
  case Failed(message: String)

/** Device registration with a client-generated token: an app-wide state
  * machine, independent of any screen instance.
  *
  * The token (32 random bytes, base64url) is stored, and checked to be on
  * disk, before the request is sent. A lost response is retried with the
  * same token and request; the server answers with the same device id. The
  * pending request is discarded only when the server definitely refused it
  * or the user cancelled it.
  *
  * Each send is an attempt with its own id. `current` holds the id of the
  * attempt in flight (0: none). Cancelling sets it to 0, so the cancelled
  * attempt's answer is dropped: the pairing is saved only if its attempt is
  * still current, checked under [[lock]] right before saving.
  *
  * Threads: [[lock]] guards the stored request and the pairing it turns
  * into. It is held only for local storage writes, never across network
  * I/O, and the main thread never takes it: the screen calls the
  * non-blocking [[start]], [[retry]] and [[cancel]] and observes [[state]].
  */
object Registration:
  private val Key = "pendingRegistration"
  private val lock = Object()
  private val ids = java.util.concurrent.atomic.AtomicLong(0)
  private val current = java.util.concurrent.atomic.AtomicLong(0)
  /** The stored request, cached once read. None: not read yet. */
  private val cached = java.util.concurrent.atomic.AtomicReference[Option[Option[PendingRegistration]]](None)
  /** Storage steps (start, cancel), in the order they were asked for. */
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

  // --- observers (main thread) ---

  private val listeners = scala.collection.mutable.LinkedHashSet.empty[RegistrationEvent => Unit]
  private lazy val main = android.os.Handler(android.os.Looper.getMainLooper)

  /** Main thread only. Returns the function that stops observing. */
  def observe(f: RegistrationEvent => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  private def emit(e: RegistrationEvent): Unit = main.post(() => listeners.toVector.foreach(_(e)))

  // --- storage (under lock) ---

  private def store(p: Option[PendingRegistration]): Unit =
    p match
      case Some(r) =>
        SecureStore.put(Key, ujson.write(ujson.Obj("url" -> r.url, "kind" -> r.kind, "code" -> r.code, "name" -> r.name,
          "token" -> r.token)).getBytes(UTF_8))
      case None => SecureStore.remove(Key)
    cached.set(Some(p))

  /** Claims a new attempt id, unless one is in flight. Non-blocking. */
  private def claim(): Option[Long] =
    val id = ids.incrementAndGet()
    if current.compareAndSet(0, id) then Some(id) else None

  /** Ends attempt `id` if it is still the current one. */
  private def release(id: Long): Unit =
    if current.compareAndSet(id, 0) then emit(RegistrationEvent.Changed)

  /** Stores a new request with a fresh token, checked to be on disk. */
  private def begin(id: Long, url: String, kind: String, code: String, name: String): PendingRegistration = lock.synchronized {
    if current.get != id then throw IllegalStateException("登録をやめました")
    if SyncConfig.paired || pending.isDefined then throw IllegalStateException("登録の処理がすでに進んでいます")
    val p = PendingRegistration(url.stripSuffix("/"), kind, code, name, Crypto.randomToken())
    store(Some(p))
    p
  }

  /** Sends `p`; network errors are retried a few times. Blocking, no lock held. */
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

  /** Takes in the answer to attempt `id`, unless it was cancelled meanwhile.
    * The check and the saving are one step under [[lock]]. Returns the
    * device id when this attempt paired the device.
    */
  private def finish(id: Long, p: PendingRegistration, answer: Try[String]): Option[String] =
    val paired = lock.synchronized {
      if current.get != id then None
      else
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
    }
    paired

  /** Runs attempt `id` of `p` on the network executor and reports the outcome. */
  private def run(id: Long, p: PendingRegistration): Unit =
    network.execute { () =>
      val outcome = Try(finish(id, p, Try(send(p))))
      release(id)
      outcome match
        case scala.util.Success(Some(_)) => emit(RegistrationEvent.Paired(p.kind == "account"))
        case scala.util.Success(None)    => () // cancelled meanwhile: dropped
        case scala.util.Failure(e)       => emit(RegistrationEvent.Failed(e.getMessage))
    }

  /** Starts a registration: stores the request, then sends it. Non-blocking. */
  def start(url: String, kind: String, code: String, name: String): Unit =
    claim() match
      case None => emit(RegistrationEvent.Failed("登録の処理がすでに進んでいます"))
      case Some(id) =>
        emit(RegistrationEvent.Changed)
        local.execute { () =>
          Try(begin(id, url, kind, code, name)) match
            case scala.util.Success(p) =>
              emit(RegistrationEvent.Changed)
              run(id, p)
            case scala.util.Failure(e) =>
              release(id)
              emit(RegistrationEvent.Failed(e.getMessage))
        }

  /** Sends the stored request again, with the same token. Non-blocking. */
  def retry(): Unit =
    claim() match
      case None => emit(RegistrationEvent.Failed("登録の処理がすでに進んでいます"))
      case Some(id) =>
        emit(RegistrationEvent.Changed)
        local.execute { () =>
          pending match
            case Some(p) => run(id, p)
            case None =>
              release(id)
              emit(RegistrationEvent.Failed("登録の処理がありません"))
        }

  /** Gives up the request. The attempt in flight, if any, is invalidated at
    * once: its answer is dropped. Non-blocking.
    */
  def cancel(): Unit =
    current.set(0)
    emit(RegistrationEvent.Changed)
    local.execute { () =>
      Try(lock.synchronized(store(None))).failed.foreach(e => emit(RegistrationEvent.Failed(e.getMessage)))
      emit(RegistrationEvent.Changed)
    }

  /** Forgets any request and invalidates any attempt (unpairing). Blocking. */
  def reset(): Unit =
    current.set(0)
    lock.synchronized(store(None))
    emit(RegistrationEvent.Changed)

  /** Registers on the calling thread and returns the device id (debug hook). Blocking. */
  def registerBlocking(url: String, kind: String, code: String, name: String): String =
    val id = claim().getOrElse(throw IllegalStateException("登録の処理がすでに進んでいます"))
    try
      val p = begin(id, url, kind, code, name)
      finish(id, p, Try(send(p))).getOrElse(throw IllegalStateException("登録をやめました"))
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
  /** Archive files are sent in parts of this size, one batch each. */
  private val ArchivePart = 3 * 1024 * 1024
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

  /** Identifies one version of an archive file by size and modification time
    * (files are replaced by rename, never rewritten in place).
    */
  private def generation(f: File): Long =
    val h = java.security.MessageDigest.getInstance("SHA-256").digest(s"${f.length}/${f.lastModified}".getBytes(UTF_8))
    java.nio.ByteBuffer.wrap(h, 0, 8).getLong & Long.MaxValue

  /** Reads part `i` of `f` (at most [[ArchivePart]] bytes) without reading the rest. */
  private def readPart(f: File, i: Long, size: Long): Array[Byte] =
    Using.resource(java.io.RandomAccessFile(f, "r")) { raf =>
      val off = i * ArchivePart
      val buf = new Array[Byte](math.min(ArchivePart.toLong, size - off).toInt)
      raf.seek(off)
      raf.readFully(buf)
      buf
    }

  /** Sends archived files not sent yet, as `archive` batches of ≤3 MiB parts.
    * A month file is skipped when every day it merged was already sent.
    *
    * Each part is read, encoded and sealed on its own, then committed with
    * its seq and the file's next part (`uppart:`) in a small transaction, and
    * sent. The file counts as handed over (`up:`) only with its last part. A
    * run that stopped part-way resumes at the recorded part, if the file is
    * still the same generation (`upgen:`); a file that changed meanwhile
    * starts over from part 0 with new seqs, and its records carry the new
    * `gen`.
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
      val (up, genKey, partKey) = (key("up", t, name), key("upgen", t, name), key("uppart", t, name))
      val isMonth = name.length == "YYYY-MM.parquet".length
      val alreadyCovered =
        db.syncValue(up) != 0 ||
          (isMonth && {
            val month = java.time.YearMonth.parse(name.take(7))
            (1 to month.lengthOfMonth).forall(d => db.syncValue(key("up", t, s"${month.atDay(d)}.parquet")) != 0)
          })
      var done = alreadyCovered
      while !done && !full do
        run.checkStopped()
        val size = f.length
        val gen = generation(f)
        val parts = (size + ArchivePart - 1) / ArchivePart
        // Resume only within the same generation of the file.
        val part = if db.syncValue(genKey) == gen then db.syncValue(partKey) else 0L
        if !f.exists then done = true // merged into a month file or deleted meanwhile
        else if part >= parts then
          db.updateSync(Seq(up -> 1L), Seq(genKey, partKey))
          done = true
        else if db.outboxBytes(run.reg, cls) > MaxOutboxBytes then
          Log.w(s"outbox over ${MaxOutboxBytes >> 20} MiB: archive upload paused")
          full = true
        else
          val chunk = readPart(f, part, size)
          // Changed while reading: start over with the new generation.
          if generation(f) == gen then
            val last = part + 1 == parts
            val plain = jsonl(Seq(ujson.Obj("file" -> s"${t.name}/$name", "gen" -> java.lang.Long.toHexString(gen),
              "part" -> ujson.Num(part.toDouble), "parts" -> ujson.Num(parts.toDouble),
              "data" -> java.util.Base64.getEncoder.encodeToString(chunk))))
            if last then enqueue(run, cls, "archive", plain, Seq(up -> 1L), Seq(genKey, partKey))
            else enqueue(run, cls, "archive", plain, Seq(genKey -> gen, partKey -> (part + 1)))
            sent += flush(run, cls)
            done = last
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
