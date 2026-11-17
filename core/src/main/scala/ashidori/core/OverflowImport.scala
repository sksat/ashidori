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

/** Overflow files (raw lines the queue had no room for, `.txt`; small-row
  * batches it had no room for or SQLite refused, `.rec`): each written whole
  * to `<name>.tmp`, synced, then renamed to `<name>` (never over a file) and
  * its directory synced. A completed file is never written again (only
  * imported and deleted), so a copy of it is consistent; a `.tmp` may be
  * being written, or left by a process that died while writing it.
  */
object OverflowFiles:
  import java.nio.file.{Files, Path, StandardOpenOption}

  val Completed = ".txt"
  /** A small-row batch ([[SmallRecord]]): completed the same way. */
  val CompletedRecord = ".rec"
  val Partial = ".tmp"
  private val completed = Seq(Completed, CompletedRecord)

  /** Writes `bytes` as the completed file `dir/name` (ending in
    * [[Completed]]): to its `.tmp`, synced, renamed, the directory synced.
    * `crash` marks the point before the rename (for tests).
    */
  def write(dir: Path, name: String, bytes: Array[Byte], syncDir: Path => Unit, crash: () => Unit = () => ()): Path =
    require(completed.exists(name.endsWith), name)
    val f = dir.resolve(name)
    val tmp = dir.resolve(name + Partial)
    val ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try
      val bb = java.nio.ByteBuffer.wrap(bytes)
      while bb.hasRemaining do ch.write(bb)
      ch.force(true)
    finally ch.close()
    crash()
    // Without REPLACE_EXISTING: a completed file is never written over.
    Files.move(tmp, f)
    syncDir(dir)
    f

  /** How a backup takes a file of the overflow directory: Some(false) a
    * completed file (a consistent copy), Some(true) a `.tmp` (as it is,
    * marked partial: never presented as consistent), None not a spill file.
    */
  def inBackup(name: String): Option[Boolean] =
    if completed.exists(name.endsWith) then Some(false)
    else if completed.exists(c => name.endsWith(c + Partial)) then Some(true)
    else None

  /** The spill files of `dir` for a backup, each opened by `open`: (file
    * name, what `open` gave, partial) in name order, and the names of the
    * files that could not be taken. A `.tmp` listed and then completed
    * before it was opened (renamed to its `.txt`) is taken as that
    * completed file; a spill file is never left out without being named.
    */
  def forBackup[A](dir: Path, open: Path => A): (Vector[(String, A, Boolean)], Vector[String]) =
    import scala.jdk.CollectionConverters.*
    val listed =
      if !Files.isDirectory(dir) then Vector.empty
      else scala.util.Using.resource(Files.list(dir))(_.iterator.asScala.map(_.getFileName.toString).toVector.sorted)
    val taken = scala.collection.mutable.Map.empty[String, (A, Boolean)]
    val missing = Vector.newBuilder[String]
    def take(name: String, partial: Boolean): Boolean =
      scala.util.Try(open(dir.resolve(name))).toOption.map(a => taken(name) = (a, partial)).isDefined
    for name <- listed; partial <- inBackup(name) if !taken.contains(name) do
      if !take(name, partial) then
        val done = name.stripSuffix(Partial)
        // Completed meanwhile: its completed file (listed or not).
        val ok = partial && (taken.contains(done) || take(done, partial = false))
        if !ok then missing += name
    (taken.toVector.map { case (n, (a, p)) => (n, a, p) }.sortBy(_._1), missing.result())

/** The backup ZIP's `manifest.txt`: a line per entry, `SHA-256  size  name`,
  * and `  partial` after the name for a file taken as it was while it may
  * have been being written (not a consistent copy). A file that should be
  * in it and could not be taken: `-  -  name  missing`.
  */
object BackupManifest:
  def line(sha: String, bytes: Long, name: String, partial: Boolean = false): String =
    s"$sha  $bytes  $name" + (if partial then "  partial" else "") + "\n"
  def missing(name: String): String = s"-  -  $name  missing\n"
