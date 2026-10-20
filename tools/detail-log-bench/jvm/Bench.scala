import ashidori.core.*
import ashidori.core.parquet.*
import java.io.*
import scala.collection.mutable

/** Cost of today's path on the JVM: parsing GnssLogger lines and writing the
  * archive tables with the core ParquetWriter (whole day, and 10-minute
  * fragments rotated by arrival), and the per-record cost of formatting a
  * text line. Times are wall-clock (System.nanoTime).
  *
  *   Bench <dump: time|line per line, rowid order> <scratch dir>
  */
object Bench:
  private val tm = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def alloc(): Long = tm.getThreadAllocatedBytes(Thread.currentThread().getId)

  private def lines(path: String): Iterator[(Long, String)] =
    val br = BufferedReader(InputStreamReader(FileInputStream(path), "UTF-8"), 1 << 20)
    Iterator.continually(br.readLine()).takeWhile(_ != null).map { l =>
      val i = l.indexOf('|')
      (l.substring(0, i).toLong, l.substring(i + 1))
    }

  def main(args: Array[String]): Unit =
    val in = args(0)
    val out = File(args(1)); out.mkdirs()
    var t0 = System.nanoTime(); var a0 = alloc(); var n = 0L; var last = 0L
    for (_, line) <- lines(in) do
      GnssLogMerge.timeOf(line).foreach(last = _)
      Archive.rowOf(line, last)
      n += 1
    println(f"parse: $n lines, ${(System.nanoTime() - t0).toDouble / n}%.0f ns/line, ${(alloc() - a0).toDouble / n}%.0f B/line")

    for (label, fragMs) <- Seq(("day", 0L), ("frag10", 600_000L)) do
      val dir = File(out, label); dir.mkdirs()
      final class W(t: Archive.TableDef, f: File):
        val os = BufferedOutputStream(FileOutputStream(f), 1 << 16)
        val w = ParquetWriter(os, t.columns)
        def close(): Unit = { w.close(); os.close() }
      val open = mutable.Map.empty[Archive.TableDef, (Long, W)]
      var hi = 0L; var lt = 0L; var files = 0
      t0 = System.nanoTime(); a0 = alloc()
      for (time, line) <- lines(in) do
        GnssLogMerge.timeOf(line).foreach(lt = _)
        hi = math.max(hi, time) // rotate by arrival, not by sample time
        Archive.rowOf(line, lt).foreach { (t, row) =>
          val frag = if fragMs == 0 then 0L else hi / fragMs
          val w = open.get(t) match
            case Some((f, w)) if f == frag => w
            case other =>
              other.foreach(_._2.close())
              files += 1
              val w = W(t, File(dir, s"${t.name}-$frag.parquet"))
              open(t) = (frag, w)
              w
          w.w.write(row)
        }
      open.values.foreach(_._2.close())
      val bytes = dir.listFiles().map(_.length).sum
      println(f"$label: $files files, ${bytes / 1e6}%.1f MB, ${(System.nanoTime() - t0).toDouble / n}%.0f ns/line, ${(alloc() - a0).toDouble / n}%.0f B/line (parse included)")

    def bench(name: String, k: Int)(f: => Any): Unit =
      for _ <- 0 until 200_000 do f
      val a = alloc(); val t = System.nanoTime()
      for _ <- 0 until k do f
      println(f"$name: ${(System.nanoTime() - t).toDouble / k}%.0f ns, ${(alloc() - a).toDouble / k}%.0f B per record")
    var x = 0.1f
    bench("text Accel line (GnssLogFormat.row)", 2_000_000) { x += 1e-6f; GnssLogFormat.row("Accel", 1760000000000L, 123456789012345L, x, -9.81234f, 0.5f) }
    bench("typed row (Seq[Any])", 2_000_000) { x += 1e-6f; Seq[Any](1760000000000L, 123456789012345L, "Accel", x, -9.81234f, 0.5f) }
