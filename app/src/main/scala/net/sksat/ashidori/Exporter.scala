package net.sksat.ashidori

import java.io.{BufferedOutputStream, File, FileOutputStream, FilterOutputStream, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.util.Using
import ashidori.core.*
import ashidori.core.parquet.ParquetWriter

/** Exports logged signals for a date range, from the archive and from what
  * is still in SQLite (today). Work goes a day at a time so a long range
  * does not need to fit in memory. Call off the main thread.
  */
object Exporter:
  private def startOf(d: LocalDate) = d.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
  private def days(from: LocalDate, to: LocalDate) = Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to))

  /** Raw lines of [a, b) still in SQLite, a chunk at a time, in time order:
    * (time, line).
    */
  private def hotLines(a: Long, b: Long): Iterator[(Long, String)] =
    val db = AshidoriApp.db
    ArchiveMerge.pages(a, c => db.rawLinesAfter(a, b, Long.MaxValue, c, ArchiveStore.ChunkLines),
      (r: (Long, Long, String)) => ArchiveMerge.Cursor(r._2, r._1)).flatten.map(r => (r._2, r._3))

  /** Rows of `t` for one day, archived or not, in time order and without
    * exact repeats, to `out`. Streams: a day of raw signals does not need to
    * fit in memory.
    */
  private def dayRows(t: Archive.TableDef, d: LocalDate)(out: IndexedSeq[Any] => Unit): Long =
    val (a, b) = (startOf(d), startOf(d.plusDays(1)))
    // No commit moves rows from SQLite to the archive while the day is read:
    // each row is read once, from one or the other.
    AshidoriApp.archive.reading(t) {
      val (archived, close) = AshidoriApp.archive.stream(t, a, b)
      try
        val hot =
          if Archive.RawTables.contains(t) then
            hotLines(a, b).flatMap((time, line) => scala.util.Try(Archive.rowOf(line, time)).toOption.flatten)
              .collect { case (`t`, row) => row.toIndexedSeq }
          else AshidoriApp.db.hotRows(t, a, b).iterator.map(_.toIndexedSeq)
        ArchiveMerge.merge(archived, hot, out)
      finally close()
    }

  /** The ZIP entry stays open when the Parquet writer is done with it. */
  private final class KeepOpen(out: OutputStream) extends FilterOutputStream(out):
    override def write(b: Array[Byte], off: Int, len: Int): Unit = out.write(b, off, len)
    override def close(): Unit = out.flush()

  /** One Parquet file per table (only tables with data), zipped.
    * `progress` gets a short status line now and then.
    */
  def parquetZip(from: LocalDate, to: LocalDate, dest: File, progress: String => Unit): Int =
    var tables = 0
    Using.resource(ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), 1 << 16))) { zip =>
      for t <- Archive.All do
        progress(s"${t.name} を書き出し中…")
        var w: Option[ParquetWriter] = None
        for d <- days(from, to) do
          dayRows(t, d) { row =>
            val writer = w.getOrElse {
              zip.putNextEntry(ZipEntry(s"${t.name}.parquet"))
              val nw = Archive.writer(t, KeepOpen(zip), ArchiveStore.groupRows(t))
              w = Some(nw)
              nw
            }
            writer.write(row)
          }
        w.foreach { writer =>
          writer.close()
          zip.closeEntry()
          tables += 1
        }
    }
    tables

  /** GNSS measurements, status, navigation messages, NMEA and IMU as one
    * GnssLogger-format text file (zipped; raw logs are large).
    */
  def gnssLogZip(from: LocalDate, to: LocalDate, dest: File, progress: String => Unit): Long =
    var lines = 0L
    Using.resource(ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), 1 << 16))) { zip =>
      zip.putNextEntry(ZipEntry(s"ashidori-gnss-$from-$to.txt"))
      val out = OutputStreamWriter(KeepOpen(zip), StandardCharsets.UTF_8)
      val version = AshidoriApp.app.getPackageManager.getPackageInfo(AshidoriApp.app.getPackageName, 0).versionName
      out.write(GnssLogFormat.header(version, s"Android ${android.os.Build.VERSION.RELEASE}",
        android.os.Build.MANUFACTURER, android.os.Build.MODEL))
      for d <- days(from, to) do
        progress(s"$d を書き出し中…")
        val (a, b) = (startOf(d), startOf(d.plusDays(1)))
        // Archived days come back as rows (streamed, merged in time order);
        // days not archived yet are still raw lines.
        AshidoriApp.archive.reading(Archive.RawTables*) {
          // Raw lines of an unfinished commit cannot be left out by a query
          // (rule C): the export waits for it (shown as 「整理待ち」).
          if AshidoriApp.archive.rawPending then throw ArchiveStore.Pending()
          val streams = Archive.RawTables.map(t => (t, AshidoriApp.archive.stream(t, a, b)))
          try
            val archived = streams.map((t, s) => s._1.map(r => (t, r)).map { case (t, r) => Vector[Any](r(0), Archive.lineOf(t, r)) })
              .foldLeft(Iterator.empty[IndexedSeq[Any]])(ArchiveStore.mergeSorted).map(_(1).asInstanceOf[String])
            for l <- archived ++ hotLines(a, b).map(_._2) do
              out.write(l)
              out.write("\n")
              lines += 1
          finally streams.foreach(_._2._2())
        }
      out.flush()
      zip.closeEntry()
    }
    lines
