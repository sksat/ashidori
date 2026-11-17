package net.sksat.ashidori.ui

import android.content.{ClipData, ClipDescription, ClipboardManager, DialogInterface, Intent}
import android.hardware.biometrics.{BiometricManager, BiometricPrompt}
import android.os.{CancellationSignal, PersistableBundle}
import android.text.{Editable, InputType, TextWatcher}
import android.view.{View, WindowManager}
import android.widget.{EditText, LinearLayout, Toast}
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.{TextInputEditText, TextInputLayout}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import scala.collection.mutable
import scala.util.Try
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.*
import ashidori.core.sync.{Crypto, PairLink, RecoveryCode, RegistrationEvent, RegistrationState, Username}
import Ui.*

/** Server sync setup, one step at a time from the persisted state:
  * server URL → registration (request pending until the server answered)
  * → encryption key confirmed → syncing. Sync stays off until the key is
  * confirmed, and a confirmed key is never replaced.
  *
  * Two ways in, named on screen so that it is clear which one happens:
  *  - 「新しく登録」: this device creates a new user, under a username chosen
  *    here, with an invite (a link or its QR code) or, where the server
  *    allows it, without one; then it creates the key.
  *  - 「この端末を追加」: this device joins an existing user with a pairing
  *    code; then the recovery code is entered.
  *
  * `link`: a registration link the app was opened with. It, like a link
  * scanned from a QR code here, only fills in the server and the pairing
  * code or invite; the user still checks the server shown and confirms. A
  * link for a server other than the one entered or configured is applied
  * only after a confirmation naming it.
  */
final class SyncSettingsScreen(act: MainActivity, link: Option[PairLink] = None) extends Screen:
  private given Ctx = act
  override val title = "サーバ同期"
  private val net = Executors.newSingleThreadExecutor()
  /** 「ブラウザでも見る」: the opt-in, the browsers that can read, and the approval. */
  private lazy val browsers = BrowserPairingUi(act, () => ())
  private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
  private val body = vbox()
  val view: View = scroll(body)

  /** Bumped by every URL edit and every server query; an answer of an older
    * generation is dropped.
    */
  private var gen = 0
  /** An unpairing request of this screen is in flight: the actions are
    * disabled. A registration in flight is app-wide state
    * ([[Registration.state]]), so a reopened screen sees it too.
    */
  private var busy = false
  /** A key dialog is open; only one at a time. */
  private var keyDialogOpen = false
  /** The URL last entered, kept across re-renders (a refused registration
    * re-renders step 1). Starts empty unless the build sets a server.
    */
  private var lastUrl = SyncSettingsScreen.DefaultServer
  /** A registration link (pairing code or invite) for the server in
    * [[lastUrl]]; dropped when the URL is edited.
    */
  private var fromLink: Option[PairLink] = None
  /** The server for which 「新しく登録」 without an invite was chosen; dropped when the URL is edited. */
  private var newAccountFor: Option[String] = None
  /** The username input of 「新しく登録」, kept across re-renders, and why the server refused it. */
  private var usernameDraft = ""
  private var usernameError: Option[String] = None
  /** [[link]] is still to be applied (on the first show). */
  private var linkPending = link.isDefined
  /** Buttons that start a request, disabled while [[busy]]. */
  private val actions = mutable.ArrayBuffer.empty[View]

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve()
    unobserve = Registration.observe(onRegistration)
    render()
    if linkPending then
      linkPending = false
      link.foreach(openLink)
  override def onHide(): Unit = unobserve()
  override def onDestroy(): Unit =
    unobserve()
    net.shutdown()
    browsers.shutdown()

  /** Outcomes of registration attempts, whichever screen instance started
    * them. A result of an attempt that is no longer the latest (cancelled,
    * or followed by another) only redraws.
    */
  private def onRegistration(e: RegistrationEvent): Unit =
    e match
      case RegistrationEvent.Changed => render()
      case RegistrationEvent.Failed(attempt, msg) =>
        if Registration.isLatest(attempt) then toast(msg)
        render()
      case RegistrationEvent.UsernameTaken(attempt, name) =>
        // Back to the username, with the reason next to it.
        if Registration.isLatest(attempt) then
          usernameDraft = name
          usernameError = Some("このユーザ名はすでに使われています。別のユーザ名を入力してください")
        render()
      case RegistrationEvent.Paired(attempt, newAccount) =>
        render()
        // Self-registered: no other device has a key, so go straight to making one.
        if newAccount && Registration.isLatest(attempt) then createKey()

  private def async[A](work: => A)(done: Try[A] => Unit): Unit =
    net.execute { () =>
      val r = Try(work)
      act.runOnUiThread(() => done(r))
    }

  private def toast(s: String): Unit = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

  /** Debug builds may talk to a local `wrangler dev` over http. */
  private def debuggable = (act.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Fills in step 1 from a registration link (opened or scanned). Never registers by itself. */
  private def openLink(l: PairLink): Unit =
    val s = Registration.state
    if SyncConfig.paired || SyncConfig.unpairing then
      toast("この端末は登録済みです。登録リンクを使うには、先に同期を解除してください")
    else if s.pending.isDefined || s.sending then
      toast("登録の処理中です。登録リンクを使うには、先に現在の登録を中止してください")
    else
      def apply(): Unit =
        lastUrl = l.server
        fromLink = Some(l)
        newAccountFor = None
        usernameError = None
        render()
      // The server the user entered (or a build-time one) or configured.
      val known = Seq(lastUrl, SyncConfig.serverUrl).filter(u => u != null && u.nonEmpty)
        .flatMap(PairLink.normalizeServer(_, allowHttp = true))
      if known.contains(l.server) then apply()
      else
        MaterialAlertDialogBuilder(act)
          .setTitle("登録先のサーバの確認")
          .setMessage(s"この登録リンクは次のサーバへの登録用です。\n\n${l.server}\n\n信頼できるサーバの場合のみ続けてください。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("このサーバを使う", (_, _) => apply())
          .show()

  private def action[B <: View](b: B): B =
    b.setEnabled(!busy)
    actions += b
    b

  private def setBusy(b: Boolean): Unit =
    busy = b
    actions.foreach(_.setEnabled(!b))

  /** The server a step talks to, shown before the step's final action. */
  private def target(url: String): View =
    val t = text(s"登録先: $url", 14f, bold = true)
    t.setPadding(0, dp(8), 0, dp(4))
    t

  private def render(): Unit =
    body.removeAllViews()
    actions.clear()
    body.addView(
      card(
        text("エンドツーエンド暗号化", 16f, bold = true),
        caption("記録は端末で暗号化してから送ります。鍵はあなたの端末だけが持ち、サーバ（Cloudflare）からは中身を読めません。鍵をなくすとサーバ上のデータは復元できないため、復旧コードを必ず控えてください。")
      )
    )
    if !SyncConfig.paired then
      val s = Registration.state
      if s.pending.isDefined || s.sending then body.addView(pendingCard(s))
      else body.addView(registerCard())
    else if SyncConfig.masterKey.isEmpty then body.addView(keyCard())
    else
      body.addView(statusCard())
      body.addView(optionsCard())
      body.addView(devicesCard())
      body.addView(browsers.view())
    body.addView(spacer(24))

  // --- step 1: server and registration ---

  /** Step 1: the server URL first, unless a registration link gave it. What
    * follows depends on the link, or on the server's registration mode
    * (`/v1/info`): with open registration, a choice between 「新しく登録」 and
    * 「この端末を追加」; otherwise (or when the server cannot be asked) the
    * pairing code input, and a note that registering needs an invite.
    */
  private def registerCard(): View =
    val url = EditText(act)
    url.setHint("https://…")
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
    url.setText(if SyncConfig.serverUrl.nonEmpty then SyncConfig.serverUrl else lastUrl)
    val next = vbox()
    def entered = url.getText.toString.trim.stripSuffix("/")
    // The steps below belong to the URL they were shown for.
    url.addTextChangedListener(new TextWatcher:
      def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit =
        gen += 1
        lastUrl = s.toString
        fromLink = None
        newAccountFor = None
        next.removeAllViews()
      def afterTextChanged(s: Editable): Unit = ()
    )
    // From a registration link: what it is for is known, so straight to its step.
    fromLink match
      case Some(l) if l.invite => next.addView(newAccountInput(entered, Some(l.code)))
      case Some(l) => next.addView(pairingCodeInput(entered, Some(l.code)))
      case None if newAccountFor.contains(entered) => next.addView(newAccountInput(entered, None))
      case None => ()
    val check = button("次へ") {
      val u = entered
      if !u.startsWith("https://") && !(debuggable && u.startsWith("http://")) then
        toast("https:// で始まる URL を入力してください")
      else
        gen += 1
        val g = gen
        fromLink = None
        newAccountFor = None
        next.removeAllViews()
        next.addView(caption("サーバに問い合わせています…"))
        async(SyncClient(u, None).registration()) { r =>
          // An answer for an earlier URL (or an earlier query) is dropped.
          if g == gen && entered == u then
            next.removeAllViews()
            // Network errors fall back to the pairing code, which works on every server.
            if r.toOption.contains("open") then next.addView(startChoice(u, next))
            else
              next.addView(pairingCodeInput(u, None))
              next.addView(caption("このサーバに新しく登録するには、サーバの管理者から受け取った招待のリンクを開くか、その QR コードを読み取ります。"))
        }
    }
    // A scanned link goes through the same checks as an opened one.
    val scan = action(outlined("QR コードを読み取る")(PairQrUi.scan(act, allowHttp = debuggable)(openLink)))
    card(
      text("1. 端末を登録", 16f, bold = true),
      caption("招待または登録リンクの QR コードを読み取るか、同期先のサーバの URL を入力します。"),
      scan,
      caption("カメラを使います。映像は端末内で処理し、送信しません。"),
      spacer(8),
      caption("サーバの URL"),
      url,
      check,
      next
    )

  /** Open registration: this device either registers a new user or joins one. */
  private def startChoice(u: String, next: LinearLayout): View =
    vbox(
      target(u),
      action(button("新しく登録") {
        newAccountFor = Some(u)
        usernameError = None
        next.removeAllViews()
        next.addView(newAccountInput(u, None))
      }),
      caption("このサーバに新しく登録し、この端末で暗号化の鍵を作ります。"),
      outlined("この端末を追加") {
        next.removeAllViews()
        next.addView(pairingCodeInput(u, None))
      },
      caption("登録済みの端末で発行したペアリングコードと、復旧コードを入力します。")
    )

  /** 「新しく登録」: the username only, then the account (with `invite`, or
    * without one where the server allows it), then the key.
    */
  private def newAccountInput(u: String, invite: Option[String]): View =
    val name = EditText(act)
    name.setHint("例: hanako_01")
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    name.setFilters(Array(android.text.InputFilter.LengthFilter(20)))
    name.setText(usernameDraft)
    usernameError.foreach(name.setError(_))
    name.addTextChangedListener(new TextWatcher:
      def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = usernameDraft = s.toString
      def afterTextChanged(s: Editable): Unit = ()
    )
    val go = action(button("登録") {
      val raw = name.getText.toString
      Username.problem(raw) match
        case Some(why) => name.setError(why)
        case None =>
          val username = Username.normalize(raw).get
          usernameError = None
          startRegistration(u, "account", invite.getOrElse(""), username)
    })
    vbox(
      spacer(8),
      text("新しく登録", 15f, bold = true),
      caption(if invite.isDefined then "招待のリンクから、このサーバに新しく登録します。登録先を確認してください。"
        else "このサーバに新しく登録します。"),
      caption("ユーザ名"),
      name,
      caption("英小文字・数字・_ の 3〜20 文字。ほかの利用者に表示されます。"),
      target(u),
      go
    )

  /** 「この端末を追加」. `fromLink`: the code of a registration link for this server, filled in. */
  private def pairingCodeInput(u: String, fromLink: Option[String]): View =
    val code = EditText(act)
    code.setHint("ペアリングコード（8文字）")
    code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    fromLink.foreach(code.setText(_))
    val go = action(button("この端末を追加") {
      val c = code.getText.toString.trim.toUpperCase
      if c.isEmpty then toast("ペアリングコードを入力してください")
      else startRegistration(u, "pair", c)
    })
    vbox(
      spacer(8),
      text("この端末を追加", 15f, bold = true),
      caption(if fromLink.isDefined then "登録リンクからサーバとペアリングコードを入力しました。登録先を確認してから追加してください。"
        else "登録済みの別の端末、またはサーバの管理者が発行したペアリングコードを入力します。"),
      code,
      target(u),
      go
    )

  /** Stores the request with a new device token, then sends it. One at a
    * time, app-wide; the outcome arrives through [[onRegistration]].
    */
  private def startRegistration(u: String, kind: String, code: String, username: String = ""): Unit =
    if !busy then Registration.start(u, kind, code, SyncScheduler.deviceName, username)

  /** A registration being sent, or sent but not answered: wait, retry it
    * with the same token, or give it up. Giving up also drops the answer of
    * an attempt still in flight, so it is never disabled.
    */
  private def pendingCard(s: RegistrationState): View =
    val retry = button("再送信")(Registration.retry())
    retry.setEnabled(!s.sending && !busy)
    val cancel = outlined("中止") {
      if !Registration.cancel() then toast("サーバの応答を保存中のため、中止できません")
    }
    val head = Seq(
      text("1. 端末を登録", 16f, bold = true),
      caption(if s.sending then "サーバに登録しています…"
        else "登録の応答を受け取れていません。同じ内容でもう一度送ると、サーバで登録済みならその登録を使います。")
    )
    val detail = s.pending.toSeq.flatMap { p =>
      val what =
        if p.kind != "account" then s"この端末を追加（ペアリングコード ${p.code}）"
        else
          val how = if p.code.nonEmpty then "招待で新しく登録" else "新しく登録"
          if p.username.nonEmpty then s"$how（ユーザ名 ${p.username}）" else how
      Seq(target(p.url), caption(what))
    }
    card((head ++ detail ++ Seq(retry, cancel))*)



  // --- step 2: the encryption key ---

  private def keyCard(): View =
    val head = Seq(text("2. 暗号化の鍵", 16f, bold = true), target(SyncConfig.serverUrl))
    if SyncConfig.newAccount then
      // Self-registered: no other device has a key, so only a new one makes sense.
      card((head ++ Seq(
        caption("鍵を新しく作成し、表示される復旧コードを控えます。"),
        action(button("新しい鍵を作る")(createKey()))
      ))*)
    else
      card((head ++ Seq(
        caption("最初の端末なら鍵を新しく作ります。2台目以降は、最初の端末で控えた復旧コードを入力します。"),
        action(button("新しい鍵を作る（最初の端末）")(createKey())),
        action(outlined("復旧コードを入力（2台目以降）")(enterRecoveryCode()))
      ))*)

  /** Keeps the dialog's content out of screenshots and the recent-apps view while it is open. */
  private def secure(d: AlertDialog, onDismiss: () => Unit = () => ()): Unit =
    d.getWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    act.getWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    d.setOnDismissListener { _ =>
      act.getWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      onDismiss()
    }

  /** Copies the recovery code, marked sensitive so the system does not preview it. */
  private def copySecret(code: String): Unit =
    val clip = ClipData.newPlainText("ashidori recovery code", code)
    val extras = PersistableBundle()
    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    clip.getDescription.setExtras(extras)
    act.getSystemService(classOf[ClipboardManager]).setPrimaryClip(clip)
    toast("コピーしました。保存後はクリップボードを消去してください")

  /** Sets the key for the registration the dialog was opened for. */
  /** The key is written off the main thread; sync is enabled only once the
    * write is confirmed on disk.
    */
  private def confirm(key: Array[Byte], reg: String): Unit =
    setBusy(true)
    async(SyncConfig.confirmKey(key, reg)) { r =>
      setBusy(false)
      r match
        case scala.util.Success(true) => SyncScheduler.schedule(act)
        case scala.util.Success(false) => toast("鍵はすでに設定されているか、登録が変わりました。鍵は変更していません")
        case scala.util.Failure(e) => toast(s"鍵を保存できませんでした。同期は未開始: ${e.getMessage}")
      render()
    }

  private def createKey(): Unit =
    if !keyDialogOpen && !busy && SyncConfig.paired && SyncConfig.masterKey.isEmpty then
      keyDialogOpen = true
      val reg = SyncConfig.registration
      val key = Crypto.newMasterKey()
      val code = RecoveryCode.encode(key)
      val groups = code.split('-')
      val tail = groups.last
      // The code is 55 characters, so the last group is shorter than the
      // others; show the shape with that group blank instead of naming a length.
      val shape = (groups.init.map(_ => "XXXX") :+ ("_" * tail.length)).mkString("-")
      val check = EditText(act)
      check.setHint(s"最後の区切り（${tail.length}文字）")
      check.setFilters(Array(android.text.InputFilter.LengthFilter(tail.length)))
      check.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
      check.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
      val d = MaterialAlertDialogBuilder(act)
        .setTitle("復旧コードを控えてください")
        .setMessage(s"$code\n\n別の端末の追加や、この端末をなくした場合の復元に必要です。パスワードマネージャーなどに保存してください。\n\n確認のため、最後の区切り（${tail.length}文字）を入力してください。\n$shape")
        .setView(padded(vbox(check), 20, 0))
        .setCancelable(false)
        .setNeutralButton("コピー", null)
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("控えました", null)
        .create()
      secure(d, () => keyDialogOpen = false)
      d.setOnShowListener { _ =>
        d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(_ => copySecret(code))
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ =>
          if check.getText.toString.trim.equalsIgnoreCase(groups.last) then
            d.dismiss()
            confirm(key, reg)
          else check.setError("一致しません")
        }
      }
      d.show()

  private def enterRecoveryCode(): Unit =
    if !keyDialogOpen && !busy && SyncConfig.paired && SyncConfig.masterKey.isEmpty then
      keyDialogOpen = true
      val reg = SyncConfig.registration
      // Hidden, kept out of suggestions and autofill; the eye icon shows it.
      val layout = TextInputLayout(act)
      layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE)
      val input = TextInputEditText(layout.getContext)
      input.setHint("XXXX-XXXX-...")
      input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
      input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
      layout.addView(input)
      val d = MaterialAlertDialogBuilder(act)
        .setTitle("復旧コードを入力")
        .setView(padded(vbox(layout), 20, 0))
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("設定", null)
        .create()
      secure(d, () => keyDialogOpen = false)
      d.setOnShowListener { _ =>
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ =>
          RecoveryCode.decode(input.getText.toString) match
            case Right(k) =>
              d.dismiss()
              confirm(k, reg)
            case Left(err) => layout.setError(err)
        }
      }
      d.show()

  /** Runs `f` once the user proved they can unlock the device (biometrics
    * or the screen lock). Without a screen lock there is nothing to ask.
    */
  private def withDeviceCredential(f: => Unit): Unit =
    val km = act.getSystemService(classOf[android.app.KeyguardManager])
    if !km.isDeviceSecure then f
    else
      BiometricPrompt.Builder(act)
        .setTitle("復旧コードを表示")
        .setSubtitle("本人確認のため、画面ロックを解除してください")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
        .authenticate(CancellationSignal(), act.getMainExecutor, new BiometricPrompt.AuthenticationCallback:
          override def onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult): Unit = f
          override def onAuthenticationError(code: Int, msg: CharSequence): Unit =
            if code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED then
              toast(msg.toString)
        )

  private def showRecoveryCode(): Unit =
    SyncConfig.masterKey.foreach { k =>
      val code = RecoveryCode.encode(k)
      val d = MaterialAlertDialogBuilder(act)
        .setTitle("復旧コード")
        .setMessage(code)
        .setNeutralButton("コピー", null)
        .setPositiveButton("閉じる", null)
        .create()
      secure(d)
      d.setOnShowListener(_ => d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(_ => copySecret(code)))
      d.show()
    }

  // --- syncing ---

  private def statusCard(): View =
    val last = SyncConfig.lastSync
    val err = SyncConfig.lastError
    // The username as last seen, then as the server has it now (an admin may set or change it).
    val who = caption("")
    def showWho(name: String): Unit =
      who.setText(if name.nonEmpty then s"ユーザ名 $name" else "ユーザ名 未設定（サーバの管理者が設定します）")
    if SyncConfig.username.nonEmpty then showWho(SyncConfig.username) else who.setVisibility(View.GONE)
    async(SyncClient(SyncConfig.serverUrl, SyncConfig.token).me()) {
      case scala.util.Success((true, name)) =>
        SyncConfig.username = name.getOrElse("")
        showWho(name.getOrElse(""))
        who.setVisibility(View.VISIBLE)
      // A device registered before users existed has no user, so no username.
      case scala.util.Success((false, _)) => who.setVisibility(View.GONE)
      case scala.util.Failure(_) => ()
    }
    card(
      text("● 同期中", 16f, bold = true, colorAttr = attr.primary),
      who,
      caption(SyncConfig.serverUrl),
      caption(if last == 0 then "まだ同期していません" else s"最終同期 ${Instant.ofEpochMilli(last).atZone(AshidoriApp.zone).format(fmt)}"),
      (if err.nonEmpty then text(s"前回のエラー: $err", 13f, colorAttr = attr.error) else spacer(0)),
      action(button("今すぐ同期", tonal = true) {
        SyncScheduler.syncNow(act)
        toast("同期を予約しました（ネットワーク接続時に開始）")
      })
    )

  private def optionsCard(): View =
    def sw(label: String, value: Boolean)(set: Boolean => Unit): MaterialSwitch =
      val s = MaterialSwitch(act)
      s.setText(label)
      s.setChecked(value)
      s.setMinHeight(dp(56))
      s.setOnCheckedChangeListener((_, b) => { set(b); SyncScheduler.schedule(act) })
      s
    card(
      text("通信", 16f, bold = true),
      sw("モバイル回線でも同期する", SyncConfig.mobileData)(SyncConfig.mobileData = _),
      caption("位置・歩数・チェックインなどの小さなデータ。オフにすると Wi-Fi のときだけ送ります。"),
      sw("生の GNSS ログは充電中だけ送る", SyncConfig.rawOnlyWhileCharging)(SyncConfig.rawOnlyWhileCharging = _),
      caption("生ログは大きいので、常に Wi-Fi など従量制でない回線のときだけ送ります。")
    )

  private def devicesCard(): View =
    val list = vbox(caption("読み込み中…"))
    val client = SyncClient(SyncConfig.serverUrl, SyncConfig.token)
    val reg = SyncConfig.registration
    def load(): Unit =
      async(client.request("GET", "/v1/devices").json) { r =>
        list.removeAllViews()
        r.fold(
          e => list.addView(caption(s"取得できませんでした: ${e.getMessage}")),
          js =>
            // Same list as the 端末 section of the ステータス tab.
            PeerStatus.saveDevices(js, reg)
            // Browsers are listed in their own card (BrowserPairingUi).
            for d <- js.arr if !d.obj.get("kind").flatMap(_.strOpt).contains("viewer") do
              val id = d("id").str
              val revoked = d.obj.get("revoked").exists(_.bool)
              val me = id == SyncConfig.deviceId
              val name = text(d("name").str + (if me then "（この端末）" else "") + (if revoked then "・登録取り消し済み" else ""), 15f)
              name.setLayoutParams(weighted())
              val row = hbox(name)
              if !revoked && !me then
                row.addView(outlined("登録を取り消す") {
                  MaterialAlertDialogBuilder(act)
                    .setTitle("この端末を失効しますか")
                    .setMessage("失効した端末はサーバにアクセスできなくなります。端末をなくした場合は、あわせて鍵の作り直しも検討してください。")
                    .setNegativeButton("やめる", null)
                    .setPositiveButton("失効", (_, _) => async(client.request("POST", s"/v1/devices/$id/revoke", "{}".getBytes))(_ => load()))
                    .show()
                })
              row.setMinimumHeight(dp(48))
              list.addView(row)
        )
      }
    load()
    card(
      text("端末", 16f, bold = true),
      list,
      button("別の端末を追加", tonal = true) {
        async(client.createPairing()) {
          _.fold(e => toast(e.getMessage), (code, _) => showPairing(code))
        }
      },
      outlined("復旧コードを表示")(withDeviceCredential(showRecoveryCode())),
      action(outlined("この端末の同期を解除") {
        MaterialAlertDialogBuilder(act)
          .setTitle("同期を解除しますか")
          .setMessage("この端末から鍵と登録情報、同期の進み具合を消します。端末内の記録は残ります。もう一度登録すると、記録をすべて送り直します。")
          .setNegativeButton("やめる", null)
          .setPositiveButton("解除", (_, _) => unpair())
          .show()
      })
    )

  /** A new pairing code and its registration link, also as a QR code. The
    * link carries the server and the code only, never the recovery code.
    */
  private def showPairing(code: String): Unit =
    val link = PairLink.url(SyncConfig.serverUrl, code)
    val codeView = text(code.take(4) + "-" + code.drop(4), 28f, bold = true)
    codeView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    codeView.setTextIsSelectable(true)
    val linkView = text(link, 14f)
    linkView.setTextIsSelectable(true)
    val d = MaterialAlertDialogBuilder(act)
      .setTitle("別の端末を追加")
      .setView(scroll(padded(vbox(
        caption("新しい端末の「QR コードを読み取る」でこの QR コードを読み取るか、登録リンクを開きます。サーバの URL とペアリングコードを入力しても登録できます。"),
        spacer(8),
        PairQrUi.image(link),
        spacer(8),
        caption("登録リンク"),
        linkView,
        spacer(12),
        caption("ペアリングコード（10分間有効、1回のみ）"),
        codeView,
        spacer(12),
        caption("登録後、新しい端末に復旧コードを入力します。復旧コードは QR コードと登録リンクに含まれません。")
      ), 24, 8)))
      .setNeutralButton("コピー", null)
      .setNegativeButton("共有", null)
      .setPositiveButton("閉じる", null)
      .create()
    d.setOnShowListener { _ =>
      d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { _ =>
        // Marked sensitive: the code admits a device to this user for 10 minutes.
        val clip = ClipData.newPlainText("ashidori registration link", link)
        val extras = PersistableBundle()
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        clip.getDescription.setExtras(extras)
        act.getSystemService(classOf[ClipboardManager]).setPrimaryClip(clip)
        toast("登録リンクをコピーしました")
      }
      d.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { _ =>
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        act.startActivity(Intent.createChooser(send, "登録リンクを共有"))
      }
    }
    d.show()

  /** Stops and waits for running sync work, then clears the registration. */
  private def unpair(): Unit =
    if !busy then
      setBusy(true)
      toast("同期を停止中…")
      async(SyncConfig.forget(act)) { r =>
        setBusy(false)
        r.failed.foreach(e => toast(e.getMessage))
        SyncScheduler.schedule(act)
        render()
      }

object SyncSettingsScreen:
  /** The server the URL field starts with: empty unless the build sets the
    * Gradle property `ashidori.defaultServer` (a personal build).
    */
  val DefaultServer: String = BuildConfig.DEFAULT_SERVER_URL
