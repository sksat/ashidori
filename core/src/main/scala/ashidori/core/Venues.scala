package ashidori.core

/** A point of interest that can be checked in to. */
final case class Venue(id: String, name: String, category: String, lat: Double, lon: Double):
  def latLng: LatLng = LatLng(lat, lon)

/** Nearby venue search on OpenStreetMap through the Overpass API. */
object Overpass:
  val Endpoint = "https://overpass-api.de/api/interpreter"

  /** Category keys in priority order. */
  private val CategoryKeys = Seq("amenity", "shop", "tourism", "leisure", "railway", "public_transport", "office", "historic", "aeroway", "building")

  /** One statement with a key regex: a union of one statement per key made
    * the public server time out (504 after ~9 s); this returns in ~5 s.
    */
  def query(lat: Double, lon: Double, radiusM: Int = 200): String =
    val keys = CategoryKeys.filterNot(_ == "building").mkString("|")
    s"""[out:json][timeout:10];nwr["name"][~"^($keys)$$"~"."](around:$radiusM,$lat,$lon);out center 80;"""

  /** `maxDistM` drops large areas (parks, reserves) that touch the search
    * circle but whose center is far away.
    */
  def parse(json: String, lat: Double, lon: Double, maxDistM: Double = 600): Vector[(Venue, Double)] =
    val root = ujson.read(json)
    root("elements").arr.toVector.flatMap { e =>
      val tags = e.obj.get("tags").map(_.obj).getOrElse(collection.mutable.LinkedHashMap.empty[String, ujson.Value])
      def tag(k: String) = tags.get(k).map(_.str)
      val name = tag("name:ja").orElse(tag("name"))
      val (vlat, vlon) =
        if e.obj.contains("lat") then (e("lat").num, e("lon").num)
        else e.obj.get("center").map(c => (c("lat").num, c("lon").num)).getOrElse((Double.NaN, Double.NaN))
      for
        n <- name
        if !vlat.isNaN
        cat = CategoryKeys.view.flatMap(k => tag(k).map(v => label(k, v))).headOption.getOrElse("")
      yield
        val v = Venue(s"${e("type").str}/${e("id").num.toLong}", n, cat, vlat, vlon)
        v -> Geo.distance(lat, lon, vlat, vlon)
    }.filter(_._2 <= maxDistM).distinctBy(_._1.name).sortBy(_._2)

  private val Labels = Map(
    "restaurant" -> "レストラン", "cafe" -> "カフェ", "fast_food" -> "ファストフード", "bar" -> "バー", "pub" -> "居酒屋",
    "convenience" -> "コンビニ", "supermarket" -> "スーパー", "station" -> "駅", "stop_position" -> "停留所",
    "bus_stop" -> "バス停", "platform" -> "乗り場", "park" -> "公園", "hotel" -> "ホテル", "museum" -> "博物館",
    "library" -> "図書館", "school" -> "学校", "university" -> "大学", "hospital" -> "病院", "clinic" -> "クリニック",
    "pharmacy" -> "薬局", "bank" -> "銀行", "post_office" -> "郵便局", "place_of_worship" -> "寺社",
    "attraction" -> "観光地", "viewpoint" -> "展望", "fuel" -> "ガソリンスタンド", "parking" -> "駐車場",
    "books" -> "書店", "clothes" -> "衣料品", "electronics" -> "家電", "department_store" -> "百貨店", "mall" -> "モール",
    "aerodrome" -> "空港", "terminal" -> "ターミナル", "fitness_centre" -> "ジム", "cinema" -> "映画館", "karaoke_box" -> "カラオケ"
  )

  private def label(key: String, value: String): String =
    Labels.getOrElse(value, if value == "yes" then key else value)
