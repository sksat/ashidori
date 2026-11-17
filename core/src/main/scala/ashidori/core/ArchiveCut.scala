package ashidori.core

import scala.util.Using
import ashidori.core.parquet.*

/** Cutting a range the user deleted out of an archive file: the only way
  * archived rows are removed on purpose (the app's ArchiveStore.deleteRange
  * commits the result like any archive commit).
  */
object ArchiveCut:
  /** The row as it stays after cutting [from, to) out: none, itself, or (a
    * state span crossing an edge) its parts outside ([[EnergyLog.clip]]).
    */
  def cutRow(t: Archive.TableDef, r: IndexedSeq[Any], from: Long, to: Long): Seq[IndexedSeq[Any]] =
    if t == Archive.EnergyStates then
      val s = Archive.energyOf(r)
      val row = EnergyRow(s.start, Some(s.end), s.state, !s.endKnown, s.boot, s.seq)
      val parts = EnergyLog.clip(Seq(row), Seq((from, to)), Long.MaxValue)
      if parts == Seq(row) then Seq(r) else parts.map(p => Archive.energyRow(p.span).toIndexedSeq)
    else
      val k = ArchiveMerge.key(r)
      if k >= from && k < to then Nil else Seq(r)

  /** Whether the file at `path` has a row of [from, to). */
  def touches(t: Archive.TableDef, path: String, from: Long, to: Long): Boolean =
    Using.resource(Archive.open(t, path, whole = true))(_.rowIterator.exists(r => cutRow(t, r, from, to) != Seq(r)))

  /** What a cut wrote: the rows kept and a running hash of them, in order. */
  final case class Written(rows: Long, hash: Long)

  private def step(h: Long, r: IndexedSeq[Any]): Long = h * 31 + ArchiveMerge.hash(r)

  /** Writes the file at `path` without [from, to) to `out` (in the current
    * layout, with its footer), in time order. Does not close `out`.
    *
    * Only state spans change their start when cut (to the range's end), so
    * that two spans of the same start, `[0,100)` and `[0,200)` minus
    * `[40,80)`, would come out as 0, 80, 0, 80: their rows (a file of them
    * is small) are sorted again, by start then as they were. Every other
    * table only loses rows, which keeps its order.
    */
  def write(t: Archive.TableDef, path: String, out: java.io.OutputStream, groupRows: Int, from: Long, to: Long): Written =
    var kept = 0L
    var hash = 17L
    val w = Archive.writer(t, out, groupRows)
    def put(k: IndexedSeq[Any]): Unit =
      w.write(k)
      kept += 1
      hash = step(hash, k)
    Using.resource(Archive.open(t, path, whole = true)) { r =>
      if t == Archive.EnergyStates then
        r.rowIterator.flatMap(cutRow(t, _, from, to)).toVector.sortBy(ArchiveMerge.key).foreach(put)
      else r.rowIterator.foreach(row => cutRow(t, row, from, to).foreach(put))
    }
    w.close()
    Written(kept, hash)

  /** The new version at `path`, read back whole: Left if its footer, its
    * rows (count, order, every column) or a row left in the range differ
    * from what was written.
    */
  def check(t: Archive.TableDef, path: String, written: Written, from: Long, to: Long): Either[String, Unit] =
    val footer = ParquetReader.meta(path).rows
    if footer != written.rows then return Left(s"${written.rows} rows written, $footer in the footer")
    var back = 17L
    var n = 0L
    var left = false
    var last = Long.MinValue
    var unordered = false
    Using.resource(Archive.open(t, path)) { r =>
      r.rowIterator.foreach { row =>
        if cutRow(t, row, from, to) != Seq(row) then left = true
        val k = ArchiveMerge.key(row)
        if k < last then unordered = true
        last = k
        back = step(back, row)
        n += 1
      }
    }
    // A repair checks a published file's order: it must hold before publishing.
    if unordered then Left("rows not in time order")
    else if left then Left("a row of the range is left")
    else if n != written.rows || back != written.hash then Left("does not read back as written")
    else Right(())

  /** The commit record's target of the new version: it carries `rows` of
    * the previous file and adds none ([[CommitRepair.verify]] checks it so).
    */
  def target(rel: String, prev: CommitIntent.Prev, written: Written, size: Long, path: String): CommitIntent.Target =
    val m = ParquetReader.meta(path)
    val range = Option.when(m.groups.nonEmpty && m.groups.forall(_.time.isDefined))(
      (m.groups.map(_.time.get._1).min, m.groups.map(_.time.get._2).max))
    CommitIntent.Target(rel, written.rows, size, Some(CommitIntent.Check(prev, carried = written.rows, added = 0, range, None, Nil)))
