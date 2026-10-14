package net.sksat.ashidori.ui

import android.view.View
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.SyncConfig
import ashidori.core.LoggingMode
import Ui.*

/** Settings hub. Each row says what it is and its current state. */
final class SettingsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "設定"

  /** Attribution of the bundled data (from the generated manifests) and of
    * the online services.
    */
  private def showSources(): Unit =
    val geocoder = scala.util.Try {
      val in = act.getAssets.open(Geocoding.ManifestPath)
      try ujson.read(in.readAllBytes())("attribution").arr.map(_.str).toSeq finally in.close()
    }.getOrElse(Seq.empty)
    val basemap = Basemap.attribution
    def bullets(ls: Seq[String]) = ls.map("・" + _).mkString("\n")
    val lines = Seq(
      "■ 地名の検索（端末内で処理）",
      if geocoder.nonEmpty then bullets(geocoder)
      else "・大字・町丁目レベル位置参照情報　国土交通省。加工: 索引形式に変換（ashidori）\n・GeoNames、CC BY 4.0。改変: 索引形式に変換（ashidori）",
      "位置参照情報: https://nlftp.mlit.go.jp/isj/",
      "GeoNames: https://www.geonames.org/（CC BY 4.0: https://creativecommons.org/licenses/by/4.0/）",
      "",
      "■ 簡易地図（端末内の地図。初回にダウンロード）",
      if basemap.nonEmpty then bullets(basemap) else BasemapStaticCredits,
      "OpenStreetMap: https://www.openstreetmap.org/copyright",
      "",
      "■ 詳しい地図（サーバから取得）",
      "・OpenFreeMap: © OpenMapTiles、© OpenStreetMap contributors",
      "・国土地理院: 地理院タイル",
      "",
      "■ 近くの施設の検索",
      "・© OpenStreetMap contributors（ODbL）"
    )
    val body = text(lines.mkString("\n"), 14f)
    body.setTextIsSelectable(true)
    android.text.util.Linkify.addLinks(body, android.text.util.Linkify.WEB_URLS)
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("データの出典")
      .setView(padded(body, 24, 8))
      .setPositiveButton("閉じる", null)
      .show()

  /** Credits of the basemap before it is downloaded (tools/basemap/assemble.py writes the same). */
  private val BasemapStaticCredits = Seq(
    "© OpenStreetMap contributors（ODbL 1.0、https://www.openstreetmap.org/copyright）",
    "Protomaps Basemap（https://protomaps.com/）。加工: 日本と全世界の低ズームを切り出し（ashidori）",
    "Natural Earth（パブリックドメイン、https://www.naturalearthdata.com/）",
    "Noto Sans（SIL Open Font License 1.1）。字形データは protomaps/basemaps-assets による",
    "アイコン: protomaps/basemaps-assets（tangrams/icons に由来、MIT License）",
    "地図の配色: @protomaps/basemaps（BSD-3-Clause）"
  ).map("・" + _).mkString("\n")

  /** オン・オフ・対象外 of the privacy switch, behind its info button. */
  private def showPrivacyInfo(): Unit =
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("位置情報を外部サービスに送らない")
      .setMessage(
        "地図の取得、地名の問い合わせ、施設の検索で、本アプリが位置情報を自動で送信することを止めます。\n\n" +
        "オン: 地図は端末内の簡易地図で表示し、地名は端末内のデータで求めます。詳しい地図の表示と施設の検索は、操作したときのみ行い、その位置情報を送信します。詳しい地図は画面を離れると簡易地図に戻ります。\n\n" +
        "オフ: 選択した地図をサーバから取得し、近くの施設を自動で検索します（OpenStreetMap）。端末内のデータで地名が求まらない場合は、Android の Geocoder に問い合わせます。\n\n" +
        "対象外: 現在地の測位は端末の位置情報の設定に従います。地図アプリで開く、エクスポートを共有するなどの操作では、その位置情報を渡します。")
      .setPositiveButton("閉じる", null)
      .show()

  private def modeValue(m: LoggingMode) = s"${ModeInfo.symbol(m)} ${m.label}"

  /** The four modes, each with its battery use (the ステータス tab has the details). */
  private def chooseMode(row: ListRow): Unit =
    import android.text.{SpannableStringBuilder, Spanned}
    import android.text.style.{ForegroundColorSpan, RelativeSizeSpan}
    val modes = LoggingMode.values.toSeq
    val items: Array[CharSequence] = modes.map { m =>
      val sb = SpannableStringBuilder(modeValue(m) + "\n")
      val start = sb.length
      sb.append(ModeInfo.battery(m))
      sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.setSpan(ForegroundColorSpan(color(attr.onSurfaceVariant)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb: CharSequence
    }.toArray
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("記録モード")
      .setSingleChoiceItems(items, modes.indexOf(Settings.mode), (d, i) => {
        val m = modes(i)
        if Settings.mode != m then
          Settings.mode = m
          if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
        showMode(row)
        d.dismiss()
      })
      .setNegativeButton("キャンセル", null)
      .show()

  private def showMode(row: ListRow): Unit =
    val m = Settings.mode
    row.setValue(modeValue(m))
    row.value.setTextColor(ModeInfo.color(m))
    row.setSubtitle(ModeInfo.battery(m))

  private def basemapLine(st: BasemapStatus): String =
    (Seq(BasemapUi.statusLine(st)) ++ Option(Basemap.sizeLabel).filter(_.nonEmpty)).mkString("・")

  private val list = vbox()
  val view: View = scroll(list)

  /** The 簡易地図 row, kept current while the download runs. */
  private var basemapRow: Option[ListRow] = None
  private val stopBasemap = BasemapDownload.listen(() =>
    basemapRow.foreach(_.setSubtitle(basemapLine(BasemapDownload.status))))
  override def onDestroy(): Unit = stopBasemap()

  override def onShow(): Unit =
    list.removeAllViews()
    def add(vs: View*): Unit = vs.foreach(list.addView)

    lazy val mode: ListRow = choiceRow("記録モード", "")(chooseMode(mode))
    showMode(mode)
    add(sectionHeader("記録"), listGroup(mode))

    add(sectionHeader("プライバシー"), listGroup(
      switchRow("位置情報を外部サービスに送らない", Settings.blockExternalLocation,
        "地図・地名・施設の検索", onInfo = Some(() => showPrivacyInfo())) { b =>
        Settings.blockExternalLocation = b
        TrackMap.privacyChanged(act)
      }))

    val basemap = navRow("簡易地図", basemapLine(BasemapDownload.status))(BasemapUi.showDialog(act))
    basemapRow = Some(basemap)
    add(sectionHeader("地図"), listGroup(basemap))

    val syncState =
      if SyncConfig.ready then s"同期中・${SyncConfig.serverUrl}"
      else if SyncConfig.paired then "鍵の設定が必要です"
      else "未設定（この端末だけに保存）"
    add(sectionHeader("データ"), listGroup(
      navRow("サーバ同期（エンドツーエンド暗号化）", syncState)(act.push(SyncSettingsScreen(act))),
      navRow("エクスポート・インポート", "Timeline.json・GPX・Parquet・GNSS ログ")(act.push(DataScreen(act)))
    ))

    add(sectionHeader("情報"), listGroup(navRow("データの出典")(showSources())))

    add(sectionHeader("開発者向け"), listGroup(
      navRow("端末診断", "GNSS とセンサーの能力・自己テスト")(act.push(DiagnosticsScreen(act)))))

    val version = supportingText(s"ashidori ${BuildInfo.versionName}")
    version.setPadding(version.getPaddingLeft, dp(24), version.getPaddingRight, dp(24))
    add(version)
