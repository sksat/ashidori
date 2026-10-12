package ashidori.core

import ashidori.core.sim.*

class TimelineJsonSuite extends munit.FunSuite:
  private val zone = Scenarios.Tokyo

  test("export then import roundtrips visits, activities and path points") {
    val out = TrajectorySimulator.run(Scenarios.commute())
    val segs = TimelineBuilder.build(out.fixes, out.motion)
    val home = Place(1, Scenarios.Home.lat, Scenarios.Home.lon, 80, "自宅", PlaceKind.Home)
    val linked = segs.map {
      case v: Visit if v.latLng.distanceTo(Scenarios.Home) < 200 => v.copy(placeId = Some(1L))
      case s                                                     => s
    }
    val json = TimelineJson.exportTimeline(linked, Map(1L -> home), zone)
    assert(json.contains("\"semanticSegments\""))
    assert(json.contains("+09:00"))
    val back = TimelineJson.importAny(json)
    assertEquals(back.visits.size, segs.count(_.isInstanceOf[Visit]))
    assert(back.visits.exists((_, name, kind) => name.contains("自宅") && kind == PlaceKind.Home))
    assert(back.activities.map(_._3).contains(TransportMode.Train))
    assert(back.fixes.nonEmpty)
    assert(back.fixes.map(_.time) == back.fixes.map(_.time).sorted)
  }

  test("imports Android on-device Timeline.json with rawSignals") {
    val json =
      """{"semanticSegments":[
        |{"startTime":"2024-05-01T09:00:00.000+09:00","endTime":"2024-05-01T10:00:00.000+09:00",
        | "visit":{"hierarchyLevel":0,"probability":0.9,"topCandidate":{"placeId":"ChIJ","semanticType":"TYPE_WORK","probability":0.8,"placeLocation":{"latLng":"35.6812°, 139.7671°"}}}},
        |{"startTime":"2024-05-01T10:00:00.000+09:00","endTime":"2024-05-01T10:30:00.000+09:00",
        | "activity":{"start":{"latLng":"35.6812°, 139.7671°"},"end":{"latLng":"35.6580°, 139.7016°"},"distanceMeters":7000,"topCandidate":{"type":"IN_SUBWAY","probability":0.7}}},
        |{"startTime":"2024-05-01T10:00:00.000+09:00","endTime":"2024-05-01T12:00:00.000+09:00",
        | "timelinePath":[{"point":"35.67°, 139.73°","time":"2024-05-01T10:15:00.000+09:00"}]}
        |],
        |"rawSignals":[{"position":{"LatLng":"35.6581°, 139.7017°","accuracyMeters":12,"source":"GPS","timestamp":"2024-05-01T10:31:00.000+09:00"}}]}""".stripMargin
    val r = TimelineJson.importAny(json)
    assertEquals(r.visits.head._3, PlaceKind.Work)
    assertEquals(r.activities.head._3, TransportMode.Subway)
    assertEquals(r.fixes.size, 4)
    assertEqualsDouble(r.fixes.last.accuracy.toDouble, 12.0, 0.01)
  }

  test("imports legacy Takeout Records.json") {
    val json = """{"locations":[{"latitudeE7":356812000,"longitudeE7":1397671000,"accuracy":20,"timestamp":"2019-01-01T00:00:00Z"},
                 |{"latitudeE7":356580000,"longitudeE7":1397016000,"timestampMs":"1546300860000"}]}""".stripMargin
    val r = TimelineJson.importAny(json)
    assertEquals(r.fixes.size, 2)
    assertEqualsDouble(r.fixes.head.lat, 35.6812, 1e-6)
  }

  test("GPX skips private check-ins and escapes names") {
    val v = Visit(0, 600_000, 35.0, 139.0, 30, 3, Some(1L))
    val ci = Seq(
      CheckIn(1, 100, 35.0, 139.0, "A & B", comment = "<hi>"),
      CheckIn(2, 200, 35.0, 139.0, "Secret", isPrivate = true)
    )
    val gpx = TimelineJson.exportGpx(Seq(v), Map(1L -> Place(1, 35, 139, 50, "Café")), ci)
    assert(gpx.contains("A &amp; B"))
    assert(gpx.contains("&lt;hi&gt;"))
    assert(!gpx.contains("Secret"))
    assert(gpx.contains("Café"))
  }
