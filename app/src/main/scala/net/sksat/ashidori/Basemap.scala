package net.sksat.ashidori

import java.io.File
import scala.util.Try
import ashidori.core.OfflineBasemapStyle

/** The on-device coarse basemap (tools/basemap/build.sh): Japan to about
  * zoom 10 and the world to zoom 5, drawn without any network access.
  *
  * The generated files live in the APK under [[AssetDir]]. MapLibre 13.6
  * reads PMTiles byte ranges only from plain files (range reads from APK
  * assets came later), so the archives are copied to [[dir]] once per data
  * version. Glyphs and sprites are read from the assets directly.
  */
object Basemap:
  val AssetDir = "basemap"
  val ManifestPath = s"$AssetDir/manifest.json"
  private val Archives = Seq("japan.pmtiles", "world.pmtiles")

  def dir: File = File(AshidoriApp.app.getFilesDir, "basemap")

  /** Whether this build bundles the generated basemap. */
  lazy val bundled: Boolean = Try(AshidoriApp.app.getAssets.open(s"$AssetDir/style.json").close()).isSuccess

  private def asset(path: String): Option[Array[Byte]] =
    Try {
      val in = AshidoriApp.app.getAssets.open(path)
      try in.readAllBytes() finally in.close()
    }.toOption

  /** Identifies the bundled archives (their sha256 from the manifest). */
  private def stamp: String =
    asset(ManifestPath).flatMap(b => Try {
      val files = ujson.read(b)("files")
      Archives.map(a => files(a)("sha256").str).mkString(",")
    }.toOption).getOrElse("")

  private var ready: Option[Boolean] = None

  /** Copies the archives out of the APK if needed. Blocking; safe to call
    * from any thread. False if this build has no basemap.
    */
  def prepare(): Boolean = synchronized {
    ready.getOrElse {
      val ok =
        if !bundled then false
        else
          try
            val d = dir
            val stampFile = File(d, "stamp")
            val want = stamp
            val current = Try(String(java.nio.file.Files.readAllBytes(stampFile.toPath), "UTF-8")).getOrElse("")
            if current != want || !Archives.forall(a => File(d, a).isFile) then
              d.mkdirs()
              stampFile.delete()
              for a <- Archives do
                val tmp = File(d, s"$a.tmp")
                val in = AshidoriApp.app.getAssets.open(s"$AssetDir/$a")
                try java.nio.file.Files.copy(in, tmp.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                finally in.close()
                if !tmp.renameTo(File(d, a)) then throw java.io.IOException(s"cannot move $a")
              java.nio.file.Files.write(stampFile.toPath, want.getBytes("UTF-8"))
            true
          catch
            case e: Exception =>
              Log.w("offline basemap unavailable", e)
              false
      ready = Some(ok)
      ok
    }
  }

  private var style: Option[String] = None

  /** MapLibre style JSON of the offline map; a plain background without the data. */
  def styleJson: String = synchronized {
    style.getOrElse {
      val template = if prepare() then asset(s"$AssetDir/style.json").map(String(_, "UTF-8")) else None
      val s = OfflineBasemapStyle.render(template, dir.getAbsolutePath)
      style = Some(s)
      s
    }
  }

  /** Credit lines from the manifest, for データの出典. */
  def attribution: Seq[String] =
    asset(ManifestPath).flatMap(b => Try(ujson.read(b)("attribution").arr.map(_.str).toSeq).toOption).getOrElse(Seq.empty)
