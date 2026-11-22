package net.sksat.ashidori

import android.os.SystemClock



import scala.util.Try
import scala.util.control.NonFatal
import ashidori.core.*

/** The bundled list of 高度化PICS intersections (assets/pics/, written by
  * tools/pics/build.py) and its grid (docs/design/pics.md 4.3).
  */
object PicsSitesData:
  val Dir = "pics"

  lazy val index: PicsSites.Index =
    Try {
      val in = AshidoriApp.app.getAssets.open(s"$Dir/sites.csv")
      try PicsSites.Index(PicsSites.parse(String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)))
      finally in.close()
    }.fold(e => { Log.w("PICS site list not read", e); PicsSites.Index(Vector.empty) }, identity)

  /** The attribution lines of the list (データの出典). */
  def attribution: Seq[String] =
    Try {
      val in = AshidoriApp.app.getAssets.open(s"$Dir/manifest.json")
      try ujson.read(in.readAllBytes())("attribution").arr.map(_.str).toSeq finally in.close()
    }.getOrElse(Seq.empty)

/** What the status and 端末診断 show of the reception. Written by the
  * `ashidori-pics` thread and the logger thread, read by the UI. Times on
  * the monotonic clock (elapsedRealtime, ms) unless named `wall`.
  */
object PicsLive:
  /** One intersection heard: its ID, the listed site its kind 1 position
    * names (if one alone), its text (kind 0), its signals and when they
    * came, and when anything of it came last.
    */
  final case class Heard(iid: String, site: Option[PicsSite], text: Option[String], peds: Seq[PicsFormat.Ped],
                         signalsAt: Long, lastAt: Long, lastWall: Long, rssi: Int):
    def label: String = site.map(_.name).getOrElse(s"ID ${PicsFormat.idNumber(iid)}")

  @volatile var heard: Map[String, Heard] = Map.empty
  @volatile var plan: PicsScanPolicy.Plan = PicsScanPolicy.Initial
  /** The scan registered and its report delay (None: none). */
  @volatile var registered: Option[(PicsScan, Long)] = None
  /** The last start failure: when (wall), its code. */
  @volatile var failure: Option[(Long, Int)] = None
  /** The plan's inputs, as last logged (PICS_STATE). */
  @volatile var input: Option[PicsScanPolicy.Input] = None
  /** The reception stopped for this process after repeated failures. */
  @volatile var broken: Option[String] = None
  /** Asks the running logger to plan the scan again (set by it). */
  @volatile var replan: () => Unit = () => ()
  /** 「受信の試験」 until then (elapsed); 0: none. */
  @volatile var testUntil: Long = 0L

  /** The intersection to show: the one whose signals came last, else the last heard. */
  def current: Option[Heard] =
    val h = heard.values
    h.filter(_.signalsAt > 0).maxByOption(_.signalsAt).orElse(h.maxByOption(_.lastAt))

  def changed(): Unit = LoggerState.changed()

/** The path of received advertisements (design 4.6): the scan's callback
  * (a Binder thread) only puts results in a bounded queue (2,000; a full
  * queue counts as refused); the `ashidori-pics` thread reads them, builds
  * the runs ([[PicsRuns]], the caps applied there) and hands the closed
  * rows to the logger's write path ([[LoggerWrites]]). A failure here is
  * caught on that thread and never takes the logger down; after
  * [[MaxFailures]] in a row the reception stops for this process (shown in
  * 端末診断).
  */
object PicsIngest:
  val QueueSize = PicsPipeline.QueueSize
  val MaxFailures = 5

  private lazy val pipeline = PicsPipeline(AshidoriApp.zone)

  /** The logger is still (runs last up to 60 s), and the report delay of the scan. */
  @volatile var still = false
  @volatile var delayMs = 0L
  /** The last qualifying reception and the last of kind 2 (elapsed ms; 0: none). */
  @volatile var lastQualifying = 0L
  @volatile var lastSignals = 0L
  /** Rows refused by the caps in this process, by reason (端末診断). */
  @volatile var refused: Map[PicsRuns.Refused, Long] = Map.empty
  /** Rows accepted today (the day cap, with those stored before this process). */
  @volatile var acceptedToday = 0
  /** The rows build as 「受信の試験」 (followed from the plan each round). */
  @volatile var testing = false
  private val debuggable =
    (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  private var lastReplan = 0L

  /** One advertisement (the scan's callback, or a debug injection): `elapsed`
    * its time on the monotonic clock. False if it was not taken.
    */
  def onResult(r: PicsResult, elapsed: Long): Boolean =
    if broken || !PicsFormat.accepts(r.company, debuggable) then false
    else
      thread
      pipeline.offer(r, elapsed)

  /** The scan changed or stopped: open runs are closed (design 4.1). */
  def scanChanged(): Unit =
    pipeline.scanChanged()
    thread

  private def broken: Boolean = PicsLive.broken.isDefined

  private lazy val thread: Thread =
    val t = Thread(() => loop(), "ashidori-pics")
    t.setDaemon(true)
    t.start()
    t

  /** The day's cap counts what earlier processes stored today and
    * yesterday (a late result of yesterday counts on yesterday): read once
    * at the start. A failure to read leaves it at 0 (logged).
    */
  private def seed(runs: PicsRuns): Unit =
    try
      val zone = AshidoriApp.zone
      val today = java.time.LocalDate.now(zone)
      for d <- Seq(today.minusDays(1), today) do
        val from = d.atStartOfDay(zone).toInstant.toEpochMilli
        val to = d.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
        runs.seedDay(from, AshidoriApp.db.picsAdv(from, to).size)
    catch case NonFatal(e) => Log.w("PICS: the day's rows not read for the cap", e)

  private def loop(): Unit =
    val runs = pipeline.runs
    seed(runs)
    var failures = 0
    var lastLog = SystemClock.elapsedRealtime()
    while !broken do
      try
        // The test follows the plan each round: one that ended while the
        // logger stopped (the plan reset) ends here too.
        val test = PicsLive.plan.state == PicsScanPolicy.State.Test
        val (batch, rows) = pipeline.step(1000L, System.currentTimeMillis(), still, delayMs, test)
        testing = runs.isTesting
        batch.foreach(i => live(i.r, i.elapsed))
        if rows.nonEmpty then LoggerWrites.small(SmallRecord.PicsAdvs(rows))
        refused = runs.refusedTotal
        acceptedToday = runs.acceptedOn(System.currentTimeMillis())
        val now = SystemClock.elapsedRealtime()
        if now - lastLog >= 60_000L then
          lastLog = now
          val r = runs.takeRefused()
          if r.nonEmpty then
            LifeLog.add(LifeKind.PicsProblem,
              s"上限で書かなかった受信 ${r.values.sum} 件（${r.toSeq.sortBy(-_._2).map((k, n) => s"${k.label} $n").mkString("、")}）")
        if batch.nonEmpty then PicsLive.changed()
        failures = 0
      catch case NonFatal(e) =>
        failures += 1
        Log.w(s"PICS reception failed ($failures)", e)
        if failures >= MaxFailures then
          PicsLive.broken = Some(LifeLog.describe(e))
          LifeLog.add(LifeKind.PicsProblem, s"信号機の電波の処理が続けて失敗したため受信を停止（${LifeLog.describe(e)}）")
          PicsLive.replan()
        else Thread.sleep(1000L)

  /** What the reception says now: the times the plan reads, and the intersections heard. */
  private def live(r: PicsResult, elapsed: Long): Unit =
    val p = r.packet
    if p.qualifying then
      val first = elapsed - lastQualifying > 2_000L
      lastQualifying = math.max(lastQualifying, elapsed)
      if p.kind.contains(2) then lastSignals = math.max(lastSignals, elapsed)
      val iid = p.intersection.get
      val before = PicsLive.heard.get(iid)
      val body = p.body
      val site = body match
        case PicsFormat.Body.Position(lat, lon) => PicsSitesData.index.matching(lat, lon).orElse(before.flatMap(_.site))
        case _                                  => before.flatMap(_.site)
      val text = body match
        case PicsFormat.Body.Text(t) => Some(t).filter(_.nonEmpty)
        case _                       => before.flatMap(_.text)
      val (peds, at) = body match
        case PicsFormat.Body.Signals(ps) if elapsed >= before.fold(0L)(_.signalsAt) => (ps, elapsed)
        case _ => (before.fold(Seq.empty[PicsFormat.Ped])(_.peds), before.fold(0L)(_.signalsAt))
      val h = PicsLive.Heard(iid, site, text, peds, at, math.max(elapsed, before.fold(0L)(_.lastAt)),
        math.max(r.at, before.fold(0L)(_.lastWall)), r.rssi)
      // A few intersections at once at most; forget the oldest beyond 16.
      val all = PicsLive.heard + (iid -> h)
      PicsLive.heard = if all.size <= 16 then all else all.toSeq.sortBy(-_._2.lastAt).take(16).toMap
      // The plan may start receiving: asked at most once a second.
      if first || PicsLive.plan.state != PicsScanPolicy.State.Receiving && elapsed - lastReplan >= 1_000L then
        lastReplan = elapsed
        PicsLive.replan()
