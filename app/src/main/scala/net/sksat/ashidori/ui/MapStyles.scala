package net.sksat.ashidori.ui

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.Style
import net.sksat.ashidori.{AshidoriApp, Basemap, Log, Net, Regions, Settings}
import ashidori.core.{BaseMapPolicy, Dest, Mode, OfflineBasemapStyle, Outbound, Permit, Precision, Refetch, TileStyle, When}
import ashidori.core.BaseMapPolicy.Outcome

/** Selectable base maps. "オフライン地図" is drawn from data in the app and never
  * uses the network; the others fetch tiles of the visible area from their
  * servers. The built-in online ones are free to use without an API key;
  * "カスタム" takes any XYZ raster tile URL (e.g. a provider with a key).
  */
enum MapStyle(val key: String, val label: String, val note: String):
  case Offline extends MapStyle("offline", "オフライン地図", "通信なし")
  case Standard extends MapStyle("ofm-liberty", "標準", "OpenStreetMap（OpenFreeMap）")
  case Light extends MapStyle("ofm-positron", "シンプル", "淡色（OpenFreeMap）")
  case GsiStd extends MapStyle("gsi-std", "地理院 標準", "国土地理院の標準地図")
  case GsiPale extends MapStyle("gsi-pale", "地理院 淡色", "国土地理院")
  case GsiPhoto extends MapStyle("gsi-photo", "航空写真", "国土地理院のシームレス空中写真")
  case GsiRelief extends MapStyle("gsi-relief", "色別標高", "国土地理院（ズーム15まで）")
  case Custom extends MapStyle("custom", "カスタム", "XYZ タイル")

  /** Identifies what a snapshot of this map shows: the offline map changes
    * when its data is downloaded or deleted.
    */
  def cacheKey: String =
    if this == Offline then s"$key@${Basemap.installedVersion.getOrElse("none")}+${Regions.key}" else key

  /** Fetches tiles from a server (and so reveals the visible area). */
  def online: Boolean = this != Offline

  /** What is drawn: [[Custom]] without a URL is [[Standard]]. */
  def resolved: MapStyle = if this == Custom && MapStyle.custom._1.isEmpty then Standard else this

  /** Where its tiles come from; None for the offline map. */
  def dest: Option[Dest] = resolved match
    case Offline => None
    case Standard | Light => Some(Dest.Ofm)
    case GsiStd | GsiPale | GsiPhoto | GsiRelief => Some(Dest.Gsi)
    case Custom => Some(Dest.CustomTiles)

  /** A raster map whose style takes the offline map's fonts ([[Basemap.glyphs]]). */
  def basemapFonts: Boolean = this match
    case GsiStd | GsiPale | GsiPhoto | GsiRelief | Custom => true
    case _ => false

  /** What a remembered consent names ([[SendGate.remember]]): the map, and
    * for [[Custom]] its URL, so a consent never carries over to another server.
    */
  def consentId: String = if this == Custom then s"$key:${MapStyle.custom._1}" else key

  /** A sample of this map at a fixed area unrelated to the user (central
    * Tokyo), bundled with the app (tools/map-previews): the picker shows it
    * instead of a thumbnail at the current view, which would send the area.
    */
  def preview: Option[Int] =
    import net.sksat.ashidori.R
    this match
      case Standard  => Some(R.drawable.map_preview_ofm_liberty)
      case Light     => Some(R.drawable.map_preview_ofm_positron)
      case GsiStd    => Some(R.drawable.map_preview_gsi_std)
      case GsiPale   => Some(R.drawable.map_preview_gsi_pale)
      case GsiPhoto  => Some(R.drawable.map_preview_gsi_photo)
      case GsiRelief => Some(R.drawable.map_preview_gsi_relief)
      case _         => None

  /** Credit line shown with [[preview]]. */
  def previewCredit: String = this match
    case Standard | Light => "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
    case GsiStd | GsiPale | GsiPhoto | GsiRelief => "地理院タイル"
    case _ => ""

  /** The style of this map for `holder` (a map surface, `snapshot`,
    * `thumb:<dest>`), drawn by `owner`: an online map only under permit `p`
    * of its destination, with every remote URL marked for the holder and
    * the zoom limited to the permit's level ([[TileStyle]]). None while
    * OpenFreeMap's style is not on the device yet ([[OnlineStyles]]: it is
    * fetched now, under `p`, and the maps are told when it is there).
    */
  def styleJson(holder: String, owner: AnyRef, p: Option[Permit]): Option[String] =
    resolved match
      // The region packs over the basemap; `owner` then holds their files.
      case Offline => Some(Regions.style(owner, Basemap.styleJson).getOrElse(Basemap.styleJson.getOrElse(MapStyle.blankJson)))
      case ms =>
        p.filter(q => ms.dest.contains(q.dest)).flatMap { p =>
          val token = Net.guard.hold(holder, owner, p)
          val cap = Precision.maxZoom(p.level)
          ms match
            case Standard => OnlineStyles.build("liberty", holder, owner, p, cap, token)
            case Light => OnlineStyles.build("positron", holder, owner, p, cap, token)
            case GsiStd => Some(MapStyle.raster(MapStyle.gsi("std", "png"), cap, token))
            case GsiPale => Some(MapStyle.raster(MapStyle.gsi("pale", "png"), cap, token))
            case GsiPhoto => Some(MapStyle.raster(MapStyle.gsi("seamlessphoto", "jpg"), cap, token))
            case GsiRelief => Some(MapStyle.raster(MapStyle.gsi("relief", "png"), cap, token, maxZoom = 15))
            case _ =>
              val (url, credit) = MapStyle.custom
              // Without a credit line, at least name where the tiles come from.
              Some(MapStyle.raster(url, cap, token, credit = if credit.nonEmpty then credit else url.takeWhile(_ != '{')))
        }

object MapStyle:
  private def prefs = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  /** Interactive map surfaces, each with its own remembered consent: the
    * map tab, the period view and the venue picker of a stay.
    */
  val SurfaceMap = "map"
  val SurfacePeriod = "period"
  val SurfacePicker = "picker"
  val Surfaces: Set[String] = Set(SurfaceMap, SurfacePeriod, SurfacePicker)

  private def byKey(k: String): Option[MapStyle] = values.find(_.key == k)

  /** The user's choice; may be [[Offline]]. */
  def current: MapStyle = byKey(prefs.getString("mapStyle", Standard.key)).getOrElse(Standard)
  def current_=(s: MapStyle): Unit =
    val e = prefs.edit().putString("mapStyle", s.key)
    if s.online then e.putString("mapStyleOnline", s.key)
    e.apply()

  /** The online map last chosen: what "オンライン地図" asks for when [[current]] is offline. */
  def lastOnline: MapStyle =
    byKey(prefs.getString("mapStyleOnline", "")).filter(_.online)
      .orElse(Some(current).filter(_.online)).getOrElse(Standard)

  /** The online map a consent names, if it is still that map ([[consentId]]). */
  def fromConsent(id: String): Option[MapStyle] = values.find(s => s.online && s.consentId == id)

  /** When this map's destination may be sent to now (the offline map: always, nothing is sent). */
  def when(s: MapStyle): When = s.dest.fold(When.Auto)(Settings.gate.when)

  /** The online map an explicit request for detail shows (see
    * [[BaseMapPolicy.detailMap]]); None when every online map is never sent to.
    */
  def detailMap: Option[MapStyle] = BaseMapPolicy.detailMap(when, current, lastOnline, Offline, values.toSeq.filter(_.online))

  /** The online map remembered on `surface` ([[SendGate.consent]]), if still valid. */
  def consented(surface: String): Option[MapStyle] = Settings.gate.consent(surface).flatMap(c => fromConsent(c.map))

  /** Base map of an interactive map on `surface` (see [[BaseMapPolicy]]).
    * [[Outcome.NoBasemap]] when it would be the offline map and that is not
    * downloaded.
    */
  def forView(surface: String, onScreen: Boolean): Outcome[MapStyle] =
    BaseMapPolicy.interactive(when, current, Offline, offlineAvailable, consented(surface), onScreen)

  /** Base map of a still image (feed, period, other devices) by the rules
    * now; a job takes its permit right before it starts ([[snapshot]]).
    * With [[Outcome.NoBasemap]] the image is drawn without MapLibre.
    */
  def forSnapshot: Outcome[MapStyle] = BaseMapPolicy.snapshot(when, current, Offline, offlineAvailable)

  /** [[forSnapshot]] with the network now: without a validated one, the
    * offline map instead of an online one ([[BaseMapPolicy.snapshotNow]]).
    */
  def forSnapshotNow: Outcome[MapStyle] =
    BaseMapPolicy.snapshotNow(when, current, Offline, offlineAvailable, Net.reachable)

  /** A snapshot that fell back from the online map is drawn again when a
    * network returns ([[BaseMapPolicy.redrawOnline]]).
    */
  def redrawOnline: Boolean = BaseMapPolicy.redrawOnline(when, current, Offline, offlineAvailable, fellBack = true)

  /** The offline map has something to draw: the basemap, or region packs. */
  def offlineAvailable: Boolean = Basemap.available || Regions.drawable

  /** What a snapshot draws when its online map cannot be drawn
    * ([[BaseMapPolicy.snapshotFallback]]): the offline map with the region
    * packs around `owner`'s view ([[Regions.view]]), which sends nothing.
    */
  def snapshotFallback(owner: AnyRef): (Outcome[MapStyle], Option[String]) =
    BaseMapPolicy.snapshotFallback(Offline, offlineAvailable) match
      case o @ Outcome.Draw(s) => o -> s.styleJson("snapshot", owner, None)
      case o => o -> None

  /** What a snapshot about to start draws: the base map with its style (an
    * online one under a permit taken now). The offline map instead
    * ([[snapshotFallback]]) without a permit, without a validated network,
    * or while OpenFreeMap's style is fetched (drawn online when it arrives).
    * None only while that style is fetched and there is no offline map.
    */
  def snapshot(owner: AnyRef): Option[(Outcome[MapStyle], Option[String])] =
    forSnapshotNow match
      case Outcome.NoBasemap => Some(Outcome.NoBasemap -> None)
      case o @ Outcome.Draw(s) if s.online =>
        s.dest.flatMap(Settings.gate.permit(_, Mode.Auto)) match
          case Some(p) =>
            s.styleJson("snapshot", owner, Some(p)) match
              case Some(j) => Some(o -> Some(j))
              case None => Option.when(offlineAvailable)(snapshotFallback(owner))
          case None => Some(snapshotFallback(owner))
      case o @ Outcome.Draw(s) => Some(o -> s.styleJson("snapshot", owner, None))

  /** Whether the picker may render this map's thumbnail at the current view. */
  def thumbnailAllowed(s: MapStyle): Boolean =
    BaseMapPolicy.thumbnail(when, s, Offline, offlineAvailable)

  /** A builder for a snapshotter whose builder also adds sources. An inline
    * JSON style finishes loading inside the snapshotter's constructor, before
    * its native peer exists, so adding those sources fails ("invalid native
    * peer"). Loading the same JSON from a local file makes it asynchronous.
    */
  def snapshotBuilder(key: String, json: String): Style.Builder = Style.Builder().fromUri(styleFile(key, json))

  /** Canvas of an interactive map without a base map: its routes and stays
    * on the app's neutral surface color, as in the feed's sketches.
    */
  def blankBuilder(ctx: Context): Style.Builder =
    given Ui.Ctx = ctx
    Style.Builder().fromJson(OfflineBasemapStyle.blank(f"#${Ui.color(Ui.attr.surfaceContainerHigh) & 0xffffff}%06x"))

  private def blankJson: String = OfflineBasemapStyle.blank("#e2dfda")

  /** Lets MapLibre use the network only when the policy allows it: with
    * the setting on and no view holding a detail grant of the current
    * generation ([[SendGate.networkAllowed]]), it is told it is offline.
    * This is a second line only: the switch is process-wide and does not
    * cancel requests already scheduled. What keeps a map or snapshot from
    * sending is that it uses the on-device style.
    */
  def applyNetworkPolicy(ctx: Context): Unit =
    MapLibre.getInstance(ctx)
    // Before any map or snapshot is made: every request through the guard.
    Net.mapClient(ctx)
    MapLibre.setConnected(if Settings.gate.networkAllowed then null else java.lang.Boolean.FALSE)

  /** Snapshotters rendering now outside the [[DayMap]] queue (picker
    * thumbnails, the device screen): cancelled when the setting changes.
    * Main thread only.
    */
  private val snapshotters = scala.collection.mutable.Set.empty[org.maplibre.android.snapshotter.MapSnapshotter]
  def track(s: org.maplibre.android.snapshotter.MapSnapshotter): Unit = snapshotters += s
  def untrack(s: org.maplibre.android.snapshotter.MapSnapshotter): Unit = snapshotters -= s
  def cancelSnapshots(): Unit =
    snapshotters.foreach(_.cancel())
    snapshotters.clear()

  /** User-supplied XYZ raster tiles: URL template ({z}/{x}/{y}) and credit
    * line. A stored URL that is not valid ([[BaseMapPolicy.validTileUrl]]) reads as none.
    */
  def custom: (String, String) =
    val url = Option(prefs.getString("customTileUrl", "")).getOrElse("")
    (if BaseMapPolicy.validTileUrl(url) then url else "",
      Option(prefs.getString("customTileCredit", "")).getOrElse("").filterNot(_.isControl))

  /** Stores a valid URL ([[BaseMapPolicy.validTileUrl]]); stores nothing and returns false otherwise. */
  def setCustom(url: String, credit: String): Boolean =
    val u = url.trim
    BaseMapPolicy.validTileUrl(u) && {
      prefs.edit().putString("customTileUrl", u).putString("customTileCredit", credit.trim.filterNot(_.isControl)).apply()
      true
    }

  /** Any XYZ raster tile server: the URL template and a credit line. `saved` runs after a valid URL is stored. */
  def editCustom(act: net.sksat.ashidori.MainActivity)(saved: () => Unit): Unit =
    given Ui.Ctx = act
    val (url0, credit0) = custom
    val url = android.widget.EditText(act)
    url.setHint("https://tiles.example.net/{z}/{x}/{y}.png")
    url.setText(url0)
    url.setSingleLine(true)
    url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI)
    val credit = android.widget.EditText(act)
    credit.setHint("出典の表記（例: © 〇〇）")
    credit.setText(credit0)
    credit.setSingleLine(true)
    val body = Ui.padded(Ui.vbox(
      Ui.caption("{z} {x} {y} を含むラスタータイルの URL"),
      url, credit
    ), 20, 8)
    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("カスタム地図")
      .setView(body)
      .setNegativeButton("キャンセル", null)
      .setPositiveButton("適用", null)
      .show()
    // A bad URL keeps the dialog open with the error on the field.
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ =>
      val u = url.getText.toString.trim
      if setCustom(u, credit.getText.toString) then
        dialog.dismiss()
        saved()
      else url.setError("URL の形式が正しくありません")
    }

  /** Writes `json` to the cache (once per content) and returns its file:// URI. */
  private def styleFile(key: String, json: String): String =
    val bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(8).map(b => f"${b & 0xff}%02x").mkString
    val dir = java.io.File(AshidoriApp.app.getCacheDir, "map-styles")
    dir.mkdirs()
    val f = java.io.File(dir, s"$key-$hash.json")
    if !f.exists() then
      val tmp = java.io.File(dir, s"${f.getName}.tmp")
      java.nio.file.Files.write(tmp.toPath, bytes)
      tmp.renameTo(f)
    "file://" + f.getAbsolutePath

  private def gsi(layer: String, ext: String) = s"https://cyberjapandata.gsi.go.jp/xyz/$layer/{z}/{x}/{y}.$ext"
  /** GSI tiles must carry this attribution (terms of use). */
  private val gsiCredit = "<a href=\"https://maps.gsi.go.jp/development/ichiran.html\">地理院タイル</a>"

  /** A raster map, marked with `token` and limited to `cap`; with the
    * offline map installed, its fonts on the device for the venue layer's
    * labels ([[Basemap.glyphs]]).
    */
  private def raster(url: String, cap: Option[Int], token: String, maxZoom: Int = 18, credit: String = gsiCredit): String =
    TileStyle.raster(url, maxZoom, credit, cap, token, Basemap.glyphs)

/** OpenFreeMap's styles and their TileJSON, fetched by the app (under a
  * permit of [[Dest.Ofm]], through [[Net.outbound]]) and kept in the cache
  * directory: the map library is given them with the TileJSON's tiles
  * inlined and every URL marked ([[TileStyle.vector]]). They hold no
  * position. Fetched again after a day; the copy on the device is used meanwhile.
  *
  * A failed fetch is retried ([[Refetch]]: 5 s doubling to 5 min, and at
  * once when a network becomes available), only for the maps that still
  * show that style under their permit and, on action, their screen grant
  * ([[MapGuard.allows]], checked before each request); a map that leaves the
  * screen or goes away is forgotten ([[forget]]).
  */
object OnlineStyles:
  private val MaxAgeMs = 24 * 3600_000L
  private def dir = java.io.File(AshidoriApp.app.getCacheDir, "online-styles")
  private def file(name: String) = java.io.File(dir, s"$name.json")
  private val net = java.util.concurrent.Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-styles"))
  private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => Thread(r, "ashidori-styles-retry"))

  /** A map (or snapshot) that wants a style: its holder, the one drawing it, and its permit. */
  final case class Wanted(holder: String, owner: AnyRef, permit: Permit)
  private def allowed(w: Wanted): Boolean = Net.guard.allows(w.holder, w.owner, w.permit)

  private lazy val refetch: Refetch[String, Wanted] =
    val r = Refetch[String, Wanted](fetch, allowed, f => net.execute(() => f()),
      (ms, f) => {
        val t = timer.schedule((() => f()): Runnable, ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        () => { t.cancel(false); () }
      })
    val cm = AshidoriApp.app.getSystemService(classOf[android.net.ConnectivityManager])
    cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback:
      override def onAvailable(n: android.net.Network): Unit = r.retryNow())
    r

  private val listeners = scala.collection.mutable.LinkedHashSet.empty[() => Unit]
  /** Called (main thread) when a style was fetched. Returns the function that stops it. */
  def listen(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  /** The style `name` for MapLibre, from the copy on the device; fetched
    * for `holder` drawn by `owner` (under `p`) when there is none or it is old.
    */
  def build(name: String, holder: String, owner: AnyRef, p: Permit, cap: Option[Int], token: String): Option[String] =
    val f = file(name)
    val bundle = scala.util.Try(ujson.read(java.nio.file.Files.readString(f.toPath))).toOption
    if bundle.isEmpty || System.currentTimeMillis() - f.lastModified() > MaxAgeMs then
      refetch.request(name, Wanted(holder, owner, p))
    bundle.flatMap { b =>
      scala.util.Try(TileStyle.vector(b("style").str, u => b("tilejson")(u).str, cap, token)).toOption
    }

  /** Retry the waiting fetches now (what a network becoming available does). */
  def retryNow(): Unit = refetch.retryNow()

  /** `owner` no longer shows an online style (left the screen, gone): no fetch or retry for it. */
  def forget(owner: AnyRef): Unit = refetch.forgetWhere(_.owner eq owner)

  /** Debug builds (DebugReceiver STYLE_FAIL): the next fetches fail before sending anything. */
  @volatile var debugFailures = 0

  /** One fetch of `name` for the first of `wanted` still allowed, checked again before each request. */
  private def fetch(name: String, wanted: Seq[Wanted]): Boolean =
    def who = wanted.find(allowed).getOrElse(throw Outbound.Blocked(s"style $name: no map wants it any more"))
    try
      Net.log(s"style fetch name=$name for=${wanted.map(_.holder).mkString(",")}")
      if debugFailures > 0 then
        debugFailures -= 1
        throw java.io.IOException("debug failure")
      val ua = Seq("User-Agent" -> s"ashidori/${_root_.net.sksat.ashidori.BuildInfo.versionName} (Android)")
      val style = Net.outbound.small(who.permit, s"https://tiles.openfreemap.org/styles/$name", ua).getOrElse(throw java.io.IOException("style"))
      val urls = ujson.read(style)("sources").obj.values.flatMap(_.obj.get("url").flatMap(_.strOpt)).filter(_.startsWith("http")).toSeq.distinct
      val tj = urls.map(u => u -> ujson.Str(Net.outbound.small(who.permit, u, ua).getOrElse(throw java.io.IOException("tilejson"))))
      dir.mkdirs()
      val tmp = java.io.File(dir, s"$name.json.tmp")
      java.nio.file.Files.writeString(tmp.toPath, ujson.write(ujson.Obj("style" -> style, "tilejson" -> ujson.Obj.from(tj))))
      tmp.renameTo(file(name))
      AshidoriApp.app.getMainExecutor.execute(() => listeners.toSeq.foreach(_()))
      true
    catch case e: Exception =>
      Log.w(s"online style $name: ${e.getMessage}")
      Net.log(s"style fetch failed name=$name reason=${e.getClass.getSimpleName}")
      false
