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
