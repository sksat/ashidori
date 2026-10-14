package ashidori.core

import ashidori.core.sync.{Bip39Japanese, ViewerPairing as VP}
import com.google.crypto.tink.{HybridDecrypt, InsecureSecretKeyAccess, KeysetHandle, RegistryConfiguration}
import com.google.crypto.tink.hybrid.{HpkePrivateKey, HpkePublicKey, HybridConfig}
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.{Bytes, SecretBytes}

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Interop vectors of browser pairing v1 for the web viewer's tests
  * (server/web/test/fixtures/viewer-pairing.json): the design's SAS vector,
  * the transcript with each field changed, and grants that Tink sealed (with
  * the recipient's private key, so that @hpke/core can open them). Written by
  * `./gradlew :core:webFixtures`; ViewerPairingSuite checks the written file
  * against this code.
  */
object ViewerPairingFixtures:
  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  def fill(b: Int): Array[Byte] = Array.fill(32)(b.toByte)

  /** The design's test vector inputs. */
  val VecId: Array[Byte] = Array.tabulate(16)(_.toByte)
  val VecCode = "ABCD2345"
  val VecPkB: Array[Byte] = fill(0x33)
  val VecNB: Array[Byte] = fill(0x11)
  val VecSA: Array[Byte] = fill(0x44)

  def transcript(id: Array[Byte], code: String, pkB: Array[Byte], nB: Array[Byte], sA: Array[Byte],
                 scope: String = VP.Scope, ttl: Int = 3600): VP.Transcript =
    VP.Transcript(id, code, VP.commit(pkB, nB), VP.appNonce(sA), scope, ttl, pkB, nB)

  private def flip(b: Array[Byte]): Array[Byte] =
    val c = b.clone()
    c(c.length - 1) = (c(c.length - 1) ^ 1).toByte
    c

  /** The vector transcript with one field changed: (field, value as JSON, transcript). */
  def tampered: Vector[(String, ujson.Value, VP.Transcript)] =
    val t = transcript(VecId, VecCode, VecPkB, VecNB, VecSA)
    Vector(
      ("id", ujson.Str(hex(flip(t.id))), t.copy(id = flip(t.id))),
      ("code", ujson.Str("ABCD2346"), t.copy(code = "ABCD2346")),
      ("c", ujson.Str(hex(flip(t.c))), t.copy(c = flip(t.c))),
      ("nA", ujson.Str(hex(flip(t.nA))), t.copy(nA = flip(t.nA))),
      ("scope", ujson.Str("batch-v2"), t.copy(scope = "batch-v2")),
      ("ttl", ujson.Num(86400), t.copy(ttl = 86400)),
      ("pkB", ujson.Str(hex(flip(t.pkB))), t.copy(pkB = flip(t.pkB))),
      ("nB", ujson.Str(hex(flip(t.nB))), t.copy(nB = flip(t.nB))),
    )

  /** Low-order X25519 public keys (and other encodings of them) that the app refuses. */
  val LowOrder: Vector[String] = Vector(
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0100000000000000000000000000000000000000000000000000000000000000",
    "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
    "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    // The same points with the top bit set, which RFC 7748 decoding masks.
    "0100000000000000000000000000000000000000000000000000000000000080",
    "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880",
    "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "0000000000000000000000000000000000000000000000000000000000000080",
  )

  /** Tink's HybridDecrypt for a raw X25519 private key. */
  def decrypter(sk: Array[Byte]): HybridDecrypt =
    HybridConfig.register()
    val pub = HpkePublicKey.create(VP.HpkeParams, Bytes.copyFrom(X25519.publicFromPrivate(sk)), null)
    val priv = HpkePrivateKey.create(pub, SecretBytes.copyFrom(sk, InsecureSecretKeyAccess.get()))
    KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(priv).withRandomId().makePrimary()).build()
      .getPrimitive(RegistryConfiguration.get(), classOf[HybridDecrypt])

  def build(): ujson.Obj =
    val vt = transcript(VecId, VecCode, VecPkB, VecNB, VecSA)
    val vth = vt.hash
    val sas = ujson.Obj(
      "id" -> hex(VecId), "code" -> VecCode, "pkB" -> hex(VecPkB), "nB" -> hex(VecNB), "sA" -> hex(VecSA),
      "scope" -> VP.Scope, "ttl" -> 3600, "c" -> hex(vt.c), "nA" -> hex(vt.nA), "th" -> hex(vth),
      "d" -> hex(VP.sha256("ashidori pair sas v1".getBytes(UTF_8), vth)),
      "indices" -> VP.sasIndices(vth), "words" -> VP.sasWords(vth))
    val tamper = tampered.map((f, v, t) => ujson.Obj("field" -> f, "value" -> v, "th" -> hex(t.hash), "words" -> VP.sasWords(t.hash)))

    // A real key pair, as the browser would make it.
    val sk = X25519.generatePrivateKey()
    val pk = X25519.publicFromPrivate(sk)
    val nB = VP.random()
    val sA = VP.random()
    val id = VP.random(16)
    val batchKey = VP.random()
    val t = transcript(id, "7K3M9QX2", pk, nB, sA, ttl = 86400)
    val th = t.hash
    val info = VP.info(th)
    val grant = VP.sealGrant(pk, th, sA, batchKey)
    val wrongSa = VP.random()
    def sealHex(plain: Array[Byte]) = hex(VP.seal(pk, info, plain))
    ujson.Obj(
      "about" -> "Written by ./gradlew :core:webFixtures (core ViewerPairingFixtures, Tink 1.23.0); do not edit.",
      "wordlistSha256" -> Bip39Japanese.Sha256,
      "sas" -> sas,
      "tamper" -> tamper,
      "grant" -> ujson.Obj(
        "skB" -> hex(sk), "pkB" -> hex(pk), "nB" -> hex(nB), "id" -> hex(id), "code" -> t.code, "sA" -> hex(sA),
        "nA" -> hex(t.nA), "c" -> hex(t.c), "scope" -> t.scope, "ttl" -> t.ttl, "th" -> hex(th), "info" -> hex(info),
        "batchKey" -> hex(batchKey), "grant" -> hex(grant)),
      "wrongSa" -> ujson.Obj("sA" -> hex(wrongSa), "grant" -> sealHex(VP.grantPlaintext(wrongSa, batchKey))),
      "badVersion" -> ujson.Obj("grant" -> sealHex(Array(2.toByte) ++ sA ++ batchKey)),
      "longPlain" -> ujson.Obj("grant" -> sealHex(VP.grantPlaintext(sA, batchKey) :+ 0.toByte)),
      "shortPlain" -> ujson.Obj("grant" -> sealHex(VP.grantPlaintext(sA, batchKey).dropRight(1))),
      "lowOrder" -> LowOrder,
    )

  def main(args: Array[String]): Unit =
    val dir = File(args.headOption.getOrElse("server/web/test/fixtures"))
    dir.mkdirs()
    val f = File(dir, "viewer-pairing.json")
    Files.writeString(f.toPath, ujson.write(build(), indent = 2) + "\n", UTF_8)
    println(s"wrote $f")
