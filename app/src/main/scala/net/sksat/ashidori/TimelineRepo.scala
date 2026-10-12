package net.sksat.ashidori

import java.time.LocalDate
import ashidori.core.*

final case class DayTimeline(
    date: LocalDate,
    segments: Vector[Segment],
    summary: DaySummary,
    places: Map[Long, Place],
    checkIns: Vector[CheckIn],
    /** placeId -> venue name of a past check-in close to an unnamed place */
    suggestions: Map[Long, String] = Map.empty
):
  def path: Vector[Fix] = segments.collect { case t: Trip => t.path }.flatten

/** Builds timelines from stored raw signals. Must run off the main thread. */
object TimelineRepo:
  private val Margin = 12 * 3_600_000L
  /** Finished days do not change unless the user edits something. */
  private val cache = java.util.concurrent.ConcurrentHashMap[LocalDate, DayTimeline]()

  /** Call after edits (places, mode corrections, check-ins, imports). */
  def invalidate(): Unit = cache.clear()

  def day(date: LocalDate): DayTimeline =
    val today = LocalDate.now(AshidoriApp.zone)
    // Yesterday can still change until the overnight visit ends.
    if date.isBefore(today.minusDays(1)) then
      Option(cache.get(date)).getOrElse {
        val tl = compute(date)
        cache.put(date, tl)
        tl
      }
    else compute(date)

  private def compute(date: LocalDate): DayTimeline =
    val zone = AshidoriApp.zone
    val db = AshidoriApp.db
    val dayStart = date.atStartOfDay(zone).toInstant.toEpochMilli
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
    // Include context on both sides so visits crossing midnight are detected whole.
    val fixes = db.fixes(dayStart - Margin, dayEnd + Margin)
    val motion = db.motion(dayStart - Margin, dayEnd + Margin)
    val built = TimelineBuilder.build(fixes, motion)

    // Link visits to places, persisting newly discovered ones.
    val known = db.places()
    var tmp = 0L
    val (_, created) = Places.assign(built.collect { case v: Visit => v }, known, () => { tmp -= 1; tmp })
    val idMap = created.map(p => p.id -> db.insertPlace(p)).toMap
    val places0 = (known ++ created.map(p => p.copy(id = idMap(p.id)))).map(p => p.id -> p).toMap
    val places = nameAreas(places0, built.collect { case v: Visit => v })
    val all = places.values.toVector
    val linked = built.map {
      case v: Visit => v.copy(placeId = Places.nearest(v, all).map(_.id))
      case s        => s
    }

    val overrides = db.modeOverrides(dayStart - Margin, dayEnd + Margin)
    val corrected = linked.map {
      case t: Trip => t.copy(legs = t.legs.map(l => applyOverride(l, overrides)))
      case s       => s
    }
    val segs = Stats.splitByDay(corrected, zone).getOrElse(date, Vector.empty)
    val steps = db.steps(dayStart, dayEnd)
    DayTimeline(date, segs, Stats.day(date, segs, steps), places, db.checkIns(dayStart, dayEnd), suggest(segs, places))

  /** For unnamed visited places, the nearest earlier check-in venue (≤100 m). */
  private def suggest(segs: Seq[Segment], places: Map[Long, Place]): Map[Long, String] =
    val unnamed = segs.collect { case v: Visit => v.placeId }.flatten.distinct.flatMap(places.get).filterNot(_.named)
    if unnamed.isEmpty then Map.empty
    else
      val past = AshidoriApp.db.checkIns(0, Long.MaxValue)
      unnamed.flatMap { p =>
        past.map(c => c -> Geo.distance(p.lat, p.lon, c.lat, c.lon)).filter(_._2 <= 100).minByOption(_._2)
          .map((c, _) => p.id -> c.venueName)
      }.toMap

  /** Gives unnamed visited places an approximate area name via the platform
    * geocoder (a few per call, cached in the DB).
    */
  private def nameAreas(places: Map[Long, Place], visits: Seq[Visit]): Map[Long, Place] =
    if !android.location.Geocoder.isPresent then places
    else
      val all = places.values.toVector
      val todo = visits.flatMap(v => Places.nearest(v, all)).distinct.filter(p => !p.named && p.area.isEmpty).take(5)
      val g = android.location.Geocoder(AshidoriApp.app, java.util.Locale.JAPANESE)
      todo.foldLeft(places) { (acc, p) =>
        @annotation.nowarn("cat=deprecation")
        val found = scala.util.Try(g.getFromLocation(p.lat, p.lon, 1)).toOption.flatMap(l => Option(l))
        val addr = found.flatMap(l => scala.jdk.CollectionConverters.ListHasAsScala(l).asScala.headOption)
        addr.flatMap(a => Option(a.getAddressLine(0)).flatMap(AreaName.fromAddressLine).orElse(Option(a.getLocality))) match
          case Some(area) =>
            AshidoriApp.db.setPlaceArea(p.id, area)
            acc + (p.id -> p.copy(area = area))
          case None => acc
      }

  /** A user correction applies to a leg if they overlap by more than half. */
  private def applyOverride(l: Leg, os: Seq[(Long, Long, TransportMode)]): Leg =
    os.find { (s, e, _) =>
      val ov = math.min(e, l.end) - math.max(s, l.start)
      ov > (l.end - l.start) / 2
    }.fold(l)((_, _, m) => l.copy(mode = m, confidence = 1.0))
