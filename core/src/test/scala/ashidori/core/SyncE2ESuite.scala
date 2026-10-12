package ashidori.core

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using
import ashidori.core.sync.*

/** Downloads every batch from a running sync server and decrypts it with the
  * recovery code, proving the server only holds ciphertext that the user's
  * key opens. Runs only when ASHIDORI_E2E_URL / _TOKEN / _RECOVERY are set
  * (see the sync end-to-end script); skipped otherwise.
  */
class SyncE2ESuite extends munit.FunSuite:
  private val env = for
    url <- sys.env.get("ASHIDORI_E2E_URL")
    token <- sys.env.get("ASHIDORI_E2E_TOKEN")
    rec <- sys.env.get("ASHIDORI_E2E_RECOVERY")
  yield (url, token, rec)

  private def get(url: String, token: String): Array[Byte] =
    val c = URL(url).openConnection().asInstanceOf[HttpURLConnection]
    c.setRequestProperty("Authorization", s"Bearer $token")
    assertEquals(c.getResponseCode, 200, url)
    Using.resource(c.getInputStream)(_.readAllBytes())

  test("server batches decrypt with the recovery code and hold valid records") {
    assume(env.isDefined, "ASHIDORI_E2E_* not set")
    val (base, token, rec) = env.get
    val key = Crypto.batchKey(RecoveryCode.decode(rec).fold(e => fail(e), identity))
    val list = ujson.read(String(get(s"$base/v1/batches?limit=1000", token), UTF_8))("items").arr
    assert(list.nonEmpty)
    val counts = list.map { it =>
      val (dev, stream, seq) = (it("deviceId").str, it("stream").str, it("seq").num.toLong)
      val blob = get(s"$base/v1/batches/$dev/$stream/$seq", token)
      assertEquals(Crypto.sha256Hex(blob), it("sha256").str)
      // Ciphertext must not contain recognisable plaintext.
      assert(!String(blob, UTF_8).contains("\"lat\""))
      val lines = String(Crypto.gunzip(Crypto.open(key, Crypto.aad(dev, stream, seq), blob)), UTF_8).linesIterator.toVector
      lines.foreach(l => assert(ujson.read(l).obj.nonEmpty))
      stream -> lines.size
    }.groupMapReduce(_._1)(_._2)(_ + _)
    println(s"[sync e2e] decrypted records per stream: $counts")
    // A wrong key must not open anything.
    val wrong = Crypto.batchKey(Crypto.newMasterKey())
    val it = list.head
    intercept[Exception] {
      Crypto.open(wrong, Crypto.aad(it("deviceId").str, it("stream").str, it("seq").num.toLong),
        get(s"$base/v1/batches/${it("deviceId").str}/${it("stream").str}/${it("seq").num.toLong}", token))
    }
  }
