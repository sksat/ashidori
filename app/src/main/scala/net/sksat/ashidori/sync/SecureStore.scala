package net.sksat.ashidori.sync

import android.security.keystore.{KeyGenParameterSpec, KeyProperties}
import android.util.Base64
import java.security.KeyStore
import javax.crypto.{Cipher, KeyGenerator, SecretKey}
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (device token, E2EE master key) wrapped with a
  * non-exportable Android Keystore key and kept in a [[DurablePrefs]] file.
  * A secret is readable only once it is on disk ([[DurablePrefs]]): while it
  * is being written, or after its write failed, [[get]] does not return it.
  */
object SecureStore extends ashidori.core.sync.SecretStore:
  private val Alias = "ashidori-wrap-v1"
  private val prefs = DurablePrefs("secure")

  private def wrapKey(): SecretKey =
    val ks = KeyStore.getInstance("AndroidKeyStore")
    ks.load(null)
    Option(ks.getKey(Alias, null)).map(_.asInstanceOf[SecretKey]).getOrElse {
      val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      gen.init(
        KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
      )
      gen.generateKey()
    }

  /** Stores `value` and returns once it is on disk. Blocking: call off the
    * main thread. Throws [[java.io.IOException]] when the write failed, so a
    * caller never goes on (sends a request, enables sync) with a secret that
    * a crash would lose; the previous value then stays.
    */
  def put(name: String, value: Array[Byte]): Unit =
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, wrapKey())
    val blob = c.getIV ++ c.doFinal(value)
    try prefs.commit(name -> Base64.encodeToString(blob, Base64.NO_WRAP))
    catch case e: java.io.IOException => throw java.io.IOException(s"秘密情報（$name）を保存できませんでした", e)

  /** The secret, if it is on disk. */
  def get(name: String): Option[Array[Byte]] =
    prefs.getString(name).map { s =>
      val blob = Base64.decode(s, Base64.NO_WRAP)
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
      c.doFinal(blob, 12, blob.length - 12)
    }

  /** Removes `name` and returns once that is on disk; throws like [[put]]. Blocking. */
  def remove(name: String): Unit =
    try prefs.commit(name -> null)
    catch case e: java.io.IOException => throw java.io.IOException(s"秘密情報（$name）を消去できませんでした", e)
