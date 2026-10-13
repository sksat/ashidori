package ashidori.core

import java.util.concurrent.{CountDownLatch, TimeUnit}
import ashidori.core.sync.*

/** A file in memory. `onReplace` runs before each replace and may block,
  * throw (the write failed, nothing replaced) or keep the content.
  */
final class MemFile extends DurableFile:
  @volatile var content: Option[Array[Byte]] = None
  @volatile var onReplace: Array[Byte] => Unit = _ => ()
  @volatile var unreadable = false
  def read(): Option[Array[Byte]] =
    if unreadable then throw java.io.IOException("read failed") else content
  def replace(b: Array[Byte]): Unit =
    onReplace(b)
    content = Some(b)

class DurableMapSuite extends munit.FunSuite:
  test("a reader never sees a value whose write is in progress or failed (F1)") {
    val f = MemFile()
    val m = DurableMap(f)
    m.commit("master" -> "old")
    val writing = CountDownLatch(1)
    val release = CountDownLatch(1)
    f.onReplace = _ =>
      writing.countDown()
      release.await(10, TimeUnit.SECONDS)
      throw java.io.IOException("disk full")
    val writer = Thread(() => { intercept[java.io.IOException](m.commit("master" -> "new")); () })
    writer.start()
    assert(writing.await(10, TimeUnit.SECONDS))
    // While the write is under way, readers do not wait and see what is on disk.
    for _ <- 1 to 1000 do assertEquals(m.getString("master"), Some("old"))
    release.countDown()
    writer.join(10_000)
    assertEquals(m.getString("master"), Some("old"))
    // A restart reads the same.
    assertEquals(DurableMap(f).getString("master"), Some("old"))
  }

  test("a successful write is visible only once it returned, and survives a restart") {
    val f = MemFile()
    val m = DurableMap(f)
    val seen = java.util.concurrent.ConcurrentLinkedQueue[Option[String]]()
    f.onReplace = _ => seen.add(m.getString("k"))
    m.commit("k" -> "v", "b" -> true)
    assertEquals(seen.peek(), None)
    assertEquals(m.getString("k"), Some("v"))
    val again = DurableMap(f)
    assertEquals((again.getString("k"), again.getBoolean("b", false)), (Some("v"), true))
    m.commit("k" -> null)
    assertEquals(DurableMap(f).getString("k"), None)
    assertEquals(DurableMap(f).getBoolean("b", false), true)
  }

  test("a failed write whose outcome cannot be read back hides the keys it changed until a write succeeds") {
    val f = MemFile()
    val m = DurableMap(f)
    m.commit("a" -> "1", "b" -> "2")
    f.onReplace = b =>
      f.content = Some(b) // the new records may have reached the disk...
      f.unreadable = true // ...but reading back fails
      throw java.io.IOException("rename failed")
    intercept[java.io.IOException](m.commit("a" -> "3"))
    assertEquals(m.getString("a"), None)
    assertEquals(m.getString("b"), Some("2"))
    f.unreadable = false
    f.onReplace = _ => ()
    m.commit("b" -> "4")
    // The successful write wrote every record of the snapshot: the file matches it.
    assertEquals(m.getString("a"), Some("1"))
    assertEquals(DurableMap(f).snapshot, Map("a" -> "1", "b" -> "4"))
  }

  test("a write that failed after the new records reached the disk counts as done") {
    val f = MemFile()
    val m = DurableMap(f)
    f.onReplace = b =>
      f.content = Some(b)
      throw java.io.IOException("directory sync failed")
    m.commit("a" -> "1")
    assertEquals(m.getString("a"), Some("1"))
  }

  test("a damaged file reads as empty") {
    val f = MemFile()
    DurableMap(f).commit("a" -> "1")
    val s = String(f.content.get, "UTF-8").replace("\\\"1\\\"", "\\\"2\\\"")
    f.content = Some(s.getBytes("UTF-8"))
    assertEquals(DurableMap(f).snapshot, Map.empty[String, DurableMap.Value])
    f.content = Some("{".getBytes("UTF-8"))
    assertEquals(DurableMap(f).getString("a"), None)
  }

  test("the real file is replaced through a synced temporary file") {
    val dir = java.nio.file.Files.createTempDirectory(java.nio.file.Paths.get("build"), "durable")
    val f = DurableFile(dir.resolve("x/store.json").toFile)
    DurableMap(f).commit("k" -> "v")
    assertEquals(DurableMap(f).getString("k"), Some("v"))
    assert(!dir.resolve("x/store.json.tmp").toFile.exists())
  }
