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
      // Server sync: highest rowid uploaded per append-only table, and the
      // updated_at watermark for editable tables.
      "CREATE TABLE sync_state(name TEXT PRIMARY KEY, value INTEGER)"
    ).foreach(db.execSQL)

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

  def fixes(from: Long, to: Long): Vector[Fix] =
    query("SELECT time,lat,lon,acc,alt,speed,bearing,provider,sats FROM fixes WHERE time>=? AND time<? ORDER BY time", from, to) { c =>
      Fix(c.getLong(0), c.getDouble(1), c.getDouble(2), flt(c, 3), dbl(c, 4), flt(c, 5), flt(c, 6), c.getString(7), c.getInt(8))
    }

  def lastFix(): Option[Fix] =
    query("SELECT time,lat,lon,acc,alt,speed,bearing,provider,sats FROM fixes ORDER BY time DESC LIMIT 1") { c =>
      Fix(c.getLong(0), c.getDouble(1), c.getDouble(2), flt(c, 3), dbl(c, 4), flt(c, 5), flt(c, 6), c.getString(7), c.getInt(8))
    }.headOption

  def motion(from: Long, to: Long): Vector[MotionWindow] =
    query("SELECT start,end,std,mean,steps FROM motion WHERE start>=? AND start<? ORDER BY start", from, to) { c =>
      MotionWindow(c.getLong(0), c.getLong(1), flt(c, 2), flt(c, 3), c.getInt(4))
    }

  def steps(from: Long, to: Long): Int =
    query("SELECT COALESCE(SUM(steps),0) FROM motion WHERE start>=? AND start<?", from, to)(_.getInt(0)).head

  def battery(from: Long): Vector[DrainEstimator.Sample] =
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
    query(
      s"SELECT MIN(time), lat, lon FROM fixes WHERE time>=? AND time<? AND (acc IS NULL OR acc<100) GROUP BY time/$bucketMs ORDER BY 1",
      from,
      to
    )(c => Fix(c.getLong(0), c.getDouble(1), c.getDouble(2)))

  def firstFixTime(): Option[Long] =
    query("SELECT MIN(time) FROM fixes")(c => if c.isNull(0) then None else Some(c.getLong(0))).head

  /** Days (epoch-day numbers in the given offset) that have any fix, for calendar dots. */
  def daysWithData(from: Long, to: Long, offsetMs: Long): Set[Long] =
    query(s"SELECT DISTINCT (time+$offsetMs)/86400000 FROM fixes WHERE time>=? AND time<?", from, to)(_.getLong(0)).toSet

  // --- sync ---------------------------------------------------------------------

  def syncValue(name: String): Long =
    query("SELECT value FROM sync_state WHERE name=?", name)(_.getLong(0)).headOption.getOrElse(0L)

  def setSyncValue(name: String, v: Long): Unit =
    w.execSQL("INSERT OR REPLACE INTO sync_state(name, value) VALUES(?, ?)", Array[AnyRef](name, java.lang.Long.valueOf(v)))

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

  /** Editable tables: rows changed after the watermark. */
  def rowsUpdatedAfter(table: String, updatedAt: Long, limit: Int): Vector[(Long, ujson.Obj)] =
    val cols = Db.SyncColumns(table)
    query(s"SELECT rowid, ${cols.map(_._1).mkString(",")} FROM $table WHERE updated_at>? ORDER BY updated_at LIMIT $limit", updatedAt) { c =>
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
  val Version = 2

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
