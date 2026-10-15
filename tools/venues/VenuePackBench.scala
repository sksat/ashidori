package ashidori.core

// Prototype, kept out of the build. To run it, copy it to
// core/src/test/scala/ashidori/core/ and run
//   VENUE_PACK=<pack.aven> ./gradlew :core:test --rerun --tests ashidori.core.VenuePackBench -i

import java.nio.file.{Files, Paths}

/** Prototype reader of tools/venues/build_packs.py's "AVEN" packs, and a
  * timing of nearby lookups. Not part of the app: runs only when
  * VENUE_PACK points at a pack (deflate, zlib-wrapped).
  */
final class VenuePackProto(bytes: Array[Byte]):
  private var pos = 0
  private def u8(): Int = { val b = bytes(pos) & 0xff; pos += 1; b }
  private def uv(): Long =
    var r = 0L
    var shift = 0
    var b = 0
    while { b = u8(); r |= (b & 0x7fL) << shift; shift += 7; (b & 0x80) != 0 } do ()
    r
  private def sv(): Long = { val v = uv(); (v >>> 1) ^ -(v & 1) }
  private def str(n: Int): String = { val s = String(bytes, pos, n, "UTF-8"); pos += n; s }

  require(String(bytes, 0, 4, "US-ASCII") == "AVEN")
  pos = 4
  require(u8() == 1)
  val scale: Int = uv().toInt
  val south: Int = sv().toInt
  val west: Int = sv().toInt
  val cell: Int = uv().toInt
  val cellsLon: Int = uv().toInt
  val categories: Array[String] = Array.fill(uv().toInt)(str(uv().toInt))
  val names: Array[String] =
    val n = uv().toInt
    val out = new Array[String](n)
    var prev = Array.emptyByteArray
    for i <- 0 until n do
      val shared = uv().toInt
      val len = uv().toInt
      val b = new Array[Byte](shared + len)
      System.arraycopy(prev, 0, b, 0, shared)
      System.arraycopy(bytes, pos, b, shared, len)
      pos += len
      out(i) = String(b, "UTF-8")
      prev = b
    out
  /** Non-empty cells: key and the index of their first point. */
  val (cellKeys: Array[Long], cellStart: Array[Int]) =
    val n = uv().toInt
    val keys = new Array[Long](n)
    val start = new Array[Int](n + 1)
    var k = 0L
    var at = 0
    for i <- 0 until n do
      k += uv()
      keys(i) = k
      start(i) = at
      at += uv().toInt
    start(n) = at
    (keys, start)
  val size: Int = uv().toInt
  val lat = new Array[Int](size)
  val lon = new Array[Int](size)
  val cat = new Array[Int](size)
  val name = new Array[Int](size)
  val nameJa = new Array[Int](size)
  val osm = new Array[Long](size)
  locally {
    var p = south
    for i <- 0 until size do { p += sv().toInt; lat(i) = p }
    p = west
    for i <- 0 until size do { p += sv().toInt; lon(i) = p }
    for i <- 0 until size do cat(i) = uv().toInt
    for i <- 0 until size do name(i) = uv().toInt
    for i <- 0 until size do nameJa(i) = uv().toInt
    for i <- 0 until size do osm(i) = uv()
  }

  /** Venues within `radiusM` of (la, lo), closest first. */
  def nearby(la: Double, lo: Double, radiusM: Double): Vector[(Venue, Double)] =
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * math.cos(math.toRadians(la)))
    def row(d: Double) = math.floor((d * scale - south) / cell).toInt
    def col(d: Double) = math.floor((d * scale - west) / cell).toInt
    val out = Vector.newBuilder[(Venue, Double)]
    for r <- row(la - dLat) to row(la + dLat) do
      val c0 = math.max(0, col(lo - dLon))
      val c1 = math.min(cellsLon - 1, col(lo + dLon))
      if r >= 0 && c0 <= c1 then
        var i = java.util.Arrays.binarySearch(cellKeys, r.toLong * cellsLon + c0)
        if i < 0 then i = -i - 1
        while i < cellKeys.length && cellKeys(i) <= r.toLong * cellsLon + c1 do
          for j <- cellStart(i) until cellStart(i + 1) do
            val d = Geo.distance(la, lo, lat(j).toDouble / scale, lon(j).toDouble / scale)
            if d <= radiusM then
              val n = if nameJa(j) > 0 then names(nameJa(j) - 1) else names(name(j))
              val t = Array("node", "way", "relation")((osm(j) & 3).toInt)
              out += Venue(s"$t/${osm(j) >>> 2}", n, categories(cat(j)), lat(j).toDouble / scale, lon(j).toDouble / scale) -> d
          i += 1
    out.result().sortBy(_._2)

object VenuePackProto:
  def load(path: String): VenuePackProto =
    val inf = java.util.zip.InflaterInputStream(Files.newInputStream(Paths.get(path)))
    try VenuePackProto(inf.readAllBytes()) finally inf.close()

class VenuePackBench extends munit.FunSuite:
  private val path = sys.env.get("VENUE_PACK")

  if path.isDefined then test("lookup timing") {
    val points = sys.env.getOrElse("VENUE_POINTS", "35.6812,139.7671;36.0827,140.1112;35.6896,139.7006")
      .split(";").toSeq.map(_.split(",").map(_.toDouble)).map(a => (a(0), a(1)))
    def ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    for round <- 1 to 3 do
      val t0 = System.nanoTime()
      val p = VenuePackProto.load(path.get)
      println(f"load+decode #$round: ${ms(t0)}%.1f ms, ${p.size} points, ${p.names.length} names, heap ${(Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory) / 1e6}%.0f MB")
    val p = VenuePackProto.load(path.get)
    for (la, lo) <- points; r <- Seq(200.0, 1000.0) do
      for _ <- 1 to 200 do p.nearby(la, lo, r)
      val n = 2000
      val t0 = System.nanoTime()
      var found = 0
      for _ <- 1 to n do found = p.nearby(la, lo, r).size
      println(f"nearby($la, $lo, ${r.toInt} m): $found venues, ${(System.nanoTime() - t0) / 1e3 / n}%.1f µs per lookup; first: ${p.nearby(la, lo, r).take(3).map(_._1.name).mkString(", ")}")
  }
