package ashidori.core.sync

import java.util.Arrays

/** The server routes the app uses for one browser pairing attempt
  * (docs/design/browser-pairing-v1.md, 「サーバ API」). Blocking; a refusal
  * (4xx) throws [[ViewerPairingApi.Refused]], anything else (network errors,
  * 5xx) another exception.
  */
trait ViewerPairingApi:
  import ViewerPairingApi.*
  def claim(code: String): Claim
  def nonce(id: String, nA: Array[Byte], scope: String, ttl: Int): Unit
  def app(id: String): AppView
  def approve(id: String, grant: Array[Byte]): ApproveView
  def abort(id: String): String

object ViewerPairingApi:
  final case class Claim(id: String, commit: Array[Byte], expiresAt: Long)
  final case class AppView(state: String, expiresAt: Long, confirmed: Boolean,
                           pkB: Option[Array[Byte]], nB: Option[Array[Byte]], deviceId: Option[String])
  final case class ApproveView(state: String, deviceId: Option[String])
  /** `error` is the server's `error` field, `state` its `state` field (if any). */
  final class Refused(val status: Int, val error: String, val state: Option[String])
      extends RuntimeException(s"HTTP $status $error")

/** Why an attempt ended without a viewer. */
enum ViewerFailure:
  /** The code is not 8 Crockford characters (nothing was sent). */
  case InvalidCode
  /** The server knows no such code (unknown, expired, or claimed by another device). */
  case UnknownCode
  /** The user said the words differ. */
  case Mismatch
  /** The browser's reveal does not match its commit, or its key is of low order. */
  case BadReveal
  /** The server's id or commit differs from the scanned QR code's. */
  case QrMismatch
  /** A pinned value came back different. */
  case Changed
  case Expired
  /** Cancelled here or by the browser, or the approving device was revoked. */
  case Aborted
  /** Blob authentication (v1.1) is not in force on all devices yet. */
  case NotReady(message: String)
  case Refused(message: String)

/** The app's side of one attempt: claim with the code the user typed (or
  * the one of the scanned QR code), send `nA`, wait for the browser's
  * reveal, show the words, approve with a grant sealed once. Every value is
  * pinned when first received (「固定する時点」); a different value later
  * aborts the attempt. After a scan ([[startQr]]), the id and the commit the
  * server answers must be the QR code's: the camera, not the server, tells
  * the app which key the browser holds.
  *
  * `now` is a monotonic clock in ms. The attempt's local deadline is
  * [[ViewerPairing.AttemptMs]] after the claim was answered; past it the
  * attempt sends nothing more, looks once whether it became active, and
  * otherwise ends as expired. `sA` and the grant stay in memory only.
  *
  * Methods are blocking (call them off the main thread) and synchronized;
  * phases only move forward. A screen keeps one instance per attempt and
  * ignores results of an instance it no longer shows.
  */
final class ViewerApproval(api: ViewerPairingApi, batchKey: () => Array[Byte], now: () => Long):
  import ViewerApproval.*
  import ViewerPairing as VP

  private var phase: Phase = Phase.Idle
  private var id: String = ""
  private var code: String = ""
  private var c: Array[Byte] = Array.emptyByteArray
  private var sA: Array[Byte] = Array.emptyByteArray
  private var nA: Array[Byte] = Array.emptyByteArray
  private var ttl: Int = VP.DefaultTtl
  private var deadline: Long = 0L
  private var pinned: Option[(Array[Byte], Array[Byte])] = None
  private var th: Array[Byte] = Array.emptyByteArray
  private var grant: Option[Array[Byte]] = None

  def current: Phase = synchronized(phase)
  def attemptId: String = synchronized(id)
  def ttlSeconds: Int = synchronized(ttl)
  /** Milliseconds left before the local deadline (0 when past it or not started). */
  def remainingMs: Long = synchronized(if deadline == 0L then 0L else math.max(0L, deadline - now()))

  private def fail(f: ViewerFailure): Phase =
    phase = Phase.Failed(f)
    if sA.nonEmpty then Arrays.fill(sA, 0.toByte)
    grant = None
    phase

  private def abortAndFail(f: ViewerFailure): Phase =
    try api.abort(id) catch case _: Exception => () // the attempt expires anyway
    fail(f)

  private def terminal: Boolean = phase match
    case Phase.Done(_, _) | Phase.Failed(_) => true
    case _ => false

  /** Claims the attempt for `codeInput` and sends `nA`, `scope` and `ttl` (pinned here). */
  def start(codeInput: String, ttlSeconds: Int): Phase = synchronized(startImpl(codeInput, ttlSeconds, None))

  /** Claims the attempt of a scanned QR code (whose server the caller checked);
    * goes on only if the server answers the QR code's id and commit.
    */
  def startQr(qr: ViewerQr, ttlSeconds: Int): Phase = synchronized(startImpl(qr.code, ttlSeconds, Some(qr)))

  private def startImpl(codeInput: String, ttlSeconds: Int, qr: Option[ViewerQr]): Phase =
    require(phase == Phase.Idle, "an attempt starts once")
    require(VP.Ttls.contains(ttlSeconds), "ttl is 1 h or 24 h")
    VP.normalizeCode(codeInput) match
      case None => fail(ViewerFailure.InvalidCode)
      case Some(norm) =>
        // The code the user typed (or the QR code's), normalized: never the server's echo.
        code = norm
        ttl = ttlSeconds
        try
          val cl = api.claim(code)
          deadline = now() + VP.AttemptMs
          if VP.unb64(cl.id, VP.IdLen).isEmpty || cl.commit.length != VP.KeyLen then return fail(ViewerFailure.Refused("サーバの応答が不正です。"))
          id = cl.id
          // The camera's commit, not the server's: a different one ends the attempt before nA is sent.
          if qr.exists(q => q.id != cl.id || !Arrays.equals(q.commit, cl.commit)) then return abortAndFail(ViewerFailure.QrMismatch)
          c = cl.commit
          // sA only after c arrived.
          sA = VP.random()
          nA = VP.appNonce(sA)
          api.nonce(id, nA, VP.Scope, ttl)
          phase = Phase.WaitingReveal
          phase
        catch
          case r: ViewerPairingApi.Refused if r.status == 404 && id.isEmpty => fail(ViewerFailure.UnknownCode)
          case r: ViewerPairingApi.Refused => refused(r)

  private def refused(r: ViewerPairingApi.Refused): Phase =
    if r.status == 410 then fail(ViewerFailure.Expired)
    else if r.state.contains("aborted") then fail(ViewerFailure.Aborted)
    else if r.status == 409 && r.error == "conflict" then abortAndFail(ViewerFailure.Changed)
    else fail(ViewerFailure.Refused(s"サーバが受け付けませんでした (${r.status} ${r.error})。"))

  /** One poll of `GET /:id/app`. */
  def poll(): Phase = synchronized(pollImpl())

  private def pollImpl(): Phase =
    if phase == Phase.Idle || terminal then return phase
    if now() >= deadline then return expire()
    try
      val v = api.app(id)
      v.state match
        case "aborted" => fail(ViewerFailure.Aborted)
        case "active" =>
          if grant.isEmpty then abortAndFail(ViewerFailure.Changed)
          else done(v.deviceId)
        case _ =>
          (v.pkB, v.nB) match
            case (Some(pk), Some(n)) =>
              onReveal(pk, n) match
                // Approved here and confirmed in the browser, yet not active:
                // the viewer cap (approve → confirm). The attempt stays open;
                // approving again, or the browser's retry, activates it once a
                // slot is free.
                case Phase.Approved(words, scope, t) if v.state == "revealed" && v.confirmed =>
                  phase = Phase.Showing(words, scope, t, tooManyViewers = true)
                  phase
                case p => p
            case _ => phase
    catch case r: ViewerPairingApi.Refused => refused(r)

  private def done(deviceId: Option[String]): Phase =
    deviceId match
      case Some(d) =>
        phase = Phase.Done(d, ttl)
        Arrays.fill(sA, 0.toByte)
        phase
      case None => fail(ViewerFailure.Refused("サーバの応答が不正です。"))

  private def onReveal(pk: Array[Byte], n: Array[Byte]): Phase =
    pinned match
      case Some((p0, n0)) =>
        if !Arrays.equals(p0, pk) || !Arrays.equals(n0, n) then abortAndFail(ViewerFailure.Changed) else phase
      case None =>
        if pk.length != VP.KeyLen || n.length != VP.KeyLen || !Arrays.equals(VP.commit(pk, n), c) || VP.isLowOrder(pk) then
          abortAndFail(ViewerFailure.BadReveal)
        else
          pinned = Some((pk.clone(), n.clone()))
          th = VP.Transcript(VP.unb64(id, VP.IdLen).get, code, c, nA, VP.Scope, ttl, pk, n).hash
          phase = Phase.Showing(VP.sasWords(th), VP.Scope, ttl, tooManyViewers = false)
          phase

  /** 「承認」: seals the grant once and sends it; a re-send uses the same bytes. */
  def approve(): Phase = synchronized(approveImpl())

  private def approveImpl(): Phase =
    phase match
      case Phase.Showing(_, _, _, _) | Phase.Approved(_, _, _) =>
        if now() >= deadline then return expire()
        val (pk, _) = pinned.get
        val g = grant.getOrElse {
          val k = batchKey()
          try VP.sealGrant(pk, th, sA, k) finally Arrays.fill(k, 0.toByte)
        }
        grant = Some(g)
        val words = VP.sasWords(th)
        try
          val r = api.approve(id, g)
          r.state match
            case "active" => done(r.deviceId)
            case "revealed" =>
              phase = Phase.Approved(words, VP.Scope, ttl)
              phase
            case "aborted" => fail(ViewerFailure.Aborted)
            case s => fail(ViewerFailure.Refused(s"サーバの状態が不正です ($s)。"))
        catch
          case r: ViewerPairingApi.Refused if r.status == 409 && r.error == "too many viewers" =>
            phase = Phase.Showing(words, VP.Scope, ttl, tooManyViewers = true)
            phase
          case r: ViewerPairingApi.Refused => refused(r)
      case p => p

  /** 「一致しない」. */
  def mismatch(): Phase = synchronized { if terminal || phase == Phase.Idle then phase else abortAndFail(ViewerFailure.Mismatch) }

  /** Leaving the screen: aborts an unfinished attempt (best effort). */
  def cancel(): Phase = synchronized { if terminal || phase == Phase.Idle then phase else abortAndFail(ViewerFailure.Aborted) }

  private def expire(): Phase =
    // Past the deadline: send nothing more; look once whether it became active.
    try
      val v = api.app(id)
      if v.state == "active" && grant.nonEmpty then done(v.deviceId) else fail(ViewerFailure.Expired)
    catch case _: Exception => fail(ViewerFailure.Expired)

object ViewerApproval:
  enum Phase:
    case Idle
    /** `nA` sent; waiting for the browser to reveal its key. */
    case WaitingReveal
    /** The words to compare, with what the approval grants. */
    case Showing(words: Vector[String], scope: String, ttl: Int, tooManyViewers: Boolean)
    /** Approved; waiting for 「一致する」 in the browser. */
    case Approved(words: Vector[String], scope: String, ttl: Int)
    case Done(deviceId: String, ttl: Int)
    case Failed(reason: ViewerFailure)
