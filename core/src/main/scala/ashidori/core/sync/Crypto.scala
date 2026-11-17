package ashidori.core.sync

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Using

/** The keys an app holds for blobs, both derived from the master key `K`:
  * the batch key (AES-256-GCM) and the blob authentication key `Ka` (the
  * MAC of [[Crypto.seal]]). A viewer (browser) receives at most the batch
  * key, never `Ka` (docs/design/browser-pairing-v1.md, v1.1).
  */
final class BlobKeys(val batch: Array[Byte], val auth: Array[Byte]):
  require(batch.length == 32 && auth.length == 32, "blob keys are 32 bytes each")

object BlobKeys:
  def of(master: Array[Byte]): BlobKeys = BlobKeys(Crypto.batchKey(master), Crypto.authKey(master))

/** The two blob formats. `Ash1`: GCM only (v1, still read under the rules of
  * [[BlobAuth]]). `Ashm`: the same GCM part followed by an HMAC-SHA256
  * under `Ka` (v1.1; every app writes it).
  */
enum BlobFormat:
  case Ash1, Ashm

/** End-to-end encryption of sync batches (see docs/sync-protocol.md).
  *
  * Only the user's devices know the master key; the server stores the output
  * of [[seal]] and cannot read it.
  *
  * ASHM = "ASHM" ‖ nonce (12) ‖ AES-256-GCM ciphertext and tag ‖ mac (32), with
  * mac = HMAC-SHA256(Ka, "ashidori blob auth v1" ‖ len(AAD) (2, big-endian)
  * ‖ AAD ‖ every byte before the mac). ASH1 is the same without the mac and
  * with the magic "ASH1". The GCM key and the AAD are the same for both.
  */
object Crypto:
  /** Magic of the v1 format (GCM only). */
  val Magic: Array[Byte] = "ASH1".getBytes(UTF_8)
  /** Magic of the v1.1 format (GCM and a MAC under `Ka`). */
  val MagicAuth: Array[Byte] = "ASHM".getBytes(UTF_8)
  private val NonceLen = 12
  private val TagBits = 128
  val MacLen = 32
  private val MacLabel = "ashidori blob auth v1".getBytes(UTF_8)
  private val rnd = SecureRandom()

  def newMasterKey(): Array[Byte] =
    val k = new Array[Byte](32)
    rnd.nextBytes(k)
    k

  /** RFC 5869 HKDF-SHA256 with empty salt. */
  def hkdf(ikm: Array[Byte], info: String, len: Int = 32): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(new Array[Byte](32), "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArrayOutputStream()
    var t = Array.emptyByteArray
    var i = 1
    while out.size < len do
      mac.update(t)
      mac.update(info.getBytes(UTF_8))
      mac.update(i.toByte)
      t = mac.doFinal()
      out.write(t)
      i += 1
    out.toByteArray.take(len)

  def batchKey(master: Array[Byte]): Array[Byte] = hkdf(master, "ashidori batch v1")

  /** `Ka`, the blob authentication key. Only apps hold it. */
  def authKey(master: Array[Byte]): Array[Byte] = hkdf(master, "ashidori batch auth v1")

  def aad(deviceId: String, stream: String, seq: Long): Array[Byte] = s"$deviceId/$stream/$seq".getBytes(UTF_8)

  /** The format of `blob` by its magic and minimum length; None if neither. */
  def format(blob: Array[Byte]): Option[BlobFormat] =
    def magic(m: Array[Byte]) = blob.length >= 4 && java.util.Arrays.equals(blob, 0, 4, m, 0, 4)
    if magic(Magic) && blob.length >= 4 + NonceLen + TagBits / 8 then Some(BlobFormat.Ash1)
    else if magic(MagicAuth) && blob.length >= 4 + NonceLen + TagBits / 8 + MacLen then Some(BlobFormat.Ashm)
    else None

  /** Encrypts a plaintext for one batch slot as ASHM (what every app writes). */
  def seal(keys: BlobKeys, aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val body = gcm(MagicAuth, keys.batch, aad, plaintext)
    body ++ mac(keys.auth, aad, body, body.length)

  /** Encrypts as ASH1 (v1, no MAC). Apps no longer write it; for tests and
    * fixtures of the legacy format only.
    */
  def sealAsh1(batchKey: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    gcm(Magic, batchKey, aad, plaintext)

  private def gcm(magic: Array[Byte], batchKey: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val nonce = new Array[Byte](NonceLen)
    rnd.nextBytes(nonce)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(batchKey, "AES"), GCMParameterSpec(TagBits, nonce))
    c.updateAAD(aad)
    magic ++ nonce ++ c.doFinal(plaintext)

  /** HMAC-SHA256(Ka, label ‖ len(AAD) (2, big-endian) ‖ AAD ‖ blob[0, len)). */
  def mac(authKey: Array[Byte], aad: Array[Byte], blob: Array[Byte], len: Int): Array[Byte] =
    require(aad.length <= 0xffff, "AAD longer than 65535 bytes")
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(authKey, "HmacSHA256"))
    m.update(MacLabel)
    m.update(((aad.length >> 8) & 0xff).toByte)
    m.update((aad.length & 0xff).toByte)
    m.update(aad)
    m.update(blob, 0, len)
    m.doFinal()

  /** Opens an ASHM blob: checks the MAC under `Ka` (in constant time), then
    * decrypts. Throws if it is not ASHM, was tampered with (any byte, or the
    * AAD), or was moved to another slot.
    */
  def open(keys: BlobKeys, aad: Array[Byte], blob: Array[Byte]): Array[Byte] =
    require(format(blob).contains(BlobFormat.Ashm), "not an ASHM blob")
    val n = blob.length - MacLen
    val expect = mac(keys.auth, aad, blob, n)
    if !MessageDigest.isEqual(expect, java.util.Arrays.copyOfRange(blob, n, blob.length)) then
      throw java.security.GeneralSecurityException("blob MAC mismatch (tampered, moved, or not sealed by an app of this user)")
    gcmOpen(keys.batch, aad, blob, n)

  /** Decrypts an ASH1 blob (GCM only). Whether an ASH1 blob may be accepted
    * at all is for [[BlobAuth]] to decide; this only decrypts.
    */
  def openAsh1(batchKey: Array[Byte], aad: Array[Byte], blob: Array[Byte]): Array[Byte] =
    require(format(blob).contains(BlobFormat.Ash1), "not an ASH1 blob")
    gcmOpen(batchKey, aad, blob, blob.length)

  /** GCM part of a blob, `blob[0, end)`; throws on a wrong key, AAD or byte. */
  private def gcmOpen(batchKey: Array[Byte], aad: Array[Byte], blob: Array[Byte], end: Int): Array[Byte] =
    val nonce = blob.slice(4, 4 + NonceLen)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(batchKey, "AES"), GCMParameterSpec(TagBits, nonce))
    c.updateAAD(aad)
    c.doFinal(blob, 4 + NonceLen, end - 4 - NonceLen)

  def gzip(b: Array[Byte]): Array[Byte] =
    val bo = ByteArrayOutputStream()
    Using.resource(GZIPOutputStream(bo))(_.write(b))
    bo.toByteArray

  /** Decompresses `b`, aborting once the output passes `max` bytes. */
  def gunzip(b: Array[Byte], max: Int = Int.MaxValue): Array[Byte] =
    Using.resource(GZIPInputStream(ByteArrayInputStream(b)))(in => Limits.readAtMost(in, max, "decompressed batch"))

  def sha256Hex(b: Array[Byte]): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  def randomToken(): String =
    val b = new Array[Byte](32)
    rnd.nextBytes(b)
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b)

/** Input exceeded a size or count cap; reading was aborted. */
final class LimitExceeded(what: String, max: Long) extends java.io.IOException(s"$what exceeds $max")

object Limits:
  /** Reads `in` to the end, aborting as soon as more than `max` bytes arrive. */
  def readAtMost(in: java.io.InputStream, max: Int, what: String): Array[Byte] =
    val out = ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = in.read(buf)
    while n >= 0 do
      if out.size.toLong + n > max then throw LimitExceeded(what, max)
      out.write(buf, 0, n)
      n = in.read(buf)
    out.toByteArray

/** Human-friendly encoding of the master key: Crockford base32 with a
  * 2-byte checksum, grouped by 4 characters.
  */
object RecoveryCode:
  private val Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  def encode(key: Array[Byte]): String =
    val check = MessageDigest.getInstance("SHA-256").digest(key).take(2)
    base32(key ++ check).grouped(4).mkString("-")

  def decode(code: String): Either[String, Array[Byte]] =
    val clean = code.toUpperCase.filterNot(c => c == '-' || c.isWhitespace)
      .map { case 'O' => '0'; case 'I' | 'L' => '1'; case c => c }
    if !clean.forall(Alphabet.contains(_)) then Left("使えない文字が含まれています")
    else
      val bytes = unbase32(clean)
      if bytes.length != 34 then Left("長さが正しくありません")
      else
        val (key, check) = bytes.splitAt(32)
        val expect = MessageDigest.getInstance("SHA-256").digest(key).take(2)
        if expect.sameElements(check) then Right(key) else Left("入力に誤りがあります")

  private def base32(b: Array[Byte]): String =
    val sb = StringBuilder()
    var buf = 0
    var bits = 0
    for x <- b do
      buf = (buf << 8) | (x & 0xff)
      bits += 8
      while bits >= 5 do
        sb.append(Alphabet((buf >> (bits - 5)) & 31))
        bits -= 5
    if bits > 0 then sb.append(Alphabet((buf << (5 - bits)) & 31))
    sb.toString

  private def unbase32(s: String): Array[Byte] =
    val out = ByteArrayOutputStream()
    var buf = 0
    var bits = 0
    for c <- s do
      buf = (buf << 5) | Alphabet.indexOf(c)
      bits += 5
      if bits >= 8 then
        out.write((buf >> (bits - 8)) & 0xff)
        bits -= 8
    out.toByteArray
