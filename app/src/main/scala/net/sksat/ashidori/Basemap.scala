package net.sksat.ashidori

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.util.Try
import ashidori.core.{BasemapPin, BasemapStore, OfflineBasemapStyle}

/** The on-device coarse basemap (tools/basemap/): Japan to about zoom 10 and
  * the world to zoom 5, drawn without any network access.
  *
  * The APK does not carry it. It is downloaded once ([[BasemapDownload]])
  * as the archive pinned in the asset [[PinAsset]] (version, size, sha256)
  * and extracted to filesDir/basemap/<version>/ ([[BasemapStore]]). Until
  * then the offline map is a plain background under the routes and stays
  * (the fallback style of [[OfflineBasemapStyle]]).
  */
object Basemap:
  /** Written by tools/basemap/package.sh. */
  val PinAsset = "basemap-pin.json"

  /** The archive this build accepts; None if the build has no pin. */
  lazy val pin: Option[BasemapPin] =
    Try {
      val in = AshidoriApp.app.getAssets.open(PinAsset)
      try String(in.readAllBytes(), UTF_8) finally in.close()
    }.toOption.flatMap { s =>
      BasemapPin.parse(s) match
        case Right(p) => Some(p)
        case Left(e) =>
          Log.w(s"basemap pin invalid: $e")
          None
    }

  lazy val store: BasemapStore = BasemapStore(File(AshidoriApp.app.getFilesDir, "basemap"))

  /** Serializes installation, cleanup and deletion (one process). */
  val lock = new Object

  /** Directory of the installed pinned version. */
  def dir: Option[File] = pin.flatMap(store.installed)
  def installed: Boolean = dir.nonEmpty
  def installedVersion: Option[String] = dir.map(_.getName)

  /** Removes other versions, the pre-download layout (archives copied out of
    * older APKs) and leftovers. Blocking.
    */
  def cleanup(): Unit = lock.synchronized {
    val gone = store.cleanup(pin)
    if gone.nonEmpty then Log.i(s"basemap: removed ${gone.mkString(", ")}")
  }

  /** Deletes every downloaded file. Blocking. */
  def deleteAll(): Unit = lock.synchronized(store.deleteAll())

  private var style: Option[(Option[String], String)] = None

  /** MapLibre style JSON of the offline map; a plain background without the data. */
  def styleJson: String = synchronized {
    val d = dir
    val key = d.map(_.getAbsolutePath)
    style match
      case Some((k, s)) if k == key => s
      case _ =>
        val template = d.flatMap(x => Try(String(Files.readAllBytes(File(x, "style.json").toPath), UTF_8)).toOption)
        val s = OfflineBasemapStyle.render(template, key.getOrElse(""))
        style = Some((key, s))
        s
  }

  /** Credit lines of the downloaded copy (its manifest), for データの出典. */
  def attribution: Seq[String] =
    dir.flatMap(d => Try(ujson.read(Files.readAllBytes(File(d, "manifest.json").toPath))("attribution").arr.map(_.str).toSeq).toOption)
      .getOrElse(Seq.empty)

  /** Approximate size for the UI, e.g. "約 64 MB". */
  def sizeLabel: String = pin.fold("")(p => s"約 ${math.max(1, math.round(p.bytes / 1e6))} MB")
