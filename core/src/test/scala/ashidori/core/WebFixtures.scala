package ashidori.core

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.{LocalDate, ZoneId}
import ashidori.core.parquet.*
import ashidori.core.sim.*
import ashidori.core.sync.{BatchMeta, BlobKeys, CutRef, CutoverRecord, Crypto, DeviceStatus, StatusFix}

/** Writes the fixtures of the web viewer's tests (server/web/test/fixtures)
  * with the app's own code: Parquet files from [[ParquetWriter]], batches
  * sealed by [[Crypto]], and the timeline [[TimelineBuilder]] builds from
  * the same rows. The viewer's readers are tested against these, so they
  * follow the real formats rather than a reimplementation of them.
  *
  * Run with `./gradlew :core:webFixtures`. Sealed blobs use random nonces,
  * so every run changes them; everything else is deterministic.
  *
  * The batches of `sync/` mix the two formats as a real history does: the
  * oldest are ASH1 (written before v1.1), the rest ASHM, and devA's
  * cut-over record (`authcut`) sits between them, named by its last status.
  */
object WebFixtures:
  private val Zone: ZoneId = Scenarios.Tokyo
  private val Date: LocalDate = LocalDate.of(2026, 10, 5)

  /** Fixed master key of the fixtures (never a real one). */
  val MasterKey: Array[Byte] = Array.tabulate(32)(i => (i * 7 + 1).toByte)

  def main(args: Array[String]): Unit =
    args.toList match
      // Demo data for previewing the viewer (not committed): `--demo <dir> <days>`.
      case "--demo" :: dir :: days :: _ => writeDemo(File(dir), days.toInt)
      case _                            => writeFixtures(File(args.headOption.getOrElse("server/web/test/fixtures")))

  /** A user's batches over the last `days` days (commutes on weekdays, outings on
    * weekends, some days without a record), in the layout of `sync/`.
    */
  private def writeDemo(dir: File, days: Int): Unit =
    val blobs = File(dir, "blobs")
    blobs.mkdirs()
    blobs.listFiles().foreach(_.delete())
    val keys = BlobKeys.of(MasterKey)
    val key = keys.batch
    val devA = "0f5c2a8e-7d1b-4c3e-9a61-2b7d5e8f4a10"
    val devB = "6a3e9b0c-1f2d-4e5a-8b7c-9d0e1f2a3b4c"
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    var cursor = 100L
    val seq = scala.collection.mutable.Map.empty[(String, String), Long].withDefaultValue(0L)
    val items = Vector.newBuilder[ujson.Value]
    def put(dev: String, stream: String, records: Seq[ujson.Value]): Unit =
      val s = seq((dev, stream))
      seq((dev, stream)) = s + 1
      val plain = records.map(r => ujson.write(r) + "\n").mkString.getBytes(UTF_8)
      val blob = Crypto.seal(keys, Crypto.aad(dev, stream, s), Crypto.gzip(plain))
      Files.write(File(blobs, s"$dev.$stream.$s").toPath, blob)
      cursor += 3
      items += ujson.Obj("cursor" -> num(cursor), "deviceId" -> dev, "stream" -> stream, "seq" -> num(s),
        "size" -> blob.length, "sha256" -> Crypto.sha256Hex(blob), "createdAt" -> num((now / hour) * hour))
    val today = LocalDate.now(Zone)
    for i <- (1 to days).reverse do
      val date = today.minusDays(i)
      if i % 9 != 4 then
        val weekend = date.getDayOfWeek.getValue >= 6
        val sc = if weekend then Scenarios.weekend(date, seed = i) else Scenarios.commute(date, seed = i)
        val sim = TrajectorySimulator.run(sc)
        for (t, rows) <- Seq(Archive.Fixes -> sim.fixes.map(Archive.fixRow), Archive.Motion -> sim.motion.map(Archive.motionRow)) do
          val pq = parquet(t.columns, rows)
          put(devA, "archive", Seq(ujson.Obj("file" -> s"${t.name}/$date.parquet", "gen" -> f"${i * 31 + t.name.length}%x",
            "part" -> 0, "parts" -> 1, "data" -> java.util.Base64.getEncoder.encodeToString(pq))))
    val sc = Scenarios
    put(devA, "places", Seq(
      ujson.Obj("id" -> 1, "lat" -> sc.Home.lat, "lon" -> sc.Home.lon, "r" -> 60.0, "name" -> "", "kind" -> "HOME", "ext" -> "", "updatedAt" -> num(now)),
      ujson.Obj("id" -> 2, "lat" -> sc.Office.lat, "lon" -> sc.Office.lon, "r" -> 80.0, "name" -> "有明オフィス", "kind" -> "WORK", "ext" -> "", "updatedAt" -> num(now)),
      ujson.Obj("id" -> 3, "lat" -> sc.Park.lat, "lon" -> sc.Park.lon, "r" -> 150.0, "name" -> "洞峰公園", "kind" -> "UNKNOWN", "ext" -> "", "updatedAt" -> num(now))))
    val lunch = today.minusDays(1).atTime(12, 50).atZone(Zone).toInstant.toEpochMilli
    put(devA, "checkins", Seq(ujson.Obj("id" -> 7, "t" -> num(lunch), "lat" -> sc.Lunch.lat, "lon" -> sc.Lunch.lon, "venue" -> "有明ガーデン",
      "cat" -> "", "ext" -> "", "place" -> ujson.Null, "comment" -> "", "private" -> 0, "updatedAt" -> num(lunch))))
    def status(t: Long, pct: Int, on: Boolean, at: LatLng, model: String) =
      DeviceStatus(t, pct, charging = !on, recording = on, mode = if on then "balanced" else "", since = if on then t - 72 * hour else 0L,
        lastFix = Some(StatusFix(t - 60_000, at.lat, at.lon, 12.0)), versionName = "0.1.0", versionCode = 12, model = model).toJson
    put(devA, "status", Seq(status(now - 15 * 60_000, 78, on = true, sc.Office, "Google Pixel 8a")))
    put(devB, "status", Seq(status(now - 5 * hour, 100, on = false, sc.Home, "Google Pixel Tablet")))
    write(dir, "listing.json", ujson.write(ujson.Obj("items" -> ujson.Arr.from(items.result()), "next" -> ujson.Null)))
    write(dir, "meta.json", ujson.write(ujson.Obj(
      "batchKeyHex" -> key.map(b => f"${b & 0xff}%02x").mkString,
      "devices" -> ujson.Arr(
        ujson.Obj("id" -> devA, "name" -> "Pixel 8a", "createdAt" -> num(now - 30 * 24 * hour), "lastSeenAt" -> num(now), "revoked" -> false),
        ujson.Obj("id" -> devB, "name" -> "Pixel Tablet", "createdAt" -> num(now - 20 * 24 * hour), "lastSeenAt" -> num(now - 5 * hour), "revoked" -> false)))))

  private def writeFixtures(dir: File): Unit =
    dir.mkdirs()

    // --- Parquet: the archive tables of a simulated day --------------------
    val sim = TrajectorySimulator.run(Scenarios.commute(Date))
    // As the app writes archive files: the current layout, with the table
    // and the archive schema in the footer.
    val fixesPq = parquet(Archive.Fixes.columns, sim.fixes.map(Archive.fixRow), table = Some(Archive.Fixes))
    // Small row groups so that a reader must handle several of them.
    val motionPq = parquet(Archive.Motion.columns, sim.motion.map(Archive.motionRow), rowGroupRows = 500, table = Some(Archive.Motion))
    write(dir, "commute-fixes.parquet", fixesPq)
    write(dir, "commute-motion.parquet", motionPq)
    write(dir, "commute-fixes.rows.json", rowsJson(fixesPq))
    write(dir, "commute-motion.rows.json", rowsJson(motionPq))

    // Edge cases of the encodings: large and wrapping deltas, extremes, nulls, -0.0,
    // non-ASCII strings, an all-null column, row groups of 128 rows.
    val edgeCols = Seq(
      Column("i64", ColType.Int64), Column("i32", ColType.Int32), Column("f32", ColType.Float32),
      Column("f64", ColType.Float64), Column("s", ColType.Str), Column("none", ColType.Float64))
    val edgeRows = (0 until 300).map { i =>
      val i64 = i match
        // Not Long.MaxValue/MinValue: ParquetWriter's DELTA_BINARY_PACKED
        // packs a 64-bit-wide miniblock wrongly (a negative BigInt), which
        // only deltas of 2^63 and more reach, never timestamps.
        case 0 => 1L << 60
        case 1 => -(1L << 60)
        case 2 => 0L
        case _ => 1_700_000_000_000L + i * 997L * (if i % 2 == 0 then 1 else -1)
      val i32 = i match
        case 0 => Int.MaxValue
        case 1 => Int.MinValue
        case _ => (i * 31) - 4000
      val f32 = if i % 11 == 0 then Float.NaN else if i == 3 then -0.0f else (i * 0.1f) - 7.25f
      val f64 = if i % 13 == 0 then null else if i == 4 then -0.0 else math.sin(i.toDouble) * 1e5
      val s = if i % 17 == 0 then null else if i % 5 == 0 then "" else s"行$i ✓"
      Seq(i64, i32, f32, f64, s, null)
    }
    val edgePq = parquet(edgeCols, edgeRows, rowGroupRows = 128)
    write(dir, "edge.parquet", edgePq)
    write(dir, "edge.rows.json", rowsJson(edgePq))

    // --- The timeline the app builds from those rows ----------------------
    val fixes = ParquetReader.read(fixesPq).rowIterator.map(Archive.fixOf).toVector
    val motion = ParquetReader.read(motionPq).rowIterator.map(Archive.motionOf).toVector
    val built = TimelineBuilder.build(fixes, motion)
    // One correction, as in the overrides stream: the first vehicle leg.
    val firstVehicle = built.collect { case t: Trip => t.legs }.flatten.find(_.mode.isVehicle)
    val overrides = firstVehicle.toVector.map(l => (l.start, l.end, TransportMode.Car))
    val corrected = TimelineBuilder.build(fixes, motion, overrides = overrides.map((s, e, m) => ModeOverride(s, e, m)))
    val segs = Stats.splitByDay(corrected, Zone).getOrElse(Date, Vector.empty)
    val dayStart = Date.atStartOfDay(Zone).toInstant.toEpochMilli
    val dayEnd = Date.plusDays(1).atStartOfDay(Zone).toInstant.toEpochMilli
    val steps = motion.filter(m => m.start >= dayStart && m.start < dayEnd).map(_.steps).sum
    val summary = Stats.day(Date, segs, steps)
    write(dir, "commute-timeline.json", ujson.write(ujson.Obj(
      "date" -> Date.toString,
      "zone" -> Zone.getId,
      "overrides" -> ujson.Arr.from(overrides.map((s, e, m) => ujson.Obj("s" -> num(s), "e" -> num(e), "mode" -> m.key))),
      "raw" -> ujson.Arr.from(built.map(segJson(_))),
      "segments" -> ujson.Arr.from(segs.map(segJson(_))),
      "summary" -> ujson.Obj(
        "distanceByMode" -> ujson.Obj.from(summary.distanceByMode.map((m, d) => m.key -> ujson.Num(d))),
        "durationByMode" -> ujson.Obj.from(summary.durationByMode.map((m, d) => m.key -> num(d))),
        "steps" -> summary.steps,
        "visits" -> summary.visits,
        "unrecordedMs" -> num(summary.unrecordedMs)
      )
    ), indent = 1))

    // --- Stretches without a record (GapDay): what the app builds ---------
    val gap = GapDay.build()
    val gapSegs = TimelineBuilder.build(gap.fixes, gap.motion)
    val gapDay = Stats.splitByDay(gapSegs, GapDay.Zone).getOrElse(GapDay.Date, Vector.empty)
    val gapSummary = Stats.day(GapDay.Date, gapDay, 0)
    write(dir, "gaps-timeline.json", ujson.write(ujson.Obj(
      "date" -> GapDay.Date.toString,
      "fixes" -> ujson.Arr.from(gap.fixes.map(f => ujson.Arr(num(f.time), f.lat, f.lon, f.accuracy.toDouble))),
      "motion" -> ujson.Arr.from(gap.motion.map(m => ujson.Arr(num(m.start), num(m.end), m.accelStd.toDouble, m.accelMean.toDouble, m.steps))),
      "segments" -> ujson.Arr.from(gapSegs.map(segJson(_))),
      "summary" -> ujson.Obj(
        "totalDistanceM" -> gapSummary.totalDistanceM,
        "movingMs" -> num(gapSummary.movingMs),
        "unrecordedMs" -> num(gapSummary.unrecordedMs)
      )
    )))

    // --- Adjacent legs of one mode merged (MergeDay), with corrections --------
    val md = MergeDay.build()
    val split = TimelineBuilder.unmerged(md.fixes, md.motion).collect { case t: Trip => t.legs.filter(_.mode != TransportMode.Still) }
    def tripAt(h: Int) = split.find(_.head.start >= MergeDay.at(h, 0)).get
    val noon = tripAt(12)
    val merged = TimelineBuilder.build(md.fixes, md.motion).collect { case t: Trip => t.legs }.flatten
      .find(l => l.start == noon.head.start).get
    val cases = Seq(
      "none" -> Seq.empty,
      "onePart" -> Seq(ModeOverride(noon(0).start, noon(0).end, TransportMode.Bus, 1)),
      "bothParts" -> Seq(ModeOverride(noon(0).start, noon(0).end, TransportMode.Bus, 1), ModeOverride(noon(1).start, noon(1).end, TransportMode.Bus, 2)),
      "wholeLeg" -> Seq(ModeOverride(merged.start, merged.end, TransportMode.Train, 3)),
      "undone" -> Seq(ModeOverride(merged.start, merged.end, TransportMode.Train, 3), ModeOverride(merged.start, merged.end, TransportMode.Car, 4)),
      "joinNeighbour" -> Seq(ModeOverride(tripAt(11).head.start, tripAt(11).head.end, TransportMode.Car, 5))
    )
    write(dir, "merge-timeline.json", ujson.write(ujson.Obj(
      "date" -> MergeDay.Date.toString,
      "fixes" -> ujson.Arr.from(md.fixes.map(f => ujson.Arr(num(f.time), f.lat, f.lon, f.accuracy.toDouble))),
      "motion" -> ujson.Arr.from(md.motion.map(m => ujson.Arr(num(m.start), num(m.end), m.accelStd.toDouble, m.accelMean.toDouble, m.steps))),
      "cases" -> ujson.Arr.from(cases.map { (name, os) =>
        val segs = TimelineBuilder.build(md.fixes, md.motion, overrides = os)
        val sum = Stats.day(MergeDay.Date, Stats.splitByDay(segs, MergeDay.Zone).getOrElse(MergeDay.Date, Vector.empty), 0)
        ujson.Obj(
          "name" -> name,
          "overrides" -> ujson.Arr.from(os.map(o => ujson.Obj("s" -> num(o.start), "e" -> num(o.end), "mode" -> o.mode.key, "updatedAt" -> num(o.updatedAt)))),
          "segments" -> ujson.Arr.from(segs.map(segJson(_, inferred = true))),
          "durationByMode" -> ujson.Obj.from(sum.durationByMode.map((m, d) => m.key -> num(d)))
        )
      })
    )))

    // --- One palette: the modes' labels and colours, and the other line colours
    // --- Which correction wins: equal timestamps, two devices ----------------
    val gapLegs = gapSegs.collect { case t: Trip => t.legs }.flatten.filter(_.mode != TransportMode.Still)
    val orderRows = gapLegs.take(3).zipWithIndex.flatMap { (l, i) =>
      val (s, e) = (l.start, l.end)
      i match
        case 0 => Seq(ModeOverride(s, e, TransportMode.Bus, 100, "dev-b", 1), ModeOverride(s, e, TransportMode.Car, 100, "dev-a", 7))
        case 1 => Seq(ModeOverride(s, e, TransportMode.Bus, 100, "dev-a", 2), ModeOverride(s, e, TransportMode.Car, 100, "dev-a", 5))
        case _ => Seq(ModeOverride(s, e, TransportMode.Train, 200, "dev-a", 1), ModeOverride(s, e, TransportMode.Car, 100, "dev-b", 9))
    }
    val chosen = TimelineBuilder.applyOverrides(gapSegs, orderRows).collect { case t: Trip => t.legs }.flatten
    write(dir, "override-order.json", ujson.write(ujson.Obj(
      "rows" -> ujson.Arr.from(orderRows.map(o => ujson.Obj("s" -> num(o.start), "e" -> num(o.end), "mode" -> o.mode.key,
        "updatedAt" -> num(o.updatedAt), "device" -> o.device, "seq" -> num(o.seq)))),
      "expected" -> ujson.Arr.from(gapLegs.take(3).map(l => ujson.Obj("start" -> num(l.start),
        "mode" -> chosen.find(_.start == l.start).get.mode.key)))
    ), indent = 1))

    write(dir, "palette.json", ujson.write(ujson.Obj(
      "modes" -> ujson.Arr.from(TransportMode.values.toSeq.map(m =>
        ujson.Obj("key" -> m.key, "label" -> m.label, "emoji" -> m.emoji, "color" -> m.color, "vehicle" -> m.isVehicle))),
      "unrecorded" -> Palette.Unrecorded,
      "stay" -> Palette.Stay
    ), indent = 1))

    // --- A user's batches as the server lists and serves them -------------
    writeSync(File(dir, "sync"), fixesPq, motionPq, overrides)

  private def writeSync(dir: File, fixesPq: Array[Byte], motionPq: Array[Byte], overrides: Seq[(Long, Long, TransportMode)]): Unit =
    val blobs = File(dir, "blobs")
    blobs.mkdirs()
    blobs.listFiles().foreach(_.delete())
    val keys = BlobKeys.of(MasterKey)
    val key = keys.batch
    val devA = "0f5c2a8e-7d1b-4c3e-9a61-2b7d5e8f4a10"
    val devB = "6a3e9b0c-1f2d-4e5a-8b7c-9d0e1f2a3b4c"
    val hour = 3_600_000L
    val t0 = Date.plusDays(1).atTime(0, 30).atZone(Zone).toInstant.toEpochMilli
    var cursor = 100L
    val seqs = scala.collection.mutable.Map.empty[(String, String), Long].withDefaultValue(0L)
    val items = Vector.newBuilder[ujson.Value]
    val metas = Vector.newBuilder[BatchMeta]
    // Before v1.1 every app wrote ASH1.
    var legacy = true

    def putPlain(dev: String, stream: String, plain: Array[Byte]): (Long, Array[Byte]) =
      val s = seqs((dev, stream))
      seqs((dev, stream)) = s + 1
      val aad = Crypto.aad(dev, stream, s)
      val blob = if legacy then Crypto.sealAsh1(key, aad, Crypto.gzip(plain)) else Crypto.seal(keys, aad, Crypto.gzip(plain))
      Files.write(File(blobs, s"$dev.$stream.$s").toPath, blob)
      // The server's global cursor skips values (other users' batches).
      cursor += 3
      items += ujson.Obj("cursor" -> num(cursor), "deviceId" -> dev, "stream" -> stream, "seq" -> num(s),
        "size" -> blob.length, "sha256" -> Crypto.sha256Hex(blob), "createdAt" -> num((t0 / hour) * hour))
      metas += BatchMeta(cursor, dev, stream, s, blob.length, Crypto.sha256Hex(blob))
      (s, blob)

    def put(dev: String, stream: String, records: Seq[ujson.Value]): Unit =
      putPlain(dev, stream, records.map(r => ujson.write(r) + "\n").mkString.getBytes(UTF_8))

    def archiveParts(file: String, gen: String, bytes: Array[Byte], partSize: Int, only: Option[Int] = None): Unit =
      val parts = ((bytes.length + partSize - 1) / partSize).max(1)
      for p <- 0 until parts if only.forall(_ > p) do
        val chunk = bytes.slice(p * partSize, math.min(bytes.length, (p + 1) * partSize))
        put(devA, "archive", Seq(ujson.Obj("file" -> file, "gen" -> gen, "part" -> p, "parts" -> parts,
          "data" -> java.util.Base64.getEncoder.encodeToString(chunk))))

    // An abandoned generation (the file changed during its upload), then the
    // complete one, sent in several parts.
    archiveParts(s"fixes/$Date.parquet", "1f00d", fixesPq.dropRight(17), 4 * 1024, only = Some(2))
    // The apps were updated to v1.1 here: ASHM from now on.
    legacy = false
    // Tables the viewer does not use (state spans, pressure): sent like the
    // others, skipped by the viewer without counting as a failure.
    val dayStart = Date.atStartOfDay(Zone).toInstant.toEpochMilli
    val st = EnergyState(true, 1000, "gps", false, false, false, false, "balanced", "balanced", "OnFoot", "Outdoor")
    val spans = Seq(EnergySpan(dayStart + 7 * hour, dayStart + 8 * hour, st),
      EnergySpan(dayStart + 8 * hour, dayStart + 9 * hour, st.copy(still = true), endKnown = false))
    archiveParts(s"energy_state/$Date.parquet", "4e11a",
      parquet(Archive.EnergyStates.columns, spans.map(Archive.energyRow), table = Some(Archive.EnergyStates)), 3 * 1024 * 1024)
    archiveParts(s"pressure/$Date.parquet", "5a7c2", parquet(Archive.Pressure.columns,
      (0 until 720).map(i => Archive.pressureRow(PressureSample(dayStart + 8 * hour + i * 5000L, 1008f + i * 0.001f))),
      table = Some(Archive.Pressure)), 3 * 1024 * 1024)
    archiveParts(s"fixes/$Date.parquet", "2b7e1", fixesPq, 4 * 1024)
    archiveParts(s"motion/$Date.parquet", "3c9a4", motionPq, 3 * 1024 * 1024)

    val sc = Scenarios
    // An ASH1 batch of an editable stream (sealed before the update, sent late).
    legacy = true
    put(devA, "places", Seq(
      ujson.Obj("id" -> 1, "lat" -> sc.Home.lat, "lon" -> sc.Home.lon, "r" -> 60.0, "name" -> "", "kind" -> "HOME",
        "ext" -> "", "updatedAt" -> num(t0 - 5 * hour)),
      ujson.Obj("id" -> 2, "lat" -> sc.Office.lat, "lon" -> sc.Office.lon, "r" -> 80.0, "name" -> "", "kind" -> "UNKNOWN",
        "ext" -> "", "updatedAt" -> num(t0 - 5 * hour))))
    legacy = false
    // A later snapshot of place 2 wins.
    put(devA, "places", Seq(
      ujson.Obj("id" -> 2, "lat" -> sc.Office.lat, "lon" -> sc.Office.lon, "r" -> 80.0, "name" -> "有明オフィス",
        "kind" -> "WORK", "ext" -> "", "updatedAt" -> num(t0 - 4 * hour))))
    put(devA, "overrides", overrides.map((s, e, m) =>
      ujson.Obj("s" -> num(s), "e" -> num(e), "mode" -> m.key, "updatedAt" -> num(t0 - 3 * hour))))
    val lunchAt = Date.atTime(12, 50).atZone(Zone).toInstant.toEpochMilli
    put(devA, "checkins", Seq(
      ujson.Obj("id" -> 7, "t" -> num(lunchAt), "lat" -> sc.Lunch.lat, "lon" -> sc.Lunch.lon, "venue" -> "有明ガーデン",
        "cat" -> "ショッピングモール", "ext" -> "node/1", "place" -> ujson.Null, "comment" -> "昼休み", "private" -> 0,
        "updatedAt" -> num(lunchAt))))

    def status(t: Long, pct: Int, on: Boolean, fixAt: LatLng, model: String) =
      DeviceStatus(t, pct, charging = !on, recording = on, mode = if on then "balanced" else "", since = if on then t - 48 * hour else 0L,
        lastFix = Some(StatusFix(t - 60_000, fixAt.lat, fixAt.lon, 12.0)), versionName = "0.1.0", versionCode = 12, model = model).toJson
    legacy = true
    put(devA, "status", Seq(status(t0 - hour, 81, on = true, sc.Office, "Google Pixel 8a")))
    legacy = false
    put(devB, "status", Seq(status(t0 - 5 * hour, 100, on = false, sc.Home, "Google Pixel Tablet")))
    // devA approves the first viewer: the cut-over record C over everything
    // listed so far, then a status that names it.
    val (cutSeq, cutBlob) = putPlain(devA, CutoverRecord.Stream, CutoverRecord.encode(CutoverRecord.fromListing(metas.result())))
    val cut = CutRef(devA, cutSeq, Crypto.sha256Hex(cutBlob))
    put(devA, "status", Seq(status(t0, 78, on = true, sc.Home, "Google Pixel 8a").tap(_("authcut") = cut.toJson)))

    write(dir, "listing.json", ujson.write(ujson.Obj("items" -> ujson.Arr.from(items.result()), "next" -> ujson.Null), indent = 1))
    write(dir, "meta.json", ujson.write(ujson.Obj(
      "batchKeyHex" -> key.map(b => f"${b & 0xff}%02x").mkString,
      "devices" -> ujson.Arr(
        ujson.Obj("id" -> devA, "name" -> "Pixel 8a", "createdAt" -> num(t0 - 30 * 24 * hour), "lastSeenAt" -> num(t0), "revoked" -> false),
        ujson.Obj("id" -> devB, "name" -> "Pixel Tablet", "createdAt" -> num(t0 - 20 * 24 * hour), "lastSeenAt" -> num(t0 - 5 * hour), "revoked" -> false)
      )
    ), indent = 1))

  extension (o: ujson.Obj) private def tap(f: ujson.Obj => Unit): ujson.Obj =
    f(o)
    o

  /** As the app applies a correction (TimelineRepo): overlap of more than half. */
  private def segJson(s: Segment, inferred: Boolean = false): ujson.Value = s match
    case v: Visit =>
      ujson.Obj("type" -> "visit", "start" -> num(v.start), "end" -> num(v.end), "lat" -> v.lat, "lon" -> v.lon,
        "radius" -> v.radius, "fixCount" -> v.fixCount)
    case t: Trip =>
      ujson.Obj("type" -> "trip", "start" -> num(t.start), "end" -> num(t.end), "pathSize" -> t.path.size,
        "legs" -> ujson.Arr.from(t.legs.map(l => ujson.Obj(
          "start" -> num(l.start), "end" -> num(l.end), "mode" -> l.mode.key, "distanceM" -> l.distanceM,
          "steps" -> l.steps, "uncertain" -> l.uncertain, "confidence" -> l.confidence)
          .tap(o => if inferred then o("inferred") = l.inferred.fold(ujson.Null)(m => ujson.Str(m.key))))))
    case u: Unrecorded =>
      ujson.Obj("type" -> "unrecorded", "start" -> num(u.start), "end" -> num(u.end),
        "from" -> ujson.Obj("lat" -> u.from.lat, "lon" -> u.from.lon), "to" -> ujson.Obj("lat" -> u.to.lat, "lon" -> u.to.lon))

  private def num(l: Long): ujson.Num = ujson.Num(l.toDouble)

  private def parquet(cols: Seq[Column], rows: Seq[Seq[Any]], rowGroupRows: Int = 50_000,
                      table: Option[Archive.TableDef] = None): Array[Byte] =
    val bo = ByteArrayOutputStream()
    val w = table.fold(ParquetWriter(bo, cols, rowGroupRows))(t => Archive.writer(t, bo, rowGroupRows))
    rows.foreach(w.write)
    w.close()
    bo.toByteArray

  /** Rows as the app's own reader reads them back. Int64 values are strings
    * (they may exceed 2^53); floats are their exact value; null is null.
    */
  private def rowsJson(pq: Array[Byte]): String =
    val t = ParquetReader.read(pq)
    def cell(v: Any): ujson.Value = v match
      case null      => ujson.Null
      case l: Long   => ujson.Str(l.toString)
      case i: Int    => ujson.Num(i.toDouble)
      case f: Float  => if f.isNaN then ujson.Null else ujson.Num(f.toDouble)
      case d: Double => if d.isNaN then ujson.Null else ujson.Num(d)
      case s: String => ujson.Str(s)
      case other     => ujson.Str(other.toString)
    ujson.write(ujson.Obj(
      "columns" -> ujson.Arr.from(t.columns.map(c => ujson.Obj("name" -> c.name, "type" -> c.tpe.toString))),
      "rows" -> ujson.Arr.from(t.rowIterator.map(r => ujson.Arr.from(r.map(cell))))
    ))

  private def write(dir: File, name: String, s: String): Unit = write(dir, name, s.getBytes(UTF_8))
  private def write(dir: File, name: String, b: Array[Byte]): Unit =
    dir.mkdirs()
    Files.write(File(dir, name).toPath, b)
