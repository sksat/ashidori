package net.sksat.ashidori

import android.content.Context
import android.net.Uri
import java.io.{BufferedOutputStream, File, FileInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{CRC32, Deflater, ZipEntry, ZipOutputStream}
import scala.util.{Try, Using}
import ashidori.core.Archive

/** 「バックアップを書き出す」: the recorded data in one ZIP file the user
  * picks (release builds are not debuggable and `adb backup` skips apps for
  * Android 12+, so this is the way out).
  *
  * Contents: a copy of the SQLite database (`databases/ashidori.db`), the
  * Parquet archive (`files/archive/`), the lifecycle log, and a short
  * description. Not the basemap or venue data (downloaded again).
  *
  * Consistency: with the archive's locks held shared (no commit moves rows
  * from SQLite to the archive meanwhile), SQLite writes a consistent copy of
  * the database to app storage (VACUUM INTO, on a read-only connection: the
  * logger keeps writing), and every archive file is opened. Then the locks
  * are released and the ZIP is written from the copy and the open files,
  * outside any lock: a slow destination holds up nothing.
  *
  * Files are streamed through a fixed buffer, never parsed: memory stays
  * flat whatever their size. The database and the text are deflated at the
  * fastest level; Parquet files are compressed already and stored.
  */
object Backup:
  def defaultName(now: Long = System.currentTimeMillis()): String =
    val t = java.time.Instant.ofEpochMilli(now).atZone(AshidoriApp.zone)
    s"ashidori-backup-${t.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip"

  /** Writes the backup to `uri`; `progress(done, total)` in bytes now and
    * then. Returns the bytes read. Call off the main thread.
    */
  def write(c: Context, uri: Uri, progress: (Long, Long) => Unit): Long =
    val archive = AshidoriApp.archive
    val db = AshidoriApp.db
    val dir = File(c.getNoBackupFilesDir, "backup")
    ArchiveStore.ensureDir(dir)
    val snapshot = File(dir, "ashidori.db")
    val dbPath = File(db.getReadableDatabase.getPath)
    val need = dbPath.length + File(dbPath.getPath + "-wal").length
    if dir.getUsableSpace < need + (64L << 20) then
      throw java.io.IOException(s"空き容量が足りません（${need >> 20} MB 必要）")
    val opened = scala.collection.mutable.ArrayBuffer.empty[(String, File, FileInputStream)]
    try
      archive.reading(Archive.All*) {
        db.snapshotTo(snapshot)
        def walk(f: File): Seq[File] =
          if f.isDirectory then Option(f.listFiles()).toSeq.flatten.sortBy(_.getName).flatMap(walk) else Seq(f)
        for f <- walk(archive.root) if f.getName.endsWith(".parquet") do
          opened += (("files/archive/" + archive.root.toPath.relativize(f.toPath).toString, f, FileInputStream(f)))
      }
      val log = File(c.getFilesDir, "lifecycle.log")
      val total = snapshot.length + opened.map(_._2.length).sum + log.length
      var done = 0L
      var reported = 0L
      val buf = new Array[Byte](1 << 16)
      Using.resource(c.getContentResolver.openOutputStream(uri, "wt")) { raw =>
        val zip = ZipOutputStream(BufferedOutputStream(raw, 1 << 16))
        zip.setLevel(Deflater.BEST_SPEED)
        def copy(in: java.io.InputStream, name: String, time: Long): Unit =
          val e = ZipEntry(name)
          e.setTime(time)
          zip.putNextEntry(e)
          var n = in.read(buf)
          while n >= 0 do
            zip.write(buf, 0, n)
            done += n
            if done - reported >= (4 << 20) then
              reported = done
              progress(done, total)
            n = in.read(buf)
          zip.closeEntry()
        def stored(f: File, in: FileInputStream, name: String): Unit =
          // A stored entry needs its size and CRC up front: read the open file twice.
          val ch = in.getChannel
          val crc = CRC32()
          val bb = java.nio.ByteBuffer.wrap(buf)
          var pos = 0L
          var n = ch.read(bb, pos)
          while n > 0 do
            crc.update(buf, 0, n)
            pos += n
            bb.clear()
            n = ch.read(bb, pos)
          val e = ZipEntry(name)
          e.setTime(f.lastModified())
          e.setMethod(ZipEntry.STORED)
          e.setSize(pos)
          e.setCompressedSize(pos)
          e.setCrc(crc.getValue)
          zip.putNextEntry(e)
          var at = 0L
          while at < pos do
            bb.clear()
            val k = ch.read(bb, at)
            zip.write(buf, 0, k)
            at += k
            done += k
            if done - reported >= (4 << 20) then
              reported = done
              progress(done, total)
          zip.closeEntry()
        Using.resource(FileInputStream(snapshot))(in => copy(in, "databases/ashidori.db", System.currentTimeMillis()))
        for (name, f, in) <- opened do stored(f, in, name)
        if log.exists then Using.resource(FileInputStream(log))(in => copy(in, "files/lifecycle.log", log.lastModified()))
        val about =
          s"""ashidori backup
             |app ${BuildInfo.versionName} (${BuildInfo.versionCode})
             |written ${java.time.Instant.now()}
             |databases/ashidori.db: SQLite, a consistent copy (VACUUM INTO)
             |files/archive/<table>/<YYYY-MM[-DD]>.parquet: archived days and months
             |files/lifecycle.log: lifecycle log (no positions)
             |""".stripMargin
        zip.putNextEntry(ZipEntry("ashidori-backup.txt"))
        zip.write(about.getBytes(UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
      }
      progress(done, total)
      done
    finally
      opened.foreach(o => Try(o._3.close()))
      snapshot.delete()
