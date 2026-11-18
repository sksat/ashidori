package ashidori.core

import VenueCheckIn.{Done, Plan, Target, Undo}

class VenueCheckInSuite extends munit.FunSuite:
  private val venue = LatLng(36.0826, 140.1114)
  /** `m` metres north of the venue. */
  private def north(m: Double) = LatLng(venue.lat + m / 111_195.0, venue.lon)
  private val H = 3_600_000L
  private val day = 1_763_000_000_000L
  private def stay(at: LatLng, startH: Double, hours: Double, radius: Double = 30) =
    Visit(day + (startH * H).toLong, day + ((startH + hours) * H).toLong, at.lat, at.lon, radius, 20)
  private val now = day + 20 * H

  test("the stay time is 10 minutes in, or the middle of a shorter stay") {
    assertEquals(VenueCheckIn.stayTime(stay(venue, 12, 1)), day + 12 * H + 10 * 60_000L)
    assertEquals(VenueCheckIn.stayTime(stay(venue, 12, 0.25)), day + 12 * H + (0.125 * H).toLong)
  }

  test("near: within 150 m of the centre, or within a larger radius") {
    assert(VenueCheckIn.near(venue, stay(north(149), 12, 1)))
    assert(!VenueCheckIn.near(venue, stay(north(151), 12, 1)))
    assert(VenueCheckIn.near(venue, stay(north(190), 12, 1, radius = 200)))
    assert(!VenueCheckIn.near(venue, stay(north(210), 12, 1, radius = 200)))
  }

  test("1. the selected stay wins, at its time, within 1 km of the venue") {
    val near = stay(north(900), 9, 1)
    val at = stay(venue, 12, 1)
    assertEquals(VenueCheckIn.target(venue, Some(near), Seq(near, at), today = true, Some((venue, now)), now),
      Some(Target.Stay(near, VenueCheckIn.stayTime(near))))
  }

  test("1. a selected stay farther than 1 km falls through to the other rules") {
    val far = stay(north(1_100), 9, 1)
    val at = stay(north(30), 12, 1)
    assertEquals(VenueCheckIn.target(venue, Some(far), Seq(far, at), today = false, None, now),
      Some(Target.Stay(at, VenueCheckIn.stayTime(at))))
    assertEquals(VenueCheckIn.target(venue, Some(far), Seq(far), today = true, Some((venue, now)), now), Some(Target.Now))
    assertEquals(VenueCheckIn.target(venue, Some(far), Seq(far), today = false, None, now), None)
  }

  test("2. else a stay of the day at the venue, also on today with the position there") {
    val at = stay(north(40), 12, 1)
    val other = stay(north(1_000), 15, 1)
    assertEquals(VenueCheckIn.target(venue, None, Seq(other, at), today = true, Some((venue, now)), now),
      Some(Target.Stay(at, VenueCheckIn.stayTime(at))))
  }

  test("2. of several stays at the venue: the nearest, then, within 25 m of it, the nearest in time to now") {
    val close = stay(north(10), 9, 1)
    val farther = stay(north(120), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(farther, close), now), Some(close))
    val morning = stay(north(5), 9, 1)
    val evening = stay(north(15), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(morning, evening), now), Some(evening))
    assertEquals(VenueCheckIn.atVenue(venue, Seq(morning, evening), day + 8 * H), Some(morning))
    // The actual difference counts, not 25 m steps: 24 m and 26 m are 2 m apart.
    val a = stay(north(24), 9, 1)
    val b = stay(north(26), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(a, b), now), Some(b))
    // 30 m farther than the nearest: the nearest, however late.
    val c = stay(north(54), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(a, c), now), Some(a))
  }

  test("3. else on today, now, with a recent position within 100 m") {
    val elsewhere = stay(north(1_000), 9, 1)
    assertEquals(VenueCheckIn.target(venue, None, Seq(elsewhere), today = true, Some((north(90), now - 60_000)), now), Some(Target.Now))
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((north(90), now - 30 * 60_000L)), now), Some(Target.Now))
  }

  test("4. otherwise no target") {
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((north(110), now)), now), None)
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((venue, now - 31 * 60_000L)), now), None)
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, None, now), None)
    assertEquals(VenueCheckIn.target(venue, None, Seq(stay(north(300), 9, 1)), today = false, Some((venue, now)), now), None)
  }

  test("the check-in of a stay: during it, closest to its time") {
    val v = stay(venue, 12, 1)
    def ci(id: Long, t: Long) = CheckIn(id, t, venue.lat, venue.lon, s"v$id")
    val cs = Seq(ci(1, day + 11 * H), ci(2, day + 12 * H + 40 * 60_000L), ci(3, day + 12 * H + 5 * 60_000L), ci(4, day + 13 * H + 30_000))
    assertEquals(VenueCheckIn.ofStay(cs, v).map(_.id), Some(3L))
    assert(VenueCheckIn.during(cs(3), v))
    assert(!VenueCheckIn.during(cs(0), v))
    assertEquals(VenueCheckIn.ofStay(Seq(cs(0)), v), None)
  }

  test("a correction renames the place only when its name is a venue's, or it has none") {
    val p = Place(1, venue.lat, venue.lon, 50, "")
    assert(VenueCheckIn.renames(p))
    assert(VenueCheckIn.renames(p.copy(name = "ドトール", externalId = "node/9", nameSource = NameSource.FromVenue)))
    // Typed (a venue id stays after a typed rename), or of unknown origin (stored before version 9).
    assert(!VenueCheckIn.renames(p.copy(name = "自宅", externalId = "node/1", nameSource = NameSource.Typed)))
    assert(!VenueCheckIn.renames(p.copy(name = "スターバックス", externalId = "node/1")))
    // A kind set by the user (自宅) without a name.
    assert(!VenueCheckIn.renames(p.copy(kind = PlaceKind.Home)))
  }

  test("a typed 「研究室」 stays: typed, checked in there, then the stay's venue changed to a cafe") {
    val p0 = Place(1, venue.lat, venue.lon, 50, "スターバックス", PlaceKind.Other, "node/1", nameSource = NameSource.FromVenue)
    // Typed in the place sheet: the venue id stays, the name is the user's.
    val p1 = PlaceEdit.rename(p0, "研究室").applyTo(p0)
    assertEquals((p1.name, p1.externalId, p1.nameSource), ("研究室", "node/1", NameSource.Typed))
    // A check-in to 「研究室」 (a place offered as a candidate: no venue id) names nothing: the place has a name.
    val v = stay(venue, 12, 1).copy(placeId = Some(1))
    val t = Table()
    t.correct(v, Venue("", "研究室", "", venue.lat, venue.lon))
    // The stay's venue changed to a cafe: the check-in is replaced, the place keeps its name.
    t.correct(v, tully)
    assertEquals(t.rows.map(_.venueName), Vector("タリーズ"))
    assert(!VenueCheckIn.renames(p1))
    // A name a venue set follows the next venue; its 取り消し gives the source back.
    val e = PlaceEdit.rename(p0, "タリーズ", Some("node/2"))
    val p2 = e.applyTo(p0)
    assertEquals(p2.nameSource, NameSource.FromVenue)
    assertEquals(e.revert(p2).map(_.nameSource), Some(NameSource.FromVenue))
    val typed = PlaceEdit.rename(p1, "タリーズ", Some("node/2"))
    assertEquals(typed.revert(typed.applyTo(p1)).map(p => (p.name, p.nameSource)), Some(("研究室", NameSource.Typed)))
  }

  // ---- corrections and their 取り消し, against a table of rows ----

  private val tully = Venue("node/2", "タリーズ", "カフェ", north(80).lat, north(80).lon)
  private val doutor = Venue("node/3", "ドトール", "カフェ", north(60).lat, north(60).lon)
  private val gigo = Venue("node/4", "ギーゴ", "ゲームセンター", north(100).lat, north(100).lon)

  /** The rows of the checkins table (live ones), and the actions as the app runs them. */
  private final class Table:
    var rows = Vector.empty[CheckIn]
    private var nextId = 1L
    /** A correction: the plan from the rows as stored now, then its write. */
    def correct(visit: Visit, v: Venue): Done =
      VenueCheckIn.correction(rows, visit, v, v.latLng) match
        case Plan.Add(row) =>
          val w = row.copy(id = nextId, placeId = visit.placeId)
          nextId += 1
          rows :+= w
          Done(w, None)
        case Plan.Replace(before, after) =>
          rows = rows.map(r => if r.id == after.id then after else r)
          Done(after, Some(before))
    def undo(d: Done): Unit =
      VenueCheckIn.undo(d, rows.find(_.id == d.written.id)).foreach {
        case Undo.Restore(row) => rows = rows.map(r => if r.id == row.id then row else r)
        case Undo.Remove(id)   => rows = rows.filterNot(_.id == id)
      }

  test("a correction re-reads the stay's check-in: two taps give one row") {
    val t = Table()
    val v = stay(venue, 12, 1).copy(placeId = Some(7))
    t.correct(v, tully)
    t.correct(v, tully)
    assertEquals(t.rows.size, 1)
    assertEquals(t.rows.head.venueName, "タリーズ")
    assertEquals(t.rows.head.time, VenueCheckIn.stayTime(v))
  }

  test("a replacement keeps the id, time, comment and privacy") {
    val t = Table()
    val v = stay(venue, 12, 1).copy(placeId = Some(7))
    t.rows = Vector(CheckIn(5, day + 12 * H + 30 * 60_000L, 0, 0, "古い店", "", "node/0", Some(3), "昼食", isPrivate = true))
    val d = t.correct(v, doutor)
    assertEquals(t.rows, Vector(CheckIn(5, day + 12 * H + 30 * 60_000L, doutor.lat, doutor.lon, "ドトール", "カフェ", "node/3", Some(7), "昼食", isPrivate = true)))
    t.undo(d)
    assertEquals(t.rows.head.venueName, "古い店")
    assertEquals(t.rows.head.placeId, Some(3L))
  }

  test("取り消し of an earlier action after a later one changes nothing") {
    val t = Table()
    val v = stay(venue, 12, 1)
    val a = t.correct(v, tully)
    val b = t.correct(v, doutor)
    t.correct(v, gigo)
    val before = t.rows
    t.undo(b)
    assertEquals(t.rows, before)
    t.undo(a)
    assertEquals(t.rows, before)
  }

  test("取り消し of the latest action restores the row, or removes an added one") {
    val t = Table()
    val v = stay(venue, 12, 1)
    val a = t.correct(v, tully)
    val b = t.correct(v, doutor)
    t.undo(b)
    assertEquals(t.rows.map(_.venueName), Vector("タリーズ"))
    // a's row holds what a wrote again: its 取り消し removes it.
    t.undo(a)
    assertEquals(t.rows, Vector.empty)
    // Twice: nothing more.
    t.undo(a)
    assertEquals(t.rows, Vector.empty)
  }

  test("the place an action created goes with its 取り消し only while it is as created and unused") {
    val p = Place(9, tully.lat, tully.lon, 50, "タリーズ", PlaceKind.Other, "node/2")
    val d = Done(CheckIn(1, day, tully.lat, tully.lon, "タリーズ", placeId = Some(9)), None, Some(p))
    assertEquals(VenueCheckIn.placeToRemove(d, Some(p), 0), Some(9L))
    assertEquals(VenueCheckIn.placeToRemove(d, Some(p.copy(area = "有明")), 0), Some(9L))
    assertEquals(VenueCheckIn.placeToRemove(d, Some(p), 1), None)
    assertEquals(VenueCheckIn.placeToRemove(d, Some(p.copy(name = "行きつけ")), 0), None)
    assertEquals(VenueCheckIn.placeToRemove(d, None, 0), None)
    assertEquals(VenueCheckIn.placeToRemove(d.copy(createdPlace = None), Some(p), 0), None)
  }
