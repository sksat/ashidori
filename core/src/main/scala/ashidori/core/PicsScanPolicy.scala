package ashidori.core

/** A BLE scan for PICS as registered, and its name in `energy_state.ble`
  * and `pics_adv.scan` (design 3.2, 3.6).
  */
enum PicsScan(val key: String, val label: String):
  case Off extends PicsScan("off", "なし")
  /** SCAN_MODE_OPPORTUNISTIC: only results of other apps' scans. */
  case Opportunistic extends PicsScan("opp", "便乗")
  case LowPower extends PicsScan("low", "低電力")
  case Balanced extends PicsScan("bal", "標準")
  /** Receiving with the screen off and offloaded batching: LOW_LATENCY
    * requested, results every 5 s (the batch window is 25 % then).
    */
  case BalancedBatch extends PicsScan("balb", "標準（一括）")
  /** LOW_LATENCY: 「受信の試験」. */
  case LowLatency extends PicsScan("lat", "連続")

object PicsScan:
  def fromKey(k: String): Option[PicsScan] = values.find(_.key == k)

/** When and how to scan for PICS (design 3.1): a pure decision of the
  * state wanted, from the logger's inputs and the previous plan. The
  * scanner ([[PicsScanGate]]) brings the registration to it.
  */
object PicsScanPolicy:
  enum State(val label: String):
    case Stopped extends State("停止")
    case Opportunistic extends State("便乗")
    case Standby extends State("待受")
    case Receiving extends State("受信中")
    case Test extends State("試験")

  /** Faster than this (m/s, 50 km/h), no scan: the owner's rule is speed,
    * not the transport mode (design 9).
    */
  val MaxSpeed = 14.0
  /** Standby within this of a known site (distance − accuracy), m. */
  val StandbyM = 150.0
  /** Standby ends this far away (distance − accuracy), m. */
  val StandbyExitM = 250.0
  /** A fix older than this says nothing about where we are. */
  val FixMaxAgeMs: Long = 5 * 60_000L
  /** Receiving ends after this long without a qualifying reception. */
  val QuietMs: Long = 20_000L
  /** ... or without a kind 2 (signal state) reception. */
  val NoSignalsMs: Long = 60_000L
  /** ... or once still this long. */
  val StillMs: Long = 5 * 60_000L
  /** 「受信の試験」. */
  val TestMs: Long = 60_000L
  /** The report delay of a batched scan (Android's minimum). */
  val BatchDelayMs: Long = 5_000L

  /** The nearest known site from the latest fix: (distance − accuracy) and when the fix came (elapsed ms). */
  final case class Near(clearanceM: Double, fixAt: Long)

  /** What the plan is made from. Times are monotonic (elapsedRealtime, ms). */
  final case class Input(
      now: Long,
      /** the user's switch */
      enabled: Boolean,
      /** BLUETOOTH_SCAN granted */
      permission: Boolean,
      bluetooth: Boolean,
      /** the logger runs */
      logging: Boolean,
      /** of the latest fix (m/s); None: unknown, taken as walking */
      speed: Option[Double],
      /** the current leg is classified as train, subway or flight */
      railOrAir: Boolean,
      /** since when the logger has been still (its motion estimate) */
      stillSince: Option[Long],
      near: Option[Near],
      /** the last qualifying reception, and the last of kind 2 */
      lastQualifying: Option[Long],
      lastSignals: Option[Long],
      /** 「受信の試験」 running until then */
      testUntil: Option[Long] = None,
      screenOn: Boolean = true,
      /** offloaded scan batching (isOffloadedScanBatchingSupported) */
      batching: Boolean = false)

  /** The state wanted, and the scan for it. `since`: when this state was
    * entered; `exitedAt`, `exitNeedsSignals`: the last end of receiving,
    * and whether only a kind 2 reception starts it again (it ended for
    * want of one).
    */
  final case class Plan(state: State, scan: PicsScan, reportDelayMs: Long, since: Long, why: String,
                        exitedAt: Long = Long.MinValue, exitNeedsSignals: Boolean = false)

  val Initial: Plan = Plan(State.Stopped, PicsScan.Off, 0L, Long.MinValue, "開始前")

  private def recent(t: Option[Long], now: Long, ms: Long) = t.exists(x => now - x <= ms)

  def plan(in: Input, prev: Plan): Plan =
    val now = in.now
    def to(s: State, why: String, exited: Long = prev.exitedAt, needSignals: Boolean = prev.exitNeedsSignals): Plan =
      val scan = s match
        case State.Stopped       => PicsScan.Off
        case State.Opportunistic => PicsScan.Opportunistic
        case State.Standby       => PicsScan.LowPower
        case State.Receiving     => if !in.screenOn && in.batching then PicsScan.BalancedBatch else PicsScan.Balanced
        case State.Test          => PicsScan.LowLatency
      Plan(s, scan, if scan == PicsScan.BalancedBatch then BatchDelayMs else 0L, if prev.state == s then prev.since else now,
        why, exited, needSignals)
    // Before everything, the test too.
    if !in.permission then to(State.Stopped, "権限なし")
    else if !in.bluetooth then to(State.Stopped, "Bluetooth がオフ")
    else if !in.logging then to(State.Stopped, "記録が停止")
    else if in.testUntil.exists(now < _) then to(State.Test, "受信の試験")
    else if !in.enabled then to(State.Stopped, "機能がオフ")
    else if in.railOrAir then to(State.Stopped, "電車・地下鉄・飛行機")
    else if in.speed.exists(_ > MaxSpeed) then to(State.Stopped, f"速さ ${in.speed.get}%.0f m/s")
    else
      val stillFor = in.stillSince.map(now - _)
      val still = stillFor.isDefined
      val fresh = in.near.filter(n => now - n.fixAt <= FixMaxAgeMs)
      val wasNear = prev.state == State.Standby || prev.state == State.Receiving
      val nearNow = fresh.exists(n => n.clearanceM <= StandbyM || wasNear && n.clearanceM < StandbyExitM)
      if prev.state == State.Receiving then
        // Receiving goes on while still, up to StillMs.
        val sigFrom = in.lastSignals.fold(prev.since)(math.max(_, prev.since))
        if stillFor.exists(_ >= StillMs) then
          leave(prev, "静止が 5 分", now, needSignals = false, standby = false, to)
        else if !recent(in.lastQualifying, now, QuietMs) then
          leave(prev, "20 秒受信なし", now, needSignals = false, standby = nearNow && !still, to)
        else if now - sigFrom > NoSignalsMs then
          leave(prev, "60 秒信号の受信なし", now, needSignals = true, standby = nearNow && !still, to)
        else to(State.Receiving, prev.why)
      else
        // A new scan is not started while still (design 3.1).
        val again = in.lastQualifying.exists(t => t > prev.exitedAt && now - t <= QuietMs) &&
          (!prev.exitNeedsSignals || in.lastSignals.exists(_ > prev.exitedAt))
        if again && !still then to(State.Receiving, "条件を満たす受信")
        else if nearNow && !still then to(State.Standby, f"交差点まで ${fresh.get.clearanceM.max(0)}%.0f m")
        else to(State.Opportunistic, if still then "静止" else "速さの条件の内")

  private def leave(prev: Plan, why: String, now: Long, needSignals: Boolean, standby: Boolean,
                    to: (State, String, Long, Boolean) => Plan): Plan =
    to(if standby then State.Standby else State.Opportunistic, why, now, needSignals)

  /** Whether the recent positions are a train, subway or flight leg: the
    * timeline's leg classification ([[ModeClassifier]]) over the last
    * `windowMs` of fixes and motion windows, while the motion estimate
    * says vehicle. Cheap enough per fix (at most a few hundred fixes).
    */
  def railOrAir(fixes: Seq[Fix], windows: Seq[MotionWindow], now: Long, windowMs: Long = 5 * 60_000L): Boolean =
    val fs = fixes.filter(f => f.time > now - windowMs && f.time <= now).sortBy(_.time).toIndexedSeq
    if fs.size < 5 || fs.last.time - fs.head.time < 60_000L then false
    else
      val iv = fs.indices.drop(1).map { i =>
        val (a, b) = (fs(i - 1), fs(i))
        val dt = math.max(1L, b.time - a.time)
        val d = Geo.distance(a, b)
        val v = if a.hasSpeed && b.hasSpeed && dt < 30_000 then (a.speed + b.speed) / 2.0 else d / (dt / 1000.0)
        (v, dt, d, dt > 60_000 && d / (dt / 1000.0) > 2.5)
      }
      val dur = fs.last.time - fs.head.time
      val moving = iv.filterNot(_._4).sortBy(_._1)
      def q(p: Double): Double =
        if moving.isEmpty then 0.0
        else
          val total = moving.map(_._2).sum.toDouble
          var acc = 0.0
          moving.find { m => acc += m._2; acc >= p * total }.getOrElse(moving.last)._1
      val ws = windows.filter(w => w.end > now - windowMs && w.start <= now)
      val wMs = ws.map(w => w.end - w.start).sum
      val cadence = if wMs <= 0 then Double.NaN else ws.map(_.steps).sum / (wMs / 60_000.0)
      val std = if ws.isEmpty then Double.NaN else ws.map(_.accelStd.toDouble).filterNot(_.isNaN).sum / ws.size
      val f = LegFeatures(dur, iv.map(_._3).sum, q(0.5), q(0.9), moving.lastOption.fold(0.0)(_._1), cadence, std,
        moving.filter(_._1 < 1.0).map(_._2).sum.toDouble / dur, iv.filter(_._4).map(_._2).sum.toDouble / dur)
      ModeClassifier.classify(f)._1 match
        case TransportMode.Train | TransportMode.Subway | TransportMode.Flying => true
        case _                                                                 => false

/** Brings the registered scan to the one wanted (design 3.1): one
  * registration at a time; a start, by any path (a state change, the
  * reopening, a retry, the test), at most once in [[MinStartGapMs]], a
  * change held until then is made after; a stop at once. A failed start is
  * tried again after 30 s (too frequent) or after 1 min doubling up to
  * 30 min. A receiving scan is reopened after 8 min (Android lowers a long
  * filtered scan at 10 min; whether reopening undoes it is not known).
  * Monotonic times (ms). Not thread-safe.
  */
final class PicsScanGate:
  import PicsScanGate.*
  private var reg: Option[(PicsScan, Long, Long)] = None
  private var lastStart = Long.MinValue / 2
  private var retryAt = Long.MinValue
  private var failures = 0
  private var lastFailure: Option[(Long, Int)] = None

  /** The scan registered now, the time it started and its report delay. */
  def registered: Option[(PicsScan, Long, Long)] = reg
  def failureCount: Int = failures
  /** The last failure: when, and its code. */
  def failure: Option[(Long, Int)] = lastFailure
  /** Starts are held until then (a retry). */
  def retryTime: Option[Long] = Option.when(retryAt != Long.MinValue)(retryAt)

  /** What to do now to reach `want` (with `delayMs`): `stop` the scan
    * registered, then `start` one; `next`: when to step again (a held start
    * or the reopening).
    */
  def step(now: Long, want: PicsScan, delayMs: Long = 0L): Decision =
    // A start that held a minute without failing: the back-off starts over.
    reg.foreach((_, at, _) => if now - at >= 60_000L then failures = 0)
    if want == PicsScan.Off then
      val had = reg.isDefined
      reg = None
      Decision(stop = had, start = None, next = None)
    else
      val reopen = reg.exists((s, at, _) => s == want && Reopened(s) && now - at >= ReopenMs)
      val need = reopen || !reg.exists((s, _, d) => s == want && d == delayMs)
      if !need then Decision(false, None, reg.collect { case (s, at, _) if Reopened(s) => at + ReopenMs })
      else
        val allowed = math.max(lastStart + MinStartGapMs, retryAt)
        if now < allowed then Decision(false, None, Some(allowed))
        else
          val had = reg.isDefined
          lastStart = now
          reg = Some((want, now, delayMs))
          Decision(stop = had, start = Some(want), next = Option.when(Reopened(want))(now + ReopenMs))

  /** The start of the registered scan failed (onScanFailed `code`). */
  def failed(now: Long, code: Int): Unit =
    reg = None
    lastFailure = Some((now, code))
    if code == TooFrequently then retryAt = now + 30_000L
    else
      retryAt = now + math.min(MaxBackoffMs, 60_000L << math.min(failures, 10))
      failures += 1

  /** The registration is gone without a stop (Bluetooth turned off). */
  def lost(): Unit = reg = None

object PicsScanGate:
  final case class Decision(stop: Boolean, start: Option[PicsScan], next: Option[Long])
  /** Android refuses more than 5 starts in 30 s (AOSP's default). */
  val MinStartGapMs: Long = 10_000L
  val ReopenMs: Long = 8 * 60_000L
  val MaxBackoffMs: Long = 30 * 60_000L
  /** ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY. */
  val TooFrequently = 6
  /** The scans that are reopened (filtered scans Android may lower). */
  val Reopened: Set[PicsScan] = Set(PicsScan.Balanced, PicsScan.BalancedBatch, PicsScan.LowLatency)

/** How a scan is stopped (design 3.2): a batched scan's controller may
  * still hold results, and `flushPendingScanResults` hands them over
  * later (asynchronously), to the same callback as the batches it delivers
  * anyway: a delivery cannot tell the flushed results from a batch that was
  * on its way before. So the scan is stopped only when the whole window
  * after the flush is over ([[FlushWindow]]), every delivery in it taken;
  * one after the stop is still taken by the callback. Every stop goes this
  * way: a change of scan, the reopening, the end of the logger.
  */
object PicsScanStop:
  val TimeoutMs: Long = 2_000L

  /** `waitFor(ms)`: waits that long, the callback taking what comes. */
  def run(batched: Boolean, flush: () => Unit, waitFor: Long => Unit, stop: () => Unit, timeoutMs: Long = TimeoutMs): Unit =
    if batched then
      flush()
      waitFor(timeoutMs)
    stop()

  /** The window after a flush (ms): no delivery ends it early, the first
    * one may be a batch of before the flush.
    */
  final class FlushWindow(flushAt: Long, timeoutMs: Long = TimeoutMs):
    private var n = 0
    def delivered(at: Long): Unit = if at >= flushAt then n += 1
    def deliveries: Int = n
    def endsAt: Long = flushAt + timeoutMs
    def over(now: Long): Boolean = now >= endsAt

/** What the status row shows of an intersection (design 6, 7.5), on the
  * monotonic clock: the signals' colours only for [[FreshMs]] after their
  * reception (grey at exactly that time, not at the next redraw), the
  * signals only while receiving, the row for [[ShownMs]] after the last
  * reception. `nextAt`: when it changes next without a new reception.
  */
object PicsDisplay:
  val FreshMs: Long = 2_000L
  val ShownMs: Long = 10 * 60_000L

  final case class View(signals: Boolean, fresh: Boolean, nextAt: Long)

  def at(now: Long, receiving: Boolean, signalsAt: Long, lastAt: Long): Option[View] =
    if !receiving && now - lastAt > ShownMs then None
    else
      val signals = receiving && signalsAt > 0 && now - signalsAt <= PicsScanPolicy.QuietMs
      val fresh = signals && now - signalsAt < FreshMs
      val next =
        if fresh then signalsAt + FreshMs
        else if signals then signalsAt + PicsScanPolicy.QuietMs + 1
        else lastAt + ShownMs + 1
      Some(View(signals, fresh, math.max(next, now + 1)))
