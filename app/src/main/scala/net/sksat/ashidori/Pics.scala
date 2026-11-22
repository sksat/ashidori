package net.sksat.ashidori

import android.os.SystemClock
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
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
  val QueueSize = 2000
  val MaxFailures = 5

  private final case class In(r: PicsResult, elapsed: Long)
  private val queue = ArrayBlockingQueue[In](QueueSize)
  private val dropped = AtomicLong()
  private enum Cmd:
    case CloseAll
    case Test(on: Boolean)
  private val commands = ConcurrentLinkedQueue[Cmd]()

  /** The logger is still (runs last up to 60 s), and the report delay of the scan. */
  @volatile var still = false
  @volatile var delayMs = 0L
  /** The last qualifying reception and the last of kind 2 (elapsed ms; 0: none). */
  @volatile var lastQualifying = 0L
  @volatile var lastSignals = 0L
  /** Rows refused by the caps in this process, by reason (端末診断). */
  @volatile var refused: Map[PicsRuns.Refused, Long] = Map.empty
  /** Rows accepted today (the day cap). */
  @volatile var acceptedToday = 0
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
      if queue.offer(In(r, elapsed)) then true
      else
        dropped.incrementAndGet()
        false

  /** The scan changed or stopped: open runs are closed (design 4.1). */
  def scanChanged(): Unit =
    commands.add(Cmd.CloseAll)
    thread

  def setTest(on: Boolean): Unit =
    commands.add(Cmd.Test(on))
    thread

  private def broken: Boolean = PicsLive.broken.isDefined

  private lazy val thread: Thread =
    val t = Thread(() => loop(), "ashidori-pics")
    t.setDaemon(true)
    t.start()
    t

  private def loop(): Unit =
    val runs = PicsRuns(AshidoriApp.zone)
    var failures = 0
    var lastLog = SystemClock.elapsedRealtime()
    val batch = mutable.ArrayBuffer.empty[In]
    while !broken do
      try
        batch.clear()
        Option(queue.poll(1, TimeUnit.SECONDS)).foreach { first =>
          batch += first
          var more = queue.poll()
          while more != null && batch.size < QueueSize do
            batch += more
            more = queue.poll()
        }
        val out = Vector.newBuilder[PicsAdv]
        var cmd = commands.poll()
        while cmd != null do
          cmd match
            case Cmd.CloseAll => out ++= runs.closeAll()
            case Cmd.Test(on) => out ++= runs.setTest(on)
          cmd = commands.poll()
        val d = dropped.getAndSet(0)
        if d > 0 then runs.refuse(PicsRuns.Refused.Queue, d)
        if batch.nonEmpty then
          batch.foreach(live)
          out ++= runs.add(batch.map(_.r).toSeq, still)
        out ++= runs.tick(System.currentTimeMillis(), still, delayMs)
        val rows = out.result()
        if rows.nonEmpty then LoggerWrites.small(SmallRecord.PicsAdvs(rows))
        refused = runs.refusedTotal
        acceptedToday = runs.acceptedToday
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
  private def live(in: In): Unit =
    val p = in.r.packet
    if p.qualifying then
      val first = in.elapsed - lastQualifying > 2_000L
      lastQualifying = math.max(lastQualifying, in.elapsed)
      if p.kind.contains(2) then lastSignals = math.max(lastSignals, in.elapsed)
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
        case PicsFormat.Body.Signals(ps) if in.elapsed >= before.fold(0L)(_.signalsAt) => (ps, in.elapsed)
        case _ => (before.fold(Seq.empty[PicsFormat.Ped])(_.peds), before.fold(0L)(_.signalsAt))
      val h = PicsLive.Heard(iid, site, text, peds, at, math.max(in.elapsed, before.fold(0L)(_.lastAt)),
        math.max(in.r.at, before.fold(0L)(_.lastWall)), in.r.rssi)
      // A few intersections at once at most; forget the oldest beyond 16.
      val all = PicsLive.heard + (iid -> h)
      PicsLive.heard = if all.size <= 16 then all else all.toSeq.sortBy(-_._2.lastAt).take(16).toMap
      // The plan may start receiving: asked at most once a second.
      if first || PicsLive.plan.state != PicsScanPolicy.State.Receiving && in.elapsed - lastReplan >= 1_000L then
        lastReplan = in.elapsed
        PicsLive.replan()
