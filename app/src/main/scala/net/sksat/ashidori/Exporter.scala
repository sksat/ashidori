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

  /** Rows of `t` for one day, archived or not, in time order. */
  private def dayRows(t: Archive.TableDef, d: LocalDate): Vector[Seq[Any]] =
    val (a, b) = (startOf(d), startOf(d.plusDays(1)))
    val db = AshidoriApp.db
    val archived = AshidoriApp.archive.read(t, a, b).map(_.toSeq)
    val hot =
      if Archive.RawTables.contains(t) then
        var last = a
        db.rawLines(a, b).flatMap { line =>
          GnssLogMerge.timeOf(line).foreach(last = _)
          Archive.rowOf(line, last).filter(_._1 == t).map(_._2)
        }
      else db.hotRows(t, a, b)
    (archived ++ hot).distinct.sortBy(_.headOption match
      case Some(l: Long) => l
      case _             => Long.MaxValue
    )

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
          val rows = dayRows(t, d)
          if rows.nonEmpty then
            val writer = w.getOrElse {
              zip.putNextEntry(ZipEntry(s"${t.name}.parquet"))
              val nw = ParquetWriter(KeepOpen(zip), t.columns)
              w = Some(nw)
              nw
            }
            rows.foreach(writer.write)
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
        // Archived days come back as rows; today is still raw lines.
        val archived = Archive.RawTables.flatMap { t =>
          AshidoriApp.archive.read(t, a, b).map(r => (r(0).asInstanceOf[Long], Archive.lineOf(t, r)))
        }.sortBy(_._1).map(_._2)
        val hot = AshidoriApp.db.rawLines(a, b)
        for l <- archived.iterator ++ hot.iterator do
          out.write(l)
          out.write("\n")
          lines += 1
      out.flush()
      zip.closeEntry()
    }
    lines
