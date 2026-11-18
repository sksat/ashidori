package net.sksat.ashidori

import java.time.LocalDate
import ashidori.core.*
import ashidori.core.GnssLogFormat.row

/** Debug builds only: a large synthetic detail log, to test archiving at the
  * size of a long flight logged with 全力ログ (raw GNSS at 1 Hz with ~45
  * signals, satellite status, NMEA, navigation messages, IMU at 100 Hz for
  * three sensors: about 400 lines a second).
  *
  * `adb shell am start -n <pkg>/net.sksat.ashidori.MainActivity --ei ashidori.synthRawHours 5`
  */
object DebugData:
  @volatile var progress = ""

  /** Checks that a corrupt database is kept, through the app's own open
    * paths (the helper and the backup's read-only connection): a test
    * database is written, corrupted on disk and read again. Returns a
    * report line.
    */
  def corruptionTest(c: android.content.Context): String =
    val name = "corrupt-test.db"
    c.deleteDatabase(name)
    val f = c.getDatabasePath(name)
    val db = Db(c, name)
    db.insertRawLines((0 until 20_000).map(i => (i.toLong, s"Accel,$i,1,0.1,0.2,0.3")))
    db.close()
    // No connection is open now: garbage over pages in the middle.
    val raf = java.io.RandomAccessFile(f, "rw")
    try
      raf.seek(f.length / 2)
      raf.write(Array.fill[Byte](16_384)(0x55))
      raf.getFD.sync()
    finally raf.close()
    val before = Db.KeepOnCorruption.reported
    val db2 = Db(c, name)
    val query = scala.util.Try(db2.rawLines(0L, Long.MaxValue).size)
    val snap = java.io.File(c.getCacheDir, "corrupt-snapshot.db")
    val vacuum = scala.util.Try(db2.snapshotTo(snap))
    val reports = Db.KeepOnCorruption.reported - before
    val kept = f.exists && f.length > 0
    scala.util.Try(db2.close())
    snap.delete()
    c.deleteDatabase(name)
    s"corruption test: query=${query.fold(e => e.getClass.getSimpleName, n => s"$n rows")} " +
      s"snapshot=${vacuum.fold(e => e.getClass.getSimpleName, _ => "ok")} handledByKeepOnCorruption=$reports fileKept=$kept"

  /** Odd raw lines at 12:00 of `day`: 5 that do not convert (kept in SQLite
    * for good) and 20 IMU lines whose own time is 3 minutes before their
    * stored time (out of order beyond the reorder window). Returns (odd, late).
    */
  def insertOdd(day: LocalDate): (Int, Int) =
    val t = day.atTime(12, 0).atZone(AshidoriApp.zone).toInstant.toEpochMilli
    val odd = Seq("Accel,%d,1,x,0.2,0.3".format(t), "OrientationDeg,%d,1,2,3".format(t), "Accel,%d,1,0.1,0.2,0.3,9".format(t),
      "Gyro,,1,0.1,0.2,0.3", s"Status,$t,40,1.5,1,7,1.5e9,38,1,2,1,1,1,").map(l => (t, l))
    val late = (0 until 20).map(i => (t + i, row("Gyro", t - 180_000 + i, 9_000_000L + i, 0.5f, 0.25f, 0.125f)))
    AshidoriApp.db.insertRawLines(odd ++ late)
    (odd.size, late.size)

  /** Inserts `hours` of lines from `startMinute` (10:00) of `day`. Returns the lines. */
  /** Per raw table of `day`: rows in its archive file, rows of it still in
    * SQLite (raw lines that convert), and distinct rows of both together
    * (a row in both is a duplicate); then the raw lines that do not
    * convert. Reads the files without the locks (also while a commit is
    * unfinished). Holds a hash per row.
    */
  def audit(day: LocalDate): Seq[String] =
    val db = AshidoriApp.db
    val root = AshidoriApp.archive.root
    val from = day.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
    val to = day.plusDays(1).atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
    val hot = scala.collection.mutable.Map.empty[String, Long].withDefaultValue(0L)
    val hashes = scala.collection.mutable.Map.empty[String, scala.collection.mutable.HashSet[Long]]
    def set(t: String) = hashes.getOrElseUpdate(t, scala.collection.mutable.HashSet.empty[Long])
    var unconverted = 0L
    var at = ArchiveMerge.Cursor(from, Long.MinValue)
    var more = true
    while more do
      val lines = db.rawLinesAfter(from, to, Long.MaxValue, at, 4000)
      if lines.isEmpty then more = false
      else
        for (_, time, line) <- lines do
          Archive.parse(line, time) match
            case Right((t, row)) =>
              hot(t.name) += 1
              set(t.name) += ArchiveMerge.hash(row)
            case Left(_) => unconverted += 1
        at = ArchiveMerge.Cursor(lines.last._2, lines.last._1)
    // The small tables: their rows in SQLite.
    for t <- Seq(Archive.Fixes, Archive.Battery); row <- db.hotRows(t, from, to) do
      hot(t.name) += 1
      set(t.name) += ArchiveMerge.hash(row.toIndexedSeq)
    val out = for t <- Archive.RawTables ++ Seq(Archive.Fixes, Archive.Battery) yield
      val f = java.io.File(root, Archive.dayFile(t, day))
      var n = 0L
      val read =
        if !f.exists then scala.util.Success(())
        else scala.util.Try(scala.util.Using.resource(ashidori.core.parquet.ParquetReader.open(f.getPath)) { r =>
          r.rowIterator.foreach { row =>
            n += 1
            set(t.name) += ArchiveMerge.hash(row)
          }
        })
      val bad = read.failed.toOption.fold("")(e => s" UNREADABLE after $n rows (${e.getClass.getSimpleName})")
      val distinct = hashes.get(t.name).map(_.size.toLong).getOrElse(0L)
      s"$day ${t.name} archive=$n sqlite=${hot(t.name)} total=${n + hot(t.name)} distinct=$distinct$bad"
    // Files a repair quarantined for this day: their rows, as far as they read.
    val quarantined =
      Option(java.io.File(root, ArchiveStore.QuarantineDir).listFiles()).toSeq.flatten.flatMap { c =>
        (Archive.RawTables ++ Seq(Archive.Fixes, Archive.Battery)).map(t => java.io.File(c, Archive.dayFile(t, day)))
          .flatMap(f => Seq(f, java.io.File(f.getPath + ".tmp"))).filter(_.exists)
      }.map { f =>
        var n = 0L
        val ok = scala.util.Try(scala.util.Using.resource(ashidori.core.parquet.ParquetReader.open(f.getPath))(_.rowIterator.foreach(_ => n += 1)))
        s"$day quarantined ${root.toPath.relativize(f.toPath)} rows=$n${if ok.isFailure then " (unreadable from there)" else ""}"
      }
    (out ++ quarantined) :+ s"$day unconverted=$unconverted commitPending=${AshidoriApp.archive.commitPending}"

  /** Debug recorders of `day`: boots no real counter reaches, one pair per day. */
  private def debugBoot(day: LocalDate, k: Int): Long =
    900_000L + java.time.temporal.ChronoUnit.DAYS.between(day, LocalDate.now(AshidoriApp.zone)) * 10 + k

  /** Signals of the energy and elevation recording for `day` (debug): two
    * recorders ([[debugBoot]])
    * with three state changes each, the first one's last span left open;
    * 2 hours of pressure at 5 s; battery readings every 5 minutes; an hour
    * of 1 Hz fixes, half with an MSL altitude. Through the app's own writes.
    */
  def insertSignals(day: LocalDate): String =
    val db = AshidoriApp.db
    val t0 = day.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli + 9 * 3_600_000L
    def st(i: Int) = EnergyState(i % 2 == 0, 1000L * (i + 1), "gps", false, i % 3 == 0, i % 2 == 1, false,
      "balanced", "balanced", "OnFoot", "Outdoor")
    val a = (1 to 3).map(i => EnergyTransition(i, t0 + i * 20 * 60_000L, Some(st(i))))
    db.applyEnergy(SmallRecord.Energy(debugBoot(day, 1), a, t0 + 70 * 60_000L, Some(st(3))))
    val b = (1 to 3).map(i => EnergyTransition(i, t0 + 3 * 3_600_000L + i * 20 * 60_000L, Some(st(i + 3))))
    db.applyEnergy(SmallRecord.Energy(debugBoot(day, 2), b, t0 + 5 * 3_600_000L, Some(st(6))))
    db.applyEnergy(SmallRecord.Energy(debugBoot(day, 2), Nil, t0 + 5 * 3_600_000L, None).copy(transitions =
      Seq(EnergyTransition(4, t0 + 5 * 3_600_000L, None))))
    db.insertPressure((0 until 1440).map(i => PressureSample(t0 + i * 5000L, 1005f + (i % 100) * 0.01f)))
    for i <- 0 until 72 do
      db.insertBattery(BatteryReading(t0 + i * 300_000L, 90 - i / 4, false, i % 2 == 0, "balanced", Some(4_000_000L - i * 5000L),
        Some(150_000), Some(160_000), Some(3900), 30.5f, Some("none")), "debug")
    db.insertFixes((0 until 3600).map(i => Fix(t0 + i * 1000L, 35.6 + i * 1e-5, 139.7, 4f, 80.0, 1.2f, 90f, "gps", 12,
      altAccuracy = 3f, msl = if i % 2 == 0 then 41.0 + i * 0.001 else Double.NaN, mslAccuracy = if i % 2 == 0 then 3.5f else Float.NaN)))
    s"$day: ${db.counts().filter((k, _) => Seq("energy_state", "pressure", "battery", "fixes").contains(k))}"

  /** A late record of `day`'s first recorder: one more change
    * after its last confirmation, as an overflow file imported late would bring.
    */
  def closeLate(day: LocalDate): String =
    val db = AshidoriApp.db
    val t0 = day.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli + 9 * 3_600_000L
    val s = EnergyState(true, 9000L, "gps", false, false, false, false, "balanced", "balanced", "Vehicle", "Outdoor")
    db.applyEnergy(SmallRecord.Energy(debugBoot(day, 1), Seq(EnergyTransition(4, t0 + 80 * 60_000L, Some(s))), t0 + 90 * 60_000L, Some(s)))
    s"$day: energy_state=${db.counts()("energy_state")}"

  /** Per signal table of `day` (fixes, motion, battery, gnss_status,
    * energy_state, pressure): rows in its day file, rows still in SQLite,
    * and distinct rows of both (a row in both is a duplicate); and the
    * state spans as the app reads them (start, end, unknown end).
    */
  def auditSignals(day: LocalDate): Seq[String] =
    val db = AshidoriApp.db
    val root = AshidoriApp.archive.root
    val from = day.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
    val to = day.plusDays(1).atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
    val month = java.time.YearMonth.from(day)
    val out = for t <- Archive.SignalTables yield
      val hot = db.hotRows(t, from, to)
      val hashes = scala.collection.mutable.HashSet.empty[Long]
      hot.foreach(r => hashes += ArchiveMerge.hash(r.toIndexedSeq))
      var n = 0L
      for f <- Seq(java.io.File(root, Archive.dayFile(t, day)), java.io.File(root, Archive.monthFile(t, month))) if f.exists do
        scala.util.Using.resource(Archive.open(t, f.getPath))(_.rowIterator.foreach { r =>
          val k = ArchiveMerge.key(r)
          if k >= from && k < to then
            n += 1
            hashes += ArchiveMerge.hash(r)
        })
      s"$day ${t.name} archive=$n sqlite=${hot.size} distinct=${hashes.size}"
    val spans = db.energyData(from, to)._2.filter(s => s.start >= from && s.start < to)
      .map(s => f"${(s.start - from) / 60_000}%d-${(s.end - from) / 60_000}%d${if s.endKnown then "" else "?"}")
    out ++ Seq(s"$day spans ${spans.mkString(" ")}", s"$day deletions pending=${db.archiveDeletionsPending().size}")

  def insertRaw(day: LocalDate, hours: Double, startMinute: Int = 600): Long =
    val db = AshidoriApp.db
    val start = day.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli + startMinute * 60_000L
    val seconds = (hours * 3600).toLong
    val rnd = scala.util.Random(42)
    val batch = scala.collection.mutable.ArrayBuffer.empty[(Long, String)]
    var n = 0L
    def add(t: Long, l: String): Unit =
      batch += ((t, l))
      if batch.size >= 20_000 then
        db.insertRawLines(batch)
        n += batch.size
        batch.clear()
    val fixes = scala.collection.mutable.ArrayBuffer.empty[Fix]
    for s <- 0L until seconds do
      val t = start + s * 1000
      val nanos = s * 1_000_000_000L + 123_456_789L
      // One measurement epoch: ~45 signals.
      for k <- 0 until 45 do
        val svid = 1 + k % 32
        add(t, row("Raw", t, nanos, 18, Double.NaN, -1_400_000_000_000_000_000L + s, 0.5 + rnd.nextDouble(), 10.0,
          1.2, 0.1, 3, svid, 0.0, 16431, nanos + 70_000_000L + k, 12.0, 20 + rnd.nextInt(25), rnd.nextGaussian() * 500, 0.05,
          0, 0.0, 0.0, if k % 3 == 0 then 1.17645e9 else 1.57542e9, null, null, null, 0, Double.NaN, 1 + k % 6, 35.0,
          18 + rnd.nextInt(25), Double.NaN, Double.NaN, Double.NaN, Double.NaN, if k % 3 == 0 then "Q" else "C", nanos))
      // Satellite status: ~40 satellites.
      for i <- 0 until 40 do
        add(t, row("Status", t, 40, i, 1 + i % 6, 1 + i % 32, 1.57542e9, 15 + rnd.nextInt(30), rnd.nextInt(360).toFloat,
          rnd.nextInt(90).toFloat, i % 2, 1, 1, Float.NaN))
      // NMEA
      for m <- Seq("GPGGA", "GPRMC", "GPGSA", "GLGSV", "GAGSV", "GBGSV", "GPGSV", "GNGNS") do
        add(t, row("NMEA", s"$$$m,${s % 86400},4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47", t))
      if s % 2 == 0 then
        add(t, row("Nav", 1 + (s % 32).toInt, 257, 1, (s % 25).toInt, 1, Seq.fill(40)(rnd.nextInt(256)).mkString(",")))
      // IMU at 100 Hz.
      for j <- 0 until 100 do
        val ti = t + j * 10
        val en = nanos + j * 10_000_000L
        add(ti, row("Accel", ti, en, rnd.nextGaussian().toFloat * 0.2f, rnd.nextGaussian().toFloat * 0.2f, 9.81f))
        add(ti, row("Gyro", ti, en + 1, rnd.nextGaussian().toFloat * 0.01f, rnd.nextGaussian().toFloat * 0.01f, 0.001f))
        add(ti, row("Mag", ti, en + 2, 20f + rnd.nextFloat(), -5f, 40f))
      // The recorder's copy of the position, and the position itself.
      add(t, row("Fix", "gps", 35.0, 139.0, 10000.0, 230f, 5f, 90f, t, 1f, 1f, nanos, 3f, false, null, null, null))
      fixes += Fix(t, 35.0 + s * 1e-4, 139.0 + s * 1e-4, 5f, 10000.0, 230f, 90f, "sim", -1)
      if fixes.size >= 600 then
        db.insertFixes(fixes.toVector)
        fixes.clear()
      if s % 600 == 0 then progress = s"${s / 60} 分 / ${seconds / 60} 分（$n 行）"
    if batch.nonEmpty then db.insertRawLines(batch)
    if fixes.nonEmpty then db.insertFixes(fixes.toVector)
    n += batch.size
    progress = s"完了 $n 行"
    Log.i(s"synthetic raw lines: $n for $day")
    n

  /** 「訪れた日」 checks (DebugReceiver VISITS): `reset` deletes the index; `rebuild` deletes it
    * and builds every day, timed; `check` times the index's answer for each
    * of `at` and compares it with every day of the record built and
    * filtered (the scan the index replaces).
    */
  def visits(op: String, at: Seq[ashidori.core.LatLng]): Unit =
    import ashidori.core.*
    def ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    op match
      case "reset" =>
        VisitDays.reset()
        Log.i("visits bench: reset")
      case "failbuild" =>
        VisitDays.failBuildsForTest = true
        Log.i("visits bench: builds fail")
      case "fail" | "unfail" =>
        if op == "unfail" then VisitDays.failBuildsForTest = false
        VisitDays.failQueriesForTest = op == "fail"
        Log.i(s"visits bench: queries fail ${VisitDays.failQueriesForTest}")
      case "stale" => VisitDays.markStale("debug")
      case "rebuild" =>
        VisitDays.reset()
        val t0 = System.nanoTime()
        var days = 0
        var go = true
        while go do
          VisitDays.run(Int.MaxValue, () => false) match
            case Some(r) =>
              days += r.built
              go = r.built > 0 && r.left > r.failed.size
            case None => go = false
        val size = VisitDays.file(AshidoriApp.app).length
        Log.i(f"visits bench: built $days days in ${ms(t0)}%.0f ms, index file $size bytes")
      case _ =>
        VisitDays.opened()
        val today = java.time.LocalDate.now(AshidoriApp.zone)
        val first = AshidoriApp.db.firstDataDate().getOrElse(today)
        val t0 = System.nanoTime()
        val all = Iterator.iterate(today)(_.minusDays(1)).takeWhile(!_.isBefore(first))
          .flatMap(d => TimelineRepo.stays(d).map(d -> _)).toVector
        Log.i(f"visits bench: brute force built ${today.toEpochDay - first.toEpochDay + 1} days in ${ms(t0)}%.0f ms")
        while VisitDays.run(Int.MaxValue, () => false).exists(r => r.built > 0 && r.left > r.failed.size) do ()
        for v <- at do
          val times = (0 until 5).map { _ =>
            val t = System.nanoTime()
            val a = VisitDays.query(v)
            (ms(t), a)
          }
          val got = times.last._2.map(_.days).getOrElse(Vector.empty)
          val want = VisitIndex.refine(v, all)
          // The index keeps a stay's times, centre and radius (not its fix count).
          def key(ds: Seq[VisitIndex.DayVisit]) = ds.map(d => d.day -> d.stays.map(s => (s.start, s.end, s.lat, s.lon, s.radius)))
          val same = key(got) == key(want)
          def show(ds: Seq[VisitIndex.DayVisit]) = ds.map(d => s"${d.day}x${d.stays.size}").mkString(" ")
          Log.i(f"visits bench: ${v.lat}%.4f,${v.lon}%.4f: ${got.size} days, query ${times.map(x => f"${x._1}%.1f").mkString("/")} ms, " +
            s"brute force ${want.size} days, ${if same then "same" else "DIFFERENT"}: ${show(got)}" +
            (if same then "" else s" | want ${show(want)}"))
