package net.sksat.ashidori.ui

import android.content.{ClipData, ClipDescription, ClipboardManager, DialogInterface}
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
import ashidori.core.sync.{Crypto, RecoveryCode}
import Ui.*

/** Server sync setup, one step at a time from the persisted state:
  * server URL → registration (request pending until the server answered)
  * → encryption key confirmed → syncing. Sync stays off until the key is
  * confirmed, and a confirmed key is never replaced.
  */
final class SyncSettingsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "同期"
  private val net = Executors.newSingleThreadExecutor()
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
  /** The URL last entered, kept across re-renders (a refused registration re-renders step 1). */
  private var lastUrl = ""
  /** Buttons that start a request, disabled while [[busy]]. */
  private val actions = mutable.ArrayBuffer.empty[View]

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve()
    unobserve = Registration.observe(onRegistration)
    render()
  override def onHide(): Unit = unobserve()
  override def onDestroy(): Unit =
    unobserve()
    net.shutdown()

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
    body.addView(spacer(24))

  // --- step 1: server and registration ---

  /** Step 1: the server URL first. What follows depends on the server's
    * registration mode (`/v1/info`): with open registration, a choice between
    * creating an account and joining from another device; otherwise (or when
    * the server cannot be asked) the pairing code input.
    */
  private def registerCard(): View =
    val url = EditText(act)
    url.setHint("サーバの URL（https://...）")
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
        next.removeAllViews()
      def afterTextChanged(s: Editable): Unit = ()
    )
    val check = button("次へ") {
      val u = entered
      // Debug builds may talk to a local `wrangler dev` over http.
      val debuggable = (act.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
      if !u.startsWith("https://") && !(debuggable && u.startsWith("http://")) then
        toast("https:// で始まる URL を入力してください")
      else
        gen += 1
        val g = gen
        next.removeAllViews()
        next.addView(caption("サーバに問い合わせています…"))
        async(SyncClient(u, None).registration()) { r =>
          // An answer for an earlier URL (or an earlier query) is dropped.
          if g == gen && entered == u then
            next.removeAllViews()
            // Network errors fall back to the pairing code, which works on every server.
            if r.toOption.contains("open") then next.addView(startChoice(u, next))
            else next.addView(pairingCodeInput(u))
        }
    }
    card(
      text("1. 端末を登録", 16f, bold = true),
      caption("同期先のサーバの URL を入力します。"),
      url,
      check,
      next
    )

  /** Open registration: this device is either the user's first or joins one. */
  private def startChoice(u: String, next: LinearLayout): View =
    vbox(
      target(u),
      action(button("新しく始める（この端末が最初）")(startRegistration(u, "account", ""))),
      caption("このサーバに新しく登録し、暗号化の鍵を作成します。"),
      outlined("別の端末から追加") {
        next.removeAllViews()
        next.addView(pairingCodeInput(u))
      },
      caption("登録済みの端末で発行したペアリングコードと、復旧コードを入力します。")
    )

  private def pairingCodeInput(u: String): View =
    val code = EditText(act)
    code.setHint("ペアリングコード（8文字）")
    code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    val go = action(button("この端末を登録") {
      val c = code.getText.toString.trim.toUpperCase
      if c.isEmpty then toast("ペアリングコードを入力してください")
      else startRegistration(u, "pair", c)
    })
    vbox(
      spacer(8),
      caption("サーバの管理ページ、または登録済みの別の端末でペアリングコードを発行して入力します。"),
      code,
      target(u),
      go
    )

  /** Stores the request with a new device token, then sends it. One at a
    * time, app-wide; the outcome arrives through [[onRegistration]].
    */
  private def startRegistration(u: String, kind: String, code: String): Unit =
    if !busy then Registration.start(u, kind, code, SyncScheduler.deviceName)

  /** A registration being sent, or sent but not answered: wait, retry it
    * with the same token, or give it up. Giving up also drops the answer of
    * an attempt still in flight, so it is never disabled.
    */
  private def pendingCard(s: RegistrationState): View =
    val retry = button("もう一度送る")(Registration.retry())
    retry.setEnabled(!s.sending && !busy)
    val cancel = outlined("やめる") {
      if !Registration.cancel() then toast("サーバの応答を保存しているため、やめられませんでした")
    }
    val head = Seq(
      text("1. 端末を登録", 16f, bold = true),
      caption(if s.sending then "サーバに登録しています…"
        else "登録の応答を受け取れていません。同じ内容でもう一度送ると、サーバで登録済みならその登録を使います。")
    )
    val detail = s.pending.toSeq.flatMap { p =>
      Seq(target(p.url), caption(if p.kind == "account" then "新しく始める（この端末が最初）" else s"ペアリングコード ${p.code}"))
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
    toast("コピーしました。保存したらクリップボードを消してください")

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
        case scala.util.Failure(e) => toast(s"鍵を保存できませんでした。同期は始めていません: ${e.getMessage}")
      render()
    }

  private def createKey(): Unit =
    if !keyDialogOpen && !busy && SyncConfig.paired && SyncConfig.masterKey.isEmpty then
      keyDialogOpen = true
      val reg = SyncConfig.registration
      val key = Crypto.newMasterKey()
      val code = RecoveryCode.encode(key)
      val groups = code.split('-')
      val check = EditText(act)
      check.setHint(s"最後の4文字（${groups.length}番目のブロック）")
      check.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
      check.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO)
      val d = MaterialAlertDialogBuilder(act)
        .setTitle("復旧コードを控えてください")
        .setMessage(s"$code\n\n別の端末の追加や、この端末をなくしたときの復元に必要です。パスワードマネージャーなどに保存してください。確認のため、最後のブロックを入力してください。")
        .setView(padded(vbox(check), 20, 0))
        .setCancelable(false)
        .setNeutralButton("コピー", null)
        .setNegativeButton("やめる", null)
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
        .setNegativeButton("やめる", null)
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
    card(
      text("● 同期中", 16f, bold = true, colorAttr = attr.primary),
      caption(SyncConfig.serverUrl),
      caption(if last == 0 then "まだ同期していません" else s"最終同期 ${Instant.ofEpochMilli(last).atZone(AshidoriApp.zone).format(fmt)}"),
      (if err.nonEmpty then text(s"前回のエラー: $err", 13f, colorAttr = attr.error) else spacer(0)),
      action(button("今すぐ同期", tonal = true) {
        SyncScheduler.syncNow(act)
        toast("同期を予約しました（ネットワークにつながると始まります）")
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
            for d <- js.arr do
              val id = d("id").str
              val revoked = d.obj.get("revoked").exists(_.bool)
              val me = id == SyncConfig.deviceId
              val name = text(d("name").str + (if me then "（この端末）" else "") + (if revoked then "・失効" else ""), 15f)
              name.setLayoutParams(weighted())
              val row = hbox(name)
              if !revoked && !me then
                row.addView(outlined("失効") {
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
          _.fold(e => toast(e.getMessage), { (code, exp) =>
            MaterialAlertDialogBuilder(act)
              .setTitle("ペアリングコード")
              .setMessage(s"$code\n\n新しい端末の ashidori で、このサーバの URL と一緒に入力してください（10分間有効）。そのあと、新しい端末に復旧コードを入力します。")
              .setPositiveButton("閉じる", null)
              .show()
          })
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

  /** Stops and waits for running sync work, then clears the registration. */
  private def unpair(): Unit =
    if !busy then
      setBusy(true)
      toast("同期を止めています…")
      async(SyncConfig.forget(act)) { r =>
        setBusy(false)
        r.failed.foreach(e => toast(e.getMessage))
        SyncScheduler.schedule(act)
        render()
      }
