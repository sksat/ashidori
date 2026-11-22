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
  private val seq = java.util.concurrent.atomic.AtomicLong()
  @volatile private var undecodableSet = Set.empty[String]

  /** The overflow file of `r` (named now, written when needed, at most once:
    * [[OverflowFiles.Batch]]).
    */
  def batch(r: SmallRecord): OverflowFiles.Batch =
    val name = f"small-${System.currentTimeMillis()}%013d-${LoggerWrites.processTag}-${seq.incrementAndGet()}%06d${OverflowFiles.CompletedRecord}"
    OverflowFiles.Batch(dir.toPath, name, () => (SmallRecord.encode(r) + "\n").getBytes(UTF_8),
      d => ArchiveStore.syncDir(d.toFile), crash = LoggerWrites.debugCrash, prepare = () => ArchiveStore.ensureDir(dir))

  /** Writes `r` to its file now; throws if that fails (nothing is kept then). */
  def spill(r: SmallRecord): Unit = batch(r).write()

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
    case SmallRecord.PicsAdvs(rs)                  => db.insertPicsAdv(rs)

  /** Files waiting to be imported. */
  def waiting: Int = Option(dir.listFiles()).toSeq.flatten.count(_.getName.endsWith(OverflowFiles.CompletedRecord))

  /** Files that could not be read (kept, never deleted unread; reported once). */
  def undecodable(name: String): Boolean = undecodableSet(name)

  /** Imports one spilled batch (on the write thread). Busy while a backup
    * holds the lock.
    */
  def importOne(f: File): ImportChain.Step =
    val lock = LoggerWrites.Overflow.lock
    if !lock.tryLock() then ImportChain.Step.Busy
    else
      try
        val db = AshidoriApp.db
        val key = s"overflow|${f.getName}"
        val rec =
          try Some(SmallRecord.decode(String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8).trim))
          catch case NonFatal(e) =>
            undecodableSet += f.getName
            LifeLog.add(LifeKind.Note, s"退避した記録を読めず: ${f.getName}（${LifeLog.describe(e)}）")
            None
        rec match
          case None => ImportChain.Step.Failed
          case Some(r) =>
            OverflowImport.run(new OverflowImport.Ops:
              def marked = db.legacyImported(key) != 0
              def insertMarked(): Unit = db.importMarked(key)(apply(db, r))
              def deleteFile(): Boolean = f.delete() || !f.exists
              def syncDir(): Unit = ArchiveStore.syncDir(dir)
              def unmark(): Unit = db.legacyDone(key)
            ) match
              case OverflowImport.Result.Kept =>
                Log.w(s"overflow $f imported but not deleted; kept marked")
                ImportChain.Step.Failed
              case _ => ImportChain.Step.Imported
      catch case NonFatal(e) =>
        Log.w(s"overflow import of $f failed", e)
        Storage.noticeFailure(e, "退避ファイルの取り込み")
        ImportChain.Step.Failed
      finally lock.unlock()
