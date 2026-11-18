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
  *
  * No change is missed for good: new rows are found by rowid in SQLite, and
  * archiving, which moves a day's rows out of SQLite, has the maintenance
  * process mark that day and its neighbours changed in this file before it
  * and again after its commit ([[beforeArchiving]], [[afterArchiving]]): a
  * late row (a spill imported, say) that came after a build had used up
  * the first mark and left with the same commit is followed by another
  * build. A durable note of the day archived (`visit_index.archiving`)
  * lasts from before the move to after the second mark: if the process
  * dies in between, the next maintenance run marks the day once its
  * recovery has finished the commit ([[settleArchiving]]). If a marking or
  * the note fails, a marker file says the whole index is of unknown
  * freshness ([[markStale]]): the next use empties it and it is built
  * again. None of this ever waits for or fails the archiving. Rows of
  * positions and motion leave SQLite only by archiving (marked both sides)
  * or a deletion (its sequence); the other tables (energy, battery,
  * pressure, raw lines) do not change a stay. Only a write path added
  * later that bypasses SQLite would need a mark of its own.
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
  /** The day being archived, from before the move to after the mark after it. */
  private def archivingFile = File(AshidoriApp.app.getNoBackupFilesDir, "visit_index.archiving")
  /** The marker of unknown freshness, and the same while being acted on. */
  private def staleFile = File(AshidoriApp.app.getNoBackupFilesDir, "visit_index.stale")
  private def claimedFile = File(AshidoriApp.app.getNoBackupFilesDir, "visit_index.stale.claimed")

  /** The index may have missed a change (a marking failed, or a repair
    * quarantined files): emptied at its next use and built again.
    */
  def markStale(why: String): Unit =
    val ok = scala.util.Try(staleFile.createNewFile() || staleFile.exists).getOrElse(false)
    Log.w(s"visits: index of unknown freshness ($why)" + (if ok then "" else "; the marker could not be written"))

  /** A marker found: claimed (a new one may come meanwhile and stays), the
    * index emptied, then the claim removed.
    */
  private def emptyIfStale(): Unit =
    if staleFile.exists then staleFile.renameTo(claimedFile)
    if claimedFile.exists then
      store.clear()
      claimedFile.delete()
      Log.i("visits: index emptied (unknown freshness); built again")

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
    def stays(day: LocalDate): Vector[Visit] =
      if failBuildsForTest then throw IllegalStateException("debug: builds fail")
      TimelineRepo.stays(day)

  /** Debug builds (DebugReceiver VISITS fail): every query fails, to see the list's failure state. */
  @volatile var failQueriesForTest = false
  /** Debug builds (VISITS failbuild): every day fails to build. */
  @volatile var failBuildsForTest = false

  /** What the list shows: the days found, the days of the record never
    * built, and those not up to date (changed since built included).
    */
  final case class Answer(days: Vector[DayVisit], unbuilt: Int, stale: Int)

  /** The list is opened: changes noted (until `stop`), today and a day being
    * archived marked to be built again. False: that failed (said in the list).
    */
  def opened(stop: () => Boolean = () => false): Boolean = guarded("note") {
    emptyIfStale()
    VisitIndex.note(store, Record, AshidoriApp.zone, stop)
    VisitIndex.touch(store, today)
    // Marked, not settled: only the maintenance process clears the note.
    archivingDay.foreach(VisitIndex.archiving(store, _))
  }.isDefined

  /** The days of `venue` the index holds now. */
  def query(venue: LatLng): Option[Answer] = guarded("query") {
    if failQueriesForTest then throw IllegalStateException("debug: queries fail")
    emptyIfStale()
    val t0 = System.nanoTime()
    val days = VisitIndex.query(store, venue)
    val t1 = System.nanoTime()
    val first = AshidoriApp.db.firstDataDateCached.getOrElse(today)
    val unbuilt = VisitIndex.unbuilt(store, first, today)
    val stale = VisitIndex.stale(store, first, today)
    Log.d(f"visits: query ${days.size} days in ${(t1 - t0) / 1e6}%.1f ms (${(System.nanoTime() - t1) / 1e6}%.1f ms more for the count), $unbuilt days not built")
    Answer(days, unbuilt, stale)
  }

  /** One run of building: at most `maxDays`, until `stop`; days in `skip` not tried. */
  def run(maxDays: Int, stop: () => Boolean, skip: Set[LocalDate] = Set.empty): Option[VisitIndex.Run] = guarded("build") {
    emptyIfStale()
    val t0 = System.nanoTime()
    val r = VisitIndex.build(store, Record, AshidoriApp.zone, today, maxDays, stop, skip)
    if r.built > 0 || r.failed.nonEmpty then
      Log.d(f"visits: built ${r.built} days in ${(System.nanoTime() - t0) / 1e6}%.0f ms, ${r.left} left" +
        (if r.failed.nonEmpty then s", failed ${r.failed.mkString(",")}" else ""))
    r
  }

  /** In the maintenance process, before archiving `day`: that day and its
    * neighbours marked changed ([[VisitIndex.archiving]]), one short
    * transaction; on failure the index is marked stale. Never throws.
    */
  def beforeArchiving(day: LocalDate): Unit =
    // The note first: it outlives this process until the mark after the commit.
    if scala.util.Try(Maintenance.write(archivingFile, day.toString)).isFailure then markStale(s"noting $day failed")
    mark(day, "before")

  /** In the maintenance process, after archiving `day` (its commits done,
    * or it failed): the day and its neighbours marked again, then the note
    * removed. Never throws.
    */
  def afterArchiving(day: LocalDate): Unit =
    if mark(day, "after") then scala.util.Try(archivingFile.delete())

  /** In the maintenance process, after recovery has finished an interrupted
    * commit: the day its note names is marked, and the note removed.
    */
  def settleArchiving(): Unit = archivingDay.foreach(afterArchiving)

  private def archivingDay: Option[LocalDate] =
    scala.util.Try(LocalDate.parse(String(java.nio.file.Files.readAllBytes(archivingFile.toPath), java.nio.charset.StandardCharsets.UTF_8).trim)).toOption

  /** The day and its neighbours marked changed; on failure the index is marked stale. */
  private def mark(day: LocalDate, when: String): Boolean =
    val t0 = System.nanoTime()
    val ok = guarded(s"mark $when archiving")(VisitIndex.archiving(store, day)).isDefined
    if !ok then markStale(s"marking $day $when archiving failed")
    else Log.d(f"visits: $day and its neighbours marked $when archiving in ${(System.nanoTime() - t0) / 1e6}%.1f ms")
    ok

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

    private def params(n: Int) = Seq.fill(n)("?").mkString(",")

    def candidateDays(cells: Seq[Long], before: Long, limit: Int): Vector[Long] =
      if cells.isEmpty then Vector.empty
      else
        q(s"SELECT DISTINCT day FROM cells WHERE cell IN (${params(cells.size)}) AND day<? ORDER BY day DESC LIMIT ?",
          (cells ++ Seq(before, limit.toLong))*)(_.getLong(0))

    def candidates(cells: Seq[Long], days: Seq[Long]): Vector[(Long, Visit)] =
      if cells.isEmpty || days.isEmpty then Vector.empty
      else
        q(s"SELECT s.day, s.start, s.end, s.lat, s.lon, s.radius FROM stays s WHERE s.id IN " +
          s"(SELECT stay FROM cells WHERE cell IN (${params(cells.size)}) AND day IN (${params(days.size)}))", (cells ++ days)*) { c =>
          c.getLong(0) -> Visit(c.getLong(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getDouble(5), 0)
        }

    def clear(): Unit = transaction {
      for t <- Seq("cells", "stays", "days", "meta") do exec(s"DELETE FROM $t")
    }
