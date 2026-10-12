package net.sksat.ashidori.ui

import android.content.{ClipData, ClipboardManager}
import android.text.InputType
import android.view.View
import android.widget.{EditText, Toast}
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import scala.util.Try
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.*
import ashidori.core.sync.{Crypto, RecoveryCode}
import Ui.*

/** Server sync setup. Steps are shown in order and only the next needed one
  * is active: register this device, set up the encryption key, then options.
  */
final class SyncSettingsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "同期"
  private val net = Executors.newSingleThreadExecutor()
  private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
  private val body = vbox()
  val view: View = scroll(body)

  override def onShow(): Unit = render()
  override def onDestroy(): Unit = net.shutdown()

  private def async[A](work: => A)(done: Try[A] => Unit): Unit =
    net.execute { () =>
      val r = Try(work)
      act.runOnUiThread(() => done(r))
    }

  private def toast(s: String): Unit = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

  private def render(): Unit =
    body.removeAllViews()
    body.addView(
      card(
        text("エンドツーエンド暗号化", 16f, bold = true),
        caption("記録は端末で暗号化してから送ります。鍵はあなたの端末だけが持ち、サーバ（Cloudflare）からは中身を読めません。鍵をなくすとサーバ上のデータは復元できないため、復旧コードを必ず控えてください。")
      )
    )
    if !SyncConfig.paired then body.addView(registerCard())
    else if SyncConfig.masterKey.isEmpty then body.addView(keyCard())
    else
      body.addView(statusCard())
      body.addView(optionsCard())
      body.addView(devicesCard())
    body.addView(spacer(24))

  private def registerCard(): View =
    val url = EditText(act)
    url.setHint("サーバの URL（https://...）")
    url.setInputType(InputType.TYPE_TEXT_VARIATION_URI)
    url.setText(SyncConfig.serverUrl)
    val code = EditText(act)
    code.setHint("ペアリングコード（8文字）")
    code.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
    val go = button("この端末を登録") {
      val u = url.getText.toString.trim.stripSuffix("/")
      val c = code.getText.toString.trim.toUpperCase
      if !u.startsWith("https://") then toast("https:// で始まる URL を入力してください")
      else
        async(SyncClient(u, None).register(c, SyncScheduler.deviceName)) {
          _.fold(e => toast(e.getMessage), { (id, token) =>
            SyncConfig.savePairing(u, id, token)
            render()
          })
        }
    }
    card(
      text("1. 端末を登録", 16f, bold = true),
      caption("サーバの管理ページ、または登録済みの別の端末でペアリングコードを発行して入力します。"),
      url,
      code,
      go
    )

  private def keyCard(): View =
    card(
      text("2. 暗号化の鍵", 16f, bold = true),
      caption("最初の端末なら鍵を新しく作ります。2台目以降は、最初の端末で控えた復旧コードを入力します。"),
      button("新しい鍵を作る（最初の端末）")(createKey()),
      outlined("復旧コードを入力（2台目以降）")(enterRecoveryCode())
    )

  private def createKey(): Unit =
    val key = Crypto.newMasterKey()
    val code = RecoveryCode.encode(key)
    val groups = code.split('-')
    val confirm = EditText(act)
    confirm.setHint(s"最後の4文字（${groups.length}番目のブロック）")
    MaterialAlertDialogBuilder(act)
      .setTitle("復旧コードを控えてください")
      .setMessage(s"$code\n\n別の端末の追加や、この端末をなくしたときの復元に必要です。パスワードマネージャーなどに保存してください。確認のため、最後のブロックを入力してください。")
      .setView(padded(vbox(confirm), 20, 0))
      .setCancelable(false)
      .setNeutralButton("コピー", null)
      .setNegativeButton("やめる", null)
      .setPositiveButton("控えました", null)
      .create()
      .tap { d =>
        d.setOnShowListener { _ =>
          d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { _ =>
            act.getSystemService(classOf[ClipboardManager]).setPrimaryClip(ClipData.newPlainText("ashidori recovery code", code))
            toast("コピーしました。保存したらクリップボードを消してください")
          }
          d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ =>
            if confirm.getText.toString.trim.equalsIgnoreCase(groups.last) then
              SyncConfig.setMasterKey(key)
              SyncScheduler.schedule(act)
              d.dismiss()
              render()
            else confirm.setError("一致しません")
          }
        }
        d.show()
      }

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }

  private def enterRecoveryCode(): Unit =
    val input = EditText(act)
    input.setHint("XXXX-XXXX-...")
    MaterialAlertDialogBuilder(act)
      .setTitle("復旧コードを入力")
      .setView(padded(vbox(input), 20, 0))
      .setNegativeButton("やめる", null)
      .setPositiveButton("設定", (_, _) =>
        RecoveryCode.decode(input.getText.toString) match
          case Right(k) =>
            SyncConfig.setMasterKey(k)
            SyncScheduler.schedule(act)
            render()
          case Left(err) => toast(err)
      )
      .show()

  private def statusCard(): View =
    val last = SyncConfig.lastSync
    val err = SyncConfig.lastError
    card(
      text("● 同期中", 16f, bold = true, colorAttr = attr.primary),
      caption(SyncConfig.serverUrl),
      caption(if last == 0 then "まだ同期していません" else s"最終同期 ${Instant.ofEpochMilli(last).atZone(AshidoriApp.zone).format(fmt)}"),
      (if err.nonEmpty then text(s"前回のエラー: $err", 13f, colorAttr = attr.error) else spacer(0)),
      button("今すぐ同期", tonal = true) {
        SyncScheduler.syncNow(act)
        toast("同期を予約しました（ネットワークにつながると始まります）")
      }
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
    def load(): Unit =
      async(client.request("GET", "/v1/devices").json) { r =>
        list.removeAllViews()
        r.fold(
          e => list.addView(caption(s"取得できませんでした: ${e.getMessage}")),
          js =>
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
      outlined("復旧コードを表示") {
        SyncConfig.masterKey.foreach { k =>
          MaterialAlertDialogBuilder(act).setTitle("復旧コード").setMessage(RecoveryCode.encode(k)).setPositiveButton("閉じる", null).show()
        }
      },
      outlined("この端末の同期を解除") {
        MaterialAlertDialogBuilder(act)
          .setTitle("同期を解除しますか")
          .setMessage("この端末から鍵と登録情報を消します。端末内の記録は残ります。")
          .setNegativeButton("やめる", null)
          .setPositiveButton("解除", (_, _) => { SyncConfig.forget(); SyncScheduler.schedule(act); render() })
          .show()
      }
    )
