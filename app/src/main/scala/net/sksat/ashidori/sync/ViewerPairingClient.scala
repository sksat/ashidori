package net.sksat.ashidori.sync

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try
import ashidori.core.sync.{Crypto, ViewerPairingApi, ViewerPairing as VP}
import ashidori.core.sync.ViewerPairingApi.*

/** The app's routes of browser pairing v1 (`/v1/viewer-pairings/…`,
  * docs/design/browser-pairing-v1.md) over [[SyncClient]], with this
  * device's token. Blocking; call off the main thread.
  */
final class ViewerPairingClient(client: SyncClient) extends ViewerPairingApi:
  private def post(path: String, body: ujson.Value): client.Response =
    client.request("POST", path, ujson.write(body).getBytes(UTF_8))

  private def ok(r: client.Response): ujson.Value =
    if r.code == 200 || r.code == 201 then r.json
    else
      val js = Try(ujson.read(r.text)).toOption
      val error = js.flatMap(_.objOpt).flatMap(_.get("error")).flatMap(_.strOpt).getOrElse("")
      val state = js.flatMap(_.objOpt).flatMap(_.get("state")).flatMap(_.strOpt)
      // 408 and 429 are worth retrying; other 4xx are refusals.
      if r.code >= 400 && r.code < 500 && r.code != 408 && r.code != 429 then throw Refused(r.code, error, state)
      throw RuntimeException(s"HTTP ${r.code} $error")

  private def bytes(js: ujson.Value, key: String, len: Int): Option[Array[Byte]] =
    js.obj.get(key).flatMap(_.strOpt).map(s => VP.unb64(s, len).getOrElse(throw RuntimeException(s"malformed $key")))

  private def enc(id: String): String = java.net.URLEncoder.encode(id, UTF_8)

  def claim(code: String): Claim =
    val js = ok(post("/v1/viewer-pairings/claim", ujson.Obj("code" -> code)))
    Claim(js("id").str, bytes(js, "commit", VP.KeyLen).getOrElse(throw RuntimeException("no commit")), js("expiresAt").num.toLong)

  def nonce(id: String, nA: Array[Byte], scope: String, ttl: Int): Unit =
    ok(post(s"/v1/viewer-pairings/${enc(id)}/nonce", ujson.Obj("nA" -> VP.b64(nA), "scope" -> scope, "ttl" -> ttl)))

  def app(id: String): AppView =
    val js = ok(client.request("GET", s"/v1/viewer-pairings/${enc(id)}/app"))
    AppView(js("state").str, js("expiresAt").num.toLong, js.obj.get("confirmed").exists(_.boolOpt.contains(true)),
      bytes(js, "pkB", VP.KeyLen), bytes(js, "nB", VP.KeyLen), js.obj.get("deviceId").flatMap(_.strOpt))

  def approve(id: String, grant: Array[Byte]): ApproveView =
    val js = ok(post(s"/v1/viewer-pairings/${enc(id)}/approve", ujson.Obj("grant" -> VP.b64(grant))))
    ApproveView(js("state").str, js.obj.get("deviceId").flatMap(_.strOpt))

  def abort(id: String): String =
    ok(post(s"/v1/viewer-pairings/${enc(id)}/abort", ujson.Obj()))("state").str

/** A browser (`kind` "viewer") as `GET /v1/devices` lists it. */
final case class Viewer(id: String, createdAt: Long, expiresAt: Long, revoked: Boolean, grantedBy: Option[String])

object Viewers:
  /** The active browsers of a `GET /v1/devices` answer (not revoked, not expired at `now`). */
  def active(devices: ujson.Value, now: Long): Vector[Viewer] =
    devices.arr.toVector.flatMap { d =>
      Option.when(d.obj.get("kind").flatMap(_.strOpt).contains("viewer")) {
        Viewer(d("id").str, d.obj.get("createdAt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
          d.obj.get("expiresAt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
          d.obj.get("revoked").exists(_.boolOpt.contains(true)),
          d.obj.get("grantedBy").flatMap(_.strOpt))
      }
    }.filter(v => !v.revoked && v.expiresAt > now)

/** What must hold before this device hands a batch key to a browser.
  *
  * Blob authentication (v1.1, docs/design/browser-pairing-v1.md, 「blob の
  * 認証（v1.1）」): before the first viewer is approved, every device that
  * is neither revoked nor a viewer must have been seen writing `ASHM`, and the
  * cut-over record `C` must exist (made here, `201`, if not), so that a
  * browser's batch key cannot forge blobs that the apps accept.
  * [[AuthCutover.ensureCutover]] does that; until it succeeds the pairing
  * does not start. Blocking (network, [[SyncLock]]): call off the main thread.
  */
object ViewerGate:
  def check(): Either[String, Unit] =
    try
      AuthCutover.ensureCutover()
      Right(())
    catch
      case e: ashidori.core.sync.CutoverNotReady => Left(e.getMessage + "。")
      case e: Exception => Left(s"ほかの端末の状態を確認できませんでした。時間をおいて再度お試しください（${e.getMessage}）。")

  /** This device's batch key (never `K` itself), for one grant. */
  def batchKey(): Array[Byte] =
    val k = SyncConfig.masterKey.getOrElse(throw IllegalStateException("no key"))
    try Crypto.batchKey(k) finally java.util.Arrays.fill(k, 0.toByte)
