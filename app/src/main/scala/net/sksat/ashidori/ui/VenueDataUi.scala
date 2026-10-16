package net.sksat.ashidori.ui

import android.net.ConnectivityManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.sksat.ashidori.*

/** Status texts and actions of the downloaded venue index (施設データ),
  * for its settings row; follows [[BasemapUi]].
  */
object VenueDataUi:
  val PrivacyNote =
    "施設データは日本全体をまとめて取得するため、位置情報は送信しません。端末内での施設の検索も送信しません。"

  def statusLine(st: BasemapStatus): String = st match
    case BasemapStatus.Installed(v, _) if VenueData.broken => s"読み込めません（v$v、再取得してください）"
    case _ => BasemapUi.pinnedStatusLine(st)

  /** Settings row: status and size (not with a failure: one line). */
  def rowLine(st: BasemapStatus): String = st match
    case BasemapStatus.Installed(_, _) if VenueData.broken => statusLine(st)
    case _ => BasemapUi.rowLine(st, VenueData.sizeLabel)

  private def metered(act: MainActivity): Boolean =
    act.getSystemService(classOf[ConnectivityManager]).isActiveNetworkMetered

  /** `again`: delete the installed (unreadable) data first. */
  def requestDownload(act: MainActivity, again: Boolean = false): Unit =
    def go(metered: Boolean) =
      if again then VenueDownload.redownload(act, metered) else VenueDownload.start(act, metered)
    if !metered(act) then go(false)
    else
      MaterialAlertDialogBuilder(act)
        .setTitle("モバイルデータ通信でダウンロードしますか")
        .setMessage(s"施設データ（${VenueData.sizeLabel}）を取得します。Wi-Fi を待つ場合は「Wi-Fi 接続時」を選択してください。")
        .setPositiveButton("ダウンロード", (_, _) => go(true))
        .setNegativeButton("Wi-Fi 接続時", (_, _) => go(false))
        .setNeutralButton("キャンセル", null)
        .show()

  /** Status, download / retry and delete. */
  def showDialog(act: MainActivity): Unit =
    val st = VenueDownload.status
    val date = st match
      case BasemapStatus.Installed(_, _) => VenueData.dateLabel.map("データ: OpenStreetMap " + _)
      case _ => None
    val body = Seq(
      "チェックインの候補となる日本の施設（OpenStreetMap）のデータです。端末内で検索するため、通信できない場所でも候補を表示します。",
      "",
      "状態: " + statusLine(st)
    ) ++ BasemapUi.failureLines(st) ++ date ++ BasemapUi.sourceLine(st) ++ Option(VenueData.sizeLabel).filter(_.nonEmpty).map("サイズ: " + _) ++
      Seq("", PrivacyNote)
    val b = MaterialAlertDialogBuilder(act).setTitle("施設データ").setMessage(body.mkString("\n"))
    st match
      case BasemapStatus.Unavailable => ()
      case BasemapStatus.Installed(_, _) =>
        if VenueData.broken then b.setPositiveButton("再取得", (_, _) => requestDownload(act, again = true))
        b.setNegativeButton("削除", (_, _) => confirmDelete(act))
      case BasemapStatus.Running(_, _, _) | BasemapStatus.Waiting(_) =>
        b.setNegativeButton("中止して削除", (_, _) => VenueDownload.delete(act))
        if st == BasemapStatus.Waiting(false) then
          b.setPositiveButton("今すぐダウンロード", (_, _) => requestDownload(act))
      case BasemapStatus.Failed(_, _, _) =>
        b.setPositiveButton("今すぐ再試行", (_, _) => requestDownload(act))
        b.setNegativeButton("削除", (_, _) => VenueDownload.delete(act))
      case BasemapStatus.Missing =>
        b.setPositiveButton("ダウンロード", (_, _) => requestDownload(act))
    b.setNeutralButton("閉じる", null).show()

  private def confirmDelete(act: MainActivity): Unit =
    MaterialAlertDialogBuilder(act)
      .setTitle("施設データを削除しますか")
      .setMessage("削除すると、チェックインの候補は過去の場所と保存済みの施設のみになります。近くの施設の検索は、これまでどおり「最新を取得」を押したときだけ行い、そのとき位置情報を送信します。設定からいつでも再取得できます。")
      .setPositiveButton("削除", (_, _) => VenueDownload.delete(act))
      .setNegativeButton("キャンセル", null)
      .show()
