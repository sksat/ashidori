package ashidori.core

class BaseMapPolicySuite extends munit.FunSuite:
  private val Offline = "offline"
  private val Osm = "ofm-liberty"
  private val Gsi = "gsi-std"

  test("setting on: interactive maps are offline unless detail is requested") {
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, detailed = false), Offline)
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, detailed = true), Gsi)
  }

  test("setting on: detail with the offline map chosen uses the last online choice") {
    assertEquals(BaseMapPolicy.interactive(true, Offline, Osm, Offline, detailed = true), Osm)
  }

  test("setting off: the user's choice applies, including the offline map") {
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Gsi, Offline, detailed = false), Gsi)
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, detailed = false), Offline)
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, detailed = true), Offline)
  }

  test("snapshots stay offline while the setting is on, even if a detailed map is open") {
    assertEquals(BaseMapPolicy.snapshot(true, Gsi, Offline), Offline)
    assertEquals(BaseMapPolicy.snapshot(false, Gsi, Offline), Gsi)
  }

  test("thumbnails: only the offline map while the setting is on") {
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(true, _, Offline)), Seq(Offline))
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(false, _, Offline)), Seq(Offline, Osm, Gsi))
  }

  test("network only with the setting off or a detailed map open") {
    assert(!BaseMapPolicy.network(block = true, detailedOpen = false))
    assert(BaseMapPolicy.network(block = true, detailedOpen = true))
    assert(BaseMapPolicy.network(block = false, detailedOpen = false))
  }
