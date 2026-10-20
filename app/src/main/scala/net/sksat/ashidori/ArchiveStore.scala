package net.sksat.ashidori

import java.io.{BufferedOutputStream, File, FileOutputStream, RandomAccessFile}
import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import scala.collection.mutable
import scala.util.{Try, Using}
import ashidori.core.*
import ashidori.core.parquet.*

/** Columnar archive of finished days.
  *
  * Today's data lives in SQLite (cheap appends, quick queries). Once a day
  * is over its rows are written to `<table>/YYYY-MM-DD.parquet`, checked
  * against the file's footer, and only then deleted from SQLite. When a
  * month is over, the day files of the small tables are merged into
  * `<table>/YYYY-MM.parquet`; the raw signal tables keep their day files.
  * Reads cover both.
  *
  * Archiving runs in its own process (see [[Maintenance]]) and streams: a
  * day of raw lines is read from SQLite a chunk at a time and written row
  * group by row group, merged with what an earlier run archived, so memory
  * stays flat however long the day's detail log is. It only ever deletes
  * rows it has written and verified, and a run cut short (stopped, killed)
  * leaves either the old file or the new one plus the rows in SQLite: the
  * next run merges them again, dropping exact repeats.
  */
final class ArchiveStore(val root: File, zone: ZoneId):
  import ArchiveStore.*
  root.mkdirs()

  private def file(rel: String) = File(root, rel)

  private def day(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate
  private def startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant.toEpochMilli

  /** Files that may hold rows of `t` in [from, to). */
  private def filesFor(t: Archive.TableDef, from: Long, to: Long): Seq[File] =
    val dir = File(root, t.name)
    if !dir.exists then Nil
    else
      val first = day(math.max(from, 0L))
      val last = day(math.min(to, System.currentTimeMillis() + 86_400_000L) - 1)
      val months = Iterator.iterate(YearMonth.from(first))(_.plusMonths(1)).takeWhile(!_.isAfter(YearMonth.from(last)))
        .map(m => file(Archive.monthFile(t, m))).filter(_.exists).toSeq
      val days =
        if java.time.temporal.ChronoUnit.DAYS.between(first, last) > 400 then
          // Long ranges: list the directory instead of probing every day.
          Option(dir.listFiles()).toSeq.flatten.filter(f => isDayFile(f))
            .filter { f =>
              val d = LocalDate.parse(f.getName.take(10))
              !d.isBefore(first) && !d.isAfter(last)
            }
        else Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).map(d => file(Archive.dayFile(t, d))).filter(_.exists).toSeq
      months ++ days

  /** Archived rows of `t` with the time column (first) in [from, to), in
    * time order. Files are read a row group at a time, skipping groups
    * outside the window (by their time statistics), so only the rows asked
    * for are held, however large the file (a month of positions).
    */
  def read(t: Archive.TableDef, from: Long, to: Long): Vector[IndexedSeq[Any]] =
    filesFor(t, from, to).flatMap { f =>
      Try(Using.resource(ParquetReader.open(f.getPath))(_.rowsBetween(from, to).filter(inWindow(from, to)).toVector))
        .getOrElse(Vector.empty)
    }.toVector.sortBy(ArchiveMerge.key)

  private def inWindow(from: Long, to: Long)(r: IndexedSeq[Any]): Boolean =
    val k = ArchiveMerge.key(r)
    k >= from && k < to

  /** Archived rows of `t` in [from, to) in time order, read a row group at
    * a time (for exports of the large raw tables). Close the iterator's
    * files with `close`.
    */
  def stream(t: Archive.TableDef, from: Long, to: Long): (Iterator[IndexedSeq[Any]], () => Unit) =
    val readers = filesFor(t, from, to).flatMap(f => Try(ParquetReader.open(f.getPath)).toOption)
    val sorted = readers.map(_.rowsBetween(from, to).filter(inWindow(from, to)))
      .foldLeft(Iterator.empty[IndexedSeq[Any]])((a, b) => mergeSorted(a, b))
    (sorted, () => readers.foreach(r => Try(r.close())))

  /** Most recent archived row of `t`, if any. */
  def lastRow(t: Archive.TableDef): Option[IndexedSeq[Any]] =
    archivedFiles(t).lastOption.flatMap { f =>
      Try(Using.resource(ParquetReader.open(f.getPath)) { r =>
        if r.groups == 0 then None else r.readGroup(r.groups - 1).rowIterator.toSeq.lastOption
      }).toOption.flatten
    }

  /** Days that have archived rows of `t` (from file names). */
  def archivedFiles(t: Archive.TableDef): Seq[File] =
    Option(File(root, t.name).listFiles()).toSeq.flatten.filter(_.getName.endsWith(".parquet")).sortBy(_.getName)

  // --- the archive lock -----------------------------------------------------------

  private val lockFile = File(root, ".lock")
  private val localLock = java.util.concurrent.locks.ReentrantLock()

  /** Runs `f` holding the archive lock, shared by the processes: changes
    * that must be seen together (an archive file replaced and its rows
    * deleted from SQLite) are made under it, and a backup copies under it.
    */
  def withLock[A](f: => A): A =
    localLock.lock()
    try
      Using.resource(RandomAccessFile(lockFile, "rw")) { raf =>
        val l = raf.getChannel.lock()
        try f
        finally l.release()
      }
    finally localLock.unlock()

  // --- writing --------------------------------------------------------------------

  /** One archive file being rewritten: the existing rows (streamed) merged
    * with the new ones into `<dest>.tmp`.
    */
  private final class Rewrite(val t: Archive.TableDef, val dest: File):
    dest.getParentFile.mkdirs()
    val tmp = File(dest.getParentFile, dest.getName + ".tmp")
    private val existing = if dest.exists then Some(ParquetReader.open(dest.getPath)) else None
    private val fos = FileOutputStream(tmp)
    private val out = BufferedOutputStream(fos, 1 << 16)
    private val writer = ParquetWriter(out, t.columns, groupRows(t))
    // Archived groups wholly before the new rows are copied as stored.
    val merge = ArchiveMerge.FileMerge(existing, writer)
    // New rows come in the order of their stored time, which for some raw
    // lines is seconds off the row's own: put them in order first.
    private val reorder = ArchiveMerge.Reorder(ReorderMs, merge.push)
    private var closed = false

    /** A new row; `watermark`: the stored time the input has reached. */
    def push(r: IndexedSeq[Any], watermark: Long): Unit = reorder.push(r, watermark)

    /** Rows that came later than the reorder window (written out of order). */
    def late: Long = reorder.late

    /** Writes the rest and checks the row count in the new file's footer. */
    def finish(): Long =
      reorder.flush()
      if reorder.late > 0 then Log.w(s"${t.name}/${dest.getName}: ${reorder.late} rows later than the reorder window")
      merge.finish()
      writer.close()
      // On disk before anything depends on it (the rename, then deleting
      // the rows from SQLite).
      out.flush()
      fos.getFD.sync()
      close()
      val n = ParquetReader.meta(tmp.getPath).rows
      if n != merge.written then
        throw IllegalStateException(s"archive verify failed for ${t.name}/${dest.getName}: $n != ${merge.written}")
      n

    /** Replaces the archive file with the new one (atomically: a reader or
      * an upload that opened the old file keeps reading it whole).
      */
    def commit(): Unit =
      java.nio.file.Files.move(tmp.toPath, dest.toPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      // The rename itself survives a power loss only once the directory is synced.
      syncDir(dest.getParentFile)

    def close(): Unit =
      if !closed then
        closed = true
        Try(out.close())
        existing.foreach(e => Try(e.close()))

    def abort(): Unit =
      close()
      tmp.delete()

  /** Archives one finished day: the small tables at once, the raw lines in
    * portions of about `portionMs` each (every portion is written, verified
    * and deleted from SQLite before the next). Ends early, not done, at
    * `until` or at `stop`, after writing out the portion in progress;
    * what was archived so far stays archived, and the
    * rest is done by the next run.
    */
  def compactDay(db: Db, d: LocalDate, stop: () => Boolean, progress: String => Unit, until: Long = Long.MaxValue,
                 portionMs: Long = PortionMs, chunk: Int = ChunkLines): DayResult =
    val from = startOf(d)
    val to = startOf(d.plusDays(1))
    // Rows inserted from here on (rowid above these) are left for the next run.
    val max = db.maxRowids()
    // Leftovers of a run that died while writing.
    for t <- Archive.All do File(root, Archive.dayFile(t, d) + ".tmp").delete()
    for t <- Seq(Archive.Fixes, Archive.Motion, Archive.Battery, Archive.GnssStatus) if !stop() do
      val table = t.name
      val rows = db.hotRows(t, from, to, max(table))
      if rows.nonEmpty then
        progress(s"$d ${t.name}")
        val rw = Rewrite(t, file(Archive.dayFile(t, d)))
        try
          rows.foreach { r =>
            val row = r.toIndexedSeq
            rw.push(row, ArchiveMerge.key(row))
          }
          rw.finish()
          withLock {
            // Exactly the rows written, or nothing: a row changed meanwhile
            // (deleted by the user) leaves the day for the next run.
            val now = db.countArchived(table, from, to, max(table))
            if now != rows.size then throw IllegalStateException(s"$table $d changed while archiving: $now != ${rows.size}")
            rw.commit()
            db.deleteArchived(table, from, to, max(table))
          }
        catch
          case e: Throwable =>
            rw.abort()
            throw e
    var done = false
    var at = ArchiveMerge.Cursor(from, Long.MinValue)
    var total = 0L
    var unconverted = 0L
    while !done && !stop() && System.currentTimeMillis() < until do
      // One portion: chunks until the time is up or the day is read.
      val open = mutable.LinkedHashMap.empty[Archive.TableDef, Rewrite]
      // Lines that became no row stay in SQLite: never delete what was not archived.
      val kept = mutable.ArrayBuffer.empty[Long]
      val portionEnd = math.min(until, System.currentTimeMillis() + portionMs)
      var read = 0L
      try
        var more = true
        while more && System.currentTimeMillis() < portionEnd && read < PortionLines && !stop() do
          val lines = db.rawLinesAfter(from, to, max("raw_lines"), at, chunk)
          if lines.isEmpty then more = false
          else
            for (rowid, time, line) <- lines do
              // A line without a time of its own (navigation messages) has
              // the preceding record's, stored with it.
              Try(Archive.rowOf(line, time)) match
                case scala.util.Success(Some((t, row))) if row.size == t.columns.size =>
                  open.getOrElseUpdate(t, Rewrite(t, file(Archive.dayFile(t, d)))).push(row.toIndexedSeq, time)
                case _ =>
                  // An unknown kind or a malformed line.
                  kept += rowid
                  if kept.size > MaxKept then
                    throw IllegalStateException(s"$d: over $MaxKept raw lines of no table; kept in SQLite")
            val (rowid, time, _) = lines.last
            at = ArchiveMerge.Cursor(time, rowid)
            read += lines.size
            if lines.size < chunk then more = false
            progress(s"$d 詳細ログ ${total + read} 行")
        // Stopped (by the OS) or not: what was read is written out, so every
        // run makes progress however short the time it is given.
        if !more then done = true
        if read > 0 then
            for rw <- open.values do rw.finish()
            withLock {
              val now = db.countRawUpTo(from, to, max("raw_lines"), at)
              if now != read then throw IllegalStateException(s"raw lines of $d changed while archiving: $now != $read")
              open.values.foreach(_.commit())
              db.deleteRawUpTo(from, to, max("raw_lines"), at, except = kept.toSeq)
            }
            total += read - kept.size
            unconverted += kept.size
      catch
        case e: Throwable =>
          open.values.foreach(_.abort())
          throw e
    DayResult(done, total, unconverted)

  /** Merges the day files of the small tables of finished months into one
    * file per month (row group by row group). The raw tables keep their day
    * files: a month of them would be too large to export or upload as one.
    */
  def mergeMonths(today: LocalDate, stop: () => Boolean = () => false): Unit =
    val current = YearMonth.from(today)
    for t <- MonthTables if !stop() do
      val days = archivedFiles(t).filter(isDayFile)
      for (month, files) <- days.groupBy(f => YearMonth.parse(f.getName.take(7))).toSeq.sortBy(_._1) if month.isBefore(current) && !stop() do
        val rw = Rewrite(t, file(Archive.monthFile(t, month)))
        try
          val readers = files.sortBy(_.getName).map(f => ParquetReader.open(f.getPath))
          try readers.foreach(_.rowIterator.foreach(r => rw.push(r, ArchiveMerge.key(r))))
          finally readers.foreach(r => Try(r.close()))
          rw.finish()
          withLock {
            rw.commit()
            files.foreach(_.delete())
          }
        catch
          case e: Throwable =>
            rw.abort()
            throw e

  /** Pre-Parquet text logs (one file per session or day) go into raw_lines,
    * from where the normal compaction archives them.
    */
  def migrateLegacyLogs(db: Db, dir: File): Int =
    val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt"))
    var n = 0
    for f <- files do
      withLock {
        // Each chunk is inserted with the count of lines imported so far, in
        // one transaction: an import cut short resumes after the last chunk.
        val key = s"${f.getName}|${f.length}|${f.lastModified}"
        var done = db.legacyImported(key)
        Using.resource(scala.io.Source.fromFile(f, "UTF-8")) { src =>
          var last = f.lastModified()
          var seen = 0L
          src.getLines().filterNot(_.startsWith("#")).filter(_.nonEmpty).grouped(5000).foreach { chunk =>
            val timed = chunk.map { l =>
              GnssLogMerge.timeOf(l).foreach(last = _)
              (last, l)
            }
            val fresh = timed.drop(math.max(0L, done - seen).toInt)
            seen += timed.size
            if fresh.nonEmpty then
              done = seen
              db.insertLegacyChunk(fresh, key, done)
              n += fresh.size
          }
        }
        f.delete()
        db.legacyDone(key)
      }
    n

  /** Days with rows still in SQLite that are ready to archive: over for at
    * least [[SettleMs]], so no late batch of the logger can still arrive.
    */
  def dueDays(db: Db, now: Long): List[LocalDate] =
    val today = day(now)
    db.oldestHot().map(day).toList.flatMap { first =>
      Iterator.iterate(first)(_.plusDays(1))
        .takeWhile(d => d.isBefore(today) && startOf(d.plusDays(1)) + SettleMs <= now).toList
    }

  /** Total archive size in bytes (for the data screen). */
  def sizeBytes: Long =
    def walk(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(walk).sum else f.length
    walk(root)

object ArchiveStore:
  /** How a day's archiving ended: `done`, or more is left; raw lines
    * archived, and lines that had no row (malformed or of no table).
    */
  final case class DayResult(done: Boolean, lines: Long, unconverted: Long)

  /** Raw lines read from SQLite at a time (a few hundred bytes each: well
    * inside one 2 MB cursor window).
    */
  val ChunkLines = 4000
  /** How far out of order new rows may come (see [[ArchiveMerge.Reorder]]):
    * gyroscope and magnetometer lines stored by earlier versions are up to
    * a few seconds off.
    */
  val ReorderMs: Long = 60_000L
  /** Raw lines of no table kept in SQLite per portion; more fails the day. */
  val MaxKept = 10_000
  /** One portion of a long day: written, verified and deleted from SQLite
    * before the next. Each portion rewrites the day's files merged with what
    * is archived already, so shorter portions cost more in all; they are
    * bounded so that a job stopped by the OS finishes its portion quickly.
    */
  val PortionMs: Long = 2 * 60_000L
  val PortionLines: Long = 2_000_000L
  /** A day is archived once it has been over this long. */
  val SettleMs: Long = 3_600_000L

  val MonthTables: Seq[Archive.TableDef] = Seq(Archive.Fixes, Archive.Motion, Archive.Battery, Archive.GnssStatus)

  /** fsync of a directory (a rename in it is durable after this). */
  def syncDir(d: File): Unit =
    Try {
      val fd = android.system.Os.open(d.getPath, android.system.OsConstants.O_RDONLY, 0)
      try android.system.Os.fsync(fd)
      finally android.system.Os.close(fd)
    }.failed.foreach(e => Log.w(s"fsync of $d failed", e))

  def isDayFile(f: File): Boolean = f.getName.length == "YYYY-MM-DD.parquet".length && f.getName.endsWith(".parquet")

  /** Rows per row group: about 400,000 values, so a group buffered by the
    * writer (boxed values) or decoded by the reader stays around 10 MB.
    */
  def groupRows(t: Archive.TableDef): Int = (400_000 / t.columns.size).max(2048).min(50_000)

  /** Two time-ordered iterators merged (no deduplication). */
  def mergeSorted(a: Iterator[IndexedSeq[Any]], b: Iterator[IndexedSeq[Any]]): Iterator[IndexedSeq[Any]] =
    if !a.hasNext then b
    else if !b.hasNext then a
    else
      val x = a.buffered
      val y = b.buffered
      new Iterator[IndexedSeq[Any]]:
        def hasNext = x.hasNext || y.hasNext
        def next() =
          if !y.hasNext then x.next()
          else if !x.hasNext then y.next()
          else if ArchiveMerge.key(x.head) <= ArchiveMerge.key(y.head) then x.next()
          else y.next()
