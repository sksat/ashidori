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
  override def onShow(): Unit =
    unobserve()
    unobserve = PeerStatus.observe(() => render())
    render()
  override def onHide(): Unit =
    unobserve()
    snapshotter.foreach(_.cancel())
    snapshotter = None

  // Label/value rows, as in the status card of the ステータス tab.
  private def row(label: String, value: View): View =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
    value.setLayoutParams(weighted())
    val h = hbox(l, value)
    h.setPadding(0, dp(3), 0, dp(3))
    h
  private def row(label: String, value: String): View = row(label, text(value, 15f))

  private def render(): Unit =
    body.removeAllViews()
    PeerStatus.entries.get(deviceId) match
      case None => body.addView(card(caption("状態を受信していません")))
      case Some(e) =>
        val s = e.status
        val now = System.currentTimeMillis()
        val color = if s.recording then ModeInfo.color(PeerFormat.mode(s)) else 0xff757575.toInt
        s.lastFix.foreach(f => body.addView(map(f, color)))

        val updated = text(s"${PeerFormat.time(s.t)}（${PeerFormat.ago(s.t, now)}）", 15f)
        if now - s.t > PeerFormat.StaleMs then updated.setTextColor(Ui.color(attr.error))
        val rec = text("", 15f)
        PeerFormat.recording(s, rec)
        if s.recording && s.since > 0 then rec.append(s"・${PeerFormat.time(s.since)} から")
        val bat = text("", 15f)
        PeerFormat.battery(s, bat)
        body.addView(card(
          row("更新", updated),
          row("記録", rec),
          row("電池", bat)
        ))

        s.lastFix.foreach { f =>
          val acc = if f.acc.isNaN then "" else f"・±${f.acc}%.0f m"
          body.addView(card(
            text("位置", 16f, bold = true),
            spacer(4),
            row("場所", if e.area.nonEmpty then e.area else "-"),
            row("座標", PeerFormat.coords(f) + acc),
            row("測位", s"${PeerFormat.time(f.t)}（${PeerFormat.ago(f.t, now)}）"),
            spacer(4),
            outlined("地図アプリで開く") {
              val uri = Uri.parse(s"geo:${f.lat},${f.lon}?q=${f.lat},${f.lon}(${Uri.encode(title)})")
              try act.startActivity(Intent(Intent.ACTION_VIEW, uri))
              catch case _: android.content.ActivityNotFoundException => ()
            }
          ))
        }

        body.addView(card(
          text("端末", 16f, bold = true),
          spacer(4),
          row("機種", if s.model.nonEmpty then s.model else "-"),
          row("アプリ", if s.versionName.nonEmpty then s"${s.versionName}（${s.versionCode}）" else "-"),
          row("受信", PeerFormat.time(e.fetchedAt))
        ))
    body.addView(spacer(24))

  /** A still map around the position: accuracy circle and a dot in the
    * recording mode's color. Without tiles (offline) it stays blank.
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
    val dLat = half / 111_320.0
    val dLon = half / (111_320.0 * math.cos(math.toRadians(f.lat)))
    val bounds = LatLngBounds.Builder()
      .include(MLatLng(f.lat - dLat, f.lon - dLon))
      .include(MLatLng(f.lat + dLat, f.lon + dLon))
      .build()
    var builder = MapStyle.current.builder
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
    snapshotter.foreach(_.cancel())
    val snap = MapSnapshotter(act, opts)
    snapshotter = Some(snap)
    snap.start(
      s => img.setImageBitmap(s.getBitmap),
      (msg: String) => Log.w(s"device map: $msg")
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
