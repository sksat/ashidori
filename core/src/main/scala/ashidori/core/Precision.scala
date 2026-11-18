package ashidori.core

/** The levels of [[Level]] applied to what is sent (design 3.2, 4.7): a
  * fixed grid for coordinates, a zoom limit for map tiles. The level is the
  * granularity of the values sent, not a range the position is hidden in.
  */
object Precision:
  /** Cells per degree of the grid of `level`, or None (sent as it is). */
  def perDegree(level: Level): Option[Int] = level match
    case Level.Area => Some(10)
    case Level.District => Some(100)
    case _ => None

  /** The grid of `level` in degrees (0.1°, 0.01°), or None. */
  def gridDeg(level: Level): Option[Double] = perDegree(level).map(1.0 / _)

  /** The highest tile zoom a map may request at `level` (12, 15), or None. */
  def maxZoom(level: Level): Option[Int] = level match
    case Level.Area => Some(12)
    case Level.District => Some(15)
    case _ => None

  /** A tiny bias so that a value printed as a cell's edge (35.70) is in that cell. */
  private val Eps = 1e-9

  /** Longitude in [-180, 180). */
  def wrapLon(lon: Double): Double =
    val w = ((lon + 180) % 360 + 360) % 360 - 180
    if w >= 180 then w - 360 else w

  /** The cell (row, column) of a position on a grid of `n` cells per degree. */
  def cell(lat: Double, lon: Double, n: Int): (Long, Long) =
    val i = math.floor(math.max(-90.0, math.min(90.0, lat)) * n + Eps).toLong
    val j = math.floor(wrapLon(lon) * n + Eps).toLong
    (math.max(-90L * n, math.min(90L * n - 1, i)), math.max(-180L * n, math.min(180L * n - 1, j)))

  /** The center of the fixed grid cell holding (lat, lon) at `level`;
    * the position as it is for a level without a grid. Every point of a
    * cell gives the same value.
    */
  def snap(lat: Double, lon: Double, level: Level): (Double, Double) =
    perDegree(level) match
      case None => (lat, lon)
      case Some(n) =>
        val (i, j) = cell(lat, lon, n)
        (decimal(2 * i + 1, 2L * n), decimal(2 * j + 1, 2L * n))

  /** `num / den` as the double nearest to its exact decimal. */
  private def decimal(num: Long, den: Long): Double =
    BigDecimal(num) / BigDecimal(den) match
      case d => d.toDouble

  /** The bounding box (south, west, north, east) of the 0.01° cell holding
    * (lat, lon) and its eight neighbours, as decimal strings: what the
    * venue search sends at [[Level.District]] (design 3.2). Holds no digit
    * of the position beyond the cell.
    */
  def districtBox(lat: Double, lon: Double): (String, String, String, String) =
    val (i, j) = cell(lat, lon, 100)
    def edge(k: Long, lo: Long, hi: Long) = java.math.BigDecimal.valueOf(math.max(lo, math.min(hi, k)), 2).toPlainString
    (edge(i - 1, -9000, 9000), edge(j - 1, -18000, 18000), edge(i + 2, -9000, 9000), edge(j + 2, -18000, 18000))
