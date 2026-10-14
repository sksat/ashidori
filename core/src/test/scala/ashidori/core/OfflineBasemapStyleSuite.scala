package ashidori.core

class OfflineBasemapStyleSuite extends munit.FunSuite:
  private val template =
    """{"version":8,"sources":{"japan":{"type":"vector","url":"pmtiles://file://@BASEMAP_DIR@/japan.pmtiles",
      |"attribution":"<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"}},
      |"glyphs":"file://@BASEMAP_DIR@/fonts/{fontstack}/{range}.pbf","sprite":"file://@BASEMAP_DIR@/sprites/light","layers":[]}""".stripMargin

  test("fills in the archive directory") {
    val s = OfflineBasemapStyle.render(Some(template), "/data/user/0/app/files/basemap/").get
    assert(s.contains("pmtiles://file:///data/user/0/app/files/basemap/japan.pmtiles"), s)
    assert(!s.contains(OfflineBasemapStyle.Placeholder))
  }

  test("the generated style refers only to the device") {
    assertEquals(OfflineBasemapStyle.remoteUrls(OfflineBasemapStyle.render(Some(template), "/x").get), Seq.empty)
  }

  test("without generated assets: no offline style") {
    assertEquals(OfflineBasemapStyle.render(None, "/x"), None)
  }

  test("the blank canvas has no sources and fetches nothing") {
    val s = OfflineBasemapStyle.blank("#eeeeee")
    assertEquals(ujson.read(s)("sources").obj.size, 0)
    assertEquals(ujson.read(s)("layers")(0)("paint")("background-color").str, "#eeeeee")
    assertEquals(OfflineBasemapStyle.remoteUrls(s), Seq.empty)
  }

  test("a template without the placeholder is not trusted") {
    val online = """{"version":8,"sources":{"s":{"type":"vector","url":"https://tiles.example/x.json"}},"layers":[]}"""
    assertEquals(OfflineBasemapStyle.render(Some(online), "/x"), None)
    assertEquals(OfflineBasemapStyle.remoteUrls(online), Seq("https://tiles.example/x.json"))
  }

  test("the generated style, if built, refers only to the device") {
    val f = java.io.File("../tools/basemap/out/style.json")
    assume(f.isFile, "tools/basemap/build.sh has not been run")
    val s = OfflineBasemapStyle.render(Some(String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8")), "/x")
    assert(s.nonEmpty)
    assertEquals(OfflineBasemapStyle.remoteUrls(s.get), Seq.empty)
  }
