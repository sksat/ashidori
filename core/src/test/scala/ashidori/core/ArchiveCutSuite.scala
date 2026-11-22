package ashidori.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.util.Using
import ashidori.core.parquet.*

/** Cutting a deleted range out of an archive file: only the range goes, the
  * new version reads back as written, and the commit's check holds for it.
  */
class ArchiveCutSuite extends munit.FunSuite:
  private val t0 = 1_760_000_000_000L
  private val Min = 60_000L

  private def file(columns: Seq[Column], rows: Seq[Seq[Any]], meta: Seq[(String, String)] = Nil): File =
    val f = File.createTempFile("cut", ".parquet")
    f.deleteOnExit()
    Using.resource(BufferedOutputStream(FileOutputStream(f))) { o =>
      val w = ParquetWriter(o, columns, 100, meta)
      rows.foreach(w.write)
      w.close()
    }
    f

  private def cutTo(t: Archive.TableDef, src: File, from: Long, to: Long): (File, ArchiveCut.Written) =
    val out = File.createTempFile("cut", ".tmp")
    out.deleteOnExit()
    val w = Using.resource(BufferedOutputStream(FileOutputStream(out)))(o => ArchiveCut.write(t, src.getPath, o, 100, from, to))
    (out, w)

  private def rows(t: Archive.TableDef, f: File) = Using.resource(Archive.open(t, f.getPath))(_.rowIterator.toVector)

  test("only the rows of the range go; a file of 0.21.0 is rewritten in the current layout") {
    // Fixes as 0.21.0 wrote them: 8 columns, no footer.
    val fs = (0 until 300).map(i => Seq[Any](t0 + i * Min, 35.0, 139.0, 5f, 40.0, 1f, 90f, "gps"))
    val src = file(Archive.Fixes.columns.take(8), fs)
    val (from, to) = (t0 + 100 * Min, t0 + 200 * Min)
    assert(ArchiveCut.touches(Archive.Fixes, src.getPath, from, to))
    assert(!ArchiveCut.touches(Archive.Fixes, src.getPath, t0 + 400 * Min, t0 + 500 * Min))
    val (out, w) = cutTo(Archive.Fixes, src, from, to)
    assertEquals(w.rows, 200L)
    assertEquals(ArchiveCut.check(Archive.Fixes, out.getPath, w, from, to), Right(()))
    val back = rows(Archive.Fixes, out)
    assertEquals(back.map(r => r(0).asInstanceOf[Long]), (0 until 100).map(i => t0 + i * Min) ++ (200 until 300).map(i => t0 + i * Min))
    assertEquals(back.head.take(8), fs.head.toIndexedSeq)
    assertEquals(ParquetReader.meta(out.getPath).keyValues.get(Archive.TableKey), Some("fixes"))
    // The commit's check of the new version: carried rows, none added.
    val prev = CommitIntent.Prev.File(300, src.length)
    val target = ArchiveCut.target("fixes/x.parquet", prev, w, out.length, out.getPath)
    assertEquals(CommitRepair.verify(target, 200, rows(Archive.Fixes, out).iterator), Right(()))
    // A repair tells the versions apart: the new one published, the old one not.
    assertEquals(CommitRepair.classify(target, Some((200L, out.length)), quarantined = false), CommitRepair.State.Published)
    assertEquals(CommitRepair.classify(target, Some((300L, src.length)), quarantined = false), CommitRepair.State.NotPublished)
    // The record round-trips.
    val intent = CommitIntent(Seq(target), CommitIntent.Delete.Files(Nil))
    assertEquals(CommitIntent.decode(CommitIntent.encode(intent)), Some(intent))
  }

  test("a state span crossing an edge keeps its parts outside the range") {
    val s = EnergyState(true, 1000, "gps", false, false, true, false, "balanced", "balanced", "Walking", "Outdoor")
    val spans = Seq(EnergySpan(t0, t0 + 50 * Min, s), EnergySpan(t0 + 50 * Min, t0 + 70 * Min, s.copy(screenOn = false)),
      EnergySpan(t0 + 70 * Min, t0 + 90 * Min, s, endKnown = false))
    val src = file(Archive.EnergyStates.columns, spans.map(Archive.energyRow))
    val (out, w) = cutTo(Archive.EnergyStates, src, t0 + 40 * Min, t0 + 80 * Min)
    assertEquals(ArchiveCut.check(Archive.EnergyStates, out.getPath, w, t0 + 40 * Min, t0 + 80 * Min), Right(()))
    assertEquals(rows(Archive.EnergyStates, out).map(Archive.energyOf),
      Vector(EnergySpan(t0, t0 + 40 * Min, s), EnergySpan(t0 + 80 * Min, t0 + 90 * Min, s, endKnown = false)))
  }

  test("two spans of the same start, [0,100) and [0,200) minus [40,80): the result is in time order") {
    val s = EnergyState(true, 1000, "gps", false, false, true, false, "balanced", "balanced", "Walking", "Outdoor")
    val spans = Seq(EnergySpan(t0, t0 + 100 * Min, s, endKnown = false), EnergySpan(t0, t0 + 200 * Min, s))
    val src = file(Archive.EnergyStates.columns, spans.map(Archive.energyRow))
    val (out, w) = cutTo(Archive.EnergyStates, src, t0 + 40 * Min, t0 + 80 * Min)
    assertEquals(ArchiveCut.check(Archive.EnergyStates, out.getPath, w, t0 + 40 * Min, t0 + 80 * Min), Right(()))
    val back = rows(Archive.EnergyStates, out).map(Archive.energyOf).map(x => ((x.start - t0) / Min, (x.end - t0) / Min))
    assertEquals(back, Vector((0L, 40L), (0L, 40L), (80L, 100L), (80L, 200L)))
    // The repair's check of the published file (time order among others) holds.
    val target = ArchiveCut.target("energy_state/x.parquet", CommitIntent.Prev.File(2, src.length), w, out.length, out.getPath)
    assertEquals(CommitRepair.verify(target, 4, rows(Archive.EnergyStates, out).iterator), Right(()))
    // Written unsorted, the check would refuse to publish it.
    val unsorted = file(Archive.EnergyStates.columns, spans.flatMap(sp => ArchiveCut.cutRow(Archive.EnergyStates,
      Archive.energyRow(sp).toIndexedSeq, t0 + 40 * Min, t0 + 80 * Min)), Seq(Archive.TableKey -> "energy_state", Archive.SchemaKey -> Archive.SchemaVersion.toString))
    val u = rows(Archive.EnergyStates, unsorted)
    val wu = ArchiveCut.Written(4, u.foldLeft(17L)((h, r) => h * 31 + ArchiveMerge.hash(r)))
    assertEquals(ArchiveCut.check(Archive.EnergyStates, unsorted.getPath, wu, t0 + 40 * Min, t0 + 80 * Min), Left("rows not in time order"))
  }

  test("a range covering the whole file leaves an empty file, not none; a damaged new version fails its check") {
    val ps = (0 until 10).map(i => Archive.pressureRow(PressureSample(t0 + i * 5000L, 1000f)))
    val src = file(Archive.Pressure.columns, ps)
    val (out, w) = cutTo(Archive.Pressure, src, t0, t0 + Min)
    assertEquals(w.rows, 0L)
    assertEquals(ArchiveCut.check(Archive.Pressure, out.getPath, w, t0, t0 + Min), Right(()))
    assertEquals(ParquetReader.meta(out.getPath).rows, 0L)
    // Not what was written: the check says so.
    val (out2, w2) = cutTo(Archive.Pressure, src, t0, t0 + 20_000L)
    assert(ArchiveCut.check(Archive.Pressure, out2.getPath, w2.copy(hash = w2.hash + 1), t0, t0 + 20_000L).isLeft)
    assert(ArchiveCut.check(Archive.Pressure, src.getPath, w2, t0, t0 + 20_000L).isLeft)
  }

  test("a PICS run touching the range goes whole; the others stay") {
    def run(s: Long, e: Long) = Archive.picsRow(PicsAdv(s, e, "a", Some("00CD0849"), Some(2), Some(1), Some(1), 0, 5, -60, s, -70,
      "580102", "bal"))
    val rs = Seq(run(t0, t0 + 4000), run(t0 + 9 * Min + 58_000, t0 + 10 * Min + 1000), run(t0 + 15 * Min, t0 + 15 * Min + 3000),
      run(t0 + 20 * Min, t0 + 20 * Min + 4000))
    val src = file(Archive.PicsAdvs.columns, rs, Seq(Archive.TableKey -> "pics_adv", Archive.SchemaKey -> "4"))
    val (from, to) = (t0 + 10 * Min, t0 + 20 * Min)
    val (out, w) = cutTo(Archive.PicsAdvs, src, from, to)
    assertEquals(ArchiveCut.check(Archive.PicsAdvs, out.getPath, w, from, to), Right(()))
    assertEquals(rows(Archive.PicsAdvs, out).map(r => r(0)), Vector[Any](t0, t0 + 20 * Min))
  }
