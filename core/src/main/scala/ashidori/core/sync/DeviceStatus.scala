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

/** One row of `GET /v1/batches`: where the blob is, and the size and
  * SHA-256 the server recorded for it.
  */
final case class BatchMeta(cursor: Long, deviceId: String, stream: String, seq: Long, size: Long, sha256: String):
  def candidate: StatusCandidate = StatusCandidate(seq, size, sha256)

object BatchMeta:
  /** Throws on a malformed row (the listing is not trusted). */
  def fromJson(v: ujson.Value): BatchMeta =
    val b = BatchMeta(v("cursor").num.toLong, v("deviceId").str, v("stream").str, v("seq").num.toLong,
      v("size").num.toLong, v("sha256").str)
    require(b.cursor >= 0 && b.seq >= 0 && b.size >= 0 && b.deviceId.matches("[A-Za-z0-9_-]{1,64}") &&
      b.stream.matches("[a-z0-9_-]{1,32}") && b.sha256.matches("[0-9a-f]{64}"),
      s"malformed batch metadata: $v")
    b

  /** The newest `status` batch per other device, among `items`, that is newer
    * than what is already known (`known`: device id → seq).
    */
  def newestStatus(items: Seq[BatchMeta], self: String, known: Map[String, Long]): Map[String, Long] =
    items.filter(b => b.stream == DeviceStatus.Stream && b.deviceId != self)
      .groupMapReduce(_.deviceId)(_.seq)(math.max)
      .filter((d, seq) => known.get(d).forall(seq > _))

/** A listed `status` batch to fetch: its seq, the size and SHA-256 the
  * listing gave for it, and how often a fetched blob failed to read.
  * Candidates are told apart by (seq, size, SHA-256): a server that lists
  * another row for a seq gives another candidate.
  */
final case class StatusCandidate(seq: Long, size: Long, sha256: String, failures: Int = 0):
  /** Why the fetched `blob` is not the listed one, if it is not. A blob that
    * fails this is not decrypted; it counts as unresolved.
    */
  def mismatch(blob: Array[Byte]): Option[String] =
    if blob.length.toLong != size then Some(s"size ${blob.length} differs from the listed $size")
    else if !Crypto.sha256Hex(blob).equalsIgnoreCase(sha256) then Some("SHA-256 differs from the listing")
    else None

  def sameRow(o: StatusCandidate): Boolean = seq == o.seq && size == o.size && sha256.equalsIgnoreCase(o.sha256)

  def exhausted: Boolean = failures >= StatusSyncState.MaxAttempts

/** A `status` batch that was listed but could not be read: wrong key,
  * damaged, too large, not the listed blob or not a status record. Kept
  * until a status of the device at least as new is read, and shown on the
  * device's row. Of several failures, the one with the highest seq is kept.
  */
final case class Unresolved(seq: Long, attempts: Int, error: String)

/** Download progress of the other devices' `status` stream.
  *
  * `cursor` is only where the listing continues; what was actually read is
  * `authenticated` (device → highest seq that decrypted and parsed).
  * `candidates` keeps, per device, the newest few listed batches newer than
  * that (newest first; at most [[StatusSyncState.MaxCandidates]] still to
  * try and as many exhausted ones), so the cursor may move on while a fetch
  * is still to be done or retried, and a newest candidate that keeps failing
  * (e.g. a row the server made up) does not hide the older genuine ones.
  *
  * A listing can lie, so the walk is redone from cursor 0 now and then
  * (`rescan`: the position of a rescan in progress; `rescanAt`: when the
  * last one started; `rescanWanted`: one is wanted soon, after a suspicious
  * page or when a device has no candidate left to try).
  */
final case class StatusSyncState(
    cursor: Long,
    candidates: Map[String, Vector[StatusCandidate]],
    authenticated: Map[String, Long],
    unresolved: Map[String, Unresolved],
    rescan: Option[Long] = None,
    rescanAt: Long = 0L,
    rescanWanted: Boolean = false
):
  import StatusSyncState.*

  private def auth(device: String): Long = authenticated.getOrElse(device, -1L)

  /** Takes the status rows of a page in as candidates: rows newer than the
    * device's authenticated seq that it does not have yet (see `trim`).
    */
  private def take(items: Seq[BatchMeta], self: String): StatusSyncState =
    val rows = items.filter(b => b.stream == DeviceStatus.Stream && b.deviceId != self)
    copy(candidates = rows.foldLeft(candidates) { (m, b) =>
      val c = b.candidate
      val have = m.getOrElse(b.deviceId, Vector.empty)
      if c.seq <= auth(b.deviceId) || have.exists(_.sameRow(c)) then m
      else m.updated(b.deviceId, trim((have :+ c).sortBy(-_.seq)))
    })

  /** Takes in one page of the main walk (`limit`: the page size asked for).
    * The cursor only moves to cursors of rows actually listed, never to a
    * bare `next` value, and not past a row that breaks [[advance]]'s rule;
    * such a page asks for a rescan.
    */
  def listed(items: Seq[BatchMeta], self: String, limit: Int = Int.MaxValue): StatusSyncState =
    val (to, suspicious) = advance(cursor, items, limit)
    take(items, self).copy(cursor = to, rescanWanted = rescanWanted || suspicious)

  /** Starts a rescan when one is due: every [[RescanEvery]], or after
    * [[RescanMinGap]] when one is wanted. A fresh walk (cursor 0) needs none.
    */
  def rescanIfDue(now: Long): StatusSyncState =
    if rescan.isDefined then this
    else if cursor == 0L && rescanAt == 0L then copy(rescanAt = now)
    else if now - rescanAt >= RescanEvery || (rescanWanted && now - rescanAt >= RescanMinGap) then
      copy(rescan = Some(0L), rescanAt = now, rescanWanted = false)
    else this

  /** Takes in one page of a rescan (`more`: the server gave a `next`). The
    * rescan ends at the end of the listing, or when its page does not move it
    * on; the main cursor then becomes where the rescan ended, so a cursor the
    * main walk took from a row that is no longer listed is given up.
    */
  def rescanned(items: Seq[BatchMeta], self: String, limit: Int, more: Boolean): StatusSyncState =
    rescan.fold(this) { from =>
      val (to, _) = advance(from, items, limit)
      val st = take(items, self)
      if more && to > from then st.copy(rescan = Some(to)) else st.copy(cursor = to, rescan = None)
    }

  /** Batch to fetch per device: its newest candidate not read yet that has
    * not failed [[MaxAttempts]] times.
    */
  def targets: Map[String, StatusCandidate] =
    candidates.flatMap((d, cs) => cs.find(c => c.seq > auth(d) && !c.exhausted).map(d -> _))

  def succeeded(device: String, seq: Long): StatusSyncState =
    val a = math.max(seq, auth(device))
    copy(
      authenticated = authenticated.updated(device, a),
      candidates = candidates.updatedWith(device)(_.map(_.filter(_.seq > a)).filter(_.nonEmpty)),
      unresolved = unresolved.filter((d, u) => d != device || u.seq > seq)
    )

  /** The blob of candidate `c` was received but is unreadable. A network
    * error is not a failure: nothing changes. When the device has no
    * candidate left to try, a rescan is wanted.
    */
  def failed(device: String, c: StatusCandidate, error: String): StatusSyncState =
    val have = candidates.getOrElse(device, Vector.empty)
    val failures = have.find(_.sameRow(c)).fold(c.failures)(_.failures) + 1
    val cs = trim((have.filterNot(_.sameRow(c)) :+ c.copy(failures = failures)).sortBy(-_.seq))
    // The newest failure stays shown: an older one does not replace it.
    val shown = problem(device).filter(_.seq > c.seq)
    val st = copy(
      candidates = candidates.updated(device, cs),
      unresolved = unresolved.updated(device,
        shown.getOrElse(Unresolved(c.seq, failures, Option(error).getOrElse("").take(200))))
    )
    if st.targets.contains(device) then st else st.copy(rescanWanted = true)

  /** Unreadable status newer than anything read from the device. */
  def problem(device: String): Option[Unresolved] =
    unresolved.get(device).filter(_.seq > auth(device))

object StatusSyncState:
  val MaxAttempts = 3
  /** Listed candidates kept per device: this many still to try, and this many exhausted. */
  val MaxCandidates = 4
  /** On a page with fewer rows than asked for (the end of the listing), a
    * cursor more than this above the previous row is not taken. The cursor
    * is shared by all users, so it has gaps, but they stay far below this.
    */
  val MaxCursorJump: Long = 10_000_000L
  /** A rescan from cursor 0 starts this long after the previous one... */
  val RescanEvery: Long = 7L * 24 * 60 * 60 * 1000
  /** ... or this long after it when one is wanted. */
  val RescanMinGap: Long = 24L * 60 * 60 * 1000
  val empty: StatusSyncState = StatusSyncState(0L, Map.empty, Map.empty, Map.empty)

  /** Of `cs` (newest first), keeps the newest [[MaxCandidates]] still to
    * try and, separately, the newest [[MaxCandidates]] exhausted ones:
    * exhausted rows the server keeps listing then neither crowd out the
    * others nor come back as new on a rescan.
    */
  private def trim(cs: Vector[StatusCandidate]): Vector[StatusCandidate] =
    val (gone, live) = cs.partition(_.exhausted)
    (live.take(MaxCandidates) ++ gone.take(MaxCandidates)).sortBy(-_.seq)

  /** Where a walk at `after` may move with this page, and whether the page
    * looked wrong. The cursor stops before the first row whose cursor is not
    * above the previous one, or, on a page with fewer than `limit` rows,
    * more than [[MaxCursorJump]] above it. A full page is always followed,
    * else an honest gap at its start would stop the walk for good.
    */
  def advance(after: Long, items: Seq[BatchMeta], limit: Int): (Long, Boolean) =
    val cs = items.map(_.cursor)
    val short = items.size < limit
    val bad = cs.indices.find { i =>
      val prev = if i == 0 then after else cs(i - 1)
      cs(i) <= prev || (short && cs(i) - prev > MaxCursorJump)
    }
    (bad.fold(cs)(cs.take).lastOption.getOrElse(after), bad.isDefined)

  private def candidateJson(c: StatusCandidate): ujson.Value =
    ujson.Obj("seq" -> ujson.Num(c.seq.toDouble), "size" -> ujson.Num(c.size.toDouble), "sha256" -> c.sha256,
      "failures" -> c.failures)

  private def candidateOf(o: collection.Map[String, ujson.Value]): StatusCandidate =
    StatusCandidate(o("seq").num.toLong, o("size").num.toLong, o("sha256").str,
      o.get("failures").flatMap(_.numOpt).fold(0)(_.toInt))

  def toJson(s: StatusSyncState): ujson.Value =
    ujson.Obj(
      "cursor" -> ujson.Num(s.cursor.toDouble),
      "candidates" -> ujson.Obj.from(s.candidates.map((k, cs) => k -> ujson.Arr.from(cs.map(candidateJson)))),
      "authenticated" -> ujson.Obj.from(s.authenticated.map((k, v) => k -> ujson.Num(v.toDouble))),
      "unresolved" -> ujson.Obj.from(s.unresolved.map((k, u) =>
        k -> ujson.Obj("seq" -> ujson.Num(u.seq.toDouble), "attempts" -> u.attempts, "error" -> u.error))),
      "rescan" -> s.rescan.fold[ujson.Value](ujson.Null)(c => ujson.Num(c.toDouble)),
      "rescanAt" -> ujson.Num(s.rescanAt.toDouble),
      "rescanWanted" -> s.rescanWanted
    )

  /** Also reads older state: one candidate per device under `latest` (its
    * failures taken from `unresolved`), and, before candidates kept the
    * listed size and SHA-256, a bare seq per device. Such bare candidates
    * have nothing to check a blob against: they are dropped and the cursor
    * restarts from 0, so the listing gives them again.
    */
  def fromJson(v: ujson.Value): StatusSyncState =
    val unresolved = v.obj.get("unresolved").flatMap(_.objOpt).fold(Map.empty[String, Unresolved])(_.toMap.map((d, u) =>
      d -> Unresolved(u("seq").num.toLong, u("attempts").num.toInt, u.obj.get("error").flatMap(_.strOpt).getOrElse(""))))
    val rawLatest = v.obj.get("latest").flatMap(_.objOpt).fold(Map.empty[String, ujson.Value])(_.toMap)
    val oldLatest = rawLatest.flatMap { (d, c) =>
      c.objOpt.map { o =>
        val cand = candidateOf(o)
        d -> Vector(cand.copy(failures = unresolved.get(d).filter(_.seq == cand.seq).fold(0)(_.attempts)))
      }
    }
    val legacy = oldLatest.size != rawLatest.size
    val candidates = v.obj.get("candidates").flatMap(_.objOpt).fold(oldLatest)(_.toMap.map((d, cs) =>
      d -> cs.arr.toVector.map(c => candidateOf(c.obj))))
    StatusSyncState(
      if legacy then 0L else v.obj.get("cursor").flatMap(_.numOpt).fold(0L)(_.toLong),
      candidates,
      v.obj.get("authenticated").flatMap(_.objOpt).fold(Map.empty[String, Long])(_.toMap.map((d, n) => d -> n.num.toLong)),
      unresolved,
      v.obj.get("rescan").flatMap(_.numOpt).map(_.toLong),
      v.obj.get("rescanAt").flatMap(_.numOpt).fold(0L)(_.toLong),
      v.obj.get("rescanWanted").flatMap(_.boolOpt).getOrElse(false)
    )
