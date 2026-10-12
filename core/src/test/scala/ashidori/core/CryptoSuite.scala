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

  test("seal/open roundtrip with gzip") {
    val text = """{"t":1,"lat":35.0,"lon":139.0}""" * 100
    val aad = Crypto.aad("dev1", "fixes", 7)
    val blob = Crypto.seal(key, aad, Crypto.gzip(text.getBytes(UTF_8)))
    assert(blob.take(4).sameElements(Crypto.Magic))
    assertEquals(String(Crypto.gunzip(Crypto.open(key, aad, blob)), UTF_8), text)
  }

  test("moving a blob to another slot or tampering fails") {
    val blob = Crypto.seal(key, Crypto.aad("dev1", "fixes", 7), "secret".getBytes(UTF_8))
    intercept[Exception](Crypto.open(key, Crypto.aad("dev1", "fixes", 8), blob))
    intercept[Exception](Crypto.open(key, Crypto.aad("dev2", "fixes", 7), blob))
    val bad = blob.clone()
    bad(bad.length - 1) = (bad(bad.length - 1) ^ 1).toByte
    intercept[Exception](Crypto.open(key, Crypto.aad("dev1", "fixes", 7), bad))
    intercept[Exception](Crypto.open(Crypto.batchKey(Crypto.newMasterKey()), Crypto.aad("dev1", "fixes", 7), blob))
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
