package net.sksat.ashidori.sync

import android.content.Context
import android.security.keystore.{KeyGenParameterSpec, KeyProperties}
import android.util.Base64
import java.security.KeyStore
import javax.crypto.{Cipher, KeyGenerator, SecretKey}
import javax.crypto.spec.GCMParameterSpec
import net.sksat.ashidori.AshidoriApp

/** Small secrets (device token, E2EE master key) wrapped with a
  * non-exportable Android Keystore key and kept in private preferences.
  */
object SecureStore:
  private val Alias = "ashidori-wrap-v1"
  private def prefs = AshidoriApp.app.getSharedPreferences("secure", Context.MODE_PRIVATE)

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
    * a crash would lose.
    */
  def put(name: String, value: Array[Byte]): Unit =
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, wrapKey())
    val blob = c.getIV ++ c.doFinal(value)
    if !prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit() then
      throw java.io.IOException(s"秘密情報（$name）を保存できませんでした")

  def get(name: String): Option[Array[Byte]] =
    Option(prefs.getString(name, null)).map { s =>
      val blob = Base64.decode(s, Base64.NO_WRAP)
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
      c.doFinal(blob, 12, blob.length - 12)
    }

  /** Removes `name` and returns once that is on disk; throws like [[put]]. Blocking. */
  def remove(name: String): Unit =
    if !prefs.edit().remove(name).commit() then throw java.io.IOException(s"秘密情報（$name）を消去できませんでした")
