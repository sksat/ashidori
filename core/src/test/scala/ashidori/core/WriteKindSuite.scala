package ashidori.core

class WriteKindSuite extends munit.FunSuite:
  test("keys are unique and stable; labels are only for display") {
    assertEquals(WriteKind.values.map(_.key).distinct.size, WriteKind.values.size)
    assertEquals(WriteKind.fromKey("energy_state"), Some(WriteKind.EnergyState))
    assertEquals(WriteKind.fromKey("pressure"), Some(WriteKind.Pressure))
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
