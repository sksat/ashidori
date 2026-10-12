package net.sksat.ashidori.ui

import android.content.Context
import org.maplibre.android.maps.Style
import net.sksat.ashidori.AshidoriApp

/** Selectable base maps. The built-in ones are free to use without an API
  * key; "カスタム" takes any XYZ raster tile URL (e.g. a provider with a key).
  */
enum MapStyle(val key: String, val label: String, val note: String):
  case Standard extends MapStyle("ofm-liberty", "標準", "OpenStreetMap（OpenFreeMap）")
  case Light extends MapStyle("ofm-positron", "シンプル", "淡い配色で経路が見やすい（OpenFreeMap）")
  case GsiStd extends MapStyle("gsi-std", "地理院 標準", "国土地理院の標準地図")
  case GsiPale extends MapStyle("gsi-pale", "地理院 淡色", "国土地理院の淡色地図。経路が目立つ")
  case GsiPhoto extends MapStyle("gsi-photo", "航空写真", "国土地理院のシームレス空中写真")
  case GsiRelief extends MapStyle("gsi-relief", "色別標高", "国土地理院。高さを色で表示（ズーム15まで）")
  case Custom extends MapStyle("custom", "カスタム", "XYZ タイルの URL を指定")

  /** MapLibre style for this base map. */
  def builder: Style.Builder = this match
    case Standard  => Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
    case Light     => Style.Builder().fromUri("https://tiles.openfreemap.org/styles/positron")
    case GsiStd    => Style.Builder().fromJson(MapStyle.raster(MapStyle.gsi("std", "png")))
    case GsiPale   => Style.Builder().fromJson(MapStyle.raster(MapStyle.gsi("pale", "png")))
    case GsiPhoto  => Style.Builder().fromJson(MapStyle.raster(MapStyle.gsi("seamlessphoto", "jpg")))
    case GsiRelief => Style.Builder().fromJson(MapStyle.raster(MapStyle.gsi("relief", "png"), maxZoom = 15))
    case Custom =>
      val (url, credit) = MapStyle.custom
      if url.isEmpty then Standard.builder
      // Without a credit line, at least name where the tiles come from.
      else Style.Builder().fromJson(MapStyle.raster(url, credit = if credit.nonEmpty then credit else url.takeWhile(_ != '{')))

object MapStyle:
  private def prefs = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  def current: MapStyle = values.find(_.key == prefs.getString("mapStyle", Standard.key)).getOrElse(Standard)
  def current_=(s: MapStyle): Unit = prefs.edit().putString("mapStyle", s.key).apply()

  /** User-supplied XYZ raster tiles: URL template ({z}/{x}/{y}) and credit line. */
  def custom: (String, String) = (prefs.getString("customTileUrl", ""), prefs.getString("customTileCredit", ""))
  def custom_=(v: (String, String)): Unit =
    prefs.edit().putString("customTileUrl", v._1.trim).putString("customTileCredit", v._2.trim).apply()

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
