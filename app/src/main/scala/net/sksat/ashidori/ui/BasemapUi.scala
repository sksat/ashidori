package net.sksat.ashidori.ui

import android.net.ConnectivityManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.sksat.ashidori.*

/** Status texts and actions of the downloaded offline basemap (簡易地図),
  * shared by the settings row and the banner on maps without it.
  */
object BasemapUi:
  /** Shown wherever the download is offered. */
  val PrivacyNote =
    "地図データは国全体をまとめて取得するため、位置情報は送信しません。「位置情報を外部サービスに送らない」がオンでも取得します。"

  def percent(done: Long, total: Long): Int = if total <= 0 then 0 else (done * 100 / total).toInt

  /** One line: 未取得 / ダウンロード中 nn% / 取得済み vX / 失敗. */
  def statusLine(st: BasemapStatus): String = st match
    case BasemapStatus.Unavailable => "このビルドでは利用できません"
    case BasemapStatus.Missing => "未取得"
    case BasemapStatus.Waiting(metered) => if metered then "ダウンロード待ち" else "未取得（Wi-Fi 接続時にダウンロード）"
    case BasemapStatus.Running(d, t, _) => s"ダウンロード中 ${percent(d, t)}%"
    case BasemapStatus.Installed(v, _) => s"取得済み v$v"
    case BasemapStatus.Failed(msg, retrying) => s"失敗: $msg" + (if retrying then "（自動で再試行します）" else "")

  def sourceLine(st: BasemapStatus): Option[String] = st match
    case BasemapStatus.Running(_, _, s) => Some(s"取得元: ${s.label}")
    case BasemapStatus.Installed(_, s) => s.map(x => s"取得元: ${x.label}")
    case _ => None

  /** Settings row: status, source and size. */
  def summary(st: BasemapStatus): String =
    (Seq(statusLine(st)) ++ sourceLine(st) ++ Option(Basemap.sizeLabel).filter(_.nonEmpty).map("サイズ: " + _)).mkString("\n")

  /** Text of the banner on a map drawn without the basemap. */
  def bannerText(st: BasemapStatus): String = st match
    case BasemapStatus.Running(d, t, _) => s"簡易地図をダウンロード中 ${percent(d, t)}%"
    case BasemapStatus.Failed(_, _) => "簡易地図を取得できませんでした（再試行）"
    case BasemapStatus.Waiting(true) => "簡易地図のダウンロード待ち"
    case _ => s"簡易地図をダウンロード（${Basemap.sizeLabel}・Wi-Fi）"

  private def metered(act: MainActivity): Boolean =
    act.getSystemService(classOf[ConnectivityManager]).isActiveNetworkMetered

  /** Starts the download; on a metered network only after confirmation, and
    * otherwise it waits for Wi-Fi.
    */
  def requestDownload(act: MainActivity): Unit =
    if !metered(act) then BasemapDownload.start(act, allowMetered = false)
    else
      MaterialAlertDialogBuilder(act)
        .setTitle("モバイルデータ通信でダウンロードしますか")
        .setMessage(s"簡易地図のデータ（${Basemap.sizeLabel}）を取得します。Wi-Fi を待つ場合は「Wi-Fi 接続時」を選択してください。")
        .setPositiveButton("ダウンロード", (_, _) => BasemapDownload.start(act, allowMetered = true))
        .setNegativeButton("Wi-Fi 接続時", (_, _) => BasemapDownload.start(act, allowMetered = false))
        .setNeutralButton("キャンセル", null)
        .show()

  /** The banner on a map without the basemap. */
  def bannerClicked(act: MainActivity): Unit = BasemapDownload.status match
    case BasemapStatus.Missing | BasemapStatus.Failed(_, _) => requestDownload(act)
    case _ => showDialog(act)

  /** Status, download / retry and delete. */
  def showDialog(act: MainActivity): Unit =
    val st = BasemapDownload.status
    val body = Seq(
      "端末内で表示する地図（日本はズーム 10、全世界はズーム 5 まで）のデータです。取得するまで、地図には経路と滞在地のみを表示します。",
      "",
      "状態: " + statusLine(st)
    ) ++ sourceLine(st) ++ Option(Basemap.sizeLabel).filter(_.nonEmpty).map("サイズ: " + _) ++ Seq("", PrivacyNote)
    val b = MaterialAlertDialogBuilder(act).setTitle("簡易地図").setMessage(body.mkString("\n"))
    st match
      case BasemapStatus.Unavailable => ()
      case BasemapStatus.Installed(_, _) =>
        b.setNegativeButton("削除", (_, _) => confirmDelete(act))
      case BasemapStatus.Running(_, _, _) | BasemapStatus.Waiting(_) =>
        b.setNegativeButton("中止して削除", (_, _) => BasemapDownload.delete(act))
        if st == BasemapStatus.Waiting(false) then
          b.setPositiveButton("今すぐダウンロード", (_, _) => requestDownload(act))
      case BasemapStatus.Failed(_, _) =>
        b.setPositiveButton("再試行", (_, _) => requestDownload(act))
        b.setNegativeButton("削除", (_, _) => BasemapDownload.delete(act))
      case BasemapStatus.Missing =>
        b.setPositiveButton("ダウンロード", (_, _) => requestDownload(act))
    b.setNeutralButton("閉じる", null).show()

  private def confirmDelete(act: MainActivity): Unit =
    MaterialAlertDialogBuilder(act)
      .setTitle("簡易地図を削除しますか")
      .setMessage("削除すると、地図には経路と滞在地のみを表示します。設定からいつでも再取得できます。")
      .setPositiveButton("削除", (_, _) => BasemapDownload.delete(act))
      .setNegativeButton("キャンセル", null)
      .show()
