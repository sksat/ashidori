package ashidori.core

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.file.Files

class BasemapReleaseSuite extends munit.FunSuite:
  private def sha(b: Array[Byte]) =
    java.security.MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  private def descriptor(version: String = "20261013", file: Option[String] = None, bytes: Long = 1234,
                         sha256: String = "a" * 64, format: String = BasemapPin.Format) =
    ujson.write(ujson.Obj("format" -> format, "version" -> version,
      "file" -> file.getOrElse(BasemapPin.archiveName(version)), "bytes" -> bytes.toDouble, "sha256" -> sha256,
      "build" -> "20261013"))

  test("parses a descriptor") {
    assertEquals(BasemapPin.parse(descriptor()), Right(BasemapPin("20261013", "ashidori-basemap-20261013.zip", 1234, "a" * 64)))
  }

  test("refuses malformed descriptors") {
    assert(BasemapPin.parse("nope").isLeft)
    assert(BasemapPin.parse(descriptor(format = "other")).isLeft)
    assert(BasemapPin.parse(descriptor(version = "../x")).isLeft)
    assert(BasemapPin.parse(descriptor(version = "a/b")).isLeft)
    assert(BasemapPin.parse(descriptor(file = Some("other.zip"))).isLeft)
    assert(BasemapPin.parse(descriptor(bytes = 0)).isLeft)
    assert(BasemapPin.parse(descriptor(sha256 = "A" * 64)).isLeft)
    assert(BasemapPin.parse(descriptor(sha256 = "a" * 63)).isLeft)
  }

  test("the pinned descriptor in the app parses") {
    val f = File("../app/src/main/assets/basemap-pin.json")
    assume(f.isFile, "tools/basemap/package.sh has not been run")
    assert(BasemapPin.parse(String(Files.readAllBytes(f.toPath), "UTF-8")).isRight)
  }

  /** Under build/, not the system temp directory. */
  private def tmpDir(): File =
    val base = File("build/tmp/basemap-suite")
    base.mkdirs()
    Files.createTempDirectory(base.toPath, "t").toFile

  private val pin = BasemapPin("v1", BasemapPin.archiveName("v1"), 10, "b" * 64)

  test("source URLs") {
    assertEquals(BasemapSource.githubUrl(pin),
      "https://github.com/sksat/ashidori/releases/download/basemap-v1/ashidori-basemap-v1.zip")
    assertEquals(BasemapSource.serverUrl("https://sync.example/", pin), "https://sync.example/basemap/v1/ashidori-basemap-v1.zip")
  }

  test("server versions from /v1/info; older servers list none") {
    assertEquals(BasemapSource.serverVersions("""{"registration":"admin","basemap":["v1","v0"]}"""), Set("v1", "v0"))
    assertEquals(BasemapSource.serverVersions("""{"registration":"admin"}"""), Set.empty[String])
    assertEquals(BasemapSource.serverVersions("""{"basemap":"v1"}"""), Set.empty[String])
    assertEquals(BasemapSource.serverVersions("""{"basemap":["../x", "v1"]}"""), Set("v1"))
    assertEquals(BasemapSource.serverVersions("<html>"), Set.empty[String])
  }

  test("source order") {
    import BasemapSource.*
    assertEquals(order(syncConfigured = false, serverHasVersion = true), Seq(GitHub))
    assertEquals(order(syncConfigured = true, serverHasVersion = true), Seq(Server, GitHub))
    assertEquals(order(syncConfigured = true, serverHasVersion = false), Seq(GitHub, Server))
  }

  test("resume plan") {
    import ResumableDownload.{plan, Plan}
    assertEquals(plan(4, 10, 206, Some("bytes 4-9/10")), Plan.Append)
    assertEquals(plan(4, 10, 206, Some("bytes 4-9/*")), Plan.Append)
    assert(plan(4, 10, 206, Some("bytes 0-9/10")).isInstanceOf[Plan.Fail])
    assert(plan(4, 10, 206, Some("bytes 4-9/11")).isInstanceOf[Plan.Fail])
    assert(plan(4, 10, 206, None).isInstanceOf[Plan.Fail])
    assertEquals(plan(4, 10, 200, None), Plan.Restart)
    assertEquals(plan(10, 10, 416, None), Plan.Complete)
    assertEquals(plan(4, 10, 416, None), Plan.Discard)
    assertEquals(plan(0, 10, 404, None), Plan.Fail("HTTP 404"))
  }

  /** Serves `data`, honouring Range unless `ranges` is false; cuts each body after `cut` bytes. */
  private final class FakeHttp(data: Array[Byte], ranges: Boolean = true, cut: Int = Int.MaxValue, status: Int = 0) extends RangeHttp:
    val requests = scala.collection.mutable.ArrayBuffer.empty[Long]
    def get(url: String, from: Long): RangeHttp.Response =
      requests += from
      val (st, bytes, range) =
        if status != 0 then (status, Array.emptyByteArray, None)
        else if from > 0 && ranges then
          if from >= data.length then (416, Array.emptyByteArray, None)
          else (206, data.drop(from.toInt), Some(s"bytes $from-${data.length - 1}/${data.length}"))
        else (200, data, None)
      new RangeHttp.Response:
        def code = st
        def header(name: String) = if name == "Content-Range" then range else None
        def body: InputStream = ByteArrayInputStream(bytes.take(cut))
        def close() = ()

  private val data = Array.tabulate[Byte](1000)(i => (i * 7).toByte)

  test("download resumes from the partial file with Range") {
    val dir = tmpDir()
    val part = File(dir, "a.part")
    Files.write(part.toPath, data.take(300))
    val http = FakeHttp(data)
    ResumableDownload.run(http, "u", part, data.length)
    assertEquals(http.requests.toSeq, Seq(300L))
    assertEquals(sha(Files.readAllBytes(part.toPath)), sha(data))
  }

  test("a cut connection is continued, and a server ignoring Range restarts the file") {
    val dir = tmpDir()
    val part = File(dir, "a.part")
    val cut = FakeHttp(data, cut = 400)
    ResumableDownload.run(cut, "u", part, data.length)
    assertEquals(cut.requests.toSeq, Seq(0L, 400L, 800L))
    assertEquals(sha(Files.readAllBytes(part.toPath)), sha(data))

    Files.write(part.toPath, data.take(300))
    val noRange = FakeHttp(data, ranges = false)
    ResumableDownload.run(noRange, "u", part, data.length)
    assertEquals(sha(Files.readAllBytes(part.toPath)), sha(data))
  }

  test("download refuses more bytes than pinned and keeps nothing") {
    val dir = tmpDir()
    val part = File(dir, "a.part")
    intercept[ResumableDownload.Refused](ResumableDownload.run(FakeHttp(data), "u", part, 500))
    assert(part.length <= 500)
    intercept[ResumableDownload.Refused](ResumableDownload.run(FakeHttp(data, status = 404), "u", part, 500))
  }

  test("download stops when cancelled, keeping the partial file") {
    val dir = tmpDir()
    val part = File(dir, "a.part")
    intercept[ResumableDownload.Cancelled](ResumableDownload.run(FakeHttp(data), "u", part, data.length, cancelled = () => true))
  }

  test("checksum verification") {
    val dir = tmpDir()
    val f = File(dir, "x")
    Files.write(f.toPath, data)
    val good = BasemapPin("v1", BasemapPin.archiveName("v1"), data.length, sha(data))
    assertEquals(Sha256.file(f), sha(data))
    assert(Sha256.matches(f, good))
    assert(!Sha256.matches(f, good.copy(sha256 = "0" * 64)))
    assert(!Sha256.matches(f, good.copy(bytes = data.length + 1)))
    assert(!Sha256.matches(File(dir, "missing"), good))
  }

  private def zip(f: File, entries: (String, Array[Byte])*): Unit =
    val out = java.util.zip.ZipOutputStream(java.io.FileOutputStream(f))
    try
      for (n, b) <- entries do
        out.putNextEntry(java.util.zip.ZipEntry(n))
        out.write(b)
        out.closeEntry()
    finally out.close()

  private def complete(extra: (String, Array[Byte])*) =
    BasemapStore.Required.map(_ -> "x".getBytes) ++ Seq("fonts/Noto Sans Regular/0-255.pbf" -> Array[Byte](1, 2)) ++ extra

  test("installs a version atomically and selects only the pinned one") {
    val root = tmpDir()
    val store = BasemapStore(root)
    val a = File(root.getParentFile, s"${root.getName}-a.zip")
    zip(a, complete()*)
    assertEquals(store.installed(pin), None)
    val dir = store.install(a, pin)
    assertEquals(dir, File(root, "v1"))
    assertEquals(store.installed(pin), Some(dir))
    assert(File(dir, "fonts/Noto Sans Regular/0-255.pbf").isFile)
    assertEquals(store.installed(pin.copy(version = "v2")), None)
    assert(!root.list().exists(_.startsWith(BasemapStore.TmpPrefix)))
  }

  test("an archive without the required files or with unsafe paths is not installed") {
    val root = tmpDir()
    val store = BasemapStore(root)
    val bad = File(root.getParentFile, s"${root.getName}-bad.zip")
    zip(bad, "style.json" -> "x".getBytes)
    intercept[java.io.IOException](store.install(bad, pin))
    val evil = File(root.getParentFile, s"${root.getName}-evil.zip")
    zip(evil, complete("../escape" -> "x".getBytes)*)
    intercept[java.io.IOException](store.install(evil, pin))
    assertEquals(store.installed(pin), None)
    assert(!File(root.getParentFile, "escape").exists())
    assertEquals(root.list().toSeq, Seq.empty)
  }

  test("entry names") {
    assert(BasemapStore.safeEntry("fonts/Noto Sans Regular/0-255.pbf"))
    assert(BasemapStore.safeEntry("sprites/light@2x.png"))
    assert(!BasemapStore.safeEntry("../x"))
    assert(!BasemapStore.safeEntry("/etc/passwd"))
    assert(!BasemapStore.safeEntry("a/./b"))
    assert(!BasemapStore.safeEntry("a\\b"))
    assert(!BasemapStore.safeEntry(""))
  }

  test("cleanup keeps the pinned version and its partial download only") {
    assertEquals(BasemapStore.stale(Seq("v0", "v1", ".download", ".tmp-v1", "japan.pmtiles", "stamp"), Some(pin)),
      Seq(".tmp-v1", "japan.pmtiles", "stamp", "v0"))
    assertEquals(BasemapStore.stale(Seq("v1", ".download"), None), Seq("v1"))

    val root = tmpDir()
    val store = BasemapStore(root)
    for d <- Seq("v0", "v1") do File(root, d).mkdirs()
    Files.write(File(root, "world.pmtiles").toPath, Array[Byte](1))
    store.downloadDir.mkdirs()
    Files.write(store.partFile(pin).toPath, Array[Byte](1))
    Files.write(File(store.downloadDir, "ashidori-basemap-v0.zip.part").toPath, Array[Byte](1))
    store.cleanup(Some(pin))
    assertEquals(root.list().toSet, Set("v1", ".download"))
    assertEquals(store.downloadDir.list().toSeq, Seq(store.partFile(pin).getName))
  }
