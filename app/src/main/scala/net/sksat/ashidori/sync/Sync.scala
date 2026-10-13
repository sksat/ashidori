package net.sksat.ashidori.sync

import android.content.Context
import android.os.Build
import androidx.work.*
import java.io.File
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
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
  def setMasterKey(k: Array[Byte]): Unit = SecureStore.put("master", k)

  def paired: Boolean = serverUrl.nonEmpty && deviceId.nonEmpty && token.isDefined
  def ready: Boolean = paired && masterKey.isDefined

  def savePairing(url: String, deviceId: String, token: String): Unit =
    prefs.edit().putString("url", url.stripSuffix("/")).putString("deviceId", deviceId).apply()
    SecureStore.put("token", token.getBytes(UTF_8))

  def forget(): Unit =
    prefs.edit().clear().apply()
    SecureStore.remove("token")
    SecureStore.remove("master")
    PeerStatus.clear()

/** Minimal HTTP client for the ashidori server. Blocking; call off the main thread. */
final class SyncClient(base: String, token: Option[String]):
  final case class Response(code: Int, body: Array[Byte]):
    def text: String = String(body, UTF_8)
    def json: ujson.Value = ujson.read(text)

  def request(method: String, path: String, body: Array[Byte] = null, contentType: String = "application/json",
              headers: Map[String, String] = Map.empty): Response =
    val c = URL(base + path).openConnection().asInstanceOf[HttpURLConnection]
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
    val in = if code >= 400 then c.getErrorStream else c.getInputStream
    val bytes = if in == null then Array.emptyByteArray else Using.resource(in)(_.readAllBytes())
    Response(code, bytes)

  def register(code: String, name: String): (String, String) =
    val r = request("POST", "/v1/devices/register", ujson.write(ujson.Obj("code" -> code, "name" -> name)).getBytes(UTF_8))
    if r.code != 200 && r.code != 201 then throw RuntimeException(s"登録に失敗しました (${r.code}): ${r.text}")
    (r.json("deviceId").str, r.json("token").str)

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
      case c => throw RuntimeException(s"upload $stream/$seq failed: HTTP $c ${r.text.take(200)}")

  /** One page of the user's batch metadata after `cursor`, and the cursor to
    * continue from (None at the end).
    */
  def listBatches(after: Long, limit: Int): (Vector[BatchMeta], Option[Long]) =
    val r = request("GET", s"/v1/batches?after=$after&limit=$limit")
    if r.code != 200 then throw RuntimeException(s"list batches failed: HTTP ${r.code}")
    val js = r.json
    (js("items").arr.map(BatchMeta.fromJson).toVector, js.obj.get("next").flatMap(_.numOpt).map(_.toLong))

  /** The blob of another device's batch, as stored (still encrypted). */
  def getBatch(deviceId: String, stream: String, seq: Long): Array[Byte] =
    val r = request("GET", s"/v1/batches/$deviceId/$stream/$seq")
    if r.code != 200 then throw RuntimeException(s"fetch $deviceId/$stream/$seq failed: HTTP ${r.code}")
    r.body

  /** The user's devices: `[{id, name, createdAt, lastSeenAt, revoked}]`. */
  def devices(): ujson.Value =
    val r = request("GET", "/v1/devices")
    if r.code != 200 then throw RuntimeException(s"HTTP ${r.code}")
    r.json

/** Builds and uploads encrypted batches. */
object Uploader:
  private val MaxRows = 5000
  private val MaxPlain = 4 * 1024 * 1024

  private def pendingDir: File =
    val d = File(AshidoriApp.app.getFilesDir, "sync-pending")
    d.mkdirs()
    d

  private def client: SyncClient = SyncClient(SyncConfig.serverUrl, SyncConfig.token)

  /** Upload the small streams. Returns the number of batches sent. */
  /** Editable data (places, check-ins, corrections) plus the archived
    * signal tables that are small (positions, motion, battery, GNSS status).
    * Logged signals are sent as the day's encrypted Parquet files once the
    * day is archived, not row by row.
    */
  def uploadSmall(): Int =
    val db = AshidoriApp.db
    var sent = 0
    for stream <- Seq("places", "checkins", "overrides") do
      val table = if stream == "overrides" then "mode_overrides" else stream
      sent += drain(stream, () => db.rowsUpdatedAfter(table, db.syncValue(s"updated:$stream"), MaxRows), rows =>
        db.setSyncValue(s"updated:$stream", rows.map(_._2("updatedAt").num.toLong).max))
    sent += uploadArchive(Seq(ashidori.core.Archive.Fixes, ashidori.core.Archive.Motion, ashidori.core.Archive.Battery, ashidori.core.Archive.GnssStatus))
    // Last, so it only goes out once everything else made it.
    sent + uploadStatus()

  /** One `status` record per run: how this device is doing, for the others. */
  private def uploadStatus(): Int =
    val sent = resumePending(DeviceStatus.Stream)
    val f = prepare(DeviceStatus.Stream, Vector(StatusReport.current().toJson))
    send(DeviceStatus.Stream, f)
    sent + 1

  /** Raw GNSS / IMU archives: large, so only on unmetered networks. */
  def uploadRawLogs(): Int = uploadArchive(ashidori.core.Archive.RawTables)

  /** Sends archived files not sent yet, as `archive` batches of ≤3 MiB chunks.
    * A month file is skipped when every day it merged was already sent.
    */
  private def uploadArchive(tables: Seq[ashidori.core.Archive.TableDef]): Int =
    val db = AshidoriApp.db
    var sent = resumePending("archive")
    def key(t: ashidori.core.Archive.TableDef, name: String) = s"up:${t.name}/$name"
    for t <- tables; f <- AshidoriApp.archive.archivedFiles(t) do
      val name = f.getName
      val isMonth = name.length == "YYYY-MM.parquet".length
      val alreadyCovered =
        db.syncValue(key(t, name)) != 0 ||
          (isMonth && {
            val month = java.time.YearMonth.parse(name.take(7))
            (1 to month.lengthOfMonth).forall(d => db.syncValue(key(t, s"${month.atDay(d)}.parquet")) != 0)
          })
      if !alreadyCovered then
        val bytes = Files.readAllBytes(f.toPath)
        val chunks = bytes.grouped(3 * 1024 * 1024).toVector
        val pending = chunks.zipWithIndex.map { (chunk, i) =>
          prepare("archive", Vector(ujson.Obj("file" -> s"${t.name}/$name", "part" -> i, "parts" -> chunks.size,
            "data" -> java.util.Base64.getEncoder.encodeToString(chunk))))
        }
        // Sealed to disk: the file now counts as handed over.
        db.setSyncValue(key(t, name), 1)
        pending.foreach { p =>
          send("archive", p)
          sent += 1
        }
    sent

  /** Repeatedly fetch rows and upload them until the stream is drained. */
  private def drain(stream: String, fetch: () => Vector[(Long, ujson.Obj)], commit: Vector[(Long, ujson.Obj)] => Unit): Int =
    var sent = resumePending(stream)
    var rows = fetch()
    while rows.nonEmpty do
      // Respect the plaintext size cap.
      var size = 0
      val batch = rows.takeWhile { (_, o) => size += ujson.write(o).length + 1; size <= MaxPlain }
      val use = if batch.isEmpty then rows.take(1) else batch
      val pending = prepare(stream, use.map(_._2))
      // The sealed file now owns these rows: advance the cursor before
      // sending, so a failed upload is retried from the file, never re-read.
      commit(use)
      send(stream, pending)
      sent += 1
      rows = fetch()
    sent

  /** Seals a batch into a pending file and claims the next sequence number.
    * Retries re-send these exact bytes, which keeps PUT idempotent.
    */
  private def prepare(stream: String, records: Vector[ujson.Value]): File =
    val db = AshidoriApp.db
    val seq = db.syncValue(s"seq:$stream")
    val plain = Crypto.gzip(records.map(ujson.write(_)).mkString("", "\n", "\n").getBytes(UTF_8))
    val key = Crypto.batchKey(SyncConfig.masterKey.get)
    val blob = Crypto.seal(key, Crypto.aad(SyncConfig.deviceId, stream, seq), plain)
    val f = File(pendingDir, s"$stream-$seq.bin")
    Files.write(f.toPath, blob)
    db.setSyncValue(s"seq:$stream", seq + 1)
    f

  private def send(stream: String, f: File): Unit =
    val seq = f.getName.stripPrefix(s"$stream-").stripSuffix(".bin").toLong
    client.putBatch(stream, seq, Files.readAllBytes(f.toPath))
    f.delete()

  private def resumePending(stream: String): Int =
    val files = Option(pendingDir.listFiles()).toSeq.flatten.filter(_.getName.startsWith(s"$stream-"))
    files.foreach(send(stream, _))
    files.size

class SmallSyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    if !SyncConfig.ready then ListenableWorker.Result.success()
    else
      Try(Uploader.uploadSmall()).fold(
        e => {
          Log.w("sync failed", e)
          SyncConfig.lastError = e.getMessage
          ListenableWorker.Result.retry()
        },
        n => {
          SyncConfig.lastSync = System.currentTimeMillis()
          SyncConfig.lastError = ""
          // Other devices' status: best effort, never fails the upload.
          try PeerStatus.pull()
          catch case e: Exception => Log.w("peer status failed", e)
          ListenableWorker.Result.success()
        }
      )

class RawLogSyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    if !SyncConfig.ready then ListenableWorker.Result.success()
    else
      Try(Uploader.uploadRawLogs()).fold(
        e => {
          Log.w("raw log sync failed", e)
          ListenableWorker.Result.retry()
        },
        _ => ListenableWorker.Result.success()
      )

object SyncScheduler:
  def schedule(ctx: Context): Unit =
    val wm = WorkManager.getInstance(ctx)
    if !SyncConfig.ready then
      wm.cancelUniqueWork("sync-small")
      wm.cancelUniqueWork("sync-raw")
    else
      val small = Constraints.Builder()
        .setRequiredNetworkType(if SyncConfig.mobileData then NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()
      wm.enqueueUniquePeriodicWork(
        "sync-small",
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
        "sync-raw",
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequest.Builder(classOf[RawLogSyncWorker], 6, TimeUnit.HOURS)
          .setConstraints(raw)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
          .build()
      )

  val SyncNow = "sync-now"

  def syncNow(ctx: Context): Unit =
    val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    WorkManager.getInstance(ctx).enqueueUniqueWork(
      SyncNow,
      ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequest.Builder(classOf[SmallSyncWorker]).setConstraints(c).build()
    )

  def deviceName: String = s"${Build.MANUFACTURER} ${Build.MODEL}"
