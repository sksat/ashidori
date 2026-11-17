package ashidori.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.util.Using
import ashidori.core.parquet.*

/** A commit left unfinished by 0.21.0 (or main before this branch), whose
  * files have the earlier layouts (fixes with 8 columns, battery with 5),
  * repaired after the upgrade: the chunk's rows, read from SQLite in the
  * current layout, are checked in the file's own layout, and a published
  * file that holds them verifies (never taken for a damaged one).
  */
class UpgradeRepairSuite extends munit.FunSuite:
  private val t0 = 1_760_000_000_000L

  private def file(columns: Seq[Column], rows: Seq[Seq[Any]]): File =
    val f = File.createTempFile("upgrade", ".parquet")
    f.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { o =>
      val w = ParquetWriter(o, columns, 100)
      rows.foreach(w.write)
      w.close()
    }
    f

  private def readRows(f: File) = Using.resource(ParquetReader.open(f.getPath))(_.rowIterator.toVector)

  /** As main archived: a day of fixes (previous rows and the chunk) in 8 columns,
    * its check recorded on 8-column rows; returns (file, target, chunk rows as
    * SQLite gives them now: 11 columns, the new ones null).
    */
  private def mainCommit(withCheck: Boolean) =
    val fixes = (0 until 300).map(i => Fix(t0 + i * 1000L, 35.0 + i * 1e-5, 139.0, 5f, 40.0, 1f, 90f, "gps"))
    val (prev, chunk) = fixes.splitAt(200)
    val old = Archive.Fixes.columns.take(8)
    val f = file(old, fixes.map(fx => Archive.fixRow(fx).take(8)))
    val sampler = ChunkSampler()
    chunk.foreach(fx => sampler.add(Archive.fixRow(fx).take(8).toIndexedSeq))
    val check = Option.when(withCheck)(sampler.check(CommitIntent.Prev.File(200, 1234), carried = 200, added = 100,
      range = Some((t0, t0 + 299 * 1000L))))
    val target = CommitIntent.Target("fixes/2025-10-09.parquet", 300, f.length, check)
    (f, target, chunk.map(fx => Archive.fixRow(fx).toIndexedSeq))

  test("a published file of the earlier layout verifies against SQLite's rows fitted to it") {
    val (f, target, sqlite) = mainCommit(withCheck = true)
    assert(sqlite.forall(_.size == 11))
    val fitted = RowBag()
    sqlite.foreach(r => fitted.add(CommitRepair.fit(r, 8)))
    assertEquals(CommitRepair.verify(target, 300, readRows(f).iterator, Some(fitted)), Right(()))
    // Unfitted (the rows as read now), the same file would be taken for a damaged one.
    val raw = RowBag()
    sqlite.foreach(raw.add)
    assert(CommitRepair.verify(target, 300, readRows(f).iterator, Some(raw)).isLeft)
  }

  test("a record of 0.21.0 (no check) completed after the upgrade: its samples in the file's layout") {
    val (f, target, sqlite) = mainCommit(withCheck = false)
    val sampler = ChunkSampler()
    sqlite.foreach(r => sampler.add(CommitRepair.fit(r, 8)))
    val completed = target.copy(check = Some(sampler.check(CommitIntent.Prev.Unknown, carried = 200, added = sampler.count, range = None)))
    val bag = RowBag()
    sqlite.foreach(r => bag.add(CommitRepair.fit(r, 8)))
    assertEquals(CommitRepair.verify(completed, 300, readRows(f).iterator, Some(bag)), Right(()))
  }

  test("a value the file has no column for still makes the check fail") {
    val (f, target, sqlite) = mainCommit(withCheck = true)
    val bag = RowBag()
    // A row of the chunk that has an MSL altitude now: not what the file holds.
    sqlite.zipWithIndex.foreach((r, i) => bag.add(if i == 5 then r.updated(9, 41.0) else r))
    val fitted = RowBag()
    sqlite.zipWithIndex.foreach((r, i) => fitted.add(CommitRepair.fit(if i == 5 then r.updated(9, 41.0) else r, 8)))
    // Not cut off: the file does not hold that value, so the row is not in it
    // and nothing of the chunk is deleted from SQLite.
    assert(CommitRepair.verify(target, 300, readRows(f).iterator, Some(fitted)).isLeft)
    assert(CommitRepair.verify(target, 300, readRows(f).iterator, Some(bag)).isLeft)
  }

  test("a month merge of battery day files of 5 columns into a month file of 11: the day rows fitted to the month's") {
    val days = (0 until 3).map(d => (0 until 50).map(i => Seq[Any](t0 + d * 86_400_000L + i * 300_000L, 80, 0, 1, "balanced")))
    // The month file as this version writes it: the current layout, the new columns null.
    val month = file(Archive.Battery.columns, days.flatten.map(r => CommitRepair.fit(r.toIndexedSeq, 11)))
    val sampler = ChunkSampler()
    days.flatten.foreach(r => sampler.add(CommitRepair.fit(r.toIndexedSeq, 11)))
    val target = CommitIntent.Target("battery/2025-10.parquet", 150, month.length,
      Some(sampler.check(CommitIntent.Prev.Absent, carried = 0, added = 150, range = Some((t0, t0 + 2 * 86_400_000L + 49 * 300_000L)))))
    val bag = RowBag()
    days.flatten.foreach(r => bag.add(CommitRepair.fit(r.toIndexedSeq, 11)))
    assertEquals(CommitRepair.verify(target, 150, readRows(month).iterator, Some(bag)), Right(()))
  }
