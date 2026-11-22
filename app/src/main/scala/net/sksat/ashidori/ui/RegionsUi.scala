package net.sksat.ashidori.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{EditText, FrameLayout, LinearLayout, PopupMenu, TextView, Toast}
import androidx.lifecycle.{DefaultLifecycleObserver, LifecycleOwner}
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.{CameraPosition, CameraUpdateFactory}
import org.maplibre.android.geometry.LatLng as MLatLng
import org.maplibre.android.maps.{MapLibreMap, MapView, Style}
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.{FillLayer, LineLayer, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Feature, FeatureCollection, Polygon, Point}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Texts of the region packs' screens. */
object RegionsUi:
  /** 「412 MB」「1.8 MB」「1.2 GB」 (SI units, as the catalog's sizes). */
  def size(bytes: Long): String =
    if bytes >= 1_000_000_000L then f"${bytes / 1e9}%.1f GB"
    else if bytes >= 10_000_000L then s"${math.round(bytes / 1e6)} MB"
    else if bytes >= 100_000L then f"${bytes / 1e6}%.1f MB"
    else if bytes > 0 then "0.1 MB"
    else "0 MB"

  def date(ms: Long): String =
    val d = Instant.ofEpochMilli(ms).atZone(AshidoriApp.zone).toLocalDate
    f"${d.getYear}/${d.getMonthValue}%02d/${d.getDayOfMonth}%02d"

  /** Fetches the pinned catalog if it is not on the device (it holds no
    * position), when the user opens a screen of the regions: under a
    * permit of that action, and on a metered network only with
    * 「モバイルデータ通信でも取得」. `done` (main thread) hears None when it
    * is there, or why not.
    */
  def ensureCatalog(act: MainActivity)(done: Option[String] => Unit): Unit =
    if Regions.catalog.nonEmpty then done(None)
    else
      val metered = scala.util.Try(act.getSystemService(classOf[android.net.ConnectivityManager]).isActiveNetworkMetered).getOrElse(true)
      if metered && !Regions.metered then done(Some("Wi-Fi 接続時にカタログを取得"))
      else Settings.gate.permit(Dest.Data, Mode.OnAction).filter(_.level == Level.Cell) match
        case None => done(Some("カタログなし"))
        case Some(p) =>
          AshidoriApp.io.execute { () =>
            val r = scala.util.Try(Regions.fetchCatalog(p))
            r.failed.foreach(e => Log.w("regions: catalog", e))
            act.runOnUiThread(() => done(r.failed.toOption.map(_ => "カタログを取得できません")))
          }

  /** Where the packs come from, as the screens name it (design 6.14). */
  def source: String = if Regions.debugUrl.nonEmpty then "開発用 URL" else "GitHub"

/** 「オフラインのデータ」 (design 6.12, mock screen 7): the regions with
  * their state, size and date and a ︙ menu (更新, 名前を変更, 削除), then
  * the offline basemap and the venue data, and the automatic updates.
  */
final class OfflineDataScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "オフラインのデータ"
  private val list = vbox()
  val view: View = scroll(list)

  /** Some work of the downloads is queued or running (asked on each refresh). */
  @volatile private var workActive = false
  private var stops = Seq.empty[() => Unit]

  override def onShow(): Unit =
    stops = Seq(Regions.listen(() => refresh()), BasemapDownload.listen(() => refresh()), VenueDownload.listen(() => refresh()))
    refresh()
    // A newer catalog of an app update: its 新しい版 marks.
    if Regions.offered && Regions.state.regions.nonEmpty && Regions.catalog.isEmpty then
      RegionsUi.ensureCatalog(act)(_ => if act.showing(this) then refresh())
  override def onHide(): Unit =
    stops.foreach(_())
    stops = Seq.empty

  private def checkWork(): Unit =
    AshidoriApp.io.execute { () =>
      val active = scala.util.Try(androidx.work.WorkManager.getInstance(act).getWorkInfosForUniqueWork(RegionDownload.WorkName).get()
        .asScala.exists(i => !i.getState.isFinished)).getOrElse(false)
      if active != workActive then
        workActive = active
        act.runOnUiThread(() => if act.showing(this) then refresh())
    }

  private def refresh(): Unit =
    try show()
    catch case e: Exception =>
      Log.w("regions: list", e)
      built = None
      list.removeAllViews()
      list.addView(supportingText("読み込めません"))

  /** One row as shown: `line` and `bar` follow a download; the rest is
    * the row's shape (a change of it builds the list again). `menu`: a
    * region's ︙; `pinned`: which pinned row (its tap opens its dialog).
    */
  private final case class Line(mark: String, markColor: Int, name: String, chipText: Option[String],
                                line: String, error: Boolean, bar: Progress,
                                menu: Option[Region] = None, pinned: Option[String] = None):
    def shape: Line = copy(line = "", error = false, bar = Progress.Hidden)

  /** A row on screen and its views that follow the download. */
  private final class Built(val view: LinearLayout, val sub: TextView,
                            val bar: com.google.android.material.progressindicator.LinearProgressIndicator)

  /** The shape of the list on screen and its rows; progress only updates those in place. */
  private var built: Option[(Any, Seq[Built])] = None

  private def show(): Unit =
    checkWork()
    val s = Regions.state
    val add = Settings.gate.rule(Dest.Data).level == Level.Cell && Regions.offered
    val problem = Regions.book.broken.map(_ => "地域データを読み込めません").orElse(Regions.book.writeError.map(_ => "地域データを保存できません"))
    val regions = if Regions.loading then Seq.empty else s.regions.map(row)
    val pinned = Seq(
      pinnedRow("basemap", "オフライン地図 · 全域", BasemapDownload.status, Basemap.sizeLabel),
      pinnedRow("venues", "施設データ · 日本", VenueDownload.status, VenueData.sizeLabel))
    // Opening this screen may fetch the catalog (RegionsUi.ensureCatalog): where it goes.
    val host = Option.when(Regions.offered)(scala.util.Try(java.net.URI(Regions.url("v", "f")).getHost).toOption.getOrElse("github.com"))
    val auto = Option.when(Regions.offered)(if !Regions.autoUpdate then "オフ" else if Regions.metered then "モバイルデータ通信でも" else "Wi-Fi 接続時")
    val rows = regions ++ pinned
    val shape = (add, problem, Regions.loading, host, auto, rows.map(_.shape))
    built match
      case Some((was, views)) if was == shape => views.zip(rows).foreach(patch)
      case _ =>
        list.removeAllViews()
        if add then
          val b = button("＋ 範囲を選んで取得", tonal = true)(selectArea())
          val lp = matchWrap
          lp.setMargins(dp(16), dp(12), dp(16), 0)
          b.setLayoutParams(lp)
          list.addView(b)
        list.addView(sectionHeader("地域"))
        problem.foreach { p =>
          val t = supportingText(p)
          t.setTextColor(color(attr.error))
          list.addView(t)
        }
        val views = rows.map(build)
        if Regions.loading then list.addView(supportingText("読み込み中"))
        else if regions.isEmpty then list.addView(supportingText("なし"))
        else list.addView(listGroup(views.take(regions.size).map(_.view)*))
        host.foreach(h => list.addView(supportingText(s"更新の確認 · $h")))
        list.addView(sectionHeader("全域"))
        list.addView(listGroup(views.drop(regions.size).map(_.view)*))
        auto.foreach { line =>
          list.addView(spacer(12))
          list.addView(listGroup(navRow("自動更新", line)(autoUpdateDialog())))
        }
        list.addView(spacer(24))
        built = Some(shape -> views)

  private def selectArea(): Unit =
    if !Regions.allowed then act.push(OutboundDestScreen(act, Dest.Data))
    else act.push(RegionSelectScreen(act))

  /** A row of the basemap or the venue data, in the same form as a region. */
  private def pinnedRow(key: String, name: String, st: BasemapStatus, sizeLabel: String): Line =
    val (mark, markColor, line, error) = st match
      case BasemapStatus.Installed(_, _) => ("✓", attr.primary, sizeLabel, false)
      case BasemapStatus.Running(_, _, _) => ("↓", attr.onSurfaceVariant, DownloadBar.line(st).getOrElse(""), false)
      case BasemapStatus.Waiting(_) => ("↓", attr.onSurfaceVariant, "待機中", false)
      case f: BasemapStatus.Failed => ("!", attr.error, BasemapUi.failedText(f.failure), true)
      case _ => ("", attr.onSurfaceVariant, "未取得", false)
    Line(mark, markColor, name, None, line, error, DownloadBar.of(st), pinned = Some(key))

  /** One row: a status mark, the name with an optional mark, one line and
    * the bar of a download under it, an optional ︙.
    */
  private def build(l: Line): Built =
    val m = text(l.mark, 18f, bold = true)
    m.setTextColor(color(l.markColor))
    m.setGravity(Gravity.CENTER)
    m.setLayoutParams(LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
    m.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val title = oneLine(text(l.name, 16f))
    title.setLayoutParams(LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val head = hbox(title)
    head.setGravity(Gravity.CENTER_VERTICAL)
    l.chipText.foreach { c =>
      val ch = chip(c, color(attr.tertiaryContainer), color(attr.onTertiaryContainer))
      val lp = wrap
      lp.setMarginStart(dp(8))
      ch.setLayoutParams(lp)
      head.addView(ch)
    }
    val sub = tabular(oneLine(text("", 14f)))
    val bar = DownloadBar()
    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val blp = matchWrap
    blp.topMargin = dp(6)
    blp.bottomMargin = dp(2)
    bar.setLayoutParams(blp)
    val texts = vbox(head, sub, bar)
    texts.setLayoutParams(LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val r = hbox(m, texts)
    r.setGravity(Gravity.CENTER_VERTICAL)
    r.setPadding(dp(12), dp(8), dp(4), dp(8))
    r.setMinimumHeight(dp(72))
    l.menu match
      case Some(region) =>
        lazy val b: View = iconButton(R.drawable.ic_more_vert, s"${l.name} のメニュー")(menu(b, region))
        r.addView(b)
      case None => r.setPadding(dp(12), dp(8), dp(16), dp(8))
    l.pinned.foreach { key =>
      r.setOnClickListener(_ => if key == "venues" then VenueDataUi.showDialog(act) else BasemapUi.showDialog(act))
      ripple(r)
    }
    val b = Built(r, sub, bar)
    patch(b, l)
    b

  /** What follows the download: the line, its colour, the bar, the row's description. */
  private def patch(b: Built, l: Line): Unit =
    b.sub.setText(l.line)
    b.sub.setTextColor(color(if l.error then attr.error else attr.onSurfaceVariant))
    DownloadBar.show(b.bar, l.bar)
    val state = if l.mark == "✓" then "取得済み" else if l.mark == "↓" then "取得中" else if l.mark == "!" then "失敗" else ""
    b.view.setContentDescription(Seq(l.name, l.chipText.getOrElse(""), state, l.line).filter(_.nonEmpty).mkString("、"))

  private def row(r: Region): Line =
    val s = Regions.state
    val st = Regions.status(r)
    val bytes = RegionsUi.size(RegionStatus.bytes(r, s))
    val dated = RegionStatus.date(r, s).map(RegionsUi.date)
    // The run works on it now: the bar (before why it waited last).
    val going: String => Option[(String, Progress)] = verb => Regions.activity(r) match
      case RegionProgress.Idle => None
      case RegionProgress.Checking => Some("照合中" -> Progress.Busy)
      case a @ RegionProgress.Bytes(d, t, _) => Some(s"$verb · ${DownloadBar.bytes(d, t)}" -> DownloadBar.of(a))
      case a => Some(verb -> DownloadBar.of(a))
    def line(mark: String, markColor: Int, l: String, error: Boolean, bar: Progress = Progress.Hidden, chipText: Option[String] = None) =
      Line(mark, markColor, r.name, chipText, l, error, bar, menu = Some(r))
    st match
      case RegionStatus.Ready(newer) =>
        line("✓", attr.primary, (Seq(bytes) ++ dated).mkString(" · "), false, chipText = Option.when(newer)("新しい版"))
      case RegionStatus.Downloading(_, _) =>
        going("取得中") match
          case Some((l, bar)) => line("↓", attr.onSurfaceVariant, l, false, bar)
          case None =>
            val l = Regions.waiting.getOrElse(if !workActive then "中断" else if Regions.metered then "待機中" else "Wi-Fi 接続待ち")
            line("↓", attr.onSurfaceVariant, l, Regions.waiting.nonEmpty)
      case RegionStatus.Failed(reason) => line("!", attr.error, reason, true)
      case RegionStatus.Unusable => line("!", attr.error, "使用不可（スタイル未取得）", true)
      // The old version stays drawn: ✓, and the update's state.
      case RegionStatus.Updating(_, _) =>
        going("更新中") match
          case Some((l, bar)) => line("✓", attr.primary, l, false, bar)
          case None =>
            val l = Regions.waiting.map("更新待ち · " + _).getOrElse(
              if !workActive then "更新を中断" else if Regions.metered then "更新待ち" else "更新待ち · Wi-Fi 接続待ち")
            line("✓", attr.primary, l, Regions.waiting.nonEmpty)
      case RegionStatus.UpdateFailed(reason) => line("✓", attr.primary, s"更新に失敗（$reason）", true)

  /** The ︙ of a region, for its state when it is opened. */
  private def menu(anchor: View, r: Region): Unit =
    val st = Regions.status(r)
    val p = PopupMenu(act, anchor)
    val m = p.getMenu
    val newer = st == RegionStatus.Ready(true)
    st match
      case RegionStatus.Failed(_) | RegionStatus.Unusable | RegionStatus.UpdateFailed(_) => m.add(0, 4, 0, "再取得")
      case RegionStatus.Downloading(_, _) | RegionStatus.Updating(_, _) if !workActive => m.add(0, 4, 0, "再開")
      case _ => ()
    m.add(0, 1, 1, "更新").setEnabled(newer && Regions.allowed)
    m.add(0, 2, 2, "名前を変更")
    m.add(0, 3, 3, "削除")
    p.setOnMenuItemClickListener { item =>
      item.getItemId match
        case 1 => Regions.catalog.foreach(c => saved(Regions.planUpdate(r.id, c))(RegionDownload.start(act)))
        case 2 => rename(r)
        case 3 => confirmDelete(r)
        case 4 =>
          if !Regions.allowed then act.push(OutboundDestScreen(act, Dest.Data))
          else saved(Regions.retry(r.id))(RegionDownload.start(act))
      true
    }
    p.show()

  /** Runs the change in the background (it writes the state), then
    * `andThen` on the main thread if it was written; else says it was not.
    */
  private def saved[A](change: => Either[String, A])(andThen: => Unit): Unit =
    AshidoriApp.io.execute { () =>
      val r = try change catch case e: Exception => Left(e.toString)
      act.runOnUiThread { () =>
        r match
          case Right(_) => andThen
          case Left(_) => Toast.makeText(act, "保存できませんでした", Toast.LENGTH_SHORT).show()
      }
    }

  private def rename(r: Region): Unit =
    val e = EditText(act)
    e.setText(r.name)
    e.setSingleLine(true)
    e.setSelectAllOnFocus(true)
    val box = frame(e)
    box.setPadding(dp(20), dp(8), dp(20), 0)
    MaterialAlertDialogBuilder(act)
      .setTitle("名前を変更")
      .setView(box)
      .setPositiveButton("変更", (_, _) => {
        val n = e.getText.toString.trim
        if n.nonEmpty then saved(Regions.rename(r.id, n.take(40)))(())
      })
      .setNegativeButton("キャンセル", null)
      .show()

  private def confirmDelete(r: Region): Unit =
    val s = Regions.state
    val own = s.own(r.id)
    val shared = r.cells.toSet -- own
    val ownBytes = own.toSeq.flatMap(s.packs.get).map(p => p.installed.map(_.bytes).getOrElse(0L)).sum
    val line =
      if own.isEmpty then "区画はほかの地域と共有のため残す"
      else s"区画 ${own.size} 個（${RegionsUi.size(ownBytes)}）を削除" + (if shared.nonEmpty then s"・共有の ${shared.size} 個は残す" else "")
    MaterialAlertDialogBuilder(act)
      .setTitle(s"「${r.name}」を削除しますか")
      .setMessage(line)
      .setPositiveButton("削除", (_, _) => saved(Regions.delete(r.id))(()))
      .setNegativeButton("キャンセル", null)
      .show()

  private def autoUpdateDialog(): Unit =
    val auto = switchRow("自動更新", Regions.autoUpdate)(b => Regions.autoUpdate = b)
    val metered = switchRow("モバイルデータ通信でも取得", Regions.metered) { b =>
      Regions.metered = b
      RegionDownload.networkChanged(act)
    }
    MaterialAlertDialogBuilder(act)
      .setTitle("自動更新")
      .setView(vbox(auto, metered))
      .setPositiveButton("閉じる", (_, _) => refresh())
      .setOnDismissListener(_ => refresh())
      .show()

/** 「この範囲の地図を取得」 (design 6.11, mock screen 6): the offline map
  * under a fixed frame; the zoom-8 cells it touches are what is downloaded,
  * outlined on the map. One line gives their size and the free space, one
  * what the source learns; 自動更新 is chosen here.
  */
final class RegionSelectScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "この範囲の地図を取得"

  private val sizeLine = tabular(oneLine(text(" ", 18f, bold = true)))
  private val sendLine = tabular(oneLine(caption(" ")))
  private val auto = switchRow("Wi-Fi 接続時に自動更新", Regions.autoUpdate)(_ => ())
  auto.setVisibility(if Regions.automatic then View.VISIBLE else View.GONE)
  private val download = button("ダウンロード")(confirm())
  private val cancel = outlined("キャンセル")(act.pop())
  download.setEnabled(false)

  private val mapHost = FrameLayout(act)
  /** The fixed frame: the map moves under it. */
  private val frameView =
    val v = View(act)
    val d = GradientDrawable()
    d.setStroke(dp(3), color(attr.primary))
    d.setCornerRadius(dp(4).toFloat)
    d.setColor(Color.TRANSPARENT)
    v.setBackground(d)
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    v

  val view: View =
    val buttons = hbox(cancel, download)
    buttons.setGravity(Gravity.END)
    val blp = wrap
    blp.setMarginStart(dp(8))
    download.setLayoutParams(blp)
    val panel = vbox(padded(vbox(sizeLine, sendLine), 16, 8), auto, padded(buttons, 16, 8))
    panel.setBackgroundColor(color(attr.surface))
    mapHost.addView(frameView, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
    val root = vbox(mapHost, panel)
    mapHost.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root

  private var mapView: Option[MapView] = None
  private var map: Option[MapLibreMap] = None
  private var cellsNow = Seq.empty[Cell]
  private var fetching = false
  private var fetchError: Option[String] = None

  private val lifecycle = new DefaultLifecycleObserver:
    override def onStart(o: LifecycleOwner): Unit = mapView.foreach(_.onStart())
    override def onResume(o: LifecycleOwner): Unit = mapView.foreach(_.onResume())
    override def onPause(o: LifecycleOwner): Unit = mapView.foreach(_.onPause())
    override def onStop(o: LifecycleOwner): Unit = mapView.foreach(_.onStop())

  /** Moves the map under the frame (debug builds: DebugReceiver CAMERA). */
  def moveTo(p: LatLng, zoom: Double): Unit =
    map.foreach(_.moveCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), zoom)))

  override def onShow(): Unit =
    RegionSelectScreen.shown = Some(this)
    MapLibre.getInstance(act)
    Net.mapClient(act)
    val mv = MapView(act)
    mv.onCreate(null)
    mapHost.addView(mv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    mapView = Some(mv)
    mv.onStart()
    mv.onResume()
    act.getLifecycle.addObserver(lifecycle)
    // The frame: most of the map, clear of its edges.
    mapHost.addOnLayoutChangeListener((_, l, t, r, b, _, _, _, _) => {
      val lp = frameView.getLayoutParams
      val (w, h) = ((r - l) * 72 / 100, (b - t) * 56 / 100)
      if lp.width != w || lp.height != h then
        lp.width = w
        lp.height = h
        frameView.post(() => { frameView.setLayoutParams(lp); update() })
    })
    mv.getMapAsync { m =>
      map = Some(m)
      m.getUiSettings.setRotateGesturesEnabled(false)
      m.getUiSettings.setTiltGesturesEnabled(false)
      m.getUiSettings.setAttributionEnabled(false)
      m.getUiSettings.setLogoEnabled(false)
      val (c, z) = TrackMap.all.headOption.flatMap(_.camera).map((p, z) => (p, math.min(z, 9.0))).getOrElse((LatLng(36.0, 138.5), 5.5))
      m.setCameraPosition(CameraPosition.Builder().target(MLatLng(c.lat, c.lon)).zoom(z).build())
      m.setMinZoomPreference(4.0)
      loadStyle(m)
      m.addOnCameraMoveListener(() => update())
      m.addOnCameraIdleListener { () =>
        update()
        // More region packs placed than one style draws: the style follows the view.
        val v = m.getProjection.getVisibleRegion.latLngBounds
        Regions.view(this, Bounds(v.getLonWest, v.getLatSouth, v.getLonEast, v.getLatNorth))
        if Regions.stale(this) then loadStyle(m)
      }
    }
    if Regions.catalog.isEmpty then fetchCatalog()
    update()


  /** The offline map (and placed packs around the view): this screen sends no area anywhere. */
  private def loadStyle(m: MapLibreMap): Unit =
    val json = MapStyle.Offline.styleJson("regions", this, None).get
    m.setStyle(Style.Builder().fromJson(json), (st: Style) => {
      st.addSource(GeoJsonSource("cells"))
      st.addLayer(FillLayer("cells-fill", "cells").withProperties(
        PropertyFactory.fillColor(Expression.get("color")),
        PropertyFactory.fillOpacity(Expression.get("opacity"))))
      st.addLayer(LineLayer("cells-line", "cells").withProperties(
        PropertyFactory.lineColor("#3c4043"), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.7f)))
      update()
    })

  override def onHide(): Unit =
    RegionSelectScreen.shown = None
    act.getLifecycle.removeObserver(lifecycle)
    map = None
    mapView.foreach { mv =>
      mapHost.removeView(mv)
      mv.onPause()
      mv.onStop()
      mv.onDestroy()
    }
    mapView = None
    Regions.release(this)

  /** The catalog (no position in the request), under a permit of the
    * user's action, on the network the downloads may use: on a metered one
    * only with 「モバイルデータ通信でも取得」.
    */
  private def fetchCatalog(): Unit =
    fetching = true
    RegionsUi.ensureCatalog(act) { e =>
      fetching = false
      fetchError = e
      update()
    }

  /** The frame's box on the map now. */
  private def frameBounds(m: MapLibreMap): Option[Bounds] =
    if frameView.getWidth == 0 then None
    else
      val p = m.getProjection
      val x0 = frameView.getLeft.toFloat
      val y0 = frameView.getTop.toFloat
      val nw = p.fromScreenLocation(android.graphics.PointF(x0, y0))
      val se = p.fromScreenLocation(android.graphics.PointF(x0 + frameView.getWidth, y0 + frameView.getHeight))
      Some(Bounds(nw.getLongitude, se.getLatitude, se.getLongitude, nw.getLatitude))

  /** Free space and what a new reservation may take, read in the background. */
  @volatile private var space: Option[(Long, Long)] = None
  @volatile private var spaceAt = 0L
  private def askSpace(): Unit =
    val now = System.currentTimeMillis()
    if now - spaceAt > 1000 then
      spaceAt = now
      AshidoriApp.io.execute { () =>
        val s = scala.util.Try((Storage.free(), Storage.budget.available)).toOption
        act.runOnUiThread { () =>
          space = s.orElse(space)
          if act.showing(this) then update()
        }
      }

  private def update(): Unit =
    try show()
    catch case e: Exception =>
      Log.w("regions: selection", e)
      sizeLine.setText("読み込めません")
      download.setEnabled(false)

  private def show(): Unit =
    askSpace()
    val cat = Regions.catalog
    val s = Regions.state
    val cells = map.flatMap(frameBounds).map(Cell.covering).getOrElse(Seq.empty)
    cellsNow = cells
    // The cells on the map: those under the frame, those placed, and a grid around.
    for m <- map; st <- Option(m.getStyle) if st.isFullyLoaded; src <- Option(st.getSourceAs[GeoJsonSource]("cells")) do
      val vis = m.getProjection.getVisibleRegion.latLngBounds
      val around = Cell.covering(Bounds(vis.getLonWest, vis.getLatSouth, vis.getLonEast, vis.getLatNorth))
      val shown = if around.size > 600 then cells else around
      val primary = f"#${color(attr.primary) & 0xffffff}%06x"
      val tertiary = f"#${color(attr.tertiary) & 0xffffff}%06x"
      val fs = shown.map { c =>
        val b = c.bounds
        val ring = Seq((b.west, b.south), (b.east, b.south), (b.east, b.north), (b.west, b.north), (b.west, b.south))
          .map((x, y) => Point.fromLngLat(x, y))
        val f = Feature.fromGeometry(Polygon.fromLngLats(java.util.List.of(ring.asJava)))
        val known = cat.forall(_.cells.contains(c))
        val (col, op) =
          if cells.contains(c) then (if known then (primary, 0.22) else ("#9e9e9e", 0.45))
          else if s.packs.get(c).exists(_.installed.nonEmpty) then (tertiary, 0.12)
          else ("#000000", 0.0)
        f.addStringProperty("color", col)
        f.addNumberProperty("opacity", op)
        f
      }
      src.setGeoJson(FeatureCollection.fromFeatures(fs.asJava))
    cat match
      case None =>
        sizeLine.setText(if fetching then "カタログを取得中…" else fetchError.getOrElse("カタログなし"))
        sendLine.setText(s"${RegionsUi.source} に伝わる範囲: なし")
        download.setEnabled(false)
      case Some(c) =>
        val e = RegionEstimate(cells, s, c, Regions.styles)
        val known = cells.filter(c.cells.contains)
        val (line, ok) =
          if cells.size > Regions.MaxCells then ("範囲を狭めてください", false)
          else if known.isEmpty then ("この範囲の地図はありません", false)
          else space match
            case None => (s"${RegionsUi.size(e.bytes)} · 空き容量を確認中", false)
            case Some((_, available)) if e.bytes > available => ("空き容量が不足", false)
            case Some((free, _)) => (s"${RegionsUi.size(e.bytes)} · 空き ${RegionsUi.size(free)}", true)
        sizeLine.setText(line)
        sizeLine.setTextColor(color(if ok then attr.onSurface else attr.error))
        sendLine.setText(s"${RegionsUi.source} に伝わる範囲: 区画 ${e.fetch.size} 個")
        download.setEnabled(ok)

  private def confirm(): Unit =
    for c <- Regions.catalog; m <- map do
      val cells = cellsNow.filter(c.cells.contains)
      if cells.nonEmpty && cells.size <= Regions.MaxCells then
        val center = m.getCameraPosition.target
        if Regions.automatic then Regions.autoUpdate = auto.switch.isChecked
        download.setEnabled(false)
        AshidoriApp.io.execute { () =>
          val name = Regions.defaultName(center.getLatitude, center.getLongitude)
          // The state is written here, off the main thread.
          val added = try Regions.add(name, cells, c) catch case e: Exception => Left(e.toString)
          act.runOnUiThread { () =>
            added match
              case Right(_) => RegionDownload.start(act)
              case Left(_) => Toast.makeText(act, "保存できませんでした", Toast.LENGTH_SHORT).show()
            act.pop()
            if !act.currentScreen.exists(_.isInstanceOf[OfflineDataScreen]) then act.push(OfflineDataScreen(act))
          }
        }

object RegionSelectScreen:
  /** The selection on screen, if any. */
  @volatile var shown: Option[RegionSelectScreen] = None
