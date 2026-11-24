package ashidori.core

import scala.collection.mutable
import ashidori.core.parquet.*
import ashidori.core.parquet.ColType.*
import ashidori.core.Archive.*
import ashidori.core.ArchiveMerge.{Row, hash, key}
import ashidori.core.CommitIntent.{Check, Prev}

/** Frozen implementations from before the compaction CPU changes. */
object CompactionOld:
  private def strictCellOld(s: String, t: ColType): Either[String, Any] =
    def integral(max: Double): Option[Long] =
      s.toDoubleOption.filter(d => !d.isNaN && d == math.rint(d) && math.abs(d) <= max).map(_.toLong)
    if s.isEmpty then Right(null)
    else
      val v: Option[Any] = t match
        case Int32 =>
          s.toIntOption.orElse(integral(Int.MaxValue.toDouble).map(_.toInt))
            .orElse(s match { case "true" => Some(1); case "false" => Some(0); case _ => None })
        case Int64   => s.toLongOption.orElse(integral(9.007199254740992e15))
        case Float32 => s.toFloatOption
        case Float64 => s.toDoubleOption
        case Str     => Some(s)
      v.toRight(s"'$s' is not ${t.toString}")

  def parseOld(line: String, time: Long): Either[String, (TableDef, IndexedSeq[Any])] =
    val a = line.split(",", -1)
    def cells(t: TableDef, fields: Seq[String]): Either[String, IndexedSeq[Any]] =
      val out = IndexedSeq.newBuilder[Any]
      var err: Option[String] = None
      for (c, s) <- t.columns.zip(fields) if err.isEmpty do
        strictCellOld(s, c.tpe).fold(e => err = Some(s"${c.name}: $e"), out += _)
      err.toLeft(out.result())
    def fields(n: Int, from: Int): Either[String, Seq[String]] =
      if a.length - from > n then Left(s"${a.length - from} fields, ${n} columns")
      else Right((from until from + n).map(i => a.lift(i).getOrElse("")))
    val row: Either[String, (TableDef, IndexedSeq[Any])] = Archive.tableFor(a(0)) match
      case None => Left(s"no table for '${a(0)}'")
      case Some(t @ (GnssRaw | GnssSat)) => fields(t.columns.size, 1).flatMap(cells(t, _)).map(t -> _)
      case Some(GnssNav) =>
        if a.length < 6 then Left("navigation message without its fields")
        else cells(GnssNav, time.toString +: (1 to 5).map(a(_)) :+ a.drop(6).mkString(" ")).map(GnssNav -> _)
      case Some(Nmea) =>
        if a.length < 3 then Left("NMEA without its time")
        else a.last.toLongOption.toRight("NMEA time").map(t => Nmea -> IndexedSeq(t, a.slice(1, a.length - 1).mkString(",")))
      case Some(Imu) =>
        if a.length != 6 then Left(s"${a.length} fields in an IMU line")
        else cells(Imu, Seq(a(1), a(2), a(0), a(3), a(4), a(5))).map(Imu -> _)
      case Some(GnssFix) =>
        fields(16, 1).flatMap { f =>
          val order = Seq(7, 0, 1, 2, 3, 4, 5, 6) ++ (8 to 15)
          cells(GnssFix, order.map(f))
        }.map(GnssFix -> _)
      case Some(other) => Left(s"no conversion for ${other.name}")
    row.filterOrElse((_, r) => r.nonEmpty && r(0) != null, "no time")


  final class DistinctOld(out: Row => Unit):
    private var current = Long.MinValue
    private val seen = mutable.HashMap.empty[Long, List[Row]]
    var written = 0L
    var dropped = 0L

    def apply(r: Row): Unit =
      val k = key(r)
      if k != current then
        current = k
        seen.clear()
      val h = hash(r)
      val same = seen.getOrElse(h, Nil)
      if same.exists(ArchiveMerge.same(_, r)) then dropped += 1
      else
        seen(h) = r :: same
        out(r)
        written += 1

  final class ReorderOld[T](windowMs: Long, keyOf: T => Long, out: T => Unit, tooLate: T => Unit = (_: T) => ()):
    private val held = mutable.PriorityQueue.empty[(Long, Long, T)](using
      Ordering.by[(Long, Long, T), (Long, Long)](x => (x._1, x._2)).reverse)
    private var seq = 0L
    private var emitted = Long.MinValue
    var late = 0L

    def push(r: T, watermark: Long): Unit =
      val k = keyOf(r)
      if k < emitted then
        late += 1
        tooLate(r)
      else
        held.enqueue((k, seq, r))
        seq += 1
      release(if watermark == Long.MinValue then Long.MinValue else watermark - windowMs)

    private def release(upTo: Long): Unit =
      while held.nonEmpty && held.head._1 < upTo do
        val (k, _, r) = held.dequeue()
        emitted = k
        out(r)

    /** Passes on everything held. */
    def flush(): Unit =
      while held.nonEmpty do
        val (k, _, r) = held.dequeue()
        emitted = k
        out(r)


  final class ChunkSamplerOld(max: Int = 8):
    private var stride = 1L
    private var seen = 0L
    private val kept = mutable.ArrayBuffer.empty[(Long, Long)]
    private var lastRow: Option[(Long, Long)] = None
    private var lo = Long.MaxValue
    private var hi = Long.MinValue

    def add(r: Row): Unit =
      val k = ArchiveMerge.key(r)
      lo = math.min(lo, k)
      hi = math.max(hi, k)
      if seen % stride == 0 then
        kept += ((k, ArchiveMerge.hash(r)))
        if kept.size >= 2 * max then
          // Keep every other one and sample half as often.
          val half = kept.indices.filter(_ % 2 == 0).map(kept)
          kept.clear()
          kept ++= half
          stride *= 2
        lastRow = None
      else lastRow = Some((k, ArchiveMerge.hash(r)))
      seen += 1

    def count: Long = seen
    def range: Option[(Long, Long)] = Option.when(seen > 0)((lo, hi))
    def samples: Seq[(Long, Long)] = (kept ++ lastRow).toSeq

    def check(prev: Prev, carried: Long, added: Long, range: Option[(Long, Long)]): Check =
      Check(prev, carried, added, range, this.range, samples)

