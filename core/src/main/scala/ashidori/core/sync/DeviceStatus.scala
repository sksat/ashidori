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

  /** Plaintext of a batch (before gzip): one JSON line. */
  def encode(s: DeviceStatus): Array[Byte] = (ujson.write(s.toJson) + "\n").getBytes(UTF_8)

  /** The last record of a decrypted, gunzipped batch. */
  def decode(plain: Array[Byte]): Option[DeviceStatus] =
    String(plain, UTF_8).split('\n').map(_.trim).filter(_.nonEmpty).lastOption.map(l => fromJson(ujson.read(l)))

/** One row of `GET /v1/batches`. */
final case class BatchMeta(cursor: Long, deviceId: String, stream: String, seq: Long)

object BatchMeta:
  def fromJson(v: ujson.Value): BatchMeta =
    BatchMeta(v("cursor").num.toLong, v("deviceId").str, v("stream").str, v("seq").num.toLong)

  /** The newest `status` batch per other device, among `items`, that is newer
    * than what is already known (`known`: device id → seq).
    */
  def newestStatus(items: Seq[BatchMeta], self: String, known: Map[String, Long]): Map[String, Long] =
    items.filter(b => b.stream == DeviceStatus.Stream && b.deviceId != self)
      .groupMapReduce(_.deviceId)(_.seq)(math.max)
      .filter((d, seq) => known.get(d).forall(seq > _))
