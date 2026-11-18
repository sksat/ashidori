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
import ashidori.core.BaseMapPolicy.Outcome

/** A line on the map with its color (CSS hex). `dashed`: drawn faint (a
  * rough position); `gap`: a stretch without a record (記録なし), grey dots.
  */
final case class MapPath(points: Seq[Fix], color: String = TrackMap.DefaultLine, dashed: Boolean = false, gap: Boolean = false)

object MapPath:
  /** 記録なし: from where the record stopped to where it resumed. */
  def unrecorded(u: Unrecorded): MapPath =
    MapPath(Seq(Fix(u.start, u.from.lat, u.from.lon), Fix(u.end, u.to.lat, u.to.lon)), Palette.Unrecorded, gap = true)

/** A marker on the map; `key` comes back in click callbacks. */
/** `weight` scales the circle (e.g. number of visits). */
final case class MapMarker(at: LatLng, key: String, label: String = "", weight: Double = 1)

/** MapLibre map with layers for tracks, visits, check-ins and one highlighted
  * path. Data set before the style finishes loading is applied afterwards.
  *
  * Where the chosen map's destination is sent to on action only
  * ([[SendGate]]), it shows the on-device map until the user asks for an
  * online one on this map ([[detailed]]). That choice is remembered for
  * this map's `surface` ([[SendGate.remember]]): kept across tab switches
  * and restarts until the user switches back, picks another base map here,
  * or the destination's rule changes. Another surface
  * does not inherit it. Only while on screen with the app in the foreground
  * ([[onScreen]], [[onStart]]) does it load the online map and hold a grant
  * that lets the map library use the network; otherwise ([[offScreen]],
  * [[onStop]]) it gives up the grant and destroys a map view holding an
  * online map (cancelling its requests), keeping the consent and camera.
  *
  * Without the on-device map's data ([[Outcome.NoBasemap]]) it draws the
  * routes and stays on a plain canvas, still panned and zoomed as a map.
  * Where an online map would be shown on action, a card then offers the
  * download and this view's online map; otherwise (the user chose the
  * offline map), the chip of the timeline's cards ([[NoBasemapChip]]) says
  * so and offers the download.
  *
  * Its online style is marked for `surface` ([[MapGuard]]): the map
  * library's requests pass only under this view's permit (and, on action,
  * its grant), within the zoom of the destination's level. The map
  * library's attribution dialog is replaced by the app's ([[showAttribution]]),
  * which adds no camera position to the links.
  */
final class TrackMap(act: MainActivity, saved: Bundle, surface: String, controlsOnTop: Boolean = false):
  MapLibre.getInstance(act)
  Net.mapClient(act)
  TrackMap.enlargeCache(act)
  /** Lifecycle the activity has reached (kept here: a map view is made and
    * destroyed over time, see [[dropMapView]]).
    */
  private var started = act.getLifecycle.getCurrentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
  private var resumed = act.getLifecycle.getCurrentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
  /** The MapLibre view; null while dropped ([[dropMapView]]). */
  private var mapView: MapView = null
  /** Venues of the on-device index ([[VenueLayer]]). */
  private val venues = VenueLayer(act)
  /** Lifecycle calls the current [[mapView]] has had. */
  private var mvStarted = false
  private var mvResumed = false
  act.registerMap(this)
  TrackMap.all += this
  MapStyle.applyNetworkPolicy(act)

  /** The user chose an online map on this surface, and the consent still
    * holds (same setting, same map).
    */
  private def detailedFlag: Boolean = MapStyle.consented(surface).isDefined
  def detailed: Boolean = detailedFlag
  /** On screen (between [[onScreen]] and [[offScreen]]). */
  private var visible = false
  /** Base map loaded (or loading) on this map. */
  private var shown: Option[Outcome[MapStyle]] = None

  private var map: Option[(MapLibreMap, Style)] = None
  /** Work asked for while a style loads or the map view is dropped, run
    * once a style has loaded: the latest update of each kind (data, live
    * tail, highlight, a layer's visibility), then the last camera intent
    * (see [[PendingMapUpdates]]). It stays small however long the map is
    * away (e.g. live updates in the background).
    */
  private val pending = PendingMapUpdates[() => Unit, TrackMap.CameraIntent]()
  /** Points of the latest [[setData]], what a pending fit shows. */
  private var dataPoints = Seq.empty[LatLng]

  /** Queues the latest data again for a new style, unless newer data waits already. */
  private def requeueData(): Unit =
    if !pending.has("data") then lastData.foreach(d => pending.update("data", d))

  /** Asks to move the camera: now, or after the pending updates once a style has loaded. */
  private def moveCamera(c: TrackMap.CameraIntent): Unit =
    if map.isDefined then applyCamera(c) else pending.camera(c)

  private def applyCamera(c: TrackMap.CameraIntent): Unit = c match
    case TrackMap.CameraIntent.FitData => fitTo(dataPoints)
    case TrackMap.CameraIntent.Show(pts) => fitTo(pts)
    case TrackMap.CameraIntent.Focus(p, zoom) =>
      map.foreach((m, _) => m.easeCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), zoom)))
  var onMarkerClick: (String, String) => Unit = (_, _) => ()
  /** A venue of the venue layer was picked (a tap, or TalkBack), or the pick
    * ended by a tap elsewhere on the map (None).
    */
  var onVenue: Option[VenueLayer.Picked] => Unit = _ => ()
  venues.onPick = p => onVenue(p)
  /** Venues take a tap before the stays and check-ins (the venue picker, whose stay marker lies among them). */
  var venuesFirst = false
  /** What activating a venue does, for TalkBack ([[VenueLayer.actionLabel]]). */
  def venueActionLabel_=(l: Option[String]): Unit = venues.actionLabel = l
  def venueActionLabel: Option[String] = venues.actionLabel

  /** Marks `p` as the picked venue (None: none), without [[onVenue]]. */
  def pickVenue(p: Option[VenueLayer.Picked]): Unit = venues.setPick(p)
  def pickedVenue: Option[VenueLayer.Picked] = venues.pick
  /** `v` as this map draws it ([[VenueLayer.pickedFor]]). */
  def venueAsDrawn(v: Venue, tag: Option[(String, String)]): VenueLayer.Picked = venues.pickedFor(v, tag)
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
    b.setContentDescription("地図の種類を変更")
    b.setOnClickListener(_ => pickBaseMap())
    b

  /** Explicit switch to the online map for this view (on-action maps only). */
  private val detailButton =
    val b = Ui.button("", tonal = true)(toggleDetailed())(using act)
    b.setTextSize(12f)
    b.setMinHeight(0)
    b.setMinimumHeight(0)
    b.setInsetTop(0)
    b.setInsetBottom(0)
    b.setPadding(Ui.dp(12)(using act), Ui.dp(6)(using act), Ui.dp(12)(using act), Ui.dp(6)(using act))
    b

  private def compact(b: com.google.android.material.button.MaterialButton): b.type =
    b.setTextSize(12f)
    b.setMinHeight(0)
    b.setMinimumHeight(0)
    b.setInsetTop(0)
    b.setInsetBottom(0)
    b.setPadding(Ui.dp(12)(using act), Ui.dp(6)(using act), Ui.dp(12)(using act), Ui.dp(6)(using act))
    b

  // ---- without a base map (setting on): why, and the two ways to a map ----
  private val noMapTitle = Ui.text("", 13f, bold = true)(using act)
  private val downloadButton =
    compact(Ui.button(BasemapUi.downloadLabel(BasemapDownload.status))(BasemapUi.requestDownload(act))(using act))
  private val grantButton =
    val b = compact(Ui.button("", tonal = true)(setDetailed(true))(using act))
    b.setText(TrackMap.detailLabel(MapStyle.detailMap))
    b
  /** Shown in place of the detail button while the map has no base map. */
  private val noMapCard =
    given Ui.Ctx = act
    val wrapLp = () =>
      val lp = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      lp.topMargin = Ui.dp(6)
      lp
    downloadButton.setLayoutParams(wrapLp())
    grantButton.setLayoutParams(wrapLp())
    Ui.tint(grantButton, Ui.color(Ui.attr.surfaceContainerHigh), Ui.color(Ui.attr.onSurface))
    val card = Ui.vbox(noMapTitle, downloadButton, grantButton)
    card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10))
    val bg = android.graphics.drawable.GradientDrawable()
    bg.setCornerRadius(Ui.dp(16).toFloat)
    bg.setColor(Ui.color(Ui.attr.surface))
    card.setBackground(bg)
    card.setElevation(Ui.dp(3).toFloat)
    card.setVisibility(android.view.View.GONE)
    card

  /** Without a base map and with the setting off: the chip of the
    * timeline's cards ([[NoBasemapChip]]) where the detail button would be.
    */
  private val noMapBadge =
    val b = NoBasemapChip(act)
    val m = Ui.dp(10)(using act)
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      (if controlsOnTop then Gravity.TOP else Gravity.BOTTOM) | Gravity.END)
    lp.setMargins(m, m, m, m)
    b.setLayoutParams(lp)
    b

  /** Basemap version the loaded style was drawn from (the offline map, or
    * a raster map's fonts, [[MapStyle.basemapFonts]]).
    */
  private var loadedBasemap: Option[String] = None

  private def noMap: Boolean = shown.contains(Outcome.NoBasemap)

  private def updateNoMap(): Unit =
    val offer = shown.exists(o => BaseMapPolicy.offerAlternatives(MapStyle.when, MapStyle.detailMap, o))
    noMapCard.setVisibility(if offer then android.view.View.VISIBLE else android.view.View.GONE)
    noMapBadge.show(noMap && !offer)
    if offer then
      val st = BasemapDownload.status
      noMapTitle.setText(BasemapUi.cardTitle(st))
      // A failure is the exception: its reason in the error colour.
      noMapTitle.setTextColor(Ui.color(st match
        case _: BasemapStatus.Failed => Ui.attr.error
        case _ => Ui.attr.onSurface)(using act))
      downloadButton.setText(BasemapUi.downloadLabel(st))
      downloadButton.setVisibility(if BasemapUi.offersDownload(st) then android.view.View.VISIBLE else android.view.View.GONE)

  private def updateDetailButton(): Unit =
    updateNoMap()
    val offer = noMapCard.getVisibility == android.view.View.VISIBLE
    val detail = MapStyle.detailMap
    detailButton.setVisibility(if BaseMapPolicy.offerDetail(MapStyle.when, detail, detailedFlag, offer) then android.view.View.VISIBLE else android.view.View.GONE)
    val online = MapStyle.consented(surface).fold("")(_.label)
    // After the choice, the button itself shows that it holds (its colour and
    // a second line); two lines' room either way, so it keeps its height.
    detailButton.setMinLines(2)
    detailButton.setText(
      if !detailedFlag then TrackMap.detailLabel(detail)
      // Back to the offline map, or to none when it is not downloaded.
      else if Basemap.available then TrackMap.twoLines(s"オフライン地図に戻す（${online}を表示中）", TrackMap.KeptOnLine)
      else TrackMap.twoLines(s"オンライン地図を終了（${online}を表示中）", TrackMap.KeptOnLine))
    // Sending is the exception: mark it with the tertiary (warning) colours.
    given Ui.Ctx = act
    if detailedFlag then Ui.tint(detailButton, Ui.color(Ui.attr.tertiaryContainer), Ui.color(Ui.attr.onTertiaryContainer))
    else Ui.tint(detailButton, Ui.color(Ui.attr.surfaceContainerHigh), Ui.color(Ui.attr.onSurface))

  private def toggleDetailed(): Unit =
    setDetailed(!detailedFlag)

  /** Switches this surface between the offline and an online map
    * (`online`, by default the chosen one), remembering the choice.
    */
  def setDetailed(on: Boolean, online: Option[MapStyle] = MapStyle.detailMap): Unit =
    if on then
      for ms <- online; d <- ms.dest do Settings.gate.remember(surface, ms.consentId, d)
    else Settings.gate.forget(surface)
    sync()

  /** Shown and the app in the foreground: only then may it hold an online
    * map and use the network.
    */
  private def active: Boolean = visible && started

  /** Grant and base map as [[active]] and the consent say now. A map that
    * stops being active gives up its grant and drops its online map (the
    * whole map view, see [[refreshBaseMap]]) before this returns, so before
    * the next screen's map is shown and may turn the network back on.
    */
  private def sync(): Unit =
    // A grant: a permit of the consented map's destination, to this view.
    MapStyle.consented(surface).flatMap(_.dest).filter(_ => active).flatMap(Settings.gate.permit(_, Mode.OnAction)) match
      case Some(p) => Settings.gate.grant(this, p)
      // Without the grant its on-action requests are refused ([[MapGuard]]);
      // an automatic map keeps its registration.
      case None =>
        Settings.gate.revoke(this)
        // Off screen: no retry of a style fetch for it either.
        if !active then OnlineStyles.forget(this)
    if !active then
      refreshBaseMap()
      MapStyle.applyNetworkPolicy(act)
    else
      MapStyle.applyNetworkPolicy(act)
      // Back on screen still without its online style: asked for again.
      refreshBaseMap(force = awaitingStyle)
    updateDetailButton()

  /** The view is on screen: with this surface's consent it loads the online
    * map again and may use the network.
    */
  def onScreen(): Unit =
    visible = true
    sync()

  /** The view left the screen (its screen was hidden or popped): its grant
    * ends and, holding an online map, it destroys its map view, which
    * cancels that map's requests, so nothing of it can go out once another
    * map has the network. The consent and the camera stay for [[onScreen]].
    */
  def offScreen(): Unit =
    visible = false
    sync()

  /** Map plus overlay controls; screens add this instead of `mapView`. */
  val view: FrameLayout =
    val f = FrameLayout(act)
    // The venues for TalkBack, over the map view (added under this at index 0).
    f.addView(venues.accessibilityView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    // Where the detail button is, clear of the controls.
    val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      (if controlsOnTop then Gravity.TOP else Gravity.BOTTOM) | Gravity.END)
    clp.setMargins(Ui.dp(64)(using act), Ui.dp(10)(using act), Ui.dp(12)(using act), Ui.dp(14)(using act))
    f.addView(noMapCard, clp)
    f.addView(noMapBadge)
    // Where the map library put its attribution button.
    val info = android.widget.ImageButton(act)
    info.setImageResource(org.maplibre.android.R.drawable.maplibre_info_bg_selector)
    info.setBackground(null)
    info.setContentDescription("地図の出典")
    info.setOnClickListener(_ => showAttribution())
    val ilp = FrameLayout.LayoutParams(Ui.dp(28)(using act), Ui.dp(28)(using act), Gravity.BOTTOM | Gravity.START)
    ilp.setMargins(Ui.dp(88)(using act), 0, 0, Ui.dp(2)(using act))
    f.addView(info, ilp)
    updateDetailButton()
    f

  /** The attribution of the sources drawn, as the app's dialog: each link
    * opens exactly as the source gives it (the map library's dialog may add
    * the camera position to some, design 4.8).
    */
  private def showAttribution(): Unit =
    given Ui.Ctx = act
    val html = map.toSeq.flatMap((_, style) => style.getSources.asScala.flatMap(s => Option(s.getAttribution)).filter(_.nonEmpty)).distinct
    val lines = if html.nonEmpty then html else Seq("地図の出典なし")
    val body = Ui.text(android.text.Html.fromHtml(lines.mkString("<br>"), android.text.Html.FROM_HTML_MODE_COMPACT), 14f)
    body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance())
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("地図の出典")
      .setView(Ui.padded(body, 24, 8))
      .setPositiveButton("閉じる", null)
      .show()

  // Follow the logger's fixes; the map never turns GNSS on by itself.
  private val stopObserving =
    LoggerState.observe(() => LoggerState.lastFix.foreach(f => updateMyLocation(TrackMap.toLocation(f))))

  private var clickInstalled = false
  /** Re-applies the latest data after a base map change. */
  private var lastData: Option[() => Unit] = None

  /** The MapLibre map, also while a style is loading (unlike [[map]]). */
  private var mlMap: Option[MapLibreMap] = None
  /** Camera asked for ([[moveTo]]) before the map was ready. */
  private var initialCamera: Option[(LatLng, Double)] = None

  /** Bundle for the first map view only (a recreated one starts from [[initialCamera]]). */
  private var savedState = saved

  /** Makes the map view (again) under the overlay controls, at the camera
    * it had, with the base map the policy picks now and the latest data.
    */
  private def makeMapView(): Unit =
    val mv = MapView(act)
    mv.onCreate(savedState)
    savedState = null
    mapView = mv
    mvStarted = false
    mvResumed = false
    view.addView(mv, 0)
    if started then { mv.onStart(); mvStarted = true }
    if resumed then { mv.onResume(); mvResumed = true }
    clickInstalled = false
    requeueData()
    mv.getMapAsync { m =>
      if mapView eq mv then
        mlMap = Some(m)
        m.getUiSettings.setRotateGesturesEnabled(false)
        m.getUiSettings.setTiltGesturesEnabled(false)
        // The app's attribution dialog instead ([[showAttribution]]).
        m.getUiSettings.setAttributionEnabled(false)
        initialCamera.foreach(c => m.moveCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(c._1.lat, c._1.lon), c._2)))
        initialCamera = None
        loadStyle(m, MapStyle.forView(surface, active))
    }

  /** Destroys the map view, keeping the camera for [[makeMapView]].
    * Destroying it cancels its requests; replacing its style would leave the
    * old sources' requests until it renders again, which a map off screen
    * does not.
    */
  private def dropMapView(): Unit =
    val mv = mapView
    if mv != null then
      initialCamera = camera.orElse(initialCamera)
      venues.detach()
      mapView = null
      mlMap = None
      map = None
      shown = None
      view.removeView(mv)
      if mvResumed then mv.onPause()
      if mvStarted then mv.onStop()
      mv.onDestroy()
      mvStarted = false
      mvResumed = false

  makeMapView()

  /** Loads a base map (or the plain canvas without one) and (re)adds our
    * sources and layers on top of it.
    */
  private def loadStyle(m: MapLibreMap, base: Outcome[MapStyle]): Unit =
    map = None
    venues.detach()
    shown = Some(base)
    loadedBasemap = Basemap.installedVersion
    updateDetailButton()
    awaitingStyle = false
    val builder = base match
      case Outcome.Draw(ms) =>
        // An online map under a permit of now: automatic, or this view's grant.
        val permit = ms.dest.flatMap { d =>
          if MapStyle.when(ms) == When.Auto then Settings.gate.permit(d, Mode.Auto)
          else Settings.gate.granted(this).filter(_.dest == d)
        }
        if ms.online && permit.isEmpty then Net.guard.release(surface, this)
        ms.styleJson(surface, this, permit) match
          case Some(json) => Style.Builder().fromJson(json)
          // OpenFreeMap's style is being fetched, or nothing may be sent: none yet.
          case None =>
            awaitingStyle = permit.nonEmpty
            MapStyle.blankBuilder(act)
      case Outcome.NoBasemap => MapStyle.blankBuilder(act)
    m.setStyle(builder, (style: Style) => {
      for id <- Seq("track", "highlight", "visits", "checkins", "live", "stay-area") do style.addSource(GeoJsonSource(id))
      // A stay's area ([[markStay]]): its radius in metres, as the accuracy circle.
      style.addLayer(
        CircleLayer("stay-area", "stay-area").withProperties(
          PropertyFactory.circleColor(Palette.Stay),
          PropertyFactory.circleOpacity(0.12f),
          PropertyFactory.circleStrokeColor(Palette.Stay),
          PropertyFactory.circleStrokeWidth(1.5f),
          PropertyFactory.circleStrokeOpacity(0.6f),
          PropertyFactory.circleRadius(
            Expression.interpolate(Expression.exponential(2), Expression.zoom(),
              Expression.stop(0, Expression.division(Expression.get("r"), Expression.literal(78271.5))),
              Expression.stop(22, Expression.division(Expression.get("r"), Expression.literal(0.0187)))))
        )
      )
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
        ).withFilter(Expression.all(
          Expression.eq(Expression.get("dashed"), Expression.literal(false)),
          Expression.eq(Expression.get("gap"), Expression.literal(false))))
      )
      // Where the position was only roughly known (GNSS gap, poor accuracy).
      style.addLayer(
        LineLayer("track-dashed", "track").withProperties(
          PropertyFactory.lineColor(Expression.toColor(Expression.coalesce(Expression.get("color"), Expression.literal(TrackMap.DefaultLine)))),
          // Faded rather than dashed: line-dasharray did not render with
          // data-driven colors (seen on the emulator's GL).
          PropertyFactory.lineWidth(3f),
          PropertyFactory.lineOpacity(0.4f)
        ).withFilter(Expression.all(
          Expression.eq(Expression.get("dashed"), Expression.literal(true)),
          Expression.eq(Expression.get("gap"), Expression.literal(false))))
      )
      // 記録なし: grey dots in one colour (dashes did not render with
      // data-driven colours, see above).
      style.addLayer(
        LineLayer("track-gap", "track").withProperties(
          PropertyFactory.lineColor(Palette.Unrecorded),
          PropertyFactory.lineWidth(4f),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
          PropertyFactory.lineDasharray(Array[java.lang.Float](0.1f, 2.2f))
        ).withFilter(Expression.eq(Expression.get("gap"), Expression.literal(true)))
      )
      // The selected item: its line thicker on a halo, or a ring at a stay.
      style.addLayer(
        LineLayer("highlight-halo", "highlight").withProperties(
          PropertyFactory.lineColor("#ffffff"),
          PropertyFactory.lineWidth(12f),
          PropertyFactory.lineOpacity(0.9f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
      )
      style.addLayer(
        LineLayer("highlight-line", "highlight").withProperties(
          PropertyFactory.lineColor(Expression.toColor(Expression.coalesce(Expression.get("color"), Expression.literal(TrackMap.DefaultLine)))),
          PropertyFactory.lineWidth(8f),
          PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
        ).withFilter(Expression.all(
          Expression.eq(Expression.geometryType(), Expression.literal("LineString")),
          Expression.eq(Expression.get("gap"), Expression.literal(false))))
      )
      style.addLayer(
        LineLayer("highlight-gap", "highlight").withProperties(
          PropertyFactory.lineColor(Palette.Unrecorded),
          PropertyFactory.lineWidth(8f),
          PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
          PropertyFactory.lineDasharray(Array[java.lang.Float](0.1f, 1.8f))
        ).withFilter(Expression.all(
          Expression.eq(Expression.geometryType(), Expression.literal("LineString")),
          Expression.eq(Expression.get("gap"), Expression.literal(true))))
      )
      style.addLayer(
        CircleLayer("visit-circles", "visits").withProperties(
          PropertyFactory.circleColor(Palette.Stay),
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
        CircleLayer("highlight-ring", "highlight").withProperties(
          PropertyFactory.circleRadius(14f),
          PropertyFactory.circleColor(Palette.Stay),
          PropertyFactory.circleOpacity(0.25f),
          PropertyFactory.circleStrokeColor(Palette.Stay),
          PropertyFactory.circleStrokeWidth(3f)
        ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
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
          val venue = if venuesFirst then venues.hit(pt) else None
          venue.foreach(venues.pickNow)
          if venue.nonEmpty then true
          else hits.headOption match
            case Some(f) =>
              if venues.pick.nonEmpty then
                venues.setPick(None)
                onVenue(None)
              onMarkerClick(f.getStringProperty("layer"), f.getStringProperty("key"))
              true
            // A venue drawn there, or the end of a pick.
            case None => venues.tap(pt)
        }
      enableMyLocation(m, style)
      // Shops and other places from the venue index, under the basemap's labels and our layers.
      venues.attach(m, style, sprite = base == Outcome.Draw(MapStyle.Offline), below = "live-line")
      map = Some((m, style))
      val (run, cam) = pending.drain()
      run.foreach(_())
      cam.foreach(applyCamera)
    })

  // The basemap was downloaded or deleted: draw the map with or without it
  // (or a new version of it). Progress only updates the card.
  // The venue data was downloaded or deleted: the layer reads the view again.
  private val stopVenues = VenueDownload.listen(() => venues.refresh())

  /** Drawn without its online style, which is being fetched ([[OnlineStyles]]). */
  private var awaitingStyle = false
  private val stopStyles = OnlineStyles.listen(() => if awaitingStyle then refreshBaseMap(force = true))

  private val stopBasemap = BasemapDownload.listen { () =>
    val usesBasemap = shown.exists {
      case Outcome.Draw(s) => s == MapStyle.Offline || s.basemapFonts
      case Outcome.NoBasemap => false
    }
    val stale = usesBasemap && loadedBasemap != Basemap.installedVersion
    refreshBaseMap(force = stale)
  }

  /** Reloads the base map the policy picks now (after a choice, a setting
    * change or a detail switch), keeping what is drawn. `force` reloads the
    * same base map (e.g. a new custom URL).
    */
  def refreshBaseMap(force: Boolean = false): Unit =
    updateDetailButton()
    val ms = MapStyle.forView(surface, active)
    val holdsOnline = shown.exists {
      case Outcome.Draw(s) => s.online
      case Outcome.NoBasemap => false
    }
    val toOnline = ms match
      case Outcome.Draw(s) => s.online
      case Outcome.NoBasemap => false
    // Off screen, an online map is dropped with its view (not restyled).
    if !active && holdsOnline && !toOnline then dropMapView()
    else if mapView == null then
      if active then makeMapView()
    else if force || !shown.contains(ms) then
      mlMap.foreach { m =>
        requeueData()
        loadStyle(m, ms)
      }

  /** Its loaded online style is of one of `dests`. */
  def drawsFrom(dests: Set[Dest]): Boolean = shown.exists {
    case Outcome.Draw(s) => s.dest.exists(dests)
    case Outcome.NoBasemap => false
  }

  /** Applies a choice from the picker. With the setting on, choosing an
    * online map is the explicit request to show it on this surface, and
    * remembered there; the offline map ends that.
    */
  private def choose(ms: MapStyle): Unit =
    MapStyle.current = ms
    // An on-action map chosen here is the explicit request to show it on
    // this surface; any other choice (offline, automatic) ends that. Other maps keep what was
    // chosen on them (or a consent to the old custom URL ends).
    if ms.online && MapStyle.when(ms) == When.OnAction then setDetailed(true, Some(ms))
    else setDetailed(false)
    TrackMap.all.foreach(t => t.refreshBaseMap(force = ms == MapStyle.Custom && (t eq this)))

  /** Picker as a 2-column grid of thumbnails rendered at the current view.
    * A thumbnail of an online map is rendered only where its destination is
    * sent to automatically, under a permit of its own holder
    * (`thumb:<dest>`); the others show a bundled sample, and a map that is
    * never sent to is marked so and opens its destination's settings.
    */
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
      img.setContentDescription(ms.label)
      val never = ms.online && MapStyle.when(ms) == When.Never
      val thumbnail = MapStyle.thumbnailAllowed(ms)
      // A sample at a fixed area stands in where a thumbnail at the current
      // view would send that area.
      val sample = if thumbnail then None else ms.preview
      // No thumbnail: the bundled sample, marked as such with its credit; a
      // tinted panel that says the area would be sent (custom); or a plain
      // one for the offline map without its data.
      val face: android.view.View =
        if thumbnail then img
        else sample match
          case Some(res) =>
            img.setImageResource(res)
            val tag = Ui.text("見本", 11f, bold = true, colorAttr = Ui.attr.onSecondaryContainer)
            tag.setPadding(Ui.dp(6), Ui.dp(1), Ui.dp(6), Ui.dp(1))
            val tagBg = android.graphics.drawable.GradientDrawable()
            tagBg.setCornerRadius(Ui.dp(6).toFloat)
            tagBg.setColor(Ui.color(Ui.attr.secondaryContainer))
            tag.setBackground(tagBg)
            val credit = Ui.text(ms.previewCredit, 9f, colorAttr = Ui.attr.onSurface)
            credit.setPadding(Ui.dp(4), 0, Ui.dp(4), 0)
            credit.setBackgroundColor(Ui.color(Ui.attr.surface) & 0x00ffffff | 0xcc000000)
            credit.setMaxLines(2)
            val f = android.widget.FrameLayout(act)
            f.addView(img)
            val tlp = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START)
            tlp.setMargins(Ui.dp(6), Ui.dp(6), 0, 0)
            f.addView(tag, tlp)
            f.addView(credit, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END))
            f
          case None if ms == MapStyle.Offline =>
            val t = Ui.text("未取得", 13f, colorAttr = Ui.attr.onSurfaceVariant)
            t.setGravity(Gravity.CENTER)
            t.setBackgroundColor(Ui.color(Ui.attr.surfaceContainerHigh))
            t
          case None =>
            val t = Ui.text("表示範囲を送信", 13f, colorAttr = Ui.attr.onTertiaryContainer)
            t.setGravity(Gravity.CENTER)
            t.setBackgroundColor(Ui.color(Ui.attr.tertiaryContainer))
            t
      face.setLayoutParams(android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
      val selected =
        MapStyle.forView(surface, active) match
          case Outcome.Draw(s) => ms == s
          // The offline map, without its data.
          case Outcome.NoBasemap => ms == MapStyle.Offline
      val card = com.google.android.material.card.MaterialCardView(act)
      card.setRadius(Ui.dp(12).toFloat)
      card.setStrokeWidth(Ui.dp(if selected then 3 else 1))
      card.setStrokeColor(Ui.color(if selected then Ui.attr.primary else Ui.attr.outlineVariant))
      card.setCardElevation(0)
      val texts = Ui.vbox(Ui.text(ms.label, 15f, bold = true), Ui.caption(ms.note))
      // Under a sample: what choosing it does, as the second line.
      if never then texts.addView(Ui.text("送信しない設定", 12f, colorAttr = Ui.attr.onSurfaceVariant))
      else if sample.isDefined then texts.addView(Ui.text("表示範囲を送信", 12f, colorAttr = Ui.attr.tertiary))
      card.addView(Ui.vbox(face, Ui.padded(texts, 10, 6)))
      val lp = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      lp.setMargins(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6))
      card.setLayoutParams(lp)
      card.setOnClickListener { _ =>
        dialog.dismiss()
        if never then ms.dest.foreach(d => act.push(OutboundDestScreen(act, d)))
        else if ms == MapStyle.Custom then MapStyle.editCustom(act)(() => choose(MapStyle.Custom))
        else choose(ms)
      }
      if sample.isDefined then ()
      else if !thumbnail || (ms == MapStyle.Custom && MapStyle.custom._1.isEmpty) then img.setImageDrawable(null)
      else for
        cam <- camera
        // The offline map sends nothing; an online one under a permit of its own destination.
        json <- ms.dest match
          case None => ms.styleJson("thumb:offline", dialog, None)
          case Some(d) => Settings.gate.permit(d, Mode.Auto).flatMap(p => ms.styleJson(s"thumb:${d.key}", dialog, Some(p)))
      do
        val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(thumbW, thumbH)
          .withStyleBuilder(Style.Builder().fromJson(json))
          .withCameraPosition(cam)
          .withLogo(false)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(act, opts)
        snapshotters += snap
        MapStyle.track(snap)
        snap.start(s => { MapStyle.untrack(snap); img.setImageBitmap(s.getBitmap) },
          (_: String) => MapStyle.untrack(snap))
      card
    }
    val grid = Ui.vbox(cards.grouped(2).map(row => Ui.hbox(row*)).toSeq*)
    // The thumbnails were chosen under the setting as it was: a change closes the picker.
    val stop = Settings.gate.onChange(ds => if ds.exists(Dest.Tiles) then act.runOnUiThread(() => dialog.dismiss()))
    dialog.setOnDismissListener { _ =>
      stop()
      snapshotters.foreach { s => s.cancel(); MapStyle.untrack(s) }
      Dest.Tiles.foreach(d => Net.guard.release(s"thumb:${d.key}", dialog))
      OnlineStyles.forget(dialog)
    }
    val head = Ui.vbox(Ui.text("地図の種類", 20f, bold = true))
    dialog.setContentView(Ui.scroll(Ui.padded(Ui.vbox(head, Ui.spacer(8), grid), 14, 12)))
    dialog.show()

  /** Runs `f` now, or once a style has loaded, replacing an earlier queued `kind`. */
  private def withStyle(kind: String)(f: (MapLibreMap, Style) => Unit): Unit =
    map match
      case Some((m, s)) => f(m, s)
      case None => pending.update(kind, () => map.foreach((m, s) => f(m, s)))

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
    dataPoints = paths.flatMap(_.points).map(_.latLng) ++ visits.map(_.at) ++ checkIns.map(_.at)
    withStyle("data") { (m, style) =>
      style.getSourceAs[GeoJsonSource]("track").setGeoJson(lines(paths))
      style.getSourceAs[GeoJsonSource]("visits").setGeoJson(markers("visit", visits))
      style.getSourceAs[GeoJsonSource]("checkins").setGeoJson(markers("checkin", checkIns))
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(FeatureCollection.fromFeatures(java.util.List.of()))
    }
    // The camera separately: a later focus or highlight still wins over this fit.
    if fit then moveCamera(TrackMap.CameraIntent.FitData)

  /** Replaces the live tail (empty to clear). */
  def setLive(points: Seq[Fix]): Unit =
    withStyle("live") { (_, style) =>
      Option(style.getSourceAs[GeoJsonSource]("live")).foreach(_.setGeoJson(lines(Seq(MapPath(points)))))
    }

  /** Draws `path` thicker over the rest and (`fit`) fits the camera to it. */
  def highlight(path: MapPath, fit: Boolean = true): Unit =
    withStyle("highlight") { (_, style) =>
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(lines(Seq(path)))
    }
    if fit then moveCamera(TrackMap.CameraIntent.Show(path.points.map(_.latLng)))

  /** A ring at a stay, (`fit`) centred on the map. */
  def highlightPlace(at: LatLng, fit: Boolean = true): Unit =
    withStyle("highlight") { (_, style) =>
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat))))
    }
    if fit then moveCamera(TrackMap.CameraIntent.Focus(at, 16))

  /** A stay's area: a disc of `radiusM` metres at `at` (None: none). */
  def markStay(area: Option[(LatLng, Double)]): Unit =
    withStyle("stay-area") { (_, style) =>
      Option(style.getSourceAs[GeoJsonSource]("stay-area")).foreach(_.setGeoJson(FeatureCollection.fromFeatures(area.toSeq.map { (at, r) =>
        val props = JsonObject()
        // Metres per pixel shrink with cos(latitude), as for the accuracy circle.
        props.addProperty("r", r / math.cos(math.toRadians(at.lat)))
        Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat), props)
      }.asJava)))
    }

  def clearHighlight(): Unit =
    withStyle("highlight") { (_, style) =>
      style.getSourceAs[GeoJsonSource]("highlight").setGeoJson(FeatureCollection.fromFeatures(java.util.List.of()))
    }

  /** Views over the map moved without a layout (a sheet sliding, a button
    * lifted): the venues read aloud are those still uncovered ([[VenueLayer.coversChanged]]).
    */
  def overlaysChanged(): Unit = venues.coversChanged()

  /** Where the camera is: centre and zoom (None before the map is ready). */
  def camera: Option[(LatLng, Double)] =
    mlMap.map(_.getCameraPosition).filter(_.target != null).map(c => (LatLng(c.target.getLatitude, c.target.getLongitude), c.zoom))
      .orElse(initialCamera)

  /** Puts the camera at once (no animation), also before the map is ready. */
  def moveTo(p: LatLng, zoom: Double): Unit =
    mlMap match
      case Some(m) => m.moveCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lon), zoom))
      case None    => initialCamera = Some((p, zoom))

  /** Fits the camera to `pts` (also before the map is ready). */
  def show(pts: Seq[LatLng]): Unit = moveCamera(TrackMap.CameraIntent.Show(pts))

  def focus(p: LatLng, zoom: Double = 15): Unit =
    moveCamera(TrackMap.CameraIntent.Focus(p, zoom))

  def setLayerVisible(layer: String, visible: Boolean): Unit =
    val ids = layer match
      case "track"    => Seq("track-line", "track-dashed", "track-gap")
      case "visit"    => Seq("visit-circles")
      case "checkin"  => Seq("checkin-circles")
      case other      => Seq(other)
    withStyle(s"layer:$layer") { (_, style) =>
      ids.foreach(id => Option(style.getLayer(id)).foreach(_.setProperties(
        PropertyFactory.visibility(if visible then Property.VISIBLE else Property.NONE))))
    }

  private def fitTo(pts: Seq[LatLng]): Unit =
    map.foreach { (m, _) =>
      val distinct = pts.distinct
      if distinct.size >= 2 then
        val b = LatLngBounds.Builder().includes(distinct.map(p => MLatLng(p.lat, p.lon)).asJava).build()
        val pad = Ui.dp(40)(using act)
        // Never more padding than the map is tall (a sheet still being laid
        // out reports all of it covered): MapLibre would zoom out to the world.
        val h = Option(mapView).map(_.getHeight).getOrElse(0)
        val bottom = if h > 0 then math.max(0, math.min(bottomInset, h - 3 * pad - Ui.dp(120)(using act))) else 0
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad, pad, pad + bottom))
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

  def onStart(): Unit =
    started = true
    if mapView != null && !mvStarted then { mapView.onStart(); mvStarted = true }
    sync()
  def onResume(): Unit =
    resumed = true
    if mapView != null && !mvResumed then { mapView.onResume(); mvResumed = true }
  def onPause(): Unit =
    resumed = false
    if mapView != null && mvResumed then { mapView.onPause(); mvResumed = false }
  /** In the background: as off screen ([[offScreen]]). */
  def onStop(): Unit =
    started = false
    sync()
    if mapView != null && mvStarted then { mapView.onStop(); mvStarted = false }
  def onLowMemory(): Unit = if mapView != null then mapView.onLowMemory()
  def onSaveInstanceState(b: Bundle): Unit = if mapView != null then mapView.onSaveInstanceState(b)
  def onDestroy(): Unit =
    stopBasemap()
    stopVenues()
    stopObserving()
    act.unregisterMap(this)
    // The grant ends with the view; the surface's consent stays.
    Settings.gate.revoke(this)
    Net.guard.release(surface, this)
    OnlineStyles.forget(this)
    stopStyles()
    TrackMap.all -= this
    // A closed detailed view no longer allows the network.
    MapStyle.applyNetworkPolicy(act)
    dropMapView()
    venues.destroy()

object TrackMap:
  /** Where the camera should go; while the map is not ready only the last one is kept. */
  enum CameraIntent:
    /** Show all of the latest data. */
    case FitData
    /** Show these points (a highlighted path). */
    case Show(points: Seq[LatLng])
    case Focus(at: LatLng, zoom: Double)

  val DefaultLine = "#006B5B"
  /** The explicit request for this view's online map, with the level it is sent at. */
  def detailLabel(detail: Option[MapStyle]): String =
    val level = detail.flatMap(_.dest).map(Settings.gate.rule(_).level).fold("")(OutboundUi.levelSuffix)
    s"オンライン地図で表示（表示範囲を送信$level）"
  /** Second line of the detail button while the choice holds. */
  val KeptOnLine = "再起動後も継続"

  /** A label with a smaller second line. */
  def twoLines(first: String, second: String): CharSequence =
    val sb = android.text.SpannableStringBuilder(first).append("\n")
    val start = sb.length
    sb.append(second)
    sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb

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
      props.addProperty("gap", p.gap)
      Feature.fromGeometry(LineString.fromLngLats(pts.map(f => Point.fromLngLat(f.lon, f.lat)).asJava), props)
    }.asJava)

  /** Live maps, so a base map change applies everywhere. */
  val all = scala.collection.mutable.ArrayBuffer.empty[TrackMap]

  /** After a map destination's rule or the pause changed (which already
    * ended its grants, see [[SendGate.setRule]]): snapshots rendered outside
    * the day map queue are cancelled, and every open map shows what the new
    * rules allow.
    */
  def privacyChanged(ctx: android.content.Context, dests: Set[Dest]): Unit =
    MapStyle.cancelSnapshots()
    MapStyle.applyNetworkPolicy(ctx)
    // A map of a changed destination loads its style again, under a new
    // permit and marks (also when only the level changed).
    all.foreach(t => t.refreshBaseMap(force = t.drawsFrom(dests)))
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

