package ashidori.core

/** A single position fix. Times are Unix epoch milliseconds.
  *
  * Optional numeric fields use NaN for "unknown" so that rows map directly onto
  * SQLite columns and Android's `Location` getters without boxing.
  */
final case class Fix(
    time: Long,
    lat: Double,
    lon: Double,
    accuracy: Float = Float.NaN,
    altitude: Double = Double.NaN,
    speed: Float = Float.NaN,
    bearing: Float = Float.NaN,
    provider: String = "gps",
    satellites: Int = -1
):
  def hasSpeed: Boolean = !speed.isNaN
  def hasAccuracy: Boolean = !accuracy.isNaN
  def latLng: LatLng = LatLng(lat, lon)

final case class LatLng(lat: Double, lon: Double):
  def distanceTo(o: LatLng): Double = Geo.distance(lat, lon, o.lat, o.lon)
  /** Google Timeline export style, e.g. `35.6812362°, 139.7671248°`. */
  def toGoogleString: String = f"$lat%.7f°, $lon%.7f°"

object LatLng:
  private val GooglePattern = """\s*(-?[\d.]+)°?\s*,\s*(-?[\d.]+)°?\s*""".r
  def parseGoogle(s: String): Option[LatLng] = s match
    case GooglePattern(a, b) => Some(LatLng(a.toDouble, b.toDouble))
    case _                   => None

/** Accelerometer features summarised over a short window (typically 5-10 s). */
final case class MotionWindow(
    start: Long,
    end: Long,
    /** standard deviation of |a| in m/s^2 (gravity included, so it is orientation free) */
    accelStd: Float,
    /** mean of |a| - g; roughly 0 when the phone is not accelerating */
    accelMean: Float,
    steps: Int
):
  def durationMs: Long = math.max(1L, end - start)
  def cadencePerMin: Double = steps * 60000.0 / durationMs

/** Transport modes. Keys follow the Google Timeline export vocabulary so that
  * exported files can be read by tools that understand that format.
  */
enum TransportMode(val key: String, val label: String, val emoji: String):
  case Still extends TransportMode("STILL", "滞在", "📍")
  case Walking extends TransportMode("WALKING", "徒歩", "🚶")
  case Running extends TransportMode("RUNNING", "ランニング", "🏃")
  case Cycling extends TransportMode("CYCLING", "自転車", "🚲")
  case Car extends TransportMode("IN_PASSENGER_VEHICLE", "車", "🚗")
  case Bus extends TransportMode("IN_BUS", "バス", "🚌")
  case Train extends TransportMode("IN_TRAIN", "電車", "🚆")
  case Subway extends TransportMode("IN_SUBWAY", "地下鉄", "🚇")
  case Flying extends TransportMode("FLYING", "飛行機", "✈️")
  case Ferry extends TransportMode("IN_FERRY", "船", "⛴️")
  case Unknown extends TransportMode("UNKNOWN_ACTIVITY_TYPE", "移動", "➡️")

  /** Display color (map lines, timeline rail), readable on light and dark maps. */
  def color: String = this match
    case Walking => "#2E9D57"
    case Running => "#E8710A"
    case Cycling => "#00A3A3"
    case Car     => "#5B6CD9"
    case Bus     => "#D9822B"
    case Train   => "#1A73E8"
    case Subway  => "#9334E6"
    case Flying  => "#00838F"
    case Ferry   => "#0277BD"
    case Still   => "#80868B"
    case Unknown => "#80868B"

  def isVehicle: Boolean = this match
    case Car | Bus | Train | Subway | Flying | Ferry => true
    case _                                          => false

object TransportMode:
  def fromKey(key: String): TransportMode =
    values.find(_.key == key).getOrElse(key match
      case "IN_VEHICLE" | "IN_CAR" | "IN_TAXI" | "MOTORCYCLING" => Car
      case "IN_TRAM" | "IN_RAIL_VEHICLE"                         => Train
      case "ON_FOOT" | "HIKING"                                  => Walking
      case "ON_BICYCLE"                                          => Cycling
      case "SAILING" | "IN_BOAT"                                 => Ferry
      case _                                                     => Unknown
    )

/** Semantic place label, as in Google Timeline's `semanticType`. */
enum PlaceKind(val key: String, val label: String):
  case Home extends PlaceKind("HOME", "自宅")
  case Work extends PlaceKind("WORK", "職場")
  case School extends PlaceKind("SCHOOL", "学校")
  case Other extends PlaceKind("UNKNOWN", "")

object PlaceKind:
  def fromKey(k: String): PlaceKind = values.find(_.key == k).getOrElse(Other)

final case class Place(
    id: Long,
    lat: Double,
    lon: Double,
    radius: Double,
    name: String,
    kind: PlaceKind = PlaceKind.Other,
    /** OSM element id like `node/123`, when the place came from venue search */
    externalId: String = "",
    /** approximate area from reverse geocoding, e.g. "天王台" */
    area: String = ""
):
  def latLng: LatLng = LatLng(lat, lon)
  /** Best available label: the user's name, the kind, the area, or a placeholder. */
  def displayName: String =
    if name.nonEmpty then name
    else if kind.label.nonEmpty then kind.label
    else if area.nonEmpty then s"${area}付近"
    else "不明な場所"
  def named: Boolean = name.nonEmpty || kind != PlaceKind.Other

/** A Swarm-like explicit check-in. */
final case class CheckIn(
    id: Long,
    time: Long,
    lat: Double,
    lon: Double,
    venueName: String,
    venueCategory: String = "",
    venueExternalId: String = "",
    placeId: Option[Long] = None,
    comment: String = "",
    /** private check-ins are never exported unless explicitly requested */
    isPrivate: Boolean = false
)

/** One element of a day's timeline. */
sealed trait Segment:
  def start: Long
  def end: Long
  def durationMs: Long = end - start

final case class Visit(
    start: Long,
    end: Long,
    lat: Double,
    lon: Double,
    /** rough radius containing most fixes, metres */
    radius: Double,
    fixCount: Int,
    placeId: Option[Long] = None,
    confidence: Double = 0.7
) extends Segment:
  def latLng: LatLng = LatLng(lat, lon)

final case class Leg(
    start: Long,
    end: Long,
    mode: TransportMode,
    distanceM: Double,
    steps: Int,
    confidence: Double,
    maxSpeed: Double,
    medianSpeed: Double,
    /** fraction of the leg's time without a good position (GNSS gap or
      * poor accuracy): the drawn path there is only an approximation
      */
    uncertain: Double = 0,
    /** the mode inferred from the record, when a correction set another one
      * ([[TimelineBuilder.applyOverrides]])
      */
    inferred: Option[TransportMode] = None,
    /** the leg as built, when this is the part of it on one day ([[Stats.splitByDay]]) */
    whole: Option[(Long, Long)] = None
) extends Segment:
  def isUncertain: Boolean = uncertain >= 0.3
  /** The user set a mode other than the inferred one (shown with ✎). */
  def corrected: Boolean = inferred.exists(_ != mode)
  /** The mode the record says, whatever the user set. */
  def autoMode: TransportMode = inferred.getOrElse(mode)
  /** Start and end of the whole leg, also across midnight: a correction is
    * saved for these, so it applies from either day.
    */
  def wholeStart: Long = whole.fold(start)(_._1)
  def wholeEnd: Long = whole.fold(end)(_._2)

final case class Trip(start: Long, end: Long, path: Vector[Fix], legs: Vector[Leg]) extends Segment:
  def distanceM: Double = legs.map(_.distanceM).sum
  def mainMode: TransportMode =
    if legs.isEmpty then TransportMode.Unknown
    else legs.maxBy(_.distanceM).mode

/** A stretch without positions (記録なし) where the user went from `from` to
  * `to` but nothing says how: the phone was off, recording stopped, or the
  * signal was lost for long. Neither a stay nor a trip; it is shown as such
  * and never counted in distance or travel time.
  */
final case class Unrecorded(start: Long, end: Long, from: LatLng, to: LatLng) extends Segment:
  /** Straight line between the last position before and the first after. */
  def displacementM: Double = from.distanceTo(to)

/** Display colours shared by the map lines, chips and legends (the web
  * viewer's `MODES` has the same values; a test compares them).
  */
object Palette:
  /** 記録なし: the dotted line and rail. */
  val Unrecorded = "#9AA0A6"
  /** Stays: the visit markers and the rail's dots. */
  val Stay = "#006B5B"
