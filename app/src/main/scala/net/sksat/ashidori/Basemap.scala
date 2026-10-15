package net.sksat.ashidori

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.util.Try
import ashidori.core.{BasemapPin, BasemapStore, OfflineBasemapStyle, PinnedKind}

/** The on-device coarse basemap (tools/basemap/): Japan to about zoom 10 and
  * the world to zoom 5, drawn without any network access.
  *
  * The APK does not carry it. It is downloaded once ([[BasemapDownload]])
  * as the archive pinned in the asset [[PinAsset]] (version, size, sha256)
  * and extracted to filesDir/basemap/<version>/ ([[BasemapStore]]). Until
  * then the offline map is a plain background under the routes and stays
  * (the fallback style of [[OfflineBasemapStyle]]).
  */
object Basemap extends PinnedData:
  /** Written by tools/basemap/package.sh. */
  val PinAsset = "basemap-pin.json"
  val kind: PinnedKind = PinnedKind.Basemap

  /** The archive this build accepts; None if the build has no pin. */
  lazy val pin: Option[BasemapPin] = readPin(PinAsset)

  /** cleanup() also removes the pre-download layout (archives copied out of older APKs). */
  lazy val store: BasemapStore = BasemapStore(File(AshidoriApp.app.getFilesDir, "basemap"))

  private var style: Option[(Option[String], Option[String])] = None

  /** MapLibre style JSON of the offline map; None without (valid) data. */
  def styleJson: Option[String] = synchronized {
    val d = dir
    val key = d.map(_.getAbsolutePath)
    style match
      case Some((k, s)) if k == key => s
      case _ =>
        val template = d.flatMap(x => Try(String(Files.readAllBytes(File(x, "style.json").toPath), UTF_8)).toOption)
        val s = template.flatMap(t => OfflineBasemapStyle.render(Some(t), key.getOrElse("")))
        if d.nonEmpty && s.isEmpty then Log.w("basemap: installed style.json is missing or not local")
        style = Some((key, s))
        s
  }

  /** The offline map can be drawn: its data is installed and valid. */
  def available: Boolean = styleJson.nonEmpty

  /** Credit lines of the downloaded copy (its manifest), for データの出典. */
  def attribution: Seq[String] =
    dir.flatMap(d => Try(ujson.read(Files.readAllBytes(File(d, "manifest.json").toPath))("attribution").arr.map(_.str).toSeq).toOption)
      .getOrElse(Seq.empty)

