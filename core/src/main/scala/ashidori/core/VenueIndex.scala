package ashidori.core

import java.io.{File, IOException, RandomAccessFile}
import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import java.util.zip.Inflater
import scala.collection.mutable

/** The offline venue index (tools/venues/build_index.py, "AVEN" version 2):
  * venues from OpenStreetMap in a grid of ~1 km cells, stored in
  * deflate-compressed blocks of whole cells with an index in the header.
  * Opening reads only the header; a query (around a point, by name, or in
  * a rectangle for the map, [[within]]) inflates the few blocks it needs,
  * and recently used blocks stay decoded (at most [[CacheBlocks]] and
  * [[MaxCacheBytes]], the least recently used dropped first). Searching
  * sends nothing.
  *
  * The file is checked against the pinned sha256 before it is installed,
  * but the reader does not rely on that: every count and length is bounded
  * before anything is allocated or enumerated ([[MaxBlockPoints]],
  * [[MaxNameBytes]], [[MaxBlockStringBytes]], [[MaxInflatedBytes]],
  * [[MaxQueryCells]], the grid limits), and a damaged file fails with an
  * IOException rather than exhausting memory.
  *
  * Thread-safe (queries are serialized).
  */
final class VenueIndex private (src: VenueIndex.Source, maxCacheBytes: Long):
  import VenueIndex.*

  private val (scale, cell, south, west, rows, cols, categories, tags, dict, firstKeys, blockPoints, offsets, lengths) =
    val head = src.read(0, 10)
    if String(head, 0, 4, UTF_8) != "AVEN" then throw IOException("not a venue index")
    if head(4) != 2 then throw IOException(s"unsupported version ${head(4)}")
    val len = ((head(6) & 0xff) << 24) | ((head(7) & 0xff) << 16) | ((head(8) & 0xff) << 8) | (head(9) & 0xff)
    if len <= 0 || len > MaxHeader || 10L + len > src.length then throw IOException("bad header length")
    val r = Reader(src.read(10, len))
    val scale = r.count(MaxScale)
    val cell = r.count(Int.MaxValue)
    val south = r.sv()
    val west = r.sv()
    val rows = r.count(65535)
    val cols = r.count(65535)
    checkGrid(scale, cell, south, west, rows, cols)
    // Each category's tag (key, value) and its label.
    val tags = Array.fill(r.count(MaxCategories)) {
      val kv = r.str(r.count(1024))
      val i = kv.indexOf('=')
      if i < 0 then (kv, "") else (kv.take(i), kv.drop(i + 1))
    }
    val categories = tags.map((k, v) => if v.isEmpty then k else Overpass.label(k, v))
    val dict = r.bytes(r.count(MaxDictionary))
    val n = r.count(MaxBlocks)
    val firstKeys = new Array[Long](n)
    val points = new Array[Int](n)
    val offsets = new Array[Long](n)
    val lengths = new Array[Int](n)
    var k = 0L
    var off = 10L + len
    for i <- 0 until n do
      k += r.uv()
      firstKeys(i) = k
      points(i) = r.count(MaxBlockPoints)
      lengths(i) = r.count(MaxBlockBytes)
      offsets(i) = off
      off += lengths(i)
      if i > 0 && firstKeys(i) <= firstKeys(i - 1) then throw IOException("blocks out of order")
    if off != src.length then throw IOException("index does not match the file size")
    (scale, cell, south.toInt, west.toInt, rows, cols, categories, tags, dict, firstKeys, points, offsets, lengths)

  /** Number of venues. */
  val size: Long = blockPoints.iterator.map(_.toLong).sum
  def blocks: Int = firstKeys.length

  /** Decoded blocks, least recently used first, and their estimated sizes. */
  private val cache = new java.util.LinkedHashMap[Int, Block](CacheBlocks, 0.75f, true)
  private var cachedBytes = 0L
  /** Blocks inflated so far (for measurements). */
  @volatile var inflated = 0
  /** Estimated bytes held by decoded blocks. */
  def cacheBytes: Long = synchronized(cachedBytes)

  /** Venues within `radiusM` of `p`, closest first, distinct by OSM id. */
  def nearby(p: LatLng, radiusM: Double, limit: Int = Int.MaxValue): Vector[(Venue, Double)] = synchronized {
    val out = Vector.newBuilder[(Venue, Double)]
    forCells(p, radiusM, budget = Int.MaxValue) { (b, from, until) =>
      var j = from
      while j < until do
        val d = Geo.distance(p.lat, p.lon, b.lat(j).toDouble / scale, b.lon(j).toDouble / scale)
        if d <= radiusM then out += venue(b, j) -> d
        j += 1
    }
    out.result().sortBy(_._2).take(limit)
  }

  /** [[nearby]] within `radiusM`, widened (×2.5 each time, up to `maxM`)
    * while fewer than `want` venues are found: dense areas stay at the
    * walking distance, sparse ones still get candidates.
    */
  def around(p: LatLng, radiusM: Double = 200, want: Int = 8, maxM: Double = 1_500): Vector[(Venue, Double)] =
    var r = radiusM
    var vs = nearby(p, r)
    while vs.size < want && r < maxM do
      r = math.min(maxM, r * 2.5)
      vs = nearby(p, r)
    vs

  /** Venues whose name contains `query` (ignoring case and full/half width),
    * closest to `p` first, searching outwards up to `maxM` and inflating at
    * most `budget` blocks.
    */
  def search(query: String, p: LatLng, limit: Int = 30, maxM: Double = 20_000, budget: Int = 48): Vector[(Venue, Double)] = synchronized {
    val q = normalize(query)
    if q.isEmpty then Vector.empty
    else
      val found = mutable.LinkedHashMap.empty[String, (Venue, Double)]
      var r = 1_000.0
      var spent = 0
      var done = false
      while !done do
        forCells(p, r, budget - spent) { (b, from, until) =>
          var j = from
          while j < until do
            if matches(b, j, q) then
              val v = venue(b, j)
              if !found.contains(v.id) then
                found(v.id) = v -> Geo.distance(p.lat, p.lon, v.lat, v.lon)
            j += 1
        }
        spent = touched
        if found.size >= limit || r >= maxM || spent >= budget then done = true
        else r = math.min(maxM, r * 4)
      found.values.toVector.sortBy(_._2).take(limit)
  }

  /** Venues in the rectangle `south` ≤ lat ≤ `north`, `west` ≤ lon ≤ `east`
    * (degrees), with their tags, in the file's order. It inflates only the
    * blocks holding the cells that meet the rectangle. A rectangle of more
    * than `maxCells` cells, or one that crosses the antimeridian
    * (`west` > `east`), finds nothing.
    */
  def within(south: Double, west: Double, north: Double, east: Double,
             maxCells: Int = MaxQueryCells): Vector[Tagged] = synchronized {
    val sane = south <= north && west <= east && south <= 90 && north >= -90 && west <= 180 && east >= -180
    // Cell indices as doubles first: a far-away rectangle must not overflow.
    val r0 = math.max(0.0, math.floor((south * scale - this.south) / cell))
    val r1 = math.min(rows - 1.0, math.floor((north * scale - this.south) / cell))
    val c0 = math.max(0.0, math.floor((west * scale - this.west) / cell))
    val c1 = math.min(cols - 1.0, math.floor((east * scale - this.west) / cell))
    if !sane || r0 > r1 || c0 > c1 || (r1 - r0 + 1) * (c1 - c0 + 1) > math.min(maxCells, MaxQueryCells) then Vector.empty
    else
      // In whole scale units, so that 36.0827 × 1e5 still takes 3608270.
      val (s, n) = (math.ceil(south * scale - 1e-6), math.floor(north * scale + 1e-6))
      val (w, e) = (math.ceil(west * scale - 1e-6), math.floor(east * scale + 1e-6))
      // Morton order: the cells of a block come together.
      val keys = (for r <- r0.toInt to r1.toInt; c <- c0.toInt to c1.toInt yield morton(r, c)).sorted
      val out = Vector.newBuilder[Tagged]
      for key <- keys do
        val bi = blockOf(key)
        if bi >= 0 then
          val b = block(bi)
          b.cellRange(key).foreach { (from, until) =>
            var j = from
            while j < until do
              val la = b.lat(j).toDouble
              val lo = b.lon(j).toDouble
              if la >= s && la <= n && lo >= w && lo <= e then
                val (k, v) = tags(b.cat(j))
                out += Tagged(venue(b, j), k, v)
              j += 1
          }
      out.result()
  }

  // ---- internals ----

  /** Blocks used by the last [[forCells]]. */
  private var touched = 0

  /** Calls `f` for the points of every cell within `radiusM` of `p`
    * (closest cells first, at most [[MaxQueryCells]] of them), inflating at
    * most `budget` blocks.
    */
  private def forCells(p: LatLng, radiusM: Double, budget: Int)(f: (Block, Int, Int) => Unit): Unit =
    touched = 0
    if !(p.lat.abs <= 90 && p.lon.abs <= 180 && radiusM >= 0) then return
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * math.max(0.01, math.cos(math.toRadians(p.lat))))
    // Cell indices as doubles first: a far-away point must not overflow.
    val pr = (p.lat * scale - south) / cell
    val pc = (p.lon * scale - west) / cell
    val hr = dLat * scale / cell
    val hc = dLon * scale / cell
    // Within the cap, keep the cells nearest p.
    val half = math.floor(math.sqrt(MaxQueryCells.toDouble) / 2) - 1
    val r0 = math.max(0.0, math.floor(pr - math.min(hr, half)))
    val r1 = math.min(rows - 1.0, math.floor(pr + math.min(hr, half)))
    val c0 = math.max(0.0, math.floor(pc - math.min(hc, half)))
    val c1 = math.min(cols - 1.0, math.floor(pc + math.min(hc, half)))
    if r0 > r1 || c0 > c1 then return
    val cells =
      (for r <- r0.toInt to r1.toInt; c <- c0.toInt to c1.toInt yield (r, c))
        .sortBy((r, c) => (r + 0.5 - pr) * (r + 0.5 - pr) + (c + 0.5 - pc) * (c + 0.5 - pc))
    val used = mutable.Set.empty[Int]
    for (r, c) <- cells do
      val key = morton(r, c)
      val bi = blockOf(key)
      if bi >= 0 && (used.contains(bi) || used.size < budget) then
        used += bi
        val b = block(bi)
        b.cellRange(key).foreach((from, until) => f(b, from, until))
    touched = used.size

  /** The block whose cells may include `key`, or -1. */
  private def blockOf(key: Long): Int =
    var i = java.util.Arrays.binarySearch(firstKeys, key)
    if i < 0 then i = -i - 2
    i

  private def block(i: Int): Block =
    Option(cache.get(i)).getOrElse {
      val b =
        try decode(i)
        catch
          case e: IOException => throw e
          case e: Exception => throw IOException(s"block $i: ${e.getMessage}", e)
      // A block larger than the whole cache serves this query only.
      if b.bytes <= maxCacheBytes then
        cache.put(i, b)
        cachedBytes += b.bytes
        // Least recently used first; the block just read is last, and fits alone.
        val it = cache.entrySet().iterator()
        while (cache.size > CacheBlocks || cachedBytes > maxCacheBytes) && it.hasNext do
          val e = it.next()
          cachedBytes -= e.getValue.bytes
          it.remove()
      b
    }

  private def decode(i: Int): Block =
    val raw = inflate(src.read(offsets(i), lengths(i)))
    inflated += 1
    val r = Reader(raw)
    val n = blockPoints(i)
    val nc = r.count(n)
    val keys = new Array[Long](nc)
    val starts = new Array[Int](nc + 1)
    var at = 0
    for c <- 0 until nc do
      keys(c) = firstKeys(i) + r.uv()
      if c > 0 && keys(c) <= keys(c - 1) then throw IOException(s"block $i: cells out of order")
      starts(c) = at
      at += r.count(n - at)
    starts(nc) = at
    if at != n then throw IOException(s"block $i: cells hold $at of $n points")
    // At most a name and a Japanese name per point.
    val names = new Array[String](r.count(2 * n))
    var prev = Array.emptyByteArray
    var stringBytes = 0L
    for k <- names.indices do
      val shared = r.count(prev.length)
      val len = shared.toLong + r.count(MaxNameBytes)
      if len > MaxNameBytes then throw IOException(s"block $i: name too long")
      stringBytes += len
      if stringBytes > MaxBlockStringBytes then throw IOException(s"block $i: names too large")
      val b = r.prefixed(prev, shared, (len - shared).toInt)
      names(k) = String(b, UTF_8)
      prev = b
    // Names as searched (NFKC can expand a character up to 18 times: one
    // name of at most MaxNameBytes is a bounded allocation), counted before
    // the next one is made.
    val norm = new Array[String](names.length)
    var normChars = 0L
    for k <- names.indices do
      val v = normalize(names(k))
      normChars += v.length
      if normChars > MaxBlockStringBytes then throw IOException(s"block $i: names too large")
      norm(k) = v
    val (r0, c0) = unmorton(keys.headOption.getOrElse(firstKeys(i)))
    val lat = new Array[Int](n)
    val lon = new Array[Int](n)
    var v = south.toLong + r0.toLong * cell
    for j <- 0 until n do { v += r.sv(); lat(j) = clampInt(v) }
    v = west.toLong + c0.toLong * cell
    for j <- 0 until n do { v += r.sv(); lon(j) = clampInt(v) }
    val cat = Array.fill(n)(r.count(categories.length - 1))
    val name = Array.fill(n)(r.count(names.length - 1))
    val nameJa = Array.fill(n)(r.count(names.length))
    val osm = Array.fill(n)(r.uv())
    // Strings are UTF-16: at most 2 bytes per UTF-8 byte of a name.
    Block(keys, starts, names, norm, lat, lon, cat, name, nameJa, osm,
      raw.length + 2 * stringBytes + 2 * normChars + 44L * n + 8L * nc)

  private def inflate(z: Array[Byte]): Array[Byte] =
    val inf = Inflater(true)
    try
      if dict.nonEmpty then inf.setDictionary(dict)
      // Raw deflate may need one more (dummy) byte to finish.
      val in = java.util.Arrays.copyOf(z, z.length + 1)
      inf.setInput(in)
      val out = java.io.ByteArrayOutputStream(math.min(z.length * 4, MaxInflatedBytes))
      val buf = new Array[Byte](64 * 1024)
      while !inf.finished() do
        val n = inf.inflate(buf)
        if n == 0 && (inf.needsInput() || inf.needsDictionary()) then throw IOException("truncated block")
        if out.size.toLong + n > MaxInflatedBytes then throw IOException("block too large")
        out.write(buf, 0, n)
      out.toByteArray
    finally inf.end()

  private def venue(b: Block, j: Int): Venue =
    val n = if b.nameJa(j) > 0 then b.names(b.nameJa(j) - 1) else b.names(b.name(j))
    val t = (b.osm(j) & 3).toInt match
      case 0 => "node"
      case 1 => "way"
      case _ => "relation"
    Venue(s"$t/${b.osm(j) >>> 2}", n, categories(b.cat(j)), b.lat(j).toDouble / scale, b.lon(j).toDouble / scale)

  private def matches(b: Block, j: Int, q: String): Boolean =
    b.normalized(b.name(j)).contains(q) || (b.nameJa(j) > 0 && b.normalized(b.nameJa(j) - 1).contains(q))

  def close(): Unit = src.close()

object VenueIndex:
  /** A venue with its OpenStreetMap tag `key`=`value` (e.g. shop=books). */
  final case class Tagged(venue: Venue, key: String, value: String)

  /** Decoded blocks kept (about 2048 venues each), and their total size. */
  val CacheBlocks = 24
  val MaxCacheBytes: Long = 16L * 1024 * 1024
  /** Limits of a well-formed file (build_index.py stays far below them). */
  val MaxBlockPoints = 65_536
  val MaxNameBytes = 1_024
  val MaxBlockStringBytes: Long = 4L * 1024 * 1024
  val MaxInflatedBytes: Int = 8 * 1024 * 1024
  val MaxQueryCells = 10_000
  val MaxScale = 10_000_000
  private val MaxHeader = 8 * 1024 * 1024
  private val MaxDictionary = 64 * 1024
  private val MaxCategories = 100_000
  private val MaxBlocks = 1_000_000
  private val MaxBlockBytes = 4 * 1024 * 1024

  /** The grid must describe a part of the globe in cells of 0.001° to 1°. */
  private def checkGrid(scale: Int, cell: Int, south: Long, west: Long, rows: Int, cols: Int): Unit =
    val cellDeg = cell.toDouble / scale
    val ok = scale >= 1_000 && cellDeg >= 0.001 && cellDeg <= 1 && rows >= 1 && cols >= 1 &&
      south >= -90L * scale && south + rows.toLong * cell <= 90L * scale + cell &&
      west >= -180L * scale && west + cols.toLong * cell <= 180L * scale + cell
    if !ok then throw IOException("bad grid")

  private def clampInt(v: Long): Int = math.max(Int.MinValue.toLong, math.min(Int.MaxValue.toLong, v)).toInt

  /** Random access to the file's bytes. */
  trait Source:
    def length: Long
    def read(offset: Long, n: Int): Array[Byte]
    def close(): Unit = ()

  final class FileSource(f: File) extends Source:
    private val raf = RandomAccessFile(f, "r")
    val length: Long = raf.length
    def read(offset: Long, n: Int): Array[Byte] = synchronized {
      if offset < 0 || n < 0 || offset + n > length then throw IOException("read past the end")
      val b = new Array[Byte](n)
      raf.seek(offset)
      raf.readFully(b)
      b
    }
    override def close(): Unit = raf.close()

  final class BytesSource(bytes: Array[Byte]) extends Source:
    val length: Long = bytes.length.toLong
    def read(offset: Long, n: Int): Array[Byte] =
      if offset < 0 || n < 0 || offset + n > length then throw IOException("read past the end")
      java.util.Arrays.copyOfRange(bytes, offset.toInt, offset.toInt + n)

  def open(f: File): VenueIndex =
    val s = FileSource(f)
    try VenueIndex(s, MaxCacheBytes)
    catch case e: Exception => { s.close(); throw e }

  def apply(bytes: Array[Byte]): VenueIndex = VenueIndex(BytesSource(bytes), MaxCacheBytes)
  def apply(bytes: Array[Byte], maxCacheBytes: Long): VenueIndex = VenueIndex(BytesSource(bytes), maxCacheBytes)
  def apply(src: Source, maxCacheBytes: Long): VenueIndex =
    try new VenueIndex(src, maxCacheBytes)
    catch
      case e: IOException => throw e
      case e: Exception => throw IOException(s"bad venue index: ${e.getMessage}", e)

  /** Lower case, full-width forms folded (NFKC), spaces dropped. */
  def normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).filterNot(_.isWhitespace)

  def morton(row: Int, col: Int): Long =
    var k = 0L
    for i <- 0 until 16 do
      k |= ((col >> i) & 1L) << (2 * i)
      k |= ((row >> i) & 1L) << (2 * i + 1)
    k

  def unmorton(k: Long): (Int, Int) =
    var r = 0
    var c = 0
    for i <- 0 until 16 do
      c |= ((k >> (2 * i)) & 1L).toInt << i
      r |= ((k >> (2 * i + 1)) & 1L).toInt << i
    (r, c)

  /** `bytes`: estimated memory (names, their searched forms, columns), for the cache limit. */
  private final class Block(val keys: Array[Long], val starts: Array[Int], val names: Array[String],
                            norm: Array[String], val lat: Array[Int], val lon: Array[Int], val cat: Array[Int],
                            val name: Array[Int], val nameJa: Array[Int], val osm: Array[Long], val bytes: Long):
    def normalized(i: Int): String = norm(i)
    /** Points of cell `key` as [from, until). */
    def cellRange(key: Long): Option[(Int, Int)] =
      val i = java.util.Arrays.binarySearch(keys, key)
      if i < 0 then None else Some((starts(i), starts(i + 1)))

  /** Varints over a byte array, bounds-checked. */
  private final class Reader(b: Array[Byte]):
    private var pos = 0
    private def u8(): Int =
      if pos >= b.length then throw IOException("truncated")
      val v = b(pos) & 0xff
      pos += 1
      v
    def uv(): Long =
      var r = 0L
      var shift = 0
      var x = 0
      while
        if shift > 63 then throw IOException("varint too long")
        x = u8()
        r |= (x & 0x7fL) << shift
        shift += 7
        (x & 0x80) != 0
      do ()
      r
    def sv(): Long = { val v = uv(); (v >>> 1) ^ -(v & 1) }
    /** A non-negative count of at most `max`. */
    def count(max: Int): Int =
      val v = uv()
      if v < 0 || v > max then throw IOException(s"value $v out of range")
      v.toInt
    def bytes(n: Int): Array[Byte] =
      if n < 0 || pos + n > b.length then throw IOException("truncated")
      val out = java.util.Arrays.copyOfRange(b, pos, pos + n)
      pos += n
      out
    def str(n: Int): String = String(bytes(n), UTF_8)
    def prefixed(prev: Array[Byte], shared: Int, n: Int): Array[Byte] =
      if n < 0 || pos + n > b.length then throw IOException("truncated")
      val out = new Array[Byte](shared + n)
      System.arraycopy(prev, 0, out, 0, shared)
      System.arraycopy(b, pos, out, shared, n)
      pos += n
      out
