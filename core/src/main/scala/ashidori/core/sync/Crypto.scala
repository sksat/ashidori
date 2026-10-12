package ashidori.core.sync

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Using

/** End-to-end encryption of sync batches (see docs/sync-protocol.md).
  *
  * Only the user's devices know the master key; the server stores the output
  * of [[seal]] and cannot read it.
  */
object Crypto:
  val Magic: Array[Byte] = "ASH1".getBytes(UTF_8)
  private val NonceLen = 12
  private val TagBits = 128
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

  def aad(deviceId: String, stream: String, seq: Long): Array[Byte] = s"$deviceId/$stream/$seq".getBytes(UTF_8)

  /** Encrypts a plaintext for one batch slot. */
  def seal(batchKey: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val nonce = new Array[Byte](NonceLen)
    rnd.nextBytes(nonce)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(batchKey, "AES"), GCMParameterSpec(TagBits, nonce))
    c.updateAAD(aad)
    Magic ++ nonce ++ c.doFinal(plaintext)

  /** Decrypts a blob; throws if it was tampered with or moved to another slot. */
  def open(batchKey: Array[Byte], aad: Array[Byte], blob: Array[Byte]): Array[Byte] =
    require(blob.length > Magic.length + NonceLen && blob.take(4).sameElements(Magic), "not a ashidori blob")
    val nonce = blob.slice(4, 4 + NonceLen)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(batchKey, "AES"), GCMParameterSpec(TagBits, nonce))
    c.updateAAD(aad)
    c.doFinal(blob, 4 + NonceLen, blob.length - 4 - NonceLen)

  def gzip(b: Array[Byte]): Array[Byte] =
    val bo = ByteArrayOutputStream()
    Using.resource(GZIPOutputStream(bo))(_.write(b))
    bo.toByteArray

  def gunzip(b: Array[Byte]): Array[Byte] =
    Using.resource(GZIPInputStream(ByteArrayInputStream(b)))(_.readAllBytes())

  def sha256Hex(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  def randomToken(): String =
    val b = new Array[Byte](32)
    rnd.nextBytes(b)
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b)

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
        if expect.sameElements(check) then Right(key) else Left("チェックサムが一致しません（入力ミス）")

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
