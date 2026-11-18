package ashidori.core

import VenueCheckIn.Target

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

  test("1. the selected stay wins, at its time, wherever it is") {
    val far = stay(north(2_000), 9, 1)
    val at = stay(venue, 12, 1)
    assertEquals(VenueCheckIn.target(venue, Some(far), Seq(far, at), today = true, Some((venue, now)), now),
      Some(Target.Stay(far, VenueCheckIn.stayTime(far))))
  }

  test("2. else a stay of the day at the venue, also on today with the position there") {
    val at = stay(north(40), 12, 1)
    val other = stay(north(1_000), 15, 1)
    assertEquals(VenueCheckIn.target(venue, None, Seq(other, at), today = true, Some((venue, now)), now),
      Some(Target.Stay(at, VenueCheckIn.stayTime(at))))
  }

  test("2. of several stays at the venue: the nearest, then the nearest in time to now") {
    val close = stay(north(10), 9, 1)
    val farther = stay(north(120), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(farther, close), now), Some(close))
    // Within the same 25 m: the later one (nearer to now).
    val morning = stay(north(5), 9, 1)
    val evening = stay(north(15), 18, 1)
    assertEquals(VenueCheckIn.atVenue(venue, Seq(morning, evening), now), Some(evening))
    assertEquals(VenueCheckIn.atVenue(venue, Seq(morning, evening), day + 8 * H), Some(morning))
  }

  test("3. else on today, now, with a recent position within 100 m") {
    val elsewhere = stay(north(1_000), 9, 1)
    assertEquals(VenueCheckIn.target(venue, None, Seq(elsewhere), today = true, Some((north(90), now - 60_000)), now), Some(Target.Now))
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((north(90), now - 30 * 60_000L)), now), Some(Target.Now))
  }

  test("4. otherwise no target") {
    // Too far, too old, no position, or not today.
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((north(110), now)), now), None)
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, Some((venue, now - 31 * 60_000L)), now), None)
    assertEquals(VenueCheckIn.target(venue, None, Nil, today = true, None, now), None)
    assertEquals(VenueCheckIn.target(venue, None, Seq(stay(north(300), 9, 1)), today = false, Some((venue, now)), now), None)
  }

  test("prefilter: a position within 500 m") {
    assert(VenueCheckIn.mayHaveStay(venue, Seq(north(2_000), north(450))))
    assert(!VenueCheckIn.mayHaveStay(venue, Seq(north(2_000), north(550))))
    assert(!VenueCheckIn.mayHaveStay(venue, Nil))
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

  test("a correction renames the place unless the user typed its name") {
    val p = Place(1, venue.lat, venue.lon, 50, "")
    val old = CheckIn(1, day, venue.lat, venue.lon, "スターバックス")
    assert(VenueCheckIn.renames(p, None))
    assert(VenueCheckIn.renames(p.copy(name = "ドトール", externalId = "node/9"), None))
    assert(VenueCheckIn.renames(p.copy(name = "スターバックス"), Some(old)))
    assert(!VenueCheckIn.renames(p.copy(name = "研究室"), Some(old)))
    assert(!VenueCheckIn.renames(p.copy(name = "研究室"), None))
  }
