package ashidori.core

import ashidori.core.OverflowImport.*

class OverflowImportSuite extends munit.FunSuite:
  /** A file and a database: what was inserted, the mark, the file. */
  private final class World(var deletable: Boolean = true, var syncFails: Boolean = false):
    var inserted = 0
    var mark = false
    var file = true
    val ops: Ops = new Ops:
      def marked = mark
      def insertMarked(): Unit = { inserted += 1; mark = true }
      def deleteFile(): Boolean = { if deletable then file = false; !file }
      def syncDir(): Unit = if syncFails then throw java.io.IOException("fsync")
      def unmark(): Unit = mark = false

  test("a file is imported once, then deleted and unmarked") {
    val w = World()
    assertEquals(run(w.ops), Result.Imported)
    assertEquals((w.inserted, w.mark, w.file), (1, false, false))
  }

  test("a file that cannot be deleted keeps its mark and is never inserted again") {
    val w = World(deletable = false)
    assertEquals(run(w.ops), Result.Kept)
    assertEquals(run(w.ops), Result.Kept)
    assertEquals((w.inserted, w.mark, w.file), (1, true, true))
    // Once it can be deleted: deleted, not inserted again.
    w.deletable = true
    assertEquals(run(w.ops), Result.AlreadyImported)
    assertEquals((w.inserted, w.mark, w.file), (1, false, false))
  }

  test("a failed directory sync keeps the mark (the deletion may not be durable)") {
    val w = World(syncFails = true)
    intercept[java.io.IOException](run(w.ops))
    assertEquals((w.inserted, w.mark), (1, true))
  }

  // --- spill files and backups ---------------------------------------------------------------

  private final class Stop extends RuntimeException

  test("a spill is completed only by its rename: the .tmp written whole and synced first, the directory synced after") {
    val d = java.nio.file.Files.createTempDirectory("overflow")
    val synced = scala.collection.mutable.ArrayBuffer.empty[java.nio.file.Path]
    val f = OverflowFiles.write(d, "raw-1.txt", "1\ta\n".getBytes, synced += _)
    assertEquals(java.nio.file.Files.readString(f), "1\ta\n")
    assert(!java.nio.file.Files.exists(d.resolve("raw-1.txt.tmp")))
    assertEquals(synced.toSeq, Seq(d))
    // A completed file is never written again (not over it, nor appended to).
    intercept[java.nio.file.FileAlreadyExistsException](OverflowFiles.write(d, "raw-1.txt", "2\tb\n".getBytes, _ => ()))
    assertEquals(java.nio.file.Files.readString(f), "1\ta\n")
  }

  test("a backup takes completed spills as consistent copies and a .tmp as partial, whatever its age") {
    val d = java.nio.file.Files.createTempDirectory("overflow")
    OverflowFiles.write(d, "raw-1.txt", "1\ta\n".getBytes, _ => ())
    // A writer cut short (or still writing) before its rename.
    intercept[Stop](OverflowFiles.write(d, "raw-2.txt", "2\tb\n".getBytes, _ => (), () => throw Stop()))
    java.nio.file.Files.setLastModifiedTime(d.resolve("raw-2.txt.tmp"), java.nio.file.attribute.FileTime.fromMillis(0L))
    val names = java.nio.file.Files.list(d).toArray.toSeq.map(_.asInstanceOf[java.nio.file.Path].getFileName.toString).sorted
    assertEquals(names, Seq("raw-1.txt", "raw-2.txt.tmp"))
    assertEquals(names.map(OverflowFiles.inBackup), Seq(Some(false), Some(true)))
    assertEquals(OverflowFiles.inBackup("notes"), None)
    // In the manifest: only the .tmp is marked partial.
    assertEquals(BackupManifest.line("ab", 4, "files/overflow/raw-1.txt"), "ab  4  files/overflow/raw-1.txt\n")
    assertEquals(BackupManifest.line("cd", 4, "files/overflow/raw-2.txt.tmp", partial = true),
      "cd  4  files/overflow/raw-2.txt.tmp  partial\n")
  }
