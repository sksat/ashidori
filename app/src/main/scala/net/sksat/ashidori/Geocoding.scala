package net.sksat.ashidori

import java.io.{File, FileInputStream, InputStream}
import java.lang.ref.SoftReference
import scala.jdk.CollectionConverters.*
import scala.util.Try
import ashidori.core.*

/** Area names for positions, computed on the device.
  *
  * The offline index (位置参照情報 and GeoNames, built by
  * `tools/geocoder/build_index.py`) is read from the APK asset
  * [[AssetPath]], or from [[downloaded]] if the asset is absent. The platform
  * `android.location.Geocoder`, which may send the position to Google or
  * another provider, is asked only when the index has no answer and
  * [[Settings.blockExternalLocation]] is off. Call off the main thread.
  */
object Geocoding:
  val AssetPath = "geocoder/index.bin"
  /** Input versions and attribution of the bundled index. */
  val ManifestPath = "geocoder/manifest.json"
  /** Where a downloaded copy of the whole index would be stored. */
  def downloaded: File = File(AshidoriApp.app.getFilesDir, "geocoder/index.bin")

  /** Held softly: about 17 MB, reloaded (~0.5 s) after the system reclaims it. */
  private var ref: SoftReference[ReverseGeocoder] = SoftReference(null)
  private var unavailable = false

  private def open(): Option[InputStream] =
    Try(AshidoriApp.app.getAssets.open(AssetPath)).toOption
      .orElse(Option(downloaded).filter(_.isFile).map(FileInputStream(_)))

  /** Whether an index exists, without loading it. */
  def available: Boolean =
    Option(ref.get).isDefined || open().map(_.close()).isDefined

  /** The offline index, or None if this build has none. */
  def index: Option[ReverseGeocoder] = synchronized {
    Option(ref.get).orElse {
      if unavailable then None
      else
        val loaded = open().flatMap { in =>
          try Some(ReverseGeocoder.load(in))
          catch
            case e: Exception =>
              Log.w("offline geocoder index unreadable", e)
              None
          finally in.close()
        }
        loaded match
          case Some(g) => ref = SoftReference(g)
          case None    => unavailable = true
        loaded
    }
  }

  def offline(lat: Double, lon: Double): Option[GeoPlace] = index.flatMap(_.lookup(lat, lon))

  private def platformPresent = android.location.Geocoder.isPresent

  /** Forget a missing index, e.g. after it was downloaded. */
  def reset(): Unit = synchronized { ref = SoftReference(null); unavailable = false }

  /** Short area name ("渋谷区宇田川町"), shown as "…付近". `platform = false`
    * skips the platform fallback even when it is allowed (to bound slow calls).
    */
  def area(lat: Double, lon: Double, platform: Boolean = true): Option[String] =
    GeocodePolicy.resolve(offline(lat, lon).flatMap(_.area), Settings.blockExternalLocation, platform && platformPresent) {
      platformAddress(lat, lon).flatMap(a => Option(a.getAddressLine(0)).flatMap(AreaName.fromAddressLine).orElse(Option(a.getLocality)))
    }

  /** (city, country) for period summaries, e.g. ("渋谷区", "日本"). */
  def region(lat: Double, lon: Double, platform: Boolean = true): Option[(String, String)] =
    GeocodePolicy.resolve(offline(lat, lon).map(p => (p.city, p.country)), Settings.blockExternalLocation, platform && platformPresent) {
      platformAddress(lat, lon).map { a =>
        (Option(a.getLocality).orElse(Option(a.getSubAdminArea)).getOrElse(""), Option(a.getCountryName).getOrElse(""))
      }
    }

  /** Bumped when cached names must be recomputed (1: the offline index). */
  private val NamesVersion = 1

  /** Area names ("…付近") and regions cached in the places table before the
    * offline index existed came from the platform geocoder. Once per
    * [[NamesVersion]], recompute every cached one from the index and clear
    * those it cannot name; with the setting off, the platform may fill
    * those in again later. Call on [[AshidoriApp.io]].
    */
  def migrateCachedNames(): Unit =
    val prefs = AshidoriApp.app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    if prefs.getInt("cachedNamesVersion", 0) < NamesVersion then
      try
        val db = AshidoriApp.db
        val places = db.places().map(p => p.id -> p).toMap
        val areas = db.placeAreas().keys.flatMap(places.get)
        val regions = db.placeRegions().keys.flatMap(places.get)
        for p <- areas do db.setPlaceArea(p.id, offline(p.lat, p.lon).flatMap(_.area).orNull)
        for p <- regions do
          offline(p.lat, p.lon) match
            case Some(g) => db.setPlaceRegion(p.id, g.city, g.country)
            case None    => db.setPlaceRegion(p.id, null, null)
        TimelineRepo.invalidate()
        prefs.edit().putInt("cachedNamesVersion", NamesVersion).apply()
        Log.i(s"area names recomputed offline: ${areas.size} areas, ${regions.size} regions")
      catch case e: Exception => Log.w("recomputing cached area names failed", e)

  @annotation.nowarn("cat=deprecation")
  private def platformAddress(lat: Double, lon: Double): Option[android.location.Address] =
    val g = android.location.Geocoder(AshidoriApp.app, java.util.Locale.JAPANESE)
    Try(g.getFromLocation(lat, lon, 1)).toOption.flatMap(Option(_)).flatMap(_.asScala.headOption)
