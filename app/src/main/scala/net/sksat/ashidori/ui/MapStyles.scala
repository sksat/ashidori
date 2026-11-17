package net.sksat.ashidori.ui

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.Style
import net.sksat.ashidori.{AshidoriApp, Basemap, Settings}
import ashidori.core.{BaseMapPolicy, OfflineBasemapStyle}
import ashidori.core.BaseMapPolicy.Outcome

/** Selectable base maps. "オフライン地図" is drawn from data in the app and never
  * uses the network; the others fetch tiles of the visible area from their
  * servers. The built-in online ones are free to use without an API key;
  * "カスタム" takes any XYZ raster tile URL (e.g. a provider with a key).
  */
enum MapStyle(val key: String, val label: String, val note: String):
  case Offline extends MapStyle("offline", "オフライン地図", "通信なし")
  case Standard extends MapStyle("ofm-liberty", "標準", "OpenStreetMap（OpenFreeMap）")
  case Light extends MapStyle("ofm-positron", "シンプル", "淡い配色で経路が見やすい（OpenFreeMap）")
  case GsiStd extends MapStyle("gsi-std", "地理院 標準", "国土地理院の標準地図")
  case GsiPale extends MapStyle("gsi-pale", "地理院 淡色", "国土地理院の淡色地図。経路が目立つ")
  case GsiPhoto extends MapStyle("gsi-photo", "航空写真", "国土地理院のシームレス空中写真")
  case GsiRelief extends MapStyle("gsi-relief", "色別標高", "国土地理院。高さを色で表示（ズーム15まで）")
  case Custom extends MapStyle("custom", "カスタム", "XYZ タイルの URL を指定")

  /** Identifies what a snapshot of this map shows: the offline map changes
    * when its data is downloaded or deleted.
    */
  def cacheKey: String = if this == Offline then s"$key@${Basemap.installedVersion.getOrElse("none")}" else key

  /** Fetches tiles from a server (and so reveals the visible area). */
  def online: Boolean = this != Offline

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

  /** Style document: a URL, or inline JSON. The policy picks [[Offline]]
    * only while its data is there ([[Basemap.available]]); should it be gone
    * meanwhile, the map shows no base map rather than an online one.
    */
  private def source: Either[String, String] = this match
    case Offline   => Right(Basemap.styleJson.getOrElse(MapStyle.blankJson))
    case Standard  => Left("https://tiles.openfreemap.org/styles/liberty")
    case Light     => Left("https://tiles.openfreemap.org/styles/positron")
    case GsiStd    => Right(MapStyle.raster(MapStyle.gsi("std", "png")))
    case GsiPale   => Right(MapStyle.raster(MapStyle.gsi("pale", "png")))
    case GsiPhoto  => Right(MapStyle.raster(MapStyle.gsi("seamlessphoto", "jpg")))
    case GsiRelief => Right(MapStyle.raster(MapStyle.gsi("relief", "png"), maxZoom = 15))
    case Custom =>
      val (url, credit) = MapStyle.custom
      if url.isEmpty then Standard.source
      // Without a credit line, at least name where the tiles come from.
      else Right(MapStyle.raster(url, credit = if credit.nonEmpty then credit else url.takeWhile(_ != '{')))

  /** MapLibre style for this base map. */
  def builder: Style.Builder = source match
    case Left(uri)   => Style.Builder().fromUri(uri)
    case Right(json) => Style.Builder().fromJson(json)

  /** Style for a [[org.maplibre.android.snapshotter.MapSnapshotter]] whose
    * builder also adds sources. An inline JSON style finishes loading inside
    * the snapshotter's constructor, before its native peer exists, so adding
    * those sources fails ("invalid native peer"). Loading the same JSON from a
    * local file makes it asynchronous like a URL style.
    */
  def snapshotBuilder: Style.Builder = source match
    case Left(uri)   => Style.Builder().fromUri(uri)
    case Right(json) => Style.Builder().fromUri(MapStyle.styleFile(key, json))

object MapStyle:
  private def prefs = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  /** Interactive map surfaces, each with its own remembered consent: the map tab and the period view. */
  val SurfaceMap = "map"
  val SurfacePeriod = "period"
  val Surfaces: Set[String] = Set(SurfaceMap, SurfacePeriod)

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

  /** The online map an explicit request for detail shows (see [[BaseMapPolicy.detailMap]]). */
  def detailMap: MapStyle = BaseMapPolicy.detailMap(current, lastOnline, Offline)

  /** Base map of an interactive map on `surface`: with the setting on, the
    * online map remembered there, else the offline one (see
    * [[BaseMapPolicy]]). [[Outcome.NoBasemap]] when it would be the offline
    * map and that is not downloaded.
    */
  def forView(surface: String, onScreen: Boolean): Outcome[MapStyle] =
    BaseMapPolicy.interactive(Settings.blockExternalLocation, current, Offline, Basemap.available,
      Settings.gate.consent(surface).flatMap(fromConsent), onScreen)

  /** Base map of a still image (feed, period, other devices), with the
    * setting as it is now (or `block`, as taken right before a job starts).
    * With [[Outcome.NoBasemap]] the image is drawn without MapLibre.
    */
  def forSnapshot: Outcome[MapStyle] = forSnapshot(Settings.blockExternalLocation)
  def forSnapshot(block: Boolean): Outcome[MapStyle] = BaseMapPolicy.snapshot(block, current, Offline, Basemap.available)

  /** Whether the picker may render this map's thumbnail at the current view. */
  def thumbnailAllowed(s: MapStyle): Boolean =
    BaseMapPolicy.thumbnail(Settings.blockExternalLocation, s, Offline, Basemap.available)

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

  private def raster(url: String, maxZoom: Int = 18, credit: String = gsiCredit): String =
    ujson.write(
      ujson.Obj(
        "version" -> 8,
        "sources" -> ujson.Obj(
          "base" -> ujson.Obj(
            "type" -> "raster",
            "tiles" -> ujson.Arr(url),
            "tileSize" -> 256,
            "maxzoom" -> maxZoom,
            "attribution" -> credit
          )
        ),
        "layers" -> ujson.Arr(ujson.Obj("id" -> "base", "type" -> "raster", "source" -> "base"))
      )
    )
