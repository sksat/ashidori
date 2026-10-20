package ashidori.core

/** Keeps archiving a day from crash-looping the app.
  *
  * Before a day is archived, its attempt is recorded (synchronously); a
  * success clears it. A record still there at the next run means the last
  * attempt failed or the process died in it. After [[MaxAttempts]] failed
  * attempts the day is skipped: it stays in SQLite, nothing is lost. It is
  * tried again after an app update, once while charging at most every
  * [[RetryGapMs]], or when the user asks for it.
  */
object CompactionGuard:
  val MaxAttempts = 2
  val RetryGapMs: Long = 24 * 3_600_000L

  /** `attempts` started and not finished, the app build (its install or
    * update time) and time of the last one, and whether the skip was logged.
    */
  final case class State(attempts: Int, build: Long, lastAttempt: Long, logged: Boolean)

  enum Decision:
    /** Archive the day; store `next` before starting. */
    case Run(next: State, retry: Boolean)
    /** Leave the day in SQLite; `log` once per skip. */
    case Skip(log: Boolean, state: State)

  def decide(s: Option[State], build: Long, now: Long, charging: Boolean, manual: Boolean): Decision =
    s match
      case None => Decision.Run(State(1, build, now, logged = false), retry = false)
      case Some(st) if st.attempts < MaxAttempts => Decision.Run(State(st.attempts + 1, build, now, logged = false), retry = false)
      case Some(st) if st.build != build => Decision.Run(State(1, build, now, logged = false), retry = true)
      case Some(st) if manual || (charging && now - st.lastAttempt >= RetryGapMs) =>
        // One more attempt: failing it skips the day again.
        Decision.Run(State(MaxAttempts, build, now, logged = false), retry = true)
      case Some(st) => Decision.Skip(!st.logged, st.copy(logged = true))

  def encode(s: State): String = s"${s.attempts},${s.build},${s.lastAttempt},${if s.logged then 1 else 0}"

  def decode(v: String): Option[State] =
    v.split(',') match
      case Array(a, b, t, l) =>
        for
          attempts <- a.toIntOption
          build <- b.toLongOption
          time <- t.toLongOption
        yield State(attempts, build, time, l == "1")
      case _ => None
