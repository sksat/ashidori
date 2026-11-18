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
          // Stored after the storage floor for it is written (design 6.8); then applied.
          Storage.changeMode(m, () => {
            if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
            showMode(row)
          })
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

  private val list = vbox()
  val view: View = scroll(list)

  /** The オフラインのデータ row, kept current while downloads run. */
  private var offlineRow: Option[ListRow] = None
  private val stopBasemap = BasemapDownload.listen(() => offlineRow.foreach(showOffline))
  private val stopVenues = VenueDownload.listen(() => offlineRow.foreach(showOffline))
  private val stopRegions = Regions.listen(() => offlineRow.foreach(showOffline))
  override def onDestroy(): Unit =
    stopBasemap()
    stopVenues()
    stopRegions()

  /** One line: a failed download first (in the error colour), else what is on the device. */
  private def showOffline(row: ListRow): Unit =
    val failed = Seq(BasemapDownload.status -> "オフライン地図", VenueDownload.status -> "施設データ").collectFirst {
      case (f: BasemapStatus.Failed, name) => s"$name: ${BasemapUi.failedText(f.failure)}"
    }
    val regions = if Regions.offered then Seq(s"地域 ${Regions.state.regions.size}") else Seq.empty
    row.setSubtitle(failed.getOrElse((regions ++ Seq("全域の地図", "施設データ")).mkString("・")))
    row.subtitle.setTextColor(color(if failed.nonEmpty then attr.error else attr.onSurfaceVariant))

  override def onShow(): Unit =
    list.removeAllViews()
    def add(vs: View*): Unit = vs.foreach(list.addView)

    lazy val mode: ListRow = choiceRow("記録モード", "")(chooseMode(mode))
    showMode(mode)
    add(sectionHeader("記録"), listGroup(mode))

    // Where the records are kept and what is sent where, per destination.
    add(sectionHeader("プライバシー"), listGroup(
      navRow("データの扱い", OutboundUi.summary)(act.push(DataHandlingScreen(act)))))

    val offline = navRow("オフラインのデータ", " ")(act.push(OfflineDataScreen(act)))
    showOffline(offline)
    offlineRow = Some(offline)
    // Open (check) the installed index, so an unreadable one shows here.
    AshidoriApp.io.execute { () =>
      VenueData.index()
      VenueDownload.changed()
    }
    add(sectionHeader("地図"), listGroup(offline))

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
