package ashidori.core.sync

import java.nio.charset.StandardCharsets.UTF_8

/** Last known position in a status record. */
final case class StatusFix(t: Long, lat: Double, lon: Double, acc: Double)

/** How one device is doing: one record of the append-only `status` stream,
  * uploaded on every small-stream sync so the user's other devices can show it.
  * `mode` is a [[ashidori.core.LoggingMode]] key.
  */
final case class DeviceStatus(
    t: Long,
    batteryPct: Int,
    charging: Boolean,
    recording: Boolean,
    mode: String,
    since: Long,
    lastFix: Option[StatusFix],
    versionName: String,
    versionCode: Long,
    model: String
):
  def toJson: ujson.Obj =
    ujson.Obj(
      "t" -> ujson.Num(t.toDouble),
      "battery" -> ujson.Obj("pct" -> batteryPct, "charging" -> charging),
      "recording" -> ujson.Obj("on" -> recording, "mode" -> mode, "since" -> ujson.Num(since.toDouble)),
      "lastFix" -> lastFix.fold[ujson.Value](ujson.Null) { f =>
        // ~0.1 m and 0.1 m: enough for a map, keeps the record short.
        ujson.Obj("t" -> ujson.Num(f.t.toDouble), "lat" -> round(f.lat, 1e6), "lon" -> round(f.lon, 1e6),
          "acc" -> (if f.acc.isNaN then ujson.Null else ujson.Num(round(f.acc, 10))))
      },
      "app" -> ujson.Obj("versionName" -> versionName, "versionCode" -> ujson.Num(versionCode.toDouble)),
      "device" -> ujson.Obj("model" -> model)
    )

  private def round(x: Double, k: Double): Double = math.round(x * k) / k

object DeviceStatus:
  val Stream = "status"

  /** Lenient: a newer app may add fields, an older one may lack some. */
  def fromJson(v: ujson.Value): DeviceStatus =
    def obj(k: String) = v.obj.get(k).flatMap(_.objOpt)
    def num(o: Option[collection.Map[String, ujson.Value]], k: String) = o.flatMap(_.get(k)).flatMap(_.numOpt)
    def str(o: Option[collection.Map[String, ujson.Value]], k: String) = o.flatMap(_.get(k)).flatMap(_.strOpt).getOrElse("")
    def bool(o: Option[collection.Map[String, ujson.Value]], k: String) = o.flatMap(_.get(k)).flatMap(_.boolOpt).getOrElse(false)
    val bat = obj("battery")
    val rec = obj("recording")
    val app = obj("app")
    val fix = obj("lastFix").flatMap { f =>
      for t <- f.get("t").flatMap(_.numOpt); lat <- f.get("lat").flatMap(_.numOpt); lon <- f.get("lon").flatMap(_.numOpt)
      yield StatusFix(t.toLong, lat, lon, f.get("acc").flatMap(_.numOpt).getOrElse(Double.NaN))
    }
    DeviceStatus(
      v("t").num.toLong,
      num(bat, "pct").map(_.toInt).getOrElse(-1),
      bool(bat, "charging"),
      bool(rec, "on"),
      str(rec, "mode"),
      num(rec, "since").map(_.toLong).getOrElse(0L),
      fix,
      str(app, "versionName"),
      num(app, "versionCode").map(_.toLong).getOrElse(0L),
      str(obj("device"), "model")
    )

  /** Caps for a received `status` batch: the blob as downloaded, its
    * decompressed plaintext, and its JSON lines. A record is ~300 bytes.
    */
  val MaxBlob: Int = 64 * 1024
  val MaxPlain: Int = 64 * 1024
  val MaxLines: Int = 64

  /** Plaintext of a batch (before gzip): one JSON line. */
  def encode(s: DeviceStatus): Array[Byte] = (ujson.write(s.toJson) + "\n").getBytes(UTF_8)

  /** The last record of a decrypted, gunzipped batch. More than `maxLines`
    * lines is refused rather than parsed.
    */
  def decode(plain: Array[Byte], maxLines: Int = MaxLines): Option[DeviceStatus] =
    val lines = String(plain, UTF_8).split('\n').iterator.map(_.trim).filter(_.nonEmpty).toVector
    if lines.size > maxLines then throw LimitExceeded("status lines", maxLines)
    lines.lastOption.map(l => fromJson(ujson.read(l)))

/** One row of `GET /v1/batches`. */
final case class BatchMeta(cursor: Long, deviceId: String, stream: String, seq: Long)

object BatchMeta:
  /** Throws on a malformed row (the listing is not trusted). */
  def fromJson(v: ujson.Value): BatchMeta =
    val b = BatchMeta(v("cursor").num.toLong, v("deviceId").str, v("stream").str, v("seq").num.toLong)
    require(b.cursor >= 0 && b.seq >= 0 && b.deviceId.matches("[A-Za-z0-9_-]{1,64}") && b.stream.matches("[a-z0-9_-]{1,32}"),
      s"malformed batch metadata: $v")
    b

  /** The newest `status` batch per other device, among `items`, that is newer
    * than what is already known (`known`: device id → seq).
    */
  def newestStatus(items: Seq[BatchMeta], self: String, known: Map[String, Long]): Map[String, Long] =
    items.filter(b => b.stream == DeviceStatus.Stream && b.deviceId != self)
      .groupMapReduce(_.deviceId)(_.seq)(math.max)
      .filter((d, seq) => known.get(d).forall(seq > _))

/** A `status` batch that was listed but could not be read: wrong key,
  * damaged, too large or not a status record. Kept until a newer status of
  * the device is read, and shown on the device's row.
  */
final case class Unresolved(seq: Long, attempts: Int, error: String)

/** Download progress of the other devices' `status` stream.
  *
  * `cursor` is only where the listing continues; what was actually read is
  * `authenticated` (device → highest seq that decrypted and parsed). `latest`
  * remembers the newest listed seq per device, so the cursor may move on
  * while a fetch is still to be done or retried.
  */
final case class StatusSyncState(
    cursor: Long,
    latest: Map[String, Long],
    authenticated: Map[String, Long],
    unresolved: Map[String, Unresolved]
):
  import StatusSyncState.MaxAttempts

  /** Takes in one listing page. The cursor only moves to cursors of rows
    * actually listed, never to a bare `next` value.
    */
  def listed(items: Seq[BatchMeta], self: String): StatusSyncState =
    val newest = items.filter(b => b.stream == DeviceStatus.Stream && b.deviceId != self)
      .groupMapReduce(_.deviceId)(_.seq)(math.max)
    copy(
      cursor = (items.map(_.cursor) :+ cursor).max,
      latest = newest.foldLeft(latest) { case (m, (d, s)) => m.updated(d, math.max(s, m.getOrElse(d, -1L))) }
    )

  /** Seq to fetch per device: its newest listed status when not read yet,
    * unless that one already failed [[MaxAttempts]] times.
    */
  def targets: Map[String, Long] =
    latest.filter { (d, seq) =>
      seq > authenticated.getOrElse(d, -1L) && !unresolved.get(d).exists(u => u.seq == seq && u.attempts >= MaxAttempts)
    }

  def succeeded(device: String, seq: Long): StatusSyncState =
    copy(
      authenticated = authenticated.updated(device, math.max(seq, authenticated.getOrElse(device, -1L))),
      unresolved = unresolved.filter((d, u) => d != device || u.seq > seq)
    )

  /** The blob was received but is unreadable. A network error is not a failure: nothing changes. */
  def failed(device: String, seq: Long, error: String): StatusSyncState =
    val attempts = unresolved.get(device).filter(_.seq == seq).fold(1)(_.attempts + 1)
    copy(unresolved = unresolved.updated(device, Unresolved(seq, attempts, Option(error).getOrElse("").take(200))))

  /** Unreadable status newer than anything read from the device. */
  def problem(device: String): Option[Unresolved] =
    unresolved.get(device).filter(_.seq > authenticated.getOrElse(device, -1L))

object StatusSyncState:
  val MaxAttempts = 3
  val empty: StatusSyncState = StatusSyncState(0L, Map.empty, Map.empty, Map.empty)

  def toJson(s: StatusSyncState): ujson.Value =
    def nums(m: Map[String, Long]) = ujson.Obj.from(m.map((k, v) => k -> ujson.Num(v.toDouble)))
    ujson.Obj(
      "cursor" -> ujson.Num(s.cursor.toDouble),
      "latest" -> nums(s.latest),
      "authenticated" -> nums(s.authenticated),
      "unresolved" -> ujson.Obj.from(s.unresolved.map((k, u) =>
        k -> ujson.Obj("seq" -> ujson.Num(u.seq.toDouble), "attempts" -> u.attempts, "error" -> u.error)))
    )

  def fromJson(v: ujson.Value): StatusSyncState =
    def nums(k: String) = v.obj.get(k).flatMap(_.objOpt).fold(Map.empty[String, Long])(_.toMap.map((d, n) => d -> n.num.toLong))
    StatusSyncState(
      v.obj.get("cursor").flatMap(_.numOpt).fold(0L)(_.toLong),
      nums("latest"),
      nums("authenticated"),
      v.obj.get("unresolved").flatMap(_.objOpt).fold(Map.empty[String, Unresolved])(_.toMap.map((d, u) =>
        d -> Unresolved(u("seq").num.toLong, u("attempts").num.toInt, u.obj.get("error").flatMap(_.strOpt).getOrElse(""))))
    )
