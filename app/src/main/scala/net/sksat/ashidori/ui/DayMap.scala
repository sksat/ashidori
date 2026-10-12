package net.sksat.ashidori.ui

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{FrameLayout, ImageView, LinearLayout}
import org.maplibre.android.geometry.{LatLng as MLatLng, LatLngBounds}
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.{CircleLayer, LineLayer, Property, PropertyFactory}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.{Feature, FeatureCollection, Point}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** A still map of one day for the timeline: the route in transport mode
  * colors and the stays, on the chosen base map. Rendered off-screen one at
  * a time and cached; without tiles (offline) the plain sketch stays.
  */
object DayMap:
  private val cache = android.util.LruCache[String, Bitmap](40)
  private val queue = mutable.Queue.empty[() => Unit]
  private var busy = false
  /** Running snapshotters must stay referenced until they call back. */
  private val running = mutable.Set.empty[MapSnapshotter]

  def view(act: MainActivity, tl: DayTimeline): Option[View] = view(act, Seq(tl), tl.date.toString)

  /** One map for several days (a week or a month card): coarser lines and
    * one dot per place. `insetDp` is the horizontal space around the map.
    */
  def view(act: MainActivity, tls: Seq[DayTimeline], id: String, heightDp: Int = 180, toleranceM: Double = 8,
           insetDp: Int = 32, marginDp: Int = 16): Option[View] =
    given Ctx = act
    val trips = tls.flatMap(_.segments.collect { case t: Trip => t })
    val visits = tls.flatMap(_.segments.collect { case v: Visit => v })
    val paths = trips.flatMap(t => t.legs.filter(_.mode != TransportMode.Still).map { l =>
      MapPath(Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), toleranceM), l.mode.color, l.isUncertain)
    }).filter(_.points.size >= 2)
    // Repeated stays at one place draw one dot.
    val stays = visits.map(v => v.placeId.map(Left(_)).getOrElse(Right(v.latLng)) -> v.latLng).distinctBy(_._1).map(_._2)
    val pts = paths.flatMap(_.points).map(_.latLng) ++ stays
    if pts.isEmpty then return None

    val dm = act.getResources.getDisplayMetrics
    val wPx = dm.widthPixels - dp(insetDp)
    val hPx = dp(heightDp)
    val sketch = RouteSketchView(act, paths.map(p => (p.points.map(_.latLng), android.graphics.Color.parseColor(p.color))), stays)
    val img = ImageView(act)
    img.setScaleType(ImageView.ScaleType.CENTER_CROP)
    val style = MapStyle.current
    val frame = FrameLayout(act)
    frame.addView(sketch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(12).toFloat)
    bg.setColor(color(attr.surfaceContainerHigh))
    frame.setBackground(bg)
    frame.setClipToOutline(true)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx)
    lp.setMargins(dp(marginDp), dp(4), dp(marginDp), dp(8))
    frame.setLayoutParams(lp)

    // Same route, same picture: edits (mode changes) change the key.
    val key = s"${style.key}/$id/${paths.map(p => (p.color, p.points.size, p.dashed)).hashCode}/${stays.hashCode}/$wPx"
    // The snapshot draws the base map's attribution itself.
    def show(b: Bitmap): Unit = img.setImageBitmap(b)
    Option(cache.get(key)) match
      case Some(b) => show(b)
      case None =>
        enqueue { () =>
          val density = dm.density
          // Fit everything with a margin; at least ~400 m across.
          val (lat0, lat1) = (pts.map(_.lat).min, pts.map(_.lat).max)
          val (lon0, lon1) = (pts.map(_.lon).min, pts.map(_.lon).max)
          val padLat = math.max((lat1 - lat0) * 0.15, 0.002)
          val padLon = math.max((lon1 - lon0) * 0.15, 0.0025)
          val bounds = LatLngBounds.Builder()
            .include(MLatLng(lat0 - padLat, lon0 - padLon))
            .include(MLatLng(lat1 + padLat, lon1 + padLon))
            .build()
          val builder = style.builder
            .withSource(GeoJsonSource("day-route", TrackMap.lineFeatures(paths)))
            .withSource(GeoJsonSource("day-stays", FeatureCollection.fromFeatures(
              stays.map(s => Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))).asJava)))
            .withLayer(LineLayer("day-route-line", "day-route").withProperties(
              PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
              PropertyFactory.lineWidth(4f),
              PropertyFactory.lineOpacity(Expression.switchCase(Expression.get("dashed"), Expression.literal(0.45f), Expression.literal(0.95f))),
              PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
              PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            ))
            .withLayer(CircleLayer("day-stays-circle", "day-stays").withProperties(
              PropertyFactory.circleColor(TrackMap.DefaultLine),
              PropertyFactory.circleRadius(5f),
              PropertyFactory.circleStrokeColor("#ffffff"),
              PropertyFactory.circleStrokeWidth(2f)
            ))
          val opts = MapSnapshotter.Options((wPx / density).toInt, (hPx / density).toInt)
            .withPixelRatio(density)
            .withRegion(bounds)
            .withStyleBuilder(builder)
            .withLogo(false)
          val snap = MapSnapshotter(act, opts)
          running += snap
          def done(): Unit =
            running -= snap
            next()
          snap.start(
            s => {
              cache.put(key, s.getBitmap)
              show(s.getBitmap)
              done()
            },
            (_: String) => done() // offline or no tiles: keep the sketch
          )
        }
    Some(frame)

  private def enqueue(job: () => Unit): Unit =
    queue.enqueue(job)
    if !busy then next()

  private def next(): Unit =
    if queue.isEmpty then busy = false
    else
      busy = true
      val job = queue.dequeue()
      try job()
      catch
        case e: Exception =>
          Log.w("day map snapshot failed", e)
          next()

  /** Drop queued work (e.g. the feed reloaded). */
  def cancelPending(): Unit = queue.clear()
