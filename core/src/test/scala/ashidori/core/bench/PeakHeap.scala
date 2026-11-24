package ashidori.core.bench

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable
import ashidori.core.*
import ashidori.core.parquet.*

/** The archiving pipeline as compactDay streams it (parse, reorder,
  * sampler, writer, a writer per raw table open at once) over `seconds` of
  * Max-mode detail-log lines generated second by second, with the code of
  * 0.24.0 (`old`: [[CompactionOld]], [[ReferenceParquetWriter]]) or the
  * current (`new`). Run it under decreasing -Xmx to find the smallest heap
  * it completes in:
  *
  *   java -Xmx<n>m -cp <core test runtime classpath> ashidori.core.bench.PeakHeap old|new <scratch dir> [seconds]
  */
object PeakHeap:
  private final class Sink(t: Archive.TableDef, dir: File, old: Boolean):
    private val out = BufferedOutputStream(FileOutputStream(File(dir, s"${t.name}.parquet")), 1 << 16)
    private val rows = (400_000 / t.columns.size).max(2048).min(50_000)
    private val meta = Seq(Archive.TableKey -> t.name, Archive.SchemaKey -> Archive.SchemaVersion.toString)
    private val before = if old then ReferenceParquetWriter(out, t.columns, rows, meta) else null
    private val now = if old then null else ParquetWriter(out, t.columns, rows, meta)
    private val samplerBefore = if old then CompactionOld.ChunkSamplerOld() else null
    private val samplerNow = if old then null else ChunkSampler()
    private def take(r: IndexedSeq[Any]): Unit =
      if old then { samplerBefore.add(r); before.write(r) } else { samplerNow.add(r); now.write(r) }
    private val reorderBefore = if old then CompactionOld.ReorderOld[IndexedSeq[Any]](10_000L, ArchiveMerge.key, take) else null
    private val reorderNow = if old then null else ArchiveMerge.Reorder[IndexedSeq[Any]](10_000L, ArchiveMerge.key, take)
    def push(r: IndexedSeq[Any], watermark: Long): Unit =
      if old then reorderBefore.push(r, watermark) else reorderNow.push(r, watermark)
    def finish(): Int =
      if old then
        reorderBefore.flush()
        before.close()
      else
        reorderNow.flush()
        now.close()
      out.close()
      (if old then samplerBefore.samples else samplerNow.samples).size

  def main(args: Array[String]): Unit =
    val old = args(0) match
      case "old" => true
      case "new" => false
      case x     => sys.error(s"old or new, not $x")
    val dir = File(args(1), args(0))
    dir.mkdirs()
    val seconds = args.lift(2).map(_.toInt).getOrElse(3600)
    val sinks = mutable.LinkedHashMap.empty[Archive.TableDef, Sink]
    val rnd = scala.util.Random(1)
    var n = 0L
    for s <- 0 until seconds; (time, line) <- CompactionBench.second(s, rnd) do
      val (t, row) = (if old then CompactionOld.parseOld(line, time) else Archive.parse(line, time)).toOption.get
      sinks.getOrElseUpdate(t, Sink(t, dir, old)).push(row, time)
      n += 1
    sinks.values.foreach(_.finish())
    println(s"${args(0)}: $n lines, ${sinks.size} tables, -Xmx ${Runtime.getRuntime.maxMemory / 1048576} MiB")
