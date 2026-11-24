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
  * Runs the code of 0.24.0 ([[CompactionOld]], [[ReferenceParquetWriter]])
  * and the current code alternately, round by round, and prints the
  * milliseconds of each stage (median of the rounds) for both.
  */
object CompactionBench:
  def lines(seconds: Int, seed: Long = 1L): Vector[(Long, String)] =
    val rnd = scala.util.Random(seed)
    Vector.range(0, seconds).flatMap(second(_, rnd))

  /** The lines of second `s` of the session (`rnd` drawn in order). */
  def second(s: Int, rnd: scala.util.Random): Vector[(Long, String)] =
    val out = Vector.newBuilder[(Long, String)]
    val t0 = 1_760_000_000_000L
    val rawFields = GnssLogFormat.RawHeader.split(',').length - 1
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


  private trait Impl:
    def parse(line: String, time: Long): (Archive.TableDef, IndexedSeq[Any])
    def sample(rows: Seq[IndexedSeq[Any]]): Int
    def reorder(rows: Seq[IndexedSeq[Any]]): Unit
    def write(t: Archive.TableDef, out: java.io.OutputStream, rows: Seq[IndexedSeq[Any]]): Unit

  private object Before extends Impl:
    def parse(line: String, time: Long) = CompactionOld.parseOld(line, time).toOption.get
    def sample(rows: Seq[IndexedSeq[Any]]) =
      val s = CompactionOld.ChunkSamplerOld()
      rows.foreach(s.add)
      s.samples.size
    def reorder(rows: Seq[IndexedSeq[Any]]) =
      val r = CompactionOld.ReorderOld[IndexedSeq[Any]](10_000L, ArchiveMerge.key, _ => ())
      rows.foreach(x => r.push(x, ArchiveMerge.key(x)))
      r.flush()
    def write(t: Archive.TableDef, out: java.io.OutputStream, rows: Seq[IndexedSeq[Any]]) =
      val w = ReferenceParquetWriter(out, t.columns, (400_000 / t.columns.size).max(2048).min(50_000),
        Seq(Archive.TableKey -> t.name, Archive.SchemaKey -> Archive.SchemaVersion.toString))
      rows.foreach(w.write)
      w.close()

  private object Now extends Impl:
    def parse(line: String, time: Long) = Archive.parse(line, time).toOption.get
    def sample(rows: Seq[IndexedSeq[Any]]) =
      val s = ChunkSampler()
      rows.foreach(s.add)
      s.samples.size
    def reorder(rows: Seq[IndexedSeq[Any]]) =
      val r = ArchiveMerge.Reorder[IndexedSeq[Any]](10_000L, ArchiveMerge.key, _ => ())
      rows.foreach(x => r.push(x, ArchiveMerge.key(x)))
      r.flush()
    def write(t: Archive.TableDef, out: java.io.OutputStream, rows: Seq[IndexedSeq[Any]]) =
      val w = Archive.writer(t, out, (400_000 / t.columns.size).max(2048).min(50_000))
      rows.foreach(w.write)
      w.close()

  def main(args: Array[String]): Unit =
    val dir = File(args.lift(0).getOrElse(sys.error("scratch dir")))
    dir.mkdirs()
    val seconds = args.lift(1).map(_.toInt).getOrElse(600)
    val rounds = args.lift(2).map(_.toInt).getOrElse(5)
    val input = lines(seconds)
    println(s"${input.size} lines, ${seconds} s of log")
    val impls = Seq("before" -> Before, "now" -> Now)
    val times = mutable.LinkedHashMap.empty[(String, String), mutable.ArrayBuffer[Double]]
    for round <- 0 until rounds + 2; (name, impl) <- (if round % 2 == 0 then impls else impls.reverse) do
      def time[A](stage: String)(f: => A): A =
        val s = System.nanoTime()
        val r = f
        if round >= 2 then times.getOrElseUpdate((stage, name), mutable.ArrayBuffer.empty) += (System.nanoTime() - s) / 1e6
        r
      val parsed = time("parse")(input.map((t, l) => impl.parse(l, t)))
      val byTable = parsed.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      time("sampler")(byTable.values.foreach(impl.sample))
      time("reorder")(byTable.values.foreach(impl.reorder))
      time("write") {
        for (t, rows) <- byTable do
          val out = BufferedOutputStream(FileOutputStream(File(dir, s"$name-${t.name}.parquet")), 1 << 16)
          impl.write(t, out, rows)
          out.close()
      }
    def median(stage: String, name: String) =
      val v = times((stage, name)).sorted
      v(v.size / 2)
    val stages = times.keys.map(_._1).toSeq.distinct
    for stage <- stages :+ "total" do
      val Seq(b, n) = impls.map(_._1).map(name =>
        if stage == "total" then stages.map(median(_, name)).sum else median(stage, name))
      println(f"$stage%-8s before $b%8.1f ms  now $n%8.1f ms  (${(1 - n / b) * 100}%.0f%% less time)")
    for t <- Archive.RawTables do
      val Seq(b, n) = impls.map(_._1).map(name => File(dir, s"$name-${t.name}.parquet"))
      if b.exists then
        val same = java.util.Arrays.equals(java.nio.file.Files.readAllBytes(b.toPath), java.nio.file.Files.readAllBytes(n.toPath))
        println(f"${t.name}%-10s ${n.length}%,d bytes, ${if same then "identical" else "DIFFERENT"} to before")
