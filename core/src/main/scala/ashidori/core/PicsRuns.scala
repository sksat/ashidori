package ashidori.core

import java.time.{Instant, ZoneId}
import scala.collection.mutable

/** One row of `pics_adv` (docs/design/pics.md, 4.1): a run of receptions
  * of one flow (sender, kind, intersection ID) with the same content.
  * Times are ms since the epoch; RSSI in dBm. `iid`, `kind`, `msg`: null
  * (None) when the packet is too short for them. `data`: the first
  * reception's data in hex, whatever its length. `scan`: the scan it was
  * received in ([[PicsScan]] names, or `debug` for an injection).
  */
final case class PicsAdv(start: Long, end: Long, addr: String, iid: Option[String], kind: Option[Int], msg: Option[Int],
                         msgLast: Option[Int], msgChanges: Int, n: Int, rssiMax: Int, rssiMaxAt: Long, rssiMin: Int,
                         data: String, scan: String)

/** One advertisement as the scanner hands it over: `at` (ms since the
  * epoch) from the result's own timestamp, `data` without the company ID.
  */
final case class PicsResult(at: Long, addr: String, company: Int, data: IndexedSeq[Byte], rssi: Int, scan: String):
  lazy val packet: PicsFormat.Packet = PicsFormat.Packet(data)

object PicsRuns:
  /** The acceptance caps (design 4.1), applied before a row goes to the
    * write path: each run is one row, so a reception that would open a run
    * is refused (and counted) when a cap is reached.
    */
  final case class Caps(
      /** rows per sender: per second, and the burst allowed */
      perSourcePerSec: Double = 2.0, perSourceBurst: Double = 20.0,
      /** rows of all senders: per second (and burst), per local day */
      totalPerSec: Double = 10.0, totalBurst: Double = 10.0, perDay: Int = 20_000,
      /** open runs */
      open: Int = 64,
      /** senders remembered (the least recent forgotten) */
      tracked: Int = 256,
      /** new senders a minute */
      newPerMinute: Int = 32,
      /** rows of one 「受信の試験」 */
      testRows: Int = 1_000)

  /** A run closes this long after its first reception (still: [[StillMaxMs]]). */
  val MaxMs: Long = 5_000L
  /** While still (design 4.1, 静止中の長さ): repeats of the same content
    * near a signal all day stay a few rows a minute.
    */
  val StillMaxMs: Long = 60_000L
  /** A run closes when nothing came for this long. */
  val GapMs: Long = 3_000L

  /** Why a reception was not written. */
  enum Refused(val key: String, val label: String):
    case Queue extends Refused("queue", "待ちの上限")
    case NewSenders extends Refused("new", "新しい送信元の上限")
    case Open extends Refused("open", "開いている連なりの上限")
    case Day extends Refused("day", "1 日の上限")
    case Source extends Refused("source", "送信元ごとの上限")
    case Total extends Refused("total", "全体の上限")
    case Test extends Refused("test", "試験の上限")

/** Builds the runs of `pics_adv` (design 4.1) from receptions, applying
  * the acceptance caps. Not thread-safe: one thread (`ashidori-pics`) uses
  * it. Closed rows are returned by each call, never changed afterwards.
  */
final class PicsRuns(zone: ZoneId, caps: PicsRuns.Caps = PicsRuns.Caps()):
  import PicsRuns.*

  private final case class Flow(addr: String, kind: Option[Int], iid: Option[String])

  private final class Run(val flow: Flow, r: PicsResult):
    val start: Long = r.at
    var end: Long = r.at
    val content: String = r.packet.content
    val scan: String = r.scan
    val msg: Option[Int] = r.packet.msgId
    var msgLast: Option[Int] = msg
    var msgChanges = 0
    var n = 1
    var rssiMax: Int = r.rssi
    var rssiMaxAt: Long = r.at
    var rssiMin: Int = r.rssi
    val data: String = PicsFormat.hex(r.data)
    val day: Long = dayOf(r.at)
    def add(x: PicsResult): Unit =
      end = x.at
      n += 1
      val m = x.packet.msgId
      if m != msgLast then msgChanges += 1
      msgLast = m
      if x.rssi > rssiMax then
        rssiMax = x.rssi
        rssiMaxAt = x.at
      rssiMin = math.min(rssiMin, x.rssi)
    def row: PicsAdv = PicsAdv(start, end, flow.addr, flow.iid, flow.kind, msg, msgLast, msgChanges, n, rssiMax, rssiMaxAt,
      rssiMin, data, scan)

  private val open = mutable.LinkedHashMap.empty[Flow, Run]
  /** Per sender, its token bucket (tokens, at): least recently used first. */
  private val senders = mutable.LinkedHashMap.empty[String, (Double, Long)]
  private var total: (Double, Long) = (caps.totalBurst, Long.MinValue)
  private val newAt = mutable.Queue.empty[Long]
  private var rowsDay = Long.MinValue
  private var rowsToday = 0
  private var testing = false
  private var testRows = 0
  private val refused = mutable.LinkedHashMap.empty[Refused, Long]
  private val refusedAll = mutable.LinkedHashMap.empty[Refused, Long]

  private def dayOf(t: Long): Long = Instant.ofEpochMilli(t).atZone(zone).toLocalDate.toEpochDay

  /** Runs open now. */
  def openRuns: Int = open.size
  /** Rows accepted on the local day of the last one. */
  def acceptedToday: Int = rowsToday

  /** Refused receptions since the last call (for the lifecycle log, once a minute). */
  def takeRefused(): Map[Refused, Long] =
    val m = refused.toMap
    refused.clear()
    m

  /** Refused receptions since the start, by reason. */
  def refusedTotal: Map[Refused, Long] = refusedAll.toMap

  /** Counts a reception refused before it got here (the queue was full). */
  def refuse(why: Refused, n: Long = 1): Unit =
    refused(why) = refused.getOrElse(why, 0L) + n
    refusedAll(why) = refusedAll.getOrElse(why, 0L) + n

  /** 「受信の試験」: each reception is a row of its own (`n` 1), under the
    * test's own cap. Open runs are closed first (returned).
    */
  def setTest(on: Boolean): Seq[PicsAdv] =
    val closed = closeAll()
    testing = on
    testRows = 0
    closed

  private def bucket(b: (Double, Long), at: Long, rate: Double, burst: Double): Double =
    if b._2 == Long.MinValue then burst else math.min(burst, b._1 + math.max(0L, at - (if at < b._2 - 60_000L then at else b._2)) * rate / 1000.0)

  /** A bucket's new time: never back by a little (a batch out of order
    * refills nothing twice); back by over a minute (the clock was set back,
    * results stamped long ago), it starts again from there.
    */
  private def since(prev: Long, at: Long): Long = if at < prev - 60_000L then at else math.max(prev, at)

  /** Whether a row of `r` may be written; takes its share of the caps if so. */
  private def admit(r: PicsResult, now: Long): Boolean =
    if testing then
      if testRows >= caps.testRows then { refuse(Refused.Test); false }
      else { testRows += 1; true }
    else
      while newAt.nonEmpty && (now - newAt.head >= 60_000L || newAt.head - now > 60_000L) do newAt.dequeue()
      val known = senders.contains(r.addr)
      val day = dayOf(r.at)
      if day != rowsDay then
        rowsDay = day
        rowsToday = 0
      val src = senders.get(r.addr).map(bucket(_, now, caps.perSourcePerSec, caps.perSourceBurst)).getOrElse(caps.perSourceBurst)
      val all = bucket(total, now, caps.totalPerSec, caps.totalBurst)
      val why =
        if !known && newAt.size >= caps.newPerMinute then Some(Refused.NewSenders)
        else if open.size >= caps.open then Some(Refused.Open)
        else if rowsToday >= caps.perDay then Some(Refused.Day)
        else if src < 1 then Some(Refused.Source)
        else if all < 1 then Some(Refused.Total)
        else None
      why match
        case Some(w) =>
          refuse(w)
          // A sender refused is still remembered (its bucket keeps refilling).
          if known then touch(r.addr, (src, since(senders(r.addr)._2, now)))
          if total._2 != Long.MinValue then total = (all, since(total._2, now))
          false
        case None =>
          if !known then newAt.enqueue(now)
          touch(r.addr, (src - 1, since(senders.get(r.addr).fold(now)(_._2), now)))
          total = (all - 1, if total._2 == Long.MinValue then now else since(total._2, now))
          rowsToday += 1
          true

  private def touch(addr: String, b: (Double, Long)): Unit =
    senders.remove(addr)
    senders(addr) = b
    while senders.size > caps.tracked do senders.remove(senders.head._1)

  private def flowOf(r: PicsResult) = Flow(r.addr, r.packet.kind, r.packet.intersection)

  /** Receptions (any order: a batch of results is not in time order). The
    * rows closed by them come back. The rate caps run on the results' own
    * times.
    */
  def add(results: Seq[PicsResult], still: Boolean): Seq[PicsAdv] =
    val out = Vector.newBuilder[PicsAdv]
    val maxMs = if still then StillMaxMs else MaxMs
    for r <- results.sortBy(_.at) do
      val f = flowOf(r)
      if testing then
        if admit(r, r.at) then out += Run(f, r).row
      else open.get(f) match
        case Some(run) if r.at < run.end =>
          // Older than the open run (a late result): a run of its own, closed now.
          if admit(r, r.at) then out += Run(f, r).row
        case Some(run) if r.packet.content == run.content && r.scan == run.scan && r.at - run.start < maxMs &&
            r.at - run.end <= GapMs && dayOf(r.at) == run.day =>
          run.add(r)
        case Some(run) =>
          open.remove(f)
          out += run.row
          if admit(r, r.at) then open(f) = Run(f, r)
        case None =>
          if admit(r, r.at) then open(f) = Run(f, r)
    out.result()

  /** Closes the runs that are over at `now`: none for [[GapMs]] (plus the
    * scan's report delay, for results still held by the controller), past
    * their longest, or of an earlier day.
    */
  def tick(now: Long, still: Boolean, delayMs: Long = 0L): Seq[PicsAdv] =
    val maxMs = if still then StillMaxMs else MaxMs
    val today = dayOf(now)
    val over = open.values.filter(r => now - r.end > GapMs + delayMs || now - r.start >= maxMs || r.day != today).toVector
    over.foreach(r => open.remove(r.flow))
    over.map(_.row)

  /** Closes every run (the scan changed or stopped). */
  def closeAll(): Seq[PicsAdv] =
    val rows = open.values.map(_.row).toVector
    open.clear()
    rows
