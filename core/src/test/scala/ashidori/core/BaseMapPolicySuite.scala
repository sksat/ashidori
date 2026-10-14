package ashidori.core

import BaseMapPolicy.Outcome.{Draw, NoBasemap}

class BaseMapPolicySuite extends munit.FunSuite:
  private val Offline = "offline"
  private val Osm = "ofm-liberty"
  private val Gsi = "gsi-std"

  test("setting on: interactive maps are offline unless the surface holds a consent") {
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, true, consent = None, onScreen = true), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
  }

  test("setting on: a surface shows the online map consented to there, not the global choice made elsewhere") {
    // The map tab consented to GSI; another surface then chose OSM globally.
    assertEquals(BaseMapPolicy.interactive(true, Osm, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
    // The offline map chosen globally does not end another surface's consent.
    assertEquals(BaseMapPolicy.interactive(true, Offline, Offline, true, consent = Some(Osm), onScreen = true), Draw(Osm))
  }

  test("setting on: a map off screen drops its online map, and gets the consent back when shown (F5)") {
    // Hidden: the on-device map (or none without it), so no request of its own is left.
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, true, consent = Some(Gsi), onScreen = false), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, false, consent = Some(Gsi), onScreen = false), NoBasemap)
    // Shown again: the consent still holds.
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
    // Setting off: the user's choice, on screen or not.
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Offline, true, consent = None, onScreen = false), Draw(Gsi))
  }

  test("custom tile URLs: http(s) with {z} {x} {y}, one line only") {
    assert(BaseMapPolicy.validTileUrl("https://t.example/{z}/{x}/{y}.png?key=abc"))
    assert(!BaseMapPolicy.validTileUrl("https://t.example/{z}/{x}.png"))
    assert(!BaseMapPolicy.validTileUrl("ftp://t.example/{z}/{x}/{y}.png"))
    // A newline and tab could forge another stored entry.
    assert(!BaseMapPolicy.validTileUrl("https://t.example/{z}/{x}/{y}.png\nperiod\tgsi-std"))
    assert(!BaseMapPolicy.validTileUrl("https://t.example/{z}/{x}/{y}.png x"))
  }

  test("detail asks for the chosen online map, or the last online one when the offline map is chosen") {
    assertEquals(BaseMapPolicy.detailMap(Gsi, Osm, Offline), Gsi)
    assertEquals(BaseMapPolicy.detailMap(Offline, Osm, Offline), Osm)
  }

  test("setting off: the user's choice applies, including the offline map, whatever the consent") {
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Offline, true, consent = None, onScreen = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Offline, true, consent = None, onScreen = true), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Offline, true, consent = Some(Osm), onScreen = true), Draw(Offline))
  }

  test("setting on without the basemap: no base map unless the surface holds a consent") {
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, false, consent = None, onScreen = true), NoBasemap)
    assertEquals(BaseMapPolicy.interactive(true, Offline, Offline, false, consent = None, onScreen = true), NoBasemap)
    assertEquals(BaseMapPolicy.interactive(true, Gsi, Offline, false, consent = Some(Gsi), onScreen = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(true, Offline, Offline, false, consent = Some(Osm), onScreen = true), Draw(Osm))
  }

  test("setting off without the basemap: an online choice is unaffected, the offline one has no base map") {
    assertEquals(BaseMapPolicy.interactive(false, Gsi, Offline, false, consent = None, onScreen = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(false, Offline, Offline, false, consent = None, onScreen = true), NoBasemap)
    // Never an online map the user did not choose.
    assertEquals(BaseMapPolicy.interactive(false, Offline, Offline, false, consent = Some(Osm), onScreen = true), NoBasemap)
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
