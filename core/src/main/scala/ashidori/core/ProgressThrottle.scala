package ashidori.core

/** Which progress reports of one download go on to the UI: at most one
  * per `intervalMs`, each at least `step` bytes (or 1/200 of the total, if
  * that is less) past the last one shown; the first, the last (`total`)
  * and one after a restart from the start always. Only the reports are
  * thinned: the download does not wait for them. One download (thread).
  */
final class ProgressThrottle(intervalMs: Long = 250, step: Long = 1L << 20):
  private var shown = -1L
  private var at = 0L

  /** Whether the report of `done` of `total` bytes at `now` (ms) is shown. */
  def due(done: Long, total: Long, now: Long): Boolean =
    val min = if total > 0 then math.max(1L, math.min(step, total / 200)) else step
    val d = done != shown &&
      (shown < 0 || done < shown || (total > 0 && done >= total) || (done - shown >= min && now - at >= intervalMs))
    if d then
      shown = done
      at = now
    d
