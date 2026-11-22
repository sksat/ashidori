package ashidori.core

import java.time.{Instant, ZoneId}
import scala.collection.mutable

/** One row of `pics_adv` (docs/design/pics.md, 4.1): a run of receptions
  * of one flow (sender, kind, intersection ID) with the same content, or
  * (`parts` > 1) runs of a kind 2 flow folded into one row, whose content
  * differs only in the signal bytes ([[PicsRuns.Fold]]).
  *
  * Times are ms since the epoch; RSSI in dBm. `iid`, `kind`, `msg`: null
  * (None) when the packet is too short for them. `data`: the first
  * reception's data in hex, whatever its length. `scan`: the scan it was
  * received in ([[PicsScan]] names, or `debug` for an injection).
  *
  * A folded row's columns summarize all its runs (the first start and
  * data, the last end and message ID, the sums and extremes); `sub` holds
  * every run, so that each is restored exactly ([[PicsAdv.expand]]).
  */
final case class PicsAdv(start: Long, end: Long, addr: String, iid: Option[String], kind: Option[Int], msg: Option[Int],
                         msgLast: Option[Int], msgChanges: Int, n: Int, rssiMax: Int, rssiMaxAt: Long, rssiMin: Int,
                         data: String, scan: String, parts: Int = 1, sub: Option[String] = None):
  /** The runs it holds: itself, or each folded run as it was. */
  def expand: Seq[PicsAdv] = sub.filter(_ => parts > 1).fold(Seq(this))(PicsAdv.unfold(this, _))

object PicsAdv:
  /** Where the signal bytes are (kind 2: one byte per crosswalk, design 2.2). */
  val SignalFrom = 10
  val SignalTo = 16

  private def oi(s: String): Option[Int] = Option.when(s.nonEmpty)(s.toInt)

  /** One row of `runs` (of one flow and scan, in time order, each a run
    * of its own): the summary columns and `sub`, every run as
    * `start,end,msg,msg_last,msg_changes,n,rssi_max,rssi_max_at,rssi_min,signals`
    * with the times as offsets from the row's start and `signals` the hex
    * of bytes 10–15 (as far as the data goes), runs separated by `;`.
    */
  def fold(runs: Seq[PicsAdv]): PicsAdv =
    require(runs.nonEmpty && runs.forall(_.parts == 1))
    if runs.size == 1 then runs.head
    else
      val a = runs.head
      val z = runs.last
      val s0 = a.start
      val max = runs.map(_.rssiMax).max
      // Byte 3 changed within the runs, and between one run's last and the next one's first.
      val between = runs.sliding(2).count { case Seq(x, y) => x.msgLast != y.msg; case _ => false }
      val sub = runs.map { r =>
        val sig = r.data.slice(2 * SignalFrom, 2 * SignalTo)
        Seq(r.start - s0, r.end - s0, r.msg.fold("")(_.toString), r.msgLast.fold("")(_.toString), r.msgChanges, r.n,
          r.rssiMax, r.rssiMaxAt - s0, r.rssiMin, sig).mkString(",")
      }.mkString(";")
      PicsAdv(s0, z.end, a.addr, a.iid, a.kind, a.msg, z.msgLast, runs.map(_.msgChanges).sum + between, runs.map(_.n).sum,
        max, runs.find(_.rssiMax == max).get.rssiMaxAt, runs.map(_.rssiMin).min, a.data, a.scan, runs.size, Some(sub))

  /** The runs of a folded row `a` from its `sub` ([[fold]]). */
  private def unfold(a: PicsAdv, sub: String): Seq[PicsAdv] =
    sub.split(';').toSeq.map { p =>
      val f = p.split(",", -1)
      val m = oi(f(2))
      // The run's own data: the first's, with its byte 3 and signal bytes.
      val d = StringBuilder(a.data)
      m.foreach(x => if d.length >= 8 then d.replace(6, 8, f"$x%02X"))
      d.replace(2 * SignalFrom, 2 * SignalFrom + f(9).length, f(9))
      PicsAdv(a.start + f(0).toLong, a.start + f(1).toLong, a.addr, a.iid, a.kind, m, oi(f(3)), f(4).toInt, f(5).toInt,
        f(6).toInt, a.start + f(7).toLong, f(8).toInt, d.toString, a.scan)
    }

/** One advertisement as the scanner hands it over: `at` (ms since the
  * epoch) from the result's own timestamp, `data` without the company ID.
  */
final case class PicsResult(at: Long, addr: String, company: Int, data: IndexedSeq[Byte], rssi: Int, scan: String):
  lazy val packet: PicsFormat.Packet = PicsFormat.Packet(data)

object PicsRuns:
  /** The acceptance caps (design 4.1), applied before a row goes to the
    * write path: a reception that would open a row is refused (and
    * counted) when a cap is reached. Runs folded into an open row are not
    * new rows.
    */
  final case class Caps(
      /** rows per sender: per second, and the burst allowed */
      perSourcePerSec: Double = 2.0, perSourceBurst: Double = 20.0,
      /** rows of all senders: per second (and burst), per local day */
      totalPerSec: Double = 10.0, totalBurst: Double = 10.0, perDay: Int = 20_000,
      /** open rows */
      open: Int = 64,
      /** senders remembered (the least recent forgotten) */
      tracked: Int = 256,
      /** new senders a minute */
      newPerMinute: Int = 32,
      /** rows of one 「受信の試験」 */
      testRows: Int = 1_000)

  /** Which runs of a kind 2 flow share a row (design 4.1, 連なりの折り畳み):
    * consecutive runs whose data differ only in byte 3 and in the signal
    * bytes (10–15) as the mode allows; each run is kept whole in `sub`.
    */
  enum Fold:
    /** Every run a row (the format of each run; used to check the others). */
    case Off
    /** Only the remaining-time gauges (the high four bits) differ. */
    case Gauge
    /** The signal bytes differ: states and gauges. */
    case Signals

  /** A row closes this long after its first reception (still: [[StillMaxMs]]). */
  val MaxMs: Long = 5_000L
  /** While still (design 4.1, 静止中の長さ): repeats of the same content
    * near a signal all day stay a few rows a minute.
    */
  val StillMaxMs: Long = 60_000L
  /** A row closes when nothing came for this long. */
  val GapMs: Long = 3_000L
  /** Runs in one row at most. */
  val MaxParts = 64

  /** Why a reception was not written. */
  enum Refused(val key: String, val label: String):
    case Queue extends Refused("queue", "待ちの上限")
    case NewSenders extends Refused("new", "新しい送信元の上限")
    case Open extends Refused("open", "開いている連なりの上限")
    case Day extends Refused("day", "1 日の上限")
    case Source extends Refused("source", "送信元ごとの上限")
    case Total extends Refused("total", "全体の上限")
    case Test extends Refused("test", "試験の上限")

/** Builds the rows of `pics_adv` (design 4.1) from receptions, applying
  * the acceptance caps. Not thread-safe: one thread (`ashidori-pics`) uses
  * it. Closed rows are returned by each call, never changed afterwards.
  */
final class PicsRuns(zone: ZoneId, caps: PicsRuns.Caps = PicsRuns.Caps(), fold: PicsRuns.Fold = PicsRuns.Fold.Signals):
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
    val data: IndexedSeq[Byte] = r.data
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
      rssiMin, PicsFormat.hex(data), scan)

  /** An open row: its runs; the last one open unless `lastClosed`. A row
    * takes a new run while it is younger than the run's longest (5 s, 60 s
    * still), so it holds at most that and one run more.
    */
  private final class Open(first: Run):
    val runs = mutable.ArrayBuffer(first)
    var lastClosed = false
    def last: Run = runs.last
    def start: Long = runs.head.start
    def day: Long = runs.head.day
    def flow: Flow = first.flow
    def row: PicsAdv = PicsAdv.fold(runs.map(_.row).toSeq)

  /** Whether `r` may start a new run in the row whose last run is `a`
    * (kind 2, the same bytes but byte 3 and the signal bytes; the same
    * content too: a run closed by its length or a gap).
    */
  private def foldable(a: Run, r: PicsResult): Boolean =
    val b = r.data
    fold != Fold.Off && a.flow.kind.contains(2) && b.size == a.data.size && b.size > PicsAdv.SignalFrom &&
      b.indices.forall { i =>
        if i == 3 then true
        else if i >= PicsAdv.SignalFrom && i < PicsAdv.SignalTo then
          fold == Fold.Signals || (a.data(i) & 0x0f) == (b(i) & 0x0f)
        else a.data(i) == b(i)
      }

  private val open = mutable.LinkedHashMap.empty[Flow, Open]
  /** Per sender, its token bucket (tokens, at): least recently used first. */
  private val senders = mutable.LinkedHashMap.empty[String, (Double, Long)]
  private var total: (Double, Long) = (caps.totalBurst, Long.MinValue)
  private val newAt = mutable.Queue.empty[Long]
  /** Rows accepted per local day (epoch day): the newest few days. */
  private val dayRows = mutable.TreeMap.empty[Long, Int]
  private var testing = false
  private var testRows = 0
  private val refused = mutable.LinkedHashMap.empty[Refused, Long]
  private val refusedAll = mutable.LinkedHashMap.empty[Refused, Long]

  private def dayOf(t: Long): Long = Instant.ofEpochMilli(t).atZone(zone).toLocalDate.toEpochDay

  /** Rows open now. */
  def openRuns: Int = open.size
  /** Rows accepted on the newest day seen. */
  def acceptedToday: Int = dayRows.lastOption.fold(0)(_._2)
  /** Rows accepted on the local day of `t`. */
  def acceptedOn(t: Long): Int = dayRows.getOrElse(dayOf(t), 0)
  def isTesting: Boolean = testing

  /** Rows of the day of `t` stored before this started (by an earlier
    * process): the day's cap counts them ([[Caps.perDay]]).
    */
  def seedDay(t: Long, rows: Int): Unit =
    val d = dayOf(t)
    dayRows(d) = math.max(dayRows.getOrElse(d, 0), rows)
    trimDays()

  private def trimDays(): Unit = while dayRows.size > 4 do dayRows.remove(dayRows.head._1)

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
    * test's own cap. Open rows are closed first (returned).
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
      // The cap of the result's own day: a late result of yesterday counts there, not today.
      val day = dayOf(r.at)
      val rowsOfDay = dayRows.getOrElse(day, 0)
      val src = senders.get(r.addr).map(bucket(_, now, caps.perSourcePerSec, caps.perSourceBurst)).getOrElse(caps.perSourceBurst)
      val all = bucket(total, now, caps.totalPerSec, caps.totalBurst)
      val why =
        if !known && newAt.size >= caps.newPerMinute then Some(Refused.NewSenders)
        else if open.size >= caps.open then Some(Refused.Open)
        else if rowsOfDay >= caps.perDay then Some(Refused.Day)
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
          dayRows(day) = rowsOfDay + 1
          trimDays()
          true

  private def touch(addr: String, b: (Double, Long)): Unit =
    senders.remove(addr)
    senders(addr) = b
    while senders.size > caps.tracked do senders.remove(senders.head._1)

  private def flowOf(r: PicsResult) = Flow(r.addr, r.packet.kind, r.packet.intersection)

  /** Receptions (any order: a batch of results is not in time order). The
    * rows closed by them come back. The rate caps run on the results' own
    * times. The runs are the same whatever the [[Fold]]: a run closes by
    * its own rules (content, length, gap, scan, day), and only the rows
    * that hold them differ.
    */
  def add(results: Seq[PicsResult], still: Boolean): Seq[PicsAdv] =
    val out = Vector.newBuilder[PicsAdv]
    val maxMs = if still then StillMaxMs else MaxMs
    for r <- results.sortBy(_.at) do
      val f = flowOf(r)
      if testing then
        if admit(r, r.at) then out += Run(f, r).row
      else open.get(f) match
        case Some(o) if r.at < o.last.end =>
          // Older than the open row (a late result): a row of its own, closed now.
          if admit(r, r.at) then out += Run(f, r).row
        case Some(o) =>
          val l = o.last
          val sameDay = dayOf(r.at) == o.day
          if !o.lastClosed && r.packet.content == l.content && r.scan == l.scan && r.at - l.start < maxMs &&
              r.at - l.end <= GapMs && sameDay then l.add(r)
          else if fold != Fold.Off && r.scan == l.scan && sameDay && r.at - o.start < maxMs && o.runs.size < MaxParts &&
              foldable(l, r) then
            o.runs += Run(f, r)
            o.lastClosed = false
          else
            open.remove(f)
            out += o.row
            if admit(r, r.at) then open(f) = Open(Run(f, r))
        case None =>
          if admit(r, r.at) then open(f) = Open(Run(f, r))
    out.result()

  /** Closes what is over at `now`: a run after [[GapMs]] without a
    * reception (plus the scan's report delay, for results the controller
    * still holds), past its longest, or of an earlier day; its row too,
    * unless the row may still take another run.
    */
  def tick(now: Long, still: Boolean, delayMs: Long = 0L): Seq[PicsAdv] =
    val maxMs = if still then StillMaxMs else MaxMs
    val today = dayOf(now)
    for o <- open.values do
      val l = o.last
      if now - l.end > GapMs + delayMs || now - l.start >= maxMs || l.day != today then o.lastClosed = true
    val over = open.values.filter(o => o.lastClosed &&
      (fold == Fold.Off || now - o.start >= maxMs || o.day != today || o.runs.size >= MaxParts)).toVector
    over.foreach(o => open.remove(o.flow))
    over.map(_.row)

  /** Closes every row (the scan changed or stopped). */
  def closeAll(): Seq[PicsAdv] =
    val rows = open.values.map(_.row).toVector
    open.clear()
    rows
