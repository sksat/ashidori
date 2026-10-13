package ashidori.core

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

    /** A fresh process over this storage. */
    def boot(): PairingStore =
      val s = DurableMap(secrets)
      PairingStore(DurableMap(config), new SecretStore:
        def get(n: String) = s.getString(n).map(java.util.Base64.getDecoder.decode)
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
