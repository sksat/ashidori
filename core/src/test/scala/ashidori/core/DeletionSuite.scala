package ashidori.core

class DeletionSuite extends munit.FunSuite:
  private val day = (86_400_000L, 2 * 86_400_000L)

  test("times inside a deleted range are not written again; the edges are [from, to)") {
    assert(Deletion.kept(Nil, 5))
    assert(!Deletion.kept(Seq(day), day._1))
    assert(!Deletion.kept(Seq(day), day._2 - 1))
    assert(Deletion.kept(Seq(day), day._2))
    assert(Deletion.kept(Seq(day), day._1 - 1))
  }

  test("an interval crossing deleted ranges keeps only its parts outside them") {
    val (a, b) = day
    assertEquals(Deletion.cut(Seq(day), a - 100, b + 100), Seq((a - 100, a), (b, b + 100)))
    assertEquals(Deletion.cut(Seq(day), a + 1, b - 1), Nil)
    assertEquals(Deletion.cut(Seq(day), a - 100, a), Seq((a - 100, a)))
    assertEquals(Deletion.cut(Seq((10L, 20L), (30L, 40L)), 0, 50), Seq((0L, 10L), (20L, 30L), (40L, 50L)))
  }

  test("state spans: a crossing span keeps its parts, the open one waits for the range's end") {
    val s = EnergyState(true, 1000, "gps", false, false, true, false, "balanced", "balanced", "Walking", "Outdoor")
    val (a, b) = day
    val rows = Seq(EnergyRow(a - 100, Some(a + 100), s), EnergyRow(a + 100, Some(b + 50), s, endUnknown = true), EnergyRow(b + 50, None, s))
    assertEquals(EnergyLog.clip(rows, Seq(day), now = b + 60),
      Seq(EnergyRow(a - 100, Some(a), s), EnergyRow(b, Some(b + 50), s, endUnknown = true), EnergyRow(b + 50, None, s)))
    // Deleting today while recording: the open row inside it is dropped
    // (a state confirmed after the range ends opens a new one).
    assertEquals(EnergyLog.clip(Seq(EnergyRow(a + 100, None, s)), Seq(day), now = a + 200), Nil)
  }

  test("a PICS run touching a range goes whole: its counts and RSSI cannot be cut") {
    val d = Seq((1000L, 2000L))
    assert(Deletion.keptRun(Nil, 1500, 1600))
    assert(!Deletion.keptRun(d, 1500, 1600))
    // Crossing either edge, or ending on its start.
    assert(!Deletion.keptRun(d, 990, 1000))
    assert(!Deletion.keptRun(d, 1990, 2500))
    assert(!Deletion.keptRun(d, 500, 2500))
    // Outside: before its start, or starting at its end.
    assert(Deletion.keptRun(d, 900, 999))
    assert(Deletion.keptRun(d, 2000, 2400))
  }
