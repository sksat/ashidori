package ashidori.core

/** One intersection of the National Police Agency's list of 高度化PICS
  * intersections (design 4.3). The list has no intersection IDs.
  */
final case class PicsSite(pref: String, city: String, name: String, lat: Double, lon: Double)

/** The bundled list (`assets/pics/sites.csv`, written by tools/pics/build.py)
  * and the lookups on it.
  */
object PicsSites:
  val Header = "pref,city,name,lat,lon"

  /** The sites of the bundled file. Throws if it is not one. */
  def parse(text: String): Vector[PicsSite] =
    val lines = text.linesIterator.filter(_.nonEmpty).toVector
    if lines.headOption.forall(_.trim != Header) then throw IllegalArgumentException("not a PICS site list")
    lines.tail.zipWithIndex.map { (l, i) =>
      l.split(",", -1) match
        case Array(p, c, n, la, lo) =>
          val lat = la.toDouble
          val lon = lo.toDouble
          if lat < 20 || lat > 46 || lon < 122 || lon > 154 then throw IllegalArgumentException(s"line ${i + 2}: outside Japan")
          PicsSite(p, c, n, lat, lon)
        case _ => throw IllegalArgumentException(s"line ${i + 2}: ${l.take(40)}")
    }

  /** Within this of a received kind 1 position, a listed site is the one sent (design 4.3). */
  val MatchM = 60.0

  /** A grid over the sites (cells of `cellDeg` degrees): the nearest site to
    * a fix, computed on each fix (design 3.1: no Geofence, no new wake-up).
    */
  final class Index(val sites: IndexedSeq[PicsSite], cellDeg: Double = 0.01):
    private def key(lat: Double, lon: Double): (Int, Int) =
      (math.floor(lat / cellDeg).toInt, math.floor(lon / cellDeg).toInt)
    private val cells: Map[(Int, Int), IndexedSeq[PicsSite]] = sites.groupBy(s => key(s.lat, s.lon))

    /** The sites within `radiusM` of (lat, lon), nearest first, with their distance. */
    def within(lat: Double, lon: Double, radiusM: Double): Seq[(PicsSite, Double)] =
      if sites.isEmpty || lat.isNaN || lon.isNaN then return Nil
      // The cell's smaller side here (longitude cells narrow to the north).
      val side = cellDeg * 111_320.0 * math.max(0.1, math.cos(math.toRadians(math.min(math.abs(lat) + cellDeg, 89.0))))
      val rings = math.ceil(radiusM / side).toInt + 1
      val near =
        if rings > 40 then sites
        else
          val (y, x) = key(lat, lon)
          for dy <- -rings to rings; dx <- -rings to rings; s <- cells.getOrElse((y + dy, x + dx), Nil) yield s
      near.map(s => s -> Geo.distance(lat, lon, s.lat, s.lon)).filter(_._2 <= radiusM).sortBy(_._2)

    def nearest(lat: Double, lon: Double, radiusM: Double): Option[(PicsSite, Double)] = within(lat, lon, radiusM).headOption

    /** The listed site a received kind 1 position names: the only one within
      * [[MatchM]] (two or more: none; 96 pairs are under 100 m apart).
      */
    def matching(lat: Double, lon: Double): Option[PicsSite] =
      within(lat, lon, MatchM) match
        case Seq((s, _)) => Some(s)
        case _           => None
