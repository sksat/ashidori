package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.io.{File, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.util.concurrent.TimeUnit
import scala.util.Try
import ashidori.core.*

/** A data set downloaded as one pinned archive ([[PinnedKind]]): the
  * offline basemap ([[Basemap]]) or the venue index ([[VenueData]]).
  */
trait PinnedData:
  def kind: PinnedKind
  /** The archive this build accepts; None if the build has no pin. */
  def pin: Option[BasemapPin]
  def store: BasemapStore
  /** Serializes installation, cleanup and deletion (one process). */
  val lock: Object = new Object
  /** Directory of the installed pinned version. */
  def dir: Option[File] = pin.flatMap(store.installed)
  def installed: Boolean = dir.nonEmpty
  def installedVersion: Option[String] = dir.map(_.getName)
  /** Removes other versions and leftovers. Blocking. */
  def cleanup(): Unit = lock.synchronized {
    val gone = store.cleanup(pin)
    if gone.nonEmpty then Log.i(s"${kind.name}: removed ${gone.mkString(", ")}")
  }
  /** Deletes every downloaded file. Blocking. */
  def deleteAll(): Unit = lock.synchronized {
    changedOnDisk()
    store.deleteAll()
  }
  /** Approximate size for the UI, e.g. "約 64 MB". */
  def sizeLabel: String = pin.fold("")(p => s"約 ${math.max(1, math.round(p.bytes / 1e6))} MB")
  /** The installed files are about to change (installed, replaced or
    * deleted): drop what was read from them.
    */
  def changedOnDisk(): Unit = ()

  /** Reads the pin from the app's assets. */
  protected def readPin(asset: String): Option[BasemapPin] =
    Try {
      val in = AshidoriApp.app.getAssets.open(asset)
      try String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    }.toOption.flatMap { s =>
      BasemapPin.parse(s, kind) match
        case Right(p) => Some(p)
        case Left(e) =>
          Log.w(s"${kind.name} pin invalid: $e")
          None
    }

/** State of a pinned download (the offline basemap, the venue index) for the UI. */
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
  /** No source has the pinned archive (each answered 404): not published yet. */
  case NotPublished

/** Downloads the pinned basemap archive once, in the background
  * ([[BasemapWorker]]). See [[PinnedDownload]].
  */
object BasemapDownload extends PinnedDownload(Basemap, "basemap", "basemap-download", classOf[BasemapWorker], server = true)

/** Downloads the pinned venue index once, in the background
  * ([[VenueDataWorker]]), from GitHub Releases only (the sync server does
  * not serve it). See [[PinnedDownload]].
  */
object VenueDownload extends PinnedDownload(VenueData, "venues", "venues-download", classOf[VenueDataWorker], server = false)

/** Downloads a pinned archive ([[PinnedData]]) once, in the background.
  *
  * The archive covers whole countries, so fetching it reveals nothing about
  * where the user is: it is not one of the automatic location sends that
  * 「位置情報を外部サービスに送らない」 stops ([[SendGate]]), and it runs
  * whatever that setting is. It goes to GitHub Releases, or (`server`) to
  * the paired sync server when that has the pinned version, without the
  * device token; the bytes are accepted only with the pinned size and sha256.
  *
  * By default it waits for an unmetered network; on a metered one it runs
  * only after the user confirms ([[start]] with `allowMetered`).
  */
class PinnedDownload(val data: PinnedData, prefsName: String, val WorkName: String,
                     worker: Class[? <: ListenableWorker], val server: Boolean):
  /** Runs before the error is shown as final; later runs only by request or at the next start. */
  val MaxAttempts = 4

  private def prefs = AshidoriApp.app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

  def debuggable: Boolean =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Debug builds only: a directory URL holding the archive, instead of the
    * pinned sources (`am start ... --es ashidori.basemapUrl http://...`, or
    * `ashidori.venuesUrl`). Release builds ignore it.
    */
  def debugUrl: Option[String] = Option(prefs.getString("debugUrl", "")).filter(_.nonEmpty).filter(_ => debuggable)
  def debugUrl_=(u: String): Unit = prefs.edit().putString("debugUrl", u.trim).apply()

  /** Where the installed copy came from. */
  def installedSource: Option[BasemapSource] = BasemapSource.fromKey(prefs.getString("source", ""))

  /** Stored as the error when no source has the archive. */
  private val NotPublishedError = "not-published"

  @volatile private var progress: Option[(Long, Long, BasemapSource)] = None

  def status: BasemapStatus =
    data.pin match
      case None => BasemapStatus.Unavailable
      case Some(p) =>
        data.installedVersion match
          case Some(v) => BasemapStatus.Installed(v, installedSource)
          case None =>
            progress match
              case Some((d, t, s)) => BasemapStatus.Running(d, t, s)
              case None =>
                val err = prefs.getString("error", "")
                val queued = prefs.getBoolean("queued", false)
                if err == NotPublishedError && !queued then BasemapStatus.NotPublished
                else if err.nonEmpty && err != NotPublishedError then BasemapStatus.Failed(err, queued)
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
    OneTimeWorkRequest.Builder(worker)
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
    if data.pin.nonEmpty then
      prefs.edit().putBoolean("queued", true).putBoolean("metered", allowMetered)
        .putString("error", "").putBoolean("declined", false).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, request(allowMetered))
      changed()

  /** At app start: queue the first download on an unmetered network, unless
    * it is there or the user deleted it. Leaves queued work as it is.
    */
  def autoStart(ctx: Context): Unit =
    if data.pin.nonEmpty && !data.installed && !prefs.getBoolean("declined", false) then
      val metered = prefs.getBoolean("queued", false) && prefs.getBoolean("metered", false)
      prefs.edit().putBoolean("queued", true).putBoolean("metered", metered).putString("error", "").apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request(metered))

  /** Debug builds: installs `file` (pushed with adb) instead of downloading. */
  def importFile(ctx: Context, file: File): Unit =
    if debuggable && data.pin.nonEmpty then
      prefs.edit().putBoolean("queued", true).putString("error", "").putBoolean("declined", false).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequest.Builder(worker)
          .setInputData(Data.Builder().putString(BasemapWorker.ImportKey, file.getAbsolutePath).build()).build())
      changed()

  /** Deletes the installed files (e.g. unreadable ones) and downloads them again. */
  def redownload(ctx: Context, allowMetered: Boolean): Unit =
    WorkManager.getInstance(ctx).cancelUniqueWork(WorkName)
    AshidoriApp.io.execute { () =>
      data.deleteAll()
      AshidoriApp.app.getMainExecutor.execute(() => start(ctx, allowMetered))
    }

  /** Stops a download and deletes all its files; no automatic download until [[start]]. */
  def delete(ctx: Context): Unit =
    WorkManager.getInstance(ctx).cancelUniqueWork(WorkName)
    prefs.edit().putBoolean("declined", true).putBoolean("queued", false).putString("error", "").remove("source").apply()
    progress = None
    AshidoriApp.io.execute { () =>
      data.deleteAll()
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
  /** Every source answered that it has no such file: not retried until asked for. */
  private[ashidori] def notPublished(): Unit =
    progress = None
    prefs.edit().putString("error", NotPublishedError).putBoolean("queued", false).commit()
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

/** Downloads the basemap; see [[BasemapDownload]]. The class name is kept:
  * WorkManager stores it with queued work.
  */
class BasemapWorker(ctx: Context, params: WorkerParameters) extends PinnedWorker(ctx, params, BasemapDownload)

/** Downloads the venue index; see [[VenueDownload]]. */
class VenueDataWorker(ctx: Context, params: WorkerParameters) extends PinnedWorker(ctx, params, VenueDownload)

/** Downloads (or, in debug builds, imports), verifies and installs a
  * pinned archive; see [[PinnedDownload]]. Not a location send.
  */
abstract class PinnedWorker(ctx: Context, params: WorkerParameters, dl: PinnedDownload) extends Worker(ctx, params):
  import BasemapWorker.*
  private def data = dl.data
  private def name = data.kind.name

  override def doWork(): ListenableWorker.Result =
    data.pin match
      case None => ListenableWorker.Result.success()
      case Some(pin) =>
        try
          if data.installed then
            dl.succeeded(dl.installedSource.getOrElse(BasemapSource.GitHub))
          else
            val (archive, source) = Option(getInputData.getString(ImportKey)).filter(_ => dl.debuggable) match
              case Some(path) =>
                val f = File(path)
                if !Sha256.matches(f, pin) then throw BasemapError(s"${f.getName} は指定のファイルと一致しません")
                (f, BasemapSource.LocalFile)
              case None => download(pin)
            data.lock.synchronized {
              data.changedOnDisk()
              data.store.install(archive, pin)
              // Deleted (or replaced) meanwhile: do not leave it installed.
              if isStopped then
                data.store.deleteAll()
                throw ResumableDownload.Cancelled()
            }
            if source != BasemapSource.LocalFile then archive.delete()
            data.cleanup()
            Log.i(s"$name ${pin.version} installed from ${source.key}")
            dl.succeeded(source)
          ListenableWorker.Result.success()
        catch
          case _: ResumableDownload.Cancelled =>
            dl.reportProgress(None)
            ListenableWorker.Result.retry()
          case _: NotPublished =>
            Log.w(s"$name ${pin.version}: no source has it")
            dl.notPublished()
            ListenableWorker.Result.failure()
          case e: Exception =>
            Log.w(s"$name download failed", e)
            val last = getRunAttemptCount + 1 >= dl.MaxAttempts
            dl.failed(message(e), retrying = !last)
            if last then ListenableWorker.Result.failure() else ListenableWorker.Result.retry()

  private def message(e: Exception): String = e match
    case _: java.net.UnknownHostException | _: java.net.ConnectException | _: java.net.SocketTimeoutException =>
      "接続できませんでした"
    case e: BasemapError => e.getMessage
    case e => s"取得できませんでした（${Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(100)}）"

  /** The pinned archive in the download directory, and where it came from. */
  private def download(pin: BasemapPin): (File, BasemapSource) =
    val store = data.store
    val part = store.partFile(pin)
    part.getParentFile.mkdirs()
    // Room for the rest of the archive and its extraction.
    val need = (pin.bytes - (if part.isFile then part.length else 0L)) + pin.bytes + 16L * 1024 * 1024
    if part.getParentFile.getUsableSpace < need then
      throw BasemapError(s"空き容量が不足しています（約 ${need / 1_000_000} MB 必要）")

    val sources: Seq[(BasemapSource, String)] = dl.debugUrl match
      case Some(base) => Seq(BasemapSource.DebugUrl -> BasemapSource.debugUrl(base, pin))
      case None =>
        import net.sksat.ashidori.sync.SyncConfig
        val server = Some(SyncConfig.serverUrl).filter(u => dl.server && u.nonEmpty && SyncConfig.paired)
        // Unauthenticated: the device token is not sent for this.
        val listed = server.flatMap(u => PlainHttp.small(s"${u.stripSuffix("/")}/v1/info"))
          .exists(info => BasemapSource.serverVersions(info).contains(pin.version))
        BasemapSource.order(server.nonEmpty, listed).map {
          case BasemapSource.Server => BasemapSource.Server -> BasemapSource.serverUrl(server.get, pin)
          case s => s -> BasemapSource.githubUrl(pin)
        }

    var lastError: Exception = IOException("no source")
    val errors = scala.collection.mutable.ArrayBuffer.empty[Exception]
    val found = sources.iterator.map { (source, url) =>
      try
        var shown = -1L
        ResumableDownload.run(PlainHttp, url, part, pin.bytes, () => isStopped, n => {
          // About every 1 %.
          if n == pin.bytes || n - shown >= pin.bytes / 100 then
            shown = n
            dl.reportProgress(Some((n, pin.bytes, source)))
        })
        if Sha256.matches(part, pin) then Some(part -> source)
        else
          // Bytes from either source must be the pinned ones; start over.
          part.delete()
          lastError = BasemapError(s"データの照合に失敗しました（${source.label}）")
          errors += lastError
          None
      catch
        case e: ResumableDownload.Cancelled => throw e
        case e: Exception =>
          Log.w(s"$name from ${source.key} failed", e)
          lastError = e
          errors += e
          None
    }.collectFirst { case Some(r) => r }
    found.getOrElse(throw (if BasemapSource.notPublished(errors.toSeq) then NotPublished() else lastError))

object BasemapWorker:
  val ImportKey = "import"
  /** No source has the pinned archive. */
  final class NotPublished extends IOException("not published")

/** A failure with a message for the user. */
final class BasemapError(message: String) extends IOException(message)
