package net.sksat.ashidori

import android.content.Context
import android.net.Uri
import java.io.{BufferedOutputStream, File, FileInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{CRC32, Deflater, ZipEntry, ZipOutputStream}
import scala.util.Using

/** 「バックアップを書き出す」: the recorded data as it is on the device, in
  * one ZIP file the user picks (release builds are not debuggable and
  * `adb backup` skips apps for Android 12+, so this is the way out).
  *
  * Contents: the SQLite database (`databases/ashidori.db` and its WAL), the
  * Parquet archive (`files/archive/`), the lifecycle log, and a short
  * description. Not the basemap or venue data (downloaded again).
  *
  * Files are streamed one at a time through a fixed buffer, never parsed:
  * memory stays flat whatever their size. The database and the text
  * compress well and are deflated at the fastest level; Parquet files are
  * compressed already and stored.
  *
  * Consistency while the logger keeps writing: the archive lock is held
  * throughout, so archiving (another process) neither replaces a file nor
  * deletes rows meanwhile; the database is copied by [[Db.copyForBackup]]
  * (checkpoint, main file without a lock, WAL under the write lock). The
  * main file is read through the one descriptor [[Db.mainFile]] keeps open:
  * closing a descriptor of it would drop this process's SQLite locks.
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
    archive.withLock {
      val dbFile = File(db.getReadableDatabase.getPath)
      val archived = walk(archive.root).filter(f => f.getName.endsWith(".parquet"))
      val log = File(c.getFilesDir, "lifecycle.log")
      val total = dbFile.length + File(dbFile.getPath + "-wal").length + archived.map(_.length).sum + log.length
      var done = 0L
      val buf = new Array[Byte](1 << 16)
      var reported = 0L
      Using.resource(c.getContentResolver.openOutputStream(uri, "wt")) { raw =>
        val zip = ZipOutputStream(BufferedOutputStream(raw, 1 << 16))
        zip.setLevel(Deflater.BEST_SPEED)
        def copy(f: File, name: String, store: Boolean): Unit =
          if f.exists then
            val e = ZipEntry(name)
            e.setTime(f.lastModified())
            if store then
              // A stored entry needs its size and CRC up front.
              val crc = CRC32()
              Using.resource(FileInputStream(f)) { in =>
                var n = in.read(buf)
                while n >= 0 do
                  crc.update(buf, 0, n)
                  n = in.read(buf)
              }
              e.setMethod(ZipEntry.STORED)
              e.setSize(f.length)
              e.setCompressedSize(f.length)
              e.setCrc(crc.getValue)
            zip.putNextEntry(e)
            Using.resource(FileInputStream(f)) { in =>
              var n = in.read(buf)
              while n >= 0 do
                zip.write(buf, 0, n)
                done += n
                if done - reported >= (4 << 20) then
                  reported = done
                  progress(done, total)
                n = in.read(buf)
            }
            zip.closeEntry()
        def copyChannel(ch: java.nio.channels.FileChannel, name: String): Unit =
          val e = ZipEntry(name)
          e.setTime(dbFile.lastModified())
          zip.putNextEntry(e)
          val bb = java.nio.ByteBuffer.wrap(buf)
          val size = ch.size
          var pos = 0L
          while pos < size do
            bb.clear()
            val n = ch.read(bb, pos)
            if n < 0 then pos = size
            else
              zip.write(buf, 0, n)
              pos += n
              done += n
              if done - reported >= (4 << 20) then
                reported = done
                progress(done, total)
          zip.closeEntry()
        db.copyForBackup(
          main => copyChannel(main, "databases/ashidori.db"),
          wal => copy(wal, "databases/ashidori.db-wal", store = false))
        for f <- archived do
          copy(f, "files/archive/" + archive.root.toPath.relativize(f.toPath).toString, store = true)
        copy(log, "files/lifecycle.log", store = false)
        val about =
          s"""ashidori backup
             |app ${BuildInfo.versionName} (${BuildInfo.versionCode})
             |written ${java.time.Instant.now()}
             |databases/ashidori.db (+ -wal): SQLite, open with the WAL next to it
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
    }

  private def walk(f: File): Seq[File] =
    if f.isDirectory then Option(f.listFiles()).toSeq.flatten.sortBy(_.getName).flatMap(walk) else Seq(f)
