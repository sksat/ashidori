package ashidori.core

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest

class RegionPacksSuite extends munit.FunSuite:
  private def tmpDir(): File = Files.createTempDirectory("regions").toFile
  private def sha(b: Array[Byte]) = MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  private val tokyo = Cell(227, 100)
  private val osaka = Cell(224, 101)
  private val ishigaki = Cell(216, 110)

  /** A PMTiles v3 header (and some bytes) for `cell`, with bounds shrunk inside it. */
  private def pack(cell: Cell, minZoom: Int = 8, maxZoom: Int = 14, bounds: Option[Bounds] = None, size: Int = 4000): Array[Byte] =
    val b = bounds.getOrElse { val c = cell.bounds; Bounds(c.west + 1e-7, c.south + 1e-7, c.east - 1e-7, c.north - 1e-7) }
    val buf = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put("PMTiles".getBytes(UTF_8)).put(3.toByte)
    buf.put(100, minZoom.toByte).put(101, maxZoom.toByte)
    buf.putInt(102, math.round(b.west * 1e7).toInt).putInt(106, math.round(b.south * 1e7).toInt)
      .putInt(110, math.round(b.east * 1e7).toInt).putInt(114, math.round(b.north * 1e7).toInt)
    for i <- 127 until size do buf.put(i, (i * 31 + cell.x).toByte)
    buf.array()

  private val layers = """{"format":"ashidori regions style v1","style":"s1","base":[{"id":"earth","type":"fill","source":"pack","source-layer":"earth"},{"id":"roads","type":"line","source":"pack","source-layer":"roads"}],"labels":[{"id":"places","type":"symbol","source":"pack","source-layer":"places","layout":{"text-font":["Noto Sans Regular"]}}]}"""

  private def bundle(): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val z = java.util.zip.ZipOutputStream(out)
    for (n, d) <- Seq("layers.json" -> layers, "manifest.json" -> "{}", "sprites/light.json" -> "{}", "fonts/Noto Sans Regular/0-255.pbf" -> "x") do
      z.putNextEntry(java.util.zip.ZipEntry(n))
      z.write(d.getBytes(UTF_8))
      z.closeEntry()
    z.close()
    out.toByteArray

  /** Files of a catalog: name -> bytes. */
  private final case class Published(catalog: RegionCatalog, json: String, files: Map[String, Array[Byte]])

  private def publish(version: String, cells: Seq[Cell], style: String = "s1"): Published =
    val b = bundle()
    val bundleName = RegionCatalog.styleName(style)
    val packs = cells.map(c => RegionCatalog.packName(c, version) -> pack(c, size = 4000 + c.x))
    val json = ujson.write(ujson.Obj(
      "format" -> RegionCatalog.Format, "version" -> version, "build" -> version, "minzoom" -> 8, "maxzoom" -> 14,
      "styles" -> ujson.Arr(ujson.Obj("style" -> style, "file" -> bundleName, "bytes" -> b.length, "sha256" -> sha(b))),
      "cells" -> ujson.Arr(cells.zip(packs).map((c, p) => ujson.Obj("cell" -> c.id, "style" -> style,
        "map" -> ujson.Obj("file" -> p._1, "bytes" -> p._2.length, "sha256" -> sha(p._2)), "venues" -> ujson.Null))*)))
    Published(RegionCatalog.parse(json).toOption.get, json, (packs :+ (bundleName -> b)).toMap)

  // ---- cells ----

  test("cells: the id, the cell of a point, its bounds, and the order by x then y") {
    assertEquals(Cell.at(36.08, 140.11), tokyo) // Tsukuba is in Tokyo's cell
    assertEquals(Cell.at(24.34, 124.16), ishigaki)
    assertEquals(Cell.parse("8-227-100"), Some(tokyo))
    assertEquals(Cell.parse("8-256-1"), None)
    assertEquals(Cell.parse("8-01-1"), None)
    assertEquals(Cell.parse("9-1-1"), None)
    val b = tokyo.bounds
    assertEqualsDouble(b.west, 139.21875, 1e-9)
    assertEqualsDouble(b.east, 140.625, 1e-9)
    assertEqualsDouble(b.north, 36.5978891, 1e-6)
    assertEquals(Seq(osaka, tokyo, Cell(224, 100), ishigaki).sorted, Seq(ishigaki, Cell(224, 100), osaka, tokyo))
  }

  test("cells covering a box: an edge on a cell border does not take the next cell; the 180° meridian cuts") {
    val b = tokyo.bounds
    assertEquals(Cell.covering(b), Seq(tokyo))
    assertEquals(Cell.covering(Bounds(b.west + 0.1, b.south + 0.1, b.east + 0.1, b.north - 0.1)), Seq(tokyo, Cell(228, 100)))
    assertEquals(Cell.covering(Bounds(179.9, 0, 181, 0.5)).map(_.x).distinct, Seq(255))
    assertEquals(Cell.at(85.1, 179.99), Cell(255, 0))
    assertEquals(Cell.covering(Bounds(1, 1, 1, 2)), Seq.empty)
  }

  // ---- catalog and pin ----

  test("catalog: parsed with every file pinned; wrong names, sizes, styles, repeated keys and cells are refused") {
    val p = publish("20261117", Seq(tokyo, ishigaki))
    val c = p.catalog
    assertEquals(c.version, "20261117")
    assertEquals(c.ordered.map(_.cell), Seq(ishigaki, tokyo))
    assertEquals(c.cells(tokyo).map.file, "ashidori-region-8-227-100-20261117.pmtiles")
    def bad(f: String => String) = assert(RegionCatalog.parse(f(p.json)).isLeft, f(p.json).take(200))
    bad(_.replace("\"minzoom\":8", "\"minzoom\":9"))
    bad(_.replace("ashidori-region-8-227-100-20261117", "ashidori-region-8-227-101-20261117"))
    bad(_.replace("\"style\":\"s1\",\"map\"", "\"style\":\"s2\",\"map\""))
    bad(_.replace("8-216-110", "8-227-100"))
    bad(_.replace("{\"format\"", "{\"version\":\"x\",\"format\""))
    bad(_.replace("../", "").replace("\"version\":\"20261117\"", "\"version\":\"../x\""))
    bad(j => j.replace(p.catalog.cells(tokyo).map.sha256, "0" * 63))
  }

  test("pin: only the exact catalog bytes") {
    val p = publish("20261117", Seq(tokyo))
    val raw = p.json.getBytes(UTF_8)
    val pinJson = ujson.write(ujson.Obj("format" -> RegionPin.Format, "version" -> "20261117",
      "file" -> "ashidori-regions-20261117.json", "bytes" -> raw.length, "sha256" -> sha(raw)))
    val pin = RegionPin.parse(pinJson).toOption.get
    assert(pin.matches(raw))
    assert(!pin.matches(raw.updated(10, 'x'.toByte)))
    assertEquals(pin.githubUrl, "https://github.com/sksat/ashidori/releases/download/regions-20261117/ashidori-regions-20261117.json")
    assert(RegionPin.parse(pinJson.replace("ashidori-regions-20261117.json", "a.json")).isLeft)
  }

  test("PMTiles header: zooms and bounds inside the cell") {
    val ok = PmtilesHeader.parse(pack(tokyo)).toOption.get
    assertEquals(PmtilesHeader.check(ok, tokyo, 8, 14), Right(()))
    assert(PmtilesHeader.check(ok, osaka, 8, 14).isLeft)
    assert(PmtilesHeader.check(PmtilesHeader.parse(pack(tokyo, maxZoom = 15)).toOption.get, tokyo, 8, 14).isLeft)
    val wide = tokyo.bounds.copy(east = tokyo.bounds.east + 0.01)
    assert(PmtilesHeader.check(PmtilesHeader.parse(pack(tokyo, bounds = Some(wide))).toOption.get, tokyo, 8, 14).isLeft)
    assert(PmtilesHeader.parse(Array.fill[Byte](127)(0)).isLeft)
  }

  // ---- state ----

  test("state: written and read back; a repeated key or a bad path is unreadable") {
    val p = publish("20261117", Seq(tokyo, ishigaki))
    val s = RegionState.empty.add(Region("r1", "関東", Seq(tokyo), 5L), p.catalog)
      .copy(trash = Seq("packs/8-227-100/20261010"))
    assertEquals(RegionState.decode(RegionState.encode(s)), Some(s))
    val j = RegionState.encode(s)
    assertEquals(RegionState.decode(j.replace("{\"format\"", "{\"regions\":[],\"format\"")), None)
    assertEquals(RegionState.decode(j.replace("packs/8-227-100/20261010", "../x")), None)
    assertEquals(RegionState.decode("{"), None)
  }

  test("regions share packs; a pack is planned once, removing a region drops only its own packs") {
    val c = publish("20261117", Seq(tokyo, osaka, ishigaki)).catalog
    val s1 = RegionState.empty.add(Region("r1", "関東", Seq(tokyo), 0), c)
    val s2 = s1.add(Region("r2", "本州", Seq(tokyo, osaka, Cell(0, 0)), 0), c)
    assertEquals(s2.queue.map(_._1), Seq(osaka, tokyo))
    assertEquals(s2.own("r1"), Set.empty[Cell])
    assertEquals(s2.own("r2"), Set(osaka, Cell(0, 0)))
    assertEquals(s2.remove("r2").packs.keySet, Set(tokyo))
    assertEquals(s2.remove("r1").packs.keySet, Set(tokyo, osaka))
    assertEquals(s2.nextId, "r3")
  }

  test("an update plans the new version for older packs only; 新しい版 shows until then") {
    val old = publish("20261010", Seq(tokyo, ishigaki)).catalog
    val now = publish("20261117", Seq(tokyo, ishigaki)).catalog
    val placed = RegionState(Seq(Region("r1", "a", Seq(tokyo, ishigaki), 0)), Map(
      tokyo -> PackState(Some(Installed("20261010", "s1", 1, 1)), None),
      ishigaki -> PackState(Some(Installed("20261117", "s1", 1, 1)), None)))
    val r = placed.regions.head
    assertEquals(RegionStatus.of(r, placed, Set("s1"), Some(now), Map.empty), RegionStatus.Ready(newer = true))
    assertEquals(RegionStatus.of(r, placed, Set("s1"), Some(old), Map.empty), RegionStatus.Ready(newer = false))
    val up = placed.update(Set(tokyo, ishigaki), now)
    assertEquals(up.queue.map(_._1), Seq(tokyo))
    assertEquals(up.packs(tokyo).installed.map(_.version), Some("20261010"))
    assertEquals(RegionStatus.of(r, up, Set("s1"), Some(now), Map.empty), RegionStatus.Updating(0, now.cells(tokyo).map.bytes))
  }

  test("the estimate: packs not on the device, the bundle if missing, cells outside the catalog apart") {
    val c = publish("20261117", Seq(tokyo, osaka)).catalog
    val s = RegionState.empty.add(Region("r1", "a", Seq(tokyo), 0), c)
    val e = RegionEstimate(Seq(tokyo, osaka, Cell(0, 0)), s, c, Set.empty)
    assertEquals(e.fetch, Seq(osaka))
    assertEquals(e.unknown, Seq(Cell(0, 0)))
    // The bundle is already planned with Tokyo.
    assertEquals(e.styles, Seq.empty)
    assertEquals(e.bytes, c.cells(osaka).map.bytes)
    val e2 = RegionEstimate(Seq(osaka), RegionState.empty, c, Set.empty)
    assertEquals(e2.bytes, c.cells(osaka).map.bytes + c.styles("s1").bytes)
    assertEquals(RegionEstimate(Seq(osaka), RegionState.empty, c, Set("s1")).bytes, c.cells(osaka).map.bytes)
  }

  // ---- store, run and reconcile ----

  private final class Http(files: Map[String, Array[Byte]], cut: Int = Int.MaxValue) extends RangeHttp:
    val requests = scala.collection.mutable.ArrayBuffer.empty[(String, Long)]
    var fail = false
    def get(url: String, from: Long): RangeHttp.Response =
      requests += url -> from
      if fail then throw java.io.IOException("offline")
      val data = files.getOrElse(url, Array.emptyByteArray)
      val (st, bytes, range) =
        if !files.contains(url) then (404, Array.emptyByteArray, None)
        else if from > 0 then (206, data.drop(from.toInt), Some(s"bytes $from-${data.length - 1}/${data.length}"))
        else (200, data, None)
      new RangeHttp.Response:
        def code = st
        def header(name: String) = if name == "Content-Range" then range else None
        def body: InputStream = ByteArrayInputStream(bytes.take(cut))
        def close() = ()

  private class Env(val store: RegionStore, val http: RangeHttp, val budget: StorageBudget) extends RegionRun.Env:
    var state: RegionState = store.readState().flatten.getOrElse(RegionState.empty)
    var stopAfter: Option[Long] = None
    var progressed = 0L
    val versions = scala.collection.mutable.ArrayBuffer.empty[String]
    def url(version: String, file: String) =
      versions += version
      file
    def cancelled() = stopAfter.exists(progressed >= _)
    def now() = 42L
    override def progress(cell: Option[Cell], done: Long): Unit = if cell.nonEmpty then progressed = done
    def update(f: RegionState => RegionState): RegionState = synchronized {
      state = f(state)
      store.writeState(state)
      state
    }

  private def budget(free: Long = Long.MaxValue / 4, reserve: Long = 0L) = StorageBudget(() => free, () => reserve)

  test("download: the bundle before the packs, packs in cell order, each verified and placed; the state says so last") {
    val p = publish("20261117", Seq(tokyo, ishigaki))
    val store = RegionStore(tmpDir())
    val http = Http(p.files)
    val env = Env(store, http, budget())
    env.update(_.add(Region("r1", "a", Seq(tokyo, ishigaki), 0), p.catalog))
    assertEquals(RegionRun.run(env), RegionRun.Result.Done)
    assertEquals(http.requests.map(_._1).toSeq, Seq(RegionCatalog.styleName("s1"),
      RegionCatalog.packName(ishigaki, "20261117"), RegionCatalog.packName(tokyo, "20261117")))
    assert(store.stylePresent("s1"))
    assert(store.packFile(tokyo, "20261117").isFile)
    assertEquals(env.state.packs(tokyo).installed, Some(Installed("20261117", "s1", p.catalog.cells(tokyo).map.bytes, 42L)))
    assertEquals(env.state.queue, Seq.empty)
    val scan = store.scan()
    assertEquals(scan.packs(tokyo), Map("20261117" -> Some("s1")))
    assertEquals(RegionReconcile.deletable(env.state, scan), Seq.empty)
  }

  test("download: stopped part-way it resumes with Range; a wrong byte is caught and the pack fails without retrying") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val name = RegionCatalog.packName(tokyo, "20261117")
    val http = Http(p.files)
    val env = Env(store, http, budget())
    env.update(_.add(Region("r1", "a", Seq(tokyo), 0), p.catalog))
    env.stopAfter = Some(1000)
    val cut = Http(p.files, cut = 1500)
    val env1 = Env(store, cut, budget())
    env1.stopAfter = Some(1)
    assertEquals(RegionRun.run(env1), RegionRun.Result.Cancelled)
    assert(store.part(name).length > 0)
    val have = store.part(name).length
    val env2 = Env(store, http, budget())
    assertEquals(RegionRun.run(env2), RegionRun.Result.Done)
    assert(http.requests.contains(name -> have), http.requests.toString)
    assert(store.packFile(tokyo, "20261117").isFile)

    // A corrupt byte (same size): deleted, the pack fails, nothing placed, no retry.
    val p2 = publish("20261124", Seq(osaka))
    val bad = p2.files.updated(RegionCatalog.packName(osaka, "20261124"),
      p2.files(RegionCatalog.packName(osaka, "20261124")).updated(2000, 0x55.toByte))
    val store2 = RegionStore(tmpDir())
    val env3 = Env(store2, Http(bad), budget())
    env3.update(_.add(Region("r1", "a", Seq(osaka), 0), p2.catalog))
    assertEquals(RegionRun.run(env3), RegionRun.Result.Done)
    assertEquals(env3.state.packs(osaka).failure, Some(RegionRun.MismatchReason))
    assert(!store2.part(RegionCatalog.packName(osaka, "20261124")).exists)
    assert(!File(store2.root, "packs").exists)
    assertEquals(RegionStatus.of(env3.state.regions.head, env3.state, Set("s1"), None, Map.empty),
      RegionStatus.Failed(RegionRun.MismatchReason))
  }

  test("download: an unusable pack planned again at its own version fetches only its bundle") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    store.root.mkdirs()
    val f = File(store.root, "x")
    Files.write(f.toPath, pack(tokyo))
    store.placePack(f, tokyo, "20261117", "s1")
    val http = Http(p.files)
    val env = Env(store, http, budget())
    env.update(_ => RegionState(Seq(Region("r1", "a", Seq(tokyo), 0)),
      Map(tokyo -> PackState(Some(Installed("20261117", "s1", 9, 1)), Some(RegionCatalog.target(p.catalog, p.catalog.cells(tokyo)))))))
    assertEquals(RegionRun.run(env), RegionRun.Result.Done)
    assertEquals(http.requests.map(_._1).toSeq, Seq(RegionCatalog.styleName("s1")))
    assert(store.stylePresent("s1"))
    assertEquals(env.state.packs(tokyo).target, None)
    assertEquals(env.state.packs(tokyo).installed.map(_.at), Some(42L))
  }

  test("download: a pack whose header leaves its cell is refused after its sha256 matched") {
    val p = publish("20261117", Seq(tokyo))
    // The catalog pins Osaka's bytes under Tokyo's name.
    val fake = pack(osaka)
    val json = p.json.replace(p.catalog.cells(tokyo).map.sha256, sha(fake))
      .replace(s"\"bytes\":${p.catalog.cells(tokyo).map.bytes}", s"\"bytes\":${fake.length}")
    val c = RegionCatalog.parse(json).toOption.get
    val store = RegionStore(tmpDir())
    val env = Env(store, Http(p.files.updated(RegionCatalog.packName(tokyo, "20261117"), fake)), budget())
    env.update(_.add(Region("r1", "a", Seq(tokyo), 0), c))
    assertEquals(RegionRun.run(env), RegionRun.Result.Done)
    assert(env.state.packs(tokyo).failure.exists(_.startsWith(RegionRun.HeaderReason)))
    assert(!store.packFile(tokyo, "20261117").exists)
  }

  test("download: the budget refuses a pack that would cut into the logging reserve, and stops a write that does") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    // Room for the bundle, one byte short for the pack.
    val bytes = p.files(RegionCatalog.packName(tokyo, "20261117")).length.toLong
    val env = Env(store, Http(p.files), budget(free = bytes + 999, reserve = 1000))
    env.update(_.add(Region("r1", "a", Seq(tokyo), 0), p.catalog))
    assertEquals(RegionRun.run(env), RegionRun.Result.NoRoom)
    // Free space shrinking while it writes (the logger writes too): it stops, the partial file stays.
    var free = 1_000_000L
    val b = StorageBudget(() => free, () => 970_000L)
    val big = Array.tabulate[Byte](20_000)(_.toByte)
    val store2 = RegionStore(tmpDir())
    val http = new RangeHttp:
      def get(url: String, from: Long) = new RangeHttp.Response:
        def code = 200
        def header(n: String) = None
        def body: InputStream = new java.io.FilterInputStream(ByteArrayInputStream(big)):
          override def read(buf: Array[Byte], off: Int, len: Int): Int =
            free -= 4000
            super.read(buf, off, math.min(len, 1000))
        def close() = ()
    val f = RegionFile("x.pmtiles", big.length, sha(big))
    val env2 = Env(store2, http, b)
    val t = Target("v", "s1", f, RegionFile("ashidori-regions-style-s1.zip", 1, "0" * 64))
    env2.update(_ => RegionState(Seq(Region("r1", "a", Seq(tokyo), 0)), Map(tokyo -> PackState(None, Some(t)))))
    Files.createDirectories(store2.styleRoot("s1").toPath)
    Files.write(File(store2.styleRoot("s1"), "layers.json").toPath, layers.getBytes(UTF_8))
    assertEquals(RegionRun.run(env2), RegionRun.Result.NoRoom)
    val left = store2.part("x.pmtiles").length
    assert(left > 0 && left < big.length, left.toString)
    assert(!b.busy)
  }

  test("budget: what is written leaves the reservation (no double count); one large write at a time") {
    var free = 10_000L
    val b = StorageBudget(() => free, () => 2_000L)
    assertEquals(b.reserve(8_001), None)
    val r = b.reserve(8_000).get
    assertEquals(b.reserve(1), None)
    // 3,000 written: F drops by 3,000 and the rest of the reservation by as much.
    free -= 3_000
    assert(r.wrote(3_000))
    assert(b.ok)
    // Someone else (the logger) takes 1 byte too many.
    free -= 1
    assert(!r.wrote(0))
    r.release()
    assert(!b.busy)
    assertEquals(b.available, 4_999L)
    assertEquals(StorageBudget.loggingReserve(LoggingMode.Balanced, None), StorageBudget.MinReserve)
    assertEquals(StorageBudget.loggingReserve(LoggingMode.Max, None), 210L * 1000 * 1000 * 24)
    assertEquals(StorageBudget.loggingReserve(LoggingMode.Eco, Some(100L * 1000 * 1000)), 2400L * 1000 * 1000)
    val h = 3600L * 1000
    assertEquals(StorageBudget.measuredRate(Seq(0L -> 0L, h -> 50L, 2 * h -> 100L), 2 * h), Some(50L))
    assertEquals(StorageBudget.measuredRate(Seq(0L -> 100L, h / 2 -> 200L), h / 2), None)
    assertEquals(StorageBudget.measuredRate(Seq(0L -> 100L, h -> 10L), h), Some(0L))
  }

  test("reconcile: stopped between the rename and the state, the pack counts as placed") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val s = RegionState.empty.add(Region("r1", "a", Seq(tokyo), 0), p.catalog)
    store.writeState(s)
    val f = File(store.root, "x")
    Files.write(f.toPath, pack(tokyo))
    store.placePack(f, tokyo, "20261117", "s1")
    Files.createDirectories(store.styleRoot("s1").toPath)
    Files.write(File(store.styleRoot("s1"), "layers.json").toPath, layers.getBytes(UTF_8))
    val r = RegionReconcile(store.readState().flatten, store.scan(), Some(p.catalog), 7L)
    assertEquals(r.state.packs(tokyo), PackState(Some(Installed("20261117", "s1", p.catalog.cells(tokyo).map.bytes, 7L)), None))
    assertEquals(r.delete, Seq.empty)
  }

  test("reconcile: a pack without its style bundle is unusable, never deleted, and drawable again once the bundle is back") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val f = File(store.root, "x")
    store.root.mkdirs()
    Files.write(f.toPath, pack(tokyo))
    store.placePack(f, tokyo, "20261117", "s1")
    val s = RegionState(Seq(Region("r1", "a", Seq(tokyo), 0)), Map(tokyo -> PackState(Some(Installed("20261117", "s1", 9, 1)), None)))
    val r = RegionReconcile(Some(s), store.scan(), Some(p.catalog), 7L)
    assertEquals(r.delete, Seq.empty)
    assertEquals(r.state, s)
    assertEquals(RegionStatus.of(s.regions.head, s, store.scan().styles, None, Map.empty), RegionStatus.Unusable)
    Files.createDirectories(store.styleRoot("s1").toPath)
    Files.write(File(store.styleRoot("s1"), "layers.json").toPath, layers.getBytes(UTF_8))
    assertEquals(RegionStatus.of(s.regions.head, s, store.scan().styles, None, Map.empty), RegionStatus.Ready(false))
  }

  test("reconcile: other versions, unreferenced cells and styles, stray parts and extractions are deletable; missing files are planned again") {
    val p = publish("20261117", Seq(tokyo, osaka))
    val store = RegionStore(tmpDir())
    def put(c: Cell, v: String) =
      val f = File(store.root, s"x-$v")
      store.root.mkdirs()
      Files.write(f.toPath, pack(c))
      store.placePack(f, c, v, "s1")
    put(tokyo, "20261010")
    put(tokyo, "20261117")
    put(osaka, "20261117")
    Files.createDirectories(File(store.root, "styles/s0/x").toPath)
    Files.write(File(store.root, "styles/s0/layers.json").toPath, Array[Byte]())
    Files.createDirectories(File(store.root, ".tmp-pack-8-1-1-v").toPath)
    Files.createDirectories(store.downloadDir.toPath)
    Files.write(store.part("stray.pmtiles").toPath, Array[Byte](1))
    Files.write(store.part(RegionCatalog.packName(Cell(1, 1), "20261117")).toPath, Array[Byte](1))
    val s = RegionState(Seq(Region("r1", "a", Seq(tokyo, Cell(1, 1)), 0)), Map(
      tokyo -> PackState(Some(Installed("20261117", "s1", 9, 1)), None),
      Cell(1, 1) -> PackState(Some(Installed("20261117", "s1", 9, 1)), None)))
    val r = RegionReconcile(Some(s), store.scan(), Some(p.catalog), 7L)
    assertEquals(r.delete, Seq(".download/ashidori-region-8-1-1-20261117.pmtiles.part", ".download/stray.pmtiles.part",
      ".tmp-pack-8-1-1-v", "packs/8-224-101/20261117", "packs/8-227-100/20261010", "styles/s0").sorted)
    // Cell 8-1-1's files are gone and the catalog does not have it: no target.
    assertEquals(r.state.packs(Cell(1, 1)), PackState(None, None))
    val gone = RegionState(Seq(Region("r1", "a", Seq(osaka), 0)), Map(osaka -> PackState(Some(Installed("20261010", "s1", 9, 1)), None)))
    val r2 = RegionReconcile(Some(gone), store.scan(), Some(p.catalog), 7L)
    assertEquals(r2.state.packs(osaka).target.map(_.version), Some("20261117"))
  }

  test("reconcile: an unreadable state is rebuilt from the files, one provisional region per cell, nothing deleted") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    store.root.mkdirs()
    val f = File(store.root, "x")
    Files.write(f.toPath, pack(tokyo))
    store.placePack(f, tokyo, "20261117", "s1")
    Files.write(store.stateFile.toPath, "{not json".getBytes(UTF_8))
    assertEquals(store.readState(), Some(None))
    val r = RegionReconcile(None, store.scan(), Some(p.catalog), 7L)
    assertEquals(r.delete, Seq.empty)
    assertEquals(r.state.regions.map(_.cells), Seq(Seq(tokyo)))
    assertEquals(r.state.packs(tokyo).installed.map(i => (i.version, i.style)), Some(("20261117", "s1")))
  }

  test("in use: a directory a map draws from is not deletable until released") {
    val u = RegionUse()
    val owner = new Object
    u.hold(owner, Set("packs/8-227-100/20261117", "styles/s1"))
    assert(u.inUse("packs/8-227-100/20261117"))
    assert(u.inUse("packs/8-227-100"))
    assert(u.inUse("styles/s1"))
    assert(!u.inUse("packs/8-227-100/20261010"))
    assert(u.release(owner))
    assert(!u.inUse("styles/s1"))
  }

  test("the composed style: pack shapes over the base shapes, every label last, local only, the bundle's fonts") {
    val base = ujson.write(ujson.Obj("version" -> 8, "sources" -> ujson.Obj(
      "japan" -> ujson.Obj("type" -> "vector", "url" -> "pmtiles://file:///b/japan.pmtiles")),
      "glyphs" -> "file:///b/fonts/{fontstack}/{range}.pbf", "sprite" -> "file:///b/sprites/light",
      "layers" -> ujson.Arr(
        ujson.Obj("id" -> "japan-earth", "type" -> "fill", "source" -> "japan"),
        ujson.Obj("id" -> "japan-places", "type" -> "symbol", "source" -> "japan"))))
    val packs = Seq(tokyo, ishigaki).map(c => RegionStyle.Pack(c, s"/r/packs/${c.id}/v/map.pmtiles", layers, "/r/styles/s1"))
    val st = ujson.read(RegionStyle.compose(Some(base), packs, "© OpenStreetMap"))
    assertEquals(st("layers").arr.map(_("id").str).toSeq, Seq("japan-earth",
      "r-8-216-110-earth", "r-8-216-110-roads", "r-8-227-100-earth", "r-8-227-100-roads",
      "japan-places", "r-8-216-110-places", "r-8-227-100-places"))
    assertEquals(st("sources")("r-8-227-100")("url").str, "pmtiles://file:///r/packs/8-227-100/v/map.pmtiles")
    assertEquals(st("sources")("r-8-227-100")("maxzoom").num, 14.0)
    assertEquals(st("glyphs").str, "file:///r/styles/s1/fonts/{fontstack}/{range}.pbf")
    assertEquals(OfflineBasemapStyle.remoteUrls(ujson.write(st)), Seq.empty)
    // Without the basemap: a background under the packs.
    val alone = ujson.read(RegionStyle.compose(None, packs.take(1), "c"))
    assertEquals(alone("layers").arr.head("type").str, "background")
    assertEquals(alone("sprite").str, "file:///r/styles/s1/sprites/light")
  }

  // ---- review 67 ----

  test("budget: writes run side by side, each counting the others' unwritten rest; all stop under the reserve") {
    var free = 10_000L
    val b = StorageBudget(() => free, () => 2_000L)
    val a = b.reserve(5_000).get
    val c = b.reserve(3_000).get
    // 10,000 - 8,000 outstanding - 2,000 reserve: nothing more fits.
    assertEquals(b.reserve(1), None)
    free -= 2_000
    assert(a.wrote(2_000))
    assertEquals(b.available, 0L)
    // The logger takes 500: both writes must stop.
    free -= 500
    assert(!a.wrote(0) && !c.wrote(0))
    a.release()
    c.release()
    assertEquals(b.available, 5_500L)
  }

  test("an extraction stops when the budget says so, and leaves nothing behind") {
    val dir = tmpDir()
    val zip = File(dir, "a.zip")
    Files.write(zip.toPath, bundle())
    val store = BasemapStore(File(dir, "basemap"), Seq("layers.json"))
    val pin = BasemapPin("v1", "ashidori-basemap-v1.zip", zip.length, sha(Files.readAllBytes(zip.toPath)))
    var calls = 0
    intercept[BasemapStore.NoRoom](store.install(zip, pin, _ => { calls += 1; calls < 2 }))
    assertEquals(Option(store.root.list()).map(_.toSeq).getOrElse(Seq.empty), Seq.empty[String])
    assert(store.install(zip, pin).isDirectory)
    assert(BasemapStore.extractedSize(zip) > 0)
  }

  /** A store whose writes of the state fail like a full disk. */
  private final class FullStore(root: File) extends RegionStore(root):
    var full = false
    override def writeState(s: RegionState): Unit =
      if full then throw java.io.IOException("write failed: ENOSPC (No space left on device)")
      super.writeState(s)

  test("state: a failed write (ENOSPC) keeps the state as it was, on disk and in memory; nothing throws") {
    val p = publish("20261117", Seq(tokyo, osaka))
    val store = FullStore(tmpDir())
    val book = RegionBook(store, () => Some(p.catalog), () => 7L)
    assert(book.update(_.add(Region("r1", "a", Seq(tokyo), 0), p.catalog)).isRight)
    val before = book.state
    store.full = true
    val r = book.update(_.add(Region("r2", "b", Seq(osaka), 0), p.catalog))
    assert(r.left.exists(_.contains("ENOSPC")), r.toString)
    assertEquals(book.state, before)
    assertEquals(store.readState(), Some(Some(before)))
    assert(book.writeError.nonEmpty)
    book.tidy()
    book.collect()
    store.full = false
    assert(book.update(_.add(Region("r2", "b", Seq(osaka), 0), p.catalog)).isRight)
    assertEquals(book.writeError, None)
  }

  test("state: unreadable files turn the regions off without throwing; a failed write at start keeps what was stored") {
    val broken = new RegionStore(tmpDir()):
      override def scan(): RegionScan = throw java.io.IOException("EIO")
    val b1 = RegionBook(broken, () => None, () => 7L)
    assertEquals(b1.state, RegionState.empty)
    assert(b1.broken.exists(_.contains("EIO")))
    assert(b1.update(identity).isLeft)
    b1.tidy()
    b1.collect()

    val p = publish("20261117", Seq(tokyo))
    val store = FullStore(tmpDir())
    val s = RegionState.empty.add(Region("r1", "a", Seq(tokyo), 0), p.catalog).copy(trash = Seq("styles/old"))
    store.writeState(s)
    Files.createDirectories(store.styleRoot("old").toPath)
    store.full = true
    val b2 = RegionBook(store, () => Some(p.catalog), () => 7L)
    assertEquals(b2.state, s)
    assert(b2.writeError.nonEmpty)
    // Nothing deleted while the state could not be written.
    assert(store.styleRoot("old").isDirectory)
  }

  test("trash: a pack deleted while drawn and added back before it is let go survives") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val book = RegionBook(store, () => Some(p.catalog), () => 7L)
    val env = new Env(store, Http(p.files), budget()):
      override def update(f: RegionState => RegionState): RegionState = book.update(f).toOption.get
    book.update(_.add(Region("r1", "a", Seq(tokyo), 0), p.catalog))
    assertEquals(RegionRun.run(env), RegionRun.Result.Done)
    val map = new Object
    val dirs = Set(RegionStore.packDir(tokyo, "20261117"), RegionStore.styleDir("s1"))
    book.use.hold(map, dirs)
    book.update(_.remove("r1"))
    book.tidy()
    assertEquals(book.state.trash.toSet, dirs)
    assert(store.packFile(tokyo, "20261117").isFile)
    // Added back while still drawn: planned, the file in place is recorded, not fetched again.
    book.update(_.add(Region("r2", "a again", Seq(tokyo), 0), p.catalog))
    assertEquals(book.state.trash, Seq.empty)
    val http = Http(p.files)
    val env2 = new Env(store, http, budget()):
      override def update(f: RegionState => RegionState): RegionState = book.update(f).toOption.get
    assertEquals(RegionRun.run(env2), RegionRun.Result.Done)
    assertEquals(http.requests.toSeq, Seq.empty)
    // Let go: the file stays.
    book.use.release(map)
    book.collect()
    book.tidy()
    assert(store.packFile(tokyo, "20261117").isFile)
    assert(store.stylePresent("s1"))
    assertEquals(book.state.packs(tokyo).installed.map(_.version), Some("20261117"))
  }

  test("state: a missing state.json with files on disk is rebuilt from them (nothing deleted); without files it is a fresh start") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    store.root.mkdirs()
    val f = File(store.root, "x")
    Files.write(f.toPath, pack(tokyo))
    store.placePack(f, tokyo, "20261117", "s1")
    Files.createDirectories(store.styleRoot("s1").toPath)
    Files.write(File(store.styleRoot("s1"), "layers.json").toPath, layers.getBytes(UTF_8))
    assert(!store.stateFile.exists)
    val book = RegionBook(store, () => Some(p.catalog), () => 7L)
    assertEquals(book.state.regions.map(_.cells), Seq(Seq(tokyo)))
    assertEquals(book.state.trash, Seq.empty)
    assertEquals(book.state.packs(tokyo).installed.map(_.bytes), Some(store.packFile(tokyo, "20261117").length))
    assert(store.packFile(tokyo, "20261117").isFile && store.stylePresent("s1"))

    val fresh = RegionStore(File(tmpDir(), "regions"))
    val b2 = RegionBook(fresh, () => None, () => 7L)
    assertEquals(b2.state, RegionState.empty)
    assert(!fresh.root.exists)
  }

  test("a download planned under an older catalog resumes from its own release after the catalog changed") {
    val old = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val name = RegionCatalog.packName(tokyo, "20261117")
    val env1 = Env(store, Http(old.files, cut = 1500), budget())
    env1.update(_.add(Region("r1", "a", Seq(tokyo), 0), old.catalog))
    env1.stopAfter = Some(1)
    assertEquals(RegionRun.run(env1), RegionRun.Result.Cancelled)
    // An app update brings a newer catalog; the planned pack keeps its version.
    val newer = publish("20261124", Seq(tokyo))
    assert(newer.catalog.version != old.catalog.version)
    val env2 = Env(store, Http(old.files), budget())
    assertEquals(RegionRun.run(env2), RegionRun.Result.Done)
    assertEquals(env2.versions.distinct.toSeq, Seq("20261117"))
    assert(store.packFile(tokyo, "20261117").isFile)
    assertEquals(store.part(name).exists, false)
  }

  test("status: an update in progress, stopped or failed is told apart from a region that is ready") {
    val now = publish("20261124", Seq(tokyo, osaka)).catalog
    val r = Region("r1", "a", Seq(tokyo, osaka), 0)
    val placed = RegionState(Seq(r), Map(
      tokyo -> PackState(Some(Installed("20261117", "s1", 1, 1)), None),
      osaka -> PackState(Some(Installed("20261117", "s1", 1, 1)), None)))
    assertEquals(RegionStatus.of(r, placed, Set("s1"), Some(now), Map.empty), RegionStatus.Ready(newer = true))
    val updating = placed.update(Set(tokyo), now)
    val total = now.cells(tokyo).map.bytes
    assertEquals(RegionStatus.of(r, updating, Set("s1"), Some(now), Map(tokyo -> 10L)), RegionStatus.Updating(10, total))
    val failed = updating.copy(packs = updating.packs.updated(tokyo, updating.packs(tokyo).copy(failure = Some(RegionRun.MismatchReason))))
    assertEquals(RegionStatus.of(r, failed, Set("s1"), Some(now), Map.empty), RegionStatus.UpdateFailed(RegionRun.MismatchReason))
    // A pack never placed counts first.
    val adding = failed.copy(packs = failed.packs.updated(osaka, PackState(None, Some(RegionCatalog.target(now, now.cells(osaka))))))
    assertEquals(RegionStatus.of(r, adding, Set("s1"), Some(now), Map.empty), RegionStatus.Downloading(0, now.cells(osaka).map.bytes))
  }

  test("packs drawn: all up to the limit; beyond it those around the view, never one in view left out") {
    val all = (0 until 20).map(i => Cell(200 + i, 100) -> i)
    assertEquals(RegionPick(all.take(16), None, 16), all.take(16))
    // A view over cells 218-219 (east of the first 16).
    val v = Bounds(Cell(218, 100).bounds.west + 0.1, 35.6, Cell(219, 100).bounds.east - 0.1, 36.0)
    val picked = RegionPick(all, Some(v), 16).map(_._1.x)
    // Only the packs around the view (the last cell is 219), in cell order.
    assertEquals(picked, Seq(217, 218, 219))
    // With room for two, the two in view.
    assertEquals(RegionPick(all, Some(v), 2).map(_._1.x), Seq(218, 219))
  }

  // ---- review 68 ----

  test("budget: a file cut back to the start must be admitted again; a writer that took the freed room stops it") {
    var disk = 600L
    var others = 0L
    val b = StorageBudget(() => 10_000L - disk - others, () => 8_000L)
    // Resumed at 600 of 1,000: 400 to write.
    val r1 = b.reserveFile(1_000, 600).get
    assert(r1.at(600))
    // The server answers 200 to the Range request: the file starts over (600 freed).
    disk = 0
    // Before the first write is reported, another writer takes what looks free.
    val r2 = b.reserveFile(1_600, 0).get
    // The restart needs 1,000 now: it does not fit next to the other one.
    assert(!r1.at(0))
    r1.release()
    r2.release()
    // Alone, the same restart is admitted and every write is checked.
    disk = 600
    val r3 = b.reserveFile(1_000, 600).get
    disk = 0
    assert(r3.at(0))
    disk = 500
    assert(r3.at(500))
    others = 1_600
    assert(!r3.at(600))
  }

  test("download: a server that ignores Range starts over; the restart is checked against the budget before it writes") {
    val p = publish("20261117", Seq(tokyo))
    val store = RegionStore(tmpDir())
    val name = RegionCatalog.packName(tokyo, "20261117")
    val packBytes = p.files(name)
    store.root.mkdirs()
    // The bundle is there; 3,000 bytes of the pack too.
    Files.createDirectories(store.styleRoot("s1").toPath)
    Files.write(File(store.styleRoot("s1"), "layers.json").toPath, layers.getBytes(UTF_8))
    Files.createDirectories(store.downloadDir.toPath)
    Files.write(store.part(name).toPath, packBytes.take(3_000))
    // A server without Range: 200 and the whole file.
    // The logger (or another app) writes 2,000 bytes while the request is made.
    var logged = 2_000L
    var extra = 0L
    val noRange = new RangeHttp:
      def get(url: String, from: Long) =
        extra = logged
        new RangeHttp.Response:
          def code = 200
          def header(n: String) = None
          def body: InputStream = ByteArrayInputStream(packBytes)
          def close() = ()
    def disk = Option(store.part(name)).filter(_.isFile).map(_.length).getOrElse(0L)
    // Room for the rest (about 1,200), not for the whole file again after the logger wrote.
    val budget = StorageBudget(() => 20_000L - disk - extra, () => 15_000L)
    val env = Env(store, noRange, budget)
    env.update(_.add(Region("r1", "a", Seq(tokyo), 0), p.catalog))
    assertEquals(RegionRun.run(env), RegionRun.Result.NoRoom)
    // It stopped at the restart, before it wrote into the reserve.
    assertEquals(disk, 0L)
    logged = 0L
    extra = 0L
    assertEquals(RegionRun.run(Env(store, noRange, budget)), RegionRun.Result.Done)
    assert(store.packFile(tokyo, "20261117").isFile)
  }

  test("scan: a directory that cannot be listed stops the start; nothing is changed or deleted") {
    val p = publish("20261117", Seq(tokyo))
    val root = tmpDir()
    val good = RegionStore(root)
    val f = File(root, "x")
    Files.write(f.toPath, pack(tokyo))
    good.placePack(f, tokyo, "20261117", "s1")
    val s = RegionState(Seq(Region("r1", "a", Seq(tokyo), 0)), Map(tokyo -> PackState(Some(Installed("20261117", "s1", 9, 1)), None)))
    good.writeState(s)
    val before = Files.readAllBytes(good.stateFile.toPath).toSeq
    // The listing of packs/ fails (null), as on an I/O error.
    val failing = new RegionStore(root):
      override protected def names(d: File): Array[String] =
        if d.getName == RegionStore.PacksDir then null else super.names(d)
    val book = RegionBook(failing, () => Some(p.catalog), () => 7L)
    assertEquals(book.state, RegionState.empty)
    assert(book.broken.exists(_.contains("cannot list")), book.broken.toString)
    assert(book.update(identity).isLeft)
    book.tidy()
    assertEquals(Files.readAllBytes(good.stateFile.toPath).toSeq, before)
    assert(good.packFile(tokyo, "20261117").isFile)
  }

  test("start: readers while another thread loads wait for the whole state; ids are taken from it") {
    val p = publish("20261117", Seq(tokyo, osaka))
    val root = tmpDir()
    RegionStore(root).writeState(RegionState.empty.add(Region("r1", "a", Seq(tokyo), 0), p.catalog))
    val entered = java.util.concurrent.CountDownLatch(1)
    val go = java.util.concurrent.CountDownLatch(1)
    val slow = new RegionStore(root):
      override def scan(): RegionScan =
        entered.countDown()
        go.await()
        super.scan()
    val book = RegionBook(slow, () => Some(p.catalog), () => 7L)
    val loader = java.util.concurrent.CompletableFuture.supplyAsync(() => book.state)
    entered.await()
    // A reader and a change while the load is in its scan.
    val reader = java.util.concurrent.CompletableFuture.supplyAsync(() => book.state)
    val adder = java.util.concurrent.CompletableFuture.supplyAsync(() =>
      book.update(s => s.add(Region(s.nextId, "b", Seq(osaka), 0), p.catalog)))
    Thread.sleep(100)
    assert(!reader.isDone && !adder.isDone)
    go.countDown()
    assertEquals(reader.get().regions.map(_.id).headOption, Some("r1"))
    assertEquals(adder.get().map(_.regions.map(_.id)), Right(Seq("r1", "r2")))
    assertEquals(loader.get().regions.head.id, "r1")
    // A reader that cannot wait for a load stuck in its scan sees "loading", and changes are refused.
    val stuck = new RegionStore(tmpDir()):
      override def scan(): RegionScan = { Thread.sleep(2_000); super.scan() }
    val b2 = RegionBook(stuck, () => None, () => 7L, loadWaitMs = 50)
    val first = java.util.concurrent.CompletableFuture.supplyAsync(() => b2.state)
    Thread.sleep(100)
    assert(b2.loading)
    assert(b2.update(identity).isLeft)
    first.get()
    assert(!b2.loading)
  }
