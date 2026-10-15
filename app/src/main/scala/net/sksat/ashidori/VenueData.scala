package net.sksat.ashidori

import java.io.File
import java.nio.file.Files
import scala.util.Try
import ashidori.core.{BasemapPin, BasemapStore, LatLng, PinnedKind, Venue, VenueIndex}

/** The offline venue index (施設データ, tools/venues/): places in Japan that
  * one checks in to, from OpenStreetMap, searched on the device without
  * sending anything.
  *
  * The APK does not carry it. It is downloaded once ([[VenueDownload]]) as
  * the archive pinned in the asset [[PinAsset]] (version, size, sha256) and
  * extracted to filesDir/venues/<version>/ ([[BasemapStore]]). The index is
  * opened on first use, off the main thread; it reads only the blocks near
  * a query ([[VenueIndex]]).
  */
object VenueData extends PinnedData:
  /** Written by tools/venues/package.sh. */
  val PinAsset = "venues-pin.json"
  val kind: PinnedKind = PinnedKind.Venues
  val IndexFile = "venues.aven"

  lazy val pin: Option[BasemapPin] = readPin(PinAsset)
  lazy val store: BasemapStore = BasemapStore(File(AshidoriApp.app.getFilesDir, "venues"), kind.required)

  /** The open index and the directory it was opened from; None: not tried yet. */
  private var opened: Option[(Option[String], Option[VenueIndex])] = None
  /** The installed index could not be read (opened or queried): shown in
    * the check-in sheet and the settings row, until it is replaced.
    */
  @volatile var broken = false

  /** A lookup near a spot: no index installed, an installed index that
    * cannot be read, or the venues found.
    */
  enum Lookup:
    case Missing, Broken
    case Found(venues: Vector[(Venue, Double)])

  /** The installed index, opened on first use. Blocking (reads the file's
    * header): call it off the main thread. None without (valid) data.
    */
  def index(): Option[VenueIndex] = synchronized {
    val d = dir
    val key = d.map(_.getAbsolutePath)
    opened match
      case Some((k, ix)) if k == key => ix
      case _ =>
        opened.flatMap(_._2).foreach(ix => Try(ix.close()))
        val t0 = System.nanoTime()
        val ix = d.flatMap { dd =>
          Try(VenueIndex.open(File(dd, IndexFile))).fold(e => { Log.w("venues: cannot open the index", e); None }, Some(_))
        }
        broken = d.nonEmpty && ix.isEmpty
        ix.foreach(x => Log.i(f"venues: opened ${x.size} venues, ${x.blocks} blocks in ${(System.nanoTime() - t0) / 1e6}%.1f ms"))
        opened = Some((key, ix))
        ix
  }

  override def changedOnDisk(): Unit = synchronized {
    opened.flatMap(_._2).foreach(ix => Try(ix.close()))
    opened = None
    broken = false
  }

  /** Venues near `p` from the index (widened where sparse). Blocking. */
  def lookup(p: LatLng, radiusM: Double = 200): Lookup =
    if !installed then Lookup.Missing
    else around(p, radiusM).fold(Lookup.Broken)(Lookup.Found(_))

  /** Venues near `p` (widened where sparse), or None without the index. Blocking. */
  def around(p: LatLng, radiusM: Double = 200): Option[Vector[(Venue, Double)]] =
    index().flatMap { ix =>
      val t0 = System.nanoTime()
      val r = Try(ix.around(p, radiusM)).fold(e => { Log.w("venues: lookup failed", e); broken = true; None }, Some(_))
      Log.i(f"venues: around ${r.fold(0)(_.size)} in ${(System.nanoTime() - t0) / 1e6}%.1f ms (${ix.inflated} blocks inflated so far)")
      r
    }

  /** Venues whose name contains `q`, closest to `p` first, or None without the index. Blocking. */
  def search(q: String, p: LatLng): Option[Vector[(Venue, Double)]] =
    index().flatMap { ix =>
      val t0 = System.nanoTime()
      val r = Try(ix.search(q, p)).fold(e => { Log.w("venues: search failed", e); broken = true; None }, Some(_))
      Log.i(f"venues: search ${r.fold(0)(_.size)} in ${(System.nanoTime() - t0) / 1e6}%.1f ms")
      r
    }

  private def manifest: Option[ujson.Value] =
    dir.flatMap(d => Try(ujson.read(Files.readAllBytes(File(d, "manifest.json").toPath))).toOption)

  /** Credit lines of the downloaded copy (its manifest), for データの出典. */
  def attribution: Seq[String] =
    manifest.flatMap(m => Try(m("attribution").arr.map(_.str).toSeq).toOption).getOrElse(Seq.empty)

  /** "2026年10月14日時点" from the installed data's OpenStreetMap date. */
  def dateLabel: Option[String] =
    manifest.flatMap(m => Try(m("source")("osmDate").str).toOption)
      .flatMap(s => Try(java.time.LocalDate.parse(s.take(10))).toOption)
      .map(d => s"${d.getYear}年${d.getMonthValue}月${d.getDayOfMonth}日時点")
