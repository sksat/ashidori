package ashidori.core

class PendingMapUpdatesSuite extends munit.FunSuite:
  enum Cam:
    case Fit
    case Focus(p: String)
    case Show(path: String)

  test("the last camera intent wins and comes after the latest data") {
    val p = PendingMapUpdates[String, Cam]()
    // setData(fit = true), focus(P), setData(fit = false) while the style loads.
    p.update("data", "day v1")
    p.camera(Cam.Fit)
    p.camera(Cam.Focus("P"))
    p.update("data", "day v2")
    assertEquals(p.drain(), (Vector("day v2"), Some(Cam.Focus("P"))))
    assertEquals(p.size, 0)
  }

  test("a later fit or highlight replaces a focus; updates keep the order they were last set") {
    val p = PendingMapUpdates[String, Cam]()
    p.camera(Cam.Focus("P"))
    p.update("live", "tail 1")
    p.update("data", "day")
    p.update("highlight", "trip")
    p.camera(Cam.Show("trip"))
    p.update("live", "tail 2")
    assertEquals(p.drain(), (Vector("data" -> "day", "highlight" -> "trip", "live" -> "tail 2").map(_._2), Some(Cam.Show("trip"))))
  }

  test("however many updates arrive while away, one of each kind is kept") {
    val p = PendingMapUpdates[Int, Cam]()
    for i <- 1 to 10_000 do
      p.update("data", i)
      p.update("live", i)
    assertEquals(p.size, 2)
    assert(p.has("data") && !p.has("highlight"))
    assertEquals(p.drain(), (Vector(10_000, 10_000), None))
  }
