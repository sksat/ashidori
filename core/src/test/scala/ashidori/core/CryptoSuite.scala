package ashidori.core

import java.nio.charset.StandardCharsets.UTF_8
import ashidori.core.sync.*

class CryptoSuite extends munit.FunSuite:
  private val master = Crypto.newMasterKey()
  private val key = Crypto.batchKey(master)

  test("HKDF matches RFC 5869 test case 3 (empty salt, empty info)") {
    val ikm = Array.fill[Byte](22)(0x0b)
    val okm = Crypto.hkdf(ikm, "", 42)
    assertEquals(
      okm.map(b => f"${b & 0xff}%02x").mkString,
      "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
    )
  }

  private val keys = BlobKeys.of(master)

  test("seal/open roundtrip with gzip (ASHM)") {
    val text = """{"t":1,"lat":35.0,"lon":139.0}""" * 100
    val aad = Crypto.aad("dev1", "fixes", 7)
    val blob = Crypto.seal(keys, aad, Crypto.gzip(text.getBytes(UTF_8)))
    assert(blob.take(4).sameElements(Crypto.MagicAuth))
    assertEquals(Crypto.format(blob), Some(BlobFormat.Ashm))
    assertEquals(String(Crypto.gunzip(Crypto.open(keys, aad, blob)), UTF_8), text)
  }

  test("ASH1 (legacy) roundtrip; each opener takes only its own format") {
    val aad = Crypto.aad("dev1", "fixes", 7)
    val old = Crypto.sealAsh1(key, aad, "legacy".getBytes(UTF_8))
    assertEquals(Crypto.format(old), Some(BlobFormat.Ash1))
    assertEquals(String(Crypto.openAsh1(key, aad, old), UTF_8), "legacy")
    intercept[IllegalArgumentException](Crypto.open(keys, aad, old))
    intercept[IllegalArgumentException](Crypto.openAsh1(key, aad, Crypto.seal(keys, aad, "new".getBytes(UTF_8))))
    assertEquals(Crypto.format(Crypto.Magic ++ new Array[Byte](27)), None)
    assertEquals(Crypto.format(Crypto.MagicAuth ++ new Array[Byte](59)), None)
    assertEquals(Crypto.format("ASH2".getBytes(UTF_8) ++ new Array[Byte](60)), None)
  }

  test("Ka is HKDF(K, \"ashidori batch auth v1\"), not the batch key") {
    assertEquals(Crypto.authKey(master).toSeq, Crypto.hkdf(master, "ashidori batch auth v1").toSeq)
    assert(!Crypto.authKey(master).sameElements(key))
    assertEquals(keys.batch.toSeq, key.toSeq)
  }

  test("the MAC is HMAC-SHA256(Ka, label ‖ len(AAD) (2, big-endian) ‖ AAD ‖ every byte before it)") {
    val aad = Crypto.aad("0f5c2a8e-7d1b-4c3e-9a61-2b7d5e8f4a10", "status", 12)
    val blob = Crypto.seal(keys, aad, "x".getBytes(UTF_8))
    val body = blob.dropRight(32)
    val m = javax.crypto.Mac.getInstance("HmacSHA256")
    m.init(javax.crypto.spec.SecretKeySpec(Crypto.authKey(master), "HmacSHA256"))
    val input = "ashidori blob auth v1".getBytes(UTF_8) ++ Array((aad.length >> 8).toByte, aad.length.toByte) ++ aad ++ body
    assertEquals(blob.takeRight(32).toSeq, m.doFinal(input).toSeq)
    // The GCM part is that of ASH1: a viewer strips the MAC and opens it.
    assertEquals(String(Crypto.openAsh1(key, aad, Crypto.Magic ++ body.drop(4)), UTF_8), "x")
  }

  test("moving a blob to another slot or tampering fails") {
    val blob = Crypto.seal(keys, Crypto.aad("dev1", "fixes", 7), "secret".getBytes(UTF_8))
    intercept[Exception](Crypto.open(keys, Crypto.aad("dev1", "fixes", 8), blob))
    intercept[Exception](Crypto.open(keys, Crypto.aad("dev2", "fixes", 7), blob))
    intercept[Exception](Crypto.open(keys, Crypto.aad("dev1", "fixe", 7), blob))
    intercept[Exception](Crypto.open(BlobKeys.of(Crypto.newMasterKey()), Crypto.aad("dev1", "fixes", 7), blob))
    val old = Crypto.sealAsh1(key, Crypto.aad("dev1", "fixes", 7), "secret".getBytes(UTF_8))
    intercept[Exception](Crypto.openAsh1(key, Crypto.aad("dev1", "fixes", 8), old))
  }

  test("a wrong Ka fails even with the right batch key (the MAC is checked before GCM)") {
    val aad = Crypto.aad("dev1", "status", 3)
    val blob = Crypto.seal(keys, aad, "secret".getBytes(UTF_8))
    val viewer = BlobKeys(key, Crypto.authKey(Crypto.newMasterKey()))
    val e = intercept[java.security.GeneralSecurityException](Crypto.open(viewer, aad, blob))
    assert(e.getMessage.contains("MAC"))
    // A holder of the batch key alone (a viewer) cannot make a blob an app accepts.
    val forged = Crypto.seal(viewer, aad, "forged".getBytes(UTF_8))
    intercept[java.security.GeneralSecurityException](Crypto.open(keys, aad, forged))
  }

  test("flipping any bit of an ASHM blob, or any byte of the AAD, fails") {
    val aad = Crypto.aad("dev1", "places", 42)
    val blob = Crypto.seal(keys, aad, "tamper me".getBytes(UTF_8))
    for i <- blob.indices; bit <- Seq(0, 7) do
      val bad = blob.clone()
      bad(i) = (bad(i) ^ (1 << bit)).toByte
      intercept[Exception](Crypto.open(keys, aad, bad))
    for i <- aad.indices do
      val bad = aad.clone()
      bad(i) = (bad(i) ^ 1).toByte
      intercept[Exception](Crypto.open(keys, bad, blob))
    // Truncated or extended.
    intercept[Exception](Crypto.open(keys, aad, blob.dropRight(1)))
    intercept[Exception](Crypto.open(keys, aad, blob :+ 0.toByte))
    intercept[Exception](Crypto.open(keys, aad ++ "0".getBytes(UTF_8), blob))
  }

  test("recovery code roundtrip, tolerant of case and confusable letters, detects typos") {
    val code = RecoveryCode.encode(master)
    assertEquals(RecoveryCode.decode(code.toLowerCase).map(_.toSeq), Right(master.toSeq))
    val confusable = code.replace('0', 'O').replace('1', 'I')
    assertEquals(RecoveryCode.decode(confusable).map(_.toSeq), Right(master.toSeq))
    val i = code.indexWhere(_ != '-')
    val typo = code.updated(i, if code(i) == 'A' then 'B' else 'A')
    assert(RecoveryCode.decode(typo).isLeft)
  }
