package ashidori.core

class MotionHistorySuite extends munit.FunSuite:
  private def w(start: Long, std: Float, steps: Int = 0) = MotionWindow(start, start + 10_000, std, 0f, steps)

  test("windows are classified by cadence, speed and shake") {
    assertEquals(MotionHistory.classify(w(0, 1.2f, steps = 15), Double.NaN), MotionClass.Walking)
    assertEquals(MotionHistory.classify(w(0, 0.4f), 12.0), MotionClass.Vehicle)
    assertEquals(MotionHistory.classify(w(0, 0.05f), Double.NaN), MotionClass.Still)
    assertEquals(MotionHistory.classify(w(0, 0.6f), 1.0), MotionClass.Other)
    assertEquals(MotionHistory.classify(w(0, Float.NaN), Double.NaN), MotionClass.Off)
  }

  test("uncovered time is sensor-off, and every bucket adds up to its length") {
    val from = 1_000_000L
    // 3 minutes of walking in the first minute bucket's neighbors, then nothing.
    val ws = (0 until 18).map(i => w(from + i * 10_000L, 1.5f, steps = 16))
    val bs = MotionHistory.buckets(ws, Vector.empty, from, from + 5 * 60_000L, 60_000L)
    assertEquals(bs.size, 5)
    for b <- bs do assertEquals(b.ms.sum, b.end - b.start)
    assertEquals(bs(0).dominant, MotionClass.Walking)
    assertEquals(bs(0).steps, 6 * 16)
    assertEquals(bs(4).dominant, MotionClass.Off)
    assert(bs(4).meanStd.isNaN)
    assertEquals(MotionHistory.totals(bs)(MotionClass.Walking), 3 * 60_000L)
  }

  test("the last bucket is cut at the end time") {
    val bs = MotionHistory.buckets(Seq(w(0, 0.1f)), Vector.empty, 0L, 90_000L, 60_000L)
    assertEquals(bs.map(_.durationMs), Vector(60_000L, 30_000L))
    assertEquals(bs(0).fraction(MotionClass.Still), 10f / 60f, 1e-6f)
  }

  test("speed applies only near the window") {
    val sp = Vector(100_000L -> 10f, 500_000L -> 1f)
    assertEquals(MotionHistory.speedAt(sp, 130_000L), 10.0)
    assertEquals(MotionHistory.speedAt(sp, 480_000L), 1.0)
    assert(MotionHistory.speedAt(sp, 300_000L).isNaN)
  }

  test("the 1-hour view keeps the windows of the last minute, in the partial last bucket") {
    // The last 100 s were measured; the newest window (clocks differ) runs
    // a moment past now.
    val now = 3_600_000L * 10 + 25_000L
    val from = now - 3_600_000L
    val ws = (20 to 100 by 10).map(k => w(now - k * 1_000L, 0.6f)) :+ MotionWindow(now - 6_000, now + 4_000, 0.6f, 0f, 0)
    val bs = MotionHistory.buckets(ws, Vector.empty, from, now, 60_000L)
    assertEquals(bs.size, 60)
    assertEquals(bs.last.end, now)
    assertEquals(MotionHistory.totals(bs)(MotionClass.Other), 100_000L)
    // The last two buckets show "その他", not sensor-off.
    assertEquals(bs.takeRight(2).map(_.shown), Vector(Some(MotionClass.Other), Some(MotionClass.Other)))
    assertEquals(bs.head.shown, Some(MotionClass.Off))
  }

  test("time not delivered yet is pending, not sensor-off") {
    val now = 600_000L
    // Measuring, but only the first 20 s of the last minute have arrived.
    val ws = Seq(w(540_000, 0.6f), w(550_000, 0.6f))
    val bs = MotionHistory.buckets(ws, Vector.empty, 0L, now, 60_000L, pendingFrom = 560_000L)
    val last = bs.last
    assertEquals(last.pendingMs, 40_000L)
    assertEquals(last.ms(MotionClass.Off.ordinal), 0L)
    assertEquals(last.knownMs, 20_000L)
    assertEquals(last.measuredMs, 20_000L)
    for b <- bs do assertEquals(b.ms.sum + b.pendingMs, b.end - b.start)
    assertEquals(last.shown, Some(MotionClass.Other))
    // Nothing of it arrived yet: not drawn at all.
    val none = MotionHistory.buckets(Seq.empty, Vector.empty, 0L, now, 60_000L, pendingFrom = 540_000L)
    assertEquals(none.last.shown, None)
  }
