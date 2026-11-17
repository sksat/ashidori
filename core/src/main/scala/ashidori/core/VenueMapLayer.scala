package ashidori.core

import scala.collection.mutable

/** Shops and other places on the map, from the on-device venue index
  * ([[VenueIndex.within]]): what the offline basemap lacks above zoom 14
  * (docs/design/outbound-and-offline.md, 6.16). Nothing is sent.
  *
  * Each venue gets a colour group, a Protomaps light sprite name where that
  * sprite has one, the first display zoom ([[Style.minZoom]]: 15 major, 16
  * most, 17 offices) and a collision order ([[Style.rank]], lower placed
  * first). From [[CloseZoom]] on, shops and restaurants go before the malls
  * and department stores that hold them ([[Style.rankClose]]).
  */
object VenueMapLayer:
  /** Colour groups: the Protomaps light POI text colours, plus other and office. */
  enum Group(val key: String, val color: String, val order: Int):
    case Transit extends Group("transit", "#315BCF", 0)
    case Culture extends Group("culture", "#EF56BA", 1)
    case Green extends Group("green", "#20834D", 1)
    case Food extends Group("food", "#CB6704", 2)
    case Shop extends Group("shop", "#1A8CBD", 2)
    case Civic extends Group("civic", "#6A5B8F", 3)
    case Other extends Group("other", "#6F6A66", 4)
    case Office extends Group("office", "#8C8781", 5)

  /** `icon`: a Protomaps light sprite name, or "" (a dot in the group colour). */
  final case class Style(group: Group, icon: String, minZoom: Int, rank: Int, rankClose: Int):
    def rankAt(zoom: Double): Int = if zoom >= CloseZoom then rankClose else rank

  /** Below this zoom the layer shows nothing (the basemap's own POIs suffice). */
  val MinZoom = 15
  /** From this zoom on, shops go before malls and department stores. */
  val CloseZoom = 17
  /** Venues of the same (normalized) name closer than this are one. A
    * basemap POI of the same name is not checked: reading the rendered
    * POIs took 8–70 ms on the main thread (emulator); the layer lies under
    * the basemap's labels, so the collision hides the venue on the same spot.
    */
  val SameNameM = 50.0
  /** Stations of one name (a node per line or operator) closer than this are one. */
  val SameStationM = 400.0

  private val Transit = Set(("railway", "station"), ("railway", "halt"), ("railway", "tram_stop"),
    ("public_transport", "station"), ("amenity", "bus_station"), ("amenity", "ferry_terminal"),
    ("aeroway", "aerodrome"), ("aeroway", "terminal"))
  private val Food = Set("restaurant", "fast_food", "cafe", "bar", "pub", "food_court", "ice_cream", "biergarten", "nightclub")
  private val Green = Set("park", "garden", "nature_reserve", "zoo", "beach_resort", "marina", "camp_site", "golf_course")
  private val Civic = Set("school", "university", "college", "kindergarten", "library", "post_office", "townhall",
    "hospital", "clinic", "doctors", "dentist", "police", "fire_station", "community_centre", "courthouse",
    "social_facility", "childcare", "prep_school", "research_institute", "driving_school", "music_school",
    "place_of_worship", "pharmacy")
  private val Culture = Set("attraction", "museum", "theatre", "artwork", "gallery", "cinema", "arts_centre",
    "viewpoint", "planetarium", "exhibition_centre", "events_venue", "conference_centre")
  /** Shown from zoom 15 with transit. */
  private val Major = Set("department_store", "mall", "supermarket", "museum", "theatre", "university", "hospital",
    "townhall", "library", "park", "zoo", "stadium", "aquarium", "theme_park", "castle")
  /** Places that hold shops: after the shops from [[CloseZoom]] on. */
  private val Holders = Set(("shop", "mall"), ("shop", "department_store"))
  /** Kinds the Protomaps light "pois" layer draws that have a sprite of the same name. */
  private val Sprites = Set("aerodrome", "animal", "artwork", "attraction", "bar", "beach", "beauty", "bench", "books",
    "building", "bus_stop", "cafe", "clothes", "convenience", "drinking_water", "electronics", "fast_food",
    "ferry_terminal", "forest", "garden", "library", "marina", "museum", "park", "peak", "post_office",
    "restaurant", "school", "stadium", "supermarket", "theatre", "toilets", "university", "zoo")

  def group(key: String, value: String): Group =
    if Transit((key, value)) then Group.Transit
    else if key == "office" then (if value == "government" then Group.Civic else Group.Office)
    else if key == "historic" then Group.Culture
    else if key == "shop" then Group.Shop
    else if Food(value) then Group.Food
    else if Green(value) then Group.Green
    else if Culture(value) then Group.Culture
    else if Civic(value) then Group.Civic
    else Group.Other

  def style(key: String, value: String): Style =
    val g = group(key, value)
    val station = Set(("railway", "station"), ("railway", "halt"), ("public_transport", "station"))((key, value))
    val icon = if station then "train_station" else if Sprites(value) && key != "office" then value else ""
    val minZoom =
      if g == Group.Transit || Major(value) || (key, value) == ("tourism", "attraction") then 15
      else if g == Group.Office then 17
      else 16
    val rank = (minZoom - 15) * 10 + g.order
    // After everything shown from zoom 16, before the offices.
    val rankClose = if Holders((key, value)) then 20 + g.order else rank
    Style(g, icon, minZoom, rank, rankClose)

  /** A venue to draw. */
  final case class Item(venue: Venue, style: Style)

  /** Of `vs` (venues in view), those to draw at `zoom`: shown from `zoom`
    * or the next level (so that zooming in shows them before the next
    * query), one per normalized name within [[SameNameM]], at most `cap`,
    * by rank at `zoom` and then closest to `centre`. Nothing below
    * [[MinZoom]] − 1. Runs off the main thread, a few thousand venues at a
    * time: names come normalized from the index, styles once per tag.
    */
  def select(vs: Seq[VenueIndex.Tagged], zoom: Double, centre: LatLng, cap: Int): Vector[Item] =
    if zoom < MinZoom - 1 then Vector.empty
    else
      val upTo = math.floor(zoom).toInt + 1
      val cosLat = math.cos(math.toRadians(centre.lat))
      val styles = mutable.HashMap.empty[(String, String), Style]
      val cand = vs.iterator.filter(_.normalizedName.nonEmpty)
        .map(t => (t, styles.getOrElseUpdate((t.key, t.value), style(t.key, t.value))))
        .filter(_._2.minZoom <= upTo).toArray
      // Rank, then squared distance in degrees of latitude (an order, not metres).
      val order = cand.map { (t, st) =>
        val dy = t.venue.lat - centre.lat
        val dx = (t.venue.lon - centre.lon) * cosLat
        (st.rankAt(zoom).toDouble * 1e6) + dx * dx + dy * dy
      }
      val idx = cand.indices.sortBy(order(_))
      val kept = mutable.HashMap.empty[String, List[Venue]]
      val out = Vector.newBuilder[Item]
      var n = 0
      val it = idx.iterator
      while n < cap && it.hasNext do
        val (t, st) = cand(it.next())
        val v = t.venue
        val same = kept.getOrElse(t.normalizedName, Nil)
        val r = if st.group == Group.Transit then SameStationM else SameNameM
        if !same.exists(p => Geo.distance(p.lat, p.lon, v.lat, v.lon) < r) then
          kept(t.normalizedName) = v :: same
          out += Item(v, st)
          n += 1
      out.result()
