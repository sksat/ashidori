package net.sksat.ashidori.ui

import android.net.ConnectivityManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.{Instant, LocalDate}
import net.sksat.ashidori.*
import ashidori.core.DownloadFailure

/** Status texts and actions of the downloaded offline basemap (簡易地図),
  * shared by the settings row and the card on maps without it.
  */
object BasemapUi:
  /** Shown wherever the download is offered. */
  val PrivacyNote =
    "地図データは国全体をまとめて取得するため、位置情報は送信しません。「位置情報を外部サービスに送らない」がオンでも取得します。"

  def percent(done: Long, total: Long): Int = if total <= 0 then 0 else (done * 100 / total).toInt

  /** "9:26" today, "10月17日 3:00" on another day. */
  def timeLabel(ms: Long): String =
    val t = Instant.ofEpochMilli(ms).atZone(AshidoriApp.zone)
    val hm = f"${t.getHour}:${t.getMinute}%02d"
    if t.toLocalDate == LocalDate.now(AshidoriApp.zone) then hm else s"${t.getMonthValue}月${t.getDayOfMonth}日 $hm"

  /** 「取得できません（配信元にありません・HTTP 404）」. */
  def failedText(f: DownloadFailure): String = s"取得できません（${f.label}）"

  private def unmeteredNow: Boolean =
    scala.util.Try(!AshidoriApp.app.getSystemService(classOf[ConnectivityManager]).isActiveNetworkMetered).getOrElse(false)

  /** When a failed download runs again by itself: 「18:30 に再試行」, or
    * soon, or on Wi-Fi; 「再試行できます」 when it does not.
    */
  def retryText(st: BasemapStatus.Failed): String =
    val now = System.currentTimeMillis()
    if !st.queued then "再試行できます"
    else st.failure.retryAt.filter(_ > now) match
      case Some(t) => s"${timeLabel(t)} に再試行"
      case None => if st.metered || unmeteredNow then "まもなく再試行" else "Wi-Fi 接続時に再試行"

  /** One line, shared with the 施設データ row: 未取得 / ダウンロード中 nn% / 取得済み vX / 取得できません（理由）・再試行. */
  def pinnedStatusLine(st: BasemapStatus): String = st match
    case BasemapStatus.Unavailable => "このビルドでは利用できません"
    case BasemapStatus.Missing => "未取得"
    case BasemapStatus.Waiting(metered) => if metered then "ダウンロード待ち" else "未取得（Wi-Fi 接続時にダウンロード）"
    case BasemapStatus.Running(d, t, _) => s"ダウンロード中 ${percent(d, t)}%"
    case BasemapStatus.Installed(v, _) => s"取得済み v$v"
    case f: BasemapStatus.Failed => s"${failedText(f.failure)}・${retryText(f)}"

  def statusLine(st: BasemapStatus): String = pinnedStatusLine(st)

  /** The settings row's one line: 「取得できません（HTTP 404）・15:34 に再試行」;
    * the dialog has the full reason.
    */
  private def rowStatus(st: BasemapStatus): String = st match
    case f: BasemapStatus.Failed => s"取得できません（${f.failure.shortLabel}）・${retryText(f)}"
    case _ => pinnedStatusLine(st)

  /** Dialog lines of a failure: when, and each source's answer. */
  def failureLines(st: BasemapStatus): Seq[String] = st match
    case BasemapStatus.Failed(f, _, _) =>
      Seq(s"最後の失敗: ${timeLabel(f.at)}" + (if f.sources.size == 1 then s"（${f.sources.head._1}）" else "")) ++
        (if f.sources.size > 1 then f.sources.map((src, c) => s"・$src: ${ashidori.core.DownloadCause.label(c)}") else Seq.empty)
    case _ => Seq.empty

  /** Settings row: one line; the size only while it says nothing about a failure. */
  def rowLine(st: BasemapStatus, sizeLabel: String): String = st match
    case _: BasemapStatus.Failed => rowStatus(st)
    case _ => (Seq(pinnedStatusLine(st)) ++ Option(sizeLabel).filter(_.nonEmpty)).mkString("・")

  def sourceLine(st: BasemapStatus): Option[String] = st match
    case BasemapStatus.Running(_, _, s) => Some(s"取得元: ${s.label}")
    case BasemapStatus.Installed(_, s) => s.map(x => s"取得元: ${x.label}")
    case _ => None

  /** Settings row: status, source and size. */
  def summary(st: BasemapStatus): String =
    (Seq(statusLine(st)) ++ sourceLine(st) ++ Option(Basemap.sizeLabel).filter(_.nonEmpty).map("サイズ: " + _)).mkString("\n")

  /** Heading of the card on a map without a base map: why it has none. */
  def cardTitle(st: BasemapStatus): String = st match
    case BasemapStatus.Unavailable => "このビルドでは簡易地図を利用できません"
    case BasemapStatus.Missing => "簡易地図は未取得です"
    case BasemapStatus.Installed(_, _) => "簡易地図のデータを読み込めません"
    case BasemapStatus.Waiting(false) => "簡易地図は Wi-Fi 接続時に取得します"
    case BasemapStatus.Waiting(true) => "簡易地図のダウンロード待ち"
    case BasemapStatus.Running(d, t, _) => s"簡易地図をダウンロード中 ${percent(d, t)}%"
    case f: BasemapStatus.Failed => s"簡易地図を取得できません（${f.failure.shortLabel}）"

  /** Whether that card offers the download (or a retry) now. */
  def offersDownload(st: BasemapStatus): Boolean = st match
    case BasemapStatus.Missing | BasemapStatus.Waiting(false) | BasemapStatus.Failed(_, _, _) => true
    case _ => false

  /** Label of the card's download button: a retry after a failure. */
  def downloadLabel(st: BasemapStatus): String =
    val verb = st match
      case _: BasemapStatus.Failed => "簡易地図の取得を再試行"
      case _ => "簡易地図をダウンロード"
    if Basemap.sizeLabel.isEmpty then verb else s"$verb（${Basemap.sizeLabel}）"

  /** What the small chip on a map without a base map offers: its action
    * text, whether that is a failure (error colour), and what a tap does.
    * None: nothing to offer (this build has no basemap).
    */
  enum ChipAction:
    /** 「取得」: starts the download (asks first on a metered network). */
    case Download
    /** Opens the 簡易地図 dialog: the reason, retry and delete. */
    case Details(label: String, failed: Boolean)

  def chipTitle(st: BasemapStatus): String = st match
    case BasemapStatus.Installed(_, _) => "簡易地図を読み込めません"
    case _ => "簡易地図がありません"

  def chipAction(st: BasemapStatus): Option[ChipAction] = st match
    case BasemapStatus.Unavailable => None
    case BasemapStatus.Missing | BasemapStatus.Waiting(false) => Some(ChipAction.Download)
    case BasemapStatus.Waiting(true) => Some(ChipAction.Details("取得待ち", failed = false))
    case BasemapStatus.Running(d, t, _) => Some(ChipAction.Details(s"取得中 ${percent(d, t)}%", failed = false))
    case BasemapStatus.Installed(_, _) => Some(ChipAction.Details("詳細", failed = true))
    case _: BasemapStatus.Failed => Some(ChipAction.Details("取得できません", failed = true))

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

  /** Status, download / retry and delete. */
  def showDialog(act: MainActivity): Unit =
    val st = BasemapDownload.status
    val body = Seq(
      "端末内で表示する地図（日本はズーム 10、全世界はズーム 5 まで）のデータです。取得するまで、地図には経路と滞在地のみを表示します。",
      "",
      "状態: " + statusLine(st)
    ) ++ failureLines(st) ++ sourceLine(st) ++ Option(Basemap.sizeLabel).filter(_.nonEmpty).map("サイズ: " + _) ++ Seq("", PrivacyNote)
    val b = MaterialAlertDialogBuilder(act).setTitle("簡易地図").setMessage(body.mkString("\n"))
    st match
      case BasemapStatus.Unavailable => ()
      case BasemapStatus.Installed(_, _) =>
        b.setNegativeButton("削除", (_, _) => confirmDelete(act))
      case BasemapStatus.Running(_, _, _) | BasemapStatus.Waiting(_) =>
        b.setNegativeButton("中止して削除", (_, _) => BasemapDownload.delete(act))
        if st == BasemapStatus.Waiting(false) then
          b.setPositiveButton("今すぐダウンロード", (_, _) => requestDownload(act))
      case BasemapStatus.Failed(_, _, _) =>
        b.setPositiveButton("今すぐ再試行", (_, _) => requestDownload(act))
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
