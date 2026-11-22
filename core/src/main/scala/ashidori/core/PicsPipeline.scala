package ashidori.core

import java.time.ZoneId
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/** The path from the scan's callback to closed rows (design 4.6): a
  * bounded queue the callback only offers to (a full queue counts as
  * refused, never blocks), and the rounds of one thread that take at most
  * [[batchMax]] results, build the rows ([[PicsRuns]]) and close what is
  * over. Every result offered is either in a round or counted as refused.
  */
final class PicsPipeline(zone: ZoneId, caps: PicsRuns.Caps = PicsRuns.Caps(), queueSize: Int = PicsPipeline.QueueSize,
                         batchMax: Int = PicsPipeline.QueueSize):
  /** A result and its time on the monotonic clock (ms). */
  final case class In(r: PicsResult, elapsed: Long)

  private val queue = ArrayBlockingQueue[In](queueSize)
  private val dropped = AtomicLong()
  private val closeAsked = AtomicBoolean()
  val runs: PicsRuns = PicsRuns(zone, caps)

  /** From the callback (any thread). False: the queue was full (counted). */
  def offer(r: PicsResult, elapsed: Long): Boolean =
    if queue.offer(In(r, elapsed)) then true
    else
      dropped.incrementAndGet()
      false

  /** The scan changed or stopped: the open rows close at the next round. */
  def scanChanged(): Unit = closeAsked.set(true)

  def queued: Int = queue.size

  /** Up to [[batchMax]] results: the first waited for up to `waitMs`, the
    * rest as they are. The bound is checked before each one is taken, so
    * none is taken and left out.
    */
  def drain(waitMs: Long): Vector[In] =
    val b = Vector.newBuilder[In]
    var n = 0
    if batchMax > 0 then
      val first = if waitMs > 0 then queue.poll(waitMs, TimeUnit.MILLISECONDS) else queue.poll()
      if first != null then
        b += first
        n = 1
        var more = true
        while more && n < batchMax do
          val x = queue.poll()
          if x == null then more = false
          else
            b += x
            n += 1
    b.result()

  /** One round: the results taken, and the rows closed by it. `test`: the
    * plan is 「受信の試験」 now (followed each round, so a test that ended
    * while the logger was stopped ends here too).
    */
  def step(waitMs: Long, now: Long, still: Boolean, delayMs: Long, test: Boolean): (Vector[In], Seq[PicsAdv]) =
    val batch = drain(waitMs)
    val out = Vector.newBuilder[PicsAdv]
    if closeAsked.getAndSet(false) then out ++= runs.closeAll()
    if test != runs.isTesting then out ++= runs.setTest(test)
    val d = dropped.getAndSet(0)
    if d > 0 then runs.refuse(PicsRuns.Refused.Queue, d)
    if batch.nonEmpty then out ++= runs.add(batch.map(_.r), still)
    out ++= runs.tick(now, still, delayMs)
    (batch, out.result())

object PicsPipeline:
  val QueueSize = 2000
