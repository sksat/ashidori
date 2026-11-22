package ashidori.core

class WriteKindSuite extends munit.FunSuite:
  test("keys are unique and stable; labels are only for display") {
    assertEquals(WriteKind.values.map(_.key).distinct.size, WriteKind.values.size)
    assertEquals(WriteKind.fromKey("energy_state"), Some(WriteKind.EnergyState))
    assertEquals(WriteKind.fromKey("pressure"), Some(WriteKind.Pressure))
    assertEquals(WriteKind.fromKey("pics_adv"), Some(WriteKind.PicsAdv))
    // A label is not a key.
    assertEquals(WriteKind.fromKey(WriteKind.Pressure.label), None)
    assertEquals(WriteKind.label("pressure"), WriteKind.Pressure.label)
    assertEquals(WriteKind.label("unknown"), "unknown")
  }

  test("the stall monitor sees each new kind on its own") {
    val b = WriteBacklog()
    val e = b.offer(1, raw = false, WriteKind.EnergyState.key, now = 0).get
    val p = b.offer(10, raw = false, WriteKind.Pressure.key, now = 5).get
    b.done(e, 1, raw = false, now = 10)
    // Pressure still waiting: reported under its key, whatever else was saved.
    assertEquals(b.oldestUnsaved, Some(WriteKind.Pressure.key -> 5L))
    b.done(p, 10, raw = false, now = 20, lost = true)
    assertEquals(b.unsavedSince.keySet, Set(WriteKind.Pressure.key))
    assert(b.lastSaved(WriteKind.EnergyState.key).contains(10L))
  }

  test("a batch kept in an overflow file is not lost, but its kind counts as not saved") {
    val b = WriteBacklog()
    val p = b.offer(10, raw = false, WriteKind.Pressure.key, now = 5).get
    b.done(p, 10, raw = false, now = 20, failed = true)
    b.spill(10)
    assertEquals((b.totalLost, b.totalSpilled, b.pending), (0L, 10L, 0L))
    assertEquals(b.unsavedSince, Map(WriteKind.Pressure.key -> 5L))
    // Saved again: no longer unsaved.
    val q = b.offer(1, raw = false, WriteKind.Pressure.key, now = 30).get
    b.done(q, 1, raw = false, now = 31)
    assertEquals(b.unsavedSince, Map.empty[String, Long])
  }
