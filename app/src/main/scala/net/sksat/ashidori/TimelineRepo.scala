package net.sksat.ashidori

import java.time.LocalDate
import ashidori.core.*

final case class DayTimeline(
    date: LocalDate,
    segments: Vector[Segment],
    summary: DaySummary,
    places: Map[Long, Place],
    checkIns: Vector[CheckIn]
):
  def path: Vector[Fix] = segments.collect { case t: Trip => t.path }.flatten

/** Builds timelines from stored raw signals. Must run off the main thread. */
object TimelineRepo:
  private val Margin = 12 * 3_600_000L

  def day(date: LocalDate): DayTimeline =
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
    val places = (known ++ created.map(p => p.copy(id = idMap(p.id)))).map(p => p.id -> p).toMap
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
    DayTimeline(date, segs, Stats.day(date, segs, steps), places, db.checkIns(dayStart, dayEnd))

  /** A user correction applies to a leg if they overlap by more than half. */
  private def applyOverride(l: Leg, os: Seq[(Long, Long, TransportMode)]): Leg =
    os.find { (s, e, _) =>
      val ov = math.min(e, l.end) - math.max(s, l.start)
      ov > (l.end - l.start) / 2
    }.fold(l)((_, _, m) => l.copy(mode = m, confidence = 1.0))
