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

  /** One batch's overflow file, completed at most once whatever the retries
    * (plan C stage 2). Its name is fixed with the batch (unique to the
    * process and batch); once a rename completed it, a retry only syncs the
    * directory again and never writes a second file, so the batch cannot be
    * imported twice.
    *
    * A `.tmp` is never deleted (it may be the only copy on disk) and never
    * taken as complete. A retry after a `.tmp` was written whole and synced
    * (the rename or something after the sync failed) renames that same file;
    * a retry after a write or sync that failed writes a new `.tmp` of its
    * own (`<name without suffix>.r<n><suffix>.tmp`), leaving the earlier one
    * as it is. `crash` marks the points after the write, after the fsync,
    * just before the rename (`move`) and after it (for tests).
    */
  final class Batch(val dir: Path, val name: String, bytes: () => Array[Byte], syncDir: Path => Unit,
                    crash: String => Unit = _ => (), prepare: () => Unit = () => ()):
    require(completed.exists(name.endsWith), name)
    @volatile private var done = false
    // The `.tmp` written whole and synced, not yet renamed.
    private var synced: Option[Path] = None
    private var attempt = 0
    /** Completed (renamed) by an attempt of this batch. */
    def completedNow: Boolean = done

    private def tmpName(n: Int): String =
      if n == 0 then name + Partial
      else
        val ext = completed.find(name.endsWith).get
        name.stripSuffix(ext) + s".r$n" + ext + Partial

    def write(): Path =
      val f = dir.resolve(name)
      synchronized {
        if !done then
          prepare()
          val tmp = synced.getOrElse {
            val t = dir.resolve(tmpName(attempt))
            attempt += 1
            val ch = java.nio.channels.FileChannel.open(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try
              val bb = java.nio.ByteBuffer.wrap(bytes())
              while bb.hasRemaining do ch.write(bb)
              crash("write")
              ch.force(true)
            finally ch.close()
            crash("fsync")
            synced = Some(t)
            t
          }
          crash("move")
          // Without REPLACE_EXISTING: a completed file is never written over.
          // Only an attempt of this batch has its name, so one there is this
          // batch completed (its `.tmp` stays: never deleted).
          try Files.move(tmp, f)
          catch case _: java.nio.file.FileAlreadyExistsException => ()
          done = true
          synced = None
          crash("rename")
        syncDir(dir)
      }
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
        // (A retry's `.tmp`, `<name>.r<n><suffix>.tmp`, completes `<name><suffix>`.)
        val done = name.stripSuffix(Partial).replaceAll("""\.r\d+(\.txt|\.rec)$""", "$1")
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

/** The import of overflow files on the logger's writer queue (plan C stage
  * 2): one file per step, each step posted behind whatever is queued, so a
  * new batch waiting is always written first (a step that finds batches
  * waiting posts itself again, behind them, and imports nothing). The
  * chain goes on until no file is left, with no cap per run, logging or not.
  * Raw and small batches are one chain, oldest first.
  *
  * A file that fails is skipped for the rest of the chain and tried again
  * later, after a backoff that doubles from [[ImportChain.FirstBackoffMs]] up
  * to [[ImportChain.MaxBackoffMs]] (no tight retry loop); a file held by a
  * backup is tried again after [[ImportChain.BusyMs]]. `stopped()` (the
  * emergency state) ends the chain; [[request]] starts it again.
  */
final class ImportChain(post: Runnable => Unit, later: (Runnable, Long) => Unit, pending: () => Long,
                        stopped: () => Boolean, next: Set[String] => Option[String],
                        importOne: String => ImportChain.Step):
  import ImportChain.*
  private var queued = false
  private var skip = Set.empty[String]
  private var backoff = 0L
  private var waiting = false

  /** Posts a step unless one is queued. */
  def request(): Unit =
    val go = synchronized { if queued then false else { queued = true; true } }
    if go then post(() => step())

  private def step(): Unit =
    synchronized { queued = false }
    if !stopped() then
      if pending() > 0 then request()
      else
        val s = synchronized(skip)
        next(s) match
          case None =>
            if s.nonEmpty then retryLater()
          case Some(f) =>
            importOne(f) match
              case Step.Imported =>
                synchronized { backoff = 0 }
                request()
              case Step.Busy => once(BusyMs)
              case Step.Failed =>
                synchronized { skip += f }
                request()

  /** The skipped files again, after the backoff. */
  private def retryLater(): Unit =
    val ms = synchronized {
      backoff = if backoff == 0 then FirstBackoffMs else math.min(MaxBackoffMs, backoff * 2)
      backoff
    }
    once(ms, clearSkip = true)

  private def once(ms: Long, clearSkip: Boolean = false): Unit =
    val go = synchronized { if waiting then false else { waiting = true; true } }
    if go then later(() => {
      synchronized {
        waiting = false
        if clearSkip then skip = Set.empty
      }
      request()
    }, ms)

object ImportChain:
  enum Step:
    case Imported, Busy, Failed
  val BusyMs: Long = 5_000L
  val FirstBackoffMs: Long = 30_000L
  val MaxBackoffMs: Long = 30 * 60_000L

  /** The overflow files to import, oldest first across raw (`.txt`) and
    * small (`.rec`) batches (by the time in their names).
    */
  def order(names: Seq[String]): Seq[String] =
    names.filter(n => n.endsWith(OverflowFiles.Completed) || n.endsWith(OverflowFiles.CompletedRecord))
      .sortBy(n => (n.dropWhile(_ != '-'), n))
