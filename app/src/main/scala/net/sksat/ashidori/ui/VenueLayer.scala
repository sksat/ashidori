package net.sksat.ashidori.ui

import android.graphics.{Bitmap, Canvas, Paint}
import android.os.{Handler, Looper}
import java.util.concurrent.{Executors, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger
import org.maplibre.android.maps.{MapLibreMap, Style}
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.{Property, PropertyFactory, SymbolLayer}
import org.maplibre.android.style.sources.GeoJsonSource
import scala.jdk.CollectionConverters.*
import scala.util.Try
import net.sksat.ashidori.{Log, VenueData}
import ashidori.core.{LatLng, VenueMapLayer}

/** Shops and other places on an interactive map ([[TrackMap]]), from the
  * on-device venue index ([[VenueMapLayer]]): the offline basemap and the
  * region packs stop at zoom 14, where most shop POIs are not yet in the
  * tiles. Nothing is sent; without the venue data the layer stays empty,
  * and in a style without fonts (see [[attach]]) it is left out.
  *
  * When the camera comes to rest (debounced), the venues of the view and a
  * margin around it are read off the main thread and replace the layer's
  * GeoJSON. The layer lies under the basemap's own POIs and place labels,
  * so those win a collision. Not interactive yet: there is no venue sheet
  * to open (the check-in sheet would check in, which a tap must not do).
  */
final class VenueLayer(ctx: android.content.Context):
  import VenueLayer.*

  /** The map and its loaded style the layer is in; None while a style loads. */
  private var target: Option[(MapLibreMap, Style)] = None
  /** Draws the Protomaps light sprite's icons (the offline style has them). */
  private var sprite = false
  /** The map whose camera this layer listens to. */
  private var listening: MapLibreMap = null
  /** Raised by every request and detach: an older answer is dropped. */
  private val gen = AtomicInteger()
  /** What the source holds now, so an unchanged answer is not laid out again. */
  private var shown = Empty
  private val main = Handler(Looper.getMainLooper)
  private val fetch: Runnable = () => request()

  /** Adds the layer to a style that has just loaded, under its POIs and
    * place labels, or else under `below` (the map's own layers).
    * `sprite`: the style's sprite is Protomaps light.
    */
  def attach(m: MapLibreMap, style: Style, sprite: Boolean, below: String): Unit =
    detach()
    // A symbol layer in a style without fonts draws nothing at all (not
    // even its icons): a raster map without the offline map's fonts, or
    // the canvas without a base map. Then the layer is left out.
    val t0 = System.nanoTime()
    val fonts = Glyphs.findFirstIn(style.getJson).nonEmpty
    if fonts then add(m, style, sprite, below)
    Log.d(f"venues: map layer ${if fonts then "added" else "left out (no fonts)"} in ${(System.nanoTime() - t0) / 1e6}%.1f ms")

  private def add(m: MapLibreMap, style: Style, sprite: Boolean, below: String): Unit =
    this.sprite = sprite
    shown = Empty
    for g <- VenueMapLayer.Group.values do style.addImage(dotId(g), dot(g.color))
    style.addSource(GeoJsonSource(SourceId))
    val layers = style.getLayers.asScala.toSeq
    val anchor = layers.find(l => l.isInstanceOf[SymbolLayer] && LabelLayer.findFirstIn(l.getId).nonEmpty)
      .map(_.getId).getOrElse(below)
    val layer = SymbolLayer(LayerId, SourceId)
      .withFilter(Expression.gte(Expression.zoom(), Expression.get("mz")))
      .withProperties(
        PropertyFactory.iconImage(Expression.get("img")),
        // From CloseZoom on, shops before the malls that hold them.
        PropertyFactory.symbolSortKey(Expression.step(Expression.zoom(), Expression.get("r"),
          Expression.stop(VenueMapLayer.CloseZoom, Expression.get("rc")))),
        // As the Protomaps light "pois" layer.
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(font(layers)),
        PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
          Expression.stop(17, 10f), Expression.stop(19, 16f))),
        PropertyFactory.textMaxWidth(8f),
        PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
        PropertyFactory.textVariableAnchor(Array(Property.TEXT_ANCHOR_LEFT, Property.TEXT_ANCHOR_RIGHT)),
        PropertyFactory.textOffset(Array[java.lang.Float](1.1f, 0f)),
        PropertyFactory.textColor(Expression.toColor(Expression.get("color"))),
        PropertyFactory.textHaloColor("#ffffff"),
        PropertyFactory.textHaloWidth(1f))
    style.addLayerBelow(layer, anchor)
    target = Some((m, style))
    if listening ne m then
      listening = m
      m.addOnCameraMoveStartedListener(_ => if listening eq m then main.removeCallbacks(fetch))
      m.addOnCameraIdleListener(() => if listening eq m then schedule(DebounceMs))
    schedule(0)

  /** The style is going away (a new one loads, or the map view is dropped). */
  def detach(): Unit =
    target = None
    gen.incrementAndGet()
    main.removeCallbacks(fetch)

  private def schedule(delayMs: Long): Unit =
    main.removeCallbacks(fetch)
    main.postDelayed(fetch, delayMs)

  /** Reads the venues of the view off the main thread and shows them. */
  private def request(): Unit = target.foreach { (m, style) =>
    val g = gen.incrementAndGet()
    val cam = m.getCameraPosition
    val zoom = cam.zoom
    if zoom < VenueMapLayer.MinZoom - 0.5 || cam.target == null then show(style, Empty)
    else
      val b = m.getProjection.getVisibleRegion.latLngBounds
      val dLat = (b.getLatNorth - b.getLatSouth) * Margin
      val dLon = (b.getLonEast - b.getLonWest) * Margin
      val (s, w, n, e) = (b.getLatSouth - dLat, b.getLonWest - dLon, b.getLatNorth + dLat, b.getLonEast + dLon)
      val centre = LatLng(cam.target.getLatitude, cam.target.getLongitude)
      val withSprite = sprite
      worker.execute { () =>
        if gen.get == g then
          val t0 = System.nanoTime()
          val found = VenueData.index().flatMap(ix =>
            Try(ix.within(s, w, n, e)).fold(err => { Log.w("venues: map query failed", err); None }, Some(_)))
          val t1 = System.nanoTime()
          val items = found.fold(Vector.empty)(VenueMapLayer.select(_, zoom, centre, Cap))
          val json = geoJson(items, withSprite)
          val t2 = System.nanoTime()
          Log.d(f"venues: map z$zoom%.1f ${found.fold(0)(_.size)} found, ${items.size} drawn; " +
            f"query ${(t1 - t0) / 1e6}%.1f ms, select ${(t2 - t1) / 1e6}%.1f ms")
          main.post(() => if gen.get == g then show(style, json))
      }
  }

  private def show(style: Style, json: String): Unit =
    if target.exists(_._2 eq style) && json != shown then
      Option(style.getSourceAs[GeoJsonSource](SourceId)).foreach { src =>
        val t0 = System.nanoTime()
        src.setGeoJson(json)
        shown = json
        Log.d(f"venues: map source set in ${(System.nanoTime() - t0) / 1e6}%.1f ms (main), ${json.length} chars")
      }

  /** The font of the style's own labels (its glyphs have it), the regular one if there is one. */
  private def font(layers: Seq[org.maplibre.android.style.layers.Layer]): Array[String] =
    val fonts = layers.collect { case l: SymbolLayer => l.getTextFont }
      .filter(p => p != null && p.isValue && p.getValue != null && p.getValue.nonEmpty).map(_.getValue)
    fonts.find(_.exists(_.contains("Regular"))).orElse(fonts.headOption).getOrElse(Array(DefaultFont))

  private def dot(color: String): Bitmap =
    val d = ctx.getResources.getDisplayMetrics.density
    val size = math.ceil(13 * d).toInt
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.setColor(android.graphics.Color.WHITE)
    c.drawCircle(size / 2f, size / 2f, 5 * d, p)
    p.setColor(android.graphics.Color.parseColor(color))
    c.drawCircle(size / 2f, size / 2f, 3.8f * d, p)
    bmp

object VenueLayer:
  val SourceId = "venues"
  val LayerId = "venue-symbols"
  /** Features drawn at most (the closest to the centre, by rank). */
  val Cap = 1_200
  /** Read around the view, in parts of its size on each side, so a short pan shows them at once. */
  val Margin = 0.25
  val DebounceMs = 200L
  private val DefaultFont = "Noto Sans Regular"
  private val Empty = """{"type":"FeatureCollection","features":[]}"""
  /** Basemap layers whose labels go over the venues: POIs and place names
    * (Protomaps "japan-pois", "world-places_locality"; OpenMapTiles "poi_r20", "place_city").
    */
  private val LabelLayer = "(?i)(^|[-_])(pois?|places?)([-_]|$)".r
  private val Glyphs = "\"glyphs\"\\s*:\\s*\"[^\"]".r

  private def dotId(g: VenueMapLayer.Group) = s"venue-dot-${g.key}"

  /** One background thread for every map: queries never pile up on the main thread. */
  private lazy val worker: ExecutorService = Executors.newSingleThreadExecutor { r =>
    val t = Thread(() => { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ashidori-venue-map")
    t.setDaemon(true)
    t
  }

  def geoJson(items: Seq[VenueMapLayer.Item], sprite: Boolean): String =
    if items.isEmpty then Empty
    else
      val features = ujson.Arr.from(items.map { i =>
        val st = i.style
        ujson.Obj(
          "type" -> "Feature",
          "geometry" -> ujson.Obj("type" -> "Point", "coordinates" -> ujson.Arr(i.venue.lon, i.venue.lat)),
          "properties" -> ujson.Obj(
            "name" -> i.venue.name,
            "img" -> (if sprite && st.icon.nonEmpty then st.icon else dotId(st.group)),
            "color" -> st.group.color,
            "mz" -> st.minZoom,
            "r" -> st.rank,
            "rc" -> st.rankClose))
      })
      ujson.write(ujson.Obj("type" -> "FeatureCollection", "features" -> features))
