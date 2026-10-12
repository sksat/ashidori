package net.sksat.ashidori

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import scala.collection.mutable
import scala.util.{Try, Using}
import ashidori.core.*
import ashidori.core.parquet.*

/** Columnar archive of finished days.
  *
  * Today's data lives in SQLite (cheap appends, quick queries). Once a day
  * is over its rows are written to `<table>/YYYY-MM-DD.parquet`, read back to
  * verify, and only then deleted from SQLite. When a month is over, its day
  * files are merged into `<table>/YYYY-MM.parquet`. Reads cover both.
  * All maintenance runs on [[AshidoriApp.io]], serialized with DB writes.
  */
final class ArchiveStore(val root: File, zone: ZoneId):
  root.mkdirs()

  private val cache = new java.util.LinkedHashMap[String, ParquetReader.Table](16, 0.75f, true):
    override def removeEldestEntry(e: java.util.Map.Entry[String, ParquetReader.Table]): Boolean = size > 12

  private def file(rel: String) = File(root, rel)

  private def load(f: File): Option[ParquetReader.Table] =
    if !f.exists then None
    else
      cache.synchronized {
        Option(cache.get(f.getPath)).orElse {
          val t = Try(ParquetReader.readFile(f.getPath)).toOption
          t.foreach(cache.put(f.getPath, _))
          t
        }
      }

  private def invalidate(f: File): Unit = cache.synchronized(cache.remove(f.getPath))

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
          Option(dir.listFiles()).toSeq.flatten.filter(f => f.getName.length == "YYYY-MM-DD.parquet".length)
            .filter { f =>
              val d = LocalDate.parse(f.getName.take(10))
              !d.isBefore(first) && !d.isAfter(last)
            }
        else Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).map(d => file(Archive.dayFile(t, d))).filter(_.exists).toSeq
      months ++ days

  /** Archived rows of `t` with the time column (first) in [from, to), in time order. */
  def read(t: Archive.TableDef, from: Long, to: Long): Vector[IndexedSeq[Any]] =
    filesFor(t, from, to).flatMap(load).flatMap(_.rowIterator).filter { r =>
      val time = r(0).asInstanceOf[Long]
      time >= from && time < to
    }.toVector.sortBy(_(0).asInstanceOf[Long])

  /** Most recent archived row of `t`, if any. */
  def lastRow(t: Archive.TableDef): Option[IndexedSeq[Any]] =
    Option(File(root, t.name).listFiles()).toSeq.flatten.filter(_.getName.endsWith(".parquet"))
      .sortBy(_.getName).lastOption.flatMap(load).flatMap(_.rowIterator.toSeq.lastOption)

  /** Days that have archived rows of `t` (from file names). */
  def archivedFiles(t: Archive.TableDef): Seq[File] =
    Option(File(root, t.name).listFiles()).toSeq.flatten.filter(_.getName.endsWith(".parquet")).sortBy(_.getName)

  /** Writes rows atomically and verifies them by reading back. */
  private def writeVerified(t: Archive.TableDef, rows: Seq[Seq[Any]], dest: File): Unit =
    dest.getParentFile.mkdirs()
    val tmp = File(dest.getParentFile, dest.getName + ".tmp")
    Using.resource(BufferedOutputStream(FileOutputStream(tmp), 1 << 16)) { out =>
      val w = ParquetWriter(out, t.columns)
      rows.foreach(w.write)
      w.close()
    }
    val back = ParquetReader.readFile(tmp.getPath)
    if back.rows != rows.size then
      tmp.delete()
      throw IllegalStateException(s"archive verify failed for ${dest.getName}: ${back.rows} != ${rows.size}")
    if !tmp.renameTo(dest) then throw IllegalStateException(s"rename failed: $dest")
    invalidate(dest)

  /** Archives one finished day and removes it from SQLite. */
  def compactDay(db: Db, d: LocalDate): Int =
    val from = startOf(d)
    val to = startOf(d.plusDays(1))
    val byTable = mutable.LinkedHashMap.empty[Archive.TableDef, Vector[Seq[Any]]]
    for t <- Seq(Archive.Fixes, Archive.Motion, Archive.Battery, Archive.GnssStatus) do
      val rows = db.hotRows(t, from, to)
      if rows.nonEmpty then byTable(t) = rows
    // Raw lines -> typed rows; navigation messages take the previous record's time.
    var lastTime = from
    val raw = mutable.LinkedHashMap.empty[Archive.TableDef, mutable.ArrayBuffer[Seq[Any]]]
    for line <- db.rawLines(from, to) do
      GnssLogMerge.timeOf(line).foreach(lastTime = _)
      Archive.rowOf(line, lastTime).foreach((t, row) => raw.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += row)
    for (t, rows) <- raw do byTable(t) = rows.toVector
    var written = 0
    for (t, rows) <- byTable do
      val dest = file(Archive.dayFile(t, d))
      // Late data for an already archived day: merge with what is there.
      val existing = load(dest).toSeq.flatMap(_.rowIterator.map(_.toSeq))
      val all = (existing ++ rows).sortBy(_(0) match
        case l: Long => l
        case _       => Long.MaxValue
      )
      writeVerified(t, all, dest)
      written += rows.size
    db.deleteHot(from, to)
    written

  /** Merges the day files of finished months into one file per month. */
  def mergeMonths(today: LocalDate): Unit =
    val current = YearMonth.from(today)
    for t <- Archive.All do
      val days = archivedFiles(t).filter(_.getName.length == "YYYY-MM-DD.parquet".length)
      for (month, files) <- days.groupBy(f => YearMonth.parse(f.getName.take(7))) if month.isBefore(current) do
        val dest = file(Archive.monthFile(t, month))
        val rows = (load(dest).toSeq ++ files.flatMap(load)).flatMap(_.rowIterator.map(_.toSeq))
          .sortBy(r => Option(r(0)).map(_.asInstanceOf[Long]).getOrElse(Long.MaxValue))
        writeVerified(t, rows, dest)
        files.foreach { f => invalidate(f); f.delete() }

  /** Pre-Parquet text logs (one file per session or day) go into raw_lines,
    * from where the normal compaction archives them.
    */
  def migrateLegacyLogs(db: Db, dir: File): Int =
    val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt"))
    var n = 0
    for f <- files do
      Using.resource(scala.io.Source.fromFile(f, "UTF-8")) { src =>
        var last = f.lastModified()
        src.getLines().filterNot(_.startsWith("#")).filter(_.nonEmpty).grouped(5000).foreach { chunk =>
          val timed = chunk.map { l =>
            GnssLogMerge.timeOf(l).foreach(last = _)
            (last, l)
          }
          db.insertRawLines(timed)
          n += timed.size
        }
      }
      f.delete()
    n

  /** Runs everything that is due: migration, daily archive, monthly merge. */
  @volatile private var maintaining = false

  /** Archives finished days one at a time. `next` schedules the following
    * step; passing the I/O executor lets UI reads run in between instead of
    * waiting for a long first-time migration.
    */
  def maintain(db: Db, legacyDir: File, next: (() => Unit) => Unit = f => f()): Unit =
    if maintaining then return
    maintaining = true
    val today = LocalDate.now(zone)
    Try(migrateLegacyLogs(db, legacyDir)).failed.foreach(e => Log.w("legacy log migration failed", e))
    val days = db.oldestHot().map(day).filter(_.isBefore(today)).toList
      .flatMap(first => Iterator.iterate(first)(_.plusDays(1)).takeWhile(_.isBefore(today)).toList)
    def step(rest: List[LocalDate]): Unit = rest match
      case d :: tail =>
        Try(compactDay(db, d)).failed.foreach(e => Log.w(s"archiving $d failed; kept in SQLite", e))
        next(() => step(tail))
      case Nil =>
        try Try(mergeMonths(today)).failed.foreach(e => Log.w("monthly merge failed", e))
        finally maintaining = false
    try step(days)
    catch
      case e: Throwable =>
        maintaining = false
        throw e

  /** Total archive size in bytes (for the data screen). */
  def sizeBytes: Long =
    def walk(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(walk).sum else f.length
    walk(root)
