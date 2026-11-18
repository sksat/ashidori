package ashidori.core

import BaseMapPolicy.Outcome.{Draw, NoBasemap}

class BaseMapPolicySuite extends munit.FunSuite:
  private val Offline = "offline"
  private val Osm = "ofm-liberty"
  private val Gsi = "gsi-std"
  private def rules(osm: When, gsi: When): String => When = m => if m == Osm then osm else gsi
  private val onAction = rules(When.OnAction, When.OnAction)
  private val auto = rules(When.Auto, When.Auto)

  test("on action: interactive maps are offline unless the surface holds a consent") {
    assertEquals(BaseMapPolicy.interactive(onAction, Gsi, Offline, true, consent = None, onScreen = true), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(onAction, Gsi, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
  }

  test("on action: a surface shows the online map consented to there, not the global choice made elsewhere") {
    assertEquals(BaseMapPolicy.interactive(onAction, Osm, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(onAction, Offline, Offline, true, consent = Some(Osm), onScreen = true), Draw(Osm))
  }

  test("on action: a map off screen drops its online map, and gets the consent back when shown") {
    assertEquals(BaseMapPolicy.interactive(onAction, Gsi, Offline, true, consent = Some(Gsi), onScreen = false), Draw(Offline))
    assertEquals(BaseMapPolicy.interactive(onAction, Gsi, Offline, false, consent = Some(Gsi), onScreen = false), NoBasemap)
    assertEquals(BaseMapPolicy.interactive(onAction, Gsi, Offline, true, consent = Some(Gsi), onScreen = true), Draw(Gsi))
    // Automatic: the user's choice, on screen or not.
    assertEquals(BaseMapPolicy.interactive(auto, Gsi, Offline, true, consent = None, onScreen = false), Draw(Gsi))
  }

  test("per destination: an automatic chosen map is shown; a never one is not, even with an old consent") {
    val mixed = rules(When.Auto, When.Never)
    assertEquals(BaseMapPolicy.interactive(mixed, Osm, Offline, true, None, onScreen = true), Draw(Osm))
    assertEquals(BaseMapPolicy.interactive(mixed, Gsi, Offline, true, Some(Gsi), onScreen = true), Draw(Offline))
    assertEquals(BaseMapPolicy.snapshot(mixed, Gsi, Offline, true), Draw(Offline))
    assertEquals(BaseMapPolicy.snapshot(mixed, Osm, Offline, true), Draw(Osm))
    // Thumbnails: each candidate by its own rule.
    assert(BaseMapPolicy.thumbnail(mixed, Osm, Offline, true))
    assert(!BaseMapPolicy.thumbnail(mixed, Gsi, Offline, true))
    assert(!BaseMapPolicy.thumbnail(rules(When.OnAction, When.Auto), Osm, Offline, true))
    assert(BaseMapPolicy.thumbnail(mixed, Offline, Offline, true) && !BaseMapPolicy.thumbnail(mixed, Offline, Offline, false))
  }

  test("custom tile URLs: http(s) with {z} {x} {y}, one line only") {
    assert(BaseMapPolicy.validTileUrl("https://t.example.net/{z}/{x}/{y}.png?key=abc"))
    assert(!BaseMapPolicy.validTileUrl("https://t.example.net/{z}/{x}.png"))
    assert(!BaseMapPolicy.validTileUrl("ftp://t.example.net/{z}/{x}/{y}.png"))
    assert(!BaseMapPolicy.validTileUrl("https://t.example.net/{z}/{x}/{y}.png\nperiod\tgsi-std"))
    assert(!BaseMapPolicy.validTileUrl("https://t.example.net/{z}/{x}/{y}.png x"))
  }

  test("detail asks for the chosen online map, the last online one for the offline map, never a never one") {
    assertEquals(BaseMapPolicy.detailMap(onAction, Gsi, Osm, Offline, Seq(Osm, Gsi)), Some(Gsi))
    assertEquals(BaseMapPolicy.detailMap(onAction, Offline, Osm, Offline, Seq(Osm, Gsi)), Some(Osm))
    assertEquals(BaseMapPolicy.detailMap(rules(When.OnAction, When.Never), Gsi, Osm, Offline, Seq(Osm, Gsi)), Some(Osm))
    assertEquals(BaseMapPolicy.detailMap(rules(When.Never, When.Never), Gsi, Osm, Offline, Seq(Osm, Gsi)), None)
  }

  test("the download and the per-view online map are offered only where an on-action map would be shown") {
    assert(BaseMapPolicy.offerAlternatives(onAction, Some(Gsi), NoBasemap))
    assert(!BaseMapPolicy.offerAlternatives(onAction, Some(Gsi), Draw(Offline)))
    assert(!BaseMapPolicy.offerAlternatives(auto, Some(Gsi), NoBasemap))
    assert(!BaseMapPolicy.offerAlternatives(onAction, None, NoBasemap))
  }

  test("automatic: the user's choice applies, including the offline map, whatever the consent") {
    assertEquals(BaseMapPolicy.interactive(auto, Gsi, Offline, true, consent = None, onScreen = true), Draw(Gsi))
    assertEquals(BaseMapPolicy.interactive(auto, Offline, Offline, true, consent = None, onScreen = true), Draw(Offline))
  }
