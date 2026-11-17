package ashidori.core

import java.time.{LocalDate, ZoneId}

/** A synthetic day with three kinds of silence, for [[UnrecordedSuite]] and
  * the web viewer's fixtures ([[WebFixtures]]):
  *  - 08:00 a walk from home, then nothing for 3 hours while the user ends
  *    up 20 km away (phone off): 記録なし;
  *  - 11:30 a walk, 20 min without positions but with steps counted (the
  *    user walked 1.5 km indoors): a trip, walking;
  *  - 12:30 a walk into an underground station, 8 min without positions,
  *    5 km away: an underground ride (subway), a trip;
  *  - 20 min, 1 km, motion rows without steps (a phone on a table): 記録なし;
  *  - 20 min, 10 km, no motion rows (the phone off, at a subway's speed): 記録なし;
  *  - 20 min, 10 km, the accelerometer recording (a tunnel): a trip.
  * Stays of 30 min sit between them.
  */
object GapDay:
  val Zone: ZoneId = ZoneId.of("Asia/Tokyo")
  val Date: LocalDate = LocalDate.of(2026, 10, 6)
  private val mPerDegLat = 111_320.0
  private val mPerDegLon = 91_000.0
  private def at(h: Int, m: Int): Long = Date.atTime(h, m).atZone(Zone).toInstant.toEpochMilli

  final case class Out(fixes: Vector[Fix], motion: Vector[MotionWindow])

  def build(): Out =
    val fixes = Vector.newBuilder[Fix]
    val motion = Vector.newBuilder[MotionWindow]
    var north = 0.0
    var east = 0.0
    var t = at(7, 30)
    def fix(): Unit =
      fixes += Fix(t, 36.0 + north / mPerDegLat, 140.0 + east / mPerDegLon, accuracy = 8f)
    /** Fixes every 30 s while still. */
    def stay(minutes: Int): Unit =
      for _ <- 0 until minutes * 2 do
        fix()
        t += 30_000
    /** Fixes every 5 s while walking north at 1.4 m/s, with steps. */
    def walk(minutes: Int): Unit =
      for _ <- 0 until minutes * 12 do
        fix()
        motion += MotionWindow(t, t + 5_000, 1.8f, 0.3f, 9)
        t += 5_000
        north += 7.0
    /** No position for `minutes` while moving `east` metres; motion rows
      * of (`steps` per 10 s, `std`), or none.
      */
    def silence(minutes: Int, eastM: Double, rows: Option[(Int, Float)]): Unit =
      for (steps, std) <- rows; k <- 0 until minutes * 6 do
        motion += MotionWindow(t + k * 10_000L, t + (k + 1) * 10_000L, std, 0.3f, steps)
      t += minutes * 60_000L
      east += eastM

    stay(30)
    walk(10)
    silence(180, 20_000, None)
    walk(10)
    stay(30)
    walk(5)
    silence(20, 1_500, Some((18, 1.8f)))
    walk(5)
    stay(30)
    walk(5)
    silence(8, 5_000, None)
    walk(5)
    stay(30)
    walk(5)
    silence(20, 1_000, Some((0, 0.05f)))
    walk(5)
    stay(30)
    walk(5)
    silence(20, 10_000, None)
    walk(5)
    stay(30)
    walk(5)
    silence(20, 10_000, Some((0, 0.4f)))
    walk(5)
    stay(30)
    Out(fixes.result(), motion.result())
