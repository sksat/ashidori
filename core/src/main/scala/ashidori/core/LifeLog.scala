package ashidori.core

/** One entry of the logger's lifecycle log: what the service, the OS and
  * the recovery paths did, so a gap in the recording can be explained
  * later. Never holds positions.
  */
final case class LifeEvent(time: Long, kind: LifeKind, detail: String = ""):
  def label: String = kind.label
  /** Shown in the error colour: failures, and exits that were not asked for. */
  def problem: Boolean =
    kind.problem || (kind == LifeKind.ProcessExit &&
      PlatformCodes.problemExitNames.contains(detail.takeWhile(_ != ' ')))

/** Kinds of [[LifeEvent]]. `problem` marks entries shown in the error colour. */
enum LifeKind(val key: String, val label: String, val problem: Boolean = false):
  case ProcessStart extends LifeKind("process", "アプリのプロセス起動")
  case ProcessExit extends LifeKind("exit", "プロセス終了（OS の記録）")
  case Crash extends LifeKind("crash", "クラッシュ", problem = true)
  case ServiceStart extends LifeKind("start", "記録サービス開始")
  case StickyRestart extends LifeKind("sticky", "記録サービスの自動再起動")
  case ServiceStop extends LifeKind("stop", "記録サービス終了")
  case ForegroundFailed extends LifeKind("fgsfail", "記録サービスを開始できない", problem = true)
  case Boot extends LifeKind("boot", "端末の起動・アプリの更新")
  case Watchdog extends LifeKind("watchdog", "定期確認")
  case StillEnter extends LifeKind("still", "静止（GNSS 停止）")
  case StillExit extends LifeKind("move", "動きを検知")
  case Source extends LifeKind("source", "測位の切り替え")
  case Provider extends LifeKind("provider", "位置情報プロバイダ")
  case State extends LifeKind("state", "権限・電池の設定")
  case Note extends LifeKind("note", "注意", problem = true)

object LifeKind:
  def fromKey(k: String): Option[LifeKind] = values.find(_.key == k)

object LifeEvent:
  /** One line: time, kind and detail separated by tabs. Tabs and line
    * breaks inside the detail become spaces.
    */
  def encode(e: LifeEvent): String =
    s"${e.time}\t${e.kind.key}\t${clean(e.detail)}"

  def decode(line: String): Option[LifeEvent] =
    line.split('\t') match
      case Array(t, k, rest*) =>
        for
          time <- t.toLongOption
          kind <- LifeKind.fromKey(k)
        yield LifeEvent(time, kind, rest.mkString(" "))
      case _ => None

  def clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim

  /** Lines kept when an append-only file of `lines` is compacted: the newest
    * `keep`. A file is compacted once it holds twice that, so each append
    * stays cheap.
    */
  def compact(lines: Seq[String], keep: Int): Seq[String] = lines.takeRight(keep)
  def needsCompaction(lineCount: Int, keep: Int): Boolean = lineCount >= 2 * keep

/** Steps counted while the accelerometer is off (stationary) that mean the
  * user is moving again: `threshold` steps within `windowMs`. The step
  * counter reports on change, so one delivery may carry 1 step or 30;
  * counting per delivery would miss a walk reported step by step.
  */
final class StepWake(threshold: Int = 10, windowMs: Long = 2 * 60_000L):
  private val recent = scala.collection.mutable.Queue.empty[(Long, Int)]

  def reset(): Unit = recent.clear()

  /** Adds `steps` counted at `time`; true when the threshold is reached
    * (and the count starts over).
    */
  def add(time: Long, steps: Int): Boolean =
    if steps <= 0 then false
    else
      recent.enqueue((time, steps))
      while recent.nonEmpty && recent.head._1 < time - windowMs do recent.dequeue()
      if recent.iterator.map(_._2).sum >= threshold then
        recent.clear()
        true
      else false

/** A stretch without recorded positions, and what the lifecycle log says
  * about it.
  */
final case class RecordingGap(start: Long, end: Long, cause: GapCause, events: Vector[LifeEvent]):
  def durationMs: Long = end - start

enum GapCause(val label: String):
  /** Recording switched off by the user. */
  case Stopped extends GapCause("記録停止中")
  /** The service or its process ended (killed, crashed, not restarted). */
  case ServiceDown extends GapCause("記録サービスが停止していた")
  /** GNSS off on purpose, waiting for movement. */
  case Still extends GapCause("静止中（GNSS 停止）")
  case Unknown extends GapCause("原因不明（サービスは動作中のはず）")

object RecordingGaps:
  /** Gaps of at least `minGapMs` between `from` and `to` in the sorted fix
    * times, including before the first and after the last fix.
    */
  def find(fixTimes: Seq[Long], from: Long, to: Long, minGapMs: Long): Vector[(Long, Long)] =
    val ts = fixTimes.filter(t => t >= from && t <= to).sorted
    val bounds = (from +: ts) :+ to
    bounds.sliding(2).collect { case Seq(a, b) if b - a >= minGapMs => (a, b) }.toVector

  /** Explains each gap from the events before and inside it. */
  def explain(gaps: Seq[(Long, Long)], events: Seq[LifeEvent]): Vector[RecordingGap] =
    val sorted = events.sortBy(_.time)
    gaps.map { (s, e) =>
      val before = sorted.filter(_.time <= s)
      val inside = sorted.filter(ev => ev.time > s && ev.time < e).toVector
      RecordingGap(s, e, cause(before, inside), inside)
    }.toVector

  private def cause(before: Seq[LifeEvent], inside: Seq[LifeEvent]): GapCause =
    import LifeKind.*
    val lastLife = before.reverseIterator.find(ev =>
      Set(ServiceStart, StickyRestart, ServiceStop, ForegroundFailed, ProcessExit, Crash).contains(ev.kind))
    val stoppedByUser = lastLife.exists(ev => ev.kind == ServiceStop && ev.detail.startsWith("user"))
    val downAtStart = lastLife.exists(ev => ev.kind != ServiceStart && ev.kind != StickyRestart)
    val downInside = inside.exists(ev =>
      ev.kind == ProcessExit || ev.kind == Crash || ev.kind == ForegroundFailed ||
        (ev.kind == ServiceStop && !ev.detail.startsWith("user")))
    val stillAtStart = before.reverseIterator.find(ev => ev.kind == StillEnter || ev.kind == StillExit ||
      ev.kind == ServiceStart || ev.kind == StickyRestart).exists(_.kind == StillEnter)
    val stillInside = inside.exists(_.kind == StillEnter)
    if stoppedByUser && !inside.exists(ev => ev.kind == ServiceStart || ev.kind == StickyRestart) then GapCause.Stopped
    else if downAtStart || downInside then GapCause.ServiceDown
    else if stillAtStart || stillInside then GapCause.Still
    else GapCause.Unknown

/** Names of platform codes shown in the diagnostics, kept here so they are
  * plain data.
  */
object PlatformCodes:
  /** android.app.ApplicationExitInfo.REASON_* */
  def exitReason(r: Int): String = r match
    case 0  => "UNKNOWN"
    case 1  => "EXIT_SELF"
    case 2  => "SIGNALED"
    case 3  => "LOW_MEMORY"
    case 4  => "CRASH"
    case 5  => "CRASH_NATIVE"
    case 6  => "ANR"
    case 7  => "INITIALIZATION_FAILURE"
    case 8  => "PERMISSION_CHANGE"
    case 9  => "EXCESSIVE_RESOURCE_USAGE"
    case 10 => "USER_REQUESTED"
    case 11 => "USER_STOPPED"
    case 12 => "DEPENDENCY_DIED"
    case 13 => "OTHER"
    case 14 => "FREEZER"
    case 15 => "PACKAGE_STATE_CHANGE"
    case 16 => "PACKAGE_UPDATED"
    case n  => s"REASON_$n"

  /** Exits that end recording against the user's wish. */
  def exitIsProblem(r: Int): Boolean = ProblemExits.contains(r)
  private val ProblemExits = Set(2, 3, 4, 5, 6, 7, 9, 13, 14)
  val problemExitNames: Set[String] = ProblemExits.map(exitReason)

  /** android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_* */
  def importance(i: Int): String = i match
    case 100  => "FOREGROUND"
    case 125  => "FOREGROUND_SERVICE"
    case 200  => "VISIBLE"
    case 230  => "PERCEPTIBLE"
    case 300  => "SERVICE"
    case 325  => "TOP_SLEEPING"
    case 350  => "CANT_SAVE_STATE"
    case 400  => "CACHED"
    case 1000 => "GONE"
    case n    => n.toString

  /** android.app.usage.UsageStatsManager.STANDBY_BUCKET_* */
  def standbyBucket(b: Int): String = b match
    case 5  => "EXEMPTED"
    case 10 => "ACTIVE"
    case 20 => "WORKING_SET"
    case 30 => "FREQUENT"
    case 40 => "RARE"
    case 45 => "RESTRICTED"
    case 50 => "NEVER"
    case n  => n.toString
