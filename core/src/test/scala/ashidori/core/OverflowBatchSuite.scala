package ashidori.core

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** One batch's overflow file ([[OverflowFiles.Batch]]): written at most once
  * whatever the retries and kills, imported once ([[OverflowImport]]), a
  * `.tmp` never taken as complete.
  */
class OverflowBatchSuite extends munit.FunSuite:
  private final class Killed(at: String) extends Error(s"killed at $at")

  private def dir(): Path = Files.createTempDirectory("overflow")
  private def names(d: Path): Seq[String] = Files.list(d).iterator.asScala.map(_.getFileName.toString).toSeq.sorted
  private def bytes(id: Int) = s"$id\tline of batch $id\n".getBytes("UTF-8")

  /** Imports the completed files of `d` into `db` (batch ids), each once: the
    * import mark, the file deleted, the mark removed ([[OverflowImport]]).
    */
  private def importAll(d: Path, db: scala.collection.mutable.ArrayBuffer[Int], marks: scala.collection.mutable.Set[String]): Unit =
    for n <- names(d) if n.endsWith(OverflowFiles.Completed) do
      val f = d.resolve(n)
      OverflowImport.run(new OverflowImport.Ops:
        def marked = marks(n)
        def insertMarked(): Unit =
          db += String(Files.readAllBytes(f), "UTF-8").takeWhile(_ != '\t').toInt
          marks += n
        def deleteFile(): Boolean = Files.deleteIfExists(f) || !Files.exists(f)
        def syncDir(): Unit = ()
        def unmark(): Unit = marks -= n
      )

  test("a retry after the directory sync failed writes no second file and does not write the batch again") {
    val d = dir()
    var made = 0
    var syncFails = true
    val b = OverflowFiles.Batch(d, "raw-1.txt", () => { made += 1; bytes(1) }, _ => if syncFails then throw java.io.IOException("fsync"))
    intercept[java.io.IOException](b.write())
    assert(b.completedNow)
    syncFails = false
    b.write()
    b.write()
    assertEquals(made, 1)
    assertEquals(names(d), Seq("raw-1.txt"))
  }

  test("an attempt that failed (ENOSPC while writing) leaves no .tmp; the retry completes it once") {
    val d = dir()
    var fail = true
    val b = OverflowFiles.Batch(d, "raw-2.txt", () => bytes(2), _ => (),
      crash = at => if at == "write" && fail then throw java.io.IOException("No space left on device"))
    intercept[java.io.IOException](b.write())
    assertEquals(names(d), Nil)
    fail = false
    b.write()
    assertEquals(names(d), Seq("raw-2.txt"))
  }

  test("killed after the write, the fsync or the rename: a .tmp is never imported, a completed file once; batch ids accounted") {
    // Batches 1..30; the process is killed at one point of each third batch.
    val d = dir()
    val db = scala.collection.mutable.ArrayBuffer.empty[Int]
    val marks = scala.collection.mutable.Set.empty[String]
    val points = Seq("write", "fsync", "rename")
    val killed = scala.collection.mutable.Map.empty[Int, String]
    for id <- 1 to 30 do
      val at = if id % 3 == 0 then Some(points((id / 3) % 3)) else None
      val b = OverflowFiles.Batch(d, f"raw-$id%03d.txt", () => bytes(id), _ => (),
        crash = p => if at.contains(p) then throw Killed(p))
      try b.write()
      catch case _: Killed => killed(id) = at.get
      // A restart imports between some batches (and twice: nothing again).
      if id % 7 == 0 then
        importAll(d, db, marks)
        importAll(d, db, marks)
    importAll(d, db, marks)
    importAll(d, db, marks)
    // Killed before the rename: lost with the process (in memory), its .tmp kept, never imported.
    val beforeRename = killed.filter(_._2 != "rename").keySet
    val tmps = names(d).filter(_.endsWith(OverflowFiles.Partial))
    assertEquals(tmps.size, beforeRename.size)
    assertEquals(db.sorted.toSeq, (1 to 30).filterNot(beforeRename))
    assertEquals(db.distinct.size, db.size)
    assert(marks.isEmpty)
  }
