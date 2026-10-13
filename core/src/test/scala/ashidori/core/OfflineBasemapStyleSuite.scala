package ashidori.core

class OfflineBasemapStyleSuite extends munit.FunSuite:
  private val template =
    """{"version":8,"sources":{"japan":{"type":"vector","url":"pmtiles://file://@BASEMAP_DIR@/japan.pmtiles",
      |"attribution":"<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"}},
      |"glyphs":"asset://basemap/fonts/{fontstack}/{range}.pbf","sprite":"asset://basemap/sprites/light","layers":[]}""".stripMargin

  test("fills in the archive directory") {
    val s = OfflineBasemapStyle.render(Some(template), "/data/user/0/app/files/basemap/")
    assert(s.contains("pmtiles://file:///data/user/0/app/files/basemap/japan.pmtiles"), s)
    assert(!s.contains(OfflineBasemapStyle.Placeholder))
  }

  test("the generated style refers only to the device") {
    assertEquals(OfflineBasemapStyle.remoteUrls(OfflineBasemapStyle.render(Some(template), "/x")), Seq.empty)
  }

  test("without generated assets: a background-only style with no sources") {
    val s = OfflineBasemapStyle.render(None, "/x")
    assertEquals(s, OfflineBasemapStyle.fallback)
    assertEquals(ujson.read(s)("sources").obj.size, 0)
    assertEquals(OfflineBasemapStyle.remoteUrls(s), Seq.empty)
  }

  test("a template without the placeholder is not trusted") {
    val online = """{"version":8,"sources":{"s":{"type":"vector","url":"https://tiles.example/x.json"}},"layers":[]}"""
    assertEquals(OfflineBasemapStyle.render(Some(online), "/x"), OfflineBasemapStyle.fallback)
    assertEquals(OfflineBasemapStyle.remoteUrls(online), Seq("https://tiles.example/x.json"))
  }

  test("the generated asset, if built, refers only to the device") {
    val f = java.io.File("../app/src/main/assets/basemap/style.json")
    assume(f.isFile, "tools/basemap/build.sh has not been run")
    val s = OfflineBasemapStyle.render(Some(String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8")), "/x")
    assertNotEquals(s, OfflineBasemapStyle.fallback)
    assertEquals(OfflineBasemapStyle.remoteUrls(s), Seq.empty)
  }
