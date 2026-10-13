package ashidori.core

import java.io.{BufferedInputStream, EOFException, InputStream}
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/** How precise an offline reverse-geocoding result is. */
enum GeoLevel:
  /** Nearest 大字・町丁目 representative point within [[ReverseGeocoder.TownM]]. */
  case Town
  /** Only the municipality: the nearest town point is farther away (mountains, coast). */
  case Municipality
  /** City or town abroad (GeoNames). */
  case City

/** An offline reverse-geocoding result. */
final case class GeoPlace(
    level: GeoLevel,
    /** ISO 3166-1 alpha-2, e.g. "JP" */
    countryCode: String,
    /** Japanese prefecture, empty abroad */
    prefecture: String,
    /** Japanese municipality as in the source ("札幌市中央区", "入間郡毛呂山町"), empty abroad */
    municipality: String,
    /** 大字・町丁目 name in Japan ("神宮前六丁目"), the city name abroad */
    name: String,
    distanceM: Double
):
  /** Short area name in the [[AreaName]] form ("渋谷区神宮前"), shown as "…付近". */
  def area: Option[String] = level match
    case GeoLevel.Town         => AreaName.fromParts(municipality, name)
    case GeoLevel.Municipality => Option(AreaName.municipality(municipality)).filter(_.nonEmpty)
    case GeoLevel.City         => Option(name).filter(_.nonEmpty)

  /** City for per-period summaries ("札幌市", "渋谷区", "パリ"). */
  def city: String = if countryCode == "JP" then AreaName.city(municipality) else name

  /** Country name in Japanese ("日本", "フランス"). */
  def country: String = ReverseGeocoder.countryName(countryCode)

/** Offline reverse geocoder over the index built by `tools/geocoder/build_index.py`:
  * 大字・町丁目 representative points in Japan (位置参照情報, 国土交通省) and cities
  * abroad (GeoNames). Nothing leaves the device.
  *
  * Memory: about 17 bytes per point plus the UTF-8 string table, decoded only
  * for results (roughly 12 MB for the whole of Japan and cities1000).
  */
final class ReverseGeocoder private (
    scale: Int,
    strBytes: Array[Byte],
    strStart: Array[Int],
    regionCountry: Array[Int],
    regionPref: Array[Int],
    regionMuni: Array[Int],
    cellDeg: Double,
    cellKeys: Array[Int],
    cellStart: Array[Int],
    kind: Array[Byte],
    /** round(10 * log10(population)) for cities abroad, else 0 */
    popSize: Array[Byte],
    lat: Array[Int],
    lon: Array[Int],
    nameId: Array[Int],
    regionId: Array[Int]
):
  import ReverseGeocoder.*

  private val latCells = math.round(180 / cellDeg).toInt
  private val lonCells = math.round(360 / cellDeg).toInt

  def size: Int = kind.length

  private def string(i: Int): String = String(strBytes, strStart(i), strStart(i + 1) - strStart(i), StandardCharsets.UTF_8)

  /** Town, then municipality in Japan, then a city abroad; None at sea or in
    * unpopulated areas.
    */
  def lookup(latDeg: Double, lonDeg: Double): Option[GeoPlace] =
    if latDeg.isNaN || lonDeg.isNaN then None
    else
      nearest(latDeg, lonDeg, KindJapan, MunicipalityM) match
        case Some((i, d)) => Some(place(i, d, if d <= TownM then GeoLevel.Town else GeoLevel.Municipality))
        case None         => nearest(latDeg, lonDeg, KindWorld, CityM).map((i, d) => place(i, d, GeoLevel.City))

  private def place(i: Int, d: Double, level: GeoLevel): GeoPlace =
    val r = regionId(i)
    GeoPlace(level, string(regionCountry(r)), string(regionPref(r)), string(regionMuni(r)), string(nameId(i)), d)

  /** Index and distance of the nearest point of `k` within `maxM`. Cities
    * abroad are weighted by population: the distance is divided by
    * [[cityRadius]], so a point in Manhattan belongs to New York rather than
    * to a small town across the river that happens to be closer.
    */
  private[core] def nearest(latDeg: Double, lonDeg: Double, k: Byte, maxM: Double): Option[(Int, Double)] =
    val li = math.floor((latDeg + 90) / cellDeg).toInt
    val lj = math.floor((lonDeg + 180) / cellDeg).toInt
    // One extra cell absorbs rounding differences with the converter.
    val dLat = math.ceil(maxM / MetersPerDeg / cellDeg).toInt + 1
    val cos = math.cos(math.toRadians(math.min(89.0, math.abs(latDeg) + dLat * cellDeg)))
    val dLon = math.min(lonCells / 2, math.ceil(maxM / (MetersPerDeg * cos) / cellDeg).toInt + 1)
    val weighted = k == KindWorld
    var best = -1
    var bestD = maxM
    var bestScore = Double.MaxValue
    var a = math.max(0, li - dLat)
    while a <= math.min(latCells - 1, li + dLat) do
      var b = lj - dLon
      while b <= lj + dLon do
        val wrapped = Math.floorMod(b, lonCells)
        val c = java.util.Arrays.binarySearch(cellKeys, a * lonCells + wrapped)
        if c >= 0 then
          var i = cellStart(c)
          while i < cellStart(c + 1) do
            if kind(i) == k then
              val d = Geo.distance(latDeg, lonDeg, lat(i).toDouble / scale, lon(i).toDouble / scale)
              val score = if weighted then d / cityRadius(popSize(i) & 0xFF) else d
              if d <= maxM && score <= bestScore then
                best = i
                bestD = d
                bestScore = score
            i += 1
        b += 1
      a += 1
    if best < 0 then None else Some((best, bestD))

object ReverseGeocoder:
  /** Town names are used up to this distance from the representative point. */
  val TownM = 2_000.0
  /** Beyond it, only the municipality, up to this distance (coast, mountains). */
  val MunicipalityM = 15_000.0
  /** Cities abroad up to this distance. */
  val CityM = 30_000.0

  private val KindJapan: Byte = 0
  private val KindWorld: Byte = 1
  private val MetersPerDeg = 111_320.0

  /** Nominal radius of a city of population 10^(size/10): 1 km for 2,500
    * people, ~6 km for 100,000, ~29 km for 2 million.
    */
  private[core] def cityRadius(size: Int): Double =
    math.max(1_000.0, 2_000.0 * math.sqrt(math.pow(10, size / 10.0) / 10_000))

  def countryName(cc: String): String =
    if cc.isEmpty then ""
    else
      scala.util.Try(java.util.Locale.Builder().setRegion(cc).build().getDisplayCountry(java.util.Locale.JAPANESE))
        .toOption.filter(_.nonEmpty).getOrElse(cc)

  /** Reads a gzip-compressed index (format described in build_index.py). */
  def load(in: InputStream): ReverseGeocoder =
    val r = Reader(BufferedInputStream(GZIPInputStream(in, 1 << 16), 1 << 16))
    val magic = String(Array.fill(4)(r.u8().toByte), StandardCharsets.US_ASCII)
    if magic != "AGEO" then throw IllegalArgumentException(s"not a geocoder index: $magic")
    val version = r.u8()
    if version != 1 then throw IllegalArgumentException(s"unsupported geocoder index version $version")
    val scale = r.uv()

    val nStr = r.uv()
    val strStart = new Array[Int](nStr + 1)
    var bytes = new Array[Byte](1 << 20)
    var used = 0
    for i <- 0 until nStr do
      val len = r.uv()
      if used + len > bytes.length then bytes = java.util.Arrays.copyOf(bytes, math.max(bytes.length * 2, used + len))
      r.read(bytes, used, len)
      strStart(i) = used
      used += len
    strStart(nStr) = used
    val strBytes = java.util.Arrays.copyOf(bytes, used)

    val nReg = r.uv()
    val rc, rp, rm = new Array[Int](nReg)
    for i <- 0 until nReg do
      rc(i) = r.uv(); rp(i) = r.uv(); rm(i) = r.uv()

    val cellDeg = r.uv() / 1e6
    val nCells = r.uv()
    val keys = new Array[Int](nCells)
    val starts = new Array[Int](nCells + 1)
    var key, start = 0
    for i <- 0 until nCells do
      key += r.uv()
      keys(i) = key
      starts(i) = start
      start += r.uv()
    starts(nCells) = start

    val n = r.uv()
    if n != start then throw IllegalArgumentException(s"cell counts ($start) do not match points ($n)")
    val kind = new Array[Byte](n)
    r.read(kind, 0, n)
    val popSize = new Array[Byte](n)
    r.read(popSize, 0, n)
    def deltas(): Array[Int] =
      val a = new Array[Int](n)
      var acc = 0
      for i <- 0 until n do
        acc += r.sv()
        a(i) = acc
      a
    val lat = deltas()
    val lon = deltas()
    val names = Array.fill(n)(r.uv())
    val regions = Array.fill(n)(r.uv())
    ReverseGeocoder(scale, strBytes, strStart, rc, rp, rm, cellDeg, keys, starts, kind, popSize, lat, lon, names, regions)

  private final class Reader(in: InputStream):
    def u8(): Int =
      val b = in.read()
      if b < 0 then throw EOFException("truncated geocoder index")
      b
    def uv(): Int =
      var v = 0L
      var shift = 0
      var b = u8()
      while (b & 0x80) != 0 do
        v |= (b & 0x7F).toLong << shift
        shift += 7
        b = u8()
      v |= b.toLong << shift
      if v > Int.MaxValue then throw IllegalArgumentException("varint out of range")
      v.toInt
    def sv(): Int =
      val z = uv()
      (z >>> 1) ^ -(z & 1)
    def read(dst: Array[Byte], off: Int, len: Int): Unit =
      var o = off
      while o < off + len do
        val k = in.read(dst, o, off + len - o)
        if k < 0 then throw EOFException("truncated geocoder index")
        o += k

/** Which reverse geocoder may answer. The platform geocoder
  * (`android.location.Geocoder`) may send the position to Google or another
  * provider, so it is asked only when the offline index has no answer and the
  * user allows sending location information to external services.
  */
object GeocodePolicy:
  def usePlatform(offlineAnswered: Boolean, blockExternal: Boolean, platformPresent: Boolean): Boolean =
    !offlineAnswered && !blockExternal && platformPresent

  /** The offline answer, else (if allowed) the platform's. `platform` is
    * evaluated only when allowed.
    */
  def resolve[A](offline: Option[A], blockExternal: Boolean, platformPresent: Boolean)(platform: => Option[A]): Option[A] =
    if usePlatform(offline.isDefined, blockExternal, platformPresent) then platform else offline
