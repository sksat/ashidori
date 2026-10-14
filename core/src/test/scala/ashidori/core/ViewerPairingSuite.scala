package ashidori.core

import ashidori.core.ViewerPairingFixtures.*
import ashidori.core.sync.{Bip39Japanese, ViewerApproval, ViewerFailure, ViewerPairingApi, ViewerPairing as VP}
import ashidori.core.sync.ViewerApproval.Phase
import ashidori.core.sync.ViewerPairingApi.*
import com.google.crypto.tink.subtle.X25519

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import scala.collection.mutable

class ViewerPairingSuite extends munit.FunSuite:

  test("the BIP-39 Japanese word list is the pinned file, read as NFC") {
    val raw = Bip39Japanese.raw()
    assertEquals(hex(VP.sha256(raw)), Bip39Japanese.Sha256)
    assertEquals(Bip39Japanese.words.size, 2048)
    assert(Bip39Japanese.words.forall(w => Normalizer.isNormalized(w, Normalizer.Form.NFC)))
    assertEquals(Bip39Japanese.words.distinct.size, 2048)
  }

  test("the design's test vector: c, nA, th, d, indices and words") {
    val t = transcript(VecId, VecCode, VecPkB, VecNB, VecSA)
    assertEquals(hex(t.c), "bd511eec808fd863c98d184c6c3365b4795dd5faa744c4eb4739745b643c90a6")
    assertEquals(hex(t.nA), "12f1f75587df148a55d473a566e6af39dfb694346eb15f318296e00ec72c0428")
    val th = t.hash
    assertEquals(hex(th), "7d6395cd068e7461034748a495ad8260d04489d466cb7a0b33f14b157cd9d3a8")
    assertEquals(hex(VP.sha256("ashidori pair sas v1".getBytes(UTF_8), th).take(6)), "44999b593025")
    assertEquals(VP.sasIndices(th), Vector(548, 1638, 1714, 770))
    assertEquals(VP.sasWords(th), Vector("けっせき", "ぴっちり", "ふみん", "ざんしょ"))
  }

  test("changing any transcript field changes th and the words") {
    val base = transcript(VecId, VecCode, VecPkB, VecNB, VecSA)
    val all = tampered
    assertEquals(all.map(_._1).toSet, Set("id", "code", "c", "nA", "scope", "ttl", "pkB", "nB"))
    for (f, _, t) <- all do
      assertNotEquals(hex(t.hash), hex(base.hash), f)
      assertNotEquals(VP.sasWords(t.hash), VP.sasWords(base.hash), f)
  }

  test("the scope's length prefix keeps th unambiguous") {
    val a = VP.Transcript(VecId, VecCode, fill(1), fill(2), "batch-v1", 3600, fill(3), fill(4))
    val b = a.copy(scope = "batch-v")
    assertNotEquals(hex(a.hash), hex(b.hash))
  }

  test("codes are normalized as Crockford base32") {
    assertEquals(VP.normalizeCode("abcd-2345"), Some("ABCD2345"))
    assertEquals(VP.normalizeCode(" o1il 2345 "), Some("0111" + "2345"))
    assertEquals(VP.normalizeCode("ABCD234"), None)
    assertEquals(VP.normalizeCode("ABCD2345X"), None)
    assertEquals(VP.normalizeCode("ABCU2345"), None) // U is not Crockford
    assertEquals(VP.displayCode("ABCD2345"), "ABCD-2345")
  }

  test("base64url is strict about alphabet, padding and length") {
    val b = fill(7)
    assertEquals(VP.unb64(VP.b64(b), 32).map(hex), Some(hex(b)))
    assertEquals(VP.unb64(VP.b64(b) + "=", 32), None)
    assertEquals(VP.unb64(VP.b64(b), 31), None)
    assertEquals(VP.unb64(VP.b64(b).replace('_', '/'), 32).isDefined, !VP.b64(b).contains('_'))
  }

  test("low-order public keys are refused before sealing; they give an all-zero secret") {
    for h <- LowOrder do
      val pk = unhex(h)
      assert(VP.isLowOrder(pk), h)
      val shared = scala.util.Try(X25519.computeSharedSecret(X25519.generatePrivateKey(), pk))
      assert(shared.isFailure || shared.get.forall(_ == 0), s"$h is not of low order")
      intercept[IllegalArgumentException](VP.sealGrant(pk, fill(9), fill(1), fill(2)))
    for _ <- 0 until 20 do assert(!VP.isLowOrder(X25519.publicFromPrivate(X25519.generatePrivateKey())))
  }

  test("a grant is 113 bytes that Tink opens only with the same info") {
    val sk = X25519.generatePrivateKey()
    val pk = X25519.publicFromPrivate(sk)
    val sA = VP.random()
    val key = VP.random()
    val th = transcript(VP.random(16), "ABCD2345", pk, VP.random(), sA).hash
    val g = VP.sealGrant(pk, th, sA, key)
    assertEquals(g.length, VP.GrantLen)
    val plain = decrypter(sk).decrypt(g, VP.info(th))
    assertEquals(hex(plain), "01" + hex(sA) + hex(key))
    intercept[java.security.GeneralSecurityException](decrypter(sk).decrypt(g, VP.info(fill(5))))
    // Each grant has its own ephemeral key.
    assertNotEquals(hex(VP.sealGrant(pk, th, sA, key)), hex(g))
  }

  test("the web viewer's fixture is current and opens with Tink") {
    val f = File("../server/web/test/fixtures/viewer-pairing.json")
    assume(f.exists, s"$f not written yet (./gradlew :core:webFixtures)")
    val js = ujson.read(java.nio.file.Files.readString(f.toPath))
    val fresh = build()
    // Deterministic parts equal what this code computes now.
    assertEquals(js("sas"), fresh("sas"))
    assertEquals(js("tamper"), fresh("tamper"))
    assertEquals(js("lowOrder"), fresh("lowOrder"))
    assertEquals(js("wordlistSha256").str, Bip39Japanese.Sha256)
    val g = js("grant")
    val sk = unhex(g("skB").str)
    val t = VP.Transcript(unhex(g("id").str), g("code").str, unhex(g("c").str), unhex(g("nA").str), g("scope").str,
      g("ttl").num.toInt, unhex(g("pkB").str), unhex(g("nB").str))
    assertEquals(hex(t.hash), g("th").str)
    assertEquals(hex(VP.info(t.hash)), g("info").str)
    assertEquals(hex(VP.commit(t.pkB, t.nB)), g("c").str)
    assertEquals(hex(X25519.publicFromPrivate(sk)), g("pkB").str)
    val plain = decrypter(sk).decrypt(unhex(g("grant").str), VP.info(t.hash))
    assertEquals(hex(plain), "01" + g("sA").str + g("batchKey").str)
    assertEquals(unhex(js("longPlain")("grant").str).length, VP.GrantLen + 1)
    assertEquals(unhex(js("shortPlain")("grant").str).length, VP.GrantLen - 1)
    assertNotEquals(hex(VP.appNonce(unhex(js("wrongSa")("sA").str))), g("nA").str)
  }

  // ---- The app's attempt, against a fake server ----

  /** An honest server and browser, scripted per test. */
  final class FakeServer(val sk: Array[Byte] = X25519.generatePrivateKey()) extends ViewerPairingApi:
    val pk: Array[Byte] = X25519.publicFromPrivate(sk)
    val nB: Array[Byte] = VP.random()
    val id: String = VP.b64(VP.random(16))
    var c: Array[Byte] = VP.commit(pk, nB)
    var state = "open"
    var nA: Option[Array[Byte]] = None
    var ttl = 0
    var grant: Option[Array[Byte]] = None
    var confirmed = false
    var revealPk: Array[Byte] = pk
    var revealNb: Array[Byte] = nB
    var viewers = 0
    val calls: mutable.Buffer[String] = mutable.Buffer()
    val grants: mutable.Buffer[String] = mutable.Buffer()

    def claim(code: String): Claim =
      calls += s"claim $code"
      if code != "ABCD2345" then throw Refused(404, "invalid or expired code", None)
      state = "claimed"
      Claim(id, c, 0L)
    def nonce(i: String, n: Array[Byte], scope: String, t: Int): Unit =
      calls += s"nonce $scope $t"
      nA = Some(n); ttl = t; state = "nonce"
    /** The browser reveals. */
    def reveal(): Unit = state = "revealed"
    def confirm(): Unit = { confirmed = true; activate() }
    private def activate(): Unit =
      if state == "revealed" && grant.isDefined && confirmed then
        if viewers >= 5 then throw Refused(409, "too many viewers", None)
        viewers += 1
        state = "active"
    def app(i: String): AppView =
      calls += "app"
      val shown = state == "revealed" || state == "active"
      AppView(state, 0L, confirmed, Option.when(shown)(revealPk), Option.when(shown)(revealNb), Option.when(state == "active")("viewer-1"))
    def approve(i: String, g: Array[Byte]): ApproveView =
      calls += "approve"
      grants += hex(g)
      if state != "revealed" then throw Refused(409, "invalid state", Some(state))
      grant match
        case Some(old) if hex(old) != hex(g) => throw Refused(409, "conflict", None)
        case _ => grant = Some(g)
      activate()
      ApproveView(state, Option.when(state == "active")("viewer-1"))
    def abort(i: String): String =
      calls += "abort"
      if state != "active" then state = "aborted"
      state
    /** What the browser computes and opens. */
    def browserTh: Array[Byte] =
      VP.Transcript(VP.unb64(id, 16).get, "ABCD2345", c, nA.get, VP.Scope, ttl, pk, nB).hash

  final class Clock:
    var t = 1_000L
    def apply(): Long = t

  private val batchKey = fill(0x5a)

  test("the app's attempt: claim, nonce, reveal, the same words as the browser, approve, done") {
    val s = FakeServer()
    val clock = Clock()
    val a = ViewerApproval(s, () => batchKey.clone(), () => clock())
    assertEquals(a.start("abcd-2345", 86400), Phase.WaitingReveal)
    assertEquals(s.calls.toList, List("claim ABCD2345", "nonce batch-v1 86400"))
    assertEquals(a.poll(), Phase.WaitingReveal)
    s.reveal()
    val shown = a.poll()
    assertEquals(shown, Phase.Showing(VP.sasWords(s.browserTh), "batch-v1", 86400, false))
    // Approve first, then the browser confirms.
    assertEquals(a.approve(), Phase.Approved(VP.sasWords(s.browserTh), "batch-v1", 86400))
    val plain = decrypter(s.sk).decrypt(s.grant.get, VP.info(s.browserTh))
    assertEquals(plain.length, VP.PlainLen)
    assertEquals(plain(0), 1.toByte)
    assertEquals(hex(VP.appNonce(plain.slice(1, 33))), hex(s.nA.get))
    assertEquals(hex(plain.drop(33)), hex(batchKey))
    s.confirm()
    assertEquals(a.poll(), Phase.Done("viewer-1", 86400))
  }

  test("confirm first, then approve activates at once") {
    val s = FakeServer()
    val a = ViewerApproval(s, () => batchKey.clone(), () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    a.poll()
    s.confirmed = true
    assertEquals(a.approve(), Phase.Done("viewer-1", 3600))
  }

  test("an invalid code sends nothing; an unknown code ends the attempt") {
    val s = FakeServer()
    assertEquals(ViewerApproval(s, () => batchKey, () => 0L).start("ABC", 3600), Phase.Failed(ViewerFailure.InvalidCode))
    assert(s.calls.isEmpty)
    assertEquals(ViewerApproval(s, () => batchKey, () => 0L).start("ZZZZ-2345", 3600), Phase.Failed(ViewerFailure.UnknownCode))
  }

  test("a reveal that does not match the commit aborts") {
    val s = FakeServer()
    val a = ViewerApproval(s, () => batchKey, () => 0L)
    a.start("ABCD2345", 3600)
    s.revealNb = VP.random()
    s.reveal()
    assertEquals(a.poll(), Phase.Failed(ViewerFailure.BadReveal))
    assertEquals(s.state, "aborted")
  }

  test("a low-order key is refused even with a matching commit") {
    val s = FakeServer()
    val low = unhex(LowOrder.head)
    s.revealPk = low
    s.c = VP.commit(low, s.nB)
    val a = ViewerApproval(s, () => batchKey, () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    assertEquals(a.poll(), Phase.Failed(ViewerFailure.BadReveal))
  }

  test("a pinned reveal that changes later aborts") {
    val s = FakeServer()
    val a = ViewerApproval(s, () => batchKey, () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    assert(a.poll().isInstanceOf[Phase.Showing])
    s.revealNb = VP.random()
    assertEquals(a.poll(), Phase.Failed(ViewerFailure.Changed))
    assert(s.calls.contains("abort"))
  }

  test("a re-sent approval carries the same bytes; the cap keeps the attempt open") {
    val s = FakeServer()
    s.viewers = 5
    s.confirmed = true
    val a = ViewerApproval(s, () => batchKey.clone(), () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    a.poll()
    val words = VP.sasWords(s.browserTh)
    assertEquals(a.approve(), Phase.Showing(words, "batch-v1", 3600, true))
    s.viewers = 4 // the user revoked an old viewer
    assertEquals(a.approve(), Phase.Done("viewer-1", 3600))
    assertEquals(s.grants.size, 2)
    assertEquals(s.grants.distinct.size, 1)
  }

  test("the cap with approve → confirm: the waiting screen offers the approval again, and a free slot activates") {
    for retryBy <- Seq("app", "browser") do
      val s = FakeServer()
      s.viewers = 5
      val a = ViewerApproval(s, () => batchKey.clone(), () => 0L)
      a.start("ABCD2345", 3600)
      s.reveal()
      a.poll()
      val words = VP.sasWords(s.browserTh)
      assertEquals(a.approve(), Phase.Approved(words, "batch-v1", 3600))
      // The browser confirms at the cap: refused, the attempt stays revealed.
      intercept[ViewerPairingApi.Refused](s.confirm())
      assertEquals(s.state, "revealed")
      assertEquals(a.poll(), Phase.Showing(words, "batch-v1", 3600, true))
      assertEquals(a.poll(), Phase.Showing(words, "batch-v1", 3600, true))
      assertEquals(a.approve(), Phase.Showing(words, "batch-v1", 3600, true))
      s.viewers = 4 // the user ended another browser
      if retryBy == "app" then assertEquals(a.approve(), Phase.Done("viewer-1", 3600))
      else
        s.confirm()
        assertEquals(a.poll(), Phase.Done("viewer-1", 3600))
      assertEquals(s.grants.distinct.size, 1)
  }

  test("「一致しない」 aborts the attempt") {
    val s = FakeServer()
    val a = ViewerApproval(s, () => batchKey, () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    a.poll()
    assertEquals(a.mismatch(), Phase.Failed(ViewerFailure.Mismatch))
    assertEquals(s.state, "aborted")
    assertEquals(a.approve(), Phase.Failed(ViewerFailure.Mismatch))
  }

  test("past the local deadline: nothing is sent, one look whether it became active") {
    val s = FakeServer()
    val clock = Clock()
    val a = ViewerApproval(s, () => batchKey.clone(), () => clock())
    a.start("ABCD2345", 3600)
    s.reveal()
    a.poll()
    clock.t += VP.AttemptMs
    s.calls.clear()
    assertEquals(a.approve(), Phase.Failed(ViewerFailure.Expired))
    assertEquals(s.calls.toList, List("app"))
  }

  test("a lost approval answer is recovered by the poll") {
    val s = FakeServer()
    s.confirmed = true
    val lossy = new ViewerPairingApi:
      export s.{claim, nonce, app, abort}
      def approve(i: String, g: Array[Byte]): ApproveView =
        s.approve(i, g)
        throw java.io.IOException("connection reset")
    val a = ViewerApproval(lossy, () => batchKey.clone(), () => 0L)
    a.start("ABCD2345", 3600)
    s.reveal()
    a.poll()
    intercept[java.io.IOException](a.approve())
    assertEquals(a.poll(), Phase.Done("viewer-1", 3600))
  }

  test("aborted by the browser or by the approver's revocation") {
    val s = FakeServer()
    val a = ViewerApproval(s, () => batchKey, () => 0L)
    a.start("ABCD2345", 3600)
    s.state = "aborted"
    assertEquals(a.poll(), Phase.Failed(ViewerFailure.Aborted))
  }
