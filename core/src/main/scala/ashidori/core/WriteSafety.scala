package ashidori.core

/** Accounting of the logger's queue of rows not yet in SQLite.
  *
  * Every batch is counted from the moment it is handed over until it is
  * committed. The queue is bounded: a batch that would push its class over
  * its cap is dropped (and counted) instead of growing memory until the
  * process dies, which would lose everything queued. Raw signal lines are
  * bulky and capped low; positions, motion, battery and GNSS status are
  * tiny and capped high. Not thread-safe: the app synchronizes.
  */
final class WriteBacklog(maxRaw: Int = WriteBacklog.MaxRawRows, maxOther: Int = WriteBacklog.MaxOtherRows,
                         noteEveryMs: Long = WriteBacklog.NoteEveryMs, slowMs: Long = WriteBacklog.SlowMs):
  private var pendingRaw = 0L
  private var pendingOther = 0L
  private var droppedRaw = 0L
  private var droppedOther = 0L
  private var slowest = 0L
  private var lastNote = Long.MinValue / 2

  def pending: Long = pendingRaw + pendingOther

  /** A batch of `rows` is about to be queued; false if it must be dropped. */
  def offer(rows: Int, raw: Boolean): Boolean =
    if raw then
      if pendingRaw + rows > maxRaw then { droppedRaw += rows; false }
      else { pendingRaw += rows; true }
    else if pendingOther + rows > maxOther then { droppedOther += rows; false }
    else { pendingOther += rows; true }

  /** A queued batch left the queue (committed, or given up after `lost`). */
  def done(rows: Int, raw: Boolean, waitedMs: Long, lost: Boolean = false): Unit =
    if raw then pendingRaw -= rows else pendingOther -= rows
    if lost then if raw then droppedRaw += rows else droppedOther += rows
    slowest = math.max(slowest, waitedMs)

  /** A line for the lifecycle log when rows were dropped, or a batch waited
    * longer than `slowMs`, since the last note; at most every `noteEveryMs`.
    */
  def note(now: Long): Option[String] =
    if droppedRaw + droppedOther == 0 && slowest < slowMs then None
    else if now - lastNote < noteEveryMs then None
    else
      val parts = Seq(
        Option.when(droppedOther > 0)(s"位置・動きなど ${droppedOther} 行を保存できず"),
        Option.when(droppedRaw > 0)(s"詳細ログ ${droppedRaw} 行を保存できず"),
        Option.when(slowest >= slowMs)(s"保存待ち最大 ${slowest / 1000} 秒"),
        Some(s"待ち $pending 行")
      ).flatten
      droppedRaw = 0
      droppedOther = 0
      slowest = 0
      lastNote = now
      Some(parts.mkString("・"))

object WriteBacklog:
  /** About a minute of raw lines when logging everything (~400 lines/s);
    * some 20 MB of strings.
    */
  val MaxRawRows = 30_000
  /** Days of positions at 1 Hz: never reached unless SQLite is unwritable. */
  val MaxOtherRows = 200_000
  val NoteEveryMs: Long = 10 * 60_000L
  val SlowMs: Long = 30_000L

/** Whether the logger stopped persisting: no row committed for longer than
  * the current plan explains. Measured in awake time (uptime), so a phone
  * asleep in a drawer is not a stall: while awake the service writes a
  * battery row every 5 minutes at the least.
  */
object WriteStall:
  val MinMs: Long = 15 * 60_000L
  val MarginMs: Long = 5 * 60_000L

  def thresholdMs(intervalMs: Long, maxBatchDelayMs: Long): Long =
    math.max(MinMs, 2 * math.max(intervalMs, maxBatchDelayMs) + MarginMs)

  def stalled(lastPersistUptime: Long, nowUptime: Long, thresholdMs: Long): Boolean =
    nowUptime - lastPersistUptime > thresholdMs

/** Repeated crashes of the main process (where the logger runs): optional
  * background work (archive maintenance, sync) is switched off until the app
  * is updated or the user runs it by hand, so it cannot keep killing
  * the logger.
  */
object CrashLoop:
  val WindowMs: Long = 6 * 3_600_000L
  val Crashes = 3

  /** `crashes`: times of the main process's crashes; only those after
    * `since` (the install or update, or the last manual reset) count.
    */
  def tripped(crashes: Seq[Long], now: Long, since: Long): Boolean =
    crashes.count(t => t > since && t > now - WindowMs && t <= now) >= Crashes
