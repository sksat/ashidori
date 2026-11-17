package ashidori.core

/** The fixture is tools/venues/build_index.py's output for
  * src/test/resources/venues/fixture.tsv:
  *
  *   python3 tools/venues/build_index.py core/src/test/resources/venues/fixture.tsv <tmp> \
  *     --block-points 2 --dict-min 1 && cp <tmp>/venues.aven core/src/test/resources/venues/fixture.aven
  *
  * (small blocks and a preset dictionary, to cover both).
  */
class VenueIndexSuite extends munit.FunSuite:
  private def bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getClass.getResource("/venues/fixture.aven").toURI))
  private lazy val index = VenueIndex(bytes)
  private val tsukuba = LatLng(36.08270, 140.11120)
  private val tokyo = LatLng(35.68120, 139.76710)

  test("reads the header: venues that one checks in to only, in several blocks") {
    // 13 rows, less a platform, a bench and a parking.
    assertEquals(index.size, 10L)
    assert(index.blocks >= 3, index.blocks)
  }

  test("nearby: within the radius, closest first, with Japanese names and labels") {
    val vs = index.nearby(tsukuba, 200)
    assertEquals(vs.map(_._1.id), Vector("node/1", "node/2", "node/3"))
    assertEquals(vs.map(_._1.name), Vector("つくば", "ファミリーマート つくば駅前店", "スターバックス"))
    assertEquals(vs.map(_._1.category), Vector("駅", "コンビニ", "カフェ"))
    assertEqualsDouble(vs.head._2, 0, 0.01)
    assertEqualsDouble(vs(0)._1.lat, 36.0827, 1e-9)
    assertEqualsDouble(vs(0)._1.lon, 140.1112, 1e-9)
    assert(vs(1)._2 > 30 && vs(1)._2 < 60, vs(1)._2)
  }

  test("nearby at Tokyo Station: ways and relations at their center, dropped kinds absent") {
    val vs = index.nearby(tokyo, 300)
    assertEquals(vs.map(_._1.id).toSet, Set("node/10", "node/11", "node/12", "relation/13"))
    assertEquals(vs.head._1.name, "東京")
    assert(!vs.exists(_._1.name == "ベンチ"))
    assertEquals(vs.find(_._1.id == "relation/13").map(_._1.category), Some("ホテル"))
  }

  test("around widens a sparse result up to the limit") {
    assertEquals(index.around(tsukuba, 200, want = 3).size, 3)
    val wide = index.around(tsukuba, 200, want = 5)
    assertEquals(wide.size, 5)
    assert(wide.exists(_._1.id == "node/6"))
    // Never beyond maxM.
    assertEquals(index.around(tsukuba, 200, want = 50, maxM = 1_500).size, 5)
  }

  test("nothing near the sea or outside the grid") {
    assertEquals(index.nearby(LatLng(34.0, 145.0), 1_000), Vector.empty)
    assertEquals(index.nearby(LatLng(10.0, 100.0), 1_000), Vector.empty)
  }

  test("search: by name or Japanese name, ignoring case and width, closest first") {
    assertEquals(index.search("starbucks", tsukuba).map(_._1.id), Vector("node/3"))
    assertEquals(index.search("スターバックス", tsukuba).map(_._1.id), Vector("node/3"))
    assertEquals(index.search("newdays", tokyo).map(_._1.id), Vector("node/12"))
    assertEquals(index.search("東京", tokyo).map(_._1.id), Vector("node/10", "relation/13"))
    // Far away (Sapporo) only with a large enough range.
    assertEquals(index.search("sapporo", tokyo), Vector.empty)
    // Within a wider range (from Otaru, ~30 km), but never beyond the cell cap (~50 km here).
    assertEquals(index.search("sapporo", LatLng(43.19, 141.0), maxM = 50_000).map(_._1.name), Vector("Cafe Sapporo"))
    assertEquals(index.search("sapporo", tokyo, maxM = 2_000_000), Vector.empty)
    assertEquals(index.search("  ", tokyo), Vector.empty)
  }

  test("inflates only the blocks near a query, and keeps them") {
    val fresh = VenueIndex(bytes)
    fresh.nearby(tsukuba, 200)
    val first = fresh.inflated
    assert(first >= 1 && first < fresh.blocks, (first, fresh.blocks))
    fresh.nearby(tsukuba, 200)
    assertEquals(fresh.inflated, first)
  }

  test("rejects damaged files") {
    val b = bytes
    intercept[java.io.IOException](VenueIndex(b.take(b.length - 1)))
    intercept[java.io.IOException](VenueIndex(b.updated(4, 1.toByte)))
    intercept[java.io.IOException](VenueIndex("NOPE".getBytes ++ b.drop(4)))
    // A damaged block fails the query, not the process.
    val broken = b.clone()
    for i <- broken.length - 40 until broken.length do broken(i) = 0x55
    val idx = VenueIndex(broken)
    intercept[java.io.IOException](idx.nearby(LatLng(43.0687, 141.3507), 100))
  }

  test("Morton keys round-trip") {
    for (r, c) <- Seq((0, 0), (1, 0), (0, 1), (2499, 3199), (65535, 65535)) do
      assertEquals(VenueIndex.unmorton(VenueIndex.morton(r, c)), (r, c))
  }

  test("reads from a file with random access") {
    val f = java.io.File.createTempFile("venues", ".aven")
    try
      java.nio.file.Files.write(f.toPath, bytes)
      val idx = VenueIndex.open(f)
      try assertEquals(idx.nearby(tsukuba, 100).map(_._1.id), Vector("node/1", "node/2"))
      finally idx.close()
    finally f.delete()
  }

  // ---- crafted (malicious or broken) files ----

  private object Craft:
    def uv(out: java.io.ByteArrayOutputStream, v0: Long): Unit =
      var v = v0
      while v >= 0x80 do { out.write(((v & 0x7f) | 0x80).toInt); v >>>= 7 }
      out.write(v.toInt)
    def sv(out: java.io.ByteArrayOutputStream, v: Long): Unit = uv(out, (v << 1) ^ (v >> 63))
    def deflate(raw: Array[Byte]): Array[Byte] =
      val d = java.util.zip.Deflater(9, true)
      d.setInput(raw)
      d.finish()
      val out = java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](65536)
      while !d.finished() do out.write(buf, 0, d.deflate(buf))
      d.end()
      out.toByteArray
    /** A file with one block of `points` points, holding `raw` (inflated). */
    def file(raw: Array[Byte], points: Int, scale: Long = 100_000, cell: Long = 1_000,
             south: Long = 3_600_000, west: Long = 14_000_000, rows: Long = 10, cols: Long = 10): Array[Byte] =
      val z = deflate(raw)
      val h = java.io.ByteArrayOutputStream()
      uv(h, scale); uv(h, cell); sv(h, south); sv(h, west); uv(h, rows); uv(h, cols)
      uv(h, 1)
      val c = "shop=yes".getBytes("UTF-8")
      uv(h, c.length); h.write(c)
      uv(h, 0) // no dictionary
      uv(h, 1); uv(h, 0); uv(h, points); uv(h, z.length)
      val hb = h.toByteArray
      val out = java.io.ByteArrayOutputStream()
      out.write("AVEN".getBytes("US-ASCII")); out.write(2); out.write(0)
      out.write(java.nio.ByteBuffer.allocate(4).putInt(hb.length).array())
      out.write(hb); out.write(z)
      out.toByteArray
    /** The start of a block: one cell (key 0) with `points` points, then `names`. */
    def names(points: Int, names: Seq[(Int, Array[Byte])]): Array[Byte] =
      val b = java.io.ByteArrayOutputStream()
      uv(b, 1); uv(b, 0); uv(b, points)
      uv(b, names.size)
      for (shared, suffix) <- names do { uv(b, shared); uv(b, suffix.length); b.write(suffix) }
      b.toByteArray

  /** A complete block: one cell (key 0), a point per name near its corner. */
  private def fullBlock(names: Seq[String]): Array[Byte] =
    val b = java.io.ByteArrayOutputStream()
    val n = names.size
    Craft.uv(b, 1); Craft.uv(b, 0); Craft.uv(b, n)
    Craft.uv(b, n)
    for s <- names do
      val u = s.getBytes("UTF-8")
      Craft.uv(b, 0); Craft.uv(b, u.length); b.write(u)
    for j <- 0 until n do Craft.sv(b, if j == 0 then 10 else 0) // lat
    for j <- 0 until n do Craft.sv(b, if j == 0 then 10 else 0) // lon
    for _ <- 0 until n do Craft.uv(b, 0)                         // category
    for j <- 0 until n do Craft.uv(b, j)                         // name
    for _ <- 0 until n do Craft.uv(b, 0)                         // nameJa
    for j <- 0 until n do Craft.uv(b, j.toLong * 4)              // node/j
    b.toByteArray

  private val p0 = LatLng(36.005, 140.005)

  test("the cache counts the searched (NFKC) forms of the names") {
    // "㌀" (3 bytes) becomes "アパート" (4 chars) when normalized.
    val names = (0 until 200).map(j => "㌀" * 300 + j)
    val idx = VenueIndex(Craft.file(fullBlock(names), names.size))
    assertEquals(idx.nearby(LatLng(36.0001, 140.0001), 100).size, names.size)
    val normChars = names.map(VenueIndex.normalize(_).length.toLong).sum
    assert(normChars > names.map(_.getBytes("UTF-8").length.toLong).sum, normChars)
    assert(idx.cacheBytes >= 2 * normChars, (idx.cacheBytes, normChars))
    assertEquals(idx.search("アパート", LatLng(36.0001, 140.0001)).size, 30)
  }

  test("a block larger than the whole cache serves its query and is not kept") {
    val names = (0 until 200).map(j => "x" * 500 + j)
    val idx = VenueIndex(Craft.file(fullBlock(names), names.size), maxCacheBytes = 100_000)
    assertEquals(idx.nearby(LatLng(36.0001, 140.0001), 100).size, names.size)
    assertEquals(idx.cacheBytes, 0L)
    idx.nearby(LatLng(36.0001, 140.0001), 100)
    assertEquals(idx.inflated, 2)
    // A small one is kept.
    val small = VenueIndex(Craft.file(fullBlock(Seq("a", "b")), 2), maxCacheBytes = 100_000)
    small.nearby(LatLng(36.0001, 140.0001), 100)
    small.nearby(LatLng(36.0001, 140.0001), 100)
    assertEquals(small.inflated, 1)
    assert(small.cacheBytes > 0 && small.cacheBytes <= 100_000)
  }

  test("a grid outside sane ranges is rejected when opening") {
    val raw = Craft.names(1, Seq(0 -> "a".getBytes))
    for (s, c, r, k) <- Seq((2_000_000_000L, 1L, 65_535L, 65_535L), (100_000L, 1L, 10L, 10L),
                            (100_000L, 1_000L, 65_535L, 65_535L), (100_000L, 200_000L, 10L, 10L), (0L, 1L, 1L, 1L)) do
      intercept[java.io.IOException](VenueIndex(Craft.file(raw, 1, scale = s, cell = c, rows = r, cols = k)))
    intercept[java.io.IOException](VenueIndex(Craft.file(raw, 1, south = -9_500_000)))
  }

  test("a front-coded name may not grow past the name limit") {
    val a = Array.fill(1_000)('a'.toByte)
    // The second name repeats the first and adds 1000 bytes more.
    val idx = VenueIndex(Craft.file(Craft.names(1, Seq(0 -> a, 1_000 -> a)), 1))
    val e = intercept[java.io.IOException](idx.nearby(p0, 1_000))
    assert(e.getMessage.contains("name too long"), e.getMessage)
  }

  test("the names of a block may not add up past the block limit") {
    val n = 3_000
    val first = Array.fill(1_000)('a'.toByte)
    // 6000 names of ~1000 bytes each from a few bytes apiece: ~6 MB.
    val raw = Craft.names(n, (0 -> first) +: Seq.fill(2 * n - 1)(999 -> Array('b'.toByte)))
    assert(raw.length < 64 * 1024, raw.length)
    val idx = VenueIndex(Craft.file(raw, n))
    val e = intercept[java.io.IOException](idx.nearby(p0, 1_000))
    assert(e.getMessage.contains("names too large"), e.getMessage)
  }

  test("a block that inflates past the limit fails before it is held") {
    val bomb = new Array[Byte](VenueIndex.MaxInflatedBytes + 1)
    val f = Craft.file(bomb, 1)
    assert(f.length < 64 * 1024, f.length)
    val e = intercept[java.io.IOException](VenueIndex(f).nearby(p0, 1_000))
    assert(e.getMessage.contains("too large"), e.getMessage)
  }

  test("a query enumerates a bounded number of cells, however large the radius") {
    // Fine cells (0.001°) over a large grid.
    val raw = Craft.names(1, Seq(0 -> "a".getBytes))
    val idx = VenueIndex(Craft.file(raw, 1, cell = 100, south = 0, west = 10_000_000, rows = 65_535, cols = 65_535))
    val t0 = System.nanoTime()
    // The block lacks its columns: a query reading it fails cleanly, others find nothing.
    try idx.nearby(LatLng(30, 120), 5_000_000) catch case _: java.io.IOException => ()
    try idx.search("x", LatLng(30, 120), maxM = 5_000_000) catch case _: java.io.IOException => ()
    assert((System.nanoTime() - t0) / 1e9 < 5, "took too long")
  }

  test("the decoded-block cache stays within its memory limit") {
    val idx = VenueIndex(bytes)
    for p <- Seq(tsukuba, tokyo, LatLng(43.0687, 141.3507)) do idx.nearby(p, 500)
    assert(idx.cacheBytes > 0 && idx.cacheBytes <= VenueIndex.MaxCacheBytes, idx.cacheBytes)
  }

  // ---- the map's rectangle query ----

  /** The rectangle (south, west, north, east) that holds the circle of `r` metres around `p`. */
  private def square(p: LatLng, r: Double) =
    val dLat = r / 111_320.0
    val dLon = r / (111_320.0 * math.cos(math.toRadians(p.lat)))
    (p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)

  /** `within` agrees with `nearby`: the circle's venues are those of the
    * enclosing square within the radius, and a wider circle clipped to the
    * square gives the square's venues. Returns the square's count.
    */
  private def checkConsistent(idx: VenueIndex, p: LatLng, r: Double): Int =
    val (s, w, n, e) = square(p, r)
    val box = idx.within(s, w, n, e)
    assertEquals(box.map(_.venue.id).distinct.size, box.size)
    assertEquals(
      box.filter(t => Geo.distance(p.lat, p.lon, t.venue.lat, t.venue.lon) <= r).map(_.venue).toSet,
      idx.nearby(p, r).map(_._1).toSet)
    val inBox = (v: Venue) => v.lat >= s && v.lat <= n && v.lon >= w && v.lon <= e
    assertEquals(idx.nearby(p, r * 1.5).map(_._1).filter(inBox).toSet, box.map(_.venue).toSet)
    box.size

  test("within: the venues of a rectangle with their tags, consistent with nearby") {
    for (p, r) <- Seq((tsukuba, 100.0), (tsukuba, 1_200.0), (tokyo, 50.0), (tokyo, 400.0), (LatLng(36.0835, 140.1115), 30.0)) do
      checkConsistent(index, p, r)
    val (s, w, n, e) = square(tsukuba, 200)
    val tagged = index.within(s, w, n, e).map(t => t.venue.id -> t).toMap
    assertEquals(tagged.keySet, Set("node/1", "node/2", "node/3", "way/4"))
    assertEquals((tagged("node/1").key, tagged("node/1").value), ("railway", "station"))
    assertEquals((tagged("node/3").key, tagged("node/3").value), ("amenity", "cafe"))
    assertEquals((tagged("way/4").key, tagged("way/4").value), ("leisure", "park"))
    // Names and labels as nearby gives them; the shown name normalized.
    assertEquals(tagged("node/3").venue.name, "スターバックス")
    assertEquals(tagged("node/3").venue.category, "カフェ")
    for t <- tagged.values do assertEquals(t.normalizedName, VenueIndex.normalize(t.venue.name))
    val (ts, tw, tn, te) = square(tokyo, 300)
    assertEquals(index.within(ts, tw, tn, te).find(_.venue.id == "node/12").map(_.normalizedName), Some("newdays"))
  }

  test("within: clipped to the rectangle, not to whole cells") {
    // Tsukuba station (36.0827, 140.1112) and the convenience store
    // (36.0830, 140.1115) share a cell; a rectangle around either takes one.
    assertEquals(index.within(36.0826, 140.1111, 36.0828, 140.1113).map(_.venue.id), Vector("node/1"))
    assertEquals(index.within(36.0829, 140.1114, 36.0831, 140.1116).map(_.venue.id), Vector("node/2"))
    // Edges are inclusive.
    assertEquals(index.within(36.0827, 140.1112, 36.0827, 140.1112).map(_.venue.id), Vector("node/1"))
  }

  test("within: empty rectangles, outside the grid, too many cells") {
    assertEquals(index.within(36.09, 140.11, 36.08, 140.12), Vector.empty) // south > north
    assertEquals(index.within(36.08, 140.12, 36.09, 140.11), Vector.empty) // west > east
    assertEquals(index.within(10, 100, 11, 101), Vector.empty)
    assertEquals(index.within(-95, -200, -91, -190), Vector.empty)
    assertEquals(index.within(Double.NaN, 140.11, 36.09, 140.12), Vector.empty)
    // Japan at once is beyond the cell cap: nothing, not a long query.
    assertEquals(index.within(30, 128, 46, 146), Vector.empty)
    val (s, w, n, e) = square(tsukuba, 200)
    assertEquals(index.within(s, w, n, e, maxCells = 1), Vector.empty)
  }

  test("within: inflates only the blocks of the rectangle, and keeps them") {
    val fresh = VenueIndex(bytes)
    val (s, w, n, e) = square(tsukuba, 200)
    fresh.within(s, w, n, e)
    val first = fresh.inflated
    assert(first >= 1 && first < fresh.blocks, (first, fresh.blocks))
    fresh.within(s, w, n, e)
    assertEquals(fresh.inflated, first)
  }

  /** The released index (venues-20261014), when ASHIDORI_VENUES names its
    * venues.aven: consistency at random spots, and the time of the map's
    * query around Tokyo Station (printed).
    */
  test("within on the released index (ASHIDORI_VENUES)") {
    val path = sys.env.get("ASHIDORI_VENUES").map(java.io.File(_)).filter(_.isFile)
    assume(path.nonEmpty, "ASHIDORI_VENUES not set")
    val idx = VenueIndex.open(path.get)
    try
      val rnd = scala.util.Random(7)
      val spots = Seq(tokyo, tsukuba, LatLng(34.7025, 135.4959), LatLng(35.1709, 136.8815)) ++
        Seq.fill(12)(LatLng(33.5 + rnd.nextDouble() * 2.5, 130.5 + rnd.nextDouble() * 9))
      val counts = spots.map(p => checkConsistent(idx, p, 150 + rnd.nextDouble() * 600))
      assert(counts.take(4).forall(_ > 50), counts)
      // A phone's view (412 × 915 dp) around Tokyo Station; 512 dp tiles.
      def view(zoom: Int) =
        val cos = math.cos(math.toRadians(tokyo.lat))
        val mPerDp = 40_075_016.7 * cos / (512 * math.pow(2, zoom))
        val dLat = 915 / 2.0 * mPerDp / 111_320.0
        val dLon = 412 / 2.0 * mPerDp / (111_320.0 * cos)
        (tokyo.lat - dLat, tokyo.lon - dLon, tokyo.lat + dLat, tokyo.lon + dLon)
      for zoom <- Seq(15, 16, 17) do
        val (s, w, n, e) = view(zoom)
        val cold = VenueIndex.open(path.get)
        try
          val t0 = System.nanoTime()
          val vs = cold.within(s, w, n, e)
          val t1 = System.nanoTime()
          cold.within(s, w, n, e)
          val t2 = System.nanoTime()
          val sel = VenueMapLayer.select(vs, zoom.toDouble, tokyo, cap = 1_500)
          val t3 = System.nanoTime()
          println(f"within Tokyo Station z$zoom: ${vs.size} venues, ${cold.inflated} blocks; " +
            f"cold ${(t1 - t0) / 1e6}%.1f ms, cached ${(t2 - t1) / 1e6}%.1f ms; select ${sel.size} in ${(t3 - t2) / 1e6}%.1f ms")
        finally cold.close()
    finally idx.close()
  }
