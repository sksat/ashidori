package net.sksat.ashidori.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{EditText, FrameLayout, LinearLayout, PopupMenu, TextView}
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
    checkWork()
    list.removeAllViews()
    val s = Regions.state
    val add = Settings.gate.rule(Dest.Data).level == Level.Cell && Regions.offered
    if add then
      val b = button("＋ 範囲を選んで取得", tonal = true)(selectArea())
      val lp = matchWrap
      lp.setMargins(dp(16), dp(12), dp(16), 0)
      b.setLayoutParams(lp)
      list.addView(b)
    list.addView(sectionHeader("地域"))
    if s.regions.isEmpty then list.addView(supportingText("なし"))
    else list.addView(listGroup(s.regions.map(row)*))
    list.addView(sectionHeader("全域"))
    list.addView(listGroup(pinnedRow("オフライン地図 · 全域", BasemapDownload.status, Basemap.sizeLabel)(BasemapUi.showDialog(act)),
      pinnedRow("施設データ · 日本", VenueDownload.status, VenueData.sizeLabel)(VenueDataUi.showDialog(act))))
    if Regions.offered then
      val line = if !Regions.autoUpdate then "オフ" else if Regions.metered then "モバイルデータ通信でも" else "Wi-Fi 接続時"
      list.addView(spacer(12))
      list.addView(listGroup(navRow("自動更新", line)(autoUpdateDialog())))
    list.addView(spacer(24))

  private def selectArea(): Unit =
    if !Regions.allowed then act.push(OutboundDestScreen(act, Dest.Data))
    else act.push(RegionSelectScreen(act))

  /** A row of the basemap or the venue data, in the same form as a region. */
  private def pinnedRow(name: String, st: BasemapStatus, sizeLabel: String)(onTap: => Unit): View =
    val (mark, markColor, line, error) = st match
      case BasemapStatus.Installed(_, _) => ("✓", attr.primary, sizeLabel, false)
      case BasemapStatus.Running(d, t, _) => ("↓", attr.onSurfaceVariant, s"取得中 · ${BasemapUi.percent(d, t)}%", false)
      case BasemapStatus.Waiting(_) => ("↓", attr.onSurfaceVariant, "待機中", false)
      case f: BasemapStatus.Failed => ("!", attr.error, BasemapUi.failedText(f.failure), true)
      case _ => ("", attr.onSurfaceVariant, "未取得", false)
    val r = regionLine(mark, markColor, name, None, line, error, None)
    r.setOnClickListener(_ => onTap)
    ripple(r)
    r

  /** One row: a status mark, the name with an optional mark, one line, an optional ︙. */
  private def regionLine(mark: String, markColor: Int, name: String, chipText: Option[String], line: String,
                         error: Boolean, menu: Option[View => Unit]): LinearLayout =
    val m = text(mark, 18f, bold = true)
    m.setTextColor(color(markColor))
    m.setGravity(Gravity.CENTER)
    m.setLayoutParams(LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
    m.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val title = oneLine(text(name, 16f))
    title.setLayoutParams(LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val head = hbox(title)
    head.setGravity(Gravity.CENTER_VERTICAL)
    chipText.foreach { c =>
      val ch = chip(c, color(attr.tertiaryContainer), color(attr.onTertiaryContainer))
      val lp = wrap
      lp.setMarginStart(dp(8))
      ch.setLayoutParams(lp)
      head.addView(ch)
    }
    val sub = tabular(oneLine(text(line, 14f, colorAttr = if error then attr.error else attr.onSurfaceVariant)))
    val texts = vbox(head, sub)
    texts.setLayoutParams(LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val r = hbox(m, texts)
    r.setGravity(Gravity.CENTER_VERTICAL)
    r.setPadding(dp(12), dp(8), dp(4), dp(8))
    r.setMinimumHeight(dp(72))
    menu match
      case Some(f) =>
        lazy val b: View = iconButton(R.drawable.ic_more_vert, s"$name のメニュー")(f(b))
        r.addView(b)
      case None => r.setPadding(dp(12), dp(8), dp(16), dp(8))
    val state = if mark == "✓" then "取得済み" else if mark == "↓" then "取得中" else if mark == "!" then "失敗" else ""
    r.setContentDescription(Seq(name, chipText.getOrElse(""), state, line).filter(_.nonEmpty).mkString("、"))
    r

  private def row(r: Region): View =
    val s = Regions.state
    val st = Regions.status(r)
    val bytes = RegionsUi.size(RegionStatus.bytes(r, s))
    val dated = RegionStatus.date(r, s).map(RegionsUi.date)
    val (mark, markColor, line, error, chipText) = st match
      case RegionStatus.Ready(newer) =>
        ("✓", attr.primary, (Seq(bytes) ++ dated).mkString(" · "), false, Option.when(newer)("新しい版"))
      case RegionStatus.Downloading(done, total) =>
        val l =
          if Regions.progress.keySet.exists(r.cells.contains) then s"取得中 · ${if total > 0 then done * 100 / total else 0}%"
          else Regions.waiting.getOrElse(if !workActive then "中断" else if Regions.metered then "待機中" else "Wi-Fi 接続待ち")
        ("↓", attr.onSurfaceVariant, l, Regions.waiting.nonEmpty, None)
      case RegionStatus.Failed(reason) => ("!", attr.error, reason, true, None)
      case RegionStatus.Unusable => ("!", attr.error, "使用不可（スタイル未取得）", true, None)
    regionLine(mark, markColor, r.name, chipText, line, error, Some(anchor => menu(anchor, r, st)))

  private def menu(anchor: View, r: Region, st: RegionStatus): Unit =
    val p = PopupMenu(act, anchor)
    val m = p.getMenu
    val newer = st == RegionStatus.Ready(true)
    st match
      case RegionStatus.Failed(_) | RegionStatus.Unusable => m.add(0, 4, 0, "再取得")
      case RegionStatus.Downloading(_, _) if !workActive => m.add(0, 4, 0, "再開")
      case _ => ()
    m.add(0, 1, 1, "更新").setEnabled(newer && Regions.allowed)
    m.add(0, 2, 2, "名前を変更")
    m.add(0, 3, 3, "削除")
    p.setOnMenuItemClickListener { item =>
      item.getItemId match
        case 1 => Regions.catalog.foreach { c => Regions.planUpdate(r.id, c); RegionDownload.start(act) }
        case 2 => rename(r)
        case 3 => confirmDelete(r)
        case 4 =>
          if !Regions.allowed then act.push(OutboundDestScreen(act, Dest.Data))
          else
            Regions.retry(r.id)
            RegionDownload.start(act)
      true
    }
    p.show()

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
        if n.nonEmpty then Regions.rename(r.id, n.take(40))
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
      .setPositiveButton("削除", (_, _) => Regions.delete(r.id))
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
      // The offline map (and placed packs): this screen sends no area anywhere.
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
      m.addOnCameraMoveListener(() => update())
      m.addOnCameraIdleListener(() => update())
    }
    if Regions.catalog.isEmpty then fetchCatalog()
    update()

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

  /** The catalog (no position in the request), under a permit of the user's action. */
  private def fetchCatalog(): Unit =
    Settings.gate.permit(Dest.Data, Mode.OnAction).filter(_.level == Level.Cell).foreach { p =>
      fetching = true
      AshidoriApp.io.execute { () =>
        val r = scala.util.Try(Regions.fetchCatalog(p))
        r.failed.foreach(e => Log.w("regions: catalog", e))
        act.runOnUiThread { () =>
          fetching = false
          fetchError = r.failed.toOption.map(_ => "カタログを取得できません")
          update()
        }
      }
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

  private def update(): Unit =
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
    val free = RegionDownload.free()
    cat match
      case None =>
        sizeLine.setText(if fetching then "カタログを取得中…" else fetchError.getOrElse("カタログなし"))
        sendLine.setText(s"${RegionsUi.source} に伝わる範囲: なし")
        download.setEnabled(false)
      case Some(c) =>
        val e = RegionEstimate(cells, s, c, Regions.styles)
        val known = cells.filter(c.cells.contains)
        val fits = e.bytes <= RegionDownload.budget.available
        val (line, ok) =
          if cells.size > Regions.MaxCells then ("範囲を狭めてください", false)
          else if known.isEmpty then ("この範囲の地図はありません", false)
          else if !fits then ("空き容量が不足", false)
          else (s"${RegionsUi.size(e.bytes)} · 空き ${RegionsUi.size(free)}", true)
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
          act.runOnUiThread { () =>
            Regions.add(name, cells, c)
            RegionDownload.start(act)
            act.pop()
            if !act.currentScreen.exists(_.isInstanceOf[OfflineDataScreen]) then act.push(OfflineDataScreen(act))
          }
        }

object RegionSelectScreen:
  /** The selection on screen, if any. */
  @volatile var shown: Option[RegionSelectScreen] = None
