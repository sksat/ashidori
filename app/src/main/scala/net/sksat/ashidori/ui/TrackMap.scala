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
import org.maplibre.android.style.layers.{CircleLayer, LineLayer, Property, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Feature, FeatureCollection, LineString, Point}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import ashidori.core.*

/** A marker on the map; `key` comes back in click callbacks. */
final case class MapMarker(at: LatLng, key: String, label: String = "")

/** MapLibre map with layers for tracks, visits, check-ins and one highlighted
  * path. Data set before the style finishes loading is applied afterwards.
  */
final class TrackMap(act: MainActivity, saved: Bundle):
  MapLibre.getInstance(act)
  TrackMap.enlargeCache(act)
  val mapView: MapView = MapView(act)
  mapView.onCreate(saved)
  act.registerMap(this)

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

  /** Map plus overlay controls; screens add this instead of `mapView`. */
  val view: FrameLayout =
    val f = FrameLayout(act)
    f.addView(mapView)
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START)
    lp.setMargins(Ui.dp(12)(using act), 0, 0, Ui.dp(12)(using act))
    f.addView(locate, lp)
    f

  // Follow the logger's fixes; the map never turns GNSS on by itself.
  LoggerState.observe(() => LoggerState.lastFix.foreach(f => updateMyLocation(TrackMap.toLocation(f))))

  mapView.getMapAsync { m =>
    m.getUiSettings.setRotateGesturesEnabled(false)
    m.getUiSettings.setTiltGesturesEnabled(false)
    m.setStyle(TrackMap.StyleUrl, (style: Style) => {
      for id <- Seq("track", "highlight", "visits", "checkins") do style.addSource(GeoJsonSource(id))
      style.addLayer(
        LineLayer("track-line", "track").withProperties(
          PropertyFactory.lineColor("#1a73e8"),
          PropertyFactory.lineWidth(3.5f),
          PropertyFactory.lineOpacity(0.8f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        )
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
          PropertyFactory.circleColor("#1e8e3e"),
          PropertyFactory.circleRadius(7f),
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
  }

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
      Feature.fromGeometry(Point.fromLngLat(mk.at.lon, mk.at.lat), props)
    }.asJava)

  private def lines(paths: Seq[Seq[Fix]]): FeatureCollection =
    FeatureCollection.fromFeatures(paths.filter(_.size >= 2).map { p =>
      Feature.fromGeometry(LineString.fromLngLats(p.map(f => Point.fromLngLat(f.lon, f.lat)).asJava))
    }.asJava)

  /** Replaces all data. `fit` moves the camera to show everything. */
  def setData(paths: Seq[Seq[Fix]], visits: Seq[MapMarker], checkIns: Seq[MapMarker], fit: Boolean = true): Unit =
    withStyle { (m, style) =>
      style.getSourceAs[GeoJsonSource]("track").setGeoJson(lines(paths))
      style.getSourceAs[GeoJsonSource]("visits").setGeoJson(markers("visit", visits))
      style.getSourceAs[GeoJsonSource]("checkins").setGeoJson(markers("checkin", checkIns))
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(FeatureCollection.fromFeatures(java.util.List.of()))
      if fit then fitTo(paths.flatten.map(_.latLng) ++ visits.map(_.at) ++ checkIns.map(_.at))
    }

  def highlight(path: Seq[Fix]): Unit =
    withStyle { (m, style) =>
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(lines(Seq(path)))
      fitTo(path.map(_.latLng))
    }

  def focus(p: LatLng, zoom: Double = 15): Unit =
    withStyle((m, _) => m.easeCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), zoom)))

  def setLayerVisible(layer: String, visible: Boolean): Unit =
    val ids = layer match
      case "track"    => Seq("track-line")
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
  def onDestroy(): Unit = mapView.onDestroy()

object TrackMap:
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

  /** OpenFreeMap: free vector tiles, no API key. */
  val StyleUrl = "https://tiles.openfreemap.org/styles/liberty"
