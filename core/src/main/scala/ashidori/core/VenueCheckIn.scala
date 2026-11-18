package ashidori.core

/** What 「チェックイン」 on a venue tapped on the map checks in to: chosen by
  * context, so that the one button does the likely thing ([[target]]).
  *
  *   1. The stay selected in the day view, at that stay's time (as the
  *      place sheet's 「この滞在にチェックイン」, [[stayTime]]).
  *   2. Else a stay of the day shown at the venue ([[atVenue]]); of several,
  *      the nearest, then the nearest in time to now.
  *   3. Else, on today, now, when the current position is within [[HereM]]
  *      of the venue and not older than [[HereMaxAgeMs]].
  *   4. Else nothing (no button).
  */
object VenueCheckIn:
  /** A stay is at a venue within this of its centre (or within its radius, if larger). */
  val StayM = 150.0
  /** Checking in now: the current position within this of the venue. */
  val HereM = 100.0
  /** A position older than this is not the current one (as the check-in sheet's). */
  val HereMaxAgeMs: Long = 30 * 60_000L
  /** Distances closer than this count as equal (GPS noise): then the time decides. */
  val SameDistanceM = 25.0
  /** A day may hold a stay at the venue only with a position within this
    * of it ([[mayHaveStay]]): a cheap test before the day's timeline is built.
    */
  val PrefilterM = 500.0

  enum Target:
    /** The stay, at `time` (inside it, so the day view shows the check-in on it). */
    case Stay(visit: Visit, time: Long)
    case Now

  /** The time a check-in to a stay gets: 10 minutes in, or the middle of a shorter one. */
  def stayTime(v: Visit): Long = v.start + math.min(v.durationMs / 2, 10 * 60_000L)

  /** The stay is at the venue: its centre within [[StayM]] or within its radius. */
  def near(venue: LatLng, v: Visit): Boolean = v.latLng.distanceTo(venue) <= math.max(StayM, v.radius)

  /** Of `stays`, the one at the venue: the nearest (within [[SameDistanceM]]
    * counts as equal), then the nearest in time to `now`.
    */
  def atVenue(venue: LatLng, stays: Seq[Visit], now: Long): Option[Visit] =
    stays.filter(near(venue, _)).minByOption { v =>
      (math.floor(v.latLng.distanceTo(venue) / SameDistanceM), math.abs(now - stayTime(v)))
    }

  /** The target, or None for no button. `selected`: the stay selected in the
    * day view; `stays`: the stays of the day shown; `today`: that day is
    * today; `here`: the latest position and its time.
    */
  def target(venue: LatLng, selected: Option[Visit], stays: Seq[Visit], today: Boolean,
             here: Option[(LatLng, Long)], now: Long): Option[Target] =
    selected.orElse(atVenue(venue, stays, now)).map(v => Target.Stay(v, stayTime(v)))
      .orElse(Option.when(today && here.exists((p, t) => now - t <= HereMaxAgeMs && p.distanceTo(venue) <= HereM))(Target.Now))

  /** The check-in was made during the stay (a minute's grace at both ends): the day view shows it on the stay. */
  def during(c: CheckIn, v: Visit): Boolean = c.time >= v.start - 60_000 && c.time <= v.end + 60_000

  /** The check-in of the stay that a correction replaces: of those during
    * it, the one closest to its time ([[stayTime]]).
    */
  def ofStay(checkIns: Seq[CheckIn], v: Visit): Option[CheckIn] =
    checkIns.filter(during(_, v)).minByOption(c => math.abs(c.time - stayTime(v)))

  /** A correction to `v` names the stay's place after the venue too, unless
    * the user typed its name: a name from a venue (or from the check-in
    * being replaced) follows the correction; an unnamed place gets one.
    */
  def renames(p: Place, replaced: Option[CheckIn]): Boolean =
    p.name.isEmpty || p.externalId.nonEmpty || replaced.exists(_.venueName == p.name)

  /** Some of `positions` (a day's, sampled) are within [[PrefilterM]] of the venue. */
  def mayHaveStay(venue: LatLng, positions: Iterable[LatLng]): Boolean =
    positions.exists(_.distanceTo(venue) <= PrefilterM)
