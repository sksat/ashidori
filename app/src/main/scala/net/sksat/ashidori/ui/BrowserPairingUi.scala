package net.sksat.ashidori.ui

import android.os.{Handler, Looper, SystemClock}
import android.text.InputType
import android.view.{Gravity, View, WindowManager}
import android.widget.{EditText, LinearLayout, RadioButton, RadioGroup}
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import scala.util.Try
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.*
import ashidori.core.sync.{ViewerApproval, ViewerFailure, ViewerQr, ViewerPairing as VP}
import ashidori.core.sync.ViewerApproval.Phase
import Ui.*

/** 「ブラウザでも見る」 in the sync settings (docs/design/browser-pairing-v1.md):
  * the opt-in with its consequence, the browsers that can read now (with
  * 「終了」), and the approval: the QR code the browser shows, scanned (or
  * its code, typed) → the four words, what the browser can read and until
  * when → 「承認」 or 「一致しない」.
  *
  * The browser receives the batch key only. `sA` and the sealed grant live
  * in memory, in the [[ViewerApproval]] of the open dialog; closing the
  * dialog aborts an unfinished attempt.
  */
final class BrowserPairingUi(act: MainActivity, onChanged: () => Unit):
  private given Ctx = act
  private val net = Executors.newSingleThreadExecutor()
  private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")

  private def async[A](work: => A)(done: Try[A] => Unit): Unit =
    net.execute { () =>
      val r = Try(work)
      act.runOnUiThread(() => done(r))
    }

  def shutdown(): Unit = net.shutdown()

  private def client = SyncClient(SyncConfig.serverUrl, SyncConfig.token)

  private def ttlLabel(ttl: Int): String = if ttl >= 86400 then "24時間" else "1時間"

  /** The 「詳しく」 of the opt-in: what is weaker than reading in the app only. */
  private def details(): View =
    val host = Try(java.net.URI(SyncConfig.serverUrl).getHost).toOption.flatMap(Option(_)).getOrElse(SyncConfig.serverUrl)
    val v = vbox(
      caption(s"・閲覧ページは $host が配信します。サーバの管理者がページを改変すると、記録を読まれる可能性があります。"),
      caption("・ブラウザは、これまでと今後の記録をすべて読めます。期限後はサーバが記録を提供しませんが、持ち出された鍵では期限後も読めます。"),
      caption("・ブラウザは記録を書いた端末を確かめられません。鍵とサーバを悪用されると、ブラウザに偽の記録を見せられる可能性があります。承認の後にまだ同期していない端末のアプリも、偽の記録を受け付ける可能性があります。"),
      caption("・復旧コードとアカウントの鍵はブラウザに渡しません。"),
      caption("・ブラウザの登録は、この画面、ブラウザ自身、サーバの管理者がいつでも終了できます。"),
    )
    v.setVisibility(View.GONE)
    v

  /** Fills `list` with the active browsers, each with 「終了」; `ended` runs after one was ended. */
  private def loadViewers(list: LinearLayout, ended: () => Unit): Unit =
    async(client.devices()) { r =>
      list.removeAllViews()
      r.fold(
        e => list.addView(caption(s"取得できませんでした: ${e.getMessage}")),
        js =>
          val vs = Viewers.active(js, System.currentTimeMillis())
          if vs.isEmpty then list.addView(caption("記録を読めるブラウザはありません。"))
          for v <- vs do
            val until = Instant.ofEpochMilli(v.expiresAt).atZone(AshidoriApp.zone).format(fmt)
            val label = vbox(text("ブラウザ", 15f), caption(s"$until まで"))
            label.setLayoutParams(weighted())
            val row = hbox(label, outlined("終了") {
              MaterialAlertDialogBuilder(act)
                .setTitle("このブラウザの閲覧を終了しますか")
                .setMessage("ブラウザはサーバから記録を取得できなくなります。すでに取得した記録と、持ち出された鍵は消せません。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("終了する", (_, _) =>
                  async(client.request("POST", s"/v1/devices/${v.id}/revoke", "{}".getBytes)) { _ => ended() })
                .show()
            })
            row.setMinimumHeight(dp(48))
            list.addView(row)
      )
    }

  /** The card: the opt-in, the active browsers, and 「ブラウザでも見る」. */
  def view(): View =
    val more = details()
    val moreBtn = outlined("詳しく") {
      more.setVisibility(if more.getVisibility == View.VISIBLE then View.GONE else View.VISIBLE)
    }
    val list = vbox(caption("読み込み中…"))
    def load(): Unit = loadViewers(list, () => { load(); onChanged() })
    load()
    card(
      text("ブラウザでも見る", 16f, bold = true),
      hbox(
        { val t = caption("アプリだけで見る場合より、保護は弱くなります。"); t.setLayoutParams(weighted()); t },
        moreBtn,
      ),
      more,
      spacer(8),
      list,
      button("ブラウザでも見る", tonal = true)(begin(() => { load(); onChanged() })),
    )

  // ---- the approval ----

  private def debuggable: Boolean =
    (act.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Step 1: the expiry, then the QR code the browser shows (or its code, typed). */
  private def begin(done: () => Unit): Unit =
    // Blob authentication must be in force before any browser gets the batch key.
    val wait = MaterialAlertDialogBuilder(act).setTitle("ブラウザでも見る").setMessage("ほかの端末の状態を確認中…").setCancelable(false).show()
    async(ViewerGate.check()) { r =>
      wait.dismiss()
      r.fold(e => Left(String.valueOf(e.getMessage)), identity) match
        case Left(msg) =>
          MaterialAlertDialogBuilder(act).setTitle("ブラウザでも見る").setMessage(msg).setPositiveButton("閉じる", null).show()
        case Right(()) => chooseWay(done)
    }

  /** The expiry, and how to take the browser's attempt: 「QR コードを読み取る」
    * (primary) or 「コードを入力」 (for a phone that cannot scan).
    */
  private def chooseWay(done: () => Unit): Unit =
    val ttl = RadioGroup(act)
    val r1 = RadioButton(act); r1.setText("1時間"); r1.setId(View.generateViewId())
    val r24 = RadioButton(act); r24.setText("24時間"); r24.setId(View.generateViewId())
    ttl.addView(r1); ttl.addView(r24)
    ttl.check(r1.getId)
    def ttlSeconds = if ttl.getCheckedRadioButtonId == r24.getId then 86400 else 3600
    var d: Option[AlertDialog] = None
    val scan = button("QR コードを読み取る") {
      d.foreach(_.dismiss())
      val t = ttlSeconds
      PairQrUi.scanFor(act, "ブラウザに表示された QR コードを写してください。", "ブラウザの登録の QR コードではありません。")(
        ViewerQr.parse(_, allowHttp = debuggable))(qr => scanned(qr, t, done))
    }
    val typed = outlined("コードを入力") {
      d.foreach(_.dismiss())
      askCode(ttlSeconds, done)
    }
    for b <- Seq(scan, typed) do
      b.setLayoutParams(LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    val content = vbox(
      caption("ブラウザに表示された QR コードを読み取ってください"),
      spacer(8),
      text("期限", 14f, bold = true),
      ttl,
      spacer(8),
      scan,
      typed,
    )
    d = Some(MaterialAlertDialogBuilder(act)
      .setTitle("ブラウザでも見る")
      .setView(scroll(padded(content, 24, 8)))
      .setNegativeButton("キャンセル", null)
      .show())

  /** A scanned QR code: only of the server this app syncs with. */
  private def scanned(qr: ViewerQr, ttl: Int, done: () => Unit): Unit =
    if ViewerQr.sameServer(qr, SyncConfig.serverUrl, allowHttp = debuggable) then run(Right(qr), ttl, done)
    else
      val host = Try(java.net.URI(qr.server).getHost).toOption.flatMap(Option(_)).getOrElse(qr.server)
      MaterialAlertDialogBuilder(act)
        .setTitle("ブラウザでも見る")
        .setMessage(s"この QR コードは $host のページのもので、同期しているサーバのものではありません。同期しているサーバのページで表示した QR コードを読み取ってください。")
        .setPositiveButton("閉じる", null)
        .show()

  /** The fallback: the code the browser shows, typed. */
  private def askCode(ttl: Int, done: () => Unit): Unit =
        val code = EditText(act)
        code.setHint("ブラウザに表示されたコード")
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        code.setTypeface(android.graphics.Typeface.MONOSPACE)
        val err = text("", 13f, colorAttr = attr.error)
        err.setVisibility(View.GONE)
        val content = vbox(
          code,
          err,
        )
        val d = MaterialAlertDialogBuilder(act)
          .setTitle("ブラウザでも見る")
          .setView(padded(content, 24, 8))
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("次へ", null)
          .create()
        d.setOnShowListener { _ =>
          d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ =>
            VP.normalizeCode(code.getText.toString) match
              case None =>
                err.setText("8 文字の英数字を入力してください。")
                err.setVisibility(View.VISIBLE)
              case Some(c) =>
                d.dismiss()
                run(Left(c), ttl, done)
          }
        }
        d.show()

  /** Steps 2 and 3: waits for the browser, shows the words, approves. `from`
    * is the code typed (Left) or the QR code scanned (Right), whose id and
    * commit the attempt checks against the server's answer.
    */
  private def run(from: Either[String, ViewerQr], ttl: Int, done: () => Unit): Unit =
    val code = from.fold(identity, _.code)
    val attempt = ViewerApproval(ViewerPairingClient(client), () => ViewerGate.batchKey(), () => SystemClock.elapsedRealtime())
    val body = vbox()
    val timer = caption("")
    val d = MaterialAlertDialogBuilder(act)
      .setTitle("ブラウザでも見る")
      .setView(scroll(padded(vbox(body, spacer(8), timer), 24, 8)))
      .setCancelable(false)
      .create()
    // The words and the code are shown in a dialog: keep them out of screenshots and the recents.
    d.getWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    // Timers of this dialog only.
    val main = Handler(Looper.getMainLooper)
    var closed = false
    var inFlight = false
    var shown: Phase = Phase.Idle
    def close(): Unit =
      if !closed then
        closed = true
        main.removeCallbacksAndMessages(null)
        d.dismiss()
        // Leaving an unfinished attempt aborts it (best effort).
        net.execute(() => attempt.cancel())
        done()

    def step(work: => Phase): Unit =
      if !inFlight && !closed then
        inFlight = true
        async(work) { r =>
          inFlight = false
          val p = r.getOrElse(attempt.current)
          // Polls that change nothing leave the screen as it is.
          if !closed && (p != shown || r.isFailure) then show(p, r.failed.toOption)
        }

    def tick(): Unit =
      if !closed then
        val left = attempt.remainingMs
        timer.setText(if left > 0 then f"残り ${left / 60000}%d:${left / 1000 % 60}%02d" else "")
        main.postDelayed(() => tick(), 1000)

    def poll(): Unit =
      if !closed then
        attempt.current match
          // While the words are shown too: the browser may have stopped.
          case Phase.WaitingReveal | Phase.Showing(_, _, _, _) | Phase.Approved(_, _, _) => step(attempt.poll())
          case _ => ()
        main.postDelayed(() => poll(), VP.PollMs)

    def wordsView(words: Vector[String]): View =
      val v = vbox()
      for (w, i) <- words.zipWithIndex do
        val t = text(s"${i + 1}  $w", 26f, bold = true)
        t.setPadding(0, dp(4), 0, dp(4))
        v.addView(t)
      v

    def setButtons(neg: Option[(String, () => Unit)], pos: Option[(String, () => Unit)]): Unit =
      for (which, b) <- Seq(android.content.DialogInterface.BUTTON_NEGATIVE -> neg, android.content.DialogInterface.BUTTON_POSITIVE -> pos) do
        val btn = d.getButton(which)
        if btn != null then
          b match
            case Some((label, f)) =>
              btn.setText(label)
              btn.setVisibility(View.VISIBLE)
              btn.setOnClickListener(_ => f())
            case None => btn.setVisibility(View.GONE)

    def show(p: Phase, error: Option[Throwable]): Unit =
      shown = p
      body.removeAllViews()
      p match
        case Phase.Idle | Phase.WaitingReveal =>
          body.addView(text("ブラウザの応答待ち", 16f, bold = true))
          body.addView(caption(s"コード ${VP.displayCode(code)}"))
          error.foreach(e => body.addView(caption(s"通信できませんでした。再試行します（${e.getMessage}）")))
          setButtons(Some("キャンセル" -> (() => close())), None)
        case Phase.Showing(words, _, t, tooMany) =>
          body.addView(caption("ブラウザに表示された 4 語と一致するか確認してください。"))
          body.addView(wordsView(words))
          body.addView(spacer(8))
          body.addView(text("渡すもの", 14f, bold = true))
          body.addView(caption("記録を読むための鍵（これまでと今後の記録）"))
          body.addView(text("期限", 14f, bold = true))
          body.addView(caption(ttlLabel(t)))
          body.addView(spacer(8))
          body.addView(text("このブラウザは、これまでと今後の記録を読めます。持ち出された鍵では期限後も読めます。", 13f))
          if tooMany then
            body.addView(text(s"記録を読めるブラウザが上限（${VP.MaxViewers} 台）です。ほかのブラウザを終了してから、再度承認してください。", 13f, colorAttr = attr.error))
            val others = vbox(caption("読み込み中…"))
            def reload(): Unit = loadViewers(others, () => { reload(); onChanged() })
            reload()
            body.addView(others)
          error.foreach(e => body.addView(caption(s"通信できませんでした（${e.getMessage}）。再度承認してください。")))
          setButtons(Some("一致しない" -> (() => step(attempt.mismatch()))), Some("承認" -> (() => step(attempt.approve()))))
        case Phase.Approved(words, _, _) =>
          body.addView(text("ブラウザで「一致する」を押してください", 16f, bold = true))
          body.addView(wordsView(words))
          setButtons(Some("キャンセル" -> (() => close())), None)
        case Phase.Done(_, t) =>
          main.removeCallbacksAndMessages(null)
          timer.setText("")
          body.addView(text("ブラウザでの閲覧を承認しました", 16f, bold = true))
          body.addView(caption(s"期限: ${ttlLabel(t)}"))
          setButtons(None, Some("閉じる" -> (() => close())))
        case Phase.Failed(reason) =>
          main.removeCallbacksAndMessages(null)
          timer.setText("")
          body.addView(text(failure(reason), 15f))
          setButtons(None, Some("閉じる" -> (() => close())))

    d.setOnShowListener { _ =>
      show(Phase.Idle, None)
      step(from.fold(attempt.start(_, ttl), attempt.startQr(_, ttl)))
      tick()
      main.postDelayed(() => poll(), VP.PollMs)
    }
    // Placeholders, relabelled per phase in setButtons.
    d.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "キャンセル", null: android.content.DialogInterface.OnClickListener)
    d.setButton(android.content.DialogInterface.BUTTON_POSITIVE, "承認", null: android.content.DialogInterface.OnClickListener)
    d.show()

  private def failure(f: ViewerFailure): String = f match
    case ViewerFailure.InvalidCode => "コードの形式が正しくありません。8 文字の英数字を入力してください。"
    case ViewerFailure.UnknownCode => "コードが見つかりません。ブラウザに表示されたコードを確かめ、期限切れの場合はブラウザでやり直してください。"
    case ViewerFailure.Mismatch => "中止しました。ブラウザでも「一致しない」を押し、最初からやり直してください。"
    case ViewerFailure.BadReveal => "ブラウザの応答が正しくないため中止しました。ブラウザで最初からやり直してください。"
    case ViewerFailure.QrMismatch => "サーバの応答が QR コードと一致しないため中止しました。ブラウザで最初からやり直してください。"
    case ViewerFailure.Changed => "途中で値が変わったため中止しました。ブラウザで最初からやり直してください。"
    case ViewerFailure.Expired => "期限（10分）が過ぎました。ブラウザで最初からやり直してください。"
    case ViewerFailure.Aborted => "この登録は中止されました。ブラウザで最初からやり直してください。"
    case ViewerFailure.NotReady(m) => m
    case ViewerFailure.Refused(m) => m
