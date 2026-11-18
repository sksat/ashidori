package ashidori.core

import java.io.{File, IOException, RandomAccessFile}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption}
import scala.util.Try

/** A west/south/east/north box in degrees. */
final case class Bounds(west: Double, south: Double, east: Double, north: Double):
  def intersects(o: Bounds): Boolean = west < o.east && o.west < east && south < o.north && o.south < north
  /** `o` lies inside this box, allowing `eps` degrees. */
  def contains(o: Bounds, eps: Double = 0): Boolean =
    o.west >= west - eps && o.south >= south - eps && o.east <= east + eps && o.north <= north + eps

/** A zoom-8 Web Mercator tile, the unit of the region packs
  * (docs/design/outbound-and-offline.md 6.4). Ordered by x, then y: the
  * catalog's and the download's order, not by distance from anything.
  */
final case class Cell(x: Int, y: Int) extends Ordered[Cell]:
  def id: String = s"${Cell.Zoom}-$x-$y"
  def compare(o: Cell): Int = if x != o.x then Integer.compare(x, o.x) else Integer.compare(y, o.y)
  def bounds: Bounds = Bounds(Cell.lon(x), Cell.lat(y + 1), Cell.lon(x + 1), Cell.lat(y))
  override def toString: String = id

object Cell:
  val Zoom = 8
  private val N = 1 << Zoom
  private val Re = """8-(\d{1,3})-(\d{1,3})""".r

  def parse(s: String): Option[Cell] = s match
    case Re(x, y) =>
      val (a, b) = (x.toInt, y.toInt)
      Option.when(a < N && b < N && s == s"8-$a-$b")(Cell(a, b))
    case _ => None

  def lon(x: Int): Double = x.toDouble / N * 360.0 - 180.0
  def lat(y: Int): Double = math.toDegrees(math.atan(math.sinh(math.Pi - 2.0 * math.Pi * y / N)))
  private def fx(lon: Double): Double = (lon + 180.0) / 360.0 * N
  private def fy(lat: Double): Double =
    val r = math.toRadians(lat.max(-85.0511).min(85.0511))
    (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.Pi) / 2.0 * N

  /** The cell holding a point. */
  def at(lat: Double, lon: Double): Cell =
    Cell(fx(lon).floor.toInt.max(0).min(N - 1), fy(lat).floor.toInt.max(0).min(N - 1))

  /** Cells a box intersects (a box across the 180° meridian is cut at it), sorted. */
  def covering(b: Bounds): Seq[Cell] =
    val west = b.west.max(-180.0)
    val east = b.east.min(180.0)
    if east <= west || b.north <= b.south then Seq.empty
    else
      val x0 = fx(west).floor.toInt.max(0)
      // An edge on a cell border does not take the next cell.
      val x1 = (math.ceil(fx(east)).toInt - 1).min(N - 1)
      val y0 = fy(b.north).floor.toInt.max(0)
      val y1 = (math.ceil(fy(b.south)).toInt - 1).min(N - 1)
      for x <- x0 to x1; y <- y0 to y1 yield Cell(x, y)

/** A file the catalog lists: name, size and sha256. */
final case class RegionFile(file: String, bytes: Long, sha256: String)

/** One cell of the catalog: its map pack and the style version it is drawn with. */
final case class RegionCellEntry(cell: Cell, style: String, map: RegionFile)

/** The published list of region packs (tools/regionpacks/catalog.py), as
  * pinned by the app ([[RegionPin]]).
  */
final case class RegionCatalog(version: String, build: String, minZoom: Int, maxZoom: Int,
                               styles: Map[String, RegionFile], cells: Map[Cell, RegionCellEntry],
                               attribution: Seq[String]):
  /** Every cell, in the catalog's order. */
  def ordered: Seq[RegionCellEntry] = cells.values.toSeq.sortBy(_.cell)

object RegionCatalog:
  val Format = "ashidori regions catalog v1"
  val MinZoom = 8
  val MaxZoom = 14
  private val ShaRe = "[0-9a-f]{64}".r
  /** Below GitHub's 2 GiB per asset. */
  val MaxPackBytes: Long = 2L * 1024 * 1024 * 1024 - 1
  val MaxStyleBytes: Long = 256L * 1024 * 1024

  /** The download of `e`'s pack in `c`'s version. */
  def target(c: RegionCatalog, e: RegionCellEntry): Target = Target(c.version, e.style, e.map, c.styles(e.style))

  def packName(cell: Cell, version: String): String = s"ashidori-region-${cell.id}-$version.pmtiles"
  def styleName(style: String): String = s"ashidori-regions-style-$style.zip"

  private def file(v: ujson.Value, name: String, max: Long): Either[String, RegionFile] =
    for
      o <- v.objOpt.toRight("not an object")
      f <- o.get("file").flatMap(_.strOpt).filter(_ == name).toRight(s"file name is not $name")
      n <- o.get("bytes").flatMap(_.numOpt).filter(n => n > 0 && n <= max && n == n.floor).map(_.toLong).toRight(s"$name: invalid size")
      s <- o.get("sha256").flatMap(_.strOpt).filter(ShaRe.matches).toRight(s"$name: invalid sha256")
    yield RegionFile(f, n, s)

  /** Parses and checks a catalog: its format, version, zooms, and every
    * file name, size and sha256; cells once each, each with a known style.
    */
  def parse(json: String): Either[String, RegionCatalog] =
    for
      js <- StrictJson.read(json).toRight("not JSON (or a repeated key)")
      o <- js.objOpt.toRight("not an object")
      _ <- Either.cond(o.get("format").flatMap(_.strOpt).contains(Format), (), "unknown format")
      version <- o.get("version").flatMap(_.strOpt).filter(BasemapPin.validVersion).toRight("invalid version")
      build <- o.get("build").flatMap(_.strOpt).filter(BasemapPin.validVersion).toRight("invalid build")
      _ <- Either.cond(o.get("minzoom").flatMap(_.numOpt).contains(MinZoom.toDouble) &&
        o.get("maxzoom").flatMap(_.numOpt).contains(MaxZoom.toDouble), (), "zooms are not 8-14")
      styleList <- o.get("styles").flatMap(_.arrOpt).toRight("no styles")
      styles <- styleList.foldLeft[Either[String, Map[String, RegionFile]]](Right(Map.empty)) { (acc, s) =>
        for
          m <- acc
          id <- s.objOpt.flatMap(_.get("style")).flatMap(_.strOpt).filter(BasemapPin.validVersion).toRight("invalid style")
          _ <- Either.cond(!m.contains(id), (), s"style $id twice")
          f <- file(s, styleName(id), MaxStyleBytes)
        yield m + (id -> f)
      }
      cellList <- o.get("cells").flatMap(_.arrOpt).toRight("no cells")
      cells <- cellList.foldLeft[Either[String, Map[Cell, RegionCellEntry]]](Right(Map.empty)) { (acc, c) =>
        for
          m <- acc
          co <- c.objOpt.toRight("cell is not an object")
          cell <- co.get("cell").flatMap(_.strOpt).flatMap(Cell.parse).toRight("invalid cell")
          _ <- Either.cond(!m.contains(cell), (), s"cell $cell twice")
          style <- co.get("style").flatMap(_.strOpt).filter(styles.contains).toRight(s"$cell: unknown style")
          map <- co.get("map").toRight(s"$cell: no map").flatMap(file(_, packName(cell, version), MaxPackBytes))
        yield m + (cell -> RegionCellEntry(cell, style, map))
      }
      attribution = o.get("attribution").flatMap(_.arrOpt).map(_.flatMap(_.strOpt).toSeq).getOrElse(Seq.empty)
    yield RegionCatalog(version, build, MinZoom, MaxZoom, styles, cells, attribution)

/** The catalog the app accepts (tools/regionpacks/package.sh writes it to
  * the asset `regions-pin.json`): whatever it downloads must have exactly
  * this size and sha256. Fetching it sends no position.
  */
final case class RegionPin(version: String, file: String, bytes: Long, sha256: String):
  def githubUrl: String = s"https://github.com/${BasemapSource.Repository}/releases/download/${RegionPin.tag(version)}/$file"
  def matches(data: Array[Byte]): Boolean =
    data.length == bytes && java.security.MessageDigest.getInstance("SHA-256").digest(data).map(b => f"${b & 0xff}%02x").mkString == sha256

object RegionPin:
  val Format = "ashidori regions pin v1"
  val MaxBytes: Long = 16L * 1024 * 1024
  def tag(version: String): String = s"regions-$version"
  def catalogName(version: String): String = s"ashidori-regions-$version.json"

  def parse(json: String): Either[String, RegionPin] =
    for
      js <- Try(ujson.read(json)).toEither.left.map(_ => "not JSON")
      o <- js.objOpt.toRight("not an object")
      _ <- Either.cond(o.get("format").flatMap(_.strOpt).contains(Format), (), "unknown format")
      v <- o.get("version").flatMap(_.strOpt).filter(BasemapPin.validVersion).toRight("invalid version")
      f <- o.get("file").flatMap(_.strOpt).filter(_ == catalogName(v)).toRight("invalid file name")
      n <- o.get("bytes").flatMap(_.numOpt).filter(n => n > 0 && n <= MaxBytes && n == n.floor).map(_.toLong).toRight("invalid size")
      s <- o.get("sha256").flatMap(_.strOpt).filter("[0-9a-f]{64}".r.matches).toRight("invalid sha256")
    yield RegionPin(v, f, n, s)

/** The fixed 127-byte header of a PMTiles v3 archive: what a pack is
  * checked against after its sha256 (its zooms, and its bounds inside its cell).
  */
object PmtilesHeader:
  final case class Header(minZoom: Int, maxZoom: Int, bounds: Bounds)
  val Length = 127

  def parse(h: Array[Byte]): Either[String, Header] =
    if h.length < Length then Left("short header")
    else if String(h, 0, 7, java.nio.charset.StandardCharsets.US_ASCII) != "PMTiles" || h(7) != 3 then Left("not PMTiles v3")
    else
      val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      def e7(at: Int) = b.getInt(at) / 1e7
      Right(Header(h(100) & 0xff, h(101) & 0xff, Bounds(e7(102), e7(106), e7(110), e7(114))))

  /** The pack of `cell` with zooms `minZoom`..`maxZoom`: its header says
    * exactly those zooms and bounds inside the cell (E7 rounding allowed).
    */
  def check(h: Header, cell: Cell, minZoom: Int, maxZoom: Int): Either[String, Unit] =
    if h.minZoom != minZoom || h.maxZoom != maxZoom then Left(s"zoom ${h.minZoom}-${h.maxZoom}")
    else if !cell.bounds.contains(h.bounds, 2e-7) then Left(s"bounds ${h.bounds} outside $cell")
    else Right(())

  def read(f: File): Either[String, Header] =
    Try {
      val r = RandomAccessFile(f, "r")
      try
        val buf = new Array[Byte](Length)
        r.readFully(buf)
        buf
      finally r.close()
    }.toEither.left.map(_ => "unreadable").flatMap(parse)

/** A region: a name the user gave (or the default) and its cells. Packs
  * are shared between regions; what a region refers to is computed from
  * the cells each time, never counted and stored (design 6.6).
  */
final case class Region(id: String, name: String, cells: Seq[Cell], created: Long)

/** A pack on the device: its version, the style version it is drawn with,
  * its size and when it was placed.
  */
final case class Installed(version: String, style: String, bytes: Long, at: Long)

/** A pack to download: the catalog's entry it was planned from, with its style bundle. */
final case class Target(version: String, style: String, file: RegionFile, bundle: RegionFile)

/** A cell's pack: what is installed, what is planned to replace or add it,
  * and why the last attempt for the target failed.
  */
final case class PackState(installed: Option[Installed], target: Option[Target], failure: Option[String] = None)

/** The regions' `state.json` (design 6.7): regions, the packs of their
  * cells, and files left to delete once no map uses them. Kept apart from
  * the record's database.
  */
final case class RegionState(regions: Seq[Region], packs: Map[Cell, PackState], trash: Seq[String] = Seq.empty):
  /** Cells some region refers to. */
  def referenced: Set[Cell] = regions.flatMap(_.cells).toSet
  /** Pack and style directories the state needs: placed and planned
    * versions of the referenced cells, and their bundles.
    */
  def dirs: Set[String] =
    packs.toSeq.filter((c, _) => referenced(c)).flatMap { (c, p) =>
      p.installed.toSeq.flatMap(i => Seq(RegionStore.packDir(c, i.version), RegionStore.styleDir(i.style))) ++
        p.target.toSeq.flatMap(t => Seq(RegionStore.packDir(c, t.version), RegionStore.styleDir(t.style)))
    }.toSet
  /** `rel` is, holds or lies in a directory the state needs. */
  def needs(rel: String): Boolean = dirs.exists(d => d == rel || d.startsWith(rel + "/") || rel.startsWith(d + "/"))
  /** The trash without what the state needs again (a cell added back, a planned version). */
  def withoutNeededTrash: RegionState = copy(trash = trash.filterNot(needs))
  /** Planned downloads, in cell order (design 6.9). */
  def queue: Seq[(Cell, Target)] = packs.toSeq.collect { case (c, PackState(_, Some(t), _)) => c -> t }.sortBy(_._1)

  /** Adds a region and plans every cell of it the catalog has that is
    * neither on the device nor planned (a pack of another region is shared
    * as it is; an older version of it shows as 新しい版).
    */
  def add(region: Region, catalog: RegionCatalog): RegionState =
    val planned = region.cells.flatMap(catalog.cells.get).foldLeft(packs) { (m, e) =>
      val p = m.getOrElse(e.cell, PackState(None, None))
      if p.installed.nonEmpty || p.target.nonEmpty then m
      else m.updated(e.cell, PackState(None, Some(RegionCatalog.target(catalog, e))))
    }
    copy(regions = regions :+ region, packs = planned)

  /** Plans the catalog's version for the given cells that have an older one (an update). */
  def update(cells: Set[Cell], catalog: RegionCatalog): RegionState =
    copy(packs = packs.map { (c, p) =>
      catalog.cells.get(c) match
        case Some(e) if cells(c) && RegionState.newer(catalog.version, p) =>
          c -> p.copy(target = Some(RegionCatalog.target(catalog, e)), failure = None)
        case _ => c -> p
    })

  /** Removes a region; packs no other region refers to leave the state
    * (their files become deletable).
    */
  def remove(id: String): RegionState =
    val rs = regions.filterNot(_.id == id)
    val keep = rs.flatMap(_.cells).toSet
    copy(regions = rs, packs = packs.filter((c, _) => keep(c)))

  def rename(id: String, name: String): RegionState =
    copy(regions = regions.map(r => if r.id == id then r.copy(name = name) else r))

  /** A region's packs that no other region shares. */
  def own(id: String): Set[Cell] =
    val others = regions.filterNot(_.id == id).flatMap(_.cells).toSet
    regions.find(_.id == id).fold(Set.empty[Cell])(_.cells.toSet -- others)

  def nextId: String =
    val used = regions.map(_.id).toSet
    Iterator.from(1).map(i => s"r$i").find(!used(_)).get

object RegionState:
  val Format = "ashidori regions state v1"
  val empty: RegionState = RegionState(Seq.empty, Map.empty)

  /** The catalog's version is newer than the pack's (installed or planned). */
  def newer(catalogVersion: String, p: PackState): Boolean =
    val have = p.target.map(_.version).orElse(p.installed.map(_.version))
    have.exists(_ < catalogVersion)

  def encode(s: RegionState): String =
    def file(f: RegionFile) = ujson.Obj("file" -> f.file, "bytes" -> f.bytes.toDouble, "sha256" -> f.sha256)
    ujson.write(ujson.Obj(
      "format" -> Format,
      "regions" -> ujson.Arr(s.regions.map(r => ujson.Obj(
        "id" -> r.id, "name" -> r.name, "cells" -> ujson.Arr(r.cells.map(c => ujson.Str(c.id))*), "created" -> r.created.toDouble))*),
      "packs" -> ujson.Arr(s.packs.toSeq.sortBy(_._1).map { (c, p) =>
        val o = ujson.Obj("cell" -> c.id)
        p.installed.foreach(i => o("installed") = ujson.Obj("version" -> i.version, "style" -> i.style,
          "bytes" -> i.bytes.toDouble, "at" -> i.at.toDouble))
        p.target.foreach(t => o("target") = ujson.Obj("version" -> t.version, "style" -> t.style, "map" -> file(t.file),
          "bundle" -> file(t.bundle)))
        p.failure.foreach(f => o("failure") = f)
        o
      }*),
      "trash" -> ujson.Arr(s.trash.map(ujson.Str(_))*)
    ))

  /** None for a state that cannot be read; the caller rebuilds it from the
    * files ([[RegionReconcile]]) and deletes nothing.
    */
  def decode(json: String): Option[RegionState] =
    Try {
      val o = StrictJson.read(json).get.obj
      require(o("format").str == Format)
      def long(v: ujson.Value) = { val n = v.num; require(n == n.floor && n >= 0); n.toLong }
      def file(v: ujson.Value) = RegionFile(v("file").str, long(v("bytes")), v("sha256").str)
      val regions = o("regions").arr.toSeq.map { r =>
        Region(r("id").str, r("name").str, r("cells").arr.toSeq.map(c => Cell.parse(c.str).get), long(r("created")))
      }
      require(regions.map(_.id).distinct.size == regions.size)
      val packs = o("packs").arr.toSeq.map { p =>
        val po = p.obj
        Cell.parse(po("cell").str).get -> PackState(
          po.get("installed").map(i => Installed(i("version").str, i("style").str, long(i("bytes")), long(i("at")))),
          po.get("target").map(t => Target(t("version").str, t("style").str, file(t("map")), file(t("bundle")))),
          po.get("failure").map(_.str))
      }
      require(packs.map(_._1).distinct.size == packs.size)
      for (_, p) <- packs do
        p.installed.foreach(i => require(BasemapPin.validVersion(i.version) && BasemapPin.validVersion(i.style)))
        p.target.foreach(t => require(BasemapPin.validVersion(t.version) && BasemapPin.validVersion(t.style)))
      val trash = o.get("trash").map(_.arr.toSeq.map(_.str)).getOrElse(Seq.empty)
      require(trash.forall(RegionStore.safeRelative))
      RegionState(regions, packs.toMap, trash)
    }.toOption

/** What is on disk under the regions' root ([[RegionStore.scan]]). */
final case class RegionScan(
  /** Cell -> version -> the style version written into the pack's directory. */
  packs: Map[Cell, Map[String, Option[String]]],
  /** Complete style bundles. */
  styles: Set[String],
  /** Everything else under the root that is not the state: partial downloads, extractions, unknown entries. */
  other: Seq[String],
  /** Pack directory -> the size of its archive. */
  bytes: Map[String, Long] = Map.empty
)

/** Brings the state and the files together at start and after a step
  * (design 6.7, 起動時の照合). Pure: the caller deletes what it returns
  * once no map uses it.
  */
object RegionReconcile:
  final case class Result(state: RegionState, delete: Seq[String])

  def apply(stored: Option[RegionState], scan: RegionScan, catalog: Option[RegionCatalog], now: Long): Result =
    stored match
      case None => rebuild(scan, catalog, now)
      case Some(s) =>
        val keep = s.referenced
        val packs = s.packs.filter((c, _) => keep(c)).map { (c, p0) =>
          val onDisk = scan.packs.getOrElse(c, Map.empty)
          // Placed (renamed into place) but stopped before the state said so.
          val p1 = p0.target match
            case Some(t) if onDisk.contains(t.version) =>
              PackState(Some(Installed(t.version, t.style, t.file.bytes, now)), None)
            case _ => p0
          // Its files are gone: planned again from the catalog (if it has the cell).
          val p2 = p1.installed match
            case Some(i) if !onDisk.contains(i.version) =>
              val again = p1.target.orElse(catalog.flatMap(_.cells.get(c)).map(e => RegionCatalog.target(catalog.get, e)))
              PackState(None, again, p1.failure)
            case _ => p1
          c -> p2
        }
        // Referenced cells without a pack entry (e.g. the state was written before the plan): planned.
        val missing = keep.filterNot(packs.contains).flatMap(c => catalog.flatMap(_.cells.get(c)).map(e =>
          c -> PackState(None, Some(RegionCatalog.target(catalog.get, e)))))
        val all = packs ++ missing
        val state = s.copy(packs = all)
        Result(state, deletable(state, scan))

  /** Everything the state does not refer to: other versions and cells,
    * style bundles no pack uses, partial downloads of no planned file,
    * extractions in progress.
    */
  def deletable(s: RegionState, scan: RegionScan): Seq[String] =
    val versions = for
      (c, vs) <- scan.packs.toSeq
      v <- vs.keys.toSeq
      p = s.packs.get(c)
      if !p.exists(x => x.installed.exists(_.version == v) || x.target.exists(_.version == v))
    yield RegionStore.packDir(c, v)
    val usedStyles = s.packs.values.flatMap(p => p.installed.map(_.style) ++ p.target.map(_.style)).toSet
    val styles = scan.styles.toSeq.filterNot(usedStyles).map(RegionStore.styleDir)
    val parts = s.queue.map(_._2.file.file + ".part").toSet ++
      s.queue.map(_._2.bundle.file + ".part")
    val other = scan.other.filterNot(o => o.startsWith(RegionStore.DownloadDir + "/") && parts(o.stripPrefix(RegionStore.DownloadDir + "/")))
    (versions ++ styles ++ other).sorted.distinct

  /** The state could not be read: packs from the files (the newest version
    * of each cell, its style from its directory or the catalog), one
    * provisional region per cell. Nothing is deleted.
    */
  private def rebuild(scan: RegionScan, catalog: Option[RegionCatalog], now: Long): Result =
    val packs = scan.packs.toSeq.flatMap { (c, vs) =>
      vs.keys.maxOption.map { v =>
        val style = vs(v).orElse(catalog.filter(_.version == v).flatMap(_.cells.get(c)).map(_.style)).getOrElse("unknown")
        c -> PackState(Some(Installed(v, style, scan.bytes.getOrElse(RegionStore.packDir(c, v), 0L), now)), None)
      }
    }.toMap
    val regions = packs.keys.toSeq.sorted.zipWithIndex.map((c, i) => Region(s"r${i + 1}", s"地域 ${i + 1}", Seq(c), now))
    Result(RegionState(regions, packs), Seq.empty)

/** How a region looks in the list (design 6.12). */
enum RegionStatus:
  /** Every pack is placed and drawable. `newer`: the catalog has a newer version of one. */
  case Ready(newer: Boolean)
  /** Packs not on the device yet wait or download: `done` of `total` bytes. */
  case Downloading(done: Long, total: Long)
  /** The download of a pack not on the device yet failed (`reason`). */
  case Failed(reason: String)
  /** Packs are complete but their style bundle is not on the device: not drawn. */
  case Unusable
  /** Every pack is drawable, and newer versions of some wait or download (the old ones stay in use). */
  case Updating(done: Long, total: Long)
  /** Every pack is drawable, and the update of one failed (`reason`); the old version stays in use. */
  case UpdateFailed(reason: String)

object RegionStatus:
  def of(r: Region, s: RegionState, styles: Set[String], catalog: Option[RegionCatalog],
         progress: Map[Cell, Long]): RegionStatus =
    val ps = r.cells.flatMap(c => s.packs.get(c).map(c -> _))
    // Packs not on the device yet, and newer versions of packs that are.
    val (added, updated) = ps.filter(_._2.target.nonEmpty).partition(_._2.installed.isEmpty)
    def progressOf(xs: Seq[(Cell, PackState)]) =
      val total = xs.map(_._2.target.get.file.bytes).sum
      (xs.map((c, _) => progress.getOrElse(c, 0L)).sum.min(total), total)
    added.flatMap(_._2.failure).headOption match
      case Some(f) => Failed(f)
      case None =>
        if added.nonEmpty then
          val (d, t) = progressOf(added)
          Downloading(d, t)
        else if ps.exists(_._2.installed.exists(i => !styles(i.style))) then Unusable
        else updated.flatMap(_._2.failure).headOption match
          case Some(f) => UpdateFailed(f)
          case None =>
            if updated.nonEmpty then
              val (d, t) = progressOf(updated)
              Updating(d, t)
            else Ready(catalog.exists(cat => ps.exists((c, p) => cat.cells.contains(c) && RegionState.newer(cat.version, p))))

  /** Bytes of a region: every pack it refers to, shared ones included. */
  def bytes(r: Region, s: RegionState): Long =
    r.cells.flatMap(s.packs.get).map(p => p.installed.map(_.bytes).orElse(p.target.map(_.file.bytes)).getOrElse(0L)).sum

  /** When its packs were last placed. */
  def date(r: Region, s: RegionState): Option[Long] = r.cells.flatMap(s.packs.get).flatMap(_.installed.map(_.at)).maxOption

/** The size a selection of cells would download (design 6.11): the
  * catalog's packs not on the device or planned yet, and the style bundles
  * they need that are not there; cells outside the catalog apart.
  */
final case class RegionEstimate(cells: Seq[Cell], fetch: Seq[Cell], unknown: Seq[Cell], styles: Seq[String], bytes: Long)

object RegionEstimate:
  def apply(cells: Seq[Cell], s: RegionState, catalog: RegionCatalog, stylesOnDisk: Set[String]): RegionEstimate =
    val (known, unknown) = cells.distinct.sorted.partition(catalog.cells.contains)
    val fetch = known.filterNot { c =>
      s.packs.get(c).exists(p => p.installed.exists(_.version == catalog.version) || p.target.exists(_.version == catalog.version))
    }
    val planned = s.queue.map(_._2.style).toSet
    val styles = fetch.map(c => catalog.cells(c).style).distinct.filterNot(st => stylesOnDisk(st) || planned(st))
    val bytes = fetch.map(c => catalog.cells(c).map.bytes).sum + styles.flatMap(catalog.styles.get).map(_.bytes).sum
    RegionEstimate(cells, fetch, unknown, styles, bytes)

/** The regions' files under `root` (filesDir/regions, design 6.7):
  *
  *   state.json                      the state ([[RegionState]])
  *   packs/<cell>/<version>/         a complete pack: map.pmtiles and `style`
  *   styles/<style>/                 a complete, extracted style bundle
  *   .download/<file>.part           a file being downloaded
  *   .tmp-*                          a placement in progress
  *
  * A pack or style directory appears only by renaming a complete one, so
  * its presence means it is complete. The caller serializes the changes.
  */
class RegionStore(val root: File):
  import RegionStore.*
  def stateFile: File = File(root, StateFile)
  def downloadDir: File = File(root, DownloadDir)
  def part(name: String): File = File(downloadDir, s"$name.part")
  def packFile(c: Cell, version: String): File = File(File(root, packDir(c, version)), PackMap)
  def styleRoot(style: String): File = File(root, styleDir(style))
  def stylePresent(style: String): Boolean = File(styleRoot(style), StyleLayers).isFile

  def readState(): Option[Option[RegionState]] =
    if !stateFile.isFile then None
    else Some(Try(String(Files.readAllBytes(stateFile.toPath), UTF_8)).toOption.flatMap(RegionState.decode))

  /** Writes the state: a temporary file, synced, renamed over the old one. */
  def writeState(s: RegionState): Unit =
    root.mkdirs()
    val tmp = File(root, StateFile + ".tmp")
    val out = java.io.FileOutputStream(tmp)
    try
      out.write(RegionState.encode(s).getBytes(UTF_8))
      out.getFD.sync()
    finally out.close()
    Files.move(tmp.toPath, stateFile.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  /** Extracts a verified style bundle and renames it into place; `written`
    * hears every write and stops it by answering false (the budget).
    */
  def placeStyle(zip: File, style: String, maxBytes: Long, written: Long => Boolean = _ => true): File =
    val tmp = File(root, s"${TmpPrefix}style-$style")
    BasemapStore.deleteTree(tmp)
    try
      BasemapStore.extract(zip, tmp, maxBytes, written)
      for r <- Seq(StyleLayers, "manifest.json", "sprites/light.json") if !File(tmp, r).isFile do
        throw IOException(s"style bundle lacks $r")
      val target = styleRoot(style)
      target.getParentFile.mkdirs()
      BasemapStore.deleteTree(target)
      Files.move(tmp.toPath, target.toPath, StandardCopyOption.ATOMIC_MOVE)
      target
    finally BasemapStore.deleteTree(tmp)

  /** Moves a verified pack into place with the style version it is drawn with. */
  def placePack(file: File, c: Cell, version: String, style: String): File =
    val tmp = File(root, s"${TmpPrefix}pack-${c.id}-$version")
    BasemapStore.deleteTree(tmp)
    try
      tmp.mkdirs()
      Files.write(File(tmp, PackStyle).toPath, style.getBytes(UTF_8))
      Files.move(file.toPath, File(tmp, PackMap).toPath, StandardCopyOption.ATOMIC_MOVE)
      val target = File(root, packDir(c, version))
      target.getParentFile.mkdirs()
      BasemapStore.deleteTree(target)
      Files.move(tmp.toPath, target.toPath, StandardCopyOption.ATOMIC_MOVE)
      target
    finally BasemapStore.deleteTree(tmp)

  def scan(): RegionScan =
    // A directory that cannot be listed is an I/O error (not "empty"): the
    // caller changes nothing on it.
    def list(d: File): Seq[String] =
      if !d.isDirectory then Seq.empty
      else Option(names(d)).map(_.toSeq.sorted).getOrElse(throw IOException(s"cannot list ${d.getName}"))
    val other = scala.collection.mutable.ArrayBuffer.empty[String]
    val packs = list(File(root, PacksDir)).flatMap { name =>
      Cell.parse(name) match
        case None =>
          other += s"$PacksDir/$name"
          None
        case Some(c) =>
          val vs = list(File(root, s"$PacksDir/$name")).flatMap { v =>
            val d = File(root, packDir(c, v))
            if BasemapPin.validVersion(v) && File(d, PackMap).isFile then
              Some(v -> Try(String(Files.readAllBytes(File(d, PackStyle).toPath), UTF_8).trim).toOption.filter(BasemapPin.validVersion))
            else
              other += packDir(c, v)
              None
          }
          if vs.isEmpty then
            if list(File(root, s"$PacksDir/$name")).isEmpty then other += s"$PacksDir/$name"
            None
          else Some(c -> vs.toMap)
    }.toMap
    val styles = list(File(root, StylesDir)).filter { s =>
      val ok = BasemapPin.validVersion(s) && stylePresent(s)
      if !ok then other += styleDir(s)
      ok
    }.toSet
    for n <- list(downloadDir) do other += s"$DownloadDir/$n"
    for n <- list(root) if !Set(StateFile, PacksDir, StylesDir, DownloadDir, CatalogDir)(n) do other += n
    val bytes = for (c, vs) <- packs; v <- vs.keys yield packDir(c, v) -> packFile(c, v).length
    RegionScan(packs, styles, other.toSeq.sorted, bytes.toMap)

  /** The names in a directory (`File.list`); null on an I/O error. */
  protected def names(d: File): Array[String] = d.list()

  /** Deletes `rel` (a path [[RegionScan]] or [[RegionReconcile]] named). */
  def delete(rel: String): Unit =
    if safeRelative(rel) then BasemapStore.deleteTree(File(root, rel))

object RegionStore:
  val StateFile = "state.json"
  val PacksDir = "packs"
  val StylesDir = "styles"
  val DownloadDir = ".download"
  /** The downloaded catalog, kept to plan updates offline. */
  val CatalogDir = "catalog"
  val TmpPrefix = ".tmp-"
  val PackMap = "map.pmtiles"
  val PackStyle = "style"
  val StyleLayers = "layers.json"

  def packDir(c: Cell, version: String): String = s"$PacksDir/${c.id}/$version"
  def styleDir(style: String): String = s"$StylesDir/$style"

  private val Segment = "[A-Za-z0-9._-]{1,128}".r
  /** A path inside the root: segments of safe characters, no `..`. */
  def safeRelative(rel: String): Boolean =
    rel.nonEmpty && rel.length <= 512 && !rel.startsWith("/") &&
      rel.split('/').forall(s => Segment.matches(s) && s != "." && s != "..")

/** Which pack and style directories the maps on screen draw from now
  * (design 6.7, 使用中のファイルを消さない). A map or snapshot registers the
  * directories of the style it loaded under itself ([[hold]]) and drops
  * them when it loads another style or ends ([[release]]). Thread-safe.
  */
final class RegionUse:
  private val held = scala.collection.mutable.Map.empty[AnyRef, Set[String]]
  def hold(owner: AnyRef, dirs: Set[String]): Unit = synchronized {
    if dirs.isEmpty then held -= owner else held(owner) = dirs
  }
  def release(owner: AnyRef): Boolean = synchronized(held.remove(owner).nonEmpty)
  /** `rel` or something under it is drawn from now. */
  def inUse(rel: String): Boolean = synchronized {
    held.values.exists(_.exists(d => d == rel || d.startsWith(rel + "/") || rel.startsWith(d + "/")))
  }
  def holders: Int = synchronized(held.size)
  /** `owner` holds `rel`. */
  def holds(owner: AnyRef, rel: String): Boolean = synchronized(held.get(owner).exists(_.contains(rel)))

/** The offline style with the region packs (design 6.10): each pack's
  * source `r-<cell>` (bounds of its cell, zooms 8-14) and its style
  * bundle's layers for that source, the shapes of all packs above the base
  * map's shapes and their labels above every other label; the fonts of a
  * bundle (a superset of the basemap's) as the style's only `glyphs`.
  */
object RegionStyle:
  /** One placed pack: its cell, its archive, its bundle's layers ({"base","labels"}) and directory. */
  final case class Pack(cell: Cell, archive: String, layers: String, styleDir: String)

  def sourceId(c: Cell): String = s"r-${c.id}"

  /** `base` is the rendered offline style, or None without the basemap (then
    * a background under the packs). Packs in cell order.
    */
  def compose(base: Option[String], packs: Seq[Pack], credit: String): String =
    val st = base.map(ujson.read(_)).getOrElse(ujson.Obj(
      "version" -> 8, "name" -> "ashidori regions", "sources" -> ujson.Obj(),
      "layers" -> ujson.Arr(ujson.Obj("id" -> "background", "type" -> "background",
        "paint" -> ujson.Obj("background-color" -> "#cccccc")))))
    if packs.isEmpty then return ujson.write(st)
    val sorted = packs.sortBy(_.cell)
    val sources = st.obj.getOrElseUpdate("sources", ujson.Obj()).obj
    val shapes = scala.collection.mutable.ArrayBuffer.empty[ujson.Value]
    val labels = scala.collection.mutable.ArrayBuffer.empty[ujson.Value]
    for p <- sorted do
      val id = sourceId(p.cell)
      val b = p.cell.bounds
      sources(id) = ujson.Obj("type" -> "vector", "url" -> s"pmtiles://file://${p.archive}",
        "minzoom" -> RegionCatalog.MinZoom, "maxzoom" -> RegionCatalog.MaxZoom,
        "bounds" -> ujson.Arr(b.west, b.south, b.east, b.north), "attribution" -> credit)
      val l = ujson.read(p.layers)
      def copy(v: ujson.Value): ujson.Value =
        val o = ujson.copy(v)
        o("id") = s"$id-${v("id").str}"
        o("source") = id
        o
      shapes ++= l("base").arr.map(copy)
      labels ++= l("labels").arr.map(copy)
    val layers = st("layers").arr
    // The base map's shapes, then the packs' shapes, then every label.
    val firstLabel = layers.indexWhere(_.obj.get("type").exists(_.str == "symbol")) match
      case -1 => layers.size
      case i => i
    st("layers") = ujson.Arr.from(layers.take(firstLabel) ++ shapes ++ layers.drop(firstLabel) ++ labels)
    val fonts = sorted.map(_.styleDir).max
    st("glyphs") = s"file://$fonts/fonts/{fontstack}/{range}.pbf"
    if !st.obj.contains("sprite") then st("sprite") = s"file://$fonts/sprites/light"
    ujson.write(st)

/** Downloads the planned packs one at a time in cell order and places them
  * (design 6.7, 6.9): for each, its style bundle first (verified,
  * extracted, renamed into place), then the pack (resumed from its partial
  * file with Range, verified by size and sha256 and by its header, renamed
  * into place), and only then the state says it is placed. Every byte
  * written goes through the storage budget.
  */
object RegionRun:
  trait Env:
    def http: RangeHttp
    /** Where a file of the catalog `version` is downloaded from (the
      * version a pack was planned under, not the catalog now: a download
      * planned before an app update resumes from its own release).
      */
    def url(version: String, file: String): String
    def store: RegionStore
    def budget: StorageBudget
    def cancelled(): Boolean
    def now(): Long
    /** Bytes of `cell`'s pack so far (None: between packs). */
    def progress(cell: Option[Cell], done: Long): Unit = ()
    /** Applies `f` to the state as it is now (the user may add or delete
      * regions meanwhile), stores it and returns it.
      */
    def update(f: RegionState => RegionState): RegionState
    def log(s: String): Unit = ()

  enum Result:
    /** Nothing left to download (failed packs wait for the user). */
    case Done
    /** The budget does not let it write now: it waits (partial files stay). */
    case NoRoom
    case Cancelled

  /** The budget stopped a write. */
  final class NoRoom extends IOException("no room above the logging reserve")

  val MismatchReason = "照合に失敗"
  val HeaderReason = "データが不正"

  def run(env: Env): Result =
    try
      var next = pending(env.update(identity))
      while next.nonEmpty do
        if env.cancelled() then return Result.Cancelled
        val (cell, t) = next.get
        step(env, cell, t)
        env.progress(None, 0L)
        next = pending(env.update(identity))
      Result.Done
    catch
      case _: NoRoom => Result.NoRoom
      case _: ResumableDownload.Cancelled => Result.Cancelled

  /** The first planned pack, in cell order, whose last attempt did not fail. */
  def pending(s: RegionState): Option[(Cell, Target)] =
    s.queue.find((c, _) => s.packs(c).failure.isEmpty)

  private def stillWanted(s: RegionState, c: Cell, t: Target): Boolean =
    s.packs.get(c).exists(_.target.contains(t))

  private def fail(env: Env, c: Cell, t: Target, reason: String): Unit =
    env.log(s"regions: $c ${t.version} failed: $reason")
    env.update(s => if stillWanted(s, c, t) then s.copy(packs = s.packs.updated(c, s.packs(c).copy(failure = Some(reason)))) else s)

  private def step(env: Env, c: Cell, t: Target): Unit =
    val store = env.store
    // The bundle first: a pack is never placed before the style it is drawn with.
    if !store.stylePresent(t.style) then
      fetch(env, t.version, t.bundle, None) match
        case Left(reason) => return fail(env, c, t, reason)
        case Right(zip) =>
          val size = BasemapStore.extractedSize(zip)
          val r = env.budget.reserve(size).getOrElse(throw NoRoom())
          try store.placeStyle(zip, t.style, size + 1, n => r.wrote(n))
          catch
            // The budget stopped it: the verified bundle stays for the next run.
            case _: BasemapStore.NoRoom => throw NoRoom()
            case e: IOException =>
              zip.delete()
              return fail(env, c, t, HeaderReason)
          finally r.release()
          zip.delete()
          env.log(s"regions: style ${t.style} placed")
    if !stillWanted(env.update(identity), c, t) then
      store.part(t.file.file).delete()
      return
    // Already in place (only its bundle was missing): recorded, not downloaded again.
    if store.packFile(c, t.version).isFile then
      record(env, c, t)
      return
    fetch(env, t.version, t.file, Some(c)) match
      case Left(reason) => fail(env, c, t, reason)
      case Right(part) =>
        PmtilesHeader.read(part).flatMap(PmtilesHeader.check(_, c, RegionCatalog.MinZoom, RegionCatalog.MaxZoom)) match
          case Left(e) =>
            part.delete()
            fail(env, c, t, s"$HeaderReason（$e）")
          case Right(()) =>
            if env.cancelled() then throw ResumableDownload.Cancelled()
            // Deleted meanwhile: nothing to place.
            if !stillWanted(env.update(identity), c, t) then part.delete()
            else
              store.placePack(part, c, t.version, t.style)
              record(env, c, t)
              env.log(s"regions: $c ${t.version} placed")

  /** The state says the pack is placed, last of all. */
  private def record(env: Env, c: Cell, t: Target): Unit =
    val at = env.now()
    env.update { s =>
      s.packs.get(c) match
        case Some(p) if p.target.contains(t) =>
          s.copy(packs = s.packs.updated(c, PackState(Some(Installed(t.version, t.style, t.file.bytes, at)), None)))
        case _ => s
    }

  /** Brings `f` to `.download/<file>.part`, under a reservation of what is
    * left, and checks its size and sha256 (a wrong file is deleted: Left).
    */
  private def fetch(env: Env, version: String, f: RegionFile, cell: Option[Cell]): Either[String, File] =
    val part = env.store.part(f.file)
    part.getParentFile.mkdirs()
    val have = if part.isFile then part.length else 0L
    if have < f.bytes then
      val r = env.budget.reserveFile(f.bytes, have).getOrElse(throw NoRoom())
      try
        // Every write reports the file's size, also after a restart from the
        // start (HTTP 200 to a Range request, a discarded partial file).
        ResumableDownload.run(env.http, env.url(version, f.file), part, f.bytes, () => env.cancelled(), n => {
          if !r.at(n) then throw NoRoom()
          cell.foreach(c => env.progress(Some(c), n))
        })
      finally r.release()
    if part.isFile && part.length == f.bytes && Sha256.file(part) == f.sha256 then Right(part)
    else
      part.delete()
      Left(MismatchReason)


/** The regions' state in memory and on disk (design 6.7), kept apart from
  * everything else in the process: no method throws. A failure to read
  * the files disables the regions ([[broken]]); a failure to write the
  * state keeps the state as it was ([[update]] answers Left), so what is
  * in memory is always what is on disk. Changes are serialized here.
  *
  * The state is reconciled with the files once ([[ensureLoaded]]). A
  * missing `state.json` with packs or bundles on disk is recovered as an
  * unreadable one (rebuilt from the files, nothing deleted); without any
  * it is a fresh start. Files go to the trash and are deleted only when
  * neither the state (regions and planned versions) nor a map
  * ([[RegionUse]]) needs them at that moment.
  */
final class RegionBook(val store: RegionStore, catalog: () => Option[RegionCatalog], now: () => Long,
                       log: String => Unit = _ => (), val use: RegionUse = RegionUse(), loadWaitMs: Long = 10_000):
  private val lock = new Object
  @volatile private var current = RegionState.empty
  @volatile private var loaded = false
  /** Why the regions are off (their files cannot be read); None: usable. */
  @volatile var broken: Option[String] = None
  /** The last write of the state failed (changes are refused until one succeeds). */
  @volatile var writeError: Option[String] = None

  def state: RegionState =
    ensureLoaded()
    current

  /** Still reading the files (another thread loads them, longer than a
    * reader waits): the state is empty meanwhile and changes are refused.
    */
  def loading: Boolean = !loaded

  /** The state if it is loaded; never waits (for a UI thread). */
  def peek: Option[RegionState] = if loaded then Some(current) else None

  private val loadLock = java.util.concurrent.locks.ReentrantLock()

  /** Loads the state once. A reader while another thread loads waits for
    * it (at most `loadWaitMs`); `loaded` is published only when the state
    * is complete.
    */
  def ensureLoaded(): Unit =
    if !loaded && loadLock.tryLock(loadWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS) then
      try if !loaded then lock.synchronized(load())
      finally loadLock.unlock()

  private def load(): Unit =
      try
        val scan = store.scan()
        val stored = store.readState() match
          case None =>
            if scan.packs.nonEmpty || scan.styles.nonEmpty then
              log("regions: state.json missing with files on disk; rebuilt from the files")
              None
            else Some(RegionState.empty)
          case Some(None) =>
            log("regions: state.json unreadable; rebuilt from the files")
            None
          case Some(Some(s)) => Some(s)
        val r = RegionReconcile(stored, scan, catalog(), now())
        val next = r.state.copy(trash = (r.state.trash ++ r.delete).distinct).withoutNeededTrash
        if next != RegionState.empty || store.root.exists then
          try
            store.writeState(next)
            writeError = None
          catch case e: Throwable =>
            writeError = Some(message(e))
            log(s"regions: state not written at start: ${message(e)}")
        // What is on disk is what was stored: the reconciled state only if written.
        current = if writeError.isEmpty then next else stored.getOrElse(r.state.copy(trash = Seq.empty))
        if writeError.isEmpty then collectLocked()
      catch case e: Throwable =>
        broken = Some(message(e))
        current = RegionState.empty
        log(s"regions: off: ${message(e)}")
      finally loaded = true

  /** Applies `f` to the state and writes it; only then is it the state.
    * Left (the state unchanged) when the regions are off or the write failed.
    */
  def update(f: RegionState => RegionState): Either[String, RegionState] =
    ensureLoaded()
    // Still loading in another thread: refused at once (not waiting on its lock).
    if !loaded then return Left("読み込み中")
    lock.synchronized {
      broken match
        case Some(b) => Left(b)
        case None =>
          try
            val next = f(current).withoutNeededTrash
            if next != current then
              store.writeState(next)
              writeError = None
              current = next
            Right(current)
          catch case e: Throwable =>
            writeError = Some(message(e))
            log(s"regions: change not written: ${message(e)}")
            Left(message(e))
    }

  /** After a change that may leave files unused: they go to the trash and
    * are deleted now if nothing needs them. Partial downloads and
    * placements in progress are left to the start.
    */
  def tidy(): Unit =
    ensureLoaded()
    if loaded then lock.synchronized {
      try
        if loaded && broken.isEmpty then
          val extra = RegionReconcile.deletable(current, store.scan())
            .filterNot(d => d.startsWith(RegionStore.TmpPrefix) || d.startsWith(RegionStore.DownloadDir + "/"))
            .filterNot(current.trash.contains)
          if extra.nonEmpty then
            val next = current.copy(trash = current.trash ++ extra).withoutNeededTrash
            store.writeState(next)
            current = next
          collectLocked()
      catch case e: Throwable => log(s"regions: tidy failed: ${message(e)}")
    }

  /** Deletes what is in the trash and needed by neither the state nor a map now. */
  def collect(): Unit = if loaded then lock.synchronized {
    try collectLocked() catch case e: Throwable => log(s"regions: delete failed: ${message(e)}")
  }

  private def collectLocked(): Unit =
    if broken.isEmpty then
      // Needed again (re-added, planned): kept, and out of the trash.
      val (needed, rest) = current.trash.partition(current.needs)
      val (gone, held) = rest.partition(d => !use.inUse(d))
      if needed.nonEmpty || gone.nonEmpty then
        gone.foreach(store.delete)
        val next = current.copy(trash = held)
        try
          store.writeState(next)
          current = next
        catch case e: Throwable =>
          writeError = Some(message(e))
          log(s"regions: trash not written: ${message(e)}")
        log(s"regions: deleted ${gone.mkString(", ")}${if held.nonEmpty then s"; in use: ${held.mkString(", ")}" else ""}" +
          s"${if needed.nonEmpty then s"; needed again: ${needed.mkString(", ")}" else ""}")

  private def message(e: Throwable): String = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)


/** Which placed packs a map draws (design 6.10): all of them up to `max`;
  * beyond that, those that intersect the map's view widened by half of it
  * on every side, nearest the view's centre first, so that a pack in view
  * is never left out for another one.
  */
object RegionPick:
  def apply[A](placed: Seq[(Cell, A)], view: Option[Bounds], max: Int): Seq[(Cell, A)] =
    if placed.size <= max then placed
    else view match
      case None => placed.take(max)
      case Some(v) =>
        val w = (v.east - v.west) / 2
        val h = (v.north - v.south) / 2
        val wide = Bounds(v.west - w, v.south - h, v.east + w, v.north + h)
        val (cx, cy) = ((v.west + v.east) / 2, (v.south + v.north) / 2)
        def dist(c: Cell) =
          val b = c.bounds
          val dx = math.max(0.0, math.max(b.west - cx, cx - b.east))
          val dy = math.max(0.0, math.max(b.south - cy, cy - b.north))
          dx * dx + dy * dy
        placed.filter((c, _) => c.bounds.intersects(wide)).sortBy((c, _) => dist(c)).take(max).sortBy(_._1)
