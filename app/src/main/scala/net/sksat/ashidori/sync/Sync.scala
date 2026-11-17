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
  * lose. The key counts only for the registration it was set for, and an
  * unpairing cut short is finished at start ([[PairingStore]]).
  *
  * The other settings here (network, charging, last result) need no such
  * guarantee and stay in SharedPreferences ("sync"), which holds nothing else.
  */
object SyncConfig:
  private val durable = DurablePrefs("sync", legacy = Some(Set(PairingStore.Url, PairingStore.DeviceId, PairingStore.NewAccount)))
  private lazy val store = PairingStore(durable.store, SecureStore)
  private def prefs = AshidoriApp.app.getSharedPreferences("sync", Context.MODE_PRIVATE)

  def serverUrl: String = store.serverUrl
  def deviceId: String = store.deviceId
  def mobileData: Boolean = prefs.getBoolean("mobileData", true)
  def mobileData_=(b: Boolean): Unit = prefs.edit().putBoolean("mobileData", b).apply()
  def rawOnlyWhileCharging: Boolean = prefs.getBoolean("rawCharging", false)
  def rawOnlyWhileCharging_=(b: Boolean): Unit = prefs.edit().putBoolean("rawCharging", b).apply()
  def lastSync: Long = prefs.getLong("lastSync", 0L)
  def lastSync_=(t: Long): Unit = prefs.edit().putLong("lastSync", t).apply()
  def lastError: String = prefs.getString("lastError", "")
  def lastError_=(s: String): Unit = prefs.edit().putString("lastError", s).apply()
  /** The user's username as last seen, for display only (empty: unknown, or none yet). */
  def username: String = prefs.getString("username", "")
  def username_=(s: String): Unit = prefs.edit().putString("username", s).apply()

  def token: Option[String] = store.token
  def masterKey: Option[Array[Byte]] = store.masterKey
  /** The registration, token and key from one read (see [[PairingStore.session]]); None unless ready. */
  def session: Option[PairingStore.Session] = store.session

  /** Sets the key of the registration `reg`, once (see [[PairingStore.confirmKey]]).
    * Blocking: call off the main thread.
    */
  def confirmKey(k: Array[Byte], reg: String): Boolean = store.confirmKey(k, reg)

  /** This device created its user (self-registration), so no other device has a key yet. */
  def newAccount: Boolean = store.newAccount

  def paired: Boolean = store.paired
  def ready: Boolean = store.ready
  /** An unpairing is under way (or was cut short and not finished yet). */
  def unpairing: Boolean = store.unpairing

  /** Identifies the registration (server URL and device id). Sync progress
    * and peer state are keyed by it, so state of an earlier registration is
    * never used for a later one. Empty when not registered.
    */
  def registration: String = store.registration

  /** Stores the registration, each part checked to be on disk; throws
    * [[java.io.IOException]] otherwise. Blocking: call off the main thread.
    */
  def savePairing(url: String, deviceId: String, token: String, newAccount: Boolean = false): Unit =
    try store.savePairing(url, deviceId, token, newAccount)
    catch case e: java.io.IOException => throw java.io.IOException("登録情報を保存できませんでした", e)

  /** Unpairs this device. Blocking: call off the main thread.
    *
    * Marks the unpairing on disk first ([[PairingStore.forget]]); from then
    * on the device counts as unpaired. Then invalidates any registration
    * attempt (none of its results is saved afterwards), cancels the sync
    * jobs and waits for a running one to end, then clears the registration,
    * its secrets, all sync progress (seq counters, watermarks, handed-over
    * archive files), unsent batches, peer state and the sync settings, and
    * finally the mark. A later registration therefore uploads everything
    * afresh, sealed for its own device id. A crash in between is finished
    * by [[resumeUnpair]] at the next start.
    */
  def forget(ctx: Context): Unit = store.forget(() => invalidate(ctx), f => SyncLock.run(f()), () => clearRest())

  /** Finishes an unpairing a crash cut short, and binds a key stored by an
    * earlier version to its registration. Call once at start, off the main thread.
    */
  def resumeUnpair(ctx: Context): Unit =
    try
      if store.resume(() => invalidate(ctx), f => SyncLock.run(f()), () => clearRest()) then Log.i("finished an interrupted unpairing")
      store.adoptLegacyKey()
    catch case e: Exception => Log.w("finishing the unpairing failed", e)

  private def invalidate(ctx: Context): Unit =
    Registration.reset()
    SyncScheduler.cancelAll(ctx)

  private def clearRest(): Unit =
    SecureStore.remove("pendingRegistration")
    AshidoriApp.db.clearSync()
    Uploader.deleteLegacyPending()
    PeerStatus.clear()
    AuthCutover.clear()
    if !prefs.edit().clear().commit() then throw java.io.IOException("同期の設定を消去できませんでした")

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

/** A sync run's fixed view of the registration, taken under [[SyncLock]].
  * `keys`: the batch key and the blob authentication key `Ka` (v1.1).
  */
final case class SyncRun(reg: String, deviceId: String, keys: BlobKeys, client: SyncClient, stopped: () => Boolean):
  def batchKey: Array[Byte] = keys.batch
  def checkStopped(): Unit = if stopped() then throw SyncRun.Stopped()

object SyncRun:
  final class Stopped extends RuntimeException("stopped")

  /** None when sync is not set up (or the key is not confirmed yet). The
    * registration is read once ([[PairingStore.session]]): the key is checked
    * against the same registration whose URL and device id the run uses and
    * under which it keeps its progress, even if an unpairing marks itself
    * meanwhile (it does so outside [[SyncLock]]).
    */
  def current(stopped: () => Boolean = () => false): Option[SyncRun] =
    SyncConfig.session.map(s =>
      SyncRun(s.reg, s.deviceId, BlobKeys.of(s.key), SyncClient(s.url, Some(s.token)), stopped))

/** Device registration ([[RegistrationMachine]]): the request and token in
  * [[SecureStore]], the result in [[SyncConfig]], events on the main thread.
  * The main thread never blocks: the screen calls [[start]], [[retry]] and
  * [[cancel]] and observes [[state]].
  */
object Registration:
  private val Key = "pendingRegistration"

  private object storage extends RegistrationStorage:
    def load(): Option[PendingRegistration] = SecureStore.get(Key).flatMap(PendingRegistration.fromBytes)
    def store(p: Option[PendingRegistration]): Unit = p match
      case Some(r) => SecureStore.put(Key, r.toBytes)
      case None    => SecureStore.remove(Key)
    def busy: Boolean = SyncConfig.paired || SyncConfig.unpairing
    def savePairing(p: PendingRegistration, deviceId: String): Unit =
      SyncConfig.savePairing(p.url, deviceId, p.token, newAccount = p.kind == "account")

  /** Tokens made in this process and not sent yet. */
  private val unsent = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  private def newToken(): String =
    val t = Crypto.randomToken()
    unsent.add(t)
    t

  /** How long a self-registration refused with 403 is sent again when an
    * earlier copy of it may still be on its way (see [[send]]).
    */
  private val RefusedRetryNanos = TimeUnit.MINUTES.toNanos(2)

  /** Sends `p`; network errors are retried a few times. Blocking, on the network executor.
    *
    * A 403 to `POST /v1/accounts` (only without an invite: the server reads
    * `REGISTRATION` only then) is final only for the first request with a
    * token made in this process. Otherwise an earlier copy of the request
    * (sent before a lost answer, a crash or a retry) may still be on its way
    * and create the account after the server refused this one (registration
    * closed meanwhile, or the last place taken); the server answers a later
    * copy with that account (200, result recovery comes first). So the
    * request is sent again with backoff for [[RefusedRetryNanos]] before the
    * refusal counts.
    */
  private def send(p: PendingRegistration): String =
    val c = SyncClient(p.url, None)
    val deadline = System.nanoTime() + RefusedRetryNanos
    val invite = Option(p.code).filter(_.nonEmpty)
    def attempt(n: Int, netErrors: Int, copies: Boolean): String =
      try
        if p.kind == "account" then c.createAccount(p.name, p.token, p.username, invite)._1
        else c.register(p.code, p.name, p.token)
      catch
        case e: java.io.IOException if netErrors < 2 =>
          Log.w(s"registration attempt $n failed", e)
          Thread.sleep(1000L * (netErrors + 1) * (netErrors + 1))
          attempt(n + 1, netErrors + 1, copies = true)
        case e: SyncClient.AccountRefused if copies && System.nanoTime() < deadline =>
          Log.w(s"registration attempt $n refused (403); an earlier copy may still create the account", e)
          Thread.sleep(math.min(2000L << math.min(n - 1, 4), 30_000L))
          attempt(n + 1, netErrors, copies)
        case e: SyncClient.AccountRefused => throw RegistrationRejected(e.getMessage)
    attempt(1, 0, copies = !unsent.remove(p.token))

  private val machine = RegistrationMachine(
    storage, send, () => newToken(), emit,
    java.util.concurrent.Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-registration")),
    // One thread per attempt, so a stuck one blocks nothing else.
    java.util.concurrent.Executors.newCachedThreadPool(r => Thread(r, "ashidori-registration-net"))
  )

  def pending: Option[PendingRegistration] = machine.pending
  def state: RegistrationState = machine.state
  def isLatest(attempt: Long): Boolean = machine.isLatest(attempt)

  // --- observers (main thread) ---

  private val listeners = scala.collection.mutable.LinkedHashSet.empty[RegistrationEvent => Unit]
  private lazy val main = android.os.Handler(android.os.Looper.getMainLooper)

  /** Main thread only. Returns the function that stops observing. */
  def observe(f: RegistrationEvent => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  private def emit(e: RegistrationEvent): Unit = main.post(() => listeners.toVector.foreach(_(e)))

  /** `kind` "account" (a new user; `code` the invite or empty, `username` chosen) or "pair" (`code` the pairing code). */
  def start(url: String, kind: String, code: String, name: String, username: String = ""): Unit =
    machine.start(url, kind, code, name, username)
  def retry(): Unit = machine.retry()
  /** False, changing nothing, when it came too late (see [[RegistrationMachine.cancel]]). */
  def cancel(): Boolean = machine.cancel()
  def reset(): Unit = machine.reset()
  /** Registers on the calling thread and returns the device id (debug hook). Blocking. */
  def registerBlocking(url: String, kind: String, code: String, name: String): String =
    machine.registerBlocking(url, kind, code, name)


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

  /** Creates a user named `username` with this device as its first device,
    * with the client-chosen `token`: with `invite` on any server, without
    * one only where registration is open. Idempotent like [[register]].
    * Returns the device id and the user's username (as the server has it
    * now; None from a server before usernames). A request stored by an older
    * version has no username; it is sent without one, and the server
    * recovers it by the token.
    *
    * A 409 for the username throws [[UsernameTakenError]], a 401 (the invite
    * is unknown, used, expired or revoked) [[RegistrationRejected]]. A 403
    * (registration closed or full) throws [[SyncClient.AccountRefused]]:
    * final only if no other copy of the request may reach the server later
    * (see `Registration.send`).
    */
  def createAccount(deviceName: String, token: String, username: String, invite: Option[String]): (String, Option[String]) =
    val body = ujson.Obj("deviceName" -> deviceName, "token" -> token)
    if username.nonEmpty then body("username") = username
    invite.foreach(body("invite") = _)
    val r = request("POST", "/v1/accounts", ujson.write(body).getBytes(UTF_8))
    def error = Try(r.json("error").str).getOrElse("")
    r.code match
      case 200 | 201 => (r.json("deviceId").str, r.json.obj.get("username").flatMap(_.strOpt))
      case 401 if invite.isDefined => throw RegistrationRejected("招待が無効です。期限切れ、使用済み、または取り消された可能性があります")
      case 409 if error == "username taken" => throw UsernameTakenError(username)
      case 403 => throw SyncClient.AccountRefused("このサーバでは招待なしの登録を受け付けていません")
      case 429 => throw RuntimeException("登録の試行回数が上限に達しました。時間をおいて再度お試しください")
      case c if refused(c) => throw RegistrationRejected(s"登録に失敗しました ($c): ${r.text.take(200)}")
      case c => throw RuntimeException(s"登録に失敗しました ($c): ${r.text.take(200)}")

  /** This device's user: `(registered with a user, its username)`. A legacy
    * device has no user; a legacy user may have no username yet.
    */
  def me(): (Boolean, Option[String]) =
    val r = request("GET", "/v1/me")
    if r.code != 200 then throw RuntimeException(s"HTTP ${r.code}")
    val js = r.json
    (js.obj.get("userId").exists(!_.isNull), js.obj.get("username").flatMap(_.strOpt))

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
  /** `POST /v1/accounts` answered 403: registration closed, or the server full. */
  final class AccountRefused(msg: String) extends RuntimeException(msg)

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

  /** Seals `plain` as ASHM (v1.1: with the MAC under `Ka`) for the stream's
    * next seq of `run`'s registration (outside any transaction), then stores
    * it with `progress` in one small transaction. Returns the seq and blob.
    */
  private def enqueue(run: SyncRun, cls: String, stream: String, plain: Array[Byte], progress: Seq[(String, Long)],
                      remove: Seq[String] = Nil): (Long, Array[Byte]) =
    val db = AshidoriApp.db
    val seq = db.nextSeq(run.reg, stream)
    val blob = Crypto.seal(run.keys, Crypto.aad(run.deviceId, stream, seq), plain)
    db.enqueueSealed(run.reg, run.deviceId, cls, stream, seq, blob, progress, remove)
    (seq, blob)

  /** Stores the cut-over record C (gzipped plaintext) for upload, as the next
    * `authcut` batch of this device. On disk when it returns; sent by
    * [[flushCutover]] (or any later small-stream run).
    */
  def enqueueCutover(run: SyncRun, plain: Array[Byte]): CutRef =
    val (seq, blob) = enqueue(run, Small, CutoverRecord.Stream, plain, Nil)
    CutRef(run.deviceId, seq, Crypto.sha256Hex(blob))

  /** Sends the unsent `authcut` batches of the outbox, and nothing else (the
    * user may be on a network the other batches wait out). Throws on
    * failure; true when none is left.
    */
  def flushCutover(run: SyncRun): Boolean =
    val db = AshidoriApp.db
    for e <- db.outbox(run.reg, Set(Small)) if e.stream == CutoverRecord.Stream && e.deviceId == run.deviceId do
      run.checkStopped()
      run.client.putBatch(e.stream, e.seq, db.outboxBlob(e.id))
      db.deleteOutbox(e.id)
    !db.outbox(run.reg, Set(Small)).exists(e => e.stream == CutoverRecord.Stream && e.deviceId == run.deviceId)

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
    sent += uploadArchive(run, ashidori.core.Archive.UploadedSignalTables, Small)
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
    * A file is sent again when it changed since it was handed over (marked
    * with its generation; earlier versions' marks are of no generation, so
    * such files are sent once more); a day still being archived waits until
    * done.
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
      val mark = db.syncValue(up)
      // A day still being archived (rows left in SQLite) is sent once it is
      // complete; a file handed over is sent again only when it changed.
      val inProgress = !isMonth && scala.util.Try(java.time.LocalDate.parse(name.take(10))).toOption.exists(d =>
        !net.sksat.ashidori.Maintenance.Complete.is(d) &&
          AshidoriApp.db.hasHotRows(AshidoriApp.archive.startOfDay(d), AshidoriApp.archive.startOfDay(d.plusDays(1))))
      // Merged into a month file or deleted meanwhile: nothing to send.
      val snapshot =
        if inProgress then None
        else
          try Some(ArchiveSnapshot.open(f, fstat)).filter { snap =>
            val fresh = !ArchiveUpload.handedOver(mark, snap.generation)
            if !fresh then snap.close()
            fresh
          }
          catch case _: java.io.FileNotFoundException => None
      snapshot.foreach { snap =>
        val handed = up -> ArchiveUpload.markOf(snap.generation)
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
                if ArchiveUpload.complete(p, snap.size) then db.updateSync(Seq(handed), Seq(genKey, partKey, bytesKey))
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
                if part.last then enqueue(run, cls, "archive", plain, Seq(handed), Seq(genKey, partKey, bytesKey))
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
    // After repeated crashes only a sync the user asked for runs.
    if net.sksat.ashidori.Maintenance.SafeMode.syncStopped && !getTags.contains(SyncScheduler.ManualTag) then
      return ListenableWorker.Result.success()
    SyncLock.tryRun(LockWaitMs) {
      SyncRun.current(() => isStopped).fold(ListenableWorker.Result.success()) { run =>
        val up = Try(Uploader.uploadSmall(run))
        // Other devices' status, whatever became of the upload (a full quota
        // does not stop it): best effort, never fails the job, and still
        // under the sync lock. Only a stopped job skips it.
        if !up.failed.toOption.exists(_.isInstanceOf[SyncRun.Stopped]) then
          try PeerStatus.pull(run)
          catch
            case _: SyncRun.Stopped => ()
            case e: Exception => Log.w("peer status failed", e)
        up.fold(
          e => {
            if !e.isInstanceOf[SyncRun.Stopped] then
              Log.w("sync failed", e)
              SyncConfig.lastError = e.getMessage
            SyncScheduler.onFailure(e)
          },
          _ => {
            SyncConfig.lastSync = System.currentTimeMillis()
            SyncConfig.lastError = ""
            ListenableWorker.Result.success()
          }
        )
      }
    }.getOrElse(ListenableWorker.Result.retry())

class RawLogSyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    if net.sksat.ashidori.Maintenance.SafeMode.syncStopped then return ListenableWorker.Result.success()
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
  /** Tag of a sync the user asked for (runs also after repeated crashes). */
  val ManualTag = "manual"

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
      OneTimeWorkRequest.Builder(classOf[SmallSyncWorker]).setConstraints(c).addTag(ManualTag).build()
    )

  def deviceName: String = s"${Build.MANUFACTURER} ${Build.MODEL}"
