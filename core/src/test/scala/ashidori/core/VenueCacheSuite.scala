package ashidori.core

class VenueCacheSuite extends munit.FunSuite:
  private val Day = 86_400_000L
  private val here = LatLng(36.1105, 140.1004)
  /** `m` metres north of `p`. */
  private def north(p: LatLng, m: Double) = LatLng(p.lat + m / 111_320.0, p.lon)
  private def venue(id: String, p: LatLng, name: String = "") = Venue(id, if name.isEmpty then id else name, "カフェ", p.lat, p.lon)

  test("nothing cached: no hit") {
    assertEquals(VenueCache.empty.lookup(here, 200, 0), None)
  }

  test("a stored search is found again, closest first, and fresh for a day") {
    val c = VenueCache.empty
    val ch = c.put(here, 200, 0, Seq(venue("node/2", north(here, 150)), venue("node/1", north(here, 20))), complete = true)
    assertEquals(ch.put.map(_.key), Vector(VenueCache.key(here, 200)))
    assertEquals(ch.delete, Vector.empty)
    val hit = c.lookup(here, 200, Day - 1).get
    assertEquals(hit.venues.map(_._1.id), Vector("node/1", "node/2"))
    assertEqualsDouble(hit.venues.head._2, 20, 0.5)
    assert(hit.fresh)
    assertEquals(hit.fetchedAt, 0L)
    assert(!c.lookup(here, 200, Day).get.fresh)
  }

  test("a neighbouring cell's search shows its venues near the spot but is not fresh for it") {
    val c = VenueCache.empty
    c.put(here, 200, 0, Seq(venue("node/1", north(here, 50)), venue("node/9", north(here, 2_000))), complete = true)
    val there = north(here, 300)
    assertNotEquals(VenueCache.key(there, 200), VenueCache.key(here, 200))
    val hit = c.lookup(there, 200, 1).get
    assertEquals(hit.venues.map(_._1.id), Vector("node/1"))
    assert(!hit.fresh)
    // Far away: nothing.
    assertEquals(c.lookup(north(here, 5_000), 200, 1), None)
  }

  test("a smaller-radius search is not fresh for a larger one, but the reverse is") {
    val c = VenueCache.empty
    c.put(here, 120, 0, Seq(venue("node/1", here)), complete = true)
    assert(!c.lookup(here, 200, 1).get.fresh)
    c.put(here, 200, 0, Seq(venue("node/1", here)), complete = true)
    assert(c.lookup(here, 120, 1).get.fresh)
    assertEquals(c.size, 2)
  }

  test("kept for 90 days after the fetch, then expired") {
    val c = VenueCache.empty
    c.put(here, 200, 0, Seq(venue("node/1", here)), complete = true)
    assert(c.lookup(here, 200, VenueCache.KeepMs - 1).isDefined)
    assertEquals(c.lookup(here, 200, VenueCache.KeepMs), None)
    assertEquals(c.expire(VenueCache.KeepMs).delete, Vector(VenueCache.key(here, 200)))
    assertEquals(c.size, 0)
  }

  test("load drops expired entries and reports them for deletion") {
    val old = VenueCache.Entry(VenueCache.key(here, 200), here.lat, here.lon, 0, 0, Vector(venue("node/1", here)))
    val p = north(here, 1_000)
    val recent = VenueCache.Entry(VenueCache.key(p, 200), p.lat, p.lon, 80 * Day, 80 * Day, Vector(venue("node/2", p)))
    val (c, ch) = VenueCache.load(Seq(old, recent), 91 * Day)
    assertEquals(ch.delete, Vector(old.key))
    assertEquals(c.all, Vector(recent))
  }

  test("a newer search in the same cell replaces the entry; a venue seen twice shows its newest data") {
    val c = VenueCache.empty
    c.put(here, 200, 0, Seq(venue("node/1", here, "旧名")), complete = true)
    c.put(north(here, 10), 200, Day, Seq(venue("node/1", here, "新名")), complete = false)
    assertEquals(c.size, 1)
    assertEquals(c.lookup(here, 200, Day).get.venues.map(_._1.name), Vector("新名"))
    // Overlapping entries of another cell: the newest wins per venue.
    val c2 = VenueCache.empty
    c2.put(north(here, 300), 200, Day, Seq(venue("node/1", here, "新名")), complete = false)
    c2.put(here, 200, 0, Seq(venue("node/1", here, "旧名")), complete = false)
    assertEquals(c2.lookup(here, 200, Day).get.venues.map(_._1.name), Vector("新名"))
  }

  test("a complete answer removes venues it no longer lists from older overlapping entries") {
    val c = VenueCache.empty
    val a = north(here, 800)
    c.put(a, 200, 0, Seq(venue("node/closed", north(here, 650)), venue("node/kept", north(here, 900))), complete = true)
    // A complete answer whose circle does not reach node/closed: nothing removed.
    val ch0 = c.put(here, 200, Day, Seq.empty, complete = true)
    assertEquals(ch0.put.map(_.key), Vector(VenueCache.key(here, 200)))
    // Now one around node/closed without it.
    val b = north(here, 550)
    assertNotEquals(VenueCache.key(a, 200), VenueCache.key(b, 200))
    val ch = c.put(b, 200, 2 * Day, Seq(venue("node/kept", north(here, 900))), complete = true)
    assertEquals(ch.put.map(_.key).toSet, Set(VenueCache.key(a, 200), VenueCache.key(b, 200)))
    assertEquals(c.all.flatMap(_.venues).map(_.id).distinct.sorted, Vector("node/kept"))
  }

  test("an incomplete answer (cut at the limit) removes nothing") {
    val c = VenueCache.empty
    c.put(north(here, 800), 200, 0, Seq(venue("node/x", north(here, 650))), complete = true)
    val ch = c.put(north(here, 550), 200, Day, Seq(venue("node/y", north(here, 560))), complete = false)
    assertEquals(ch.put.size, 1)
    assert(c.all.flatMap(_.venues).exists(_.id == "node/x"))
  }

  test("beyond the size cap the least recently used entries go first") {
    val c = VenueCache.empty
    val per = VenueCache.MaxVenues / 4
    def spot(i: Int) = north(here, i * 5_000.0)
    def vs(i: Int) = (0 until per).map(j => venue(s"node/$i-$j", spot(i)))
    for i <- 0 until 4 do c.put(spot(i), 200, i, vs(i), complete = false)
    // Use the oldest one: now the second is the least recently used.
    val hit = c.lookup(spot(0), 200, 10).get
    c.touch(hit.keys, 10)
    val ch = c.put(spot(4), 200, 11, vs(4), complete = false)
    assertEquals(ch.delete, Vector(VenueCache.key(spot(1), 200)))
    assertEquals(c.venueCount, VenueCache.MaxVenues)
    assert(c.lookup(spot(0), 200, 12).isDefined)
    assertEquals(c.lookup(spot(1), 200, 12), None)
  }

  test("an entry that is put and evicted at once is only deleted") {
    val c = VenueCache.empty
    val ch = c.put(here, 200, 0, (0 to VenueCache.MaxVenues).map(j => venue(s"node/$j", here)), complete = false)
    assertEquals(ch.put, Vector.empty)
    assertEquals(ch.delete, Vector(VenueCache.key(here, 200)))
  }

  test("touch records use only when it moves forward") {
    val c = VenueCache.empty
    c.put(here, 200, 5, Seq(venue("node/1", here)), complete = true)
    assertEquals(c.touch(Seq(VenueCache.key(here, 200)), 5).put, Vector.empty)
    assertEquals(c.touch(Seq(VenueCache.key(here, 200)), 6).put.map(_.usedAt), Vector(6L))
  }
