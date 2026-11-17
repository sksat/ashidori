package net.sksat.ashidori.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.{View, ViewGroup}
import android.widget.{FrameLayout, ImageView, LinearLayout}
import org.maplibre.android.geometry.{LatLng as MLatLng, LatLngBounds}
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.{CircleLayer, FillLayer, LineLayer, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Point, Polygon}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.*
import ashidori.core.*
import ashidori.core.sync.*
import ashidori.core.BaseMapPolicy.Outcome
import Ui.*

/** Another device of the user: its last position with the accuracy circle,
  * and the rest of its latest status. Opened from the 端末 section.
  */
final class DeviceScreen(act: MainActivity, deviceId: String) extends Screen:
  private given Ctx = act
  override def title: String = PeerStatus.devices.find(_.id == deviceId).fold("端末")(_.name)

  private val body = vbox()
  val view: View = scroll(body)
  /** A running snapshotter must stay referenced until it calls back. */
  private var snapshotter: Option[MapSnapshotter] = None

  private var unobserve: () => Unit = () => ()
  private var unwatch: () => Unit = () => ()
  private var unwatchBasemap: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve()
    unobserve = PeerStatus.observe(() => render())
    unwatch()
    // A change of 「位置情報を外部サービスに送らない」 cancels the map and
    // renders it again under the new setting.
    unwatch = Settings.gate.onChange(() => act.runOnUiThread(() => { stopMap(); render() }))
    unwatchBasemap()
    // Installed or removed: draw the map with or without it.
    var version = Basemap.installedVersion
    unwatchBasemap = BasemapDownload.listen { () =>
      if Basemap.installedVersion != version then
        version = Basemap.installedVersion
        stopMap()
        render()
    }
    render()
  override def onHide(): Unit =
    unobserve()
    unwatch()
    unwatch = () => ()
    unwatchBasemap()
    unwatchBasemap = () => ()
    stopMap()

  private def stopMap(): Unit =
    snapshotter.foreach { s => s.cancel(); MapStyle.untrack(s) }
    snapshotter = None

  // Label/value rows, as in the status card of the ステータス tab.
  private def row(label: String, value: View): View =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
    value.setLayoutParams(weighted())
    value match
      case t: android.widget.TextView => tabular(oneLine(t))
      case _ => ()
    val h = hbox(l, value)
    h.setPadding(0, dp(3), 0, dp(3))
    h
  private def row(label: String, value: String): View = row(label, text(value, 15f))

  private def render(): Unit =
    body.removeAllViews()
    PeerStatus.problem(deviceId).foreach(u => body.addView(card(PeerFormat.problem(u),
      caption("最新の状態を読み取れません。以下は前回の状態です。"))))
    PeerStatus.entries.get(deviceId) match
      case None => body.addView(card(caption("状態は未受信")))
      case Some(e) =>
        val s = e.status
        val now = System.currentTimeMillis()
        val color = if s.recording then ModeInfo.color(PeerFormat.mode(s)) else 0xff757575.toInt
        // The map's room stays without a position, so a status with one
        // (or without) does not move the cards below.
        body.addView(s.lastFix.fold(noMap())(f => map(f, color)))

        val updated = text(s"${PeerFormat.time(s.t)}（${PeerFormat.ago(s.t, now)}）", 15f)
        if now - s.t > PeerFormat.StaleMs then updated.setTextColor(Ui.color(attr.error))
        val rec = text("", 15f)
        PeerFormat.recording(s, rec)
        if s.recording && s.since > 0 then rec.append(s"・${PeerFormat.time(s.since)} から")
        val bat = text("", 15f)
        PeerFormat.battery(s, bat, missing = "—")
        body.addView(card(
          row("更新", updated),
          row("記録", rec),
          row("電池", bat)
        ))

        // The same rows with or without a position ("—").
        val f = s.lastFix
        val open = outlined("地図アプリで開く") {
          f.foreach { f =>
            val uri = Uri.parse(s"geo:${f.lat},${f.lon}?q=${f.lat},${f.lon}(${Uri.encode(title)})")
            try act.startActivity(Intent(Intent.ACTION_VIEW, uri))
            catch case _: android.content.ActivityNotFoundException => ()
          }
        }
        open.setEnabled(f.isDefined)
        body.addView(card(
          text("位置", 16f, bold = true),
          spacer(4),
          row("場所", if f.isDefined && e.area.nonEmpty then e.area else "—"),
          row("座標", f.fold("—")(f => PeerFormat.coords(f) + (if f.acc.isNaN then "" else f"・±${f.acc}%.0f m"))),
          row("測位", f.fold("—")(f => s"${PeerFormat.time(f.t)}（${PeerFormat.ago(f.t, now)}）")),
          spacer(4),
          open
        ))

        body.addView(card(
          text("端末", 16f, bold = true),
          spacer(4),
          row("機種", if s.model.nonEmpty then s.model else "—"),
          row("アプリ", if s.versionName.nonEmpty then s"${s.versionName}（${s.versionCode}）" else "—"),
          row("受信", PeerFormat.time(e.fetchedAt))
        ))
    body.addView(spacer(24))

  /** The map's frame without a position: same size, marked 「位置なし」. */
  private def noMap(): View =
    stopMap()
    val label = caption("位置なし")
    val frame = FrameLayout(act)
    frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      android.view.Gravity.CENTER))
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(16).toFloat)
    bg.setColor(color(attr.surfaceContainerHigh))
    frame.setBackground(bg)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    frame.setLayoutParams(lp)
    frame

  /** A still map around the position: accuracy circle and a dot in the
    * recording mode's color. Without tiles (offline) it stays blank.
    * Without a base map to draw ([[Outcome.NoBasemap]]) MapLibre is not run:
    * the circle and the dot are drawn at the same scale, marked 「地図なし」.
    */
  private def map(f: StatusFix, dotColor: Int): View =
    val dm = act.getResources.getDisplayMetrics
    val wPx = dm.widthPixels - dp(24)
    val hPx = dp(240)
    val img = ImageView(act)
    img.setScaleType(ImageView.ScaleType.CENTER_CROP)
    val frame = FrameLayout(act)
    frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(16).toFloat)
    bg.setColor(color(attr.surfaceContainerHigh))
    frame.setBackground(bg)
    frame.setClipToOutline(true)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx)
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    frame.setLayoutParams(lp)

    val acc = if f.acc.isNaN then 0.0 else f.acc
    val hex = f"#${dotColor & 0xffffff}%06x"
    // Show the circle with room around it; at least ~600 m across.
    val half = math.max(acc * 2.5, 300.0)
    // Another device's position: offline map while the setting is on.
    val style = MapStyle.forSnapshot match
      case Outcome.Draw(s) => s
      case Outcome.NoBasemap =>
        stopMap()
        frame.addView(PositionSketchView(act, acc / (2 * half), dotColor),
          FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val chip = DayMap.noMapChip(act)
        frame.addView(chip)
        chip.show(true)
        return frame
    val dLat = half / 111_320.0
    val dLon = half / (111_320.0 * math.cos(math.toRadians(f.lat)))
    val bounds = LatLngBounds.Builder()
      .include(MLatLng(f.lat - dLat, f.lon - dLon))
      .include(MLatLng(f.lat + dLat, f.lon + dLon))
      .build()
    var builder = style.snapshotBuilder
      .withSource(GeoJsonSource("peer-dot", Point.fromLngLat(f.lon, f.lat)))
    if acc > 0 then
      builder = builder
        .withSource(GeoJsonSource("peer-acc", circle(f.lat, f.lon, acc)))
        .withLayer(FillLayer("peer-acc-fill", "peer-acc").withProperties(
          PropertyFactory.fillColor(hex), PropertyFactory.fillOpacity(0.18f)))
        .withLayer(LineLayer("peer-acc-line", "peer-acc").withProperties(
          PropertyFactory.lineColor(hex), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.6f)))
    builder = builder.withLayer(CircleLayer("peer-dot-circle", "peer-dot").withProperties(
      PropertyFactory.circleColor(hex),
      PropertyFactory.circleRadius(8f),
      PropertyFactory.circleStrokeColor("#ffffff"),
      PropertyFactory.circleStrokeWidth(2.5f)
    ))
    val opts = MapSnapshotter.Options((wPx / dm.density).toInt, (hPx / dm.density).toInt)
      .withPixelRatio(dm.density)
      .withRegion(bounds)
      .withStyleBuilder(builder)
      .withLogo(false)
    stopMap()
    MapStyle.applyNetworkPolicy(act)
    val snap = MapSnapshotter(act, opts)
    snapshotter = Some(snap)
    MapStyle.track(snap)
    snap.start(
      s => { MapStyle.untrack(snap); img.setImageBitmap(s.getBitmap) },
      (msg: String) => { MapStyle.untrack(snap); Log.w(s"device map: $msg") }
    )
    frame

  /** A circle of `r` metres as a polygon, so it keeps its true size. */
  private def circle(lat: Double, lon: Double, r: Double): Polygon =
    val n = 48
    val pts = (0 to n).map { i =>
      val a = 2 * math.Pi * i / n
      Point.fromLngLat(
        lon + r * math.sin(a) / (111_320.0 * math.cos(math.toRadians(lat))),
        lat + r * math.cos(a) / 111_320.0)
    }
    Polygon.fromLngLats(java.util.List.of(pts.asJava))

/** The position without a map: the accuracy circle and the dot at the
  * center, at the scale the map would have. `accFraction` is the circle's
  * radius as a fraction of the shorter side (the map fits the same square).
  */
final class PositionSketchView(c: android.content.Context, accFraction: Double, dotColor: Int)
    extends View(c):
  private given android.content.Context = c
  private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  stroke.setStyle(android.graphics.Paint.Style.STROKE)
  private val grid = MapGrid.paint(c)

  override def onDraw(canvas: android.graphics.Canvas): Unit =
    // No base map (it is only drawn so): the same grid as the timeline's cards.
    MapGrid.draw(canvas, getWidth.toFloat, getHeight.toFloat, grid)
    val (cx, cy) = (getWidth / 2f, getHeight / 2f)
    val r = (accFraction * math.min(getWidth, getHeight)).toFloat
    if r > 0 then
      fill.setColor((dotColor & 0x00ffffff) | 0x2e000000)
      canvas.drawCircle(cx, cy, r, fill)
      stroke.setStrokeWidth(Ui.dp(1.5f).toFloat)
      stroke.setColor((dotColor & 0x00ffffff) | 0x99000000)
      canvas.drawCircle(cx, cy, r, stroke)
    fill.setColor(0xffffffff)
    canvas.drawCircle(cx, cy, Ui.dp(10).toFloat, fill)
    fill.setColor(dotColor | 0xff000000)
    canvas.drawCircle(cx, cy, Ui.dp(8).toFloat, fill)
