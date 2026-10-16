package ashidori.core

import scala.util.Try

/** Why a download of a pinned archive ([[PinnedKind]]) failed. */
enum DownloadCause:
  /** The source answered with this HTTP status. */
  case Http(status: Int)
  /** No usable answer: no connection, a timeout, a cut transfer. */
  case Network
  /** The bytes are not the pinned archive (size or sha256). */
  case Mismatch
  /** Not enough free storage for the archive and its extraction. */
  case Storage

object DownloadCause:
  /** The cause of a failed transfer from one source ([[ResumableDownload.run]]). */
  def of(e: Throwable): DownloadCause = e match
    case r: ResumableDownload.Refused => r.status.fold(Mismatch)(Http(_))
    case h: ResumableDownload.HttpFailure => Http(h.status)
    case _ => Network

  /** One line for the UI, e.g. 「配信元にありません・HTTP 404」. */
  def label(c: DownloadCause): String = c match
    case Http(s) if s == 404 || s == 410 => s"配信元にありません・HTTP $s"
    case Http(s) if DownloadRetry.permanentStatus(s) => s"配信元が拒否しました・HTTP $s"
    case Http(s) => s"配信元が応答しません・HTTP $s"
    case Network => "接続できません"
    case Mismatch => "データが一致しません"
    case Storage => "空き容量が不足しています"

  /** The shortest form, for a settings row with the retry time: 「HTTP 404」. */
  def shortLabel(c: DownloadCause): String = c match
    case Http(s) => s"HTTP $s"
    case Network => "接続不可"
    case Mismatch => "データ不一致"
    case Storage => "空き容量不足"

  private[core] def key(c: DownloadCause): String = c match
    case Http(s) => s"http-$s"
    case Network => "network"
    case Mismatch => "mismatch"
    case Storage => "storage"

  private[core] def fromKey(k: String): Option[DownloadCause] = k match
    case "network" => Some(Network)
    case "mismatch" => Some(Mismatch)
    case "storage" => Some(Storage)
    case s if s.startsWith("http-") => s.drop(5).toIntOption.filter(n => n >= 100 && n <= 999).map(Http(_))
    case _ => None

/** The last failed download of one pinned version, kept until it is
  * installed, deleted, or another version is pinned.
  *
  * `cause` is that of the first source tried; `sources` has each source's
  * (label, cause). `giveUps` counts runs given up in a row (the cool-down
  * grows with it). `retryAt` is when the next automatic run may start; None
  * while the current run is still being retried (soon, with WorkManager's
  * backoff) or after the user asked for a retry.
  */
final case class DownloadFailure(version: String, at: Long, cause: DownloadCause,
                                 sources: Seq[(String, DownloadCause)] = Seq.empty,
                                 giveUps: Int = 0, retryAt: Option[Long] = None):
  def label: String = DownloadCause.label(cause)
  def shortLabel: String = DownloadCause.shortLabel(cause)

object DownloadFailure:
  private val Version = 1

  def encode(f: DownloadFailure): String =
    ujson.write(ujson.Obj(
      "v" -> Version,
      "version" -> f.version,
      "at" -> f.at.toDouble,
      "cause" -> DownloadCause.key(f.cause),
      "sources" -> ujson.Arr(f.sources.map((s, c) => ujson.Obj("source" -> s, "cause" -> DownloadCause.key(c)))*),
      "giveUps" -> f.giveUps,
      "retryAt" -> f.retryAt.fold[ujson.Value](ujson.Null)(t => ujson.Num(t.toDouble))
    ))

  /** None for anything but a well-formed record (also an older plain message). */
  def decode(s: String): Option[DownloadFailure] =
    Try {
      val o = ujson.read(s).obj
      if !o.get("v").flatMap(_.numOpt).contains(Version.toDouble) then None
      else
        for
          version <- o.get("version").flatMap(_.strOpt).filter(BasemapPin.validVersion)
          at <- o.get("at").flatMap(_.numOpt).map(_.toLong)
          cause <- o.get("cause").flatMap(_.strOpt).flatMap(DownloadCause.fromKey)
        yield
          val sources = o.get("sources").flatMap(_.arrOpt).map(_.toSeq.flatMap { e =>
            for
              r <- e.objOpt
              src <- r.get("source").flatMap(_.strOpt)
              c <- r.get("cause").flatMap(_.strOpt).flatMap(DownloadCause.fromKey)
            yield src -> c
          }).getOrElse(Seq.empty)
          val giveUps = o.get("giveUps").flatMap(_.numOpt).map(_.toInt).filter(_ >= 0).getOrElse(0)
          val retryAt = o.get("retryAt").flatMap(_.numOpt).map(_.toLong)
          DownloadFailure(version, at, cause, sources, giveUps, retryAt)
    }.toOption.flatten

/** When a failed download of a pinned archive is tried again.
  *
  * A run (one WorkManager request) retries a transient failure (no
  * connection, 5xx, 408, 429) with WorkManager's exponential backoff, up to
  * [[MaxAttempts]] attempts. A failure a retry will not fix soon (another
  * 4xx, such as 404 while the release is not public, wrong bytes, no
  * storage) ends the run at once. A run that ends this way, or runs out of
  * attempts, is given up: the next run is scheduled after a cool-down that
  * doubles with each run given up in a row ([[cooldownMs]]), so a missing
  * release is asked for a few times a day at most. The user can retry at
  * any time.
  */
object DownloadRetry:
  /** Attempts of one run (WorkManager retries in between). */
  val MaxAttempts = 4
  private val Hour = 60L * 60 * 1000
  /** First cool-down after a run that ran out of transient retries. */
  val TransientCooldownMs: Long = 1 * Hour
  /** First cool-down after a permanent failure. */
  val PermanentCooldownMs: Long = 6 * Hour
  val MaxCooldownMs: Long = 24 * Hour

  /** An HTTP status that the same request will get again for now. */
  def permanentStatus(status: Int): Boolean = status >= 400 && status < 500 && status != 408 && status != 429

  def permanent(c: DownloadCause): Boolean = c match
    case DownloadCause.Http(s) => permanentStatus(s)
    case DownloadCause.Network => false
    case DownloadCause.Mismatch | DownloadCause.Storage => true

  /** Wait before the next run after `giveUps` runs given up in a row before this one. */
  def cooldownMs(c: DownloadCause, giveUps: Int): Long =
    val base = if permanent(c) then PermanentCooldownMs else TransientCooldownMs
    math.min(MaxCooldownMs, base << math.min(math.max(giveUps, 0), 10))

  enum Next:
    /** Retry within this run (WorkManager's backoff). */
    case Backoff
    /** Give the run up; start the next one at `at`. */
    case Later(at: Long)

  /** Attempt `runAttempt` (0-based) of a run for `version` failed at `now`;
    * `previous` is the failure stored before. Returns what to store and what
    * to do.
    */
  def afterFailure(version: String, now: Long, cause: DownloadCause, sources: Seq[(String, DownloadCause)],
                   runAttempt: Int, previous: Option[DownloadFailure]): (DownloadFailure, Next) =
    val giveUps = previous.filter(_.version == version).fold(0)(_.giveUps)
    if !permanent(cause) && runAttempt + 1 < MaxAttempts then
      (DownloadFailure(version, now, cause, sources, giveUps, None), Next.Backoff)
    else
      val at = now + cooldownMs(cause, giveUps)
      (DownloadFailure(version, now, cause, sources, giveUps + 1, Some(at)), Next.Later(at))

  /** The stored failure if it is about the pinned `version`. */
  def current(stored: Option[DownloadFailure], version: String): Option[DownloadFailure] =
    stored.filter(_.version == version)

  /** The user asked for a retry: it starts now, and the cool-down starts over. */
  def userRetry(stored: Option[DownloadFailure]): Option[DownloadFailure] =
    stored.map(_.copy(giveUps = 0, retryAt = None))

  /** Delay (ms) of an automatic start at app start: none unless a run was
    * given up and its cool-down has not passed.
    */
  def autoStartDelay(stored: Option[DownloadFailure], version: String, now: Long): Long =
    current(stored, version).flatMap(_.retryAt).fold(0L)(t => math.max(0L, t - now))
