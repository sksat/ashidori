package ashidori.core.sync

import java.nio.charset.StandardCharsets.UTF_8

/** Small secrets, each on disk once [[put]] or [[remove]] returned. */
trait SecretStore:
  def get(name: String): Option[Array[Byte]]
  /** Throws [[java.io.IOException]] when the value is not on disk; the previous one then stays. */
  def put(name: String, value: Array[Byte]): Unit
  def remove(name: String): Unit

/** The registration of this device (server URL, device id, token) and its
  * E2EE master key, as stored on the device.
  *
  * The master key is stored together with the registration it was set for
  * ([[registration]]) and counts only for that one: a key left over from an
  * earlier registration (e.g. an unpairing cut short by a crash) is never
  * used for a later one.
  *
  * Unpairing ([[forget]]) clears several stores one after the other, so it
  * is marked on disk first (`unpairing`) and finished by [[resume]] after a
  * crash. While it is marked, the device is neither paired nor ready, and no
  * new registration is saved.
  *
  * `config` holds the URL, device id, the self-registration flag and the
  * mark; `secrets` holds the token and the key.
  */
final class PairingStore(config: DurableMap, secrets: SecretStore):
  import PairingStore.*

  def unpairing: Boolean = config.getBoolean(Unpairing, false)
  def serverUrl: String = config.getString(Url).getOrElse("")
  def deviceId: String = config.getString(DeviceId).getOrElse("")
  /** This device created its user (self-registration), so no other device has a key yet. */
  def newAccount: Boolean = config.getBoolean(NewAccount, false)
  def token: Option[String] = secrets.get(Token).map(String(_, UTF_8))

  /** Identifies the registration (server URL and device id). Empty when not
    * registered or while unpairing.
    */
  def registration: String =
    val (u, d) = (serverUrl, deviceId)
    if unpairing || u.isEmpty || d.isEmpty then "" else registrationOf(u, d)

  /** The master key of the current registration. */
  def masterKey: Option[Array[Byte]] =
    val reg = registration
    if reg.isEmpty then None else secrets.get(Master).flatMap(unbind).collect { case (r, k) if r == reg => k }

  def paired: Boolean = registration.nonEmpty && token.isDefined
  def ready: Boolean = paired && masterKey.isDefined

  /** Sets the key of the registration `reg`, once. Refuses (false) when a key
    * is already set or the registration changed since the key step began, so
    * a confirmed key is never replaced. Returns true only once the key is on
    * disk; a failed write throws, and the key then reads as absent.
    */
  def confirmKey(k: Array[Byte], reg: String): Boolean = synchronized {
    if reg.isEmpty || reg != registration || masterKey.isDefined then false
    else
      secrets.put(Master, bind(reg, k))
      true
  }

  /** Stores a registration, each part checked to be on disk; throws
    * [[java.io.IOException]] otherwise. Refused while unpairing. A key stored
    * for another registration stays unused ([[masterKey]]).
    */
  def savePairing(url: String, deviceId: String, token: String, newAccount: Boolean): Unit = synchronized {
    if unpairing then throw IllegalStateException("登録の解除が終わっていません")
    // The token first: the URL and id make the registration count.
    secrets.put(Token, token.getBytes(UTF_8))
    config.commit(Url -> url.stripSuffix("/"), DeviceId -> deviceId, NewAccount -> newAccount)
  }

  /** Unpairs: marks it on disk, runs `invalidate` (stop registration
    * attempts and sync jobs), then, inside `locked` (the sync lock), clears
    * the registration, the token, the key and `clearRest` (sync progress,
    * peer state), and only then removes the mark. Any step may throw (or
    * the process die); [[resume]] then does it again from the start.
    */
  def forget(invalidate: () => Unit, locked: (() => Unit) => Unit, clearRest: () => Unit): Unit =
    config.commit(Unpairing -> true)
    invalidate()
    locked { () =>
      config.commit(Url -> null, DeviceId -> null, NewAccount -> null)
      secrets.remove(Token)
      secrets.remove(Master)
      clearRest()
      config.commit(Unpairing -> null)
    }

  /** A key stored by an earlier version carries no registration: it is
    * bound to the current one, once, at start (that version had no other
    * registration to set it for, short of an unpairing cut short by a crash,
    * which it could not detect either). True when a key was bound.
    */
  def adoptLegacyKey(): Boolean = synchronized {
    val reg = registration
    secrets.get(Master) match
      case Some(b) if reg.nonEmpty && unbind(b).isEmpty =>
        secrets.put(Master, bind(reg, b))
        true
      case _ => false
  }

  /** Finishes an unpairing a crash cut short; call at start. True when one was. */
  def resume(invalidate: () => Unit, locked: (() => Unit) => Unit, clearRest: () => Unit): Boolean =
    if !unpairing then false
    else
      forget(invalidate, locked, clearRest)
      true

object PairingStore:
  val Url = "url"
  val DeviceId = "deviceId"
  val NewAccount = "newAccount"
  val Unpairing = "unpairing"
  val Token = "token"
  val Master = "master"

  def registrationOf(url: String, deviceId: String): String =
    Crypto.sha256Hex(s"$url\n$deviceId".getBytes(UTF_8)).take(16)

  private val Tag = "reg:".getBytes(UTF_8)

  /** The key as stored: `reg:<registration>\n` then the key bytes. */
  def bind(reg: String, key: Array[Byte]): Array[Byte] = Tag ++ s"$reg\n".getBytes(UTF_8) ++ key

  /** The registration and key of a stored key; None for a key stored by an
    * earlier version without its registration (see [[PairingStore.adoptLegacyKey]]).
    */
  def unbind(b: Array[Byte]): Option[(String, Array[Byte])] =
    if !b.startsWith(Tag) then None
    else
      val nl = b.indexOf('\n'.toByte, Tag.length)
      if nl < 0 then None else Some((String(b.slice(Tag.length, nl), UTF_8), b.drop(nl + 1)))
