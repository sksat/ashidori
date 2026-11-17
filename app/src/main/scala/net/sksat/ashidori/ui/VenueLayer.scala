package net.sksat.ashidori.ui

import android.graphics.{Bitmap, Canvas, Paint, Rect, RectF}
import android.os.{Bundle, Handler, Looper}
import android.view.{MotionEvent, View}
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import java.util.concurrent.{Executors, ExecutorService, RejectedExecutionException}
import java.util.concurrent.atomic.AtomicInteger
import org.maplibre.android.geometry.LatLng as MLatLng
import org.maplibre.android.maps.{MapLibreMap, Style}
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.{Property, PropertyFactory, SymbolLayer}
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
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
  * GeoJSON; a move drops an answer still on its way ([[gen]]). The venue
  * data being installed or removed asks again ([[refresh]]). The layer lies
  * under the basemap's own POIs and place labels, so those win a collision.
  *
  * The drawn venues are also virtual views of [[accessibilityView]] (an
  * overlay of the map), so that TalkBack reads their names and kinds. Not
  * interactive yet: there is no venue sheet to open (the check-in sheet
  * would check in, which a tap must not do).
  */
final class VenueLayer(ctx: android.content.Context):
  import VenueLayer.*

  /** The map and its loaded style the layer is in; None while a style loads. */
  private var target: Option[(MapLibreMap, Style)] = None
  /** Draws the Protomaps light sprite's icons (the offline style has them). */
  private var sprite = false
  /** The map whose camera this layer listens to, and its listeners (removed by [[detach]]). */
  private var listening: Option[(MapLibreMap, MapLibreMap.OnCameraMoveStartedListener, MapLibreMap.OnCameraIdleListener)] = None
  /** Raised by every request, camera move and detach: an older answer is dropped. */
  private val gen = AtomicInteger()
  /** What the source holds now, so an unchanged answer is not laid out again. */
  private var shown = Empty
  private val main = Handler(Looper.getMainLooper)
  private val fetch: Runnable = () => request()
  private var destroyed = false
  /** One background thread per map: queries never pile up on the main thread. */
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { r =>
    val t = Thread(() => { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ashidori-venue-map")
    t.setDaemon(true)
    t
  }

  /** Adds the layer to a style that has just loaded, under its POIs and
    * place labels, or else under `below` (the map's own layers).
    * `sprite`: the style's sprite is Protomaps light.
    */
  def attach(m: MapLibreMap, style: Style, sprite: Boolean, below: String): Unit =
    detach()
    if !destroyed then
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
    val moved: MapLibreMap.OnCameraMoveStartedListener = _ =>
      // The answer for the old range would show it mid-move.
      gen.incrementAndGet()
      main.removeCallbacks(fetch)
    val idle: MapLibreMap.OnCameraIdleListener = () =>
      schedule(DebounceMs)
      accessibilityChanged()
    m.addOnCameraMoveStartedListener(moved)
    m.addOnCameraIdleListener(idle)
    listening = Some((m, moved, idle))
    schedule(0)

  /** The style is going away (a new one loads, or the map view is dropped):
    * nothing of it, nor of its map, is kept.
    */
  def detach(): Unit =
    target = None
    gen.incrementAndGet()
    main.removeCallbacks(fetch)
    main.removeCallbacks(nodesChanged)
    listening.foreach { (m, moved, idle) =>
      m.removeOnCameraMoveStartedListener(moved)
      m.removeOnCameraIdleListener(idle)
    }
    listening = None
    shown = Empty
    accessibilityChanged()

  /** The venue data was installed or removed: reads the view again (an empty layer without it). */
  def refresh(): Unit = if target.nonEmpty then schedule(0)

  /** The map is gone for good: listeners, queued work and the thread end. */
  def destroy(): Unit =
    detach()
    destroyed = true
    worker.shutdownNow()

  private def schedule(delayMs: Long): Unit =
    main.removeCallbacks(fetch)
    if !destroyed then main.postDelayed(fetch, delayMs)

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
      try worker.execute { () =>
        if gen.get == g then
          val t0 = System.nanoTime()
          val found = VenueData.index().flatMap(ix =>
            Try(ix.within(s, w, n, e)).fold(err => { Log.w("venues: map query failed", err); None }, Some(_)))
          val t1 = System.nanoTime()
          val items = found.fold(Vector.empty)(VenueMapLayer.select(_, zoom, centre, Cap))
          val json = geoJson(items, withSprite)
          val t2 = System.nanoTime()
          Log.d(f"venues: map z$zoom%.1f ${found.fold(0)(_.size)} found, ${items.size} selected; " +
            f"query ${(t1 - t0) / 1e6}%.1f ms, select ${(t2 - t1) / 1e6}%.1f ms")
          main.post(() => if gen.get == g then show(style, json))
      }
      catch case _: RejectedExecutionException => () // destroyed meanwhile
  }

  private def show(style: Style, json: String): Unit =
    if target.exists(_._2 eq style) && json != shown then
      Option(style.getSourceAs[GeoJsonSource](SourceId)).foreach { src =>
        val t0 = System.nanoTime()
        src.setGeoJson(json)
        shown = json
        Log.d(f"venues: map source set in ${(System.nanoTime() - t0) / 1e6}%.1f ms (main), ${json.length} chars")
        // Once drawn (a frame or two later), the drawn venues change.
        main.removeCallbacks(nodesChanged)
        main.postDelayed(nodesChanged, 500)
      }

  // ---- accessibility: the drawn venues as virtual views ----

  /** A drawn venue: its virtual view id, what is read, and where its icon and label are. */
  private final case class Node(id: Int, text: String, at: Rect)
  /** The drawn venues as last read; None: to be read when asked. */
  private var nodes: Option[Vector[Node]] = Some(Vector.empty)
  private val nodesChanged: Runnable = () => accessibilityChanged()
  /** Virtual view ids by OpenStreetMap id, for the life of this layer: an
    * id never names another venue, so focus stays on its venue as the map
    * moves (the ids of the venues seen in a session: a few thousand).
    */
  private val virtualIds = scala.collection.mutable.HashMap.empty[String, Int]
  private def a11yOn = ctx.getSystemService(classOf[android.view.accessibility.AccessibilityManager]).isEnabled

  /** The drawn venues may have changed. A focused venue that is gone loses
    * the focus (it does not move to another venue).
    */
  private def accessibilityChanged(): Unit =
    nodes = None
    val focused = helper.getAccessibilityFocusedVirtualViewId
    if focused != ExploreByTouchHelper.INVALID_ID && a11yOn && !currentNodes.exists(_.id == focused) then
      helper.getAccessibilityNodeProvider(accessibilityView)
        .performAction(focused, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
    helper.invalidateRoot()

  /** The part of the map that the app's own views drawn over it (the
    * sheet, buttons, banners) leave visible: a rectangle without the
    * covers that span the map's width, and the smaller covers inside it.
    */
  private def unobscured(): (Rect, Seq[Rect]) =
    val v = accessibilityView
    val at = Array(0, 0)
    v.getLocationInWindow(at)
    val area = Rect(0, 0, v.getWidth, v.getHeight)
    val covers = scala.collection.mutable.ArrayBuffer.empty[Rect]
    def add(c: View): Unit =
      if c.getVisibility == View.VISIBLE && c.getWidth > 0 && c.getHeight > 0 then c match
        // A container without a background covers only with its children.
        case g: android.view.ViewGroup if c.getBackground == null =>
          for i <- 0 until g.getChildCount do add(g.getChildAt(i))
        case _ =>
          val l = Array(0, 0)
          c.getLocationInWindow(l)
          val r = Rect(l(0) - at(0), l(1) - at(1), l(0) - at(0) + c.getWidth, l(1) - at(1) + c.getHeight)
          if r.intersect(area) then covers += r
    // Drawn over the map: later siblings, and raised ones, up to the root.
    var child: View = v
    var parent = v.getParent
    while parent.isInstanceOf[android.view.ViewGroup] do
      val g = parent.asInstanceOf[android.view.ViewGroup]
      val i = g.indexOfChild(child)
      for j <- 0 until g.getChildCount if j != i do
        val c = g.getChildAt(j)
        if j > i || c.getZ > child.getZ then add(c)
      child = g
      parent = g.getParent
    val (wide, small) = covers.partition(_.width >= area.width * 0.8)
    for c <- wide do
      if c.centerY > area.centerY then area.bottom = math.min(area.bottom, c.top)
      else area.top = math.max(area.top, c.bottom)
    (area, small.filter(r => Rect.intersects(r, area)).toSeq)

  /** The venues the map draws now in the visible part (after collisions),
    * at most [[MaxNodes]] by rank and distance from the centre, top to
    * bottom. Read on the main thread, only when an accessibility service
    * asks (or a focused venue may be gone).
    */
  private def currentNodes: Vector[Node] = nodes.getOrElse {
    val t0 = System.nanoTime()
    val read = target.fold(Vector.empty[Node]) { (m, _) =>
      val (area, covers) = unobscured()
      if area.isEmpty then Vector.empty
      else
        val cam = m.getCameraPosition
        val zoom = cam.zoom
        val c = Option(cam.target).fold(LatLng(0, 0))(t => LatLng(t.getLatitude, t.getLongitude))
        val cos = math.cos(math.toRadians(c.lat))
        def str(f: org.maplibre.geojson.Feature, k: String) = Option(Try(f.getStringProperty(k)).getOrElse(null))
        def num(f: org.maplibre.geojson.Feature, k: String) = Try(f.getNumberProperty(k).doubleValue).getOrElse(99.0)
        val found = Try(m.queryRenderedFeatures(RectF(area), LayerId).asScala.toVector).getOrElse(Vector.empty)
          .flatMap(f => (f.geometry(), str(f, "id"), str(f, "name")) match
            case (p: Point, Some(id), Some(name)) => Some((f, p, id, name))
            case _ => None)
          .distinctBy(_._3)
          // The cap before any conversion to the screen.
          .sortBy { (f, p, _, _) =>
            val rank = if zoom >= VenueMapLayer.CloseZoom then num(f, "rc") else num(f, "r")
            val dy = p.latitude - c.lat
            val dx = (p.longitude - c.lon) * cos
            rank * 1e6 + dx * dx + dy * dy
          }.take(MaxNodes)
        val d = ctx.getResources.getDisplayMetrics.density
        // As the layer's text-size (dp): 10 up to zoom 17, 16 from zoom 19.
        val em = d * (if zoom <= 17 then 10.0 else if zoom >= 19 then 16.0 else 10 + (zoom - 17) * 3).toFloat
        val icon = (9 * d).toInt
        found.flatMap { (f, p, id, name) =>
          val s = m.getProjection.toScreenLocation(MLatLng(p.latitude, p.longitude))
          val (x, y) = (s.x.toInt, s.y.toInt)
          if !area.contains(x, y) || covers.exists(_.contains(x, y)) then None
          else
            // The label beside the icon (right first, as text-variable-anchor),
            // wrapped at 8 ems: wide characters 1 em, others about 0.6.
            val ems = name.codePoints().toArray.map(cp => if Character.isIdeographic(cp) || cp >= 0x3000 then 1.0 else 0.6).sum
            val w = (math.min(ems, 8.0) * em).toInt
            val h = (math.max(1.0, math.ceil(ems / 8.0)) * em * 1.2).toInt
            val gap = (1.1 * em).toInt
            val label =
              if x + gap + w <= area.right then Rect(x + icon, y - h / 2, x + gap + w, y + h / 2)
              else Rect(x - gap - w, y - h / 2, x - icon, y + h / 2)
            val r = Rect(x - icon, y - icon, x + icon, y + icon)
            r.union(label)
            if !r.intersect(area) then None
            else
              val vid = virtualIds.getOrElseUpdate(id, virtualIds.size)
              Some(Node(vid, str(f, "kind").filter(_.nonEmpty).fold(name)(k => s"$name、$k"), r))
        }.sortBy(n => (n.at.centerY / math.max(1, 2 * icon), n.at.centerX))
    }
    nodes = Some(read)
    Log.d(f"venues: ${read.size} accessibility nodes in ${(System.nanoTime() - t0) / 1e6}%.1f ms (main)")
    read
  }

  /** Over the map, under its controls: holds the venues as virtual views.
    * It takes no touches, so the map's gestures are unchanged.
    */
  val accessibilityView: View =
    val v = new View(ctx):
      override def dispatchHoverEvent(e: MotionEvent): Boolean = helper.dispatchHoverEvent(e) || super.dispatchHoverEvent(e)
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
    v

  private lazy val helper: ExploreByTouchHelper = new ExploreByTouchHelper(accessibilityView):
    override def getVirtualViewAt(x: Float, y: Float): Int =
      currentNodes.find(_.at.contains(x.toInt, y.toInt)).fold(ExploreByTouchHelper.INVALID_ID)(_.id)
    override def getVisibleVirtualViews(ids: java.util.List[Integer]): Unit =
      currentNodes.foreach(n => ids.add(n.id))
    override def onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat): Unit =
      currentNodes.find(_.id == id) match
        case Some(n) =>
          node.setContentDescription(n.text)
          node.setBoundsInParent(n.at)
        case None =>
          // Gone since it was listed: an empty node where the helper asks one.
          node.setContentDescription("")
          node.setBoundsInParent(Rect(0, 0, 1, 1))
    override def onPerformActionForVirtualView(id: Int, action: Int, args: Bundle): Boolean = false
  ViewCompat.setAccessibilityDelegate(accessibilityView, helper)

  // ---- drawing ----

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
  /** The GeoJSON at most (chars, about bytes for Latin names, a third for Japanese). */
  val MaxGeoJsonChars = 256 * 1024
  /** A name longer than this is cut (with "…"): labels wrap at 8 ems anyway. */
  val MaxNameChars = 48
  /** Virtual views for accessibility at most (the first by rank and distance). */
  val MaxNodes = 100
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
  /** A label that is only a raw tag value (no Japanese label): not read. */
  private val RawTag = "[a-z_]+".r

  private def dotId(g: VenueMapLayer.Group) = s"venue-dot-${g.key}"

  private def cut(s: String): String =
    if s.codePointCount(0, s.length) <= MaxNameChars then s
    else s.substring(0, s.offsetByCodePoints(0, MaxNameChars - 1)) + "…"

  /** The items as GeoJSON, in their order, until [[MaxGeoJsonChars]]. */
  def geoJson(items: Seq[VenueMapLayer.Item], sprite: Boolean): String =
    val sb = StringBuilder(Empty.length + items.size * 160)
    sb ++= """{"type":"FeatureCollection","features":["""
    val it = items.iterator
    var first = true
    var full = false
    while !full && it.hasNext do
      val i = it.next()
      val st = i.style
      val kind = Some(i.venue.category).filterNot(c => c.isEmpty || RawTag.matches(c)).getOrElse("")
      val f = ujson.write(ujson.Obj(
        "type" -> "Feature",
        "geometry" -> ujson.Obj("type" -> "Point", "coordinates" -> ujson.Arr(i.venue.lon, i.venue.lat)),
        "properties" -> ujson.Obj(
          "id" -> i.venue.id,
          "name" -> cut(i.venue.name),
          "kind" -> kind,
          "img" -> (if sprite && st.icon.nonEmpty then st.icon else dotId(st.group)),
          "color" -> st.group.color,
          "mz" -> st.minZoom,
          "r" -> st.rank,
          "rc" -> st.rankClose)))
      if sb.length + f.length + 3 > MaxGeoJsonChars then full = true
      else
        if !first then sb += ','
        sb ++= f
        first = false
    sb ++= "]}"
    sb.toString
