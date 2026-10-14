package ashidori.core

import java.nio.charset.StandardCharsets.UTF_8
import ashidori.core.sync.*

class PairingStoreSuite extends munit.FunSuite:
  /** The device's storage: survives a "restart" (new instances over the same files). */
  final class Disk:
    val config = MemFile()
    val secrets = MemFile()
    /** Writes left before the "process dies" (every further write throws). */
    var writesLeft = Int.MaxValue
    for f <- Seq(config, secrets) do
      f.onReplace = _ =>
        if writesLeft <= 0 then throw java.io.IOException("crashed")
        writesLeft -= 1

    /** Runs before each read of a secret (e.g. a concurrent write). */
    var onSecretGet: String => Unit = _ => ()

    /** A fresh process over this storage. */
    def boot(): PairingStore =
      val s = DurableMap(secrets)
      PairingStore(DurableMap(config), new SecretStore:
        def get(n: String) =
          onSecretGet(n)
          s.getString(n).map(java.util.Base64.getDecoder.decode)
        def put(n: String, v: Array[Byte]) = s.commit(n -> java.util.Base64.getEncoder.encodeToString(v))
        def remove(n: String) = s.commit(n -> null))

  private val keyA = Array.fill[Byte](32)(1)

  private def paired(d: Disk): PairingStore =
    val p = d.boot()
    p.savePairing("https://a.example/", "devA", "tokA", newAccount = false)
    assert(p.confirmKey(keyA, p.registration))
    assert(p.ready)
    p

  /** Unpairs with the process dying after `n` writes; true when it finished. */
  private def forgetCrashing(d: Disk, p: PairingStore, n: Int): Boolean =
    d.writesLeft = n
    var cleared = false
    val done =
      try
        p.forget(() => (), f => f(), () => cleared = true)
        true
      catch case _: java.io.IOException => false
    d.writesLeft = Int.MaxValue
    done

  test("an unpairing cut short at any point never lets a later registration use the old key (review 3)") {
    // forget writes: the mark, the registration, the token, the key, the mark again.
    for n <- 0 to 5 do
      val d = Disk()
      val p = paired(d)
      val finished = forgetCrashing(d, p, n)
      assertEquals(finished, n == 5, s"crash after $n writes")
      val after = d.boot()
      if n == 0 then
        // Died before the mark: nothing changed, still paired with its own key.
        assert(after.ready)
      else
        assert(!after.paired, s"crash after $n writes")
        assert(!after.ready)
      if n > 0 && n < 5 then
        // A new registration is refused until the unpairing is finished.
        intercept[IllegalStateException](after.savePairing("https://b.example", "devB", "tokB", newAccount = false))
        assert(after.resume(() => (), f => f(), () => ()))
        assert(!after.unpairing)
      else assert(!after.resume(() => (), f => f(), () => ()))
      if n > 0 then
        after.savePairing("https://b.example", "devB", "tokB", newAccount = false)
        assert(after.paired)
        assertEquals(after.masterKey.map(_.toSeq), None, s"crash after $n writes")
        assert(!after.ready)
        val keyB = Array.fill[Byte](32)(2)
        assert(after.confirmKey(keyB, after.registration))
        assertEquals(d.boot().masterKey.map(_.toSeq), Some(keyB.toSeq))
  }

  test("a key left from another registration is not used, and does not block setting the new one") {
    val d = Disk()
    val p = paired(d)
    // As an older version could leave it: the registration gone, the key not.
    DurableMap(d.config).clear()
    val q = d.boot()
    q.savePairing("https://a.example", "devA2", "tok2", newAccount = false)
    assert(q.paired && !q.ready)
    assert(q.confirmKey(Array.fill[Byte](32)(3), q.registration))
    assertEquals(q.masterKey.map(_.head), Some(3.toByte))
  }

  test("a key stored without its registration by an earlier version is bound to the current one at start") {
    val d = Disk()
    val p = d.boot()
    p.savePairing("https://a.example", "devA", "tokA", newAccount = false)
    DurableMap(d.secrets).commit("master" -> java.util.Base64.getEncoder.encodeToString(keyA))
    val q = d.boot()
    assert(!q.ready)
    assert(q.adoptLegacyKey())
    assertEquals(d.boot().masterKey.map(_.toSeq), Some(keyA.toSeq))
    assert(!d.boot().adoptLegacyKey())
  }

  test("a key is set once per registration") {
    val d = Disk()
    val p = paired(d)
    assert(!p.confirmKey(Array.fill[Byte](32)(9), p.registration))
    assert(!p.confirmKey(keyA, "other"))
    assertEquals(p.masterKey.map(_.toSeq), Some(keyA.toSeq))
  }

  // formal/tla/RegistrationClient_runread.cfg (RunRegMatches). A sync run
  // read the device id, token and key (the key checked against the
  // registration as it was then), then the registration again; an
  // unpairing marks itself outside the sync lock, so a mark in between gave
  // the run the key and device id of A with the registration "" under which
  // it keeps its progress.
  /** On the next read of the key, an unpairing marks itself (forget's first
    * step, as from another thread) and stops there.
    */
  private def markDuringKeyRead(d: Disk, p: PairingStore): Unit =
    d.onSecretGet = n =>
      if n == PairingStore.Master then
        d.onSecretGet = _ => ()
        try p.forget(() => throw IllegalStateException("stop"), _ => (), () => ())
        catch case _: IllegalStateException => ()

  test("a sync session reads the registration once: an unpairing mark meanwhile does not split it (runread)") {
    // The trace with the old reading: the key (checked against A), then the registration again.
    val d = Disk()
    val p = paired(d)
    val regA = p.registration
    markDuringKeyRead(d, p)
    assertEquals(p.masterKey.map(_.toSeq), Some(keyA.toSeq))
    assertEquals(p.registration, "")
    // The same interleaving with one read.
    val d2 = Disk()
    val q = paired(d2)
    markDuringKeyRead(d2, q)
    val s = q.session.getOrElse(fail("no session"))
    assertEquals(s.reg, regA)
    assertEquals(s.reg, PairingStore.registrationOf(s.url, s.deviceId))
    assertEquals((s.url, s.deviceId, s.token, s.key.toSeq), ("https://a.example", "devA", "tokA", keyA.toSeq))
    // The mark is set now: no further session.
    assert(q.unpairing)
    assertEquals(q.session, None)
  }

  /** The config file's last unmark writes fail and cannot be read back:
    * `fails` of them, the new records reaching the disk (`reached`) or not.
    */
  private def failUnmark(d: Disk, fails: Int, reached: Boolean): Unit =
    var left = fails
    var marked = false
    d.config.onReplace = b =>
      val hasMark = String(b, UTF_8).contains(PairingStore.Unpairing)
      if hasMark then marked = true
      else if marked && left > 0 then
        left -= 1
        if reached then d.config.content = Some(b)
        d.config.unreadable = true
        throw java.io.IOException("rename failed")

  private def noop: () => Unit = () => ()
  private def direct: (() => Unit) => Unit = f => f()

  test("an unmark that fails and cannot be read back is written again until it is known (unknownmark)") {
    for reached <- Seq(false, true) do
      val d = Disk()
      val p = paired(d)
      failUnmark(d, fails = 1, reached)
      p.forget(noop, direct, noop)
      assert(!p.unpairing, s"reached=$reached")
      d.config.unreadable = false
      assert(!d.boot().unpairing, s"reached=$reached")
  }

  // formal/tla/RegistrationClient_unknownmark.cfg (TokenRecoverable), both
  // traces. The unmark write fails and cannot be read back. Before the fix
  // the mark then read as absent while the disk might still hold it:
  // (1) a new registration began; the next start found the mark, finished
  //     the unpairing again and removed the new pending request, while the
  //     server had created its device;
  // (2) a later successful write of the config (savePairing) wrote the old
  //     mark back from the snapshot: the new registration read as unpairing
  //     at once.
  // Now an unknown mark reads as set: no registration begins until the
  // unpairing is finished (resume, or a later forget), and nothing writes
  // the old mark back.
  test("a mark that cannot be read back counts as set until the unpairing is finished (unknownmark)") {
    for reached <- Seq(false, true); restart <- Seq(false, true) do
      val what = s"reached=$reached restart=$restart"
      val d = Disk()
      val p = paired(d)
      failUnmark(d, fails = 3, reached)
      intercept[java.io.IOException](p.forget(noop, direct, noop))
      assert(p.unpairing, what)
      assert(!p.paired, what)
      // Trace 1: no new registration begins (the app's storage is busy while unpairing).
      intercept[IllegalStateException](p.savePairing("https://b.example", "devB", "tokB", newAccount = false))
      val stored = java.util.concurrent.atomic.AtomicReference[Option[PendingRegistration]](None)
      @volatile var cur = p
      // As the app's Registration storage.
      val machine = RegistrationMachine(
        new RegistrationStorage:
          def load() = stored.get
          def store(r: Option[PendingRegistration]) = stored.set(r)
          def busy = cur.paired || cur.unpairing
          def savePairing(r: PendingRegistration, dev: String) = cur.savePairing(r.url, dev, r.token, r.kind == "account"),
        _ => "devB", () => "tokB", _ => (),
        java.util.concurrent.Executors.newSingleThreadExecutor(), java.util.concurrent.Executors.newCachedThreadPool())
      intercept[IllegalStateException](machine.registerBlocking("https://b.example", "account", "", "phone"))
      assertEquals(stored.get, None, what)
      // The file reads again. The unpairing is finished, by the next start or
      // in this process; then a new registration is saved and stays.
      d.config.unreadable = false
      if restart then cur = d.boot()
      if cur.unpairing then assert(cur.resume(noop, direct, noop), what)
      assert(!cur.unpairing, what)
      // Trace 2: the new registration does not come back as unpairing.
      assertEquals(machine.registerBlocking("https://b.example", "account", "", "phone"), "devB")
      assert(cur.paired, what)
      assert(!cur.unpairing, what)
      val after = d.boot()
      assert(!after.unpairing, what)
      assert(after.paired, what)
      assertEquals(after.deviceId, "devB", what)
      assertEquals(after.masterKey, None, what)
  }
