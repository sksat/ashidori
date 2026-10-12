package net.sksat.ashidori.ui

import android.location.{Location, LocationManager}
import android.os.Bundle
import android.view.{Gravity, ViewGroup}
import android.widget.FrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.JsonObject
import org.maplibre.android.location.{LocationComponentActivationOptions, LocationComponentOptions}
import org.maplibre.android.location.modes.RenderMode
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
  */
final class TrackMap(act: MainActivity, saved: Bundle, controlsOnTop: Boolean = false):
  MapLibre.getInstance(act)
  TrackMap.enlargeCache(act)
  val mapView: MapView = MapView(act)
  mapView.onCreate(saved)
  act.registerMap(this)
  TrackMap.all += this

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
    f

  // Follow the logger's fixes; the map never turns GNSS on by itself.
  LoggerState.observe(() => LoggerState.lastFix.foreach(f => updateMyLocation(TrackMap.toLocation(f))))

  private var clickInstalled = false
  /** Re-applies the latest data after a base map change. */
  private var lastData: Option[() => Unit] = None

  mapView.getMapAsync { m =>
    m.getUiSettings.setRotateGesturesEnabled(false)
    m.getUiSettings.setTiltGesturesEnabled(false)
    loadStyle(m, MapStyle.current)
  }

  /** Loads a base map and (re)adds our sources and layers on top of it. */
  private def loadStyle(m: MapLibreMap, ms: MapStyle): Unit =
    map = None
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
          PropertyFactory.lineWidth(3f),
          PropertyFactory.lineOpacity(0.7f),
          PropertyFactory.lineDasharray(Array[java.lang.Float](2f, 2f))
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

  /** Switches the base map on this map, keeping what is drawn. */
  def setBaseMap(ms: MapStyle): Unit =
    map.map(_._1).foreach { m =>
      pending = lastData.map(d => () => d())
      loadStyle(m, ms)
    }

  /** Picker as a 2-column grid of thumbnails rendered at the current view. */
  private def pickBaseMap(): Unit =
    val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
    given Ui.Ctx = act
    val camera = map.map(_._1.getCameraPosition)
    val snapshotters = scala.collection.mutable.ArrayBuffer.empty[org.maplibre.android.snapshotter.MapSnapshotter]
    val thumbW = (act.getResources.getDisplayMetrics.widthPixels - Ui.dp(64)) / 2
    val thumbH = (thumbW * 0.7).toInt
    val cards = MapStyle.values.toSeq.map { ms =>
      val img = android.widget.ImageView(act)
      img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
      img.setBackgroundColor(Ui.color(Ui.attr.surfaceContainerHigh))
      img.setLayoutParams(android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
      img.setContentDescription(ms.label)
      val selected = ms == MapStyle.current
      val card = com.google.android.material.card.MaterialCardView(act)
      card.setRadius(Ui.dp(12).toFloat)
      card.setStrokeWidth(Ui.dp(if selected then 3 else 1))
      card.setStrokeColor(Ui.color(if selected then Ui.attr.primary else Ui.attr.outlineVariant))
      card.setCardElevation(0)
      card.addView(Ui.vbox(img, Ui.padded(Ui.vbox(Ui.text(ms.label, 15f, bold = true), Ui.caption(ms.note)), 10, 6)))
      val lp = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      lp.setMargins(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6))
      card.setLayoutParams(lp)
      card.setOnClickListener { _ =>
        dialog.dismiss()
        MapStyle.current = ms
        TrackMap.all.foreach(_.setBaseMap(ms))
      }
      camera.foreach { cam =>
        val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(thumbW, thumbH)
          .withStyleBuilder(ms.builder)
          .withCameraPosition(cam)
          .withLogo(false)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(act, opts)
        snapshotters += snap
        snap.start(s => img.setImageBitmap(s.getBitmap))
      }
      card
    }
    val grid = Ui.vbox(cards.grouped(2).map(row => Ui.hbox(row*)).toSeq*)
    dialog.setOnDismissListener(_ => snapshotters.foreach(_.cancel()))
    dialog.setContentView(Ui.scroll(Ui.padded(Ui.vbox(Ui.text("地図の種類", 20f, bold = true), Ui.spacer(8), grid), 14, 12)))
    dialog.show()

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

  private def lines(paths: Seq[MapPath]): FeatureCollection =
    FeatureCollection.fromFeatures(paths.filter(_.points.size >= 2).map { p =>
      val props = JsonObject()
      props.addProperty("color", p.color)
      props.addProperty("dashed", p.dashed)
      Feature.fromGeometry(LineString.fromLngLats(p.points.map(f => Point.fromLngLat(f.lon, f.lat)).asJava), props)
    }.asJava)

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

  private def enableMyLocation(m: MapLibreMap, style: Style): Unit =
    try
      val lc = m.getLocationComponent
      lc.activateLocationComponent(
        LocationComponentActivationOptions.builder(act, style)
          .useDefaultLocationEngine(false)
          .locationComponentOptions(LocationComponentOptions.builder(act).accuracyAlpha(0.15f).build())
          .build()
      )
      lc.setLocationComponentEnabled(true)
      lc.setRenderMode(RenderMode.NORMAL)
      myLocation.orElse(LoggerState.lastFix.map(TrackMap.toLocation)).foreach(lc.forceLocationUpdate)
    catch case e: Exception => Log.w("location component unavailable", e)

  def updateMyLocation(l: Location): Unit =
    myLocation = Some(l)
    map.foreach { (m, _) =>
      try
        val lc = m.getLocationComponent
        if lc.isLocationComponentActivated then lc.forceLocationUpdate(l)
      catch case _: Exception => ()
    }

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
    TrackMap.all -= this
    mapView.onDestroy()

object TrackMap:
  val DefaultLine = "#006B5B"
  /** Live maps, so a base map change applies everywhere. */
  val all = scala.collection.mutable.ArrayBuffer.empty[TrackMap]
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

