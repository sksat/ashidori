package ashidori.core

import java.time.{LocalDate, ZoneId}

/** A synthetic day of drives whose parts are cut apart by the coarse speed
  * class but all end as 車, for [[LegMergeSuite]] and the web viewer's
  * fixtures ([[WebFixtures]]):
  *  - 09:00 a slow start (5 m/s for 5 min), then 12 m/s for 8 min;
  *  - 10:00 12 m/s for 8 min, 100 s without a position (350 m, under an
  *    overpass), 12 m/s for 8 min;
  *  - 11:00 a walk of 5 min, then 12 m/s for 8 min (stays two legs);
  *  - 12:00 as at 09:00 (for corrections).
  * The accelerometer records a car's vibration without steps while driving.
  * Stays of 20 min sit between them.
  */
object MergeDay:
  val Zone: ZoneId = ZoneId.of("Asia/Tokyo")
  val Date: LocalDate = LocalDate.of(2026, 10, 8)
  private val mPerDegLat = 111_320.0
  private val mPerDegLon = 91_000.0
  def at(h: Int, m: Int): Long = Date.atTime(h, m).atZone(Zone).toInstant.toEpochMilli

  final case class Out(fixes: Vector[Fix], motion: Vector[MotionWindow])

  def build(): Out = build(at(8, 40), Seq(at(9, 0), at(10, 0), at(11, 0), at(12, 0)))

  /** Stays until each start, then the trips in the order above (cycling
    * through them), and a stay of 20 min after the last.
    */
  def build(from: Long, starts: Seq[Long]): Out =
    val fixes = Vector.newBuilder[Fix]
    val motion = Vector.newBuilder[MotionWindow]
    var north = 0.0
    var t = from
    def fix(): Unit = fixes += Fix(t, 36.0 + north / mPerDegLat, 140.0, accuracy = 8f)
    /** Fixes every 30 s while still, until `until`. */
    def stay(until: Long): Unit =
      while t < until do
        fix()
        t += 30_000
    /** Fixes every 5 s at `speed` m/s; a car's vibration, no steps. */
    def drive(minutes: Int, speed: Double): Unit =
      for _ <- 0 until minutes * 12 do
        fix()
        motion += MotionWindow(t, t + 5_000, 0.4f, 0.1f, 0)
        t += 5_000
        north += speed * 5
    /** Fixes every 5 s while walking at 1.4 m/s, with steps. */
    def walk(minutes: Int): Unit =
      for _ <- 0 until minutes * 12 do
        fix()
        motion += MotionWindow(t, t + 5_000, 1.8f, 0.3f, 9)
        t += 5_000
        north += 7.0
    /** No position for `seconds` while driving `m` metres. */
    def outage(seconds: Int, m: Double): Unit =
      for k <- 0 until seconds / 5 do motion += MotionWindow(t + k * 5_000L, t + (k + 1) * 5_000L, 0.4f, 0.1f, 0)
      t += seconds * 1000L
      north += m

    for (s, i) <- starts.zipWithIndex do
      stay(s)
      i % 4 match
        case 0 | 3 =>
          drive(5, 5.0)
          drive(8, 12.0)
        case 1 =>
          drive(8, 12.0)
          outage(100, 350.0)
          drive(8, 12.0)
        case _ =>
          walk(5)
          drive(8, 12.0)
    stay(t + 20 * 60_000L)
    Out(fixes.result(), motion.result())
