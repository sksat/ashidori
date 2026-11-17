package ashidori.core

/** A kind of the logger's rows, as the write queue counts them: a stable
  * `key` (also kept on disk with the stall alert) and the `label` shown.
  */
enum WriteKind(val key: String, val label: String):
  case Fixes extends WriteKind("fixes", "位置")
  case Motion extends WriteKind("motion", "動き")
  case Battery extends WriteKind("battery", "電池")
  case GnssStatus extends WriteKind("gnss_status", "GNSS の状態")
  case RawLines extends WriteKind("raw_lines", "生データ")
  case RawSessions extends WriteKind("raw_sessions", "生データの区間")

object WriteKind:
  def fromKey(k: String): Option[WriteKind] = values.find(_.key == k)
  /** The label of `key` (the key itself if unknown). */
  def label(key: String): String = fromKey(key).fold(key)(_.label)

/** Accounting of the logger's queue of rows not yet in SQLite.
  *
  * Every batch is counted from the moment it is handed over until it is
  * committed. The queue is bounded: a batch that would push its class over
  * its cap is not queued. Raw signal lines are bulky and capped low (the
  * app writes such a batch to a file instead, and imports it later);
  * positions, motion, battery and GNSS status are tiny and capped high
  * (days of positions: reached only if SQLite cannot be written at all).
  * Not thread-safe: the app synchronizes.
  */
final class WriteBacklog(maxRaw: Int = WriteBacklog.MaxRawRows, maxOther: Int = WriteBacklog.MaxOtherRows,
                         noteEveryMs: Long = WriteBacklog.NoteEveryMs, slowMs: Long = WriteBacklog.SlowMs):
  private var pendingRaw = 0L
  private var pendingOther = 0L
  private var nextId = 0L
  // Batches queued and not yet done: id -> (kind, when queued (uptime)).
  private val queued = scala.collection.mutable.TreeMap.empty[Long, (String, Long)]
  // Per kind: since when data of it failed to be saved (until one of its
  // batches is saved again), and when it was last saved.
  private val failedSince = scala.collection.mutable.Map.empty[String, Long]
  private val savedAt = scala.collection.mutable.Map.empty[String, Long]
  // Since the last note.
  private var lostRaw = 0L
  private var lostOther = 0L
  private var spilled = 0L
  private var slowest = 0L
  private var lastNote = Long.MinValue / 2
  /** Totals since the start (the app keeps them on disk). */
  var totalLost = 0L
  var totalSpilled = 0L

  def pending: Long = pendingRaw + pendingOther

  /** Per kind, since when its data has not been saved (uptime): the oldest
    * batch still waiting, or the oldest that failed since its last save. A
    * kind that saves fine shows nothing, whatever the other kinds do.
    */
  def unsavedSince: Map[String, Long] =
    val waiting = queued.values.groupBy(_._1).view.mapValues(_.map(_._2).min).toMap
    (waiting.keySet ++ failedSince.keySet).map(k => k -> (waiting.get(k) ++ failedSince.get(k)).min).toMap

  /** The oldest of [[unsavedSince]]: (kind, since). */
  def oldestUnsaved: Option[(String, Long)] = unsavedSince.minByOption(_._2)

  /** Kinds saved at least once in this process. */
  def savedKinds: Set[String] = savedAt.keySet.toSet

  /** When data of `kind` was last saved (uptime), if in this process. */
  def lastSaved(kind: String): Option[Long] = savedAt.get(kind)

  /** A batch of `rows` is about to be queued: its id, or None if it is over
    * the cap (raw: spill it; other: it is lost, counted).
    */
  def offer(rows: Int, raw: Boolean, kind: String, now: Long): Option[Long] =
    val ok =
      if raw then pendingRaw + rows <= maxRaw
      else pendingOther + rows <= maxOther
    if !ok then
      if !raw then
        lostOther += rows
        totalLost += rows
        failedSince.getOrElseUpdate(kind, now)
      None
    else
      if raw then pendingRaw += rows else pendingOther += rows
      nextId += 1
      queued(nextId) = (kind, now)
      Some(nextId)

  /** A raw batch over the cap went to a file instead. */
  def spill(rows: Int): Unit =
    spilled += rows
    totalSpilled += rows

  /** A queued batch left the queue: committed, or given up (`lost`). */
  def done(id: Long, rows: Int, raw: Boolean, now: Long, lost: Boolean = false): Unit =
    if raw then pendingRaw -= rows else pendingOther -= rows
    queued.remove(id).foreach { (kind, t) =>
      slowest = math.max(slowest, now - t)
      if lost then failedSince.getOrElseUpdate(kind, t)
      else
        failedSince.remove(kind)
        savedAt(kind) = now
    }
    if lost then
      if raw then lostRaw += rows else lostOther += rows
      totalLost += rows

  /** A line for the lifecycle log when rows were lost or spilled, or a
    * batch waited longer than `slowMs`, since the last note; at most every
    * `noteEveryMs` (the totals are kept at once by the app).
    */
  def note(now: Long): Option[String] =
    if lostRaw + lostOther + spilled == 0 && slowest < slowMs then None
    else if now - lastNote < noteEveryMs then None
    else
      val parts = Seq(
        Option.when(lostOther > 0)(s"位置・動きなど ${lostOther} 行を保存できず"),
        Option.when(lostRaw > 0)(s"生データ ${lostRaw} 行を保存できず"),
        Option.when(spilled > 0)(s"生データ ${spilled} 行をファイルに退避"),
        Option.when(slowest >= slowMs)(s"保存待ち最大 ${slowest / 1000} 秒"),
        Some(s"待ち $pending 行")
      ).flatten
      lostRaw = 0
      lostOther = 0
      spilled = 0
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
  /** A batch (positions, raw lines, any) waiting longer than this is a
    * stall too, whatever else was saved meanwhile (the write retries a
    * locked database for a minute).
    */
  val OldestMs: Long = 3 * 60_000L

  /** The stall alert's state: raised when either condition holds, cleared
    * only when both are resolved (every kind saved again), not by any write
    * that succeeds.
    *
    * The app keeps the alert on disk ([[kinds]] while [[reported]]): an
    * alert raised before a restart is `restored` and cleared only once each
    * kind it named has been saved after the restart (some kind, if it named
    * none), as well as nothing being stalled now.
    */
  final class Monitor(restored: Option[Set[String]] = None):
    var reported: Boolean = restored.isDefined
    /** The kinds not saved when the alert was raised. */
    var kinds: Set[String] = restored.getOrElse(Set.empty)
    private var carried = restored.isDefined
    // The state before the change `update` last returned.
    private var before: Option[(Boolean, Set[String], Boolean)] = None

    /** Some(true): report the stall now; Some(false): it is over now.
      * `unsaved`: kinds not saved now; `saved`: kinds saved since the start.
      */
    def update(noCommit: Boolean, unsavedTooLong: Boolean, unsaved: Set[String] = Set.empty,
               saved: Set[String] = Set.empty): Option[Boolean] =
      val stalled = noCommit || unsavedTooLong
      def savedAgain = !carried || (if kinds.isEmpty then saved.nonEmpty else kinds.subsetOf(saved))
      before = Some((reported, kinds, carried))
      if stalled && !reported then
        reported = true
        carried = false
        kinds = unsaved
        Some(true)
      else if !stalled && reported && savedAgain then
        reported = false
        carried = false
        kinds = Set.empty
        Some(false)
      else
        before = None
        None

    /** The app could not carry out the change `update` just returned (post
      * or cancel the notification, or keep its state on disk): back to the
      * state before it, so the next update returns it again.
      */
    def failed(): Unit =
      before.foreach { (r, k, c) =>
        reported = r
        kinds = k
        carried = c
      }
      before = None

  def queuedTooLong(unsavedSince: Option[Long], nowUptime: Long): Boolean =
    unsavedSince.exists(nowUptime - _ > OldestMs)

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
