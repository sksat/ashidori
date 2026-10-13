package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.io.{File, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.util.concurrent.TimeUnit
import scala.util.Try
import ashidori.core.*

/** State of the offline basemap for the UI. */
enum BasemapStatus:
  /** This build pins no archive. */
  case Unavailable
  case Missing
  /** Queued; waits for an unmetered network unless `metered` is allowed. */
  case Waiting(metered: Boolean)
  case Running(done: Long, total: Long, source: BasemapSource)
  case Installed(version: String, source: Option[BasemapSource])
  /** `retrying`: WorkManager runs it again later by itself. */
  case Failed(message: String, retrying: Boolean)

/** Downloads the pinned basemap archive once, in the background
  * ([[BasemapWorker]]).
  *
  * The archive covers whole countries, so fetching it reveals nothing about
  * where the user is: it is not one of the automatic location sends that
  * 「位置情報を外部サービスに送らない」 stops ([[SendGate]]), and it runs
  * whatever that setting is. It goes to GitHub Releases, or to the paired
  * sync server when that has the pinned version, without the device token;
  * the bytes are accepted only with the pinned size and sha256.
  *
  * By default it waits for an unmetered network; on a metered one it runs
  * only after the user confirms ([[start]] with `allowMetered`).
  */
object BasemapDownload:
  val WorkName = "basemap-download"
  /** Runs before the error is shown as final; later runs only by request or at the next start. */
  val MaxAttempts = 4

  private def prefs = AshidoriApp.app.getSharedPreferences("basemap", Context.MODE_PRIVATE)

  def debuggable: Boolean =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Debug builds only: a directory URL holding the archive, instead of the
    * pinned sources (`am start ... --es ashidori.basemapUrl http://...`).
    * Release builds ignore it.
    */
  def debugUrl: Option[String] = Option(prefs.getString("debugUrl", "")).filter(_.nonEmpty).filter(_ => debuggable)
  def debugUrl_=(u: String): Unit = prefs.edit().putString("debugUrl", u.trim).apply()

  /** Where the installed copy came from. */
  def installedSource: Option[BasemapSource] = BasemapSource.fromKey(prefs.getString("source", ""))

  @volatile private var progress: Option[(Long, Long, BasemapSource)] = None

  def status: BasemapStatus =
    Basemap.pin match
      case None => BasemapStatus.Unavailable
      case Some(p) =>
        Basemap.installedVersion match
          case Some(v) => BasemapStatus.Installed(v, installedSource)
          case None =>
            progress match
              case Some((d, t, s)) => BasemapStatus.Running(d, t, s)
              case None =>
                val err = prefs.getString("error", "")
                val queued = prefs.getBoolean("queued", false)
                if err.nonEmpty then BasemapStatus.Failed(err, queued)
                else if queued then BasemapStatus.Waiting(prefs.getBoolean("metered", false))
                else BasemapStatus.Missing

  // ---- listeners (main thread) ----
  private val listeners = scala.collection.mutable.LinkedHashSet.empty[() => Unit]
  def listen(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f
  /** Tells the UI (on the main thread) that [[status]] or the installed data changed. */
  def changed(): Unit = AshidoriApp.app.getMainExecutor.execute(() => listeners.toSeq.foreach(_()))

  private def request(allowMetered: Boolean, data: Data = Data.EMPTY): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(classOf[BasemapWorker])
      .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(if allowMetered then NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .setInputData(data)
      .build()

  /** The user asked for the download (or a retry). Replaces queued work, so
    * a confirmation for a metered network takes effect at once.
    */
  def start(ctx: Context, allowMetered: Boolean): Unit =
    if Basemap.pin.nonEmpty then
      prefs.edit().putBoolean("queued", true).putBoolean("metered", allowMetered)
        .putString("error", "").putBoolean("declined", false).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, request(allowMetered))
      changed()

  /** At app start: queue the first download on an unmetered network, unless
    * it is there or the user deleted it. Leaves queued work as it is.
    */
  def autoStart(ctx: Context): Unit =
    if Basemap.pin.nonEmpty && !Basemap.installed && !prefs.getBoolean("declined", false) then
      val metered = prefs.getBoolean("queued", false) && prefs.getBoolean("metered", false)
      prefs.edit().putBoolean("queued", true).putBoolean("metered", metered).putString("error", "").apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request(metered))

  /** Debug builds: installs `file` (pushed with adb) instead of downloading. */
  def importFile(ctx: Context, file: File): Unit =
    if debuggable && Basemap.pin.nonEmpty then
      prefs.edit().putBoolean("queued", true).putString("error", "").putBoolean("declined", false).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequest.Builder(classOf[BasemapWorker])
          .setInputData(Data.Builder().putString(BasemapWorker.ImportKey, file.getAbsolutePath).build()).build())
      changed()

  /** Stops a download and deletes all basemap files; no automatic download until [[start]]. */
  def delete(ctx: Context): Unit =
    WorkManager.getInstance(ctx).cancelUniqueWork(WorkName)
    prefs.edit().putBoolean("declined", true).putBoolean("queued", false).putString("error", "").remove("source").apply()
    progress = None
    AshidoriApp.io.execute { () =>
      Basemap.deleteAll()
      changed()
    }

  // ---- called by the worker ----
  private[ashidori] def reportProgress(p: Option[(Long, Long, BasemapSource)]): Unit =
    progress = p
    changed()
  private[ashidori] def succeeded(source: BasemapSource): Unit =
    progress = None
    prefs.edit().putString("source", source.key).putBoolean("queued", false).putString("error", "").commit()
    changed()
  private[ashidori] def failed(message: String, retrying: Boolean): Unit =
    progress = None
    prefs.edit().putString("error", message).putBoolean("queued", retrying).commit()
    changed()

/** HttpURLConnection for [[ResumableDownload]]: a plain GET (no token, no
  * cookies), uncompressed so byte ranges mean bytes of the file.
  */
object PlainHttp extends RangeHttp:
  def get(url: String, from: Long): RangeHttp.Response =
    val c = URL(url).openConnection().asInstanceOf[HttpURLConnection]
    c.setConnectTimeout(15_000)
    c.setReadTimeout(30_000)
    c.setInstanceFollowRedirects(true)
    c.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName}")
    c.setRequestProperty("Accept-Encoding", "identity")
    if from > 0 then c.setRequestProperty("Range", s"bytes=$from-")
    val st = c.getResponseCode
    new RangeHttp.Response:
      def code: Int = st
      def header(name: String): Option[String] = Option(c.getHeaderField(name))
      def body: InputStream = if st < 400 then c.getInputStream else InputStream.nullInputStream()
      def close(): Unit = c.disconnect()

  /** A small response body (e.g. `/v1/info`), or None. */
  def small(url: String): Option[String] =
    Try {
      val c = URL(url).openConnection().asInstanceOf[HttpURLConnection]
      try
        c.setConnectTimeout(10_000)
        c.setReadTimeout(10_000)
        c.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName}")
        if c.getResponseCode != 200 then None
        else
          val in = c.getInputStream
          try Some(String(in.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8)) finally in.close()
      finally c.disconnect()
    }.toOption.flatten

/** Downloads (or, in debug builds, imports), verifies and installs the
  * pinned archive; see [[BasemapDownload]]. Not a location send.
  */
class BasemapWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  import BasemapWorker.*

  override def doWork(): ListenableWorker.Result =
    Basemap.pin match
      case None => ListenableWorker.Result.success()
      case Some(pin) =>
        try
          if Basemap.installed then
            BasemapDownload.succeeded(BasemapDownload.installedSource.getOrElse(BasemapSource.GitHub))
          else
            val (archive, source) = Option(getInputData.getString(ImportKey)).filter(_ => BasemapDownload.debuggable) match
              case Some(path) =>
                val f = File(path)
                if !Sha256.matches(f, pin) then throw BasemapError(s"${f.getName} は指定のファイルと一致しません")
                (f, BasemapSource.LocalFile)
              case None => download(pin)
            Basemap.lock.synchronized {
              Basemap.store.install(archive, pin)
              // Deleted (or replaced) meanwhile: do not leave it installed.
              if isStopped then
                Basemap.store.deleteAll()
                throw ResumableDownload.Cancelled()
            }
            if source != BasemapSource.LocalFile then archive.delete()
            Basemap.cleanup()
            Log.i(s"basemap ${pin.version} installed from ${source.key}")
            BasemapDownload.succeeded(source)
          ListenableWorker.Result.success()
        catch
          case _: ResumableDownload.Cancelled =>
            BasemapDownload.reportProgress(None)
            ListenableWorker.Result.retry()
          case e: Exception =>
            Log.w("basemap download failed", e)
            val last = getRunAttemptCount + 1 >= BasemapDownload.MaxAttempts
            BasemapDownload.failed(message(e), retrying = !last)
            if last then ListenableWorker.Result.failure() else ListenableWorker.Result.retry()

  private def message(e: Exception): String = e match
    case _: java.net.UnknownHostException | _: java.net.ConnectException | _: java.net.SocketTimeoutException =>
      "接続できませんでした"
    case e: BasemapError => e.getMessage
    case e => s"取得できませんでした（${Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(100)}）"

  /** The pinned archive in the download directory, and where it came from. */
  private def download(pin: BasemapPin): (File, BasemapSource) =
    val store = Basemap.store
    val part = store.partFile(pin)
    part.getParentFile.mkdirs()
    // Room for the rest of the archive and its extraction.
    val need = (pin.bytes - (if part.isFile then part.length else 0L)) + pin.bytes + 16L * 1024 * 1024
    if part.getParentFile.getUsableSpace < need then
      throw BasemapError(s"空き容量が不足しています（約 ${need / 1_000_000} MB 必要）")

    val sources: Seq[(BasemapSource, String)] = BasemapDownload.debugUrl match
      case Some(base) => Seq(BasemapSource.DebugUrl -> BasemapSource.debugUrl(base, pin))
      case None =>
        import net.sksat.ashidori.sync.SyncConfig
        val server = Some(SyncConfig.serverUrl).filter(u => u.nonEmpty && SyncConfig.paired)
        // Unauthenticated: the device token is not sent for this.
        val listed = server.flatMap(u => PlainHttp.small(s"${u.stripSuffix("/")}/v1/info"))
          .exists(info => BasemapSource.serverVersions(info).contains(pin.version))
        BasemapSource.order(server.nonEmpty, listed).map {
          case BasemapSource.Server => BasemapSource.Server -> BasemapSource.serverUrl(server.get, pin)
          case s => s -> BasemapSource.githubUrl(pin)
        }

    var lastError: Exception = IOException("no source")
    val found = sources.iterator.map { (source, url) =>
      try
        var shown = -1L
        ResumableDownload.run(PlainHttp, url, part, pin.bytes, () => isStopped, n => {
          // About every 1 %.
          if n == pin.bytes || n - shown >= pin.bytes / 100 then
            shown = n
            BasemapDownload.reportProgress(Some((n, pin.bytes, source)))
        })
        if Sha256.matches(part, pin) then Some(part -> source)
        else
          // Bytes from either source must be the pinned ones; start over.
          part.delete()
          lastError = BasemapError(s"データの照合に失敗しました（${source.label}）")
          None
      catch
        case e: ResumableDownload.Cancelled => throw e
        case e: Exception =>
          Log.w(s"basemap from ${source.key} failed", e)
          lastError = e
          None
    }.collectFirst { case Some(r) => r }
    found.getOrElse(throw lastError)

object BasemapWorker:
  val ImportKey = "import"

/** A failure with a message for the user. */
final class BasemapError(message: String) extends IOException(message)
