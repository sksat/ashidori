package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.io.{File, IOException}
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
  @volatile private var known: Option[Option[File]] = None
  /** Directory of the installed pinned version: looked up (and kept) off
    * the main thread; on the main thread the one last looked up. Never
    * looked up yet (a cold start, before the background look-up): None for
    * now ([[known]] false), looked up in the background, then [[looked]]
    * tells the listeners.
    */
  def dir: Option[File] =
    if android.os.Looper.myLooper() == android.os.Looper.getMainLooper then
      known.getOrElse {
        AshidoriApp.io.execute { () => look(); looked() }
        None
      }
    else look()
  /** Whether the installed version has been looked up (on the main thread, [[dir]] may not know yet). */
  def isKnown: Boolean = known.nonEmpty
  /** Set by the download: tells its listeners once a background look-up is done. */
  @volatile var looked: () => Unit = () => ()
  private def look(): Option[File] =
    IoCheck.offMain(s"${kind.name} installed")
    val d = pin.flatMap(store.installed)
    known = Some(d)
    d
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
    look()
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
  /** Not known yet whether it is installed (looked up in the background). */
  case Checking
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
  * The archive covers whole countries, so fetching it reveals no position,
  * but it is a send to an external destination: [[Dest.Data]]. It starts by
  * itself at app start only while that rule is automatic ([[autoStart]]),
  * and on the user's request while it is not never ([[start]]). Each run
  * keeps the mode and generation it was queued under (WorkManager input)
  * and takes a new permit when it runs; without one it ends without
  * sending or retrying. Every request, retry and redirect goes through
  * [[Net.outbound]]. A change of the rule cancels queued runs
  * ([[rulesChanged]]).
  *
  * It goes to GitHub Releases, or (`server`) to the paired sync server when
  * that has the pinned version, without the device token; the bytes are
  * accepted only with the pinned size and sha256.
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

  data.looked = () => changed()

  def status: BasemapStatus =
    data.pin match
      case None => BasemapStatus.Unavailable
      case Some(p) if !data.isKnown && android.os.Looper.myLooper() == android.os.Looper.getMainLooper =>
        data.dir
        BasemapStatus.Checking
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
  /** Tells the UI (on the main thread) that [[status]] or the installed data
    * changed; the installed version is looked up first, off the main thread.
    */
  def changed(): Unit =
    val tell: Runnable = () => listeners.toSeq.foreach(_())
    if android.os.Looper.myLooper() == android.os.Looper.getMainLooper then
      AshidoriApp.io.execute { () => data.dir; AshidoriApp.app.getMainExecutor.execute(tell) }
    else
      data.dir
      AshidoriApp.app.getMainExecutor.execute(tell)

  /** The user may ask for the download now. */
  def allowed: Boolean = Settings.gate.permit(Dest.Data, Mode.OnAction).isDefined

  /** `delayMs`: not before then (the cool-down after a run given up). The
    * run keeps `mode` and the generation of `permit` ([[PinnedWorker]]).
    */
  private def request(allowMetered: Boolean, permit: Permit, delayMs: Long = 0): OneTimeWorkRequest =
    val b = OneTimeWorkRequest.Builder(worker)
      .setInputData(Data.Builder()
        .putString(BasemapWorker.ModeKey, permit.mode.toString)
        .putString(BasemapWorker.GenKey, java.lang.Long.toHexString(permit.gen)).build())
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
    val permit = Settings.gate.permit(Dest.Data, Mode.OnAction)
    if permit.isEmpty then Net.log(s"block dest=data reason=permit ${data.kind.name} start")
    for p <- permit if data.pin.nonEmpty do
      val e = prefs.edit().putBoolean("queued", true).putBoolean("metered", allowMetered).putBoolean("declined", false)
      putFailure(e, DownloadRetry.userRetry(failure)).apply()
      WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, request(allowMetered, p))
      changed()

  /** At app start: queue the download on an unmetered network, unless it is
    * there or the user deleted it; after a run given up, not before its
    * cool-down ends. Leaves queued work (also a scheduled retry) as it is.
    */
  def autoStart(ctx: Context): Unit = AshidoriApp.io.execute { () =>
    // The installed version looked up here, off the main thread, first, and
    // the preferences loaded (the list reads both on the main thread).
    data.dir
    installedSource
    for p <- data.pin; permit <- Settings.gate.permit(Dest.Data, Mode.Auto) do
      if !data.installed && !prefs.getBoolean("declined", false) then
        val metered = prefs.getBoolean("queued", false) && prefs.getBoolean("metered", false)
        prefs.edit().putBoolean("queued", true).putBoolean("metered", metered).remove("error").apply()
        val delay = DownloadRetry.autoStartDelay(storedFailure, p.version, System.currentTimeMillis())
        WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request(metered, permit, delay))
    changed()
  }

  /** The rule of [[Dest.Data]] changed, or the pause started or ended:
    * queued runs (also a scheduled retry) go, as they would not send under
    * their old generation anyway; with the rule automatic it is queued
    * again as at app start.
    */
  def rulesChanged(ctx: Context): Unit =
    WorkManager.getInstance(ctx).cancelUniqueWork(WorkName)
    if prefs.getBoolean("queued", false) && !data.installed then
      Net.log(s"cancel dest=data ${data.kind.name}")
      prefs.edit().putBoolean("queued", false).apply()
    progress = None
    autoStart(ctx)
    changed()

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
                               sources: Seq[(String, DownloadCause)], runAttempt: Int, permit: Option[Permit]): DownloadRetry.Next =
    progress = None
    val now = System.currentTimeMillis()
    val (f, next) = DownloadRetry.afterFailure(version, now, cause, sources, runAttempt, storedFailure)
    putFailure(prefs.edit().putBoolean("queued", true), Some(f)).commit()
    next match
      case DownloadRetry.Next.Later(at) =>
        // Under the run's own generation (an imported file's: automatic now).
        permit.orElse(Settings.gate.permit(Dest.Data, Mode.Auto)).foreach { p =>
          WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(prefs.getBoolean("metered", false), p, math.max(0L, at - now)))
        }
      case DownloadRetry.Next.Backoff => ()
    changed()
    next
  /** A run found no permit (the rule changed since it was queued): it ends without sending. */
  private[ashidori] def dropped(): Unit =
    progress = None
    prefs.edit().putBoolean("queued", false).commit()
    changed()

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

  /** A permit for this run: of the mode and generation it was queued
    * under, if those are still current. A run queued by an older version
    * (no generation) counts as automatic, under the rule now.
    */
  private def permit(): Option[Permit] =
    val in = getInputData
    val mode = Option(in.getString(ModeKey)).flatMap(m => Mode.values.find(_.toString == m))
    val gen = Option(in.getString(GenKey)).flatMap(g => scala.util.Try(java.lang.Long.parseUnsignedLong(g, 16)).toOption)
    (mode, gen) match
      case (Some(m), Some(g)) => Settings.gate.permitFor(Dest.Data, m, g)
      case _ => Settings.gate.permit(Dest.Data, Mode.Auto)

  override def doWork(): ListenableWorker.Result =
    data.pin match
      case None => ListenableWorker.Result.success()
      case Some(pin) =>
        val imported = Option(getInputData.getString(ImportKey)).filter(_ => dl.debuggable)
        val p = if imported.nonEmpty || data.installed then None else permit()
        if imported.isEmpty && !data.installed && p.isEmpty then
          Net.log(s"block dest=data reason=permit ${name} run")
          dl.dropped()
          return ListenableWorker.Result.success()
        try
          if data.installed then
            dl.succeeded(dl.installedSource.getOrElse(BasemapSource.GitHub))
          else
            val (archive, source) = imported match
              case Some(path) =>
                val f = File(path)
                if !Sha256.matches(f, pin) then throw PinnedFailure(DownloadCause.Mismatch)
                (f, BasemapSource.LocalFile)
              case None => download(pin, p.get)
            // Its extraction within the shared budget (design 6.8).
            val size = BasemapStore.extractedSize(archive)
            val room = Storage.budget.reserve(size).getOrElse {
              Log.w(s"$name: ${Storage.shortage(size)}")
              throw PinnedFailure(DownloadCause.Storage)
            }
            try data.lock.synchronized {
              data.changedOnDisk()
              try data.store.install(archive, pin, o => GuardedOutputStream(o, room.guard))
              catch case _: StorageBudget.NoRoom => throw PinnedFailure(DownloadCause.Storage)
              // Deleted (or replaced) meanwhile: do not leave it installed.
              if isStopped then
                data.store.deleteAll()
                throw ResumableDownload.Cancelled()
            }
            finally room.release()
            if source != BasemapSource.LocalFile then archive.delete()
            data.cleanup()
            Log.i(s"$name ${pin.version} installed from ${source.key}")
            dl.succeeded(source)
          ListenableWorker.Result.success()
        catch
          // The rule changed (or the pause started) meanwhile: no retry.
          case _: Outbound.Blocked =>
            Net.log(s"block dest=data reason=permit ${name} transfer")
            dl.dropped()
            ListenableWorker.Result.success()
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
            dl.failed(getApplicationContext, pin.version, cause, sources, getRunAttemptCount, p) match
              case DownloadRetry.Next.Backoff => ListenableWorker.Result.retry()
              // The next run is appended to this one: it must end as a success.
              case DownloadRetry.Next.Later(_) => ListenableWorker.Result.success()

  /** The pinned archive in the download directory, and where it came from. */
  private def download(pin: BasemapPin, permit: Permit): (File, BasemapSource) =
    val store = data.store
    val part = store.partFile(pin)
    part.getParentFile.mkdirs()
    // The rest of the archive, within the shared budget (design 6.8); its
    // extraction reserves again once it is verified.
    val have = if part.isFile then part.length else 0L
    val need = pin.bytes - have
    val room = Storage.budget.reserveFile(pin.bytes, have).getOrElse {
      Log.w(s"$name: ${Storage.shortage(need)}")
      throw PinnedFailure(DownloadCause.Storage)
    }
    try fetch(pin, permit, part, room)
    finally room.release()

  private def fetch(pin: BasemapPin, permit: Permit, part: File, room: StorageBudget#Reservation): (File, BasemapSource) =

    val sources: Seq[(BasemapSource, String)] = dl.debugUrl match
      case Some(base) => Seq(BasemapSource.DebugUrl -> BasemapSource.debugUrl(base, pin))
      case None =>
        import net.sksat.ashidori.sync.SyncConfig
        val server = Some(SyncConfig.serverUrl).filter(u => dl.server && u.nonEmpty && SyncConfig.paired)
        // Unauthenticated: the device token is not sent for this.
        val listed = server.flatMap(u => Net.outbound.small(permit, s"${u.stripSuffix("/")}/v1/info", Seq("User-Agent" -> ua)))
          .exists(info => BasemapSource.serverVersions(info).contains(pin.version))
        BasemapSource.order(server.nonEmpty, listed).map {
          case BasemapSource.Server => BasemapSource.Server -> BasemapSource.serverUrl(server.get, pin)
          case s => s -> BasemapSource.githubUrl(pin)
        }

    val http = Net.outbound.rangeHttp(permit, ua)
    val errors = scala.collection.mutable.ArrayBuffer.empty[(BasemapSource, DownloadCause)]
    val found = sources.iterator.map { (source, url) =>
      try
        var shown = -1L
        ResumableDownload.run(http, url, part, pin.bytes, () => isStopped, n => {
          // Every write reports the file's size, also after a restart from
          // the start (another source, HTTP 200 to a Range request): it stops
          // (the partial file stays) once it would cut into the logging reserve.
          if !room.at(n) then throw PinnedFailure(DownloadCause.Storage)
          // About every 1 %.
          if n == pin.bytes || n - shown >= pin.bytes / 100 then
            shown = n
            dl.reportProgress(Some((n, pin.bytes, source)))
        }, o => GuardedOutputStream(o, room.guard))
        if Sha256.matches(part, pin) then Some(part -> source)
        else
          // Bytes from either source must be the pinned ones; start over.
          part.delete()
          Log.w(s"$name from ${source.key}: checksum mismatch")
          errors += source -> DownloadCause.Mismatch
          None
      catch
        case e: ResumableDownload.Cancelled => throw e
        case e: Outbound.Blocked => throw e
        case e: PinnedFailure => throw e
        // Each chunk is checked before it is written: no room, the partial file stays.
        case _: StorageBudget.NoRoom => throw PinnedFailure(DownloadCause.Storage)
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
  /** The mode and generation ([[Permit]]) a run was queued under. */
  val ModeKey = "mode"
  val GenKey = "gen"
  private[ashidori] def ua = s"ashidori/${BuildInfo.versionName}"

/** A failed run, with the cause shown and each source's cause. */
final class PinnedFailure(val problem: DownloadCause, val sources: Seq[(String, DownloadCause)] = Seq.empty)
  extends IOException(DownloadCause.label(problem))
