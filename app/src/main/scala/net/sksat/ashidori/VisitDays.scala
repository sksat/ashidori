package net.sksat.ashidori

import android.content.{ContentValues, Context}
import android.database.sqlite.{SQLiteDatabase, SQLiteDatabaseCorruptException, SQLiteOpenHelper}
import java.io.File
import java.time.LocalDate
import java.util.concurrent.{Executors, ExecutorService}
import scala.util.Using
import scala.util.control.NonFatal
import ashidori.core.*
import VisitIndex.{DayState, DayVisit, Marks, Changes}

/** 「訪れた日」: the days with a stay at a venue, from the per-day spatial
  * index of the stays ([[VisitIndex]]) kept in its own SQLite file,
  * `no_backup/visit_index.db`.
  *
  * Derived data only: it is never a source of anything, can be deleted at
  * any time and is built again from the record (the main database and the
  * archive, read through their readers: [[TimelineRepo.built]], rule C). It
  * is not in the backup ZIP, not synced and not sent. A corrupt file is
  * deleted and made again (SQLite's default handler); any failure of the
  * index only leaves the list empty, never touches the record, and the
  * logger never opens it.
  *
  * Built in the background, a bounded run at a time: in the main process
  * while the list is open (one day at a time, as the day view builds a day),
  * and in the maintenance process after archiving ([[maintain]]).
  */
object VisitDays:
  /** Days built per run while the list is open (the list is refreshed after each). */
  val RunDays = 10
  /** Days built per maintenance run: while charging, and otherwise. */
  val MaintainDays = 120
  val MaintainDaysOnBattery = 7
  val MaintainMs: Long = 2 * 60_000L

  /** The background thread of the list (queries and runs), at background priority. */
  lazy val exec: ExecutorService = Executors.newSingleThreadExecutor { r =>
    val t = Thread(() => { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ashidori-visits")
    t.setDaemon(true)
    t
  }

  def file(c: Context): File = File(c.getNoBackupFilesDir, "visit_index.db")

  @volatile private var helper: IndexDb = null
  private def store: IndexDb = synchronized {
    if helper == null then helper = IndexDb(AshidoriApp.app)
    helper
  }

  /** Closes and deletes the index (made again on next use). */
  def reset(): Unit = synchronized {
    if helper != null then scala.util.Try(helper.close())
    helper = null
    SQLiteDatabase.deleteDatabase(file(AshidoriApp.app))
  }

  /** `f`, or None after logging its failure: a corrupt index is deleted. */
  private def guarded[A](what: String)(f: => A): Option[A] =
    try Some(f)
    catch
      case e: SQLiteDatabaseCorruptException =>
        Log.w(s"visits: $what: index corrupt; deleted", e)
        reset()
        None
      case e: OutOfMemoryError =>
        Log.w(s"visits: $what: out of memory", e)
        None
      case NonFatal(e) =>
        Log.w(s"visits: $what failed", e)
        None

  private def today = LocalDate.now(AshidoriApp.zone)

  /** The record as the index reads it. */
  private object Record extends VisitIndex.Source:
    def marks(): Marks =
      val db = AshidoriApp.db
      Marks(db.rowidHigh("fixes"), db.rowidHigh("motion"), db.deletionSeq())
    def changes(from: Marks, to: Marks): Changes =
      val db = AshidoriApp.db
      Changes(db.hoursAdded("fixes", from.fixes, to.fixes) ++ db.hoursAdded("motion", from.motion, to.motion),
        db.deletedBetween(from.deletions, to.deletions))
    def firstDay(): Option[LocalDate] = AshidoriApp.db.firstDataDate()
    def stays(day: LocalDate): Vector[Visit] = TimelineRepo.stays(day)

  /** What the list shows: the days found, and the days of the record not built yet. */
  final case class Answer(days: Vector[DayVisit], unbuilt: Int)

  /** The list is opened: changes noted, today marked to be built again. */
  def opened(): Unit = guarded("note") {
    VisitIndex.note(store, Record, AshidoriApp.zone)
    VisitIndex.touch(store, today)
  }

  /** The days of `venue` the index holds now. */
  def query(venue: LatLng): Option[Answer] = guarded("query") {
    val t0 = System.nanoTime()
    val days = VisitIndex.query(store, venue)
    val t1 = System.nanoTime()
    val first = AshidoriApp.db.firstDataDateCached.getOrElse(today)
    val unbuilt = VisitIndex.unbuilt(store, first, today)
    Log.d(f"visits: query ${days.size} days in ${(t1 - t0) / 1e6}%.1f ms (${(System.nanoTime() - t1) / 1e6}%.1f ms more for the count), $unbuilt days not built")
    Answer(days, unbuilt)
  }

  /** One run of building: at most `maxDays`, until `stop`; days in `skip` not tried. */
  def run(maxDays: Int, stop: () => Boolean, skip: Set[LocalDate] = Set.empty): Option[VisitIndex.Run] = guarded("build") {
    val t0 = System.nanoTime()
    val r = VisitIndex.build(store, Record, AshidoriApp.zone, today, maxDays, stop, skip)
    if r.built > 0 || r.failed.nonEmpty then
      Log.d(f"visits: built ${r.built} days in ${(System.nanoTime() - t0) / 1e6}%.0f ms, ${r.left} left" +
        (if r.failed.nonEmpty then s", failed ${r.failed.mkString(",")}" else ""))
    r
  }

  /** In the maintenance process, first: changes noted before archiving
    * moves new rows out of SQLite (the marks read rowids there).
    */
  def noteBeforeArchiving(): Unit = guarded("note")(VisitIndex.note(store, Record, AshidoriApp.zone))

  /** In the maintenance process, after archiving: a bounded run (days, and [[MaintainMs]]). */
  def maintain(charging: Boolean, halt: () => Boolean): Unit =
    val until = System.currentTimeMillis() + MaintainMs
    run(if charging then MaintainDays else MaintainDaysOnBattery, () => halt() || System.currentTimeMillis() > until)

  /** Android's default handler, which deletes a corrupt database: only for
    * this derived file (the record's database keeps it, Db.KeepOnCorruption).
    */
  private object DerivedDeleteOnCorruption extends android.database.DatabaseErrorHandler:
    private val delete = android.database.DefaultDatabaseErrorHandler()
    override def onCorruption(db: SQLiteDatabase): Unit =
      Log.w(s"visits: index corrupt; deleted (made again from the record): ${db.getPath}")
      delete.onCorruption(db)

  /** The index on SQLite: days, stays, and the (cell, day) rows of each stay
    * with an index on cell.
    */
  private final class IndexDb(c: Context) extends SQLiteOpenHelper(c, file(c).getPath, null, 1, DerivedDeleteOnCorruption)
      with VisitIndex.Store:
    setWriteAheadLoggingEnabled(true)

    override def onCreate(db: SQLiteDatabase): Unit =
      Seq(
        "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)",
        "CREATE TABLE gen(id INTEGER PRIMARY KEY CHECK(id=0), v INTEGER NOT NULL)",
        "CREATE TABLE days(day INTEGER PRIMARY KEY, want INTEGER NOT NULL, built INTEGER)",
        "CREATE TABLE stays(id INTEGER PRIMARY KEY, day INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL," +
          " lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL)",
        "CREATE INDEX stays_day ON stays(day)",
        "CREATE TABLE cells(cell INTEGER NOT NULL, day INTEGER NOT NULL, stay INTEGER NOT NULL, PRIMARY KEY(cell, day, stay)) WITHOUT ROWID",
        "CREATE INDEX cells_stay ON cells(stay)"
      ).foreach(db.execSQL)

    /** Derived: another layout is dropped and made again. */
    private def recreate(db: SQLiteDatabase): Unit =
      for t <- Seq("meta", "gen", "days", "stays", "cells") do db.execSQL(s"DROP TABLE IF EXISTS $t")
      onCreate(db)
    override def onUpgrade(db: SQLiteDatabase, o: Int, n: Int): Unit = recreate(db)
    override def onDowngrade(db: SQLiteDatabase, o: Int, n: Int): Unit = recreate(db)

    private def w = getWritableDatabase
    private def q[A](sql: String, args: Any*)(row: android.database.Cursor => A): Vector[A] =
      Using.resource(w.rawQuery(sql, args.map(_.toString).toArray)) { c =>
        val b = Vector.newBuilder[A]
        while c.moveToNext() do b += row(c)
        b.result()
      }
    private def exec(sql: String, args: Any*): Unit = w.execSQL(sql, args.map(_.asInstanceOf[AnyRef]).toArray)

    def transaction[A](f: => A): A =
      val db = w
      db.beginTransaction()
      try
        val a = f
        db.setTransactionSuccessful()
        a
      finally db.endTransaction()

    def meta(key: String): Option[String] = q("SELECT v FROM meta WHERE k=?", key)(_.getString(0)).headOption
    def setMeta(key: String, value: String): Unit = exec("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", key, value)

    def nextGen(): Long = transaction {
      exec("INSERT INTO gen(id, v) VALUES(0, 1) ON CONFLICT(id) DO UPDATE SET v = v + 1")
      q("SELECT v FROM gen WHERE id=0")(_.getLong(0)).head
    }

    private def stateOf(c: android.database.Cursor, at: Int) =
      DayState(c.getLong(at), Option.when(!c.isNull(at + 1))(c.getLong(at + 1)))

    def day(d: Long): Option[DayState] = q("SELECT want, built FROM days WHERE day=?", d)(stateOf(_, 0)).headOption

    def setDay(d: Long, s: DayState): Unit =
      val cv = ContentValues()
      cv.put("day", java.lang.Long.valueOf(d))
      cv.put("want", java.lang.Long.valueOf(s.want))
      s.built match
        case Some(b) => cv.put("built", java.lang.Long.valueOf(b))
        case None    => cv.putNull("built")
      w.insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

    def days(from: Long, to: Long): Map[Long, DayState] =
      q("SELECT day, want, built FROM days WHERE day>=? AND day<=?", from, to)(c => c.getLong(0) -> stateOf(c, 1)).toMap

    def putStays(d: Long, stays: Seq[(Visit, Seq[Long])]): Unit = transaction {
      exec("DELETE FROM cells WHERE stay IN (SELECT id FROM stays WHERE day=?)", d)
      exec("DELETE FROM stays WHERE day=?", d)
      val cv = ContentValues()
      for (v, cells) <- stays do
        cv.clear()
        cv.put("day", java.lang.Long.valueOf(d))
        cv.put("start", java.lang.Long.valueOf(v.start))
        cv.put("end", java.lang.Long.valueOf(v.end))
        cv.put("lat", java.lang.Double.valueOf(v.lat))
        cv.put("lon", java.lang.Double.valueOf(v.lon))
        cv.put("radius", java.lang.Double.valueOf(v.radius))
        val id = w.insertOrThrow("stays", null, cv)
        val st = w.compileStatement("INSERT OR IGNORE INTO cells(cell, day, stay) VALUES(?, ?, ?)")
        try
          for cell <- cells do
            st.bindLong(1, cell)
            st.bindLong(2, d)
            st.bindLong(3, id)
            st.executeInsert()
        finally st.close()
    }

    def removeStays(from: Long, to: Long): Unit = transaction {
      exec("DELETE FROM cells WHERE stay IN (SELECT id FROM stays WHERE start<? AND end>?)", to, from)
      exec("DELETE FROM stays WHERE start<? AND end>?", to, from)
    }

    def candidates(cells: Seq[Long]): Vector[(Long, Visit)] =
      if cells.isEmpty then Vector.empty
      else
        q(s"SELECT s.day, s.start, s.end, s.lat, s.lon, s.radius FROM stays s WHERE s.id IN " +
          s"(SELECT stay FROM cells WHERE cell IN (${cells.map(_ => "?").mkString(",")}))", cells*) { c =>
          c.getLong(0) -> Visit(c.getLong(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getDouble(5), 0)
        }

    def clear(): Unit = transaction {
      for t <- Seq("cells", "stays", "days", "meta") do exec(s"DELETE FROM $t")
    }
