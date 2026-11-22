package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.io.{File, IOException}
import java.util.concurrent.TimeUnit
import ashidori.core.*

/** Downloads the planned region packs in the background (design 6.9): one
  * unique WorkManager work (`regions-download`) runs [[RegionRun]] over the
  * planned packs, one at a time in cell order.
  *
  * Each run keeps the mode and generation it was queued under and takes a
  * new permit of [[Dest.Data]] when it runs; every request, retry and
  * redirect goes through [[Net.outbound]]. A change of the rule or the
  * pause cancels the work; planned packs stay planned and wait for the
  * user (or, with the rule automatic, for the next start). It waits for an
  * unmetered network unless 「モバイルデータ通信でも取得」 is on, and writes
  * only within the storage budget ([[budget]]), above the logging reserve.
  */
object RegionDownload:
  val WorkName = "regions-download"
  val ModeKey = "mode"
  val GenKey = "gen"
  /** Also plan newer versions of the placed packs (automatic updates). */
  val UpdateKey = "update"
  /** Held by the run writing the partial files. */
  private[ashidori] object RunLock

  private def request(p: Permit, update: Boolean): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(classOf[RegionWorker])
      .setInputData(Data.Builder()
        .putString(ModeKey, p.mode.toString)
        .putString(GenKey, java.lang.Long.toHexString(p.gen))
        .putBoolean(UpdateKey, update).build())
      .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(if Regions.metered then NetworkType.CONNECTED else NetworkType.UNMETERED)
        .build())
      // Transient failures and a full budget: 1, 2, 4 minutes ... (WorkManager caps it at 5 hours).
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .build()

  /** The user asked (a download, an update, a retry, another network
    * choice): the planned packs are downloaded under a permit of now. It
    * replaces queued work (which may wait for another network); a run in
    * progress stops and the new one resumes its partial file.
    */
  def start(ctx: Context): Unit =
    Settings.gate.permit(Dest.Data, Mode.OnAction).filter(_.level == Level.Cell) match
      case None => Net.log("block dest=data reason=permit regions start")
      case Some(p) =>
        Regions.waiting = None
        WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.REPLACE, request(p, update = false))
        Regions.changed()

  /** At app start, with the rule automatic: newer versions of the placed
    * packs if 自動更新 is on, and packs left planned (e.g. stopped by a pause).
    */
  def autoStart(ctx: Context): Unit =
    // On the io thread, which other work shares: a failure here stays here.
    AshidoriApp.io.execute { () =>
      try
        Regions.refresh()
        Regions.changed()
        val s = Regions.state
        val placed = s.packs.values.exists(_.installed.nonEmpty)
        val planned = s.queue.nonEmpty
        for p <- Settings.gate.permit(Dest.Data, Mode.Auto) if p.level == Level.Cell && Regions.offered do
          if planned || (placed && Regions.autoUpdate) then
            WorkManager.getInstance(ctx).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request(p, update = Regions.autoUpdate))
      catch case e: Throwable => Log.w("regions: not started", e)
    }

  /** The rule of [[Dest.Data]] changed, or the pause started or ended: the
    * work goes (it would not send under its old generation anyway).
    */
  def rulesChanged(ctx: Context): Unit =
    WorkManager.getInstance(ctx).cancelUniqueWork(WorkName)
    Regions.progress = Map.empty
    Net.log("cancel dest=data regions")
    Regions.changed()

  /** The 「Wi-Fi のみ」 choice changed: queued work takes the new constraint. */
  def networkChanged(ctx: Context): Unit =
    if Regions.state.queue.nonEmpty then start(ctx)

  /** The shared budget of the app's large writes ([[Storage]]). */
  def budget: StorageBudget = Storage.budget

/** One run over the planned packs; see [[RegionDownload]]. */
class RegionWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  import RegionDownload.*

  private def permit(): Option[Permit] =
    val in = getInputData
    val mode = Option(in.getString(ModeKey)).flatMap(m => Mode.values.find(_.toString == m))
    val gen = Option(in.getString(GenKey)).flatMap(g => scala.util.Try(java.lang.Long.parseUnsignedLong(g, 16)).toOption)
    (mode, gen) match
      case (Some(m), Some(g)) => Settings.gate.permitFor(Dest.Data, m, g).filter(_.level == Level.Cell)
      case _ => None

  override def doWork(): ListenableWorker.Result =
    Regions.runStep(1)
    try work() finally Regions.runStep(-1)

  private def work(): ListenableWorker.Result =
    // The start's reconciliation first: nothing else touches the files before it.
    Regions.ensureLoaded()
    val p = permit()
    if p.isEmpty || !Regions.offered then
      Net.log("block dest=data reason=permit regions run")
      return ListenableWorker.Result.success()
    try
      val catalog = Regions.fetchCatalog(p.get)
      if getInputData.getBoolean(UpdateKey, false) && Regions.planAllUpdates(catalog) then
        Log.i(s"regions: updates to ${catalog.version} planned")
      val client = Net.outbound.rangeHttp(p.get, BasemapWorker.ua)
      val env = new RegionRun.Env:
        def http: RangeHttp = client
        // The version each pack was planned under (its own release).
        def url(version: String, file: String): String = Regions.url(version, file)
        def store: RegionStore = Regions.store
        def budget: StorageBudget = RegionDownload.budget
        def cancelled(): Boolean = isStopped
        def now(): Long = System.currentTimeMillis()
        /** The pack reported on, its size and its throttle. */
        private var shown: Option[(Cell, Long, ProgressThrottle)] = None
        override def progress(cell: Option[Cell], done: Long): Unit = cell match
          case None =>
            shown = None
            Regions.progress = Map.empty
            Regions.changed()
          case Some(c) =>
            Regions.waiting = None
            // At most every 250 ms, and 1 MiB on (the first and the last bytes always).
            val (_, total, t) = shown.filter(_._1 == c).getOrElse {
              val s = (c, Regions.state.packs.get(c).flatMap(_.target).fold(0L)(_.file.bytes), ProgressThrottle())
              shown = Some(s)
              s
            }
            if t.due(done, total, System.currentTimeMillis()) then
              Regions.progress = Map(c -> done)
              Regions.changed()
        // A change that cannot be written stops the run (it is retried).
        def update(f: RegionState => RegionState): RegionState =
          Regions.update(f).fold(e => throw IOException(s"state not written: $e"), identity)
        override def log(s: String): Unit = Log.i(s)
      // One run at a time: a replaced run may still be ending its last write.
      RunLock.synchronized(RegionRun.run(env)) match
        case RegionRun.Result.Done =>
          Regions.waiting = None
          Regions.progress = Map.empty
          Regions.tidy()
          Regions.changed()
          ListenableWorker.Result.success()
        case RegionRun.Result.NoRoom =>
          Log.w("regions: no room above the logging reserve; waiting")
          Regions.waiting = Some("空き容量が不足")
          Regions.progress = Map.empty
          Regions.changed()
          ListenableWorker.Result.retry()
        case RegionRun.Result.Cancelled =>
          Regions.progress = Map.empty
          Regions.changed()
          ListenableWorker.Result.retry()
    catch
      // The rule changed (or the pause started) meanwhile: no retry.
      case _: Outbound.Blocked =>
        Net.log("block dest=data reason=permit regions transfer")
        Regions.progress = Map.empty
        Regions.changed()
        ListenableWorker.Result.success()
      case e: Exception if isStopped =>
        Regions.progress = Map.empty
        ListenableWorker.Result.retry()
      case e: Exception =>
        Log.w(s"regions: download failed (attempt ${getRunAttemptCount + 1})", e)
        Storage.noticeFailure(e, "地域データの取得")
        Regions.waiting = Some(DownloadCause.label(DownloadCause.of(e)))
        Regions.progress = Map.empty
        Regions.changed()
        ListenableWorker.Result.retry()
