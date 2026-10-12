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
        |kind TEXT, external_id TEXT, updated_at INTEGER)""".stripMargin,
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

  override def onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int): Unit = ()

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
    query("SELECT id,lat,lon,radius,name,kind,external_id FROM places") { c =>
      Place(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), Option(c.getString(4)).getOrElse(""),
        PlaceKind.fromKey(Option(c.getString(5)).getOrElse("")), Option(c.getString(6)).getOrElse(""))
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
  val Version = 1
