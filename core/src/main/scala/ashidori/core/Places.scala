package ashidori.core

import java.time.{DayOfWeek, Instant, ZoneId}

/** Links visits to known places and infers home/work from dwell patterns. */
object Places:

  /** Snap distance for matching a visit to an existing place. */
  def matchRadius(place: Place, visit: Visit): Double =
    math.max(place.radius, 80.0) + math.min(visit.radius, 100.0) * 0.5

  /** Finds the best existing place for a visit, if any. */
  def nearest(visit: Visit, places: Seq[Place]): Option[Place] =
    places
      .map(pl => pl -> pl.latLng.distanceTo(visit.latLng))
      .filter { case (pl, d) => d <= matchRadius(pl, visit) }
      .minByOption(_._2)
      .map(_._1)

  /** Assigns every visit to a place, creating new anonymous places as needed.
    * Returns the updated visits and the places that were created.
    */
  def assign(visits: Seq[Visit], known: Seq[Place], newId: () => Long): (Vector[Visit], Vector[Place]) =
    val created = Vector.newBuilder[Place]
    var all = known.toVector
    val out = visits.toVector.map { v =>
      if v.placeId.isDefined then v
      else
        nearest(v, all) match
          case Some(pl) => v.copy(placeId = Some(pl.id))
          case None =>
            val pl = Place(newId(), v.lat, v.lon, math.max(50.0, v.radius), "")
            created += pl
            all :+= pl
            v.copy(placeId = Some(pl.id))
    }
    (out, created.result())

  /** Infers home (most night-time dwell) and work (most weekday-daytime dwell,
    * excluding home). Requires at least `minHours` of evidence each.
    */
  def inferHomeWork(visits: Seq[Visit], zone: ZoneId, minHours: Double = 8): Map[Long, PlaceKind] =
    val night = scala.collection.mutable.Map.empty[Long, Long].withDefaultValue(0L)
    val office = scala.collection.mutable.Map.empty[Long, Long].withDefaultValue(0L)
    val step = 15 * 60_000L
    for v <- visits; id <- v.placeId do
      var t = v.start
      while t < v.end do
        val z = Instant.ofEpochMilli(t).atZone(zone)
        val h = z.getHour
        val dt = math.min(step, v.end - t)
        if h >= 22 || h < 6 then night(id) += dt
        else if h >= 9 && h < 18 && z.getDayOfWeek != DayOfWeek.SATURDAY && z.getDayOfWeek != DayOfWeek.SUNDAY then
          office(id) += dt
        t += step
    val minMs = (minHours * 3_600_000).toLong
    val home = night.filter(_._2 >= minMs).maxByOption(_._2).map(_._1)
    val work = office.filter((id, ms) => ms >= minMs && !home.contains(id)).maxByOption(_._2).map(_._1)
    home.map(_ -> PlaceKind.Home).toMap ++ work.map(_ -> PlaceKind.Work)
