package ashidori.core

/** Importing one overflow file (raw lines the logger's queue had no room
  * for) into SQLite exactly once.
  *
  * The lines are inserted together with a mark naming the file, in one
  * transaction. Then the file is deleted, the deletion made durable (the
  * directory synced), and only then the mark removed. A crash or a failed
  * deletion at any point leaves the mark while the file exists, so the
  * next attempt deletes it without inserting its lines again.
  */
object OverflowImport:
  trait Ops:
    def marked: Boolean
    /** Inserts the file's lines and the mark, in one transaction. */
    def insertMarked(): Unit
    def deleteFile(): Boolean
    def syncDir(): Unit
    def unmark(): Unit

  enum Result:
    case Imported, AlreadyImported
    /** The file could not be deleted: its mark stays (not imported again). */
    case Kept

  def run(ops: Ops): Result =
    val had = ops.marked
    if !had then ops.insertMarked()
    if !ops.deleteFile() then Result.Kept
    else
      ops.syncDir()
      ops.unmark()
      if had then Result.AlreadyImported else Result.Imported
