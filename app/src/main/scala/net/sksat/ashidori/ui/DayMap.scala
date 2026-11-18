package net.sksat.ashidori.ui

import android.content.Context
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
import ashidori.core.BaseMapPolicy.Outcome
import Ui.*

/** A still map of one day for the timeline: the route in transport mode
  * colors and the stays, on the chosen base map. Rendered off-screen one at
  * a time and cached; without tiles (offline) the plain sketch stays.
  *
  * Without a base map to draw ([[Outcome.NoBasemap]]: the offline map is
  * needed and not downloaded) MapLibre is not run: the card, at the same
  * height, shows the sketch on a faint grid with a chip saying so and
  * offering the download ([[NoBasemapChip]], top left, clear of the feed's
  * button). While a snapshot is pending it is the plain sketch. When the
  * basemap is installed (or removed), the snapshots of the offline map are
  * dropped and the cards drawn again.
  *
  * Jobs pick their base map right before they start, an online one only
  * under a permit taken then (holder `snapshot`, [[MapGuard]]), and a change
  * of a map destination's rule or the pause drops the queued ones and
  * cancels the running one ([[SnapshotQueue]]); every card is then drawn
  * again, so none is left waiting for a dropped job. The feed holds the
  * queue before its cards leave the window ([[hold]]: its screen hidden, the
  * activity stopped, a reload) and releases it when it is shown
  * ([[release]]): cards detach one by one, and a card still attached must
  * not start its job meanwhile. Main thread only.
  */
object DayMap:
  private val cache = android.util.LruCache[String, Bitmap](40)
  /** The running snapshotter is its handle: referenced until it calls back. */
  private val queue = SnapshotQueue[MapSnapshotter](Settings.gate)
  Settings.gate.onChange(ds => if ds.exists(Dest.Tiles) then AshidoriApp.app.getMainExecutor.execute { () =>
    queue.invalidate().foreach(_.cancel())
    // Cards whose job was dropped would keep the plain sketch.
    cards.asScala.toSeq.foreach(_.invalidateMap())
  })
  // OpenFreeMap's style arrived: cards waiting for it are drawn.
  OnlineStyles.listen(() => cards.asScala.toSeq.foreach(_.invalidateMap()))

  /** A card's frame; `redraw` picks its base map again. */
  private final class MapCard(c: Context) extends FrameLayout(c):
    var redraw: () => Unit = () => ()
    private var stale = false
    /** A job of it is queued or rendering. */
    var waiting = false
    /** Moves on each time it leaves the window: a job queued before does not start. */
    var generation = 0
    /** Its snapshot rendering now, cancelled when it leaves the window. */
    var running: Option[MapSnapshotter] = None
    /** In a window (its screen is shown; a page of it may be hidden): only then may a job of it start. */
    def onScreen: Boolean = isAttachedToWindow
    /** A job of it did not start (not in a window yet, or not any more): drawn when it is attached. */
    def deferred(): Unit = if !isAttachedToWindow then stale = true
    /** The queue was held: its job (queued or rendering, cancelled) is gone; drawn again on [[resumed]]. */
    def held(): Unit =
      running = None
      if waiting then stale = true
      waiting = false
    /** The queue was released: a card still in a window whose job was dropped asks again. */
    def resumed(): Unit =
      if stale && isAttachedToWindow then
        stale = false
        redraw()
    /** Now if shown; a detached card (an old feed, another tab) when it comes back. */
    def invalidateMap(): Unit =
      if isAttachedToWindow then
        stale = false
        redraw()
      else stale = true
    override def onAttachedToWindow(): Unit =
      super.onAttachedToWindow()
      if stale then
        stale = false
        redraw()
    /** Off screen: its snapshot holds no permit, and no style fetch is retried for it. */
    override def onDetachedFromWindow(): Unit =
      super.onDetachedFromWindow()
      generation += 1
      Net.guard.release("snapshot", this)
      OnlineStyles.forget(this)
      // Its rendering stops (its requests are cancelled), and the queue goes
      // on unless held (the screen is leaving: nothing starts until shown).
      waiting = false
      running.foreach { s =>
        running = None
        s.cancel()
        queue.finished(s)
      }
      // Drawn again when it comes back (from the cache, or asking anew).
      stale = true

  /** Cards built so far, while something still shows them. */
  private val cards = java.util.Collections.newSetFromMap(java.util.WeakHashMap[MapCard, java.lang.Boolean]())
  private var basemapVersion = Basemap.installedVersion
  private val stopBasemap = BasemapDownload.listen { () =>
    val v = Basemap.installedVersion
    if v != basemapVersion then
      basemapVersion = v
      // Snapshots of the old data (keyed by its version) are stale.
      val stale = s"${MapStyle.Offline.key}@"
      cache.snapshot().keySet().asScala.filter(_.startsWith(stale)).foreach(cache.remove)
      cards.asScala.toSeq.foreach(_.invalidateMap())
  }

  /** The chip on a picture drawn without a base map, at its top left: the
    * feed's floating button covers the bottom right of a card near the
    * bottom of the screen.
    */
  def noMapChip(act: MainActivity): NoBasemapChip =
    given Ctx = act
    val chip = NoBasemapChip(act)
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.TOP | Gravity.START)
    lp.setMargins(dp(8), dp(8), dp(8), dp(8))
    chip.setLayoutParams(lp)
    chip

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
    // A single day also shows its stretches without a record (grey dots).
    val gaps = if tls.size == 1 then tls.head.segments.collect { case u: Unrecorded => MapPath.unrecorded(u) } else Nil
    // Repeated stays at one place draw one dot.
    val stays = visits.map(v => v.placeId.map(Left(_)).getOrElse(Right(v.latLng)) -> v.latLng).distinctBy(_._1).map(_._2)
    val pts = (paths ++ gaps).flatMap(_.points).map(_.latLng) ++ stays
    if pts.isEmpty then return None

    val dm = act.getResources.getDisplayMetrics
    val wPx = dm.widthPixels - dp(insetDp)
    val hPx = dp(heightDp)
    val sketch = RouteSketchView(act, paths.map(p => (p.points.map(_.latLng), android.graphics.Color.parseColor(p.color))), stays,
      gaps.map(g => (g.points.head.latLng, g.points.last.latLng)))
    val img = ImageView(act)
    img.setScaleType(ImageView.ScaleType.CENTER_CROP)
    val frame = MapCard(act)
    frame.addView(sketch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val chip = noMapChip(act)
    frame.addView(chip)
    val bg = GradientDrawable()
    bg.setCornerRadius(dp(12).toFloat)
    bg.setColor(color(attr.surfaceContainerHigh))
    frame.setBackground(bg)
    frame.setClipToOutline(true)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx)
    lp.setMargins(dp(marginDp), dp(4), dp(marginDp), dp(8))
    frame.setLayoutParams(lp)

    // Same route, same picture: edits (mode changes) change the key.
    def key(style: MapStyle) =
      s"${style.cacheKey}/$id/${paths.map(p => (p.color, p.points.size, p.dashed)).hashCode}/${gaps.size}/${stays.hashCode}/$wPx"
    // The snapshot draws the base map's attribution itself.
    def show(b: Bitmap): Unit =
      img.setImageBitmap(b)
      sketch.grid = false
      chip.show(false)
    // No base map: the sketch on a grid is the picture.
    def sketchOnly(): Unit =
      img.setImageDrawable(null)
      sketch.grid = true
      chip.show(true)
    // A snapshot is coming: the plain sketch meanwhile.
    def pending(): Unit =
      img.setImageDrawable(null)
      sketch.grid = false
      chip.show(false)

    def start(style: MapStyle, json: String): Option[MapSnapshotter] =
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
      val builder = MapStyle.snapshotBuilder(style.key, json)
        .withSource(GeoJsonSource("day-route", TrackMap.lineFeatures(paths ++ gaps)))
        .withSource(GeoJsonSource("day-stays", FeatureCollection.fromFeatures(
          stays.map(s => Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))).asJava)))
        .withLayer(LineLayer("day-route-line", "day-route").withProperties(
          PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
          PropertyFactory.lineWidth(4f),
          PropertyFactory.lineOpacity(Expression.switchCase(Expression.get("dashed"), Expression.literal(0.45f), Expression.literal(0.95f))),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        ).withFilter(Expression.eq(Expression.get("gap"), Expression.literal(false))))
        .withLayer(LineLayer("day-route-gap", "day-route").withProperties(
          PropertyFactory.lineColor(Palette.Unrecorded),
          PropertyFactory.lineWidth(3.5f),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
          PropertyFactory.lineDasharray(Array[java.lang.Float](0.1f, 2.2f))
        ).withFilter(Expression.eq(Expression.get("gap"), Expression.literal(true))))
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
      MapStyle.applyNetworkPolicy(act)
      val snap = MapSnapshotter(act, opts)
      frame.running = Some(snap)
      frame.waiting = true
      snap.start(
        s => {
          if frame.running.contains(snap) then
            frame.running = None
            frame.waiting = false
          cache.put(key(style), s.getBitmap)
          show(s.getBitmap)
          queue.finished(snap)
        },
        (_: String) => {
          if frame.running.contains(snap) then
            frame.running = None
            frame.waiting = false
          // Offline or no tiles: keep the sketch. The basemap removed
          // meanwhile: say so.
          if MapStyle.forSnapshot == Outcome.NoBasemap then sketchOnly()
          queue.finished(snap)
        }
      )
      Some(snap)

    def render(): Unit = MapStyle.forSnapshot match
      case Outcome.NoBasemap => sketchOnly()
      case Outcome.Draw(style) =>
        Option(cache.get(key(style))) match
          case Some(b) => show(b)
          case None =>
            pending()
            val gen = frame.generation
            frame.waiting = true
            queue.enqueue { () =>
              frame.waiting = false
              // A card that left the screen since: nothing (drawn when it comes back).
              if frame.generation != gen || !frame.onScreen then
                Net.log(s"snapshot deferred: card not in a window (queued for $gen, now ${frame.generation}, attached ${frame.isAttachedToWindow})")
                frame.deferred()
                None
              // Rendered while scrolling, without the user asking for that
              // area: an online map only if its destination is automatic, by
              // the rules now, not when the card was laid out.
              else MapStyle.snapshot(frame) match
                case Some((Outcome.Draw(s), Some(json))) => start(s, json)
                case Some((_, _)) =>
                  sketchOnly()
                  None
                // OpenFreeMap's style is being fetched: drawn when it arrives.
                case None => None
            }

    frame.redraw = () => render()
    cards.add(frame)
    render()
    Some(frame)

  /** The feed leaves the screen, or is about to remove its cards (reload):
    * called before they detach. Queued jobs are dropped and the running
    * one cancelled; no job starts until [[release]].
    */
  def hold(): Unit =
    val r = queue.hold()
    r.foreach(_.cancel())
    cards.asScala.toSeq.foreach(_.held())
    Net.log(s"day maps held (cancelled ${r.size})")

  /** The feed is shown: cards whose job was dropped ask again, and queued jobs start. */
  def release(): Unit =
    if queue.isHeld then
      Net.log("day maps released")
      cards.asScala.toSeq.foreach(_.resumed())
      queue.release()
