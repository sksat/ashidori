package net.sksat.ashidori.ui

import android.location.{Location, LocationManager}
import android.os.Bundle
import android.view.{Gravity, ViewGroup}
import android.widget.FrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.{LatLng as MLatLng, LatLngBounds}
import org.maplibre.android.maps.{MapLibreMap, MapView, Style}
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.{CircleLayer, LineLayer, Property, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Feature, FeatureCollection, LineString, Point}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import ashidori.core.*

/** A line on the map with its color (CSS hex). */
final case class MapPath(points: Seq[Fix], color: String = TrackMap.DefaultLine, dashed: Boolean = false)

/** A marker on the map; `key` comes back in click callbacks. */
/** `weight` scales the circle (e.g. number of visits). */
final case class MapMarker(at: LatLng, key: String, label: String = "", weight: Double = 1)

/** MapLibre map with layers for tracks, visits, check-ins and one highlighted
  * path. Data set before the style finishes loading is applied afterwards.
  *
  * With 「位置情報を外部サービスに送らない」 on, it shows the on-device map
  * until the user asks for the online one in this view ([[detailed]]): a
  * grant to this view alone ([[SendGate.grant]]), which ends when the view
  * leaves the screen ([[endDetail]]), switches back, or the setting changes.
  */
final class TrackMap(act: MainActivity, saved: Bundle, controlsOnTop: Boolean = false):
  MapLibre.getInstance(act)
  TrackMap.enlargeCache(act)
  val mapView: MapView = MapView(act)
  mapView.onCreate(saved)
  act.registerMap(this)
  TrackMap.all += this
  MapStyle.applyNetworkPolicy(act)

  /** The user asked for the online map in this view, and it is still on
    * screen and of the setting's current generation.
    */
  private def detailedFlag: Boolean = Settings.gate.granted(this)
  def detailed: Boolean = detailedFlag
  /** Base map loaded (or loading) on this map. */
  private var shown: Option[MapStyle] = None

  private var map: Option[(MapLibreMap, Style)] = None
  private var pending: Option[() => Unit] = None
  var onMarkerClick: (String, String) => Unit = (_, _) => ()
  private var myLocation: Option[Location] = None
  /** Pixels at the bottom covered by an overlay (e.g. a bottom sheet). */
  var bottomInset: Int = 0

  /** "Where am I" button. */
  private val locate =
    val b = FloatingActionButton(act)
    b.setImageResource(net.sksat.ashidori.R.drawable.ic_logger)
    b.setSize(FloatingActionButton.SIZE_MINI)
    b.setContentDescription("現在地へ移動")
    b.setOnClickListener(_ => goToMyLocation())
    b

  /** Base map switcher (standard / simple / GSI / aerial photo). */
  private val layers =
    val b = FloatingActionButton(act)
    b.setImageResource(net.sksat.ashidori.R.drawable.ic_layers)
    b.setSize(FloatingActionButton.SIZE_MINI)
    b.setContentDescription("地図の種類を変える")
    b.setOnClickListener(_ => pickBaseMap())
    b

  /** Explicit switch to the online map for this view (setting on only). */
  private val detailButton =
    val b = Ui.button("", tonal = true)(toggleDetailed())(using act)
    b.setTextSize(12f)
    b.setMinHeight(0)
    b.setMinimumHeight(0)
    b.setInsetTop(0)
    b.setInsetBottom(0)
    b.setPadding(Ui.dp(12)(using act), Ui.dp(6)(using act), Ui.dp(12)(using act), Ui.dp(6)(using act))
    b

  private def updateDetailButton(): Unit =
    detailButton.setVisibility(if Settings.blockExternalLocation then android.view.View.VISIBLE else android.view.View.GONE)
    detailButton.setText(
      if detailedFlag then s"簡易地図に戻す（${MapStyle.forView(true).label}を表示中）"
      else "この範囲を詳しい地図で表示（位置情報を送信）")
    // Sending is the exception: mark it with the tertiary (warning) colours.
    given Ui.Ctx = act
    if detailedFlag then Ui.tint(detailButton, Ui.color(Ui.attr.tertiaryContainer), Ui.color(Ui.attr.onTertiaryContainer))
    else Ui.tint(detailButton, Ui.color(Ui.attr.surfaceContainerHigh), Ui.color(Ui.attr.onSurface))

  private def toggleDetailed(): Unit =
    setDetailed(!detailedFlag)

  /** Switches this view between the offline and the chosen online map. */
  def setDetailed(on: Boolean): Unit =
    if on then Settings.gate.grant(this) else Settings.gate.revoke(this)
    MapStyle.applyNetworkPolicy(act)
    updateDetailButton()
    refreshBaseMap()

  /** The view left the screen (its screen was hidden or popped, or opens
    * other data): the detail grant ends, and the online style with its
    * requests is replaced by the on-device one. Shown again, it starts with
    * the offline map.
    */
  def endDetail(): Unit =
    if Settings.gate.granted(this) then setDetailed(false)

  /** Map plus overlay controls; screens add this instead of `mapView`. */
  val view: FrameLayout =
    val f = FrameLayout(act)
    f.addView(mapView)
    // Explicit wrap sizes: as default (match_parent) children of a wrapping
    // column they collapsed to the 1 px width of the spacer.
    val gap = Ui.dp(8)(using act)
    val wrapLp = () => android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    layers.setLayoutParams(wrapLp())
    val llp = wrapLp()
    llp.topMargin = gap
    locate.setLayoutParams(llp)
    val controls = Ui.vbox(layers, locate)(using act)
    // Top when something (the timeline's sheet) covers the bottom of the map.
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      (if controlsOnTop then Gravity.TOP else Gravity.BOTTOM) | Gravity.START)
    lp.setMargins(Ui.dp(12)(using act), Ui.dp(8)(using act), 0, Ui.dp(12)(using act))
    f.addView(controls, lp)
    val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      (if controlsOnTop then Gravity.TOP else Gravity.BOTTOM) | Gravity.END)
    dlp.setMargins(Ui.dp(76)(using act), Ui.dp(10)(using act), Ui.dp(12)(using act), Ui.dp(14)(using act))
    f.addView(detailButton, dlp)
    updateDetailButton()
    f

  // Follow the logger's fixes; the map never turns GNSS on by itself.
  LoggerState.observe(() => LoggerState.lastFix.foreach(f => updateMyLocation(TrackMap.toLocation(f))))

  private var clickInstalled = false
  /** Re-applies the latest data after a base map change. */
  private var lastData: Option[() => Unit] = None

  /** The MapLibre map, also while a style is loading (unlike [[map]]). */
  private var mlMap: Option[MapLibreMap] = None

  mapView.getMapAsync { m =>
    mlMap = Some(m)
    m.getUiSettings.setRotateGesturesEnabled(false)
    m.getUiSettings.setTiltGesturesEnabled(false)
    loadStyle(m, MapStyle.forView(detailedFlag))
  }

  /** Loads a base map and (re)adds our sources and layers on top of it. */
  private def loadStyle(m: MapLibreMap, ms: MapStyle): Unit =
    map = None
    shown = Some(ms)
    m.setStyle(ms.builder, (style: Style) => {
      for id <- Seq("track", "highlight", "visits", "checkins", "live") do style.addSource(GeoJsonSource(id))
      // From the end of the drawn route to where we are now, updated per fix.
      style.addLayer(
        LineLayer("live-line", "live").withProperties(
          PropertyFactory.lineColor(TrackMap.DefaultLine),
          PropertyFactory.lineWidth(4f),
          PropertyFactory.lineOpacity(0.9f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        )
      )
      style.addLayer(
        LineLayer("track-line", "track").withProperties(
          // Each path carries its transport mode color.
          PropertyFactory.lineColor(Expression.toColor(Expression.coalesce(Expression.get("color"), Expression.literal(TrackMap.DefaultLine)))),
          PropertyFactory.lineWidth(4f),
          PropertyFactory.lineOpacity(0.9f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(false)))
      )
      // Where the position was only roughly known (GNSS gap, poor accuracy).
      style.addLayer(
        LineLayer("track-dashed", "track").withProperties(
          PropertyFactory.lineColor(Expression.toColor(Expression.coalesce(Expression.get("color"), Expression.literal(TrackMap.DefaultLine)))),
          // Faded rather than dashed: line-dasharray did not render with
          // data-driven colors (seen on the emulator's GL).
          PropertyFactory.lineWidth(3f),
          PropertyFactory.lineOpacity(0.4f)
        ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(true)))
      )
      style.addLayer(
        LineLayer("highlight-line", "highlight").withProperties(
          PropertyFactory.lineColor("#e8710a"),
          PropertyFactory.lineWidth(7f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        )
      )
      style.addLayer(
        CircleLayer("visit-circles", "visits").withProperties(
          PropertyFactory.circleColor("#006B5B"),
          PropertyFactory.circleOpacity(0.85f),
          // More visits, bigger circle.
          PropertyFactory.circleRadius(
            Expression.interpolate(Expression.linear(), Expression.get("weight"),
              Expression.stop(1, 7f), Expression.stop(10, 12f), Expression.stop(60, 20f))
          ),
          PropertyFactory.circleStrokeColor("#ffffff"),
          PropertyFactory.circleStrokeWidth(2f)
        )
      )
      style.addLayer(
        CircleLayer("checkin-circles", "checkins").withProperties(
          PropertyFactory.circleColor("#9334e6"),
          PropertyFactory.circleRadius(6f),
          PropertyFactory.circleStrokeColor("#ffffff"),
          PropertyFactory.circleStrokeWidth(2f)
        )
      )
      if !clickInstalled then
        clickInstalled = true
        m.addOnMapClickListener { p =>
          val pt = m.getProjection.toScreenLocation(p)
          val hits = m.queryRenderedFeatures(pt, "visit-circles", "checkin-circles").asScala
          hits.headOption.exists { f =>
            onMarkerClick(f.getStringProperty("layer"), f.getStringProperty("key"))
            true
          }
        }
      enableMyLocation(m, style)
      map = Some((m, style))
      pending.foreach(_())
      pending = None
    })

  /** Reloads the base map the policy picks now (after a choice, a setting
    * change or a detail switch), keeping what is drawn. `force` reloads the
    * same base map (e.g. a new custom URL).
    */
  def refreshBaseMap(force: Boolean = false): Unit =
    updateDetailButton()
    val ms = MapStyle.forView(detailedFlag)
    if force || !shown.contains(ms) then
      mlMap.foreach { m =>
        pending = lastData.map(d => () => d()).orElse(pending)
        loadStyle(m, ms)
      }

  /** Applies a choice from the picker. With the setting on, choosing an
    * online map is the explicit request to show it in this view.
    */
  private def choose(ms: MapStyle): Unit =
    MapStyle.current = ms
    if Settings.blockExternalLocation then
      if ms.online then Settings.gate.grant(this) else Settings.gate.revoke(this)
      MapStyle.applyNetworkPolicy(act)
      // Other open maps keep the offline map; only their label changes.
      TrackMap.all.filterNot(_ == this).foreach(_.refreshBaseMap())
      refreshBaseMap(force = ms == MapStyle.Custom)
    else TrackMap.all.foreach(_.refreshBaseMap(force = ms == MapStyle.Custom))

  /** Picker as a 2-column grid of thumbnails rendered at the current view.
    * With the setting on, only the offline map gets a thumbnail; the online
    * ones are marked as sending the visible area and render nothing.
    */
  private def pickBaseMap(): Unit =
    val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
    given Ui.Ctx = act
    val camera = map.map(_._1.getCameraPosition)
    val snapshotters = scala.collection.mutable.ArrayBuffer.empty[org.maplibre.android.snapshotter.MapSnapshotter]
    val thumbW = (act.getResources.getDisplayMetrics.widthPixels - Ui.dp(64)) / 2
    val thumbH = (thumbW * 0.7).toInt
    val block = Settings.blockExternalLocation
    val cards = MapStyle.values.toSeq.map { ms =>
      val img = android.widget.ImageView(act)
      img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
      img.setBackgroundColor(Ui.color(Ui.attr.surfaceContainerHigh))
      img.setContentDescription(ms.label)
      val thumbnail = MapStyle.thumbnailAllowed(ms)
      // No thumbnail: a tinted panel that says the area would be sent.
      val face: android.view.View =
        if thumbnail then img
        else
          val t = Ui.text("選ぶと表示範囲を送信", 13f, colorAttr = Ui.attr.onTertiaryContainer)
          t.setGravity(Gravity.CENTER)
          t.setBackgroundColor(Ui.color(Ui.attr.tertiaryContainer))
          t
      face.setLayoutParams(android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
      val selected = ms == (if block then MapStyle.forView(detailedFlag) else MapStyle.current)
      val card = com.google.android.material.card.MaterialCardView(act)
      card.setRadius(Ui.dp(12).toFloat)
      card.setStrokeWidth(Ui.dp(if selected then 3 else 1))
      card.setStrokeColor(Ui.color(if selected then Ui.attr.primary else Ui.attr.outlineVariant))
      card.setCardElevation(0)
      card.addView(Ui.vbox(face, Ui.padded(Ui.vbox(Ui.text(ms.label, 15f, bold = true), Ui.caption(ms.note)), 10, 6)))
      val lp = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      lp.setMargins(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6))
      card.setLayoutParams(lp)
      card.setOnClickListener { _ =>
        dialog.dismiss()
        if ms == MapStyle.Custom then editCustomTiles()
        else choose(ms)
      }
      if !thumbnail || (ms == MapStyle.Custom && MapStyle.custom._1.isEmpty) then img.setImageDrawable(null)
      else camera.foreach { cam =>
        val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(thumbW, thumbH)
          .withStyleBuilder(ms.builder)
          .withCameraPosition(cam)
          .withLogo(false)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(act, opts)
        snapshotters += snap
        MapStyle.track(snap)
        snap.start(s => { MapStyle.untrack(snap); img.setImageBitmap(s.getBitmap) },
          (_: String) => MapStyle.untrack(snap))
      }
      card
    }
    val grid = Ui.vbox(cards.grouped(2).map(row => Ui.hbox(row*)).toSeq*)
    // The thumbnails were chosen under the setting as it was: a change closes the picker.
    val stop = Settings.gate.onChange(() => act.runOnUiThread(() => dialog.dismiss()))
    dialog.setOnDismissListener { _ =>
      stop()
      snapshotters.foreach { s => s.cancel(); MapStyle.untrack(s) }
    }
    val head = Ui.vbox(Ui.text("地図の種類", 20f, bold = true))
    if block then head.addView(Ui.caption("簡易地図以外は、表示する範囲の地図をサーバから取得します。選んだ地図はこの画面を離れるまで表示します。"))
    dialog.setContentView(Ui.scroll(Ui.padded(Ui.vbox(head, Ui.spacer(8), grid), 14, 12)))
    dialog.show()

  /** Any XYZ raster tile server: the URL template and a credit line. */
  private def editCustomTiles(): Unit =
    given Ui.Ctx = act
    val (url0, credit0) = MapStyle.custom
    val url = android.widget.EditText(act)
    url.setHint("https://example.com/tiles/{z}/{x}/{y}.png")
    url.setText(url0)
    url.setSingleLine(true)
    url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI)
    val credit = android.widget.EditText(act)
    credit.setHint("出典の表記（例: © 〇〇）")
    credit.setText(credit0)
    credit.setSingleLine(true)
    val body = Ui.padded(Ui.vbox(
      Ui.caption("{z} {x} {y} を含むラスタータイルの URL。API キーが必要なサービスは URL にキーを含めてください。利用規約と出典表記はサービスに従ってください。"),
      url, credit
    ), 20, 8)
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("カスタム地図")
      .setView(body)
      .setNegativeButton("やめる", null)
      .setPositiveButton("使う", (_, _) => {
        val u = url.getText.toString.trim
        if u.contains("{z}") && u.contains("{x}") && u.contains("{y}") && u.startsWith("http") then
          MapStyle.custom = (u, credit.getText.toString)
          choose(MapStyle.Custom)
        else android.widget.Toast.makeText(act, "{z} {x} {y} を含む http(s) の URL を入力してください", android.widget.Toast.LENGTH_LONG).show()
      })
      .show()

  private def withStyle(f: (MapLibreMap, Style) => Unit): Unit =
    map match
      case Some((m, s)) => f(m, s)
      case None         => pending = Some(() => map.foreach((m, s) => f(m, s)))

  private def markers(layer: String, ms: Seq[MapMarker]): FeatureCollection =
    FeatureCollection.fromFeatures(ms.map { mk =>
      val props = JsonObject()
      props.addProperty("key", mk.key)
      props.addProperty("layer", layer)
      props.addProperty("label", mk.label)
      props.addProperty("weight", mk.weight)
      Feature.fromGeometry(Point.fromLngLat(mk.at.lon, mk.at.lat), props)
    }.asJava)

  private def lines(paths: Seq[MapPath]): FeatureCollection = TrackMap.lineFeatures(paths)

  /** Replaces all data. `fit` moves the camera to show everything. */
  def setData(paths: Seq[MapPath], visits: Seq[MapMarker], checkIns: Seq[MapMarker], fit: Boolean = true): Unit =
    lastData = Some(() => setData(paths, visits, checkIns, fit = false))
    withStyle { (m, style) =>
      style.getSourceAs[GeoJsonSource]("track").setGeoJson(lines(paths))
      style.getSourceAs[GeoJsonSource]("visits").setGeoJson(markers("visit", visits))
      style.getSourceAs[GeoJsonSource]("checkins").setGeoJson(markers("checkin", checkIns))
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(FeatureCollection.fromFeatures(java.util.List.of()))
      if fit then fitTo(paths.flatMap(_.points).map(_.latLng) ++ visits.map(_.at) ++ checkIns.map(_.at))
    }

  /** Replaces the live tail (empty to clear). */
  def setLive(points: Seq[Fix]): Unit =
    withStyle { (_, style) =>
      Option(style.getSourceAs[GeoJsonSource]("live")).foreach(_.setGeoJson(lines(Seq(MapPath(points)))))
    }

  def highlight(path: Seq[Fix]): Unit =
    withStyle { (m, style) =>
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(lines(Seq(MapPath(path))))
      fitTo(path.map(_.latLng))
    }

  def focus(p: LatLng, zoom: Double = 15): Unit =
    withStyle((m, _) => m.easeCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), zoom)))

  def setLayerVisible(layer: String, visible: Boolean): Unit =
    val ids = layer match
      case "track"    => Seq("track-line", "track-dashed")
      case "visit"    => Seq("visit-circles")
      case "checkin"  => Seq("checkin-circles")
      case other      => Seq(other)
    withStyle { (_, style) =>
      ids.foreach(id => Option(style.getLayer(id)).foreach(_.setProperties(
        PropertyFactory.visibility(if visible then Property.VISIBLE else Property.NONE))))
    }

  private def fitTo(pts: Seq[LatLng]): Unit =
    map.foreach { (m, _) =>
      val distinct = pts.distinct
      if distinct.size >= 2 then
        val b = LatLngBounds.Builder().includes(distinct.map(p => MLatLng(p.lat, p.lon)).asJava).build()
        val pad = Ui.dp(40)(using act)
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad, pad, pad + bottomInset))
      else
        distinct.headOption
          .orElse(myLocation.map(l => LatLng(l.getLatitude, l.getLongitude)))
          .orElse(LoggerState.lastFix.map(_.latLng))
          .foreach { p =>
          m.easeCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), 14))
        }
    }

  /** The current-position marker is drawn by us (not MapLibre's animated
    * LocationComponent), so it moves in the same frame as the live route line.
    */
  private def enableMyLocation(m: MapLibreMap, style: Style): Unit =
    style.addSource(GeoJsonSource("me"))
    // Accuracy circle: radius in metres converted to pixels for the zoom
    // (exponential base 2, i.e. pixels double per zoom level).
    style.addLayer(
      CircleLayer("me-accuracy", "me").withProperties(
        PropertyFactory.circleColor("#1a73e8"),
        PropertyFactory.circleOpacity(0.15f),
        PropertyFactory.circleRadius(
          Expression.interpolate(Expression.exponential(2), Expression.zoom(),
            Expression.stop(0, Expression.division(Expression.get("acc"), Expression.literal(78271.5))),
            Expression.stop(22, Expression.division(Expression.get("acc"), Expression.literal(0.0187))))
        )
      )
    )
    style.addLayer(
      CircleLayer("me-dot", "me").withProperties(
        PropertyFactory.circleColor("#1a73e8"),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor("#ffffff"),
        PropertyFactory.circleStrokeWidth(2.5f)
      )
    )
    myLocation.orElse(LoggerState.lastFix.map(TrackMap.toLocation)).foreach(l => drawMe(style, l))

  private def drawMe(style: Style, l: Location): Unit =
    val props = JsonObject()
    // Metres per pixel shrink with cos(latitude); scale the radius to match.
    val acc = if l.hasAccuracy then l.getAccuracy.toDouble else 0.0
    props.addProperty("acc", acc / math.cos(math.toRadians(l.getLatitude)))
    Option(style.getSourceAs[GeoJsonSource]("me")).foreach(
      _.setGeoJson(Feature.fromGeometry(Point.fromLngLat(l.getLongitude, l.getLatitude), props))
    )

  def updateMyLocation(l: Location): Unit =
    myLocation = Some(l)
    map.foreach((_, style) => drawMe(style, l))

  /** Centers on the latest position; asks for one fix if the logger has none. */
  def goToMyLocation(): Unit =
    myLocation.filter(l => System.currentTimeMillis() - l.getTime < 10 * 60_000) match
      case Some(l) => focus(LatLng(l.getLatitude, l.getLongitude), 16)
      case None =>
        try
          act.getSystemService(classOf[LocationManager]).getCurrentLocation(
            LocationManager.FUSED_PROVIDER, null, act.getMainExecutor, (l: Location) =>
              if l != null then
                updateMyLocation(l)
                focus(LatLng(l.getLatitude, l.getLongitude), 16)
          )
        catch case _: SecurityException => ()

  def onStart(): Unit = mapView.onStart()
  def onResume(): Unit = mapView.onResume()
  def onPause(): Unit = mapView.onPause()
  def onStop(): Unit = mapView.onStop()
  def onLowMemory(): Unit = mapView.onLowMemory()
  def onSaveInstanceState(b: Bundle): Unit = mapView.onSaveInstanceState(b)
  def onDestroy(): Unit =
    Settings.gate.revoke(this)
    TrackMap.all -= this
    // A closed detailed view no longer allows the network.
    MapStyle.applyNetworkPolicy(act)
    mapView.onDestroy()

object TrackMap:
  val DefaultLine = "#006B5B"

  /** Paths as GeoJSON lines with `color` and `dashed` properties. */
  def lineFeatures(paths: Seq[MapPath]): FeatureCollection =
    // A line that collapses to one point is invalid geometry, and MapLibre
    // then drops the line layer's tile: zoomed out over a week or a month,
    // short legs do exactly that. Merge points too close to tell apart at the
    // scale of everything drawn, and skip lines left with a single point.
    val all = paths.flatMap(_.points)
    val minGap =
      if all.isEmpty then 0.0
      else
        val diag = Geo.distance(all.map(_.lat).min, all.map(_.lon).min, all.map(_.lat).max, all.map(_.lon).max)
        math.max(0.5, diag / 2000)
    def thinned(ps: Seq[Fix]) =
      ps.foldLeft(Vector.empty[Fix])((acc, f) => if acc.lastOption.exists(l => Geo.distance(l, f) < minGap) then acc else acc :+ f)
    FeatureCollection.fromFeatures(paths.map(p => p -> thinned(p.points)).filter(_._2.size >= 2).map { (p, pts) =>
      val props = JsonObject()
      props.addProperty("color", p.color)
      props.addProperty("dashed", p.dashed)
      Feature.fromGeometry(LineString.fromLngLats(pts.map(f => Point.fromLngLat(f.lon, f.lat)).asJava), props)
    }.asJava)

  /** Live maps, so a base map change applies everywhere. */
  val all = scala.collection.mutable.ArrayBuffer.empty[TrackMap]

  /** After 「位置情報を外部サービスに送らない」 changes (the setting already
    * ended every detail grant, see [[SendGate.setBlock]]): snapshots rendered
    * outside the day map queue are cancelled, and every open map shows what
    * the new setting allows.
    */
  def privacyChanged(ctx: android.content.Context): Unit =
    MapStyle.cancelSnapshots()
    MapStyle.applyNetworkPolicy(ctx)
    all.foreach(_.refreshBaseMap())
  private var cacheSet = false

  /** Keep viewed tiles for offline use: places you have looked at stay
    * visible without a network (default ambient cache is ~50 MB).
    */
  def enlargeCache(ctx: android.content.Context): Unit =
    if !cacheSet then
      cacheSet = true
      org.maplibre.android.offline.OfflineManager.getInstance(ctx).setMaximumAmbientCacheSize(
        300L * 1024 * 1024,
        new org.maplibre.android.offline.OfflineManager.FileSourceCallback:
          override def onSuccess(): Unit = ()
          override def onError(message: String): Unit = Log.w(s"tile cache: $message")
      )

  def toLocation(f: Fix): Location =
    val l = Location(f.provider)
    l.setLatitude(f.lat)
    l.setLongitude(f.lon)
    l.setTime(f.time)
    if f.hasAccuracy then l.setAccuracy(f.accuracy)
    if f.hasSpeed then l.setSpeed(f.speed)
    if !f.bearing.isNaN then l.setBearing(f.bearing)
    l

