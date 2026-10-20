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
