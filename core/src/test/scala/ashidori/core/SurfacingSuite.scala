package ashidori.core

/** A subway ride that surfaces briefly is one subway leg, not subway, car, subway. */
class SurfacingSuite extends munit.FunSuite:
  private val noMotion = MotionIndex(IndexedSeq.empty)
  private val mPerDegLat = 111_320.0

  /** Fixes along a meridian: (seconds from start, metres north). */
  private def path(points: Seq[(Int, Double)]): Vector[Fix] =
    points.toVector.map((s, m) => Fix(1_000_000L + s * 1000L, 36.0 + m / mPerDegLat, 140.0, accuracy = 8f))

  /** Underground for 6 min, `surface` seconds at `speed` m/s above ground, underground for 6 min. */
  private def ride(surface: Int, speed: Double): Vector[Fix] =
    val before = Seq(0 -> 0.0, 360 -> 3000.0)
    val up = (5 to surface by 5).map(s => (360 + s) -> (3000.0 + s * speed))
    val end = 360 + surface
    val endM = 3000.0 + surface * speed
    path(before ++ up :+ ((end + 360) -> (endM + 3000.0)))

  private def modes(fixes: Vector[Fix]) = TimelineBuilder.legs(fixes, noMotion, TimelineParams()).map(_.mode)

  test("a minute at vehicle speed between two outages stays part of the subway ride") {
    assertEquals(modes(ride(60, 11.0)), Vector(TransportMode.Subway))
  }

  test("a long above-ground stretch between outages is its own leg") {
    assert(modes(ride(10 * 60, 11.0)).size > 1)
  }

  test("walking between two outages is a transfer, not merged") {
    val ms = modes(ride(120, 1.3))
    assert(ms.contains(TransportMode.Walking), ms.toString)
  }
