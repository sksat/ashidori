package ashidori.core

/** The on-device basemap's MapLibre style (built by tools/basemap/).
  *
  * Its sources are "pmtiles://file://@BASEMAP_DIR@/<name>.pmtiles"; the app
  * fills in the directory holding the archives. Without the generated
  * assets, a style with only a background is used, so a map never falls
  * back to a network style.
  */
object OfflineBasemapStyle:
  val Placeholder = "@BASEMAP_DIR@"
  /** Land-ish background of the fallback, close to the Protomaps light earth. */
  val FallbackBackground = "#e2dfda"

  /** `template` is the generated style.json; `dir` an absolute path. */
  def render(template: Option[String], dir: String): String =
    template match
      case Some(t) if t.contains(Placeholder) =>
        val s = t.replace(Placeholder, dir.stripSuffix("/"))
        // Never let a style that would fetch from a server pass as offline.
        if scala.util.Try(remoteUrls(s)).toOption.exists(_.isEmpty) then s else fallback
      case _ => fallback

  val fallback: String =
    s"""{"version":8,"name":"ashidori offline (no data)","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$FallbackBackground"}}]}"""

  /** Resource URLs of a style that are not on the device: sources (url,
    * tiles), glyphs and sprite. Attribution links are not fetched.
    */
  def remoteUrls(style: String): Seq[String] =
    val js = ujson.read(style)
    val sources = js.obj.get("sources").map(_.obj.values.toSeq).getOrElse(Seq.empty).flatMap { s =>
      s.obj.get("url").map(_.str).toSeq ++ s.obj.get("tiles").map(_.arr.map(_.str).toSeq).getOrElse(Seq.empty)
    }
    val resources = Seq("glyphs", "sprite").flatMap(k => js.obj.get(k).collect { case ujson.Str(u) => u })
    (sources ++ resources).filterNot(local)

  private def local(url: String): Boolean =
    Seq("asset://", "file://", "pmtiles://asset://", "pmtiles://file://").exists(url.startsWith)
