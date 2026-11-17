package ashidori.core

/** 取り消し of a place edit takes back only its own field, and only while it
  * still holds what the edit set.
  */
class PlaceEditSuite extends munit.FunSuite:
  private val p0 = Place(1, 36.0, 140.0, 50, "", PlaceKind.Other, "", "天王台")

  test("rename, then change the kind, then undo the rename: the kind stays") {
    val rename = PlaceEdit.rename(p0, "カフェ")
    val p1 = rename.applyTo(p0)
    val kind = PlaceEdit.kind(p1, PlaceKind.Work)
    val p2 = kind.applyTo(p1)
    val undone = rename.revert(p2).get
    assertEquals(undone.name, "")
    assertEquals(undone.kind, PlaceKind.Work)
  }

  test("change the kind, then rename, then undo the kind: the name stays") {
    val kind = PlaceEdit.kind(p0, PlaceKind.Home)
    val p1 = kind.applyTo(p0)
    val p2 = PlaceEdit.rename(p1, "実家").applyTo(p1)
    assertEquals(kind.revert(p2), Some(p2.copy(kind = PlaceKind.Other)))
  }

  test("undo after the same field changed again does nothing") {
    val first = PlaceEdit.rename(p0, "A")
    val p1 = first.applyTo(p0)
    val p2 = PlaceEdit.rename(p1, "B").applyTo(p1)
    assertEquals(first.revert(p2), None)
  }

  test("a venue's name carries its id; a typed name keeps the place's") {
    val venue = PlaceEdit.rename(p0, "駅前店", Some("node/1"))
    val p1 = venue.applyTo(p0)
    assertEquals(p1.externalId, "node/1")
    assertEquals(PlaceEdit.rename(p1, "いつもの店").applyTo(p1).externalId, "node/1")
    assertEquals(venue.revert(p1).map(_.externalId), Some(""))
  }

  test("an edit that sets the value already there is a no-op") {
    assert(PlaceEdit.kind(p0, PlaceKind.Other).isNoop)
    assert(!PlaceEdit.rename(p0, "x").isNoop)
  }
