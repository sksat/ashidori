package ashidori.core.bench

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable
import ashidori.core.*
import ashidori.core.parquet.*

/** CPU cost of archiving a day of detail-log lines (ArchiveStore.compactDay
  * without SQLite and fsync): parsing, the sampler, the merge and the
  * Parquet writer, stage by stage, on synthetic lines of a Max-mode
  * session (1 Hz GNSS with 40 signals, 100 Hz IMU of three sensors, NMEA).
  *
  *   ./gradlew :core:compactionBench --no-daemon -Pargs="<scratch dir> [seconds of log] [rounds]"
  *
  * Prints milliseconds per stage (median of the rounds) and lines per second.
  */
object CompactionBench:
  def lines(seconds: Int, seed: Long = 1L): Vector[(Long, String)] =
    val rnd = scala.util.Random(seed)
    val out = Vector.newBuilder[(Long, String)]
    val t0 = 1_760_000_000_000L
    val rawFields = GnssLogFormat.RawHeader.split(',').length - 1
    for s <- 0 until seconds do
      val t = t0 + s * 1000L
      for sig <- 0 until 40 do
        val f = Array.fill(rawFields)("")
        f(0) = t.toString
        f(1) = (s * 1_000_000_000L + 123_456L).toString
        f(2) = "18"
        f(3) = "0.0"
        f(4) = (-1_300_000_000_000_000_000L - s).toString
        f(5) = f"${rnd.nextDouble() * 1e3}%.6f"
        f(6) = f"${rnd.nextDouble() * 10}%.3f"
        f(10) = (1 + sig % 32).toString
        f(11) = "0.0"
        f(12) = "16431"
        f(13) = (s * 1_000_000_000L + rnd.nextInt(1_000_000)).toString
        f(14) = (10 + rnd.nextInt(50)).toString
        f(15) = f"${20 + rnd.nextDouble() * 30}%.2f"
        f(16) = f"${rnd.nextDouble() * 800 - 400}%.4f"
        f(17) = f"${rnd.nextDouble()}%.4f"
        f(18) = "16"
        f(19) = f"${rnd.nextDouble() * 1e5}%.3f"
        f(20) = "0.003"
        f(21) = if sig % 2 == 0 then "1.57542E9" else "1.17645E9"
        f(25) = "0"
        f(27) = (1 + sig % 6).toString
        f(29) = f"${20 + rnd.nextDouble() * 30}%.2f"
        f(34) = if sig % 2 == 0 then "C" else "Q"
        f(35) = (s * 1_000_000_000L + 5L).toString
        out += t -> ("Raw," + f.mkString(","))
      for sig <- 0 until 40 do
        out += t -> s"Status,$t,40,$sig,${1 + sig % 6},${1 + sig % 32},1.57542E9,${f"${20 + rnd.nextDouble() * 30}%.1f"},${rnd.nextInt(360)}.0,${rnd.nextInt(90)}.0,${sig % 2},1,1,${f"${18 + rnd.nextDouble() * 30}%.1f"}"
      out += t -> s"Fix,GPS,${f"${35.6 + s * 1e-6}%.7f"},${f"${139.7 + s * 1e-6}%.7f"},40.5,1.2,3.5,90.0,$t,0.5,10.0,${s * 1_000_000_000L},5.0,0,30,0.4,"
      for k <- 0 until 3 do
        out += t -> s"NMEA,$$GPGSV,3,$k,12,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75,$t"
      for i <- 0 until 100; sensor <- Seq("Accel", "Gyro", "Mag") do
        val ts = t + i * 10
        out += t -> f"$sensor,$ts,${s * 1_000_000_000L + i * 10_000_000L},${rnd.nextFloat() * 2 - 1}%.6f,${rnd.nextFloat() * 2 - 1}%.6f,${9.8 + rnd.nextFloat() * 0.1}%.6f"
    out.result()

  def main(args: Array[String]): Unit =
    val dir = File(args.lift(0).getOrElse(sys.error("scratch dir")))
    dir.mkdirs()
    val seconds = args.lift(1).map(_.toInt).getOrElse(600)
    val rounds = args.lift(2).map(_.toInt).getOrElse(5)
    val input = lines(seconds)
    println(s"${input.size} lines, ${seconds} s of log")
    val times = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Double]]
    def time[A](name: String)(f: => A): A =
      val s = System.nanoTime()
      val r = f
      times.getOrElseUpdate(name, mutable.ArrayBuffer.empty) += (System.nanoTime() - s) / 1e6
      r
    for round <- 0 until rounds + 2 do
      if round == 2 then times.clear()
      val parsed = time("parse") {
        input.map((t, l) => Archive.parse(l, t).toOption.get)
      }
      val byTable = parsed.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      time("sampler") {
        for (_, rows) <- byTable do
          val s = ChunkSampler()
          rows.foreach(s.add)
          s.samples
      }
      time("distinct") {
        for (_, rows) <- byTable do
          var n = 0L
          val d = ArchiveMerge.Distinct(_ => n += 1)
          rows.foreach(d(_))
      }
      time("reorder") {
        for (_, rows) <- byTable do
          val r = ArchiveMerge.Reorder[IndexedSeq[Any]](10_000L, ArchiveMerge.key, _ => ())
          rows.foreach(x => r.push(x, ArchiveMerge.key(x)))
          r.flush()
      }
      time("write") {
        for (t, rows) <- byTable do
          val f = File(dir, s"${t.name}.parquet")
          val out = BufferedOutputStream(FileOutputStream(f), 1 << 16)
          val w = Archive.writer(t, out, (400_000 / t.columns.size).max(2048).min(50_000))
          rows.foreach(w.write)
          w.close()
          out.close()
      }
      time("meta") {
        for (t, _) <- byTable do ParquetReader.meta(File(dir, s"${t.name}.parquet").getPath)
      }
    val total = times.values.map(v => v.sorted.apply(v.size / 2)).sum
    for (k, v) <- times do
      val m = v.sorted.apply(v.size / 2)
      println(f"$k%-10s ${m}%10.1f ms  (${input.size / m * 1000}%,.0f lines/s)")
    println(f"total      $total%10.1f ms  (${input.size / total * 1000}%,.0f lines/s)")
    for (t, _) <- Archive.RawTables.map(t => t -> 0) do
      val f = File(dir, s"${t.name}.parquet")
      if f.exists then println(f"${t.name}%-10s ${f.length}%,d bytes")
