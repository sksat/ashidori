package ashidori.core

/** The on-device basemap's MapLibre style (built by tools/basemap/).
  *
  * Its sources, glyphs and sprites are under "file://@BASEMAP_DIR@/" (the
  * sources as "pmtiles://file://@BASEMAP_DIR@/<name>.pmtiles"); the app
  * fills in the directory it extracted the downloaded archive to. Before
  * the download (or without valid data) there is no offline style
  * ([[BaseMapPolicy.Outcome.NoBasemap]]): snapshots then draw the route
  * without MapLibre, and an interactive map draws it on [[blank]]. A map
  * never falls back to a network style.
  */
object OfflineBasemapStyle:
  val Placeholder = "@BASEMAP_DIR@"

  /** `template` is the generated style.json; `dir` an absolute path. None
    * without a template, or for one that would fetch from a server.
    */
  def render(template: Option[String], dir: String): Option[String] =
    template.filter(_.contains(Placeholder)).map(_.replace(Placeholder, dir.stripSuffix("/")))
      // Never let a style that would fetch from a server pass as offline.
      .filter(s => scala.util.Try(remoteUrls(s)).toOption.exists(_.isEmpty))

  /** A style with only a `background` color (CSS) and no sources: the canvas
    * of an interactive map without a base map, under the routes and stays
    * it adds. Not for a map that would draw nothing else.
    */
  def blank(background: String): String =
    ujson.write(ujson.Obj(
      "version" -> 8,
      "name" -> "ashidori (no base map)",
      "sources" -> ujson.Obj(),
      "layers" -> ujson.Arr(ujson.Obj("id" -> "background", "type" -> "background",
        "paint" -> ujson.Obj("background-color" -> background)))
    ))

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
