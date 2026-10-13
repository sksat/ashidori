package net.sksat.ashidori

import android.content.{ContentValues, Context}
import android.database.Cursor
import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import scala.collection.mutable.ArrayBuffer
import scala.util.Using
import ashidori.core.*

/** Plain SQLite storage. Raw signals (fixes, motion windows, battery) are
  * append-only; semantic data (places, check-ins, user corrections) is
  * editable. Timelines are recomputed from raw signals, so algorithm
  * improvements apply retroactively.
  */
final class Db(ctx: Context) extends SQLiteOpenHelper(ctx, "ashidori.db", null, Db.Version):

  override def onConfigure(db: SQLiteDatabase): Unit =
    db.enableWriteAheadLogging()
    db.setForeignKeyConstraintsEnabled(true)

  override def onCreate(db: SQLiteDatabase): Unit =
    Seq(
      """CREATE TABLE fixes(time INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, alt REAL,
        |speed REAL, bearing REAL, provider TEXT, sats INTEGER)""".stripMargin,
      "CREATE INDEX fixes_time ON fixes(time)",
      "CREATE TABLE motion(start INTEGER NOT NULL, end INTEGER NOT NULL, std REAL, mean REAL, steps INTEGER, src TEXT)",
      "CREATE INDEX motion_start ON motion(start)",
      "CREATE TABLE battery(time INTEGER NOT NULL, pct INTEGER, charging INTEGER, screen_on INTEGER, mode TEXT, plan TEXT)",
      "CREATE INDEX battery_time ON battery(time)",
      """CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL, lon REAL, radius REAL, name TEXT,
        |kind TEXT, external_id TEXT, city TEXT, country TEXT, area TEXT, updated_at INTEGER)""".stripMargin,
      """CREATE TABLE checkins(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, lat REAL, lon REAL, venue TEXT,
        |category TEXT, external_id TEXT, place_id INTEGER, comment TEXT, private INTEGER, updated_at INTEGER)""".stripMargin,
      "CREATE INDEX checkins_time ON checkins(time)",
      // User corrections of a leg's transport mode, keyed by time range.
      "CREATE TABLE mode_overrides(start INTEGER, end INTEGER, mode TEXT, updated_at INTEGER)",
      "CREATE TABLE gnss_sessions(start INTEGER, end INTEGER, file TEXT, reason TEXT, uploaded INTEGER DEFAULT 0)",
      // Server sync progress, keyed by registration: next seq per stream,
      // change-counter watermark per editable table, archive files handed over.
      "CREATE TABLE sync_state(name TEXT PRIMARY KEY, value INTEGER)"
    ).foreach(db.execSQL)
    createGnssStatus(db)
    createRawLines(db)
    createSyncOutbox(db)
    addChangeCounter(db)

  /** Sealed batches not yet accepted by the server. A row is written in the
    * same transaction that claims its seq and records the source progress,
    * and is deleted only after the server accepted it.
    */
  private def createSyncOutbox(db: SQLiteDatabase): Unit =
    db.execSQL("""CREATE TABLE IF NOT EXISTS sync_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT,
      |reg TEXT NOT NULL, device_id TEXT NOT NULL, stream TEXT NOT NULL, seq INTEGER NOT NULL,
      |class TEXT NOT NULL, blob BLOB NOT NULL, created_at INTEGER NOT NULL, UNIQUE(reg, stream, seq))""".stripMargin)

  /** Editable tables get `sync_rev`, a local change counter set by triggers on
    * every insert and synced-column update. Unlike `updated_at` it never
    * repeats or goes back, so a watermark on it cannot skip rows. Existing
    * rows are numbered in `updated_at` order.
    */
  private def addChangeCounter(db: SQLiteDatabase): Unit =
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_counter(id INTEGER PRIMARY KEY CHECK(id=0), value INTEGER NOT NULL)")
    db.execSQL("INSERT OR IGNORE INTO sync_counter(id, value) VALUES(0, 0)")
    for (table, cols) <- Db.RevColumns do
      val existing = Using.resource(db.rawQuery(s"PRAGMA table_info($table)", null)) { c =>
        val names = Set.newBuilder[String]
        while c.moveToNext() do names += c.getString(c.getColumnIndexOrThrow("name"))
        names.result()
      }
      if !existing("sync_rev") then
        db.execSQL(s"ALTER TABLE $table ADD COLUMN sync_rev INTEGER")
        val rowids = Using.resource(db.rawQuery(s"SELECT rowid FROM $table ORDER BY updated_at, rowid", null)) { c =>
          val b = Vector.newBuilder[Long]
          while c.moveToNext() do b += c.getLong(0)
          b.result()
        }
        for id <- rowids do
          db.execSQL("UPDATE sync_counter SET value=value+1")
          db.execSQL(s"UPDATE $table SET sync_rev=(SELECT value FROM sync_counter) WHERE rowid=?", Array[AnyRef](java.lang.Long.valueOf(id)))
      db.execSQL(s"CREATE INDEX IF NOT EXISTS ${table}_sync_rev ON $table(sync_rev)")
      val bump = s"UPDATE sync_counter SET value=value+1; UPDATE $table SET sync_rev=(SELECT value FROM sync_counter) WHERE rowid=NEW.rowid;"
      db.execSQL(s"CREATE TRIGGER IF NOT EXISTS ${table}_rev_ins AFTER INSERT ON $table BEGIN $bump END")
      db.execSQL(s"CREATE TRIGGER IF NOT EXISTS ${table}_rev_upd AFTER UPDATE OF ${cols.map(c => "\"" + c + "\"").mkString(",")} ON $table BEGIN $bump END")

  /** Detail-session signals (GNSS raw/status/nav/NMEA, IMU) as GnssLogger-format
    * lines until their day is archived to Parquet.
    */
  private def createRawLines(db: SQLiteDatabase): Unit =
    db.execSQL("CREATE TABLE IF NOT EXISTS raw_lines(time INTEGER NOT NULL, line TEXT NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS raw_lines_time ON raw_lines(time)")

  /** Periodic GnssStatus summaries: GNSS history view and indoor detection. */
  private def createGnssStatus(db: SQLiteDatabase): Unit =
    db.execSQL("""CREATE TABLE IF NOT EXISTS gnss_status(time INTEGER NOT NULL, visible INTEGER, used INTEGER,
      |top4 REAL, l5 INTEGER, env TEXT, acc REAL)""".stripMargin)
    db.execSQL("CREATE INDEX IF NOT EXISTS gnss_status_time ON gnss_status(time)")

  /** v0.1 (version 1) shipped to devices; never drop user data here. */
  override def onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int): Unit =
    if oldV < 2 then
      // Reverse-geocoded region and approximate area name of a place. Only
      // add what is missing: pre-release builds already had some of them.
      val existing = Using.resource(db.rawQuery("PRAGMA table_info(places)", null)) { c =>
        val names = Set.newBuilder[String]
        while c.moveToNext() do names += c.getString(c.getColumnIndexOrThrow("name"))
        names.result()
      }
      Seq("city", "country", "area").filterNot(existing).foreach(c => db.execSQL(s"ALTER TABLE places ADD COLUMN $c TEXT"))
    if oldV < 3 then createGnssStatus(db)
    if oldV < 4 then createRawLines(db)
    if oldV < 5 then
      createSyncOutbox(db)
      addChangeCounter(db)

  private def w = getWritableDatabase
  private def r = getReadableDatabase

  private def tx[A](f: SQLiteDatabase => A): A =
    val db = w
    db.beginTransaction()
    try
      val a = f(db)
      db.setTransactionSuccessful()
      a
    finally db.endTransaction()

  private def nanToNull(cv: ContentValues, k: String, v: Double): Unit =
    if v.isNaN then cv.putNull(k) else cv.put(k, java.lang.Double.valueOf(v))

  def insertFixes(fs: Iterable[Fix]): Unit = tx { db =>
    val cv = ContentValues()
    for f <- fs do
      cv.clear()
      cv.put("time", java.lang.Long.valueOf(f.time))
      cv.put("lat", java.lang.Double.valueOf(f.lat))
      cv.put("lon", java.lang.Double.valueOf(f.lon))
      nanToNull(cv, "acc", f.accuracy)
      nanToNull(cv, "alt", f.altitude)
      nanToNull(cv, "speed", f.speed)
      nanToNull(cv, "bearing", f.bearing)
      cv.put("provider", f.provider)
      cv.put("sats", java.lang.Integer.valueOf(f.satellites))
      db.insert("fixes", null, cv)
  }

  def insertMotion(ws: Iterable[MotionWindow], src: String = null): Unit = tx { db =>
    val cv = ContentValues()
    for m <- ws do
      cv.clear()
      cv.put("start", java.lang.Long.valueOf(m.start))
      cv.put("end", java.lang.Long.valueOf(m.end))
      nanToNull(cv, "std", m.accelStd)
      nanToNull(cv, "mean", m.accelMean)
      cv.put("steps", java.lang.Integer.valueOf(m.steps))
      cv.put("src", src)
      db.insert("motion", null, cv)
  }

  def insertBattery(time: Long, pct: Int, charging: Boolean, screenOn: Boolean, mode: String, plan: String): Unit =
    val cv = ContentValues()
    cv.put("time", java.lang.Long.valueOf(time))
    cv.put("pct", java.lang.Integer.valueOf(pct))
    cv.put("charging", java.lang.Integer.valueOf(if charging then 1 else 0))
    cv.put("screen_on", java.lang.Integer.valueOf(if screenOn then 1 else 0))
    cv.put("mode", mode)
    cv.put("plan", plan)
    w.insert("battery", null, cv)

  def insertGnssSession(start: Long, end: Long, file: String, reason: String): Unit =
    val cv = ContentValues()
    cv.put("start", java.lang.Long.valueOf(start))
    cv.put("end", java.lang.Long.valueOf(end))
    cv.put("file", file)
    cv.put("reason", reason)
    w.insert("gnss_sessions", null, cv)

  private def query[A](sql: String, args: Any*)(row: Cursor => A): Vector[A] =
    Using.resource(r.rawQuery(sql, args.map(_.toString).toArray)) { c =>
      val out = Vector.newBuilder[A]
      while c.moveToNext() do out += row(c)
      out.result()
    }

  private def dbl(c: Cursor, i: Int): Double = if c.isNull(i) then Double.NaN else c.getDouble(i)
  private def flt(c: Cursor, i: Int): Float = if c.isNull(i) then Float.NaN else c.getFloat(i)

  /** Positions in [from, to): archived days from Parquet, recent ones from SQLite. */
  def fixes(from: Long, to: Long): Vector[Fix] =
    (AshidoriApp.archive.read(Archive.Fixes, from, to).map(Archive.fixOf) ++ hotFixes(from, to)).sortBy(_.time)

  def hotFixes(from: Long, to: Long): Vector[Fix] =
    query("SELECT time,lat,lon,acc,alt,speed,bearing,provider,sats FROM fixes WHERE time>=? AND time<? ORDER BY time", from, to) { c =>
      Fix(c.getLong(0), c.getDouble(1), c.getDouble(2), flt(c, 3), dbl(c, 4), flt(c, 5), flt(c, 6), c.getString(7), c.getInt(8))
    }

  def lastFix(): Option[Fix] = hotLastFix().orElse(AshidoriApp.archive.lastRow(Archive.Fixes).map(Archive.fixOf))

  private def hotLastFix(): Option[Fix] =
    query("SELECT time,lat,lon,acc,alt,speed,bearing,provider,sats FROM fixes ORDER BY time DESC LIMIT 1") { c =>
      Fix(c.getLong(0), c.getDouble(1), c.getDouble(2), flt(c, 3), dbl(c, 4), flt(c, 5), flt(c, 6), c.getString(7), c.getInt(8))
    }.headOption

  def motion(from: Long, to: Long): Vector[MotionWindow] =
    (AshidoriApp.archive.read(Archive.Motion, from, to).map(Archive.motionOf) ++ hotMotion(from, to)).sortBy(_.start)

  private def hotMotion(from: Long, to: Long): Vector[MotionWindow] =
    query("SELECT start,end,std,mean,steps FROM motion WHERE start>=? AND start<? ORDER BY start", from, to) { c =>
      MotionWindow(c.getLong(0), c.getLong(1), flt(c, 2), flt(c, 3), c.getInt(4))
    }

  def steps(from: Long, to: Long): Int =
    AshidoriApp.archive.read(Archive.Motion, from, to).map(r => Option(r(4)).map(_.asInstanceOf[Int]).getOrElse(0)).sum +
      query("SELECT COALESCE(SUM(steps),0) FROM motion WHERE start>=? AND start<?", from, to)(_.getInt(0)).head

  def battery(from: Long): Vector[DrainEstimator.Sample] =
    AshidoriApp.archive.read(Archive.Battery, from, Long.MaxValue).map { r =>
      DrainEstimator.Sample(r(0).asInstanceOf[Long], r(1).asInstanceOf[Int], r(2) == 1, r(3) == 1)
    } ++ hotBattery(from)

  private def hotBattery(from: Long): Vector[DrainEstimator.Sample] =
    query("SELECT time,pct,charging,screen_on FROM battery WHERE time>=? ORDER BY time", from) { c =>
      DrainEstimator.Sample(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getInt(3) != 0)
    }

  def counts(): Map[String, Long] =
    Seq("fixes", "motion", "battery", "places", "checkins", "gnss_sessions").map { t =>
      t -> query(s"SELECT COUNT(*) FROM $t")(_.getLong(0)).head
    }.toMap

  // --- places -------------------------------------------------------------

  def places(): Vector[Place] =
    query("SELECT id,lat,lon,radius,name,kind,external_id,area FROM places") { c =>
      Place(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), Option(c.getString(4)).getOrElse(""),
        PlaceKind.fromKey(Option(c.getString(5)).getOrElse("")), Option(c.getString(6)).getOrElse(""),
        Option(c.getString(7)).getOrElse(""))
    }

  def insertPlace(p: Place): Long =
    val cv = ContentValues()
    cv.put("lat", java.lang.Double.valueOf(p.lat))
    cv.put("lon", java.lang.Double.valueOf(p.lon))
    cv.put("radius", java.lang.Double.valueOf(p.radius))
    cv.put("name", p.name)
    cv.put("kind", p.kind.key)
    cv.put("external_id", p.externalId)
    cv.put("updated_at", java.lang.Long.valueOf(System.currentTimeMillis()))
    w.insert("places", null, cv)

  def updatePlace(p: Place): Unit =
    val cv = ContentValues()
    cv.put("lat", java.lang.Double.valueOf(p.lat))
    cv.put("lon", java.lang.Double.valueOf(p.lon))
    cv.put("radius", java.lang.Double.valueOf(p.radius))
    cv.put("name", p.name)
    cv.put("kind", p.kind.key)
    cv.put("external_id", p.externalId)
    cv.put("updated_at", java.lang.Long.valueOf(System.currentTimeMillis()))
    w.update("places", cv, "id=?", Array(p.id.toString))

  // --- check-ins ------------------------------------------------------------

  def insertCheckIn(ci: CheckIn): Long =
    val cv = ContentValues()
    cv.put("time", java.lang.Long.valueOf(ci.time))
    cv.put("lat", java.lang.Double.valueOf(ci.lat))
    cv.put("lon", java.lang.Double.valueOf(ci.lon))
    cv.put("venue", ci.venueName)
    cv.put("category", ci.venueCategory)
    cv.put("external_id", ci.venueExternalId)
    ci.placeId.foreach(id => cv.put("place_id", java.lang.Long.valueOf(id)))
    cv.put("comment", ci.comment)
    cv.put("private", java.lang.Integer.valueOf(if ci.isPrivate then 1 else 0))
    cv.put("updated_at", java.lang.Long.valueOf(System.currentTimeMillis()))
    w.insert("checkins", null, cv)

  def checkIns(from: Long, to: Long): Vector[CheckIn] =
    query("SELECT id,time,lat,lon,venue,category,external_id,place_id,comment,private FROM checkins WHERE time>=? AND time<? ORDER BY time DESC", from, to) { c =>
      CheckIn(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3), c.getString(4), Option(c.getString(5)).getOrElse(""),
        Option(c.getString(6)).getOrElse(""), if c.isNull(7) then None else Some(c.getLong(7)),
        Option(c.getString(8)).getOrElse(""), c.getInt(9) != 0)
    }

  // --- mode overrides -------------------------------------------------------

  def modeOverrides(from: Long, to: Long): Vector[(Long, Long, TransportMode)] =
    query("SELECT start,end,mode FROM mode_overrides WHERE end>? AND start<?", from, to) { c =>
      (c.getLong(0), c.getLong(1), TransportMode.fromKey(c.getString(2)))
    }

  def setModeOverride(start: Long, end: Long, mode: TransportMode): Unit = tx { db =>
    db.delete("mode_overrides", "start=? AND end=?", Array(start.toString, end.toString))
    val cv = ContentValues()
    cv.put("start", java.lang.Long.valueOf(start))
    cv.put("end", java.lang.Long.valueOf(end))
    cv.put("mode", mode.key)
    cv.put("updated_at", java.lang.Long.valueOf(System.currentTimeMillis()))
    db.insert("mode_overrides", null, cv)
  }

  def deleteCheckIn(id: Long): Unit =
    w.delete("checkins", "id=?", Array(id.toString))

  def checkInsAtPlace(placeId: Long): Vector[CheckIn] =
    checkIns(0, Long.MaxValue).filter(_.placeId.contains(placeId))

  /** City / country from reverse geocoding, cached per place. */
  def placeRegions(): Map[Long, (String, String)] =
    query("SELECT id, city, country FROM places WHERE country IS NOT NULL") { c =>
      c.getLong(0) -> (Option(c.getString(1)).getOrElse(""), c.getString(2))
    }.toMap

  def setPlaceRegion(id: Long, city: String, country: String): Unit =
    val cv = ContentValues()
    cv.put("city", city)
    cv.put("country", country)
    w.update("places", cv, "id=?", Array(id.toString))

  /** Approximate area names ("三軒茶屋付近") for places the user has not named. */
  def placeAreas(): Map[Long, String] =
    query("SELECT id, area FROM places WHERE area IS NOT NULL")(c => c.getLong(0) -> c.getString(1)).toMap

  def setPlaceArea(id: Long, area: String): Unit =
    val cv = ContentValues()
    cv.put("area", area)
    w.update("places", cv, "id=?", Array(id.toString))

  /** One fix per time bucket, for drawing long periods cheaply. */
  def fixesSampled(from: Long, to: Long, bucketMs: Long): Vector[Fix] =
    val archived = AshidoriApp.archive.read(Archive.Fixes, from, to).map(Archive.fixOf)
      .filter(f => !f.hasAccuracy || f.accuracy < 100)
      .groupBy(_.time / bucketMs).values.map(_.minBy(_.time)).toVector
    (archived ++ hotFixesSampled(from, to, bucketMs)).sortBy(_.time)

  private def hotFixesSampled(from: Long, to: Long, bucketMs: Long): Vector[Fix] =
    query(
      s"SELECT MIN(time), lat, lon FROM fixes WHERE time>=? AND time<? AND (acc IS NULL OR acc<100) GROUP BY time/$bucketMs ORDER BY 1",
      from,
      to
    )(c => Fix(c.getLong(0), c.getDouble(1), c.getDouble(2)))

  def firstFixTime(): Option[Long] =
    query("SELECT MIN(time) FROM fixes")(c => if c.isNull(0) then None else Some(c.getLong(0))).head

  @volatile private var firstDay: Option[java.time.LocalDate] = None
  /** [[firstDataDate]], remembered once known (it only moves on import). */
  def firstDataDateCached: Option[java.time.LocalDate] =
    if firstDay.isEmpty then firstDay = firstDataDate()
    firstDay

  /** First day with any position, archived or not. */
  def firstDataDate(): Option[java.time.LocalDate] =
    val archived = AshidoriApp.archive.archivedFiles(Archive.Fixes).headOption.map { f =>
      val n = f.getName.stripSuffix(".parquet")
      if n.length == 7 then java.time.YearMonth.parse(n).atDay(1) else java.time.LocalDate.parse(n)
    }
    val hot = firstFixTime().map(t => java.time.Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).toLocalDate)
    (archived.toSeq ++ hot).minOption

  /** Days (epoch-day numbers in the given offset) that have any fix, for calendar dots. */
  def daysWithData(from: Long, to: Long, offsetMs: Long): Set[Long] =
    query(s"SELECT DISTINCT (time+$offsetMs)/86400000 FROM fixes WHERE time>=? AND time<?", from, to)(_.getLong(0)).toSet

  // --- GNSS status history ------------------------------------------------------

  def insertGnssStatus(s: GnssSnapshot, env: Environment, acc: Float): Unit =
    val cv = ContentValues()
    cv.put("time", java.lang.Long.valueOf(s.time))
    cv.put("visible", java.lang.Integer.valueOf(s.visible))
    cv.put("used", java.lang.Integer.valueOf(s.used))
    cv.put("top4", java.lang.Double.valueOf(s.top4Cn0))
    cv.put("l5", java.lang.Integer.valueOf(s.l5))
    cv.put("env", env.toString)
    nanToNull(cv, "acc", acc)
    w.insert("gnss_status", null, cv)

  def gnssStatus(from: Long): Vector[(GnssSnapshot, Environment, Float)] =
    AshidoriApp.archive.read(Archive.GnssStatus, from, Long.MaxValue).map { r =>
      def i(k: Int) = Option(r(k)).map(_.asInstanceOf[Int]).getOrElse(0)
      (GnssSnapshot(r(0).asInstanceOf[Long], i(1), i(2), Option(r(3)).map(_.asInstanceOf[Double]).getOrElse(0.0), i(4)),
        scala.util.Try(Environment.valueOf(r(5).toString)).getOrElse(Environment.Unknown),
        Option(r(6)).map(_.asInstanceOf[Float]).getOrElse(Float.NaN))
    } ++ hotGnssStatus(from)

  private def hotGnssStatus(from: Long): Vector[(GnssSnapshot, Environment, Float)] =
    query("SELECT time,visible,used,top4,l5,env,acc FROM gnss_status WHERE time>=? ORDER BY time", from) { c =>
      (GnssSnapshot(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4)),
        scala.util.Try(Environment.valueOf(c.getString(5))).getOrElse(Environment.Unknown), flt(c, 6))
    }

  // --- raw lines and archiving ----------------------------------------------------

  def insertRawLines(lines: Iterable[(Long, String)]): Unit = tx { db =>
    val st = db.compileStatement("INSERT INTO raw_lines(time, line) VALUES(?, ?)")
    for (t, l) <- lines do
      st.bindLong(1, t)
      st.bindString(2, l)
      st.executeInsert()
  }

  /** Raw lines of [from, to) in insertion order. */
  def rawLines(from: Long, to: Long): Vector[String] =
    query("SELECT line FROM raw_lines WHERE time>=? AND time<? ORDER BY rowid", from, to)(_.getString(0))

  /** Earliest time still held in SQLite for an archived table (None if empty). */
  def oldestHot(): Option[Long] =
    Seq("SELECT MIN(time) FROM fixes", "SELECT MIN(start) FROM motion", "SELECT MIN(time) FROM battery",
      "SELECT MIN(time) FROM gnss_status", "SELECT MIN(time) FROM raw_lines")
      .flatMap(q => query(q)(c => if c.isNull(0) then None else Some(c.getLong(0))).head)
      .minOption

  /** Rows of one archived table in [from, to), typed as in [[Archive]]. */
  def hotRows(t: Archive.TableDef, from: Long, to: Long): Vector[Seq[Any]] =
    t match
      case Archive.Fixes  => hotFixes(from, to).map(Archive.fixRow)
      case Archive.Motion => hotMotion(from, to).map(Archive.motionRow)
      case Archive.Battery =>
        query("SELECT time,pct,charging,screen_on,mode FROM battery WHERE time>=? AND time<? ORDER BY time", from, to) { c =>
          Seq(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getString(4))
        }
      case Archive.GnssStatus =>
        query("SELECT time,visible,used,top4,l5,env,acc FROM gnss_status WHERE time>=? AND time<? ORDER BY time", from, to) { c =>
          Seq(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4), c.getString(5), flt(c, 6))
        }
      case _ => Vector.empty

  /** Removes [from, to) from every archived SQLite table, once the archive holds it. */
  def deleteHot(from: Long, to: Long): Unit = tx { db =>
    val a = Array(from.toString, to.toString)
    db.delete("fixes", "time>=? AND time<?", a)
    db.delete("motion", "start>=? AND start<?", a)
    db.delete("battery", "time>=? AND time<?", a)
    db.delete("gnss_status", "time>=? AND time<?", a)
    db.delete("raw_lines", "time>=? AND time<?", a)
  }

  // --- sync ---------------------------------------------------------------------

  def syncValue(name: String): Long =
    query("SELECT value FROM sync_state WHERE name=?", name)(_.getLong(0)).headOption.getOrElse(0L)

  def setSyncValue(name: String, v: Long): Unit =
    w.execSQL("INSERT OR REPLACE INTO sync_state(name, value) VALUES(?, ?)", Array[AnyRef](name, java.lang.Long.valueOf(v)))

  /** Seals batches into the outbox in one transaction: each claims the next
    * seq of its stream (`<reg>|seq:<stream>`), and the source progress is
    * written with them. A crash leaves either all of it or none.
    * `seal(seq)` encrypts for that slot; it runs inside the transaction.
    */
  def enqueueBatches(reg: String, deviceId: String, cls: String, batches: Seq[(String, Long => Array[Byte])],
                     progress: Seq[(String, Long)]): Int = tx { db =>
    val st = db.compileStatement(
      "INSERT INTO sync_outbox(reg, device_id, stream, seq, class, blob, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)")
    for (stream, seal) <- batches do
      val key = s"$reg|seq:$stream"
      val seq = syncValue(key)
      st.clearBindings()
      st.bindString(1, reg)
      st.bindString(2, deviceId)
      st.bindString(3, stream)
      st.bindLong(4, seq)
      st.bindString(5, cls)
      st.bindBlob(6, seal(seq))
      st.bindLong(7, System.currentTimeMillis())
      st.executeInsert()
      setSyncValue(key, seq + 1)
    progress.foreach(setSyncValue)
    batches.size
  }

  /** Outbox rows of a registration and constraint classes, oldest first (without the blob). */
  def outbox(reg: String, classes: Set[String]): Vector[Db.OutboxEntry] =
    query(s"SELECT id, device_id, stream, seq, class FROM sync_outbox WHERE reg=? AND class IN (${classes.toSeq.map(_ => "?").mkString(",")}) ORDER BY id",
      (reg +: classes.toSeq)*) { c =>
      Db.OutboxEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
    }

  /** The sealed blob, read in pieces: a cursor window holds at most ~2 MB. */
  def outboxBlob(id: Long): Array[Byte] =
    val len = query("SELECT length(blob) FROM sync_outbox WHERE id=?", id)(_.getLong(0)).headOption.getOrElse(0L)
    val out = java.io.ByteArrayOutputStream(len.toInt)
    val piece = 512 * 1024
    var off = 0L
    while off < len do
      query("SELECT substr(blob, ?, ?) FROM sync_outbox WHERE id=?", off + 1, piece, id)(_.getBlob(0)).foreach(out.write)
      off += piece
    out.toByteArray

  def deleteOutbox(id: Long): Unit =
    w.delete("sync_outbox", "id=?", Array(id.toString))

  /** Forgets all sync progress and unsent batches (unpairing). */
  def clearSync(): Unit = tx { db =>
    db.delete("sync_outbox", null, null)
    db.delete("sync_state", null, null)
  }

  /** State from before registration-keyed sync (unprefixed `seq:`, `up:`,
    * `updated:` rows). The seq counters and handed-over archive files carry
    * over to `reg`; `updated:` watermarks are dropped (the editable tables
    * are sent again once by change counter). `legacyPending` are the old
    * pending files `(stream, seq, blob)`; they become outbox rows.
    */
  def adoptLegacySync(reg: String, deviceId: String, legacyPending: Seq[(String, Long, Array[Byte])]): Unit = tx { db =>
    val legacy = query("SELECT name, value FROM sync_state WHERE instr(name, '|')=0")(c => c.getString(0) -> c.getLong(1))
    for (name, v) <- legacy if name.startsWith("seq:") || name.startsWith("up:") do
      setSyncValue(s"$reg|$name", math.max(v, syncValue(s"$reg|$name")))
    for (stream, seq, blob) <- legacyPending do
      val cv = ContentValues()
      cv.put("reg", reg)
      cv.put("device_id", deviceId)
      cv.put("stream", stream)
      cv.put("seq", java.lang.Long.valueOf(seq))
      cv.put("class", if stream == "archive" then "raw" else "small")
      cv.put("blob", blob)
      cv.put("created_at", java.lang.Long.valueOf(System.currentTimeMillis()))
      db.insertWithOnConflict("sync_outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
      // A file written before its seq was saved.
      if syncValue(s"$reg|seq:$stream") <= seq then setSyncValue(s"$reg|seq:$stream", seq + 1)
    for (name, _) <- legacy do db.delete("sync_state", "name=?", Array(name))
  }

  private def rowJson(c: Cursor, cols: Seq[(String, String)], offset: Int): ujson.Obj =
    val o = ujson.Obj()
    for ((_, field), i) <- cols.zipWithIndex do
      val k = i + offset
      c.getType(k) match
        case Cursor.FIELD_TYPE_NULL    => ()
        case Cursor.FIELD_TYPE_INTEGER => o(field) = ujson.Num(c.getLong(k).toDouble)
        case Cursor.FIELD_TYPE_FLOAT   => o(field) = ujson.Num(c.getDouble(k))
        case _                         => o(field) = ujson.Str(c.getString(k))
    o

  /** Append-only tables: rows with rowid above the cursor, simulated data excluded. */
  def rowsAfter(table: String, rowid: Long, limit: Int): Vector[(Long, ujson.Obj)] =
    val cols = Db.SyncColumns(table)
    val notSim = table match
      case "fixes"  => " AND (provider IS NULL OR provider<>'sim')"
      case "motion" => " AND (src IS NULL OR src<>'sim')"
      case _        => ""
    query(s"SELECT rowid, ${cols.map(_._1).mkString(",")} FROM $table WHERE rowid>?$notSim ORDER BY rowid LIMIT $limit", rowid) { c =>
      (c.getLong(0), rowJson(c, cols, 1))
    }

  /** Editable tables: rows changed after the change-counter watermark, as
    * `(sync_rev, record)` in counter order.
    */
  def rowsChangedAfter(table: String, rev: Long, limit: Int): Vector[(Long, ujson.Obj)] =
    val cols = Db.SyncColumns(table)
    query(s"SELECT sync_rev, ${cols.map(_._1).mkString(",")} FROM $table WHERE sync_rev>? ORDER BY sync_rev LIMIT $limit", rev) { c =>
      (c.getLong(0), rowJson(c, cols, 1))
    }

  def pendingGnssFiles(): Vector[(Long, String)] =
    query("SELECT rowid, file FROM gnss_sessions WHERE uploaded=0 ORDER BY rowid")(c => (c.getLong(0), c.getString(1)))

  def markGnssUploaded(rowid: Long): Unit =
    w.execSQL("UPDATE gnss_sessions SET uploaded=1 WHERE rowid=?", Array[AnyRef](java.lang.Long.valueOf(rowid)))

  /** Removes data inserted by the simulator (debug tool). */
  def deleteSimulated(): Int = tx { db =>
    db.delete("fixes", "provider='sim'", null) + db.delete("motion", "src='sim'", null)
  }

  /** Deletes all raw data in a range (used by "delete this day"). */
  def deleteRange(from: Long, to: Long): Unit = tx { db =>
    db.delete("fixes", "time>=? AND time<?", Array(from.toString, to.toString))
    db.delete("motion", "start>=? AND start<?", Array(from.toString, to.toString))
  }

object Db:
  val Version = 5

  /** A sealed batch waiting in the outbox. `cls` is the constraint class:
    * "small" (any allowed network) or "raw" (unmetered only).
    */
  final case class OutboxEntry(id: Long, deviceId: String, stream: String, seq: Long, cls: String)

  /** Editable tables and the columns whose change bumps `sync_rev` (the synced ones). */
  val RevColumns: Seq[(String, Seq[String])] = Seq(
    "places" -> Seq("lat", "lon", "radius", "name", "kind", "external_id", "updated_at"),
    "checkins" -> Seq("time", "lat", "lon", "venue", "category", "external_id", "place_id", "comment", "private", "updated_at"),
    "mode_overrides" -> Seq("start", "end", "mode", "updated_at")
  )

  /** Column -> JSON field mapping per synced table (docs/sync-protocol.md). */
  val SyncColumns: Map[String, Seq[(String, String)]] = Map(
    "fixes" -> Seq("time" -> "t", "lat" -> "lat", "lon" -> "lon", "acc" -> "acc", "alt" -> "alt", "speed" -> "spd",
      "bearing" -> "brg", "provider" -> "src"),
    "motion" -> Seq("start" -> "s", "end" -> "e", "std" -> "std", "mean" -> "mean", "steps" -> "steps"),
    "battery" -> Seq("time" -> "t", "pct" -> "pct", "charging" -> "chg", "screen_on" -> "scr"),
    "places" -> Seq("id" -> "id", "lat" -> "lat", "lon" -> "lon", "radius" -> "r", "name" -> "name", "kind" -> "kind",
      "external_id" -> "ext", "updated_at" -> "updatedAt"),
    "checkins" -> Seq("id" -> "id", "time" -> "t", "lat" -> "lat", "lon" -> "lon", "venue" -> "venue", "category" -> "cat",
      "external_id" -> "ext", "place_id" -> "place", "comment" -> "comment", "private" -> "private",
      "updated_at" -> "updatedAt"),
    "mode_overrides" -> Seq("start" -> "s", "end" -> "e", "mode" -> "mode", "updated_at" -> "updatedAt")
  )
