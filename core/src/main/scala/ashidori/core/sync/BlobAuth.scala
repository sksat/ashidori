package ashidori.core.sync

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.util.Try

// Blob authentication v1.1 and its cut-over (docs/design/browser-pairing-v1.md,
// "blob の認証（v1.1）"; docs/sync-protocol.md, "Blob authentication").
//
// Apps write ASHM: the GCM part of ASH1 followed by a MAC under Ka, a key only
// apps hold. A viewer holds the batch key but not Ka, so it cannot make blobs
// an app accepts. ASH1 has no MAC: whoever holds the batch key (a viewer, or a
// leaked batch key together with the server) can make ASH1 blobs for any slot.
// So once a viewer may exist, an app accepts ASH1 only where the cut-over
// record C says the user's apps wrote it before.

/** A (device, stream) pair: the unit of the cut-over rules. */
final case class StreamKey(deviceId: String, stream: String)

/** Where a cut-over record C is: its slot (device, `authcut`, seq) and the
  * SHA-256 of its blob.
  */
final case class CutRef(deviceId: String, seq: Long, sha256: String):
  def toJson: ujson.Obj = ujson.Obj("deviceId" -> deviceId, "seq" -> ujson.Num(seq.toDouble), "sha256" -> sha256)

object CutRef:
  def of(b: BatchMeta): CutRef = CutRef(b.deviceId, b.seq, b.sha256.toLowerCase)

  /** None when malformed (the value comes from another device, or from disk). */
  def fromJson(v: ujson.Value): Option[CutRef] = Try {
    val seq = v("seq").num
    val r = CutRef(v("deviceId").str, seq.toLong, v("sha256").str.toLowerCase)
    Option.when(seq.isWhole && seq >= 0 && seq <= BatchMeta.MaxSeq.toDouble && r.deviceId.matches("[A-Za-z0-9_-]{1,64}") &&
      r.sha256.matches("[0-9a-f]{64}"))(r)
  }.toOption.flatten

/** One pair of C: the user's apps had written seq 0 to `below - 1` of it when C
  * was made, and `digest` fixes which blobs those were (see [[CutoverRecord.digest]]).
  */
final case class CutoverEntry(deviceId: String, stream: String, below: Long, digest: String):
  def key: StreamKey = StreamKey(deviceId, stream)

/** The cut-over record C: one entry per (device, stream) pair listed when it was made. */
final case class CutoverRecord(entries: Vector[CutoverEntry]):
  private lazy val byKey = entries.map(e => e.key -> e).toMap
  def entry(k: StreamKey): Option[CutoverEntry] = byKey.get(k)

object CutoverRecord:
  val Stream = "authcut"
  /** A pair whose `below` is larger is refused: the digest hashes 32 bytes per
    * seq, gaps included, so a listed row with a huge seq (made up by a
    * server) would make it unbounded. An hourly status stream reaches this
    * in 100 years.
    */
  val MaxBelow: Long = 1_000_000L
  val MaxEntries = 10_000
  /** Caps of a fetched C: the blob and its decompressed plaintext. */
  val MaxBlob: Int = 2 * 1024 * 1024
  val MaxPlain: Int = 2 * 1024 * 1024

  private val Zero = new Array[Byte](32)

  /** SHA-256 of the listed SHA-256 of seq 0 to `below - 1` of one pair,
    * concatenated in seq order, 32 zero bytes for a seq not listed. `rows`
    * are (seq, SHA-256 hex) of that pair; rows at or above `below` are
    * ignored. None when one seq is listed with two different SHA-256 (a slot
    * holds one blob) or a SHA-256 is malformed.
    */
  def digest(rows: Seq[(Long, String)], below: Long): Option[String] =
    require(below >= 0 && below <= MaxBelow, s"below $below out of range")
    val bySeq = rows.filter(_._1 < below).groupMap(_._1)(_._2.toLowerCase).view.mapValues(_.distinct).toMap
    if bySeq.values.exists(_.size != 1) || bySeq.values.exists(!_.head.matches("[0-9a-f]{64}")) then None
    else
      val md = MessageDigest.getInstance("SHA-256")
      var s = 0L
      while s < below do
        bySeq.get(s) match
          case Some(Seq(h)) => md.update(unhex(h))
          case _            => md.update(Zero)
        s += 1
      Some(Crypto.hex(md.digest()))

  /** C for a listing (every row of the user): one entry per pair except
    * `authcut`, `below` = the largest listed seq + 1. Throws when the listing
    * cannot make one (two rows for one slot, or a seq past [[MaxBelow]]).
    */
  def fromListing(rows: Seq[BatchMeta]): CutoverRecord =
    val pairs = rows.filter(_.stream != Stream).groupBy(b => StreamKey(b.deviceId, b.stream))
    if pairs.size > MaxEntries then throw IllegalStateException(s"more than $MaxEntries streams listed")
    CutoverRecord(pairs.toVector.sortBy((k, _) => (k.deviceId, k.stream)).map { (k, bs) =>
      val below = bs.map(_.seq).max + 1
      if below > MaxBelow then throw IllegalStateException(s"${k.deviceId}/${k.stream}: seq ${below - 1} is past $MaxBelow")
      val d = digest(bs.map(b => b.seq -> b.sha256), below)
        .getOrElse(throw IllegalStateException(s"${k.deviceId}/${k.stream}: two different rows for one slot"))
      CutoverEntry(k.deviceId, k.stream, below, d)
    })

  /** Plaintext (before gzip): JSON Lines, one `{deviceId, stream, below, digest}` per pair. */
  def encode(r: CutoverRecord): Array[Byte] =
    r.entries.map(e => ujson.write(ujson.Obj("deviceId" -> e.deviceId, "stream" -> e.stream,
      "below" -> ujson.Num(e.below.toDouble), "digest" -> e.digest)) + "\n").mkString.getBytes(UTF_8)

  /** Strict: every line must be a well-formed entry, and no pair appears twice. */
  def decode(plain: Array[Byte]): CutoverRecord =
    val lines = String(plain, UTF_8).split('\n').iterator.map(_.trim).filter(_.nonEmpty).toVector
    if lines.size > MaxEntries then throw LimitExceeded("cut-over entries", MaxEntries)
    val es = lines.map { l =>
      val v = ujson.read(l)
      val below = v("below").num
      val e = CutoverEntry(v("deviceId").str, v("stream").str, below.toLong, v("digest").str)
      require(below.isWhole && below >= 1 && below <= MaxBelow.toDouble && e.deviceId.matches("[A-Za-z0-9_-]{1,64}") &&
        e.stream.matches("[a-z0-9_-]{1,32}") && e.digest.matches("[0-9a-f]{64}"), s"malformed cut-over entry: $l")
      e
    }
    require(es.map(_.key).distinct.size == es.size, "a pair appears twice in the cut-over record")
    CutoverRecord(es)

  private[sync] def toJson(r: CutoverRecord): ujson.Value = ujson.Arr.from(r.entries.map(e =>
    ujson.Obj("deviceId" -> e.deviceId, "stream" -> e.stream, "below" -> ujson.Num(e.below.toDouble), "digest" -> e.digest)))

  private[sync] def fromJson(v: ujson.Value): CutoverRecord =
    decode(v.arr.map(ujson.write(_)).mkString("\n").getBytes(UTF_8))

  private def unhex(h: String): Array[Byte] = h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

/** Why an ASH1 blob is refused or needs more: see [[BlobAuthState.ash1Verdict]]. */
enum Verdict:
  case Accept
  case Reject(reason: String)
  /** The digest of the pair is to be compared: call again with the listed rows. */
  case NeedListing(entry: CutoverEntry)

/** An ASH1 blob refused by the cut-over rules (a possible downgrade). */
final class BlobRejected(reason: String) extends RuntimeException(s"ASH1 blob refused: $reason")

/** This app's state of the cut-over, per registration. Every transition only
  * adds knowledge or strictness (`required` never goes back to false), so a
  * state that may not have reached the disk is still a safe one to read.
  *
  * - `required`: C exists for this user; ASH1 is accepted only as C allows.
  * - `cut`: the C this device knows (made, accepted, or named by a status).
  *   It is named in this device's status.
  * - `record`: the content of C, once fetched and verified (ASHM under Ka).
  * - `minAshm`: per pair, the smallest seq at which an ASHM blob was accepted.
  * - `legacy`: per pair, ASH1 blobs accepted before the required state (seq →
  *   SHA-256), the newest [[BlobAuthState.MaxLegacy]] per pair. Dropped when
  *   the required state is entered.
  * - `found`: `authcut` rows listed and not tried yet; `refused`: those that
  *   did not verify (not listed as found again).
  * - `lost`: the stored state could not be read ([[BlobAuthState.Lost]]), so
  *   whether C exists is not known. `required` is set with it, so every ASH1
  *   not accepted since is refused, until a C is adopted (learned from the
  *   server, or made by [[Cutover.ensure]]); that clears it.
  */
final case class BlobAuthState(
    required: Boolean = false,
    cut: Option[CutRef] = None,
    record: Option[CutoverRecord] = None,
    minAshm: Map[StreamKey, Long] = Map.empty,
    legacy: Map[StreamKey, Map[Long, String]] = Map.empty,
    found: Vector[CutRef] = Vector.empty,
    refused: Vector[CutRef] = Vector.empty,
    lost: Boolean = false
):
  import BlobAuthState.*

  /** An ASHM blob of `deviceId` was accepted (the device writes v1.1). */
  def ashmFrom(deviceId: String): Boolean = minAshm.keys.exists(_.deviceId == deviceId)

  def ashmAccepted(k: StreamKey, seq: Long): BlobAuthState =
    copy(minAshm = minAshm.updated(k, minAshm.get(k).fold(seq)(math.min(_, seq))))

  def ash1Accepted(k: StreamKey, seq: Long, sha256: String): BlobAuthState =
    if required then this
    else
      val m = legacy.getOrElse(k, Map.empty).updated(seq, sha256.toLowerCase)
      copy(legacy = legacy.updated(k, if m.size <= MaxLegacy then m else m.toSeq.sortBy(-_._1).take(MaxLegacy).toMap))

  /** An accepted ASHM status names `ref`: C exists. ASH1 accepted before is
    * no longer accepted as such (`legacy` is dropped).
    */
  def named(ref: CutRef): BlobAuthState = copy(required = true, cut = cut.orElse(Some(ref)), legacy = Map.empty,
    found = if cut.exists(_ != ref) && record.isEmpty && !found.contains(ref) then (found :+ ref).takeRight(MaxFound) else found)

  /** C at `ref` was made here, or fetched and verified. The first record stays. */
  def adopted(ref: CutRef, rec: CutoverRecord): BlobAuthState =
    if record.isDefined then copy(required = true, legacy = Map.empty, found = found.filterNot(_ == ref), lost = false)
    else copy(required = true, cut = Some(ref), record = Some(rec), legacy = Map.empty, found = found.filterNot(_ == ref),
      lost = false)

  /** `authcut` rows of a listing page: kept to be tried while C is not fetched. */
  def listed(rows: Seq[BatchMeta]): BlobAuthState =
    if record.isDefined then this
    else
      val add = rows.filter(_.stream == CutoverRecord.Stream).map(CutRef.of)
        .filterNot(r => found.contains(r) || refused.contains(r) || cut.contains(r)).distinct
      if add.isEmpty then this else copy(found = (found ++ add).take(MaxFound))

  /** A listed C that did not verify. */
  def refusedCut(ref: CutRef): BlobAuthState =
    copy(found = found.filterNot(_ == ref), refused = (refused.filterNot(_ == ref) :+ ref).takeRight(MaxRefused))

  /** C to fetch while none is verified: the known one, then those listed. */
  def toFetch: Vector[CutRef] = if record.isDefined then Vector.empty else (cut.toVector ++ found).distinct

  /** Whether an ASH1 blob with SHA-256 `sha256` at `seq` of pair `k` may be
    * accepted, in this order:
    *  1. not required, and accepted before (as this very blob): yes;
    *  2. above the smallest seq at which ASHM of the pair was accepted: no
    *     (also before the required state);
    *  3. not required: yes;
    *  4. required and C not fetched, the pair not in C, or `seq` ≥ its
    *     `below`: no;
    *  5. otherwise the digest of the listed rows of the pair below `below`
    *     (`listed`, rows of any pair) must equal C's, and the blob must be
    *     the listed one at `seq`. Without `listed`: [[Verdict.NeedListing]].
    *
    * In the required state, a blob accepted before it is judged like any
    * other (4, 5): nothing accepted only because C was not known yet stays
    * accepted once it is.
    */
  def ash1Verdict(k: StreamKey, seq: Long, sha256: String, listed: Option[Seq[BatchMeta]] = None): Verdict =
    if !required && legacy.get(k).flatMap(_.get(seq)).exists(_.equalsIgnoreCase(sha256)) then Verdict.Accept
    else if minAshm.get(k).exists(seq > _) then Verdict.Reject(s"seq $seq is above ASHM accepted at seq ${minAshm(k)}")
    else if !required then Verdict.Accept
    else record match
      case None => Verdict.Reject(if lost then "the cut-over state was lost; C is not known again yet"
                                  else "the cut-over record is not fetched yet")
      case Some(r) => r.entry(k) match
        case None => Verdict.Reject("the stream is not in the cut-over record")
        case Some(e) if seq >= e.below => Verdict.Reject(s"seq $seq is at or above the cut-over (${e.below})")
        case Some(e) => listed match
          case None => Verdict.NeedListing(e)
          case Some(rows) =>
            val mine = rows.filter(b => b.deviceId == k.deviceId && b.stream == k.stream && b.seq < e.below)
            if !CutoverRecord.digest(mine.map(b => b.seq -> b.sha256), e.below).contains(e.digest) then
              Verdict.Reject("the listing does not match the cut-over digest")
            else if !mine.exists(b => b.seq == seq && b.sha256.equalsIgnoreCase(sha256)) then
              Verdict.Reject("not the blob listed under the cut-over")
            else Verdict.Accept

object BlobAuthState:
  val MaxLegacy = 16
  val MaxFound = 8
  val MaxRefused = 64

  /** The state when the stored one cannot be read: required, nothing known. */
  val Lost: BlobAuthState = BlobAuthState(required = true, lost = true)

  private def key(k: StreamKey, field: (String, ujson.Value)) = ujson.Obj("deviceId" -> k.deviceId, "stream" -> k.stream, field)
  private def keyOf(v: ujson.Value) = StreamKey(v("deviceId").str, v("stream").str)

  def toJson(s: BlobAuthState): ujson.Obj = ujson.Obj(
    "required" -> s.required,
    "cut" -> s.cut.fold[ujson.Value](ujson.Null)(_.toJson),
    "record" -> s.record.fold[ujson.Value](ujson.Null)(CutoverRecord.toJson),
    "minAshm" -> ujson.Arr.from(s.minAshm.toSeq.sortBy((k, _) => (k.deviceId, k.stream)).map((k, q) =>
      key(k, "seq" -> ujson.Num(q.toDouble)))),
    "legacy" -> ujson.Arr.from(s.legacy.toSeq.sortBy((k, _) => (k.deviceId, k.stream)).map((k, m) =>
      key(k, "slots" -> ujson.Arr.from(m.toSeq.sorted.map((q, h) => ujson.Arr(ujson.Num(q.toDouble), h)))))),
    "found" -> ujson.Arr.from(s.found.map(_.toJson)),
    "refused" -> ujson.Arr.from(s.refused.map(_.toJson)),
    "lost" -> s.lost
  )

  def fromJson(v: ujson.Value): BlobAuthState = BlobAuthState(
    v("required").bool,
    Option(v("cut")).filterNot(_.isNull).flatMap(CutRef.fromJson),
    Option(v("record")).filterNot(_.isNull).map(CutoverRecord.fromJson),
    v("minAshm").arr.map(o => keyOf(o) -> o("seq").num.toLong).toMap,
    v("legacy").arr.map(o => keyOf(o) -> o("slots").arr.map(a => a(0).num.toLong -> a(1).str).toMap).toMap,
    v("found").arr.flatMap(CutRef.fromJson).toVector,
    v("refused").arr.flatMap(CutRef.fromJson).toVector,
    v.obj.get("lost").exists(_.bool)
  )

/** [[BlobAuthState]] of the current registration, kept in its own
  * [[DurableMap]] (nothing else writes it).
  *
  * [[update]] returns once the new state is on disk and throws otherwise;
  * callers act on a state (e.g. count a blob as read, or hand out a key)
  * only after it returned.
  *
  * When a write fails, the file may hold the old or the new state. The new
  * one is what [[state]] returns from then on (every transition only makes
  * the state stricter or adds knowledge, so reading the old one could forget
  * the required state), but it is not persisted ([[persisted]] is false):
  * the next [[update]] writes it even if it changes nothing, and returns
  * only once that write succeeded. A restart reads the file as it is.
  *
  * A file that cannot be read or does not parse at start, or a stored state
  * that does not parse, is not a first run (a missing file or key is): the
  * state is [[BlobAuthState.Lost]] (required, so no ASH1 is accepted) until
  * a C is adopted again. The first [[update]] tries to read the file again.
  */
final class BlobAuthStore(map: DurableMap):
  private val Key = "state"
  private val lock = Object()
  /** The newest state of a registration while it may not be on disk. */
  @volatile private var pending: Option[(String, BlobAuthState)] = None

  def state(reg: String): BlobAuthState = current(reg)._1

  /** Whether [[state]] of `reg` is the one on disk (a restart reads the same). */
  def persisted(reg: String): Boolean = current(reg)._2

  private def current(reg: String): (BlobAuthState, Boolean) =
    pending.filter(_._1 == reg).fold(disk(reg))(p => (p._2, false))

  /** The stored state of `reg` and whether it is the one on disk. */
  private def disk(reg: String): (BlobAuthState, Boolean) =
    val v = map.view
    if v.unreadable || v.isUnknown(Key) then (BlobAuthState.Lost, false)
    else v.getString(Key) match
      case None => (BlobAuthState(), true) // a first run, or cleared
      case Some(s) =>
        Try {
          val js = ujson.read(s)
          if js("reg").str != reg then BlobAuthState() // another registration's (cleared at unpairing)
          else BlobAuthState.fromJson(js("state"))
        }.fold(_ => (BlobAuthState.Lost, false), st => (st, true))

  /** Applies `f` and returns the new state once it is on disk; throws
    * [[IOException]] otherwise. `required` never goes back to false, and
    * `lost` only by adopting a C. Writes also when `f` changes nothing while
    * the state is not persisted.
    */
  def update(reg: String)(f: BlobAuthState => BlobAuthState): BlobAuthState = lock.synchronized {
    require(reg.nonEmpty, "no registration")
    if map.view.unreadable then map.reload()
    val (before, onDisk) = current(reg)
    val after = f(before)
    val next = after.copy(
      required = after.required || before.required,
      lost = after.lost || (before.lost && after.record.isEmpty))
    if next == before && onDisk then before
    else
      try
        map.commit(Key -> ujson.write(ujson.Obj("reg" -> reg, "state" -> BlobAuthState.toJson(next))))
        pending = None
        next
      catch
        case e: IOException =>
          pending = Some(reg -> next)
          throw e
  }

  /** Forgets the state (unpairing). Throws [[IOException]] if that is not on disk. */
  def clear(): Unit = lock.synchronized {
    pending = None
    map.clear()
  }

/** The minimal I/O for fetching C: the keys, the state, and one fetch. */
trait CutoverSource:
  def keys: BlobKeys
  def state: BlobAuthState
  /** Applies `f` to the state and returns once it is on disk, also when `f`
    * changes nothing ([[BlobAuthStore.update]]); throws [[IOException]] otherwise.
    */
  def update(f: BlobAuthState => BlobAuthState): BlobAuthState
  /** The blob in the slot of `ref` as the server serves it (at most [[CutoverRecord.MaxBlob]]). */
  def fetch(ref: CutRef): Array[Byte]
  /** Whether `e` thrown by [[fetch]] ends the run (e.g. a stop) rather than
    * showing that the C is not valid. Like a network error, it is thrown on.
    */
  def aborts(e: Throwable): Boolean = false

/** A device of the user as `GET /v1/devices` lists it. */
final case class DeviceRow(id: String, revoked: Boolean, viewer: Boolean)

/** What [[Cutover.ensure]] needs on top of [[CutoverSource]]. */
trait CutoverEnv extends CutoverSource:
  def selfId: String
  /** Sends this device's unsent `authcut` batches (throws on failure); true
    * when none is left.
    */
  def flush(): Boolean
  /** Every row of the listing from cursor 0; throws if it cannot be read to the end. */
  def listAll(): Vector[BatchMeta]
  def devices(): Vector[DeviceRow]
  /** Seals `plain` as ASHM for this device's next `authcut` seq and stores it
    * for upload (on disk when it returns). Returns where it will be.
    */
  def enqueue(plain: Array[Byte]): CutRef

/** Some devices of the user have not shown that they write ASHM. */
final class CutoverNotReady(val devices: Seq[String]) extends RuntimeException("すべての端末のアプリを更新してください")

object Cutover:
  /** A fetched C for `ref`, checked: the listed SHA-256, ASHM under Ka in its
    * slot, and a well-formed record. Throws otherwise.
    */
  def verify(keys: BlobKeys, ref: CutRef, blob: Array[Byte]): CutoverRecord =
    if !Crypto.sha256Hex(blob).equalsIgnoreCase(ref.sha256) then throw IllegalStateException("not the named cut-over blob")
    val plain = Crypto.open(keys, Crypto.aad(ref.deviceId, CutoverRecord.Stream, ref.seq), blob)
    CutoverRecord.decode(Crypto.gunzip(plain, CutoverRecord.MaxPlain))

  /** A network failure, as opposed to a blob that is not a valid C. */
  private def network(e: Throwable): Boolean = e.isInstanceOf[IOException] && !e.isInstanceOf[LimitExceeded]

  /** Fetches and verifies `ref`; on success the state has it as C (required).
    * A blob that is not a valid C is remembered as refused. A network error
    * is thrown.
    */
  def tryAdopt(src: CutoverSource, ref: CutRef): Option[CutoverRecord] =
    val got = Try(verify(src.keys, ref, src.fetch(ref)))
    got.failed.toOption.filter(e => network(e) || src.aborts(e)).foreach(e => throw e)
    got.toOption match
      case Some(rec) =>
        src.update(_.adopted(ref, rec).ashmAccepted(StreamKey(ref.deviceId, CutoverRecord.Stream), ref.seq))
        Some(rec)
      case None =>
        if !src.state.cut.contains(ref) then src.update(_.refusedCut(ref))
        None

  /** Tries the C this device still has to fetch (named by a status, or
    * listed), at most `max`, until one verifies. Network errors are thrown.
    */
  def learn(src: CutoverSource, max: Int = 4): Unit =
    src.state.toFetch.take(max).iterator.map(tryAdopt(src, _)).find(_.isDefined)

  /** Makes sure C exists and this device is in the required state; returns C.
    * Run by the approving app before the first viewer pairing.
    *
    * 1. This device's unsent C (from an earlier call cut short) is sent first.
    * 2. If the state already has a verified C, that is it.
    * 3. Else C named or listed is fetched and, if valid, used.
    * 4. Else every non-revoked device other than this one and the viewers
    *    must have had an ASHM blob accepted here; otherwise
    *    [[CutoverNotReady]] (the user updates the apps first).
    * 5. Else C is made from the whole listing, stored for upload, recorded
    *    as this device's C (required), and sent.
    *
    * Returns only once C is on the server and the required state on disk
    * (also in 2., where a state whose write failed is written again); throws
    * otherwise (network, storage), and may then be run again.
    */
  def ensure(env: CutoverEnv): CutRef =
    var sent = env.flush()
    val st = env.state
    st.cut.filter(_ => st.record.isDefined && st.required) match
      case Some(ref) =>
        if ref.deviceId == env.selfId && !sent then throw IOException("the cut-over record is not sent yet")
        // The state read may be one whose write failed: write it until it is on disk.
        env.update(identity)
        ref
      case None =>
        val rows = env.listAll()
        val listedCuts = rows.filter(_.stream == CutoverRecord.Stream).sortBy(_.cursor).map(CutRef.of)
        val existing = (st.toFetch ++ listedCuts).distinct.iterator.flatMap(r => tryAdopt(env, r).map(_ => r)).nextOption()
        existing.getOrElse {
          val now = env.state
          val missing = env.devices().filter(d => !d.revoked && !d.viewer && d.id != env.selfId && !now.ashmFrom(d.id))
          if missing.nonEmpty then throw CutoverNotReady(missing.map(_.id))
          val rec = CutoverRecord.fromListing(rows)
          val ref = env.enqueue(Crypto.gzip(CutoverRecord.encode(rec)))
          env.update(_.adopted(ref, rec).ashmAccepted(StreamKey(ref.deviceId, CutoverRecord.Stream), ref.seq))
          sent = env.flush()
          if !sent then throw IOException("the cut-over record is not sent yet")
          ref
        }

/** Opening a received blob as an app: ASHM with its MAC, ASH1 under the
  * cut-over rules of [[BlobAuthState.ash1Verdict]].
  */
object BlobAuth:
  sealed trait Result
  /** The plaintext, and the transition that records its acceptance. Persist
    * it (e.g. [[BlobAuthStore.update]]) before counting the blob as read.
    */
  final case class Opened(plain: Array[Byte], format: BlobFormat, accept: BlobAuthState => BlobAuthState) extends Result
  /** ASH1 whose acceptance needs the listed rows of `entry`'s pair: open again with them. */
  final case class NeedListing(entry: CutoverEntry) extends Result

  /** Opens `blob` of slot (`k`, `seq`). Throws if it is neither format, fails
    * its MAC or GCM, or is an ASH1 blob the rules refuse ([[BlobRejected]]).
    */
  def open(st: BlobAuthState, keys: BlobKeys, k: StreamKey, seq: Long, blob: Array[Byte],
           listed: Option[Seq[BatchMeta]] = None): Result =
    val aad = Crypto.aad(k.deviceId, k.stream, seq)
    Crypto.format(blob) match
      case Some(BlobFormat.Ashm) =>
        Opened(Crypto.open(keys, aad, blob), BlobFormat.Ashm, _.ashmAccepted(k, seq))
      case Some(BlobFormat.Ash1) =>
        val sha = Crypto.sha256Hex(blob)
        st.ash1Verdict(k, seq, sha, listed) match
          case Verdict.Accept         => Opened(Crypto.openAsh1(keys.batch, aad, blob), BlobFormat.Ash1, _.ash1Accepted(k, seq, sha))
          case Verdict.Reject(why)    => throw BlobRejected(why)
          case Verdict.NeedListing(e) => NeedListing(e)
      case None => throw IllegalArgumentException("not a ashidori blob")

/** What [[Intake.run]] needs on top of [[CutoverSource]]. */
trait IntakeEnv extends CutoverSource:
  /** The listed rows of pair `k`, for the digest of C; None when they cannot
    * be read now (next run). Throws [[BlobRejected]] when the listing is too
    * long to check.
    */
  def listedRows(k: StreamKey): Option[Seq[BatchMeta]]

/** The blobs one sync run received, opened in the order the cut-over needs
  * (docs/design/browser-pairing-v1.md, "blob の認証（v1.1）"):
  *
  *  1. every ASHM blob, with its acceptance and the C its content names
  *     (`names`, e.g. a status's `authcut`) stored;
  *  2. the C still to fetch (named, or listed earlier in the run) is learned;
  *  3. only then the ASH1 blobs, against the state that results. If 1. or 2.
  *     could not be stored or fetched completely, or the state is not on disk,
  *     ASH1 waits for the next run ([[Intake.Deferred]]).
  *
  * So in one run, the arrival order of C, the status naming it and an ASH1
  * blob does not matter.
  */
object Intake:
  final case class Item[A](id: A, key: StreamKey, seq: Long, blob: Array[Byte])

  sealed trait Result
  /** Opened, and its acceptance is on disk. `provisional`: ASH1 accepted
    * before the required state (not authenticated; see [[BlobAuthState]]).
    */
  final case class Accepted(plain: Array[Byte], format: BlobFormat, provisional: Boolean) extends Result
  /** Not usable: not a blob, a failed MAC or GCM, or refused by the cut-over rules. */
  final case class Refused(error: Exception) extends Result
  /** Not decided this run (storage, the listing, or the cut-over not settled). */
  case object Deferred extends Result

  /** Results in the order of `items`. `names` gets the plaintext of an ASHM
    * blob and returns the C it names; a throw refuses the blob.
    */
  def run[A](env: IntakeEnv, items: Seq[Item[A]], names: (A, Array[Byte]) => Option[CutRef]): Vector[(A, Result)] =
    val out = scala.collection.mutable.Map.empty[Int, Result]
    var settled = true
    def store(f: BlobAuthState => BlobAuthState): Boolean =
      try
        env.update(f)
        true
      catch case _: IOException => false
    val indexed = items.zipWithIndex
    for (it, i) <- indexed if !Crypto.format(it.blob).contains(BlobFormat.Ashm) && !Crypto.format(it.blob).contains(BlobFormat.Ash1) do
      out(i) = Refused(IllegalArgumentException("not a ashidori blob"))
    // 1. ASHM, and the C it names.
    for (it, i) <- indexed if Crypto.format(it.blob).contains(BlobFormat.Ashm) do
      out(i) =
        try
          BlobAuth.open(env.state, env.keys, it.key, it.seq, it.blob) match
            case o: BlobAuth.Opened =>
              val ref = names(it.id, o.plain)
              if store(s => ref.fold(o.accept(s))(o.accept(s).named)) then Accepted(o.plain, o.format, false)
              else
                settled = false
                Deferred
            case BlobAuth.NeedListing(_) => Deferred // not for ASHM
        catch case e: Exception => Refused(e)
    // 2. The C to fetch, then the state on disk (also one whose write failed before).
    try
      Cutover.learn(env)
      env.update(identity)
    catch case _: IOException => settled = false
    // 3. ASH1.
    for (it, i) <- indexed if Crypto.format(it.blob).contains(BlobFormat.Ash1) do
      out(i) =
        if !settled then Deferred
        else
          try
            val st = env.state
            val opened = BlobAuth.open(st, env.keys, it.key, it.seq, it.blob) match
              case o: BlobAuth.Opened => Some(o)
              case BlobAuth.NeedListing(_) =>
                env.listedRows(it.key).map(rows => BlobAuth.open(st, env.keys, it.key, it.seq, it.blob, Some(rows))).collect {
                  case o: BlobAuth.Opened => o
                }
            opened match
              case None => Deferred
              case Some(o) => if store(o.accept) then Accepted(o.plain, o.format, !st.required) else Deferred
          catch case e: Exception => Refused(e)
    indexed.map((it, i) => it.id -> out(i)).toVector
