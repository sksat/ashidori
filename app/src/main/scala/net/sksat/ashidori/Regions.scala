package net.sksat.ashidori

import android.content.Context
import java.io.{File, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption}
import scala.util.Try
import ashidori.core.*

/** The offline region packs (docs/design/outbound-and-offline.md 6): the
  * regions the user chose, their packs under filesDir/regions
  * ([[RegionStore]]), the pinned catalog, and the offline style with the
  * packs drawn over the basemap.
  *
  * The state lives in `state.json`, not in the record's database; every
  * change goes through [[update]] under one lock and is written at once.
  * At start the state and the files are reconciled ([[RegionReconcile]])
  * before anything else touches them. Files of packs and bundles that no
  * region needs go to the state's trash and are deleted once no map draws
  * from them ([[RegionUse]]).
  */
object Regions:
  private def app = AshidoriApp.app
  private def prefs = app.getSharedPreferences("regions", Context.MODE_PRIVATE)
  /** Written by tools/regionpacks/package.sh; absent until the packs are published. */
  val PinAsset = "regions-pin.json"
  /** Packs drawn at once (design 6.10): more sources slow the map. */
  val MaxPacks = 16
  /** Cells one selection may take (design 6.11). */
  val MaxCells = 4

  lazy val root: File = File(app.getFilesDir, "regions")
  lazy val store: RegionStore = RegionStore(root)

  def debuggable: Boolean = (app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  // ---- the catalog ----

  private lazy val bundledPin: Option[RegionPin] =
    Try {
      val in = app.getAssets.open(PinAsset)
      try String(in.readAllBytes(), UTF_8) finally in.close()
    }.toOption.flatMap(s => RegionPin.parse(s).fold(e => { Log.w(s"regions pin invalid: $e"); None }, Some(_)))

  /** Debug builds only: a directory URL serving the catalog, the bundle and
    * the packs, and the pin of that catalog (DebugReceiver REGIONS_SOURCE).
    */
  def debugUrl: Option[String] = Option(prefs.getString("debugUrl", "")).filter(_.nonEmpty).filter(_ => debuggable)
  def debugPin: Option[RegionPin] =
    Option(prefs.getString("debugPin", "")).filter(_.nonEmpty).filter(_ => debuggable).flatMap(RegionPin.parse(_).toOption)
  def setDebugSource(url: String, pin: String): Unit =
    prefs.edit().putString("debugUrl", url.trim.stripSuffix("/")).putString("debugPin", pin).commit()
    catalogCache = None

  /** The catalog this build accepts (its pin); None: the feature is not offered. */
  def pin: Option[RegionPin] = debugPin.orElse(bundledPin)

  /** Where a file of the catalog `version` comes from: its GitHub release, or the debug source. */
  def url(version: String, file: String): String = debugUrl match
    case Some(base) => s"$base/$file"
    case None => s"https://github.com/${BasemapSource.Repository}/releases/download/${RegionPin.tag(version)}/$file"

  @volatile private var catalogCache: Option[(RegionPin, RegionCatalog)] = None
  private def catalogFile(p: RegionPin) = File(root, s"${RegionStore.CatalogDir}/${p.file}")

  /** The pinned catalog if it is on the device (downloaded before). */
  def catalog: Option[RegionCatalog] =
    pin.flatMap { p =>
      catalogCache.filter(_._1 == p).map(_._2).orElse {
        val f = catalogFile(p)
        Try(Files.readAllBytes(f.toPath)).toOption.filter(p.matches).flatMap(b => RegionCatalog.parse(String(b, UTF_8)).toOption).map { c =>
          catalogCache = Some(p -> c)
          c
        }
      }
    }

  /** The pinned catalog, downloaded under `permit` if not on the device.
    * Its request holds no position. Throws for a failed or wrong download.
    */
  def fetchCatalog(permit: Permit): RegionCatalog =
    val p = pin.getOrElse(throw IOException("no catalog pinned"))
    catalog.getOrElse {
      val http = Net.outbound.rangeHttp(permit, BasemapWorker.ua)
      val r = http.get(url(p.version, p.file), 0)
      val bytes = try
        if r.code != 200 then throw ResumableDownload.HttpFailure(s"HTTP ${r.code}", r.code)
        val in = r.body
        try in.readNBytes((p.bytes + 1).toInt) finally in.close()
      finally r.close()
      if !p.matches(bytes) then throw IOException(RegionRun.MismatchReason)
      val c = RegionCatalog.parse(String(bytes, UTF_8)).fold(e => throw IOException(s"catalog: $e"), identity)
      val f = catalogFile(p)
      f.getParentFile.mkdirs()
      val tmp = File(f.getParentFile, f.getName + ".tmp")
      Files.write(tmp.toPath, bytes)
      Files.move(tmp.toPath, f.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      // Catalogs of other versions are no longer needed.
      Option(f.getParentFile.listFiles()).toSeq.flatten.filterNot(_.getName == f.getName).foreach(_.delete())
      catalogCache = Some(p -> c)
      c
    }

  // ---- the rules ----

  /** Region packs may be downloaded now: the data rule sends cells (区画), and nothing is paused. */
  def allowed: Boolean = Settings.gate.permit(Dest.Data, Mode.OnAction).exists(_.level == Level.Cell)
  /** Offered at all: this build pins a catalog. */
  def offered: Boolean = pin.nonEmpty && book.broken.isEmpty
  /** The data rule downloads by itself (automatic), so updates may run without the user. */
  def automatic: Boolean = Settings.gate.permit(Dest.Data, Mode.Auto).exists(_.level == Level.Cell)

  /** 「Wi-Fi 接続時に自動更新」 (design 6.12): on by default, the last choice kept. */
  def autoUpdate: Boolean = prefs.getBoolean("autoUpdate", true)
  def autoUpdate_=(b: Boolean): Unit = prefs.edit().putBoolean("autoUpdate", b).apply()
  /** 「モバイルデータ通信でも取得」: off by default. */
  def metered: Boolean = prefs.getBoolean("metered", false)
  def metered_=(b: Boolean): Unit = prefs.edit().putBoolean("metered", b).apply()

  // ---- the state ----

  /** The state and its files ([[RegionBook]]): nothing it does throws, so a
    * failure of the regions never takes the process (and the logger) down.
    */
  lazy val book: RegionBook = RegionBook(store, () => scala.util.Try(catalog).toOption.flatten, () => System.currentTimeMillis(),
    msg => Log.i(msg), use)
  def use: RegionUse = useInstance
  private val useInstance = RegionUse()

  /** The state now (reconciled on first use; empty while the regions are off). */
  def state: RegionState = book.state

  /** Reconciles the state with the files once per process, before any
    * other use of them: the download worker and the UI wait for it.
    */
  def ensureLoaded(): Unit = book.ensureLoaded()

  /** Why the regions cannot be used now (their files unreadable), or the last write failed. */
  def problem: Option[String] = book.broken.orElse(book.writeError)

  /** Applies `f` to the state and writes it, then tells the UI. Left (the
    * state as it was) when it could not be written.
    */
  def update(f: RegionState => RegionState): Either[String, RegionState] =
    val r = book.update(f)
    changed()
    r

  /** After a change that may leave files unused (a deletion, an update):
    * they go to the trash, deleted at once if nothing needs them.
    */
  def tidy(): Unit = book.tidy()

  /** Deletes what is in the trash and needed by nothing now. */
  def collect(): Unit = book.collect()

  // ---- what the UI shows ----

  @volatile var progress: Map[Cell, Long] = Map.empty
  /** Why downloads wait now (空き容量が不足, 通信できません), until the next run gets on. */
  @volatile var waiting: Option[String] = None

  private val listeners = scala.collection.mutable.LinkedHashSet.empty[() => Unit]
  def listen(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f
  /** Tells the UI and the maps (on the main thread). */
  def changed(): Unit = app.getMainExecutor.execute(() => listeners.toSeq.foreach(_()))

  def styles: Set[String] = scala.util.Try(store.scan().styles).getOrElse(Set.empty)

  def status(r: Region): RegionStatus = RegionStatus.of(r, state, styles, scala.util.Try(catalog).toOption.flatten, progress)

  // ---- drawing ----

  /** Placed packs that can be drawn (their bundle is there), in cell order. */
  def placed: Seq[(Cell, Installed)] =
    val s = state
    val st = styles
    s.packs.toSeq.collect { case (c, PackState(Some(i), _, _)) if st(i.style) && s.referenced(c) => c -> i }.sortBy(_._1)

  /** The offline map draws packs. */
  def drawable: Boolean = placed.nonEmpty
  /** Changes whenever the packs on the device change (snapshot cache keys). */
  def key: String = placed.map((c, i) => s"${c.id}@${i.version}").mkString("+")

  /** What each map shows (its view), so that a map with more packs than
    * [[MaxPacks]] around draws those in and near its view ([[RegionPick]]).
    */
  private val views = java.util.Collections.synchronizedMap(java.util.WeakHashMap[AnyRef, Bounds]())
  def view(owner: AnyRef, b: Bounds): Unit = views.put(owner, b)

  /** The packs `owner` would draw for its view now. */
  def pick(owner: AnyRef): Seq[(Cell, Installed)] = RegionPick(placed, Option(views.get(owner)), MaxPacks)

  /** `owner`'s view moved to packs its style does not draw: it should load the style again. */
  def stale(owner: AnyRef): Boolean =
    try
      val all = placed
      all.size > MaxPacks && {
        val want = RegionPick(all, Option(views.get(owner)), MaxPacks).map((c, i) => RegionStore.packDir(c, i.version)).toSet
        !want.forall(use.holds(owner, _))
      }
    catch case _: Throwable => false

  private val layerCache = scala.collection.mutable.Map.empty[String, String]
  private def layers(style: String): Option[String] = layerCache.synchronized {
    layerCache.get(style).orElse {
      Try(String(Files.readAllBytes(File(store.styleRoot(style), RegionStore.StyleLayers).toPath), UTF_8)).toOption.map { l =>
        layerCache(style) = l
        l
      }
    }
  }

  private val Credit = """<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a> <a href="https://protomaps.com/">Protomaps</a>"""

  /** The offline style with the packs around `owner`'s view over `base`,
    * for `owner` (a map or a snapshot), who then holds those files until
    * [[release]]. None without packs (or if they cannot be read): the
    * owner holds nothing.
    */
  def style(owner: AnyRef, base: Option[String]): Option[String] =
    try
      val ps = pick(owner).flatMap((c, i) => layers(i.style).map(l =>
        (c, i, RegionStyle.Pack(c, store.packFile(c, i.version).getAbsolutePath, l, store.styleRoot(i.style).getAbsolutePath))))
      if ps.isEmpty then
        release(owner)
        None
      else
        use.hold(owner, ps.flatMap((c, i, _) => Seq(RegionStore.packDir(c, i.version), RegionStore.styleDir(i.style))).toSet)
        Some(RegionStyle.compose(base, ps.map(_._3), Credit))
    catch case e: Throwable =>
      Log.w("regions: style not composed", e)
      release(owner)
      None

  /** `owner` no longer draws from the packs: unused files may go. */
  def release(owner: AnyRef): Unit =
    if use.release(owner) && state.trash.nonEmpty then AshidoriApp.io.execute(() => collect())

  // ---- regions ----

  /** The default name of a region centred at a point: its city on the
    * device's index (「つくば市周辺」), else 「地域 N」. No geocoder is asked.
    */
  def defaultName(lat: Double, lon: Double): String =
    Try(Geocoding.offline(lat, lon)).toOption.flatten.map(_.city).filter(_.nonEmpty).map(_ + "周辺")
      .getOrElse(s"地域 ${state.regions.size + 1}")

  /** Adds a region of `cells` and plans its packs. */
  def add(name: String, cells: Seq[Cell], c: RegionCatalog): Either[String, Region] =
    // The id is taken from the state as the change is made (under its lock).
    var made: Option[Region] = None
    update { s =>
      val r = Region(s.nextId, name, cells.distinct.sorted, System.currentTimeMillis())
      made = Some(r)
      s.add(r, c)
    }.map(_ => made.get)

  def rename(id: String, name: String): Either[String, RegionState] = update(_.rename(id, name))

  /** Deletes a region: its own packs leave the maps first, then their files go once unused. */
  def delete(id: String): Either[String, RegionState] =
    val r = update(_.remove(id))
    AshidoriApp.io.execute(() => tidy())
    r

  /** Plans the catalog's newer version of a region's packs. */
  def planUpdate(id: String, c: RegionCatalog): Either[String, RegionState] =
    update(s => s.update(s.regions.find(_.id == id).map(_.cells.toSet).getOrElse(Set.empty), c))

  /** Plans every placed pack with a newer version in the catalog (automatic updates). */
  def planAllUpdates(c: RegionCatalog): Boolean =
    val before = state.queue.size
    update(s => s.update(s.referenced, c)).exists(_.queue.size > before)

  /** Clears a region's failures, so its packs are tried again; an unusable
    * pack whose bundle is missing is planned at its own version (only the
    * bundle is then downloaded).
    */
  def retry(id: String): Either[String, RegionState] =
    val cat = Try(catalog).toOption.flatten
    update { s =>
      val cells = s.regions.find(_.id == id).map(_.cells.toSet).getOrElse(Set.empty)
      val st = styles
      s.copy(packs = s.packs.map { (c, p) =>
        if !cells(c) then c -> p
        else
          val replan = p.installed.filter(i => !st(i.style) && p.target.isEmpty).flatMap { i =>
            cat.filter(_.version == i.version).flatMap(_.cells.get(c)).map(e => RegionCatalog.target(cat.get, e))
          }
          c -> p.copy(target = p.target.orElse(replan), failure = None)
      })
    }
