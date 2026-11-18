package ashidori.core

/** What 「チェックイン」 on a venue tapped on the map checks in to: chosen by
  * context, so that the one button does the likely thing ([[target]]).
  *
  *   1. The stay selected in the day view, at that stay's time (as the
  *      place sheet's, [[stayTime]]), if the venue is within [[SelectedMaxM]]
  *      of it.
  *   2. Else a stay of the day shown at the venue ([[atVenue]]); of several,
  *      the nearest, then the nearest in time to now.
  *   3. Else, on today, now, when the current position is within [[HereM]]
  *      of the venue and not older than [[HereMaxAgeMs]].
  *   4. Else nothing (no button).
  *
  * Also the correction of a past stay's check-in ([[correction]]) and the
  * conditions of its 取り消し ([[undo]], [[placeToRemove]]).
  */
object VenueCheckIn:
  /** A stay is at a venue within this of its centre (or within its radius, if larger). */
  val StayM = 150.0
  /** The selected stay is the target only for a venue within this of it. */
  val SelectedMaxM = 1_000.0
  /** Checking in now: the current position within this of the venue. */
  val HereM = 100.0
  /** A position older than this is not the current one (as the check-in sheet's). */
  val HereMaxAgeMs: Long = 30 * 60_000L
  /** Stays whose distances differ by no more than this count as equally near (GPS noise): then the time decides. */
  val SameDistanceM = 25.0

  enum Target:
    /** The stay, at `time` (inside it, so the day view shows the check-in on it). */
    case Stay(visit: Visit, time: Long)
    case Now

  /** The time a check-in to a stay gets: 10 minutes in, or the middle of a shorter one. */
  def stayTime(v: Visit): Long = v.start + math.min(v.durationMs / 2, 10 * 60_000L)

  /** The stay is at the venue: its centre within [[StayM]] or within its radius. */
  def near(venue: LatLng, v: Visit): Boolean = v.latLng.distanceTo(venue) <= math.max(StayM, v.radius)

  /** Of `stays`, the one at the venue: of those no more than
    * [[SameDistanceM]] farther than the nearest, the nearest in time to `now`.
    */
  def atVenue(venue: LatLng, stays: Seq[Visit], now: Long): Option[Visit] =
    val at = stays.filter(near(venue, _)).map(v => v -> v.latLng.distanceTo(venue))
    at.map(_._2).minOption.flatMap { nearest =>
      at.filter(_._2 - nearest <= SameDistanceM).map(_._1).minByOption(v => math.abs(now - stayTime(v)))
    }

  /** The target, or None for no button. `selected`: the stay selected in the
    * day view; `stays`: the stays of the day shown; `today`: that day is
    * today; `here`: the latest position and its time.
    */
  def target(venue: LatLng, selected: Option[Visit], stays: Seq[Visit], today: Boolean,
             here: Option[(LatLng, Long)], now: Long): Option[Target] =
    selected.filter(_.latLng.distanceTo(venue) <= SelectedMaxM).orElse(atVenue(venue, stays, now))
      .map(v => Target.Stay(v, stayTime(v)))
      .orElse(Option.when(today && here.exists((p, t) => now - t <= HereMaxAgeMs && p.distanceTo(venue) <= HereM))(Target.Now))

  /** The check-in was made during the stay (a minute's grace at both ends): the day view shows it on the stay. */
  def during(c: CheckIn, v: Visit): Boolean = c.time >= v.start - 60_000 && c.time <= v.end + 60_000

  /** The check-in of the stay that a correction replaces: of those during
    * it, the one closest to its time ([[stayTime]]).
    */
  def ofStay(checkIns: Seq[CheckIn], v: Visit): Option[CheckIn] =
    checkIns.filter(during(_, v)).minByOption(c => math.abs(c.time - stayTime(v)))

  /** A correction names the stay's place after the venue too, only when its
    * name is not the user's: the place has no name (nor a kind, such as
    * 自宅), or its name is the venue name of the check-in being replaced, or
    * the name of the venue it was named after (`venueName`: that venue's
    * name in the venue index, if found). A typed name never matches.
    */
  def renames(p: Place, replaced: Option[CheckIn], venueName: Option[String]): Boolean =
    p.kind == PlaceKind.Other &&
      (p.name.isEmpty || replaced.exists(_.venueName == p.name) || venueName.contains(p.name))

  /** What a correction of `visit` to `v` (at `at`) writes, from the
    * check-ins as stored now, read in the same transaction.
    */
  enum Plan:
    /** A new check-in at the stay's time (`placeId` to be linked when saved). */
    case Add(row: CheckIn)
    /** The stay's check-in rewritten in place: same id, time, comment and
      * privacy; the venue, its position and category, and the stay's place.
      */
    case Replace(before: CheckIn, after: CheckIn)

  def correction(stored: Seq[CheckIn], visit: Visit, v: Venue, at: LatLng): Plan =
    ofStay(stored, visit) match
      case Some(old) =>
        Plan.Replace(old, old.copy(lat = at.lat, lon = at.lon, venueName = v.name, venueCategory = v.category,
          venueExternalId = v.id, placeId = visit.placeId.orElse(old.placeId)))
      case None => Plan.Add(CheckIn(0, stayTime(visit), at.lat, at.lon, v.name, v.category, v.id))

  /** One action's check-in as it wrote it (`written`, with its id), the row
    * it replaced (None: it added one), and the place it created (None: none).
    */
  final case class Done(written: CheckIn, before: Option[CheckIn], createdPlace: Option[Place] = None)

  enum Undo:
    /** Write the replaced row back (in place). */
    case Restore(row: CheckIn)
    /** Delete the added check-in (a tombstone, see docs/sync-protocol.md). */
    case Remove(id: Long)

  /** 取り消し of `d`, given its row as stored now (None: gone or deleted):
    * only while the row holds exactly what `d` wrote. A later action on the
    * same row (another correction) makes it nothing.
    */
  def undo(d: Done, now: Option[CheckIn]): Option[Undo] =
    Option.when(now.contains(d.written))(d.before.fold(Undo.Remove(d.written.id))(Undo.Restore(_)))

  /** The place to delete with the 取り消し of `d`: the place it created, while
    * it is as created (its area name aside) and no other live check-in refers
    * to it (`otherRefs`).
    */
  def placeToRemove(d: Done, now: Option[Place], otherRefs: Int): Option[Long] =
    d.createdPlace.filter(p => otherRefs == 0 && now.exists(_.copy(area = "") == p.copy(area = ""))).map(_.id)
