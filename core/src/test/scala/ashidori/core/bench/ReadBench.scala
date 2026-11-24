package ashidori.core.bench

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.util.{Random, Using}
import ashidori.core.Archive
import ashidori.core.parquet.*

/** Time and allocation of reading archive files, the current reader against
  * the reader of app 0.24.0 ([[ReferenceParquetReader]]), on synthetic files
  * written by the app's writer in the app's row groups:
  *  - month: 30 day files of each signal table, read as ArchiveStore.read
  *    reads them (a day's time window of each, row group by row group);
  *  - raw: one hour of a Max-mode detail session (GNSS measurements and
  *    satellites of 40 signals at 1 Hz, IMU of three sensors at 100 Hz,
  *    NMEA, fixes), every row read in file order as the export, the commit
  *    check and the merge read them.
  *
  *   ./gradlew :core:readBench --no-daemon -Pargs="<scratch dir> [rounds]"
  *
  * Prints the median milliseconds and the bytes allocated per read of each
  * reader, alternating them round by round.
  */
object ReadBench:
  private val t0 = 1_760_000_000_000L
  private val Day = 86_400_000L

  private def groupRows(t: Archive.TableDef): Int = (400_000 / t.columns.size).max(2048).min(50_000)

  private def write(f: File, t: Archive.TableDef, rows: Iterator[Seq[Any]]): Unit =
    Using.resource(BufferedOutputStream(FileOutputStream(f), 1 << 16)) { out =>
      val w = Archive.writer(t, out, groupRows(t))
      rows.foreach(w.write)
      w.close()
    }

  /** A day of one signal table: rows every `step` ms over the day. */
  private def signalDay(t: Archive.TableDef, day: Int, step: Long, r: Random): Iterator[Seq[Any]] =
    val start = t0 + day * Day
    Iterator.iterate(start)(_ + step).takeWhile(_ < start + Day).map { time =>
      t.columns.zipWithIndex.map { (c, k) =>
        if k == 0 then time
        else if k == 1 && c.name == "end" then time + step
        else if r.nextInt(20) == 0 then null
        else c.tpe match
          case ColType.Int32   => r.nextInt(100)
          case ColType.Int64   => time + r.nextInt(1000)
          case ColType.Float32 => r.nextFloat() * 50
          case ColType.Float64 => 35 + r.nextDouble()
          case ColType.Str     => Seq("gps", "fused", "network")(r.nextInt(3))
      }
    }

  /** Rows a second of each signal table holds, about as a day of moving
    * and still on the device writes them.
    */
  private val signals: Seq[(Archive.TableDef, Long)] = Seq(
    Archive.Fixes -> 2_000L, Archive.Battery -> 60_000L, Archive.GnssStatus -> 5_000L, Archive.Motion -> 60_000L,
    Archive.EnergyStates -> 900_000L, Archive.Pressure -> 30_000L, Archive.PicsAdvs -> 120_000L)

  /** An hour of a Max-mode detail session, a table per file. */
  private def rawHour(dir: File): Seq[(Archive.TableDef, File)] =
    val r = Random(3)
    val seconds = 3600
    def file(t: Archive.TableDef) = File(dir, s"raw-${t.name}.parquet")
    def secs[A](f: Int => Iterator[A]) = Iterator.range(0, seconds).flatMap(f)
    val rawCols = Archive.GnssRaw.columns
    write(file(Archive.GnssRaw), Archive.GnssRaw, secs { s =>
      Iterator.range(0, 40).map { sig =>
        rawCols.zipWithIndex.map { (c, k) =>
          if k == 0 then t0 + s * 1000L
          else c.tpe match
            case ColType.Int64   => s * 1_000_000_000L + r.nextInt(1_000_000)
            case ColType.Int32   => if c.name == "Svid" then 1 + sig % 32 else sig % 6
            case ColType.Str     => if sig % 2 == 0 then "C" else "Q"
            case _               => if r.nextInt(4) == 0 then null else r.nextDouble() * 1000
        }
      }
    })
    write(file(Archive.GnssSat), Archive.GnssSat, secs { s =>
      Iterator.range(0, 40).map(sig => Seq[Any](t0 + s * 1000L, 40, sig, 1 + sig % 6, 1 + sig % 32, 1.57542e9,
        20 + r.nextDouble() * 30, r.nextInt(360).toDouble, r.nextInt(90).toDouble, sig % 2, 1, 1, 18 + r.nextDouble() * 30))
    })
    write(file(Archive.Imu), Archive.Imu, secs { s =>
      Iterator.range(0, 100).flatMap(i => Iterator("Accel", "Gyro", "Mag").map(sensor =>
        Seq[Any](t0 + s * 1000L + i * 10, s * 1_000_000_000L + i * 10_000_000L, sensor,
          r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, 9.8f + r.nextFloat() * 0.1f)))
    })
    write(file(Archive.Nmea), Archive.Nmea, secs { s =>
      Iterator.range(0, 3).map(k => Seq[Any](t0 + s * 1000L, s"$$GPGSV,3,$k,12,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75"))
    })
    write(file(Archive.GnssFix), Archive.GnssFix, secs { s =>
      Iterator.single(Archive.GnssFix.columns.zipWithIndex.map { (c, k) =>
        if k == 0 then t0 + s * 1000L
        else c.tpe match
          case ColType.Int64   => s * 1_000_000_000L
          case ColType.Int32   => 30
          case ColType.Str     => "GPS"
          case ColType.Float32 => r.nextFloat() * 10
          case ColType.Float64 => 35.6 + s * 1e-6
      })
    })
    Seq(Archive.GnssRaw, Archive.GnssSat, Archive.Imu, Archive.Nmea, Archive.GnssFix).map(t => t -> file(t))

  private trait Reader:
    def window(t: Archive.TableDef, path: String, from: Long, to: Long): Int
    def all(path: String): Int

  private object Now extends Reader:
    def window(t: Archive.TableDef, path: String, from: Long, to: Long) =
      Using.resource(ParquetReader.open(path, t.columns))(_.rowsBetween(from, to).toVector.size)
    def all(path: String) =
      var n = 0
      Using.resource(ParquetReader.open(path))(_.rowIterator.foreach(_ => n += 1))
      n

  private object Before extends Reader:
    def window(t: Archive.TableDef, path: String, from: Long, to: Long) =
      Using.resource(ReferenceParquetReader.open(path, t.columns))(_.rowsBetween(from, to).toVector.size)
    def all(path: String) =
      var n = 0
      Using.resource(ReferenceParquetReader.open(path))(_.rowIterator.foreach(_ => n += 1))
      n

  private val threads = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  def main(args: Array[String]): Unit =
    val dir = File(args.lift(0).getOrElse(sys.error("scratch dir")))
    dir.mkdirs()
    val rounds = args.lift(1).map(_.toInt).getOrElse(9)
    val r = Random(1)
    val month = for (t, step) <- signals; d <- 0 until 30 yield
      val f = File(dir, s"${t.name}-$d.parquet")
      write(f, t, signalDay(t, d, step, r))
      (t, f, t0 + d * Day)
    val raw = rawHour(dir)
    def size(fs: Seq[File]) = fs.map(_.length).sum
    println(f"month: ${month.size} files, ${size(month.map(_._2))}%,d bytes; raw hour: ${size(raw.map(_._2))}%,d bytes")

    val workloads: Seq[(String, Reader => Int)] = Seq(
      "month" -> (rd => month.map((t, f, from) => rd.window(t, f.getPath, from, from + Day)).sum),
      "raw" -> (rd => raw.map((_, f) => rd.all(f.getPath)).sum))
    for (name, run) <- workloads do
      val results = for rd <- Seq(Before, Now) yield
        val times = Vector.newBuilder[Double]
        val allocs = Vector.newBuilder[Long]
        rd -> (times, allocs)
      var rows = -1
      for round <- 0 until rounds + 2; (rd, (times, allocs)) <- (if round % 2 == 0 then results else results.reverse) do
        System.gc()
        val a0 = threads.getCurrentThreadAllocatedBytes
        val s = System.nanoTime()
        val n = run(rd)
        val ms = (System.nanoTime() - s) / 1e6
        val bytes = threads.getCurrentThreadAllocatedBytes - a0
        if rows < 0 then rows = n else require(n == rows, s"$name: $n rows, $rows before")
        if round >= 2 then { times += ms; allocs += bytes }
      def median[A: Ordering](xs: Vector[A]) = xs.sorted.apply(xs.size / 2)
      val Seq((before, beforeAlloc), (now, nowAlloc)) = results.map((_, v) => (median(v._1.result()), median(v._2.result())))
      println(f"$name%-6s $rows%,d rows  before ${before}%8.1f ms ${beforeAlloc / 1e6}%8.1f MB  now ${now}%8.1f ms ${nowAlloc / 1e6}%8.1f MB  (${(1 - now / before) * 100}%.0f%% less time)")
