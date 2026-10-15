package ashidori.core

/** A point of interest that can be checked in to. */
final case class Venue(id: String, name: String, category: String, lat: Double, lon: Double):
  def latLng: LatLng = LatLng(lat, lon)

/** Candidates from several sources (places, the venue index, saved and
  * online results), merged so that rows already shown stay where they are.
  */
object VenueMerge:
  /** Of `more`, those not among `shown`: neither the same OpenStreetMap
    * object (id) nor the same name; one per name, closest first.
    */
  def fresh(shown: Vector[(Venue, Double)], more: Vector[(Venue, Double)]): Vector[(Venue, Double)] =
    val ids = shown.iterator.map(_._1.id).filter(_.nonEmpty).toSet
    val names = shown.iterator.map(_._1.name).toSet
    more.sortBy(_._2).filterNot(v => (v._1.id.nonEmpty && ids(v._1.id)) || names(v._1.name)).distinctBy(_._1.name)

/** Nearby venue search on OpenStreetMap through the Overpass API. */
object Overpass:
  val Endpoint = "https://overpass-api.de/api/interpreter"

  /** Public instances, the main one first. Any of them may be overloaded
    * or down for days; an HTTP error moves on to the next at once.
    */
  val Endpoints: Seq[String] = Seq(
    Endpoint,
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter"
  )

  /** Elements per answer (`out center`): a full answer may miss venues. */
  val Limit = 80

  /** The whole search, over every instance, ends within this time. */
  val BudgetMs = 20_000L

  /** Connect and read timeouts for the `i`-th instance tried with
    * `remainingMs` of the budget left, or None when too little is left to
    * be worth a try. The first gets most of the budget (it answers in ~5 s
    * when healthy); the others are a quick fallback.
    */
  def timeouts(i: Int, remainingMs: Long): Option[(Int, Int)] =
    if remainingMs < 1_500 then None
    else
      val (connect, read) = if i == 0 then (5_000L, 10_000L) else (3_000L, 6_000L)
      val c = math.min(connect, remainingMs / 3)
      Some((c.toInt, math.min(read, remainingMs - c).toInt))

  /** `endpoints` in the order to try: the one that answered last first,
    * those that failed within `penaltyMs` of `now` last (each group in the
    * given order).
    */
  def order(endpoints: Seq[String], lastOk: Option[String], failedAt: Map[String, Long], now: Long,
            penaltyMs: Long = 10 * 60_000L): Seq[String] =
    endpoints.zipWithIndex.sortBy { (e, i) =>
      val failed = failedAt.get(e).exists(t => now - t < penaltyMs && !lastOk.contains(e))
      (failed, !lastOk.contains(e), i)
    }.map(_._1)

  /** Category keys in priority order. */
  private val CategoryKeys = Seq("amenity", "shop", "tourism", "leisure", "railway", "public_transport", "office", "historic", "aeroway", "building")

  /** One statement with a key regex: a union of one statement per key made
    * the public server time out (504 after ~9 s); this returns in ~5 s.
    */
  def query(lat: Double, lon: Double, radiusM: Int = 200): String =
    val keys = CategoryKeys.filterNot(_ == "building").mkString("|")
    s"""[out:json][timeout:10];nwr["name"][~"^($keys)$$"~"."](around:$radiusM,$lat,$lon);out center $Limit;"""

  /** `maxDistM` drops large areas (parks, reserves) that touch the search
    * circle but whose center is far away.
    */
  def parse(json: String, lat: Double, lon: Double, maxDistM: Double = 600): Vector[(Venue, Double)] =
    parseAnswer(json, lat, lon, maxDistM)._1

  /** [[parse]], and whether the answer is complete: fewer elements than
    * [[Limit]] and no runtime error. A server that ran out of time answers
    * 200 with a "remark" and what it found so far; with nothing found that
    * is a failure (another server may answer), not an empty spot.
    */
  def parseAnswer(json: String, lat: Double, lon: Double, maxDistM: Double = 600): (Vector[(Venue, Double)], Boolean) =
    val root = ujson.read(json)
    val elements = root("elements").arr.toVector
    val error = root.obj.get("remark").flatMap(_.strOpt).exists(_.contains("error"))
    if error && elements.isEmpty then throw RuntimeException(s"Overpass: ${root("remark").str}")
    val venues = elements.flatMap { e =>
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
    (venues, !error && elements.size < Limit)

  private val Labels = Map(
    "restaurant" -> "レストラン", "cafe" -> "カフェ", "fast_food" -> "ファストフード", "bar" -> "バー", "pub" -> "居酒屋",
    "convenience" -> "コンビニ", "supermarket" -> "スーパー", "station" -> "駅", "stop_position" -> "停留所",
    "bus_stop" -> "バス停", "platform" -> "乗り場", "park" -> "公園", "hotel" -> "ホテル", "museum" -> "博物館",
    "library" -> "図書館", "school" -> "学校", "university" -> "大学", "hospital" -> "病院", "clinic" -> "クリニック",
    "pharmacy" -> "薬局", "bank" -> "銀行", "post_office" -> "郵便局", "place_of_worship" -> "寺社",
    "attraction" -> "観光地", "viewpoint" -> "展望", "fuel" -> "ガソリンスタンド", "parking" -> "駐車場",
    "books" -> "書店", "clothes" -> "衣料品", "electronics" -> "家電", "department_store" -> "百貨店", "mall" -> "モール",
    "aerodrome" -> "空港", "terminal" -> "ターミナル", "fitness_centre" -> "ジム", "cinema" -> "映画館", "karaoke_box" -> "カラオケ",
    "kindergarten" -> "幼稚園", "social_facility" -> "福祉施設", "company" -> "会社", "hairdresser" -> "理容・美容室",
    "community_centre" -> "公民館", "doctors" -> "医院", "dentist" -> "歯科", "police" -> "交番・警察",
    "car" -> "自動車販売", "confectionery" -> "菓子店", "estate_agent" -> "不動産", "variety_store" -> "雑貨店",
    "artwork" -> "アート", "massage" -> "マッサージ", "fire_station" -> "消防署", "alcohol" -> "酒店",
    "bakery" -> "パン屋", "dry_cleaning" -> "クリーニング", "public_bath" -> "銭湯・温泉", "car_repair" -> "自動車整備",
    "townhall" -> "役所", "government" -> "官公庁", "sports_centre" -> "スポーツ施設", "mobile_phone" -> "携帯電話",
    "bicycle" -> "自転車", "prep_school" -> "塾", "doityourself" -> "ホームセンター", "florist" -> "花屋",
    "nursing_home" -> "介護施設", "chemist" -> "ドラッグストア", "optician" -> "眼鏡店", "archaeological_site" -> "遺跡",
    "laundry" -> "コインランドリー", "beauty" -> "美容", "public_building" -> "公共施設", "gift" -> "土産物",
    "childcare" -> "保育所", "guest_house" -> "民宿", "veterinary" -> "動物病院", "adult_gaming_centre" -> "パチンコ",
    "funeral_directors" -> "葬儀場", "motorcycle" -> "バイク", "golf_course" -> "ゴルフ場", "sports" -> "スポーツ用品",
    "seafood" -> "鮮魚", "deli" -> "惣菜", "second_hand" -> "リサイクル", "car_rental" -> "レンタカー", "shoes" -> "靴",
    "greengrocer" -> "八百屋", "furniture" -> "家具", "butcher" -> "精肉", "car_parts" -> "カー用品",
    "camp_site" -> "キャンプ場", "stationery" -> "文具", "garden" -> "庭園", "pastry" -> "洋菓子", "pet" -> "ペット",
    "college" -> "専門学校", "hardware" -> "金物", "insurance" -> "保険", "theatre" -> "劇場", "photo" -> "写真",
    "monument" -> "記念碑", "sports_hall" -> "体育館", "jewelry" -> "宝飾", "toys" -> "おもちゃ", "cosmetics" -> "化粧品",
    "bus_station" -> "バスターミナル", "tram_stop" -> "停留場", "halt" -> "駅", "ice_cream" -> "アイスクリーム",
    "tea" -> "茶", "castle" -> "城", "ferry_terminal" -> "フェリー乗り場", "ruins" -> "史跡", "taxi" -> "タクシー乗り場",
    "sauna" -> "サウナ", "amusement_arcade" -> "ゲームセンター", "outdoor" -> "アウトドア", "hostel" -> "ホステル",
    "zoo" -> "動物園", "aquarium" -> "水族館", "theme_park" -> "テーマパーク", "stadium" -> "スタジアム",
    "marketplace" -> "市場", "nightclub" -> "クラブ", "food_court" -> "フードコート", "biergarten" -> "ビアガーデン",
    "arts_centre" -> "文化施設", "kiosk" -> "売店", "motel" -> "モーテル", "ticket" -> "きっぷ売り場",
    "photo_booth" -> "証明写真", "building" -> "建物", "shrine" -> "神社", "temple" -> "寺院", "spa" -> "温泉", "onsen" -> "温泉"
  )

  /** Japanese label of a category tag, as shown under a venue's name. */
  def label(key: String, value: String): String =
    Labels.getOrElse(value, if value == "yes" then key else value)
