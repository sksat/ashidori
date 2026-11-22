package ashidori.core

import java.nio.file.{Files, Paths}

/** The bundled list of PICS intersections (tools/pics/build.py) and the
  * lookups on it: the grid gives what a full search gives; a received
  * position names a site only when one alone is within 60 m.
  */
class PicsSitesSuite extends munit.FunSuite:
  private val dir = Paths.get("..", "app", "src", "main", "assets", "pics")

  private lazy val text =
    assume(Files.isRegularFile(dir.resolve("sites.csv")), s"no list at $dir")
    Files.readString(dir.resolve("sites.csv"))

  test("the bundled list: 638 intersections, as its manifest says, with the attribution") {
    val sites = PicsSites.parse(text)
    assertEquals(sites.size, 638)
    val m = ujson.read(Files.readString(dir.resolve("manifest.json")))
    assertEquals(m("output")("rows").num.toInt, sites.size)
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("sites.csv")))
      .map(b => f"$b%02x").mkString
    assertEquals(m("output")("sha256").str, sha)
    assertEquals(m("source")("asOf").str, "2026-04-01")
    assertEquals(m("source")("sha256").str.length, 64)
    assert(m("attribution")(0).str.contains("警察庁") && m("attribution")(0).str.contains("加工して作成"))
    assertEquals(sites.groupBy(_.pref).view.mapValues(_.size).toMap.apply("宮城県"), 145)
  }

  test("a list that is not one is refused") {
    intercept[IllegalArgumentException](PicsSites.parse("a,b\n1,2"))
    intercept[IllegalArgumentException](PicsSites.parse(PicsSites.Header + "\n東京都,新宿区,x,51.5,-0.1\n"))
    intercept[IllegalArgumentException](PicsSites.parse(PicsSites.Header + "\n東京都,新宿区,x,35.6\n"))
  }

  test("the grid finds what a full search finds, at any radius") {
    val sites = PicsSites.parse(text)
    val idx = PicsSites.Index(sites)
    val rnd = scala.util.Random(1)
    for _ <- 0 until 3000 do
      val s = sites(rnd.nextInt(sites.size))
      // Around a site (most fixes near one are the interesting ones), and anywhere in Japan.
      val (lat, lon) =
        if rnd.nextBoolean() then (s.lat + (rnd.nextDouble() - 0.5) * 0.02, s.lon + (rnd.nextDouble() - 0.5) * 0.02)
        else (30 + rnd.nextDouble() * 15, 129 + rnd.nextDouble() * 16)
      val r = Seq(60.0, 150.0, 250.0, 1200.0, 5000.0)(rnd.nextInt(5))
      val full = sites.map(x => x -> Geo.distance(lat, lon, x.lat, x.lon)).filter(_._2 <= r).sortBy(_._2)
      assertEquals(idx.within(lat, lon, r).map(_._1).toSet, full.map(_._1).toSet)
      assertEquals(idx.nearest(lat, lon, r).map(_._2), full.headOption.map(_._2))
    assertEquals(idx.nearest(Double.NaN, 139.0, 150), None)
  }

  test("a received position names the only site within 60 m; none when two are") {
    val a = PicsSite("東京都", "新宿区", "A交差点", 35.690000, 139.700000)
    val b = PicsSite("東京都", "新宿区", "B交差点", 35.690400, 139.700000) // 44 m north
    val c = PicsSite("東京都", "新宿区", "C交差点", 35.700000, 139.700000)
    val idx = PicsSites.Index(Vector(a, b, c))
    assertEquals(idx.matching(35.700100, 139.700000), Some(c))
    assertEquals(idx.matching(35.690200, 139.700000), None)
    assertEquals(idx.matching(35.695000, 139.700000), None)
    // The real list: pairs under 100 m apart are many; each site matches itself unless one is within 60 m.
    val sites = PicsSites.parse(text)
    val real = PicsSites.Index(sites)
    val (alone, crowded) = sites.partition(s => real.within(s.lat, s.lon, 60).size == 1)
    assert(alone.forall(s => real.matching(s.lat, s.lon).contains(s)))
    assert(crowded.forall(s => real.matching(s.lat, s.lon).isEmpty))
    assert(alone.size > 600, alone.size)
  }
