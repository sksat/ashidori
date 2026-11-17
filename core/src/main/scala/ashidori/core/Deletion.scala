package ashidori.core

/** Ranges the user deleted ([from, to) in ms). Their rows are deleted, and
  * the ranges kept, so that nothing written later (a batch still queued, in
  * the logger's buffers or in an overflow file, a state change sent again)
  * brings the data back.
  */
object Deletion:
  /** Whether time `t` lies outside every range. */
  def kept(ranges: Seq[(Long, Long)], t: Long): Boolean =
    ranges.isEmpty || !ranges.exists((a, b) => t >= a && t < b)

  /** [start, end) without the ranges: the parts left, in order. */
  def cut(ranges: Seq[(Long, Long)], start: Long, end: Long): Seq[(Long, Long)] =
    ranges.sortBy(_._1).foldLeft(Seq((start, end))) { case (parts, (a, b)) =>
      parts.flatMap { (s, e) =>
        if e <= a || s >= b then Seq((s, e))
        else Seq((s, math.min(e, a)), (math.max(s, b), e)).filter((x, y) => y > x)
      }
    }
