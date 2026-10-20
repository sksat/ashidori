import ashidori.core.parquet.*

/** Time and retained heap of the core ParquetReader on one file.
  *
  *   ReadBench <file.parquet>
  */
object ReadBench:
  def main(args: Array[String]): Unit =
    val r = Runtime.getRuntime
    System.gc(); val before = r.totalMemory - r.freeMemory
    val t0 = System.nanoTime()
    val t = ParquetReader.readFile(args(0))
    val dt = (System.nanoTime() - t0) / 1e9
    System.gc(); val after = r.totalMemory - r.freeMemory
    println(f"${File(args(0)).getName}: ${t.rows} rows in $dt%.1f s, retained heap ${(after - before) / 1e6}%.0f MB")

  private def File(p: String) = java.io.File(p)
