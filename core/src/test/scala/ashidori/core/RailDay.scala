package ashidori.core

import java.time.{LocalDate, ZoneId}

/** A synthetic day of train rides that go underground and back up, for
  * [[RailMergeSuite]] and the web viewer's fixtures ([[WebFixtures]]).
  * Above ground a train runs at 15 m/s with fixes; underground at 12 m/s
  * without them (the cut by speed sees 電車 and 地下鉄). The accelerometer
  * records a smooth ride without steps; a walk has 108 steps a minute.
  *  - 09:00 a walk of 5 min, then one train through: 6 min above ground,
  *    8 min underground, 6 min above, 5 min underground (a few steps to the
  *    door at its end), 6 min above; a walk of 5 min;
  *  - 11:00 a walk, 10 min underground, a transfer underground (a walk of
  *    90 s without fixes, then 2 min on the platform above ground), 8 min
  *    above ground, a walk;
  *  - 13:00 a walk, 10 min underground, a transfer above ground (a walk of
  *    90 s with fixes, too short for a leg of its own), 8 min above ground
  *    on an express (33 m/s), a walk;
  *  - 15:00 a walk, 10 min underground, a stay of 20 min at the station,
  *    8 min above ground, a walk;
  *  - 17:00 a walk, 8 min underground, 5 min above ground, 20 min without a
  *    position (15 km on), 6 min above ground, 8 min underground, a walk.
  * Stays of 20 min sit between them.
  */
object RailDay:
  val Zone: ZoneId = ZoneId.of("Asia/Tokyo")
  val Date: LocalDate = LocalDate.of(2026, 10, 10)
  private val mPerDegLat = 111_320.0
  def at(h: Int, m: Int): Long = Date.atTime(h, m).atZone(Zone).toInstant.toEpochMilli

  final case class Out(fixes: Vector[Fix], motion: Vector[MotionWindow])

  def build(): Out =
    val fixes = Vector.newBuilder[Fix]
    val motion = Vector.newBuilder[MotionWindow]
    var north = 0.0
    var t = at(8, 40)
    def fix(): Unit = fixes += Fix(t, 36.0 + north / mPerDegLat, 140.0, accuracy = 8f)
    /** Fixes every 30 s while still, until `until`. */
    def stayUntil(until: Long): Unit =
      while t < until do
        fix()
        t += 30_000
    /** Motion rows every 5 s for `seconds`, moving `v` m/s, `steps` per row. */
    def rows(seconds: Int, v: Double, std: Float, steps: Int, withFixes: Boolean): Unit =
      for _ <- 0 until seconds / 5 do
        if withFixes then fix()
        motion += MotionWindow(t, t + 5_000, std, 0.05f, steps)
        t += 5_000
        north += v * 5
    def walk(seconds: Int): Unit = rows(seconds, 1.4, 1.8f, 9, withFixes = true)
    def ride(seconds: Int): Unit = rows(seconds, 15.0, 0.15f, 0, withFixes = true)
    def underground(seconds: Int): Unit = rows(seconds, 12.0, 0.15f, 0, withFixes = false)
    def walkUnderground(seconds: Int): Unit = rows(seconds, 1.4, 1.8f, 9, withFixes = false)
    def platform(seconds: Int): Unit = rows(seconds, 0.0, 0.05f, 0, withFixes = true)
    /** No position and no motion rows for `seconds`, `m` metres on. */
    def silence(seconds: Int, m: Double): Unit =
      t += seconds * 1000L
      north += m

    def trip(start: Long)(body: => Unit): Unit =
      stayUntil(start)
      walk(300)
      body
      walk(300)
      stayUntil(t + 20 * 60_000L)

    trip(at(9, 0)) {
      ride(360)
      underground(480)
      ride(360)
      underground(285)
      // A few steps to the door before the train comes up.
      rows(15, 12.0, 0.6f, 6, withFixes = false)
      ride(360)
    }
    trip(at(11, 0)) {
      underground(600)
      walkUnderground(90)
      platform(120)
      ride(480)
    }
    trip(at(13, 0)) {
      underground(600)
      walk(90)
      // An express: fast enough to be a train with the walk in its leg.
      rows(480, 33.0, 0.15f, 0, withFixes = true)
    }
    trip(at(15, 0)) {
      underground(600)
      stayUntil(t + 20 * 60_000L)
      ride(480)
    }
    trip(at(17, 0)) {
      underground(480)
      ride(300)
      silence(20 * 60, 15_000.0)
      ride(360)
      underground(480)
    }
    Out(fixes.result(), motion.result())
