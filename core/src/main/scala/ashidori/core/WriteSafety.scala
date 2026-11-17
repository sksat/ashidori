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
  /** A kind as kept with a stall alert: its key, or a label an earlier
    * build kept (「位置」, 「詳細ログ」, ...).
    */
  def fromStored(s: String): Option[WriteKind] =
    fromKey(s).orElse(values.find(_.label == s)).orElse(s match
      case "詳細ログ"       => Some(RawLines)
      case "詳細ログの区間" => Some(RawSessions)
      case _                => None)
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

  /** Per kind saved in this process: when it was last saved (uptime). */
  def savedTimes: Map[String, Long] = savedAt.toMap

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
    * only when both are resolved and every kind it names has been saved
    * again, not by any write that succeeds.
    *
    * While it is up, [[kinds]] is the union of the kinds not resolved: the
    * kinds unsaved when it was raised, and every kind found unsaved while
    * it is up (a kind that stalls later). A kind leaves only once it has
    * been saved after it was added and nothing of it waits or failed since.
    *
    * The app keeps the alert on disk ([[kinds]] while [[reported]]): an
    * alert raised before a restart is `restored`, and each kind it names
    * must be saved after the restart (some kind, if it names none).
    */
  final class Monitor(restored: Option[Set[String]] = None):
    var reported: Boolean = restored.isDefined
    /** The kinds not resolved while the alert is up. */
    var kinds: Set[String] = restored.getOrElse(Set.empty)
    // Per kind in `kinds`: its last save (uptime) seen when it was added;
    // None: any save in this process resolves it (restored kinds).
    private var since: Map[String, Option[Long]] = kinds.map(_ -> None).toMap
    // Restored with no kinds (0.21.0 kept only the notification): some
    // kind must be saved in this process.
    private var anySave = restored.exists(_.isEmpty)

    /** Some(true): report the stall now; Some(false): it is over now.
      * `unsaved`: kinds not saved now (anything of them waiting or failed,
      * however recently): a kind is resolved only when it has none.
      * `adding`: the kinds the alert takes in (unsaved for a while, see
      * [[stalledKinds]]; all of `unsaved` if not given); `saved`: per kind,
      * when it was last saved in this process (uptime).
      */
    def update(noCommit: Boolean, unsavedTooLong: Boolean, unsaved: Set[String] = Set.empty,
               saved: Map[String, Long] = Map.empty, adding: Option[Set[String]] = None): Option[Boolean] =
      val stalled = noCommit || unsavedTooLong
      val taken = adding.getOrElse(unsaved)
      def add(ks: Set[String]) =
        for k <- ks if !since.contains(k) do since += k -> saved.get(k)
      def resolved(k: String) =
        !unsaved(k) && saved.get(k).exists(at => since(k).forall(at > _))
      if stalled && !reported then
        reported = true
        anySave = false
        since = Map.empty
        add(taken)
        kinds = since.keySet
        Some(true)
      else if reported then
        add(taken)
        since = since.filterNot((k, _) => resolved(k))
        kinds = since.keySet
        if anySave && saved.nonEmpty then anySave = false
        if !stalled && kinds.isEmpty && !anySave then
          reported = false
          Some(false)
        else None
      else None

  /** The alert's side effects: the notification and its state on disk.
    * Each returns whether it took effect; a failure is made good at the
    * next check ([[Alert.reconcile]]).
    */
  trait Effects:
    def posted: Boolean
    def post(): Boolean
    def cancel(): Boolean
    /** The alert kept on disk: the kinds it names while it is up. */
    def stored: Option[Set[String]]
    def store(state: Option[Set[String]]): Boolean

  /** The alert with its side effects, one check at a time. The monitor
    * decides whether it is up; every check then brings the notification
    * and its state on disk to that, whatever an earlier check managed
    * (a save that failed while the notification was posted, a cancel that
    * failed, ...). A notification posted once since the alert was raised
    * and then dismissed by the user is not posted again.
    */
  final class Alert(val monitor: Monitor, fx: Effects):
    private var shown = false
    // The state on disk is not known to match (a store failed, or none was
    // made in this process): stored again at the next check. A store that
    // failed may still have changed what `stored` reads (SharedPreferences
    // updates its memory before commit() fails), so that is not trusted.
    private var dirty = true

    /** Some(true): raised now; Some(false): cleared now (`onChange` is told,
      * once per change).
      */
    def check(noCommit: Boolean, unsavedTooLong: Boolean, unsaved: Set[String], saved: Map[String, Long],
              adding: Option[Set[String]] = None)
             (onChange: Boolean => Unit = _ => ()): Option[Boolean] = synchronized {
      val change = monitor.update(noCommit, unsavedTooLong, unsaved, saved, adding)
      change.foreach { up =>
        if up then shown = false
        onChange(up)
      }
      reconcile()
      change
    }

    /** A check of the logger's queue as it is now (`now`: uptime), as the
      * app makes it. Kinds unsaved for a while ([[stalledKinds]]) are taken
      * in; a kind is taken off only with nothing of it waiting or failed,
      * however recent. `onChange` also gets the oldest unsaved (kind, since).
      */
    def checkBacklog(backlog: WriteBacklog, noCommit: Boolean, now: Long)
                    (onChange: (Boolean, Option[(String, Long)]) => Unit = (_, _) => ()): Option[Boolean] = synchronized {
      val (oldest, unsaved, saved) = backlog.synchronized((backlog.oldestUnsaved, backlog.unsavedSince, backlog.savedTimes))
      check(noCommit, queuedTooLong(oldest.map(_._2), now), unsaved.keySet, saved, Some(stalledKinds(unsaved, now)))(
        up => onChange(up, oldest))
    }

    /** The notification and the state on disk as the monitor says. */
    def reconcile(): Unit = synchronized {
      val want = Option.when(monitor.reported)(monitor.kinds)
      if dirty || fx.stored != want then dirty = !fx.store(want)
      if want.isDefined then
        if !shown && (fx.posted || fx.post()) then shown = true
      else if fx.posted then fx.cancel()
    }

  def queuedTooLong(unsavedSince: Option[Long], nowUptime: Long): Boolean =
    unsavedSince.exists(nowUptime - _ > OldestMs)

  /** A kind unsaved for at least this long is taken in by the alert (one
    * check apart): a batch merely in the queue at a check is not. Taking a
    * kind off asks for nothing of it unsaved, however recent.
    */
  val KindMs: Long = 60_000L

  /** The kinds the alert names: unsaved (waiting or failed) for at least
    * [[KindMs]].
    */
  def stalledKinds(unsavedSince: Map[String, Long], nowUptime: Long): Set[String] =
    unsavedSince.collect { case (k, since) if nowUptime - since >= KindMs => k }.toSet

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
