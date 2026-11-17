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
  * Parquet archive (`files/archive/`), raw lines spilled to files and not
  * imported yet (`files/overflow/`), the lifecycle log, and a short
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
  *
  * 「救出用バックアップ」 ([[writeRescue]]) works while an unfinished
  * commit stops the normal one ([[ArchiveStore.CommitStuck]]): it takes the
  * same copy of the database, but holds the locks without finishing the
  * commit, and stores every file as it is: the whole archive (with tmp
  * files and quarantined files), the commit record and the other state of
  * `files/maintenance/`, and all overflow files. It also holds the lock of
  * tmp writes ([[ArchiveStore.holdingTmps]]) until the ZIP is written, so
  * no tmp file is taken in the middle of being written.
  *
  * 「隔離したファイルを書き出す」 ([[writeQuarantine]]): only the files a
  * repair quarantined, and the list of them.
  *
  * Every ZIP ends with `manifest.txt`: SHA-256, size and name of each entry
  * as written.
  */
object Backup:
  /** Free space kept for the logger while a backup runs (hours of 全力ログ). */
  val LoggingMargin: Long = 256L << 20

  enum Mode:
    case Normal, Rescue, Quarantine

  def defaultName(now: Long = System.currentTimeMillis(), kind: String = "backup"): String =
    val t = java.time.Instant.ofEpochMilli(now).atZone(AshidoriApp.zone)
    s"ashidori-$kind-${t.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip"

  /** Writes the backup to `uri`; `progress(done, total)` in bytes now and
    * then. Returns the bytes read. Call off the main thread.
    */
  def write(c: Context, uri: Uri, progress: (Long, Long) => Unit): Long = writeZip(c, uri, progress, Mode.Normal)

  /** 「救出用バックアップ」: as [[write]], with every file as it is (see above). */
  def writeRescue(c: Context, uri: Uri, progress: (Long, Long) => Unit): Long = writeZip(c, uri, progress, Mode.Rescue)

  /** 「隔離したファイルを書き出す」 (see above). */
  def writeQuarantine(c: Context, uri: Uri, progress: (Long, Long) => Unit): Long = writeZip(c, uri, progress, Mode.Quarantine)

  // One at a time: they share the copy of the database.
  private def writeZip(c: Context, uri: Uri, progress: (Long, Long) => Unit, mode: Mode): Long = synchronized {
    val rescue = mode == Mode.Rescue
    val archive = AshidoriApp.archive
    val db = AshidoriApp.db
    val dir = File(c.getNoBackupFilesDir, "backup")
    ArchiveStore.ensureDir(dir)
    val snapshot = File(dir, "ashidori.db")
    val dbPath = File(db.getReadableDatabase.getPath)
    val quarantine = File(archive.root, ArchiveStore.QuarantineDir)
    // Measured (6.45 million raw lines, 728 MB database, logging meanwhile):
    // the copy is as large as the database, the WAL did not grow, and the
    // database in the ZIP (often on the same storage) is about 35% of it.
    // Asked for: the copy, half of it again for the ZIP, the archive and
    // overflow files (stored as they are), and room for the logger.
    def size(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(size).sum else f.length
    val db0 = dbPath.length + File(dbPath.getPath + "-wal").length
    val need =
      if mode == Mode.Quarantine then size(quarantine) + LoggingMargin
      else db0 + db0 / 2 + size(archive.root) + size(LoggerWrites.Overflow.dir) + LoggingMargin +
        (if rescue then size(Maintenance.dir) else 0L)
    if dir.getUsableSpace < need then
      throw java.io.IOException(s"空き容量不足（${need >> 20} MB 必要）")
    // While it runs: stop before the logger has no room left.
    def checkSpace(): Unit =
      if dir.getUsableSpace < LoggingMargin then
        throw java.io.IOException(s"空き容量が ${LoggingMargin >> 20} MB 未満のため中断（記録用の領域を確保）")
    val opened = scala.collection.mutable.ArrayBuffer.empty[(String, File, FileInputStream)]
    def walk(f: File): Seq[File] =
      if f.isDirectory then Option(f.listFiles()).toSeq.flatten.sortBy(_.getName).flatMap(walk)
      else if f.isFile then Seq(f)
      else Nil
    def under(dir: File, f: File) = dir.toPath.relativize(f.toPath).toString
    def collect(): Unit = mode match
      case Mode.Quarantine =>
        for f <- walk(quarantine) do opened += (("files/archive/" + under(archive.root, f), f, FileInputStream(f)))
        val list = File(Maintenance.dir, "attention")
        for in <- Try(FileInputStream(list)).toOption do opened += (("files/maintenance/attention", list, in))
      case _ =>
        // No overflow file is imported (and deleted) meanwhile: each one is
        // either in the copy with its import mark, or in the ZIP unimported.
        val overflow = LoggerWrites.Overflow
        overflow.lock.lock()
        try
          db.snapshotTo(snapshot)
          // A spill's `.tmp` (rescue only) is taken when it is a minute old:
          // left by a process that died, not one being written (the logger
          // is never made to wait for a backup).
          val now = System.currentTimeMillis()
          for f <- walk(overflow.dir)
              if f.getName.endsWith(".txt") || rescue && f.getName.endsWith(".tmp") && now - f.lastModified() > 60_000L
              in <- Try(FileInputStream(f)).toOption do
            opened += (("files/overflow/" + under(overflow.dir, f), f, in))
        finally overflow.lock.unlock()
        // Files a repair quarantined are kept whole (they may hold the only
        // copy of some rows); the rescue takes everything as it is.
        for f <- walk(archive.root) if rescue || f.getName.endsWith(".parquet") || f.toPath.startsWith(quarantine.toPath) do
          if !f.getName.startsWith(".lock-") then
            opened += (("files/archive/" + under(archive.root, f), f, FileInputStream(f)))
        if rescue then
          // Small state files; the transient copies of their atomic writes
          // (`*.tmp`) are not state. One gone since the listing is skipped.
          for f <- walk(Maintenance.dir) if !f.getName.endsWith(".tmp"); in <- Try(FileInputStream(f)).toOption do
            opened += (("files/maintenance/" + under(Maintenance.dir, f), f, in))
    def body(): Long =
      mode match
        // The rescue holds the locks without finishing an unfinished commit
        // (which may be what cannot be done).
        case Mode.Rescue => archive.holding(Archive.All*)(collect())
        case Mode.Normal => archive.reading(Archive.All*)(collect())
        case Mode.Quarantine => collect()
      val log = File(c.getFilesDir, "lifecycle.log")
      val withDb = mode != Mode.Quarantine
      val total = (if withDb then snapshot.length else 0L) + opened.map(_._2.length).sum + log.length
      var done = 0L
      var reported = 0L
      val buf = new Array[Byte](1 << 16)
      val manifest = StringBuilder()
      def note(name: String, bytes: Long, sha: java.security.MessageDigest): Unit =
        manifest.append(sha.digest().map(b => f"${b & 0xff}%02x").mkString).append("  ").append(bytes).append("  ").append(name).append('\n')
      Using.resource(c.getContentResolver.openOutputStream(uri, "wt")) { raw =>
        val zip = ZipOutputStream(BufferedOutputStream(raw, 1 << 16))
        zip.setLevel(Deflater.BEST_SPEED)
        def tick(): Unit =
          if done - reported >= (4 << 20) then
            reported = done
            checkSpace()
            progress(done, total)
        def copy(in: java.io.InputStream, name: String, time: Long): Unit =
          val sha = java.security.MessageDigest.getInstance("SHA-256")
          var bytes = 0L
          val e = ZipEntry(name)
          e.setTime(time)
          zip.putNextEntry(e)
          var n = in.read(buf)
          while n >= 0 do
            zip.write(buf, 0, n)
            sha.update(buf, 0, n)
            bytes += n
            done += n
            tick()
            n = in.read(buf)
          zip.closeEntry()
          note(name, bytes, sha)
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
          val sha = java.security.MessageDigest.getInstance("SHA-256")
          var at = 0L
          while at < pos do
            bb.clear()
            val k = ch.read(bb, at)
            if k <= 0 then throw java.io.IOException(s"$name changed while being written")
            zip.write(buf, 0, k)
            sha.update(buf, 0, k)
            at += k
            done += k
            tick()
          zip.closeEntry()
          note(name, pos, sha)
        if withDb then
          Using.resource(FileInputStream(snapshot))(in => copy(in, "databases/ashidori.db", System.currentTimeMillis()))
        // A state or overflow file may still change: deflated in one pass,
        // so its CRC is that of what was read. Archive files (tmp files
        // included, see above) do not change and are stored as they are.
        for (name, f, in) <- opened do
          if name.startsWith("files/maintenance/") || name.startsWith("files/overflow/") then copy(in, name, f.lastModified())
          else stored(f, in, name)
        if log.exists then Using.resource(FileInputStream(log))(in => copy(in, "files/lifecycle.log", log.lastModified()))
        val title = mode match
          case Mode.Normal     => "backup"
          case Mode.Rescue     => "rescue backup (files as they are)"
          case Mode.Quarantine => "quarantined files"
        val about =
          s"""ashidori $title
             |app ${BuildInfo.versionName} (${BuildInfo.versionCode})
             |written ${java.time.Instant.now()}
             |""".stripMargin + (if !withDb then "" else
            s"""databases/ashidori.db: SQLite, a consistent copy (VACUUM INTO)
               |files/archive/<table>/<YYYY-MM[-DD]>.parquet: archived days and months
               |files/overflow/raw-*.txt: raw lines not in the database yet (time TAB line)
               |""".stripMargin) +
            s"""files/archive/quarantine/<commit>/<table>/<file>: files a repair moved aside (may hold the only copy of some rows; Parquet, or `.tmp`: a new version that was not published)
               |files/lifecycle.log: lifecycle log (no positions)
               |manifest.txt: SHA-256, size and name of each entry above
               |""".stripMargin + (if !rescue then "" else
            s"""files/archive/**/*.tmp: new versions written by an unfinished archive commit
               |files/maintenance/commit: the unfinished commit's record (files to replace, rows to delete)
               |files/maintenance/commit-stuck, attention: the repair's state
               |commit-status.txt: the state when this was written
               |""".stripMargin)
        zip.putNextEntry(ZipEntry("ashidori-backup.txt"))
        zip.write(about.getBytes(UTF_8))
        zip.closeEntry()
        if mode != Mode.Normal then
          val status = CommitStatus.stuck().fold(
            if archive.commitPending then "unfinished commit recorded (not known to be stuck)\n" else "no unfinished commit\n")(
            ls => ("waiting for the user:" +: ls).mkString("", "\n", "\n")) +
            Attention.all().map(e => s"quarantined ${e.path} -> ${e.quarantined} onlyCopy=${e.onlyCopy} ${e.why}\n").mkString
          zip.putNextEntry(ZipEntry("commit-status.txt"))
          zip.write(status.getBytes(UTF_8))
          zip.closeEntry()
        zip.putNextEntry(ZipEntry("manifest.txt"))
        zip.write(manifest.toString.getBytes(UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
      }
      progress(done, total)
      done
    try
      // The rescue and the quarantine export: no tmp file is being written
      // while their files are collected and copied.
      if mode == Mode.Normal then body() else archive.holdingTmps(body())
    finally
      opened.foreach(o => Try(o._3.close()))
      snapshot.delete()
  }
