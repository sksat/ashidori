package ashidori.core.sync

import java.math.BigInteger
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.{MessageDigest, SecureRandom}
import java.text.Normalizer
import java.util.Base64

import com.google.crypto.tink.{HybridEncrypt, KeysetHandle, RegistryConfiguration}
import com.google.crypto.tink.hybrid.{HpkeParameters, HpkePublicKey, HybridConfig}
import com.google.crypto.tink.util.Bytes

/** Browser pairing v1, the app's side of the values (docs/design/browser-pairing-v1.md,
  * 「値と符号化」): the commit, the app's nonce, the transcript `th`, the four
  * words, and the HPKE grant that carries the batch key to the browser.
  *
  * The browser receives the batch key only: never `K`, the recovery code or
  * the blob authentication key.
  */
object ViewerPairing:
  val Scope = "batch-v1"
  /** Lifetimes of a viewer the app offers, in seconds: 1 h (the default) and 24 h. */
  val Ttls: Vector[Int] = Vector(3600, 86400)
  val DefaultTtl = 3600
  /** An attempt lives 10 minutes from its creation (server) or from the claim's answer (app). */
  val AttemptMs: Long = 10 * 60 * 1000L
  val PollMs: Long = 2000L
  val KeyLen = 32
  val IdLen = 16
  val CodeLen = 8
  val Version: Byte = 1
  /** Version (1) ‖ sA (32) ‖ batch key (32). */
  val PlainLen = 65
  /** enc (32) ‖ ciphertext (65) ‖ tag (16). */
  val GrantLen = 113
  val MaxViewers = 5

  private val CommitLabel = "ashidori pair commit v1"
  private val AppLabel = "ashidori pair app v1"
  private val ThLabel = "ashidori pair v1"
  private val SasLabel = "ashidori pair sas v1"
  private val InfoLabel = "ashidori viewer grant v1"
  private val CodeRe = "^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$".r

  private val rnd = SecureRandom()

  def sha256(parts: Array[Byte]*): Array[Byte] =
    val md = MessageDigest.getInstance("SHA-256")
    parts.foreach(p => md.update(p))
    md.digest()

  private def ascii(s: String): Array[Byte] = s.getBytes(US_ASCII)

  def random(n: Int = KeyLen): Array[Byte] =
    val b = new Array[Byte](n)
    rnd.nextBytes(b)
    b

  /** c = H("ashidori pair commit v1" ‖ pkB ‖ nB). */
  def commit(pkB: Array[Byte], nB: Array[Byte]): Array[Byte] =
    require(pkB.length == KeyLen && nB.length == KeyLen, "pkB and nB are 32 bytes")
    sha256(ascii(CommitLabel), pkB, nB)

  /** nA = H("ashidori pair app v1" ‖ sA). */
  def appNonce(sA: Array[Byte]): Array[Byte] =
    require(sA.length == KeyLen, "sA is 32 bytes")
    sha256(ascii(AppLabel), sA)

  /** All fields of one attempt that the four words, the approval and the grant bind. */
  final case class Transcript(id: Array[Byte], code: String, c: Array[Byte], nA: Array[Byte],
                              scope: String, ttl: Int, pkB: Array[Byte], nB: Array[Byte]):
    require(id.length == IdLen, "id is 16 bytes")
    require(CodeRe.matches(code), "code is 8 Crockford characters")
    require(c.length == KeyLen && nA.length == KeyLen && pkB.length == KeyLen && nB.length == KeyLen)
    require(scope.nonEmpty && scope.length <= 255 && scope.forall(ch => ch >= 0x20 && ch < 0x7f), "scope is short ASCII")
    require(ttl > 0, "ttl is positive")

    /** th = H("ashidori pair v1" ‖ id ‖ code ‖ c ‖ nA ‖ len(scope) ‖ scope ‖ ttl (4, BE) ‖ pkB ‖ nB). */
    def hash: Array[Byte] =
      val s = ascii(scope)
      val t = java.nio.ByteBuffer.allocate(4).putInt(ttl).array()
      sha256(ascii(ThLabel), id, ascii(code), c, nA, Array(s.length.toByte), s, t, pkB, nB)

  /** The four BIP-39 indices: the first 44 bits of H("ashidori pair sas v1" ‖ th), 11 bits each, big-endian. */
  def sasIndices(th: Array[Byte]): Vector[Int] =
    require(th.length == KeyLen, "th is 32 bytes")
    val d = sha256(ascii(SasLabel), th)
    var x = 0L
    for i <- 0 until 6 do x = (x << 8) | (d(i) & 0xff)
    x >>>= 4 // 48 bits -> the first 44
    Vector.tabulate(4)(i => ((x >>> (33 - 11 * i)) & 0x7ff).toInt)

  def sasWords(th: Array[Byte]): Vector[String] = sasIndices(th).map(Bip39Japanese.words)

  /** Crockford: upper case, without `-` and spaces, O -> 0, I and L -> 1; None unless 8 valid characters. */
  def normalizeCode(input: String): Option[String] =
    val s = input.toUpperCase(java.util.Locale.ROOT).filterNot(ch => ch == '-' || ch.isWhitespace)
      .replace('O', '0').replace('I', '1').replace('L', '1')
    Option.when(CodeRe.matches(s))(s)

  /** XXXX-XXXX, as the browser shows it. */
  def displayCode(code: String): String = if code.length == 8 then s"${code.take(4)}-${code.drop(4)}" else code

  // ---- X25519 public keys of low order (RFC 7748; the shared secret would be all zero) ----

  private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
  private def le(hex: String): BigInteger = BigInteger(1, hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray.reverse)
  /** u mod p of the points of order 1, 2, 4 and 8. */
  private val LowOrderU: Set[BigInteger] = Set(
    BigInteger.ZERO,
    BigInteger.ONE,
    P.subtract(BigInteger.ONE),
    le("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
    le("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
  )

  /** True for a public key whose shared secret is all zero for every private key, in
    * any encoding (the top bit masked as RFC 7748 requires, the value taken mod p).
    */
  def isLowOrder(pk: Array[Byte]): Boolean =
    require(pk.length == KeyLen, "X25519 public keys are 32 bytes")
    val b = pk.clone()
    b(31) = (b(31) & 0x7f).toByte
    LowOrderU.contains(BigInteger(1, b.reverse).mod(P))

  // ---- The grant ----

  /** info = "ashidori viewer grant v1" ‖ th. */
  def info(th: Array[Byte]): Array[Byte] = ascii(InfoLabel) ++ th

  /** 0x01 ‖ sA ‖ batch key. */
  def grantPlaintext(sA: Array[Byte], batchKey: Array[Byte]): Array[Byte] =
    require(sA.length == KeyLen && batchKey.length == KeyLen, "sA and the batch key are 32 bytes")
    Array(Version) ++ sA ++ batchKey

  /** HPKE base mode (DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM), as
    * Tink's HybridEncrypt with the NO_PREFIX variant: enc ‖ ciphertext, with
    * `info` as the context info and an empty AAD. Refuses a low-order key.
    */
  def seal(pkB: Array[Byte], info: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    if isLowOrder(pkB) then throw IllegalArgumentException("the browser's public key is of low order")
    encrypter(pkB).encrypt(plaintext, info)

  /** The grant for `th`: exactly [[GrantLen]] bytes. */
  def sealGrant(pkB: Array[Byte], th: Array[Byte], sA: Array[Byte], batchKey: Array[Byte]): Array[Byte] =
    val plain = grantPlaintext(sA, batchKey)
    try
      val g = seal(pkB, info(th), plain)
      if g.length != GrantLen then throw IllegalStateException(s"grant of ${g.length} bytes")
      g
    finally java.util.Arrays.fill(plain, 0.toByte)

  val HpkeParams: HpkeParameters = HpkeParameters.builder()
    .setVariant(HpkeParameters.Variant.NO_PREFIX)
    .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
    .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
    .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
    .build()

  private lazy val registered: Unit = HybridConfig.register()

  private def encrypter(pkB: Array[Byte]): HybridEncrypt =
    registered
    val pub = HpkePublicKey.create(HpkeParams, Bytes.copyFrom(pkB), null)
    KeysetHandle.newBuilder()
      .addEntry(KeysetHandle.importKey(pub).withRandomId().makePrimary())
      .build()
      .getPrimitive(RegistryConfiguration.get(), classOf[HybridEncrypt])

  // ---- base64url without padding, as the API sends byte strings ----

  def b64(b: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  /** Strict: base64url without padding, of exactly `len` bytes. */
  def unb64(s: String, len: Int): Option[Array[Byte]] =
    if !s.matches("^[A-Za-z0-9_-]*$") then None
    else scala.util.Try(Base64.getUrlDecoder.decode(s)).toOption.filter(_.length == len).filter(b => b64(b) == s)

/** The BIP-39 Japanese word list (bitcoin/bips bip-0039/japanese.txt), pinned by its SHA-256. */
object Bip39Japanese:
  val Sha256 = "2eed0aef492291e061633d7ad8117f1a2b03eb80a29d0e4e3117ac2528d05ffd"
  private val Resource = "/ashidori/core/sync/bip39-japanese.txt"

  def raw(): Array[Byte] =
    val in = getClass.getResourceAsStream(Resource)
    if in == null then throw IllegalStateException("BIP-39 word list missing")
    try in.readAllBytes() finally in.close()

  /** 2048 words, NFC (the file is NFKD). Fails if the file is not the pinned one. */
  lazy val words: IndexedSeq[String] =
    val b = raw()
    val got = MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString
    if got != Sha256 then throw IllegalStateException(s"BIP-39 word list has SHA-256 $got")
    val ws = String(b, UTF_8).split("\n").toVector.map(_.trim).filter(_.nonEmpty).map(Normalizer.normalize(_, Normalizer.Form.NFC))
    if ws.size != 2048 then throw IllegalStateException(s"BIP-39 word list has ${ws.size} words")
    ws
