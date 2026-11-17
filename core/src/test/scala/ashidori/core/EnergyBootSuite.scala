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

  test("a spill that cannot be read or listed refuses the allocation (never read as none)") {
    val dir = java.nio.file.Files.createTempDirectory("spill").toFile
    try
      java.nio.file.Files.writeString(java.io.File(dir, "1-small.rec").toPath, spilled(21))
      java.nio.file.Files.writeString(java.io.File(dir, "2-raw.txt").toPath, "not a record")
      // Readable: 22, above the database's 20.
      assertEquals(EnergyBoot.allocate(() => 0, () => 20, () => EnergyBoot.spillTexts(dir), _ => ()), Some(22L))
      // The database holds up to 20 and the spill with 21 cannot be read: no number
      // (21 would be given again, and the spill's records skipped as applied).
      val unreadable = (f: java.io.File) => throw java.nio.file.AccessDeniedException(f.getPath)
      var saved = 0L
      assertEquals(EnergyBoot.allocate(() => 0, () => 20, () => EnergyBoot.spillTexts(dir, unreadable), n => saved = n), None)
      assertEquals(saved, 0L)
      // A directory that cannot be listed (here: not a directory): no number either.
      val notDir = java.io.File(dir, "1-small.rec")
      assertEquals(EnergyBoot.allocate(() => 0, () => 20, () => EnergyBoot.spillTexts(notDir), _ => ()), None)
      // A file imported between the listing and the read (gone): skipped.
      val gone = (f: java.io.File) => { f.delete(); throw java.nio.file.NoSuchFileException(f.getPath) }
      assertEquals(EnergyBoot.spillTexts(dir, gone), Nil)
      // No directory at all: nothing spilled.
      assertEquals(EnergyBoot.spillTexts(java.io.File(dir, "none")), Nil)
    finally
      Option(dir.listFiles()).toSeq.flatten.foreach(_.delete())
      dir.delete()
  }

  test("while this process has no number, a gone process's open span ends at its last confirmation") {
    val open = EnergyRow(1000, None, st, boot = 21, seq = 4)
    // The running recorder's own: until now.
    assertEquals(EnergyLog.openSpan(open, live = Some(21), confirmedAt = Some(1500), now = 9000).end, 9000L)
    // No running recorder (allocation refused) or another one: up to the confirmation, end unknown.
    for live <- Seq(None, Some(22L)) do
      val s = EnergyLog.openSpan(open, live, confirmedAt = Some(1500), now = 9000)
      assertEquals((s.end, s.endKnown), (1500L, false))
    // No confirmation: zero length, unknown.
    assertEquals(EnergyLog.openSpan(open, None, None, 9000).end, 1000L)
    // The rest (1500 to 9000) is covered by no span, so no estimate uses it: a window
    // of two readings across it has under 90 % coverage.
    val span = EnergyLog.openSpan(open, None, Some(1500), 9000)
    assert(span.ms < 0.9 * (9000 - 1000))
  }

  test("after a restore without the counter file: above what the restored data holds") {
    // The backup has the database (boots up to 20) and a spilled record of boot 21 not imported.
    val next = EnergyBoot.allocate(() => 0, () => 20, () => Seq(spilled(21)), _ => ())
    assertEquals(next, Some(22L))
    // So a record of the new process is never taken for one of boot 21 already applied.
    val s = EnergyCheckpoint(21, 1, 100)
    assert(next.get != s.boot)
  }
