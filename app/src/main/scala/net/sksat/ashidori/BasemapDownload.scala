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
  /** The last attempt failed (`failure`, with its cause and when). `queued`:
    * it runs again by itself, at `failure.retryAt` or as soon as allowed
    * (on an unmetered network unless `metered`).
    */
  case Failed(failure: DownloadFailure, queued: Boolean, metered: Boolean)

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

  /** The stored failure, if it is about the pinned version. */
  def failure: Option[DownloadFailure] =
    data.pin.flatMap(p => DownloadRetry.current(storedFailure, p.version))
  private def storedFailure: Option[DownloadFailure] =
    Option(prefs.getString("failure", "")).flatMap(DownloadFailure.decode)
  private def putFailure(e: android.content.SharedPreferences.Editor, f: Option[DownloadFailure]) =
    e.putString("failure", f.fold("")(DownloadFailure.encode)).remove("error")

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
                val queued = prefs.getBoolean("queued", false)
                val metered = prefs.getBoolean("metered", false)
                failure match
                  case Some(f) => BasemapStatus.Failed(f, queued, metered)
                  case None =>
                    if queued then BasemapStatus.Waiting(metered)
                    else BasemapStatus.Missing

  // ---- listeners (main thread) ----
  private val listeners = scala.collection.mutable.LinkedHashSet.empty[() => Unit]
  def listen(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f
  /** Tells the UI (on the main thread) that [[status]] or the installed data changed. */
  def changed(): Unit = AshidoriApp.app.getMainExecutor.execute(() => listeners.toSeq.foreach(_()))

  /** `delayMs`: not before then (the cool-down after a run given up). */
  private def request(allowMetered: Boolean, delayMs: Long = 0): OneTimeWorkRequest =
    val b = OneTimeWorkRequest.Builder(worker)
      .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(if allowMetered then NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build())
      // Transient failures only ([[DownloadRetry]]): 1, 2, 4 minutes.
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
    if delayMs > 0 then b.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
    b.build()

  /** The user asked for the download (or a retry). Replaces queued work, so
    * a confirmation for a metered network takes effect at once. The last
    * failure stays shown until it succeeds; its cool-down starts over.
    */
  def start(ctx: Context, allowMetered: Boolean): Unit =
    if data.pin.nonEmpty then
      val e = prefs.edit().putBoolean("queued", true).putBoolean("metered", allowMetered).putBoolean("declined", false)
      putFailure(e, DownloadRetry.userRetry(failure)).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, request(allowMetered))
      changed()

  /** At app start: queue the download on an unmetered network, unless it is
    * there or the user deleted it; after a run given up, not before its
    * cool-down ends. Leaves queued work (also a scheduled retry) as it is.
    */
  def autoStart(ctx: Context): Unit =
    data.pin.foreach { p =>
      if !data.installed && !prefs.getBoolean("declined", false) then
        val metered = prefs.getBoolean("queued", false) && prefs.getBoolean("metered", false)
        prefs.edit().putBoolean("queued", true).putBoolean("metered", metered).remove("error").apply()
        val delay = DownloadRetry.autoStartDelay(storedFailure, p.version, System.currentTimeMillis())
        WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request(metered, delay))
    }

  /** Debug builds: installs `file` (pushed with adb) instead of downloading. */
  def importFile(ctx: Context, file: File): Unit =
    if debuggable && data.pin.nonEmpty then
      putFailure(prefs.edit().putBoolean("queued", true).putBoolean("declined", false), None).apply()
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
    putFailure(prefs.edit().putBoolean("declined", true).putBoolean("queued", false).remove("source"), None).apply()
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
    putFailure(prefs.edit().putString("source", source.key).putBoolean("queued", false), None).commit()
    changed()
  /** Attempt `runAttempt` of a run failed: stores the failure and says
    * whether the run retries ([[DownloadRetry]]). A run given up schedules
    * the next one after its cool-down, appended to this one (which must
    * then end as a success, or WorkManager would drop the appended run).
    */
  private[ashidori] def failed(ctx: Context, version: String, cause: DownloadCause,
                               sources: Seq[(String, DownloadCause)], runAttempt: Int): DownloadRetry.Next =
    progress = None
    val now = System.currentTimeMillis()
    val (f, next) = DownloadRetry.afterFailure(version, now, cause, sources, runAttempt, storedFailure)
    putFailure(prefs.edit().putBoolean("queued", true), Some(f)).commit()
    next match
      case DownloadRetry.Next.Later(at) =>
        WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.APPEND_OR_REPLACE,
          request(prefs.getBoolean("metered", false), math.max(0L, at - now)))
      case DownloadRetry.Next.Backoff => ()
    changed()
    next

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
                if !Sha256.matches(f, pin) then throw PinnedFailure(DownloadCause.Mismatch)
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
          // Replaced or cancelled meanwhile (e.g. a retry the user asked
          // for): what this run ran into no longer matters.
          case _: Exception if isStopped =>
            dl.reportProgress(None)
            ListenableWorker.Result.retry()
          case e: Exception =>
            val (cause, sources) = e match
              case f: PinnedFailure => (f.problem, f.sources)
              case other => (DownloadCause.of(other), Seq.empty)
            Log.w(s"$name ${pin.version} failed (attempt ${getRunAttemptCount + 1}): ${DownloadCause.label(cause)}", e)
            dl.failed(getApplicationContext, pin.version, cause, sources, getRunAttemptCount) match
              case DownloadRetry.Next.Backoff => ListenableWorker.Result.retry()
              // The next run is appended to this one: it must end as a success.
              case DownloadRetry.Next.Later(_) => ListenableWorker.Result.success()

  /** The pinned archive in the download directory, and where it came from. */
  private def download(pin: BasemapPin): (File, BasemapSource) =
    val store = data.store
    val part = store.partFile(pin)
    part.getParentFile.mkdirs()
    // Room for the rest of the archive and its extraction.
    val need = (pin.bytes - (if part.isFile then part.length else 0L)) + pin.bytes + 16L * 1024 * 1024
    if part.getParentFile.getUsableSpace < need then
      Log.w(s"$name: about ${need / 1_000_000} MB needed")
      throw PinnedFailure(DownloadCause.Storage)

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

    val errors = scala.collection.mutable.ArrayBuffer.empty[(BasemapSource, DownloadCause)]
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
          Log.w(s"$name from ${source.key}: checksum mismatch")
          errors += source -> DownloadCause.Mismatch
          None
      catch
        case e: ResumableDownload.Cancelled => throw e
        case e: Exception =>
          Log.w(s"$name from ${source.key} failed", e)
          errors += source -> DownloadCause.of(e)
          None
    }.collectFirst { case Some(r) => r }
    // The first source tried is the reason shown; each one's is kept.
    found.getOrElse(throw PinnedFailure(errors.headOption.fold(DownloadCause.Network)(_._2),
      errors.toSeq.map((s, c) => s.label -> c)))

object BasemapWorker:
  val ImportKey = "import"

/** A failed run, with the cause shown and each source's cause. */
final class PinnedFailure(val problem: DownloadCause, val sources: Seq[(String, DownloadCause)] = Seq.empty)
  extends IOException(DownloadCause.label(problem))
