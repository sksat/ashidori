package ashidori.core

import scala.collection.mutable

/** Fetches that are retried until they succeed, for the requesters that
  * still want them (OpenFreeMap's styles for the maps that show them).
  *
  * One state per key under one lock: idle, fetching (with `again` when a
  * retry was asked for meanwhile), or waiting for a retry (a timer). Taking a
  * waiting fetch and starting it is one step, so a retry asked for while a
  * fetch runs (a network that came back) is not lost: the fetch runs again
  * as soon as the running one failed.
  *
  * A fetch runs only for requesters `allowed` says may still have it sent
  * (the map's holder, permit and screen grant), checked when it starts;
  * [[forget]] drops a requester (its map left the screen or went away), and
  * a key without requesters stops waiting.
  *
  * `run(key, requesters)` fetches (blocking, on `execute`'s thread) and says
  * whether it succeeded. `schedule(delayMs, f)` runs `f` later and returns
  * the function that cancels it. `delay(attempt)` is the wait after the
  * attempt-th failure.
  */
final class Refetch[K, R](run: (K, Seq[R]) => Boolean, allowed: R => Boolean,
                          execute: (() => Unit) => Unit,
                          schedule: (Long, () => Unit) => (() => Unit),
                          delay: Int => Long = Refetch.backoff):
  private enum State:
    case Fetching(again: Boolean, attempt: Int)
    case Waiting(attempt: Int, cancel: () => Unit)

  private val states = mutable.Map.empty[K, State]
  private val requesters = mutable.Map.empty[K, Vector[R]]

  /** `r` wants `k`: fetched now unless it already is or waits for its retry. */
  def request(k: K, r: R): Unit =
    val start = synchronized {
      val rs = requesters.getOrElse(k, Vector.empty)
      if !rs.contains(r) then requesters(k) = rs :+ r
      states.get(k) match
        case None => begin(k, 0)
        case _ => None
    }
    start.foreach(go)

  /** Retry what waits now (e.g. a network became available); a fetch that
    * runs now runs again if it fails.
    */
  def retryNow(): Unit =
    val starts = synchronized {
      states.toSeq.flatMap {
        case (k, State.Waiting(attempt, cancel)) =>
          cancel()
          begin(k, attempt)
        case (k, State.Fetching(_, attempt)) =>
          states(k) = State.Fetching(again = true, attempt)
          None
      }
    }
    starts.foreach(go)

  /** `r` no longer wants anything: dropped everywhere; a key left without
    * requesters stops waiting for its retry.
    */
  def forget(r: R): Unit = forgetWhere(_ == r)

  /** [[forget]] every requester `p` holds for. */
  def forgetWhere(p: R => Boolean): Unit = synchronized {
    for (k, rs) <- requesters.toSeq do
      val rest = rs.filterNot(p)
      if rest.isEmpty then
        requesters -= k
        states.get(k) match
          case Some(State.Waiting(_, cancel)) =>
            cancel()
            states -= k
          case _ => ()
      else requesters(k) = rest
  }

  /** The state of `k`, for tests: "idle", "fetching", "fetching+again", "waiting". */
  def state(k: K): String = synchronized(states.get(k) match
    case None => "idle"
    case Some(State.Fetching(again, _)) => if again then "fetching+again" else "fetching"
    case Some(State.Waiting(_, _)) => "waiting")

  /** Under the lock: `k` starts fetching for its allowed requesters, or goes idle without any. */
  private def begin(k: K, attempt: Int): Option[(K, Seq[R], Int)] =
    val rs = requesters.getOrElse(k, Vector.empty).filter(allowed)
    if rs.isEmpty then
      states -= k
      requesters -= k
      None
    else
      states(k) = State.Fetching(again = false, attempt)
      Some((k, rs, attempt))

  private def go(job: (K, Seq[R], Int)): Unit =
    val (k, rs, attempt) = job
    execute { () =>
      val ok = try run(k, rs) catch case _: Exception => false
      val next = synchronized {
        if ok then
          states -= k
          requesters -= k
          None
        else states.get(k) match
          case Some(State.Fetching(true, _)) => begin(k, attempt + 1)
          case Some(State.Fetching(false, _)) if requesters.get(k).exists(_.nonEmpty) =>
            val cancel = schedule(delay(attempt), () => timer(k))
            states(k) = State.Waiting(attempt + 1, cancel)
            None
          case _ =>
            states -= k
            None
      }
      next.foreach(go)
    }

  private def timer(k: K): Unit =
    val start = synchronized {
      states.get(k) match
        case Some(State.Waiting(attempt, _)) => begin(k, attempt)
        case _ => None
    }
    start.foreach(go)

object Refetch:
  /** 5 s after the first failure, doubling, at most 5 min. */
  def backoff(attempt: Int): Long = math.min(300_000L, 5_000L << math.min(attempt, 6))
