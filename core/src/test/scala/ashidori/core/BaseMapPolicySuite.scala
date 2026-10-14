package ashidori.core

import BaseMapPolicy.Outcome.{Draw, NoBasemap}

class BaseMapPolicySuite extends munit.FunSuite:
  private val Offline = "offline"
  private val Osm = "ofm-liberty"
  private val Gsi = "gsi-std"

  test("setting on: interactive maps are offline unless detail is requested") {
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, true, detailed = false), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, true, detailed = true), Draw(Gsi))
  }

  test("setting on: detail with the offline map chosen uses the last online choice") {
    assertEquals(BaseMapPolicy.interactive(true, Offline, Osm, Offline, true, detailed = true), Draw(Osm))
  }

  test("setting off: the user's choice applies, including the offline map") {
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Gsi, Offline, true, detailed = false), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, true, detailed = false), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, true, detailed = true), Draw(Offline))
  }

  test("setting on without the basemap: no base map until detail is requested") {
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, false, detailed = false), NoBasemap)
    assertEquals(BaseMapPolicy.interactive(true, Offline, Osm, Offline, false, detailed = false), NoBasemap)
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Gsi, Offline, false, detailed = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(true, Offline, Osm, Offline, false, detailed = true), Draw(Osm))
  }

  test("setting off without the basemap: an online choice is unaffected, the offline one has no base map") {
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Gsi, Offline, false, detailed = false), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, false, detailed = false), NoBasemap)
    // Never an online map the user did not choose.
    assertEquals(BaseMapPolicy.interactive(false, Offline, Osm, Offline, false, detailed = true), NoBasemap)
  }

  test("snapshots stay offline while the setting is on, even if a detailed map is open") {
    assertEquals(BaseMapPolicy.snapshot(true, Gsi, Offline, true), Draw(Offline))
    assertEquals(BaseMapPolicy.snapshot(false, Gsi, Offline, true), Draw(Gsi))
  }

  test("snapshots without the basemap: none while the setting is on, never an online map instead") {
    assertEquals(BaseMapPolicy.snapshot(true, Gsi, Offline, false), NoBasemap)
    assertEquals(BaseMapPolicy.snapshot(true, Offline, Offline, false), NoBasemap)
    assertEquals(BaseMapPolicy.snapshot(false, Gsi, Offline, false), Draw(Gsi))
    assertEquals(BaseMapPolicy.snapshot(false, Offline, Offline, false), NoBasemap)
  }

  test("thumbnails: only the offline map while the setting is on, and only with its data") {
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(true, _, Offline, true)), Seq(Offline))
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(false, _, Offline, true)), Seq(Offline, Osm, Gsi))
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(true, _, Offline, false)), Seq.empty)
    assertEquals(Seq(Offline, Osm, Gsi).filter(BaseMapPolicy.thumbnail(false, _, Offline, false)), Seq(Osm, Gsi))
  }

  test("download and detail are offered only where the setting kept the online map away") {
    assert(BaseMapPolicy.offerAlternatives(block = true, NoBasemap))
    assert(!BaseMapPolicy.offerAlternatives(block = false, NoBasemap))
    assert(!BaseMapPolicy.offerAlternatives(block = true, Draw(Offline)))
    assert(!BaseMapPolicy.offerAlternatives(block = false, Draw(Gsi)))
  }

  test("network only with the setting off or a detailed map open") {
    assert(!BaseMapPolicy.network(block = true, detailedOpen = false))
    assert(BaseMapPolicy.network(block = true, detailedOpen = true))
    assert(BaseMapPolicy.network(block = false, detailedOpen = false))
  }
