package net.sksat.ashidori.ui

import android.os.Bundle
import android.view.{Gravity, View}
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.{LatLng as MLatLng, LatLngBounds}
import org.maplibre.android.maps.{MapLibreMap, MapView, Style}
import org.maplibre.android.style.layers.{CircleLayer, LineLayer, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Feature, FeatureCollection, LineString, Point}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Map of the selected day: track lines and visit circles. */
final class MapScreen(act: MainActivity, savedState: Bundle) extends Screen:
  private given Ctx = act
  MapLibre.getInstance(act)
  private val mapView = MapView(act)
  mapView.onCreate(savedState)
  private var map: Option[(MapLibreMap, Style)] = None
  private val label = chip("", color(attr.surface), color(attr.onSurface))

  val view: View =
    val lp = FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL
    lp.topMargin = dp(12)
    label.setLayoutParams(lp)
    label.setTextSize(14f)
    frame(mapView, label)

  mapView.getMapAsync { m =>
    m.getUiSettings.setRotateGesturesEnabled(false)
    m.setStyle(MapScreen.StyleUrl, (style: Style) => {
      style.addSource(GeoJsonSource("track"))
      style.addSource(GeoJsonSource("visits"))
      style.addLayer(
        LineLayer("track-line", "track").withProperties(
          PropertyFactory.lineColor("#1a73e8"),
          PropertyFactory.lineWidth(4f),
          PropertyFactory.lineOpacity(0.85f)
        )
      )
      style.addLayer(
        CircleLayer("visit-circles", "visits").withProperties(
          PropertyFactory.circleColor("#e8710a"),
          PropertyFactory.circleRadius(8f),
          PropertyFactory.circleStrokeColor("#ffffff"),
          PropertyFactory.circleStrokeWidth(2f)
        )
      )
      map = Some((m, style))
      load()
    })
  }

  def load(): Unit =
    val date = act.selectedDate
    label.setText(s"${date.getMonthValue}/${date.getDayOfMonth} の記録")
    AshidoriApp.io.execute { () =>
      val tl = TimelineRepo.day(date)
      act.runOnUiThread(() => show(tl))
    }

  private def show(tl: DayTimeline): Unit =
    map.foreach { (m, style) =>
      val trips = tl.segments.collect { case t: Trip => t }
      val lines = trips.map { t =>
        val pts = Geo.simplify(t.path, 5).map(f => Point.fromLngLat(f.lon, f.lat))
        Feature.fromGeometry(LineString.fromLngLats(pts.asJava))
      }
      val visits = tl.segments.collect { case v: Visit => Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)) }
      style.getSourceAs[GeoJsonSource]("track").setGeoJson(FeatureCollection.fromFeatures(lines.asJava))
      style.getSourceAs[GeoJsonSource]("visits").setGeoJson(FeatureCollection.fromFeatures(visits.asJava))
      val pts = tl.path.map(f => MLatLng(f.lat, f.lon)) ++
        tl.segments.collect { case v: Visit => MLatLng(v.lat, v.lon) }
      if pts.size >= 2 then
        val b = LatLngBounds.Builder().includes(pts.asJava).build()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, dp(48)))
      else
        pts.headOption.orElse(LoggerState.lastFix.map(f => MLatLng(f.lat, f.lon))).foreach { p =>
          m.easeCamera(CameraUpdateFactory.newLatLngZoom(p, 14))
        }
    }

  override def onShow(): Unit = load()
  def onStart(): Unit = mapView.onStart()
  def onResume(): Unit = mapView.onResume()
  def onPause(): Unit = mapView.onPause()
  def onStop(): Unit = mapView.onStop()
  def onLowMemory(): Unit = mapView.onLowMemory()
  def onSaveInstanceState(b: Bundle): Unit = mapView.onSaveInstanceState(b)
  override def onDestroy(): Unit = mapView.onDestroy()

object MapScreen:
  /** OpenFreeMap: free vector tiles, no API key. */
  val StyleUrl = "https://tiles.openfreemap.org/styles/liberty"
