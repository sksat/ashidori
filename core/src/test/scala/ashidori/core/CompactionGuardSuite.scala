package ashidori.core

import ashidori.core.CompactionGuard.*

class CompactionGuardSuite extends munit.FunSuite:
  private val build = 100L
  private val hour = 3_600_000L

  private def run(s: Option[State], now: Long, charging: Boolean = false, manual: Boolean = false, b: Long = build) =
    decide(s, b, now, charging, manual)

  test("first attempt runs; a died attempt is tried once more, then the day is skipped") {
    val Decision.Run(s1, false) = run(None, 0L): @unchecked
    assertEquals(s1.attempts, 1)
    // The process died: the record is still there at the next start.
    val Decision.Run(s2, false) = run(Some(s1), hour): @unchecked
    assertEquals(s2.attempts, 2)
    val skip = run(Some(s2), 2 * hour)
    assertEquals(skip, Decision.Skip(true, s2.copy(logged = true)))
    // Logged once only.
    assertEquals(run(Some(s2.copy(logged = true)), 3 * hour), Decision.Skip(false, s2.copy(logged = true)))
  }

  test("a skipped day is retried while charging, at most once a day, with one attempt") {
    val skipped = State(2, build, 0L, logged = true)
    assert(run(Some(skipped), hour, charging = true).isInstanceOf[Decision.Skip])
    val Decision.Run(s, true) = run(Some(skipped), RetryGapMs, charging = true): @unchecked
    assertEquals(s.attempts, MaxAttempts)
    // That one fails too: skipped again, and logged again.
    assertEquals(run(Some(s), RetryGapMs + hour, charging = true), Decision.Skip(true, s.copy(logged = true)))
    assert(run(Some(skipped), 10 * RetryGapMs, charging = false).isInstanceOf[Decision.Skip])
  }

  test("an app update or a manual run retries a skipped day") {
    val skipped = State(2, build, 0L, logged = true)
    val Decision.Run(u, true) = run(Some(skipped), hour, b = build + 1): @unchecked
    assertEquals(u, State(1, build + 1, hour, logged = false))
    val Decision.Run(m, true) = run(Some(skipped), hour, manual = true): @unchecked
    assertEquals(m.attempts, MaxAttempts)
  }

  test("state round-trips through its stored form") {
    val s = State(2, 1_760_000_000_123L, 1_761_000_000_000L, logged = true)
    assertEquals(decode(encode(s)), Some(s))
    assertEquals(decode("garbage"), None)
    assertEquals(decode("1,x,2,0"), None)
  }
