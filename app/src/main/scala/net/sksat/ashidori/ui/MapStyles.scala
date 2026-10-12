package net.sksat.ashidori.ui

import android.content.Context
import org.maplibre.android.maps.Style
import net.sksat.ashidori.AshidoriApp

/** Selectable base maps. All are free to use without an API key. */
enum MapStyle(val key: String, val label: String, val note: String):
  case Standard extends MapStyle("ofm-liberty", "標準", "OpenStreetMap（OpenFreeMap）")
  case Light extends MapStyle("ofm-positron", "シンプル", "淡い配色で経路が見やすい（OpenFreeMap）")
  case GsiStd extends MapStyle("gsi-std", "地理院 標準", "国土地理院の標準地図")
  case GsiPhoto extends MapStyle("gsi-photo", "航空写真", "国土地理院のシームレス空中写真")

  /** MapLibre style for this base map. */
  def builder: Style.Builder = this match
    case Standard => Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
    case Light    => Style.Builder().fromUri("https://tiles.openfreemap.org/styles/positron")
    case GsiStd   => Style.Builder().fromJson(MapStyle.raster("https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png"))
    case GsiPhoto => Style.Builder().fromJson(MapStyle.raster("https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg"))

object MapStyle:
  private def prefs = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  def current: MapStyle = values.find(_.key == prefs.getString("mapStyle", Standard.key)).getOrElse(Standard)
  def current_=(s: MapStyle): Unit = prefs.edit().putString("mapStyle", s.key).apply()

  /** A raster tile style with GSI attribution (required by their terms). */
  private def raster(url: String): String =
    ujson.write(
      ujson.Obj(
        "version" -> 8,
        "sources" -> ujson.Obj(
          "base" -> ujson.Obj(
            "type" -> "raster",
            "tiles" -> ujson.Arr(url),
            "tileSize" -> 256,
            "maxzoom" -> 18,
            "attribution" -> "<a href=\"https://maps.gsi.go.jp/development/ichiran.html\">地理院タイル</a>"
          )
        ),
        "layers" -> ujson.Arr(ujson.Obj("id" -> "base", "type" -> "raster", "source" -> "base"))
      )
    )
