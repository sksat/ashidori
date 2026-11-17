package ashidori.core

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter

/** Google Maps Timeline compatible import/export.
  *
  * Export writes the Android on-device `Timeline.json` shape
  * (`semanticSegments` with `visit` / `activity` / `timelinePath`). Import
  * reads that shape, the iOS variant (a bare array of segments) and the legacy
  * Takeout `Records.json` (`locations` with E7 coordinates).
  */
object TimelineJson:
  private val Iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

  private def time(t: Long, zone: ZoneId): String = Instant.ofEpochMilli(t).atZone(zone).format(Iso)
  private def offsetMin(t: Long, zone: ZoneId): Int = zone.getRules.getOffset(Instant.ofEpochMilli(t)).getTotalSeconds / 60

  def exportTimeline(segments: Seq[Segment], places: Map[Long, Place], zone: ZoneId): String =
    val segs = segments.flatMap {
      case v: Visit =>
        val place = v.placeId.flatMap(places.get)
        val top = ujson.Obj(
          "placeId" -> place.map(p => if p.externalId.nonEmpty then p.externalId else s"ashidori:${p.id}").getOrElse(""),
          "semanticType" -> place.map(_.kind.key).getOrElse(PlaceKind.Other.key),
          "probability" -> v.confidence,
          "placeLocation" -> ujson.Obj("latLng" -> v.latLng.toGoogleString)
        )
        place.filter(_.name.nonEmpty).foreach(p => top("name") = p.name)
        Seq(base(v, zone) ++ ujson.Obj("visit" -> ujson.Obj("hierarchyLevel" -> 0, "probability" -> v.confidence, "topCandidate" -> top)))
      case t: Trip =>
        val legs = t.legs.filter(_.mode != TransportMode.Still).map { l =>
          val pts = t.path.filter(f => f.time >= l.start && f.time <= l.end)
          val a = pts.headOption.getOrElse(t.path.head)
          val b = pts.lastOption.getOrElse(t.path.last)
          base(l, zone) ++ ujson.Obj(
            "activity" -> ujson.Obj(
              "start" -> ujson.Obj("latLng" -> a.latLng.toGoogleString),
              "end" -> ujson.Obj("latLng" -> b.latLng.toGoogleString),
              "distanceMeters" -> l.distanceM,
              "probability" -> l.confidence,
              "topCandidate" -> ujson.Obj("type" -> l.mode.key, "probability" -> l.confidence)
            )
          )
        }
        val path = base(t, zone) ++ ujson.Obj(
          "timelinePath" -> ujson.Arr.from(Geo.simplify(t.path, 5).map(f =>
            ujson.Obj("point" -> f.latLng.toGoogleString, "time" -> time(f.time, zone))
          ))
        )
        legs :+ path
      // Google's format has nothing for a stretch without a record: left out.
      case _: Unrecorded => Seq.empty
    }
    ujson.write(ujson.Obj("semanticSegments" -> ujson.Arr.from(segs)), indent = 1)

  private def base(s: Segment, zone: ZoneId): ujson.Obj = ujson.Obj(
    "startTime" -> time(s.start, zone),
    "endTime" -> time(s.end, zone),
    "startTimeTimezoneUtcOffsetMinutes" -> offsetMin(s.start, zone),
    "endTimeTimezoneUtcOffsetMinutes" -> offsetMin(s.end, zone)
  )

  extension (a: ujson.Obj) private def ++(b: ujson.Obj): ujson.Obj =
    val o = ujson.Obj()
    a.value.foreach((k, v) => o(k) = v)
    b.value.foreach((k, v) => o(k) = v)
    o

  /** What an import produced. Raw fixes are stored like logged ones; visits
    * and activities are kept for reference (and for mode corrections).
    */
  final case class Imported(
      fixes: Vector[Fix],
      visits: Vector[(Visit, Option[String], PlaceKind)],
      activities: Vector[(Long, Long, TransportMode)]
  )

  private def parseTime(s: String): Long = OffsetDateTime.parse(s).toInstant.toEpochMilli

  private def latLngOf(v: ujson.Value): Option[LatLng] = v match
    case ujson.Str(s) => LatLng.parseGoogle(s.stripPrefix("geo:"))
    case o: ujson.Obj =>
      o.value.get("latLng").flatMap(latLngOf)
        .orElse(o.value.get("LatLng").flatMap(latLngOf))
        .orElse(o.value.get("point").flatMap(latLngOf))
    case _ => None

  def importAny(json: String): Imported =
    val root = ujson.read(json)
    root match
      case o: ujson.Obj if o.value.contains("locations")        => importRecords(o)
      case o: ujson.Obj if o.value.contains("semanticSegments") => importSegments(o("semanticSegments").arr.toSeq, o.value.get("rawSignals"))
      case a: ujson.Arr                                         => importSegments(a.arr.toSeq, None)
      case _ => throw IllegalArgumentException("Timeline.json / Records.json の形式ではありません")

  private def importSegments(segs: Seq[ujson.Value], raw: Option[ujson.Value]): Imported =
    val fixes = Vector.newBuilder[Fix]
    val visits = Vector.newBuilder[(Visit, Option[String], PlaceKind)]
    val acts = Vector.newBuilder[(Long, Long, TransportMode)]
    for s <- segs; o = s.obj do
      val start = o.get("startTime").map(v => parseTime(v.str))
      val end = o.get("endTime").map(v => parseTime(v.str))
      for v <- o.get("visit"); st <- start; en <- end do
        val top = v.obj.get("topCandidate").map(_.obj)
        for loc <- top.flatMap(_.get("placeLocation")).flatMap(latLngOf) do
          val prob = top.flatMap(_.get("probability")).flatMap(_.numOpt).getOrElse(0.5)
          val kind = top.flatMap(_.get("semanticType")).map(_.str.stripPrefix("TYPE_")).map(PlaceKind.fromKey).getOrElse(PlaceKind.Other)
          val name = top.flatMap(_.get("name")).flatMap(_.strOpt)
          visits += ((Visit(st, en, loc.lat, loc.lon, 50, 1, None, prob), name, kind))
      for a <- o.get("activity"); st <- start; en <- end do
        val mode = a.obj.get("topCandidate").flatMap(_.obj.get("type")).map(t => TransportMode.fromKey(t.str)).getOrElse(TransportMode.Unknown)
        acts += ((st, en, mode))
        for p <- a.obj.get("start").flatMap(latLngOf) do fixes += Fix(st, p.lat, p.lon, provider = "import")
        for p <- a.obj.get("end").flatMap(latLngOf) do fixes += Fix(en, p.lat, p.lon, provider = "import")
      for path <- o.get("timelinePath"); st <- start do
        for pt <- path.arr; p <- latLngOf(pt) do
          val t = pt.obj.get("time").map(v => parseTime(v.str))
            .orElse(pt.obj.get("durationMinutesOffsetFromStartTime").map(v => st + v.str.toLong * 60_000))
            .getOrElse(st)
          fixes += Fix(t, p.lat, p.lon, provider = "import")
    for r <- raw.toSeq.flatMap(_.arr); pos <- r.obj.get("position") do
      for p <- latLngOf(pos); ts <- pos.obj.get("timestamp") do
        fixes += Fix(
          parseTime(ts.str),
          p.lat,
          p.lon,
          pos.obj.get("accuracyMeters").flatMap(_.numOpt).map(_.toFloat).getOrElse(Float.NaN),
          pos.obj.get("altitudeMeters").flatMap(_.numOpt).getOrElse(Double.NaN),
          pos.obj.get("speedMetersPerSecond").flatMap(_.numOpt).map(_.toFloat).getOrElse(Float.NaN),
          provider = "import"
        )
    Imported(fixes.result().sortBy(_.time), visits.result(), acts.result())

  private def importRecords(o: ujson.Obj): Imported =
    val fixes = o("locations").arr.toVector.flatMap { l =>
      val m = l.obj
      for
        lat <- m.get("latitudeE7").flatMap(_.numOpt)
        lon <- m.get("longitudeE7").flatMap(_.numOpt)
        t <- m.get("timestamp").map(v => parseTime(v.str)).orElse(m.get("timestampMs").map(_.str.toLong))
      yield Fix(
        t,
        lat / 1e7,
        lon / 1e7,
        m.get("accuracy").flatMap(_.numOpt).map(_.toFloat).getOrElse(Float.NaN),
        m.get("altitude").flatMap(_.numOpt).getOrElse(Double.NaN),
        m.get("velocity").flatMap(_.numOpt).map(_.toFloat).getOrElse(Float.NaN),
        m.get("heading").flatMap(_.numOpt).map(_.toFloat).getOrElse(Float.NaN),
        "import"
      )
    }
    Imported(fixes.sortBy(_.time), Vector.empty, Vector.empty)

  /** GPX 1.1 with one track per trip and waypoints for visits and check-ins. */
  def exportGpx(segments: Seq[Segment], places: Map[Long, Place], checkIns: Seq[CheckIn]): String =
    def esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    def iso(t: Long) = Instant.ofEpochMilli(t).toString
    val sb = StringBuilder()
    sb ++= """<?xml version="1.0" encoding="UTF-8"?>""" + "\n"
    sb ++= """<gpx version="1.1" creator="ashidori" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n"
    for case v: Visit <- segments do
      val name = v.placeId.flatMap(places.get).map(_.displayName).getOrElse("滞在")
      sb ++= f"""  <wpt lat="${v.lat}%.7f" lon="${v.lon}%.7f"><time>${iso(v.start)}</time><name>${esc(name)}</name><type>visit</type></wpt>""" + "\n"
    for c <- checkIns if !c.isPrivate do
      sb ++= f"""  <wpt lat="${c.lat}%.7f" lon="${c.lon}%.7f"><time>${iso(c.time)}</time><name>${esc(c.venueName)}</name><desc>${esc(c.comment)}</desc><type>checkin</type></wpt>""" + "\n"
    for case t: Trip <- segments do
      sb ++= s"  <trk><name>${esc(t.legs.map(_.mode.label).distinct.mkString(" → "))}</name><type>${t.mainMode.key}</type><trkseg>\n"
      for f <- t.path do
        sb ++= f"""    <trkpt lat="${f.lat}%.7f" lon="${f.lon}%.7f">""" +
          (if f.altitude.isNaN then "" else f"<ele>${f.altitude}%.1f</ele>") + s"<time>${iso(f.time)}</time></trkpt>\n"
      sb ++= "  </trkseg></trk>\n"
    sb ++= "</gpx>\n"
    sb.toString
