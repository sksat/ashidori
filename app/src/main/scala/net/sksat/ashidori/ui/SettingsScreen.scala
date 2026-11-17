package net.sksat.ashidori.ui

import android.view.View
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.SyncConfig
import ashidori.core.LoggingMode
import Ui.*

/** Settings hub. Each row says what it is and its current state. */
final class SettingsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val PrivacySwitch = "オンライン地図と地名検索"
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
    val venues = VenueData.attribution
    def bullets(ls: Seq[String]) = ls.map("・" + _).mkString("\n")
    val lines = Seq(
      "■ 地名の検索（端末内で処理）",
      if geocoder.nonEmpty then bullets(geocoder)
      else "・大字・町丁目レベル位置参照情報　国土交通省。加工: 索引形式に変換（ashidori）\n・GeoNames、CC BY 4.0。改変: 索引形式に変換（ashidori）",
      "位置参照情報: https://nlftp.mlit.go.jp/isj/",
      "GeoNames: https://www.geonames.org/（CC BY 4.0: https://creativecommons.org/licenses/by/4.0/）",
      "",
      "■ オフライン地図",
      if basemap.nonEmpty then bullets(basemap) else BasemapStaticCredits,
      "OpenStreetMap: https://www.openstreetmap.org/copyright",
      "",
      "■ オンライン地図",
      "・OpenFreeMap: © OpenMapTiles、© OpenStreetMap contributors",
      "・国土地理院: 地理院タイル",
      "",
      "■ 施設データ",
      if venues.nonEmpty then bullets(venues) else VenueStaticCredits,
      "OpenStreetMap: https://www.openstreetmap.org/copyright",
      "施設データは OpenStreetMap から作成した Derivative Database であり、ODbL 1.0 の下で提供する。作り方: https://github.com/sksat/ashidori/tree/main/tools/venues",
      "",
      "■ 施設のオンライン検索",
      "・OpenStreetMap（Overpass API）: © OpenStreetMap contributors（ODbL）"
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

  /** Credits of the venue data before it is downloaded (tools/venues/build_index.py writes the same). */
  private val VenueStaticCredits = Seq(
    "© OpenStreetMap contributors（ODbL 1.0、https://www.openstreetmap.org/copyright）",
    "Geofabrik の日本の抽出（https://download.geofabrik.de/asia/japan.html）。加工: チェックインの対象となる施設を抜き出し、索引形式に変換（ashidori）"
  ).map("・" + _).mkString("\n")

  /** オン・オフ of the privacy switch and what it does not cover, behind its info button. */
  private def showPrivacyInfo(): Unit =
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle(PrivacySwitch)
      .setMessage(
        "オン: オンライン地図の表示範囲を配信元へ送信。端末内で地名が求まらない位置は、Android の地名検索サービス（Google など、端末による）へ送信します。\n\n" +
        "オフ: 地図・地名は端末内のデータを使用。画面ごとにオンライン地図を選ぶと、その画面の表示範囲を送信します。選択は再起動後も保持され、オフライン地図に戻すか、この設定を切り替えると解除されます。\n\n" +
        "施設のオンライン検索は、設定に関係なくボタン操作時に検索位置を施設検索先（Overpass API）へ送信します。")
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

  /** A download row's one line, in the error colour while its last attempt failed. */
  private def showDownload(row: ListRow, st: BasemapStatus, line: String): Unit =
    row.setSubtitle(line)
    row.subtitle.setTextColor(color(st match
      case _: BasemapStatus.Failed => attr.error
      case _ => attr.onSurfaceVariant))
  private def showBasemap(row: ListRow): Unit =
    val st = BasemapDownload.status
    showDownload(row, st, BasemapUi.rowLine(st, Basemap.sizeLabel))
  private def showVenues(row: ListRow): Unit =
    val st = VenueDownload.status
    showDownload(row, st, VenueDataUi.rowLine(st))

  private val list = vbox()
  val view: View = scroll(list)

  /** The オフライン地図 row, kept current while the download runs. */
  private var basemapRow: Option[ListRow] = None
  private val stopBasemap = BasemapDownload.listen(() => basemapRow.foreach(showBasemap))
  /** The 施設データ row, likewise. */
  private var venuesRow: Option[ListRow] = None
  private val stopVenues = VenueDownload.listen(() => venuesRow.foreach(showVenues))
  override def onDestroy(): Unit =
    stopBasemap()
    stopVenues()

  override def onShow(): Unit =
    list.removeAllViews()
    def add(vs: View*): Unit = vs.foreach(list.addView)

    lazy val mode: ListRow = choiceRow("記録モード", "")(chooseMode(mode))
    showMode(mode)
    add(sectionHeader("記録"), listGroup(mode))

    // A positive switch, inverted over the stored setting (whose meaning stays: true = do not send).
    add(sectionHeader("プライバシー"), listGroup(
      switchRow(PrivacySwitch, ashidori.core.SendGate.switchOn(Settings.blockExternalLocation),
        "表示範囲を地図配信元へ、位置を地名検索先へ送信", onInfo = Some(() => showPrivacyInfo())) { on =>
        Settings.blockExternalLocation = ashidori.core.SendGate.blockFor(on)
        TrackMap.privacyChanged(act)
      }))

    val basemap = navRow("オフライン地図", " ")(BasemapUi.showDialog(act))
    showBasemap(basemap)
    basemapRow = Some(basemap)
    val venues = navRow("施設データ", " ")(VenueDataUi.showDialog(act))
    showVenues(venues)
    venuesRow = Some(venues)
    // Open (check) the installed index, so an unreadable one shows here.
    AshidoriApp.io.execute { () =>
      VenueData.index()
      VenueDownload.changed()
    }
    add(sectionHeader("地図"), listGroup(basemap, venues))

    val syncState =
      if SyncConfig.ready then s"同期中・${SyncConfig.serverUrl}"
      else if SyncConfig.paired then "鍵の設定が必要"
      else "未設定（この端末のみに保存）"
    add(sectionHeader("データ"), listGroup(
      navRow("サーバ同期", syncState)(act.push(SyncSettingsScreen(act))),
      navRow("エクスポート・インポート", "Timeline.json・GPX・Parquet・GNSS ログ")(act.push(DataScreen(act)))
    ))

    add(sectionHeader("情報"), listGroup(navRow("データの出典")(showSources())))

    add(sectionHeader("開発者向け"), listGroup(
      navRow("端末診断", "衛星測位とセンサーの対応状況・自己テスト")(act.push(DiagnosticsScreen(act)))))

    val version = supportingText(s"ashidori ${BuildInfo.versionName}")
    version.setPadding(version.getPaddingLeft, dp(24), version.getPaddingRight, dp(24))
    add(version)
