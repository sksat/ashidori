package net.sksat.ashidori

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal
import ashidori.core.*

/** The logger's small rows ([[SmallRecord]]) that could not be saved now:
  * the write queue was full, or SQLite refused the batch after its retries.
  * Each batch goes to a file of its own in the raw-line overflow directory,
  * completed the same way (written to `.tmp`, synced, renamed without
  * replacing to `.rec`, the directory synced: [[OverflowFiles]]), so both
  * backups take it along under the same rules and manifest. It is imported
  * once SQLite takes writes again: the batch and a mark naming the
  * file in one transaction, then the file is deleted, then the mark
  * ([[OverflowImport]]). A crash at any point neither loses nor repeats it.
  */
object SmallOverflow:
  private def dir: File = LoggerWrites.Overflow.dir
  private var seq = 0L
  @volatile private var undecodable = Set.empty[String]

  /** Writes `r` to its file; throws if that fails (nothing is kept then). */
  def spill(r: SmallRecord): Unit = synchronized {
    ArchiveStore.ensureDir(dir)
    seq += 1
    // Completed only by the rename (never written again): see OverflowFiles.
    OverflowFiles.write(dir.toPath, f"small-${System.currentTimeMillis()}%013d-$seq%06d${OverflowFiles.CompletedRecord}",
      (SmallRecord.encode(r) + "\n").getBytes(UTF_8), d => ArchiveStore.syncDir(d.toFile))
  }

  /** Saves `r` in SQLite (the write thread's path and the import's). */
  def apply(db: Db, r: SmallRecord): Unit = r match
    case SmallRecord.Fixes(fs)                     => db.insertFixes(fs)
    case SmallRecord.Motion(ws)                    => db.insertMotion(ws)
    case SmallRecord.Battery(b, plan)              => db.insertBattery(b, plan)
    case SmallRecord.GnssStatus(t, v, u, top4, l5, env, acc) => db.insertGnssStatusRow(t, v, u, top4, l5, env, acc)
    case SmallRecord.RawSession(s, e, file, reason) => db.insertGnssSession(s, e, file, reason)
    case SmallRecord.Pressure(ps)                  => db.insertPressure(ps)
    case e: SmallRecord.Energy                     => db.applyEnergy(e)
    case SmallRecord.FixMsl(vs)                    => db.updateFixMsl(vs)

  /** Files waiting to be imported. */
  def waiting: Int = Option(dir.listFiles()).toSeq.flatten.count(_.getName.endsWith(OverflowFiles.CompletedRecord))

  /** Imports spilled batches, oldest first, while the queue is short (on
    * the write thread, as the raw-line overflow).
    */
  def importSome(pending: () => Long): Unit =
    val lock = LoggerWrites.Overflow.lock
    val files = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(OverflowFiles.CompletedRecord)).filterNot(f => undecodable(f.getName))
      .sortBy(_.getName)
    for f <- files.take(50) if pending() < WriteBacklog.MaxOtherRows / 2 && lock.tryLock() do
      try
        val db = AshidoriApp.db
        val key = s"overflow|${f.getName}"
        val rec =
          try Some(SmallRecord.decode(String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).trim))
          catch case NonFatal(e) =>
            // Kept as it is (never deleted unread); reported once.
            undecodable += f.getName
            LifeLog.add(LifeKind.Note, s"退避した記録を読めず: ${f.getName}（${LifeLog.describe(e)}）")
            None
        rec.foreach { r =>
          OverflowImport.run(new OverflowImport.Ops:
            def marked = db.legacyImported(key) != 0
            def insertMarked(): Unit = db.importMarked(key)(apply(db, r))
            def deleteFile(): Boolean = f.delete() || !f.exists
            def syncDir(): Unit = ArchiveStore.syncDir(dir)
            def unmark(): Unit = db.legacyDone(key)
          ) match
            case OverflowImport.Result.Kept => Log.w(s"overflow $f imported but not deleted; kept marked")
            case _ => ()
        }
      catch case NonFatal(e) => Log.w(s"overflow import of $f failed", e)
      finally lock.unlock()
