package ashidori.core

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.mutable
import ashidori.core.sync.*

/** Blob authentication v1.1 (ASHM) and the cut-over rules
  * (docs/design/browser-pairing-v1.md, "blob の認証（v1.1）").
  */
class BlobAuthSuite extends munit.FunSuite:
  private val master = Crypto.newMasterKey()
  private val keys = BlobKeys.of(master)
  /** What a viewer holds: the batch key, but not Ka. */
  private val viewerBatchKey = keys.batch

  private val devA = "devA"
  private val devB = "devB"
  private val places = StreamKey(devA, "places")

  /** A tiny server: one blob per slot, a listing with increasing cursors. */
  private final class Server:
    val blobs = mutable.LinkedHashMap.empty[(String, String, Long), Array[Byte]]
    private var cursor = 0L
    val rows = mutable.ArrayBuffer.empty[BatchMeta]
    def put(dev: String, stream: String, seq: Long, blob: Array[Byte]): BatchMeta =
      require(!blobs.contains((dev, stream, seq)), "slot taken")
      blobs((dev, stream, seq)) = blob
      cursor += 3
      val m = BatchMeta(cursor, dev, stream, seq, blob.length, Crypto.sha256Hex(blob))
      rows += m
      m

  private def plain(s: String) = Crypto.gzip(s.getBytes(UTF_8))
  private def ashm(dev: String, stream: String, seq: Long, s: String = "x") = Crypto.seal(keys, Crypto.aad(dev, stream, seq), plain(s))
  private def ash1(dev: String, stream: String, seq: Long, s: String = "x") = Crypto.sealAsh1(keys.batch, Crypto.aad(dev, stream, seq), plain(s))

  /** Opens as an app, applying the acceptance; Left(reason) when refused. */
  private def accept(st: BlobAuthState, k: StreamKey, seq: Long, blob: Array[Byte],
                     listed: Option[Seq[BatchMeta]] = None): Either[String, (String, BlobAuthState)] =
    try BlobAuth.open(st, keys, k, seq, blob, listed) match
      case BlobAuth.Opened(p, _, f) => Right(String(Crypto.gunzip(p), UTF_8) -> f(st))
      case BlobAuth.NeedListing(_)  => Left("need listing")
    catch case e: Exception => Left(e.getMessage)

  // --- ASHM through BlobAuth.open ---------------------------------------

  test("ASHM round trip records the smallest accepted seq per pair") {
    val st = BlobAuthState()
    val Right((text, s1)) = accept(st, places, 4, ashm(devA, "places", 4, "four")): @unchecked
    assertEquals(text, "four")
    assertEquals(s1.minAshm(places), 4L)
    val Right((_, s2)) = accept(s1, places, 2, ashm(devA, "places", 2)): @unchecked
    assertEquals(s2.minAshm(places), 2L)
    assert(s2.ashmFrom(devA) && !s2.ashmFrom(devB))
  }

  test("ASHM moved to another slot, sealed with a wrong Ka, or with any byte changed is refused") {
    val blob = ashm(devA, "places", 4)
    assert(accept(BlobAuthState(), places, 5, blob).isLeft)
    assert(accept(BlobAuthState(), StreamKey(devB, "places"), 4, blob).isLeft)
    val viewerKeys = BlobKeys(viewerBatchKey, Crypto.authKey(Crypto.newMasterKey()))
    assert(accept(BlobAuthState(), places, 4, Crypto.seal(viewerKeys, Crypto.aad(devA, "places", 4), plain("forged"))).isLeft)
    for i <- blob.indices do
      val bad = blob.clone()
      bad(i) = (bad(i) ^ 0x10).toByte
      assert(accept(BlobAuthState(), places, 4, bad).isLeft, s"byte $i")
  }

  // --- ASH1 before the required state -----------------------------------

  test("ASH1 is accepted before the required state, below any accepted ASHM, and recorded") {
    val old = ash1(devA, "places", 0, "old")
    val Right((text, st)) = accept(BlobAuthState(), places, 0, old): @unchecked
    assertEquals(text, "old")
    assertEquals(st.legacy(places), Map(0L -> Crypto.sha256Hex(old)))
    val withAshm = st.ashmAccepted(places, 3)
    assert(accept(withAshm, places, 2, ash1(devA, "places", 2)).isRight)
    assert(accept(withAshm, places, 3, ash1(devA, "places", 3)).isRight) // not above: the slot holds one blob
  }

  test("downgrade before the required state: ASH1 above an accepted ASHM is refused") {
    val st = BlobAuthState().ashmAccepted(places, 3)
    val forged = ash1(devA, "places", 4, "forged by a batch-key holder")
    assert(accept(st, places, 4, forged).left.exists(_.contains("above ASHM")))
    // A genuine ASHM blob with its MAC stripped (made ASH1 again) likewise.
    val genuine = ashm(devA, "places", 5)
    val stripped = Crypto.Magic ++ genuine.slice(4, genuine.length - Crypto.MacLen)
    assertEquals(Crypto.format(stripped), Some(BlobFormat.Ash1))
    assert(accept(st, places, 5, stripped).left.exists(_.contains("above ASHM")))
    // Other pairs are not affected.
    assert(accept(st, StreamKey(devA, "checkins"), 9, ash1(devA, "checkins", 9)).isRight)
  }

  test("residual risk: before the required state, a forged ASH1 of a pair never seen as ASHM is accepted") {
    val ghost = StreamKey("made-up-device", "places")
    val forged = Crypto.sealAsh1(viewerBatchKey, Crypto.aad(ghost.deviceId, "places", 0), plain("forged"))
    assert(accept(BlobAuthState(), ghost, 0, forged).isRight)
    // The same in the required state is refused (the pair is not in C).
    val req = BlobAuthState(required = true, record = Some(CutoverRecord(Vector.empty)))
    assert(accept(req, ghost, 0, forged).left.exists(_.contains("not in the cut-over")))
  }

  // --- The cut-over record C --------------------------------------------

  test("digest: SHA-256 of the listed SHA-256 in seq order, 32 zero bytes for a missing seq") {
    val h0 = Crypto.sha256Hex("a".getBytes(UTF_8))
    val h2 = Crypto.sha256Hex("c".getBytes(UTF_8))
    val md = MessageDigest.getInstance("SHA-256")
    def unhex(h: String) = h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    md.update(unhex(h0)); md.update(new Array[Byte](32)); md.update(unhex(h2))
    assertEquals(CutoverRecord.digest(Seq(2L -> h2, 0L -> h0, 3L -> h0), below = 3), Some(Crypto.hex(md.digest())))
    // Upper-case hex is the same value; two different rows for one seq is no digest.
    assertEquals(CutoverRecord.digest(Seq(0L -> h0.toUpperCase), 1), CutoverRecord.digest(Seq(0L -> h0), 1))
    assertEquals(CutoverRecord.digest(Seq(0L -> h0, 0L -> h2), 1), None)
    assertEquals(CutoverRecord.digest(Nil, 0), Some(Crypto.sha256Hex(Array.emptyByteArray)))
  }

  test("C from a listing: one entry per pair except authcut; refuses two rows for a slot and a huge seq") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    s.put(devA, "places", 2, ashm(devA, "places", 2))
    s.put(devB, "status", 0, ashm(devB, "status", 0))
    s.put(devA, CutoverRecord.Stream, 0, ashm(devA, CutoverRecord.Stream, 0))
    val r = CutoverRecord.fromListing(s.rows.toSeq)
    assertEquals(r.entries.map(e => (e.deviceId, e.stream, e.below)), Vector((devA, "places", 3L), (devB, "status", 1L)))
    assertEquals(CutoverRecord.decode(CutoverRecord.encode(r)), r)
    val dup = s.rows.head.copy(cursor = 1000, sha256 = "0" * 64)
    intercept[IllegalStateException](CutoverRecord.fromListing(s.rows.toSeq :+ dup))
    intercept[IllegalStateException](CutoverRecord.fromListing(Seq(s.rows.head.copy(seq = CutoverRecord.MaxBelow))))
    // Strict decoding.
    for bad <- Seq("""{"deviceId":"a","stream":"places","below":0,"digest":"%s"}""".format("0" * 64),
                   """{"deviceId":"a","stream":"places","below":1.5,"digest":"%s"}""".format("0" * 64),
                   """{"deviceId":"a","stream":"places","below":1,"digest":"xyz"}""",
                   """{"deviceId":"a/b","stream":"places","below":1,"digest":"%s"}""".format("0" * 64)) do
      intercept[Exception](CutoverRecord.decode(bad.getBytes(UTF_8)))
    val line = """{"deviceId":"a","stream":"places","below":1,"digest":"%s"}""".format("0" * 64)
    intercept[IllegalArgumentException](CutoverRecord.decode(s"$line\n$line".getBytes(UTF_8)))
  }

  /** A history: places 0, 1 ASH1, a gap at 2, 3 ASHM; C over it. */
  private def history(): (Server, CutoverRecord, Array[Byte], Array[Byte]) =
    val s = Server()
    val p0 = ash1(devA, "places", 0, "p0")
    val p1 = ash1(devA, "places", 1, "p1")
    s.put(devA, "places", 0, p0)
    s.put(devA, "places", 1, p1)
    s.put(devA, "places", 3, ashm(devA, "places", 3, "p3"))
    (s, CutoverRecord.fromListing(s.rows.toSeq), p0, p1)

  test("required state: ASH1 below the cut-over with a matching digest is accepted, after the listing is asked for") {
    val (s, rec, p0, p1) = history()
    val st = BlobAuthState(required = true, record = Some(rec))
    assertEquals(accept(st, places, 1, p1), Left("need listing"))
    val Right((text, after)) = accept(st, places, 1, p1, Some(s.rows.toSeq)): @unchecked
    assertEquals(text, "p1")
    // In the required state every ASH1 is judged against C, also one accepted before.
    assertEquals(after, st)
    assertEquals(accept(after, places, 1, p1), Left("need listing"))
    assert(accept(st, places, 0, p0, Some(s.rows.toSeq)).isRight)
  }

  test("downgrade in the required state: ASH1 at or above the cut-over, in a gap, or not the listed blob is refused") {
    val (s, rec, p0, _) = history()
    val st = BlobAuthState(required = true, record = Some(rec))
    val listed = Some(s.rows.toSeq)
    // Above the cut-over (below = 4).
    assert(accept(st, places, 4, ash1(devA, "places", 4), listed).left.exists(_.contains("at or above")))
    // In the gap at 2: the server lists a new ASH1 there, so the digest no longer matches.
    val forged = ash1(devA, "places", 2, "forged")
    s.put(devA, "places", 2, forged)
    assert(accept(st, places, 2, forged, Some(s.rows.toSeq)).left.exists(_.contains("digest")))
    // ... which also refuses the genuine ASH1 of the pair that was not accepted before.
    assert(accept(st, places, 0, p0, Some(s.rows.toSeq)).left.exists(_.contains("digest")))
    // Another blob for a listed slot (the listing names p0's hash).
    val other = ash1(devA, "places", 0, "other")
    assert(accept(st, places, 0, other, listed).left.exists(_.contains("not the blob listed")))
    // A listing whose row for slot 0 was replaced: digest mismatch.
    val replaced = s.rows.toSeq.filterNot(_.seq == 2).map(r => if r.seq == 0 then r.copy(sha256 = Crypto.sha256Hex(other)) else r)
    assert(accept(st, places, 0, other, Some(replaced)).left.exists(_.contains("digest")))
    // A pair that was not in C.
    assert(accept(st, StreamKey(devB, "places"), 0, ash1(devB, "places", 0), listed).left.exists(_.contains("not in the cut-over")))
  }

  test("required state: ASH1 above an accepted ASHM is refused even below the cut-over with a matching digest") {
    val (s, rec, _, p1) = history()
    val st = BlobAuthState(required = true, record = Some(rec)).ashmAccepted(places, 0)
    assert(accept(st, places, 1, p1, Some(s.rows.toSeq)).left.exists(_.contains("above ASHM")))
  }

  test("C named but not fetched: every ASH1 is refused, also one accepted before") {
    val p0 = ash1(devA, "places", 0, "p0")
    val Right((_, before)) = accept(BlobAuthState(), places, 0, p0): @unchecked
    assert(accept(before, places, 0, p0).isRight)
    val named = before.named(CutRef(devB, 0, "0" * 64))
    assert(named.required)
    assertEquals((named.record, named.legacy), (None, Map.empty))
    // Accepted only because C was not known yet: not grandfathered.
    assert(accept(named, places, 0, p0).left.exists(_.contains("not fetched")))
    assert(accept(named, places, 1, ash1(devA, "places", 1)).left.exists(_.contains("not fetched")))
    // ASHM is unaffected.
    assert(accept(named, places, 2, ashm(devA, "places", 2)).isRight)
  }

  test("transitions: named, adopted, listed and refused C") {
    val c1 = CutRef(devA, 0, "1" * 64)
    val c2 = CutRef(devB, 0, "2" * 64)
    val rec = CutoverRecord(Vector(CutoverEntry(devA, "places", 1, "0" * 64)))
    val s1 = BlobAuthState().named(c1).named(c2)
    assertEquals((s1.required, s1.cut, s1.toFetch), (true, Some(c1), Vector(c1, c2)))
    val s2 = s1.adopted(c2, rec)
    assertEquals((s2.cut, s2.record, s2.toFetch), (Some(c2), Some(rec), Vector.empty))
    // The first record stays.
    assertEquals(s2.adopted(c1, CutoverRecord(Vector.empty)).record, Some(rec))
    val row = BatchMeta(9, devB, CutoverRecord.Stream, 4, 100, "3" * 64)
    val s3 = BlobAuthState().listed(Seq(row))
    assertEquals(s3.found, Vector(CutRef.of(row)))
    assert(!s3.required)
    val s4 = s3.refusedCut(CutRef.of(row))
    assertEquals((s4.found, s4.listed(Seq(row)).found), (Vector.empty, Vector.empty))
    // Only the first-time state has seen nothing of C.
    assert(!BlobAuthState().cutSeen)
    assert(Seq(s1, s2, s3, s4, BlobAuthState.Lost).forall(_.cutSeen))
    // More listed than kept: overflow, through JSON too; an adopted C ends it.
    val many = (0 to BlobAuthState.MaxFound).map(i => row.copy(cursor = 20L + i, deviceId = s"d$i"))
    val s5 = BlobAuthState().listed(many)
    assert(s5.overflow && s5.found.size == BlobAuthState.MaxFound)
    assertEquals(BlobAuthState.fromJson(BlobAuthState.toJson(s5)), s5)
    assert(!s5.adopted(c1, rec).overflow)
  }

  // --- Persistence --------------------------------------------------------

  test("the state survives a restart, belongs to its registration, and never leaves the required state") {
    val f = MemFile()
    val store = BlobAuthStore(DurableMap(f))
    val rec = CutoverRecord(Vector(CutoverEntry(devA, "places", 2, "0" * 64)))
    store.update("reg1")(_.ashmAccepted(places, 5).ash1Accepted(places, 1, "a" * 64))
    store.update("reg1")(_.adopted(CutRef(devA, 0, "b" * 64), rec))
    val restarted = BlobAuthStore(DurableMap(f))
    val st = restarted.state("reg1")
    assertEquals(st, store.state("reg1"))
    assert(st.required)
    // ASH1 accepted before the required state is not kept as accepted.
    assertEquals((st.minAshm(places), st.legacy.get(places), st.record), (5L, None, Some(rec)))
    // Another registration starts empty.
    assertEquals(restarted.state("reg2"), BlobAuthState())
    // A transition cannot clear `required`.
    assert(restarted.update("reg1")(_.copy(required = false)).required)
    assert(BlobAuthStore(DurableMap(f)).state("reg1").required)
    restarted.clear()
    assertEquals(BlobAuthStore(DurableMap(f)).state("reg1"), BlobAuthState())
  }

  test("a crash during a write: the file holds the old or the new state; a failed write keeps reading safely") {
    val f = MemFile()
    val store = BlobAuthStore(DurableMap(f))
    store.update("r")(_.ashmAccepted(places, 3))
    assert(store.persisted("r"))
    // The write fails and the file stays as it was: the caller is told, and
    // the stricter (new) state is read, but it is not persisted.
    f.onReplace = _ => throw java.io.IOException("disk full")
    intercept[java.io.IOException](store.update("r")(_.named(CutRef(devB, 0, "c" * 64))))
    assert(store.state("r").required)
    assert(!store.persisted("r"))
    assert(!BlobAuthStore(DurableMap(f)).state("r").required)
    f.onReplace = _ => ()
    store.update("r")(identity)
    assert(store.persisted("r"))
    assert(BlobAuthStore(DurableMap(f)).state("r").required)
    // The write fails and the file cannot be read back: either may be on disk,
    // so the stricter (new) state is read until a write succeeds.
    val g = MemFile()
    val other = BlobAuthStore(DurableMap(g))
    other.update("r")(_.ashmAccepted(places, 3))
    g.onReplace = b =>
      g.content = Some(b) // the new content did reach the disk
      g.unreadable = true
      throw java.io.IOException("lost answer")
    intercept[java.io.IOException](other.update("r")(_.named(CutRef(devB, 0, "c" * 64))))
    assert(other.state("r").required)
    assert(!other.persisted("r"))
    // A restart reads the file as it is.
    g.unreadable = false
    g.onReplace = _ => ()
    assert(BlobAuthStore(DurableMap(g)).state("r").required)
    // The next write goes ahead and keeps the required state.
    assert(other.update("r")(_.ashmAccepted(places, 1)).required)
    assertEquals(BlobAuthStore(DurableMap(g)).state("r").minAshm(places), 1L)
  }

  test("a failed save, then the same update again, then a restart: the retry writes it") {
    val f = MemFile()
    val store = BlobAuthStore(DurableMap(f))
    store.update("r")(_.ashmAccepted(places, 3))
    val ref = CutRef(devB, 0, "c" * 64)
    f.onReplace = _ => throw java.io.IOException("disk full")
    intercept[java.io.IOException](store.update("r")(_.named(ref)))
    // The same update while the state is not persisted is not skipped: it is written, or throws.
    intercept[java.io.IOException](store.update("r")(_.named(ref)))
    assert(!store.persisted("r"))
    f.onReplace = _ => ()
    assertEquals(store.update("r")(_.named(ref)).cut, Some(ref))
    assert(store.persisted("r"))
    val restarted = BlobAuthStore(DurableMap(f)).state("r")
    assert(restarted.required)
    assertEquals(restarted.cut, Some(ref))
    // Once persisted, an update that changes nothing does not write.
    var writes = 0
    f.onReplace = _ => writes += 1
    store.update("r")(_.named(ref))
    assertEquals(writes, 0)
  }

  test("a corrupt file at start is not a first run: no ASH1 is accepted until C is adopted again") {
    val blob = ash1(devB, "places", 0)
    val kB = StreamKey(devB, "places")
    val corruptFile = MemFile()
    corruptFile.content = Some("not json".getBytes(UTF_8))
    // A file that parses, whose stored state does not.
    val corruptState = MemFile()
    DurableMap(corruptState).commit("state" -> "{\"reg\":\"r\",\"state\":{")
    for f <- Seq(corruptFile, corruptState) do
      val store = BlobAuthStore(DurableMap(f))
      val st = store.state("r")
      assertEquals(st, BlobAuthState.Lost)
      assert(st.required && st.lost && !store.persisted("r"))
      assert(accept(st, kB, 0, blob).isLeft)
      // ASHM is still accepted; recording it writes the lost state, which a restart keeps.
      val Right((_, s1)) = accept(st, kB, 1, ashm(devB, "places", 1)): @unchecked
      store.update("r")(_ => s1.copy(required = false, lost = false))
      val again = BlobAuthStore(DurableMap(f)).state("r")
      assert(again.required && again.lost)
      assert(accept(again, kB, 0, blob).isLeft)
      // Adopting a C ends it: ASH1 below the cut-over and listed as in C is accepted again.
      val s = Server()
      val row = s.put(devB, "places", 0, blob)
      val rec = CutoverRecord.fromListing(Seq(row))
      val adopted = store.update("r")(_.adopted(CutRef(devA, 0, "e" * 64), rec))
      assert(adopted.required && !adopted.lost)
      assert(accept(adopted, kB, 0, blob, Some(Seq(row))).isRight)
      assert(!BlobAuthStore(DurableMap(f)).state("r").lost)
  }

  test("a read failure at start: ASH1 is refused; the first update reads the file again") {
    val f = MemFile()
    val good = BlobAuthStore(DurableMap(f))
    val rec = CutoverRecord(Vector(CutoverEntry(devA, "places", 2, "0" * 64)))
    good.update("r")(_.adopted(CutRef(devA, 0, "b" * 64), rec))
    f.unreadable = true
    val store = BlobAuthStore(DurableMap(f))
    assertEquals(store.state("r"), BlobAuthState.Lost)
    assert(accept(store.state("r"), StreamKey(devB, "places"), 0, ash1(devB, "places", 0)).isLeft)
    // Still unreadable: the update writes the lost state rather than a guess of what was there.
    val other = BlobAuthStore(DurableMap(f))
    assert(other.update("r")(_.ashmAccepted(places, 9)).lost)
    // Readable again by the next update of a store that saw the failure: the
    // state on disk is read and kept, not replaced by the lost one.
    val f2 = MemFile()
    BlobAuthStore(DurableMap(f2)).update("r")(_.adopted(CutRef(devA, 0, "b" * 64), rec))
    f2.unreadable = true
    val late = BlobAuthStore(DurableMap(f2))
    assert(late.state("r").lost)
    f2.unreadable = false
    val after = late.update("r")(_.ashmAccepted(places, 1))
    assert(!after.lost && after.required)
    assertEquals(after.record, Some(rec))
  }

  // --- Making and learning C (Cutover) ----------------------------------

  /** An app (`self`) against `server`, with its outbox and its state. */
  private class App(val selfId: String, val server: Server, val file: MemFile = MemFile()) extends CutoverEnv:
    val keys: BlobKeys = BlobAuthSuite.this.keys
    val store = BlobAuthStore(DurableMap(file))
    var devs = Vector(DeviceRow(devA, false, false), DeviceRow(devB, false, false))
    val outbox = mutable.Queue.empty[(String, Long, Array[Byte])]
    var nextSeq = 0L
    var failFlush = false
    var listings = 0
    def state: BlobAuthState = store.state("reg")
    def update(f: BlobAuthState => BlobAuthState): BlobAuthState = store.update("reg")(f)
    var offline = false
    def fetch(ref: CutRef): Array[Byte] =
      if offline then throw java.io.IOException("offline")
      server.blobs.getOrElse((ref.deviceId, CutoverRecord.Stream, ref.seq), throw IllegalStateException("404"))
    def flush(): Boolean =
      if failFlush && outbox.nonEmpty then throw java.io.IOException("offline")
      while outbox.nonEmpty do
        val (stream, seq, blob) = outbox.dequeue()
        server.put(selfId, stream, seq, blob)
      true
    def listAll(): Vector[BatchMeta] =
      listings += 1
      server.rows.toVector
    def devices(): Vector[DeviceRow] = devs
    def enqueue(plain: Array[Byte]): CutRef =
      val seq = nextSeq
      nextSeq += 1
      val blob = Crypto.seal(keys, Crypto.aad(selfId, CutoverRecord.Stream, seq), plain)
      outbox.enqueue((CutoverRecord.Stream, seq, blob))
      CutRef(selfId, seq, Crypto.sha256Hex(blob))

  test("ensureCutover refuses until every other live device has shown ASHM; viewers and revoked devices do not count") {
    val s = Server()
    s.put(devB, "status", 0, ash1(devB, "status", 0))
    val app = App(devA, s)
    val e = intercept[CutoverNotReady](Cutover.ensure(app))
    assertEquals(e.devices, Seq(devB))
    assertEquals(e.getMessage, "すべての端末のアプリを更新してください")
    assert(!app.state.required)
    assert(s.rows.forall(_.stream != CutoverRecord.Stream))
    app.devs = Vector(DeviceRow(devA, false, false), DeviceRow(devB, true, false), DeviceRow("viewer1", false, true))
    assert(Cutover.ensure(app).deviceId == devA)
  }

  test("ensureCutover makes C over the listing, sends it, enters the required state, and is idempotent") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    s.put(devB, "status", 0, ash1(devB, "status", 0))
    s.put(devB, "status", 1, ashm(devB, "status", 1))
    val app = App(devA, s)
    app.update(_.ashmAccepted(StreamKey(devB, "status"), 1))
    val ref = Cutover.ensure(app)
    assert(app.state.required)
    assertEquals(app.state.cut, Some(ref))
    val onServer = s.blobs((devA, CutoverRecord.Stream, ref.seq))
    assertEquals(Crypto.sha256Hex(onServer), ref.sha256)
    val rec = Cutover.verify(keys, ref, onServer)
    assertEquals(rec.entries.map(e => (e.deviceId, e.stream, e.below)), Vector((devA, "places", 1L), (devB, "status", 2L)))
    assertEquals(app.state.record, Some(rec))
    // Again: the same C, without listing again.
    val n = app.listings
    assertEquals(Cutover.ensure(app), ref)
    assertEquals(app.listings, n)
  }

  test("ensureCutover returns only once C is sent; a crash or failure in between is finished by the next call") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val file = MemFile()
    val app = App(devA, s, file)
    app.devs = Vector(DeviceRow(devA, false, false))
    app.failFlush = true
    intercept[java.io.IOException](Cutover.ensure(app))
    // C is stored for upload and the required state is on disk, but C is not on the server.
    assert(BlobAuthStore(DurableMap(file)).state("reg").required)
    assert(s.rows.forall(_.stream != CutoverRecord.Stream))
    app.failFlush = false
    val ref = Cutover.ensure(app)
    assert(s.blobs.contains((devA, CutoverRecord.Stream, ref.seq)))
    assertEquals(app.nextSeq, 1L) // no second C
  }

  test("ensureCutover does not return on a required state that is only cached, also when it reuses C") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val file = MemFile()
    val app = App(devA, s, file)
    app.devs = Vector(DeviceRow(devA, false, false))
    file.onReplace = _ => throw java.io.IOException("disk full")
    intercept[java.io.IOException](Cutover.ensure(app))
    // The state read has C (required), but it is not on disk: reusing it must still write it.
    assert(app.state.required && app.state.record.isDefined)
    intercept[java.io.IOException](Cutover.ensure(app))
    assert(!BlobAuthStore(DurableMap(file)).state("reg").required)
    file.onReplace = _ => ()
    val ref = Cutover.ensure(app)
    assertEquals(app.nextSeq, 1L)
    val restarted = BlobAuthStore(DurableMap(file)).state("reg")
    assert(restarted.required)
    assertEquals(restarted.cut, Some(ref))
  }

  test("ensureCutover after a lost state re-learns the existing C") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val maker = App(devB, s)
    maker.devs = Vector(DeviceRow(devB, false, false))
    val ref = Cutover.ensure(maker)
    val file = MemFile()
    file.content = Some("garbage".getBytes(UTF_8))
    val app = App(devA, s, file)
    assert(app.state.lost)
    assertEquals(Cutover.ensure(app), ref)
    assertEquals(app.nextSeq, 0L)
    val st = BlobAuthStore(DurableMap(file)).state("reg")
    assert(st.required && !st.lost && st.record.isDefined)
  }

  /** What a server with a leaked batch key can use: the batch key, and a wrong Ka. */
  private val viewerKeys = BlobKeys(viewerBatchKey, Crypto.authKey(Crypto.newMasterKey()))

  /** A C forged with the batch key only (ASHM with a wrong Ka), in slot (`dev`, `authcut`, `seq`). */
  private def fakeCut(dev: String, seq: Long): Array[Byte] =
    val fakeRec = CutoverRecord.encode(CutoverRecord(Vector(CutoverEntry(devA, "places", 99, "0" * 64))))
    Crypto.seal(viewerKeys, Crypto.aad(dev, CutoverRecord.Stream, seq), Crypto.gzip(fakeRec))

  test("ensureCutover uses an existing C, and refuses a listed C that does not verify") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val other = App(devB, s)
    other.devs = Vector(DeviceRow(devB, false, false))
    val real = Cutover.ensure(other)
    val fake = s.put("devC", CutoverRecord.Stream, 0, fakeCut("devC", 0))
    // devA has the fake one to try first, then finds devB's C and uses it instead of making one.
    val app = App(devA, s)
    app.update(_.listed(Seq(fake)))
    assertEquals(Cutover.ensure(app), real)
    assert(app.state.refused.contains(CutRef.of(fake)))
    assertEquals(app.nextSeq, 0L)
    assert(app.state.required)
    assertEquals(app.state.record.map(_.entries.map(_.below)), Some(Vector(1L)))
  }

  test("ensureCutover makes no C while a listed one does not verify") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val fake = s.put(devB, CutoverRecord.Stream, 0, fakeCut(devB, 0))
    val app = App(devA, s)
    app.devs = Vector(DeviceRow(devA, false, false))
    val e = intercept[CutoverUnverified](Cutover.ensure(app))
    assertEquals(e.getMessage, "切り替えの記録を確認できません。ほかの端末で同期してから再試行してください")
    assert(app.state.refused.contains(CutRef.of(fake)))
    assertEquals(app.nextSeq, 0L)
    assert(!app.state.required)
  }

  test("ensureCutover after a lost state makes no C from a listing that hides it; forged ASH1 stays refused until C is back") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val maker = App(devB, s)
    maker.devs = Vector(DeviceRow(devB, false, false))
    val ref = Cutover.ensure(maker)
    // The server shows devA a listing without C (and without devB as a
    // device), with an ASH1 status forged with a leaked batch key.
    val shown = Server()
    shown.put(devA, "places", 0, s.blobs((devA, "places", 0L)))
    val forgedBlob = Crypto.sealAsh1(viewerBatchKey, Crypto.aad(devB, "status", 0), plain("forged"))
    shown.put(devB, "status", 0, forgedBlob)
    val forged = Intake.Item("forged", StreamKey(devB, "status"), 0, forgedBlob)
    val file = MemFile()
    file.content = Some("garbage".getBytes(UTF_8))
    val app = Reader(devA, shown, file)
    app.devs = Vector(DeviceRow(devA, false, false))
    assert(app.state.lost)
    val e = intercept[CutoverUnverified](Cutover.ensure(app))
    assert(e.getMessage.startsWith("切り替えの記録を確認できません"))
    assertEquals(app.nextSeq, 0L)
    assert(shown.rows.forall(_.stream != CutoverRecord.Stream))
    assert(app.state.lost && app.state.required && app.state.record.isEmpty)
    assert(Intake.run(app, Seq(forged), names).toMap.apply("forged").isInstanceOf[Intake.Refused])
    // Also with a C forged over the batch key alone in the listing.
    val fake = shown.put("devC", CutoverRecord.Stream, 0, fakeCut("devC", 0))
    intercept[CutoverUnverified](Cutover.ensure(app))
    assertEquals(app.nextSeq, 0L)
    assert(app.state.lost && app.state.refused.contains(CutRef.of(fake)))
    assert(Intake.run(app, Seq(forged), names).toMap.apply("forged").isInstanceOf[Intake.Refused])
    // The real C listed again: adopted, the lost state ends, and C judges the forgery.
    shown.put(ref.deviceId, CutoverRecord.Stream, ref.seq, s.blobs((ref.deviceId, CutoverRecord.Stream, ref.seq)))
    assertEquals(Cutover.ensure(app), ref)
    assert(!app.state.lost && app.state.record.isDefined)
    assertEquals(app.nextSeq, 0L)
    val out = Intake.run(app, Seq(forged), names).toMap.apply("forged")
    assert(out match { case Intake.Refused(e) => e.getMessage.contains("not in the cut-over record"); case _ => false }, out.toString)
  }

  test("ensureCutover with a known C makes no other when the listing hides it; the genuine first time still does") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val maker = App(devB, s)
    maker.devs = Vector(DeviceRow(devB, false, false))
    val ref = Cutover.ensure(maker)
    val realRow = s.rows.find(_.stream == CutoverRecord.Stream).get
    val hidden = Server()
    hidden.put(devA, "places", 0, s.blobs((devA, "places", 0L)))
    // C named by an authenticated status; C listed in an earlier page; more C listed than kept.
    val known: Seq[(String, BlobAuthState => BlobAuthState)] = Seq(
      "named" -> (_.named(ref)),
      "listed before" -> (_.listed(Seq(realRow))),
      "listed beyond the kept" -> (_.listed((0 to BlobAuthState.MaxFound).map(i =>
        BatchMeta(100L + i, s"junk$i", CutoverRecord.Stream, 0, 10, f"$i%064x")))))
    for (what, f) <- known do
      val app = App(devA, hidden)
      app.devs = Vector(DeviceRow(devA, false, false))
      app.update(f)
      assert(app.state.cutSeen, what)
      intercept[CutoverUnverified](Cutover.ensure(app))
      assertEquals(app.nextSeq, 0L, what)
      assert(hidden.rows.forall(_.stream != CutoverRecord.Stream), what)
      assert(app.state.record.isEmpty, what)
    // The genuine first time: nothing of C seen, not lost, not required.
    val first = App(devA, hidden)
    first.devs = Vector(DeviceRow(devA, false, false))
    assert(!first.state.cutSeen)
    assertEquals(Cutover.ensure(first).deviceId, devA)
    assert(first.state.required && !first.state.lost && first.state.record.isDefined)
  }

  test("learn: a C named by a status is fetched and verified; a tampered one is not taken") {
    val s = Server()
    s.put(devA, "places", 0, ash1(devA, "places", 0))
    val maker = App(devB, s)
    maker.devs = Vector(DeviceRow(devB, false, false))
    val ref = Cutover.ensure(maker)
    val app = App(devA, s)
    // The status names a C whose blob the server changed.
    val bad = ref.copy(sha256 = "f" * 64)
    app.update(_.named(bad))
    Cutover.learn(app)
    assertEquals(app.state.record, None)
    assert(app.state.required)
    // The real one, listed.
    app.update(_.listed(s.rows.toSeq))
    Cutover.learn(app)
    assertEquals(app.state.cut, Some(ref)) // the verified C replaces the bad name
    assertEquals(app.state.record.map(_.entries.size), Some(1))
    assertEquals(app.state.toFetch, Vector.empty)
  }

  // --- One sync run's order (Intake) --------------------------------------

  /** An app that also opens received blobs; `listedRows` from the server. */
  private final class Reader(id: String, srv: Server, f: MemFile = MemFile()) extends App(id, srv, f) with IntakeEnv:
    var listingFails = false
    def listedRows(k: StreamKey): Option[Seq[BatchMeta]] =
      Option.when(!listingFails)(server.rows.toSeq.filter(b => b.deviceId == k.deviceId && b.stream == k.stream))

  /** A status-like plaintext that names C (or not), and the reader of it. */
  private def statusText(cut: Option[CutRef]) = cut.fold("status")(r => "status " + ujson.write(r.toJson))
  private val names: (String, Array[Byte]) => Option[CutRef] = (_, p) =>
    val t = String(Crypto.gunzip(p), UTF_8)
    if t.startsWith("status ") then CutRef.fromJson(ujson.read(t.drop(7))) else None

  /** devB has made C and sends an ASHM status naming it; "ghost" (a device
    * this reader has never seen) has a forged ASH1 status.
    */
  private def cutOverWorld(): (Server, CutRef, Intake.Item[String], Intake.Item[String]) =
    val s = Server()
    s.put(devB, "places", 0, ash1(devB, "places", 0))
    val maker = App(devB, s)
    maker.devs = Vector(DeviceRow(devB, false, false))
    val ref = Cutover.ensure(maker)
    val status = ashm(devB, "status", 0, statusText(Some(ref)))
    s.put(devB, "status", 0, status)
    val forged = Crypto.sealAsh1(viewerBatchKey, Crypto.aad("ghost", "status", 0), plain("forged"))
    s.put("ghost", "status", 0, forged)
    (s, ref, Intake.Item("status", StreamKey(devB, "status"), 0, status), Intake.Item("forged", StreamKey("ghost", "status"), 0, forged))

  test("one run: C named by a status and a forged ASH1 of an unseen device, in either order: the forgery is refused") {
    for order <- Seq(true, false) do
      val (s, ref, status, forged) = cutOverWorld()
      val app = Reader(devA, s)
      val items = if order then Seq(forged, status) else Seq(status, forged)
      val out = Intake.run(app, items, names).toMap
      assert(out("status").isInstanceOf[Intake.Accepted], s"order $order: ${out("status")}")
      assert(out("forged").isInstanceOf[Intake.Refused], s"order $order: ${out("forged")}")
      assertEquals(app.state.cut, Some(ref))
      assert(app.state.required && app.state.record.isDefined)
      // On disk before the run ended.
      assert(BlobAuthStore(DurableMap(app.file)).state("reg").required)
  }

  test("one run: C only listed (no status yet) is learned before the forged ASH1 is judged") {
    val (s, _, _, forged) = cutOverWorld()
    val app = Reader(devA, s)
    app.update(_.listed(s.rows.toSeq))
    assert(!app.state.required)
    val out = Intake.run(app, Seq(forged), names).toMap
    assert(out("forged").isInstanceOf[Intake.Refused], out("forged").toString)
    assert(app.state.required)
  }

  test("one run: while C cannot be fetched or stored, ASH1 waits for the next run") {
    val (s, _, status, forged) = cutOverWorld()
    val app = Reader(devA, s)
    app.offline = true
    val out = Intake.run(app, Seq(forged, status), names).toMap
    assert(out("status").isInstanceOf[Intake.Accepted])
    assertEquals(out("forged"), Intake.Deferred)
    // Storing fails: ASH1 waits too, also for a state that only failed to write before.
    app.offline = false
    app.file.onReplace = _ => throw java.io.IOException("disk full")
    assertEquals(Intake.run(app, Seq(forged), names).toMap.apply("forged"), Intake.Deferred)
    app.file.onReplace = _ => ()
    assert(Intake.run(app, Seq(forged), names).toMap.apply("forged").isInstanceOf[Intake.Refused])
  }

  test("one run: more C candidates than the fetch limit: ASH1 waits until the real C is verified") {
    val (s, ref, _, forged) = cutOverWorld()
    val realRow = s.rows.find(r => r.stream == CutoverRecord.Stream).get
    assertEquals(CutRef.of(realRow), ref)
    // Four candidates that do not verify, listed before the real C.
    val junk = (1 to 4).map(i => s.put(s"junk$i", CutoverRecord.Stream, 0, fakeCut(s"junk$i", 0)))
    val app = Reader(devA, s)
    app.update(_.listed(junk :+ realRow))
    assertEquals(app.state.toFetch.size, 5)
    // Run 1: the four are tried and refused, the real C is left by the limit: the forgery waits.
    assertEquals(Intake.run(app, Seq(forged), names).toMap.apply("forged"), Intake.Deferred)
    assertEquals(app.state.refused.size, 4)
    assert(!app.state.required)
    assertEquals(app.state.toFetch, Vector(ref))
    // Run 2: the real C verifies; the forgery is judged against it.
    val out = Intake.run(app, Seq(forged), names).toMap.apply("forged")
    assert(out.isInstanceOf[Intake.Refused], out.toString)
    assertEquals(app.state.cut, Some(ref))
    assert(app.state.required && app.state.record.isDefined)
  }

  test("one run: more C listed than kept: ASH1 waits until a C is verified, also once the kept ones are refused") {
    val (s, ref, _, forged) = cutOverWorld()
    val realRow = s.rows.find(r => r.stream == CutoverRecord.Stream).get
    val junk = (0 to BlobAuthState.MaxFound).map(i => s.put(s"junk$i", CutoverRecord.Stream, 0, fakeCut(s"junk$i", 0)))
    val app = Reader(devA, s)
    app.update(_.listed(junk :+ realRow))
    assert(app.state.overflow)
    assertEquals(app.state.toFetch.size, BlobAuthState.MaxFound)
    assert(!app.state.toFetch.contains(ref))
    assert(BlobAuthStore(DurableMap(app.file)).state("reg").overflow) // on disk
    // Every kept candidate is tried and refused; the real one was not kept: still waiting.
    for _ <- 1 to 3 do assertEquals(Intake.run(app, Seq(forged), names).toMap.apply("forged"), Intake.Deferred)
    assertEquals(app.state.toFetch, Vector.empty)
    assert(!app.state.required && app.state.overflow)
    // A later listing (a rescan) brings the rest; the real C verifies and ends the overflow.
    app.update(_.listed(s.rows.toSeq))
    val out = Intake.run(app, Seq(forged), names).toMap.apply("forged")
    assert(out.isInstanceOf[Intake.Refused], out.toString)
    assertEquals(app.state.cut, Some(ref))
    assert(!app.state.overflow && app.state.required)
  }

  test("an ASH1 accepted before C was known is not grandfathered once it is") {
    val (s, _, status, forged) = cutOverWorld()
    val app = Reader(devA, s)
    // Run 1: nothing of the cut-over reached this device; the forgery passes, as provisional.
    val first = Intake.run(app, Seq(forged), names).toMap.apply("forged")
    assert(first match { case Intake.Accepted(_, BlobFormat.Ash1, true) => true; case _ => false }, first.toString)
    // Run 2: the status naming C arrives; the same blob is now refused.
    val out = Intake.run(app, Seq(status, forged), names).toMap
    assert(out("forged").isInstanceOf[Intake.Refused], out("forged").toString)
    // A genuine ASH1 below the cut-over is still accepted (digest of the listing), not provisional.
    val old = Intake.Item("old", StreamKey(devB, "places"), 0, s.blobs((devB, "places", 0L)))
    val again = Intake.run(app, Seq(old), names).toMap.apply("old")
    assert(again match { case Intake.Accepted(_, BlobFormat.Ash1, false) => true; case _ => false }, again.toString)
    // Without the listing, it waits.
    app.listingFails = true
    assertEquals(Intake.run(app, Seq(old), names).toMap.apply("old"), Intake.Deferred)
  }

  // --- Status naming C ----------------------------------------------------

  test("a status names C; an older reader ignores it and a malformed one is dropped") {
    val ref = CutRef(devA, 3, "d" * 64)
    val st = DeviceStatus(1L, 50, false, true, "eco", 0L, None, "1.1", 11, "Pixel", Some(ref))
    assertEquals(DeviceStatus.fromJson(st.toJson).authCut, Some(ref))
    assertEquals(DeviceStatus.fromJson(st.copy(authCut = None).toJson).authCut, None)
    assert(!st.copy(authCut = None).toJson.obj.contains("authcut"))
    val bad = st.toJson
    bad("authcut") = ujson.Obj("deviceId" -> "a/b", "seq" -> 1, "sha256" -> "d" * 64)
    assertEquals(DeviceStatus.fromJson(bad).authCut, None)
  }
