package ashidori.core

/** Recorder numbers are never used twice for different records: a number
  * whose counter could not be written is not used, and the next one is
  * above every number in the database and in spilled records, also after a
  * restore without the counter file.
  */
class EnergyBootSuite extends munit.FunSuite:
  private val st = EnergyState(true, 1000, "gps", false, false, true, false, "balanced", "balanced", "OnFoot", "Outdoor")

  private def spilled(boot: Long): String =
    val t = EnergyTracker(boot)
    t.update(100, Some(st))
    SmallRecord.encode(t.record(100))

  test("the next number is above the counter, the database and every spilled record") {
    assertEquals(EnergyBoot.inSpills(Seq(spilled(7), spilled(12), "{\"t\":\"pressure\",\"v\":[]}")), 12L)
    // A .rec.tmp cut short still names its number.
    assertEquals(EnergyBoot.inSpills(Seq(spilled(30).take(40))), 30L)
    var saved = 0L
    assertEquals(EnergyBoot.allocate(() => 5, () => 9, () => Seq(spilled(12)), n => saved = n), Some(13L))
    assertEquals(saved, 13L)
  }

  test("a counter that cannot be written: no number is used; the next start allocates again") {
    var disk = 4L
    val failed = EnergyBoot.allocate(() => disk, () => 4, () => Nil, _ => throw java.io.IOException("full"))
    assertEquals(failed, None)
    // Nothing was recorded with a number: the restart gets the next free one.
    assertEquals(EnergyBoot.allocate(() => disk, () => 4, () => Nil, n => disk = n), Some(5L))
    assertEquals(disk, 5L)
    // A source that cannot be read: none either.
    assertEquals(EnergyBoot.allocate(() => throw java.io.IOException("bad"), () => 4, () => Nil, _ => ()), None)
    assertEquals(EnergyBoot.allocate(() => 0, () => throw IllegalStateException("db"), () => Nil, _ => ()), None)
  }

  test("after a restore without the counter file: above what the restored data holds") {
    // The backup has the database (boots up to 20) and a spilled record of boot 21 not imported.
    val next = EnergyBoot.allocate(() => 0, () => 20, () => Seq(spilled(21)), _ => ())
    assertEquals(next, Some(22L))
    // So a record of the new process is never taken for one of boot 21 already applied.
    val s = EnergyCheckpoint(21, 1, 100)
    assert(next.get != s.boot)
  }
