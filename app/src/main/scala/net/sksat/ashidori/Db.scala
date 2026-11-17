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
final class Db(ctx: Context, name: String = "ashidori.db")
    extends SQLiteOpenHelper(ctx, name, null, Db.Version, Db.KeepOnCorruption):
  // A commit is on disk when it returns (Android's WAL default, NORMAL, can
  // lose the last commits in a power loss): every connection, both processes.
  // The open parameters replace the constructor's: the error handler that
  // keeps a corrupt database (Android's default deletes it) is set again.
  setOpenParams(SQLiteDatabase.OpenParams.Builder().setSynchronousMode("FULL").setErrorHandler(Db.KeepOnCorruption).build())

  override def onConfigure(db: SQLiteDatabase): Unit =
    db.enableWriteAheadLogging()
    db.setForeignKeyConstraintsEnabled(true)

  override def onCreate(db: SQLiteDatabase): Unit =
    Seq(
      DbSchema.CreateFixes,
      "CREATE INDEX fixes_time ON fixes(time)",
      "CREATE TABLE motion(start INTEGER NOT NULL, end INTEGER NOT NULL, std REAL, mean REAL, steps INTEGER, src TEXT)",
      "CREATE INDEX motion_start ON motion(start)",
      DbSchema.CreateBattery,
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
    createVenueCache(db)
    addEnergy(db)
    addElevation(db)

  private def columns(db: SQLiteDatabase, table: String): Set[String] =
    Using.resource(db.rawQuery(s"PRAGMA table_info($table)", null)) { c =>
      val names = Set.newBuilder[String]
      while c.moveToNext() do names += c.getString(c.getColumnIndexOrThrow("name"))
      names.result()
    }

  /** Version 7: battery readings beyond the percentage, and the logger's
    * state spans ([[EnergyState]]). Only adds what is missing.
    */
  private def addEnergy(db: SQLiteDatabase): Unit =
    DbSchema.toV7(columns(db, "battery"), columns(db, "energy_state")).foreach(db.execSQL)

  /** Version 8: vertical accuracy and MSL altitude of fixes, and barometric
    * pressure ([[Elevation]]). Only adds what is missing.
    */
  private def addElevation(db: SQLiteDatabase): Unit =
    DbSchema.toV8(columns(db, "fixes"), columns(db, "sync_outbox")).foreach(db.execSQL)

  /** Rowids of the archived tables are never reused ([[claimRowids]]), and
    * the import of pre-Parquet text logs records its progress. Created on
    * open, without a schema version: an older app version still opens the
    * database (and ignores these tables).
    */
  override def onOpen(db: SQLiteDatabase): Unit =
    super.onOpen(db)
    if !db.isReadOnly then
      db.beginTransaction()
      try
        createArchiveBookkeeping(db)
        // Rows an older app version inserted (it ignores the floor) are below it again.
        Db.ArchivedTables.keys.foreach(t => db.execSQL(ArchiveSql.raiseFloor(t)))
        db.setTransactionSuccessful()
      finally db.endTransaction()
      val sync = Using.resource(db.rawQuery("PRAGMA synchronous", null))(c => if c.moveToFirst() then c.getInt(0) else -1)
      if sync != 2 then
        Log.w(s"database synchronous=$sync, not FULL")
        LifeLog.add(LifeKind.Note, s"データベースの同期書き込みが FULL ではない（synchronous=$sync）")

  private def createArchiveBookkeeping(db: SQLiteDatabase): Unit =
    db.execSQL(ArchiveSql.CreateFloor)
    db.execSQL("CREATE TABLE IF NOT EXISTS legacy_import(file TEXT PRIMARY KEY, lines INTEGER NOT NULL)")

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

  /** Online venue search results kept on the device ([[VenueCache]]): one
    * row per search, keyed by its cell and radius, and its venues. Not user
    * data: never synced or exported, and safe to drop.
    */
  private def createVenueCache(db: SQLiteDatabase): Unit =
    db.execSQL("""CREATE TABLE IF NOT EXISTS venue_cache(cell_y INTEGER NOT NULL, cell_x INTEGER NOT NULL,
      |radius INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, fetched_at INTEGER NOT NULL,
      |used_at INTEGER NOT NULL, PRIMARY KEY(cell_y, cell_x, radius))""".stripMargin)
    db.execSQL("""CREATE TABLE IF NOT EXISTS venue_cache_items(cell_y INTEGER NOT NULL, cell_x INTEGER NOT NULL,
      |radius INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, lat REAL NOT NULL,
      |lon REAL NOT NULL, FOREIGN KEY(cell_y, cell_x, radius) REFERENCES venue_cache(cell_y, cell_x, radius)
      |ON DELETE CASCADE)""".stripMargin)
    db.execSQL("CREATE INDEX IF NOT EXISTS venue_cache_items_key ON venue_cache_items(cell_y, cell_x, radius)")

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
    // Self-contained (IF NOT EXISTS): safe to run again from a later step.
    if oldV < 6 then createVenueCache(db)
    if oldV < 7 then addEnergy(db)
    if oldV < 8 then addElevation(db)

  /** A database of a newer app version: not opened (Android's default
    * would fail the same way). The data stays as it is; the way back is a
    * newer app, never a reset or a lowered version number. The app checks
    * [[Db.versionOnDisk]] first and says so instead of crashing.
    */
  override def onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int): Unit =
    throw android.database.sqlite.SQLiteException(s"database version $oldV is newer than this app's $newV")

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

  /** The first of `n` new rowids for `table`, in a write transaction.
    *
    * SQLite gives a new row the largest rowid plus one, so once the rows
    * with the largest rowids are deleted (archived), their rowids would be
    * given again. Archiving reads and deletes "rowid up to the largest at its
    * start" (see [[maxRowids]]); a reused rowid would let it delete a row it
    * never read. So inserts into the archived tables take rowids above every
    * one given before (like AUTOINCREMENT, which an existing table cannot
    * be switched to).
    */
  private def claimRowids(db: SQLiteDatabase, table: String, n: Int): Long =
    val first = Using.resource(db.rawQuery(ArchiveSql.nextRowid(table), null))(c => if c.moveToFirst() then c.getLong(0) else 1L)
    db.execSQL(ArchiveSql.takeRowids(table, first + n))
    first

  private def nanToNull(cv: ContentValues, k: String, v: Double): Unit =
    if v.isNaN then cv.putNull(k) else cv.put(k, java.lang.Double.valueOf(v))

  def insertFixes(fs0: Iterable[Fix]): Unit = tx { db =>
    val del = deletedIn(db)
    val fs = fs0.filter(f => Deletion.kept(del, f.time))
    val cv = ContentValues()
    var id = claimRowids(db, "fixes", fs.size)
    for f <- fs do
      cv.clear()
      cv.put("rowid", java.lang.Long.valueOf(id))
      id += 1
      cv.put("time", java.lang.Long.valueOf(f.time))
      cv.put("lat", java.lang.Double.valueOf(f.lat))
      cv.put("lon", java.lang.Double.valueOf(f.lon))
      nanToNull(cv, "acc", f.accuracy)
      nanToNull(cv, "alt", f.altitude)
      nanToNull(cv, "speed", f.speed)
      nanToNull(cv, "bearing", f.bearing)
      cv.put("provider", f.provider)
      cv.put("sats", java.lang.Integer.valueOf(f.satellites))
      nanToNull(cv, "alt_acc", f.altAccuracy)
      nanToNull(cv, "msl", f.msl)
      nanToNull(cv, "msl_acc", f.mslAccuracy)
      db.insertOrThrow("fixes", null, cv)
  }

  def insertMotion(ws0: Iterable[MotionWindow], src: String = null): Unit = tx { db =>
    val del = deletedIn(db)
    val ws = ws0.filter(m => Deletion.kept(del, m.start))
    val cv = ContentValues()
    var id = claimRowids(db, "motion", ws.size)
    for m <- ws do
      cv.clear()
      cv.put("rowid", java.lang.Long.valueOf(id))
      id += 1
      cv.put("start", java.lang.Long.valueOf(m.start))
      cv.put("end", java.lang.Long.valueOf(m.end))
      nanToNull(cv, "std", m.accelStd)
      nanToNull(cv, "mean", m.accelMean)
      cv.put("steps", java.lang.Integer.valueOf(m.steps))
      cv.put("src", src)
      db.insertOrThrow("motion", null, cv)
  }

  def insertBattery(b: BatteryReading, plan: String): Unit =
    val cv = ContentValues()
    cv.put("time", java.lang.Long.valueOf(b.time))
    cv.put("pct", java.lang.Integer.valueOf(b.pct))
    cv.put("charging", java.lang.Integer.valueOf(if b.charging then 1 else 0))
    cv.put("screen_on", java.lang.Integer.valueOf(if b.screenOn then 1 else 0))
    cv.put("mode", b.mode)
    cv.put("plan", plan)
    b.chargeUah.foreach(v => cv.put("charge_uah", java.lang.Long.valueOf(v)))
    b.currentAvgUa.foreach(v => cv.put("current_avg_ua", java.lang.Integer.valueOf(v)))
    b.currentNowUa.foreach(v => cv.put("current_now_ua", java.lang.Integer.valueOf(v)))
    b.voltageMv.foreach(v => cv.put("voltage_mv", java.lang.Integer.valueOf(v)))
    if !b.tempC.isNaN then cv.put("temp_c", java.lang.Double.valueOf(b.tempC.toDouble))
    b.plug.foreach(cv.put("plug", _))
    tx { db =>
      if Deletion.kept(deletedIn(db), b.time) then
        cv.put("rowid", java.lang.Long.valueOf(claimRowids(db, "battery", 1)))
        db.insertOrThrow("battery", null, cv)
    }

  private def energyValues(e: EnergyState): ContentValues =
    val cv = ContentValues()
    def b(x: Boolean) = java.lang.Integer.valueOf(if x then 1 else 0)
    cv.put("gnss", b(e.gnss))
    cv.put("gnss_interval_ms", java.lang.Long.valueOf(e.gnssIntervalMs))
    cv.put("provider", e.provider)
    cv.put("detail", b(e.detail))
    cv.put("still", b(e.still))
    cv.put("screen_on", b(e.screenOn))
    cv.put("charging", b(e.charging))
    cv.put("mode", e.mode)
    cv.put("eff_mode", e.effMode)
    cv.put("motion", e.motion)
    cv.put("env", e.env)
    cv

  /** Applies a record of the logger's state changes ([[EnergyLog]]): in
    * one transaction with the rows and checkpoints it reads. A row that
    * changes is deleted and inserted anew with a new rowid, never updated
    * ([[DbSchema.archivable]]).
    */
  def applyEnergy(r: SmallRecord.Energy): Unit = tx { db =>
    val b = r.boot
    val tail = query(s"SELECT rowid,$energyCols FROM energy_state WHERE boot=? ORDER BY seq DESC, start DESC LIMIT 1", b) { c =>
      rowOf(c, 1).copy(id = c.getLong(0))
    }.headOption
    val cp = query("SELECT boot, seq, confirmed_at FROM energy_checkpoint WHERE boot=?", b)(c =>
      EnergyCheckpoint(c.getLong(0), c.getLong(1), c.getLong(2))).headOption
    val newest = energyMaxBoot() <= b
    val olderOpen =
      if !newest then Nil
      else
        query(s"""SELECT rowid,$energyCols,(SELECT confirmed_at FROM energy_checkpoint k WHERE k.boot=energy_state.boot)
          |FROM energy_state WHERE end IS NULL AND boot<?""".stripMargin, b) { c =>
          (rowOf(c, 1).copy(id = c.getLong(0)), Option.when(!c.isNull(17))(c.getLong(17)))
        }
    val ch = EnergyLog.apply(EnergyLog.Before(tail, cp, newest, olderOpen), r, AshidoriApp.zone, deletedIn(db))
    for id <- ch.remove do db.delete("energy_state", "rowid=?", Array(id.toString))
    insertEnergyRows(db, ch.insert)
    val c = ch.checkpoint
    db.execSQL("INSERT OR REPLACE INTO energy_checkpoint(boot, seq, confirmed_at) VALUES(?, ?, ?)",
      Array[AnyRef](java.lang.Long.valueOf(c.boot), java.lang.Long.valueOf(c.seq), java.lang.Long.valueOf(c.confirmedAt)))
  }

  private def insertEnergyRows(db: SQLiteDatabase, rows: Seq[EnergyRow]): Unit =
    var id = claimRowids(db, "energy_state", rows.size)
    for row <- rows do
      val cv = energyValues(row.state)
      cv.put("rowid", java.lang.Long.valueOf(id))
      id += 1
      cv.put("start", java.lang.Long.valueOf(row.start))
      row.end.foreach(e => cv.put("end", java.lang.Long.valueOf(e)))
      cv.put("end_unknown", java.lang.Integer.valueOf(if row.endUnknown then 1 else 0))
      cv.put("boot", java.lang.Long.valueOf(row.boot))
      cv.put("seq", java.lang.Long.valueOf(row.seq))
      db.insertOrThrow("energy_state", null, cv)

  /** The highest boot of the state recorder stored (0: none). */
  def energyMaxBoot(): Long =
    query("SELECT COALESCE(MAX(boot), 0) FROM energy_checkpoint")(_.getLong(0)).head

  /** Ranges the user deleted ([[deleteRange]]): nothing is written into
    * them again, and the archive's rows in them are not shown. Read again
    * every few seconds (the other process may have added one), and at once
    * after deleteRange in this process.
    */
  def deletedRanges(): Vector[(Long, Long)] =
    val now = android.os.SystemClock.uptimeMillis()
    deletedCache.filter(_._1 > now - 5_000L).map(_._2).getOrElse {
      val d = query("SELECT start, end FROM deleted_range ORDER BY start")(c => (c.getLong(0), c.getLong(1)))
      deletedCache = Some((now, d))
      d
    }
  @volatile private var deletedCache: Option[(Long, Vector[(Long, Long)])] = None

  /** Every deleted range as it is now (no cache): (from, to, deleted at,
    * whether the archive's files may still hold its rows). For the sync's
    * check right before it sends a batch.
    */
  def deletedRangesNow(): Vector[(Long, Long, Long, Boolean)] =
    query("SELECT start, end, deleted_at, archived_at IS NULL FROM deleted_range")(c =>
      (c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0))

  /** Deleted ranges whose rows may still be in archive files: (rowid, from, to). */
  def archiveDeletionsPending(): Vector[(Long, Long, Long)] =
    query("SELECT rowid, start, end FROM deleted_range WHERE archived_at IS NULL ORDER BY start")(c =>
      (c.getLong(0), c.getLong(1), c.getLong(2)))

  /** The archive holds no row of this deleted range any more. */
  def markArchiveDeleted(rowid: Long): Unit =
    w.execSQL("UPDATE deleted_range SET archived_at=? WHERE rowid=?",
      Array[AnyRef](java.lang.Long.valueOf(System.currentTimeMillis()), java.lang.Long.valueOf(rowid)))

  /** The deleted ranges as they are now, read in the write transaction `db`
    * that is about to insert (no cache: a range the other process added a
    * moment ago counts). The write path's decision; [[deletedRanges]] is
    * only for reads.
    */
  private def deletedIn(db: SQLiteDatabase): Vector[(Long, Long)] =
    Using.resource(db.rawQuery("SELECT start, end FROM deleted_range", null)) { c =>
      val b = Vector.newBuilder[(Long, Long)]
      while c.moveToNext() do b += ((c.getLong(0), c.getLong(1)))
      b.result()
    }

  private val energyCols = "start,end,gnss,gnss_interval_ms,provider,detail,still,screen_on,charging,mode,eff_mode,motion,env,end_unknown,boot,seq"
  private def energyOf(c: Cursor, at: Int, end: Long): EnergySpan = rowOf(c, at).span.copy(end = end)
  private def rowOf(c: Cursor, at: Int): EnergyRow =
    def s(i: Int) = Option(c.getString(at + i)).getOrElse("")
    EnergyRow(c.getLong(at), Option.when(!c.isNull(at + 1))(c.getLong(at + 1)),
      EnergyState(c.getInt(at + 2) != 0, c.getLong(at + 3), s(4), c.getInt(at + 5) != 0,
        c.getInt(at + 6) != 0, c.getInt(at + 7) != 0, c.getInt(at + 8) != 0, s(9), s(10), s(11), s(12)),
      !c.isNull(at + 13) && c.getInt(at + 13) != 0, c.getLong(at + 14), c.getLong(at + 15))

  /** When recorder `boot` last confirmed its state, if it did. */
  private def confirmedAt(boot: Long): Option[Long] =
    query("SELECT confirmed_at FROM energy_checkpoint WHERE boot=?", boot)(_.getLong(0)).headOption

  /** Rows not closed yet: normally the current one; more after the process was killed. */
  private def openEnergy(): Vector[(Long, EnergyRow)] =
    query(s"SELECT rowid,$energyCols FROM energy_state WHERE end IS NULL ORDER BY start") { c =>
      (c.getLong(0), rowOf(c, 1))
    }

  /** Readings and state spans since `from` for the energy analysis; the open span ends `now`. */
  def energyData(from: Long, now: Long): (Vector[BatteryReading], Vector[EnergySpan]) =
    AshidoriApp.archive.reading(Archive.Battery, Archive.EnergyStates) {
      val readings = AshidoriApp.archive.read(Archive.Battery, from, Long.MaxValue).map(Archive.batteryOf) ++
        hotRows(Archive.Battery, from, Long.MaxValue).map(r => Archive.batteryOf(r.toIndexedSeq))
      val spans = AshidoriApp.archive.read(Archive.EnergyStates, from, Long.MaxValue).map(Archive.energyOf) ++
        hotRows(Archive.EnergyStates, from, Long.MaxValue).map(r => Archive.energyOf(r.toIndexedSeq)) ++
        openEnergy().map(_._2).filter(_.start < now).map(r =>
          EnergyLog.openSpan(r, EnergyRecorder.live, confirmedAt(r.boot), now))
      // One version of each piece of a state change (EnergyLog.select), not one per start.
      (readings.distinctBy(_.time).sortBy(_.time), EnergyLog.select(spans))
    }

  def insertGnssSession(start: Long, end: Long, file: String, reason: String): Unit = tx { db =>
    // Without the deleted ranges (a session cut short is zero length: kept if outside them).
    val del = deletedIn(db)
    val parts = if end > start then Deletion.cut(del, start, end) else Seq((start, end)).filter(p => Deletion.kept(del, p._1))
    for (s, e) <- parts do
      val cv = ContentValues()
      cv.put("start", java.lang.Long.valueOf(s))
      cv.put("end", java.lang.Long.valueOf(e))
      cv.put("file", file)
      cv.put("reason", reason)
      db.insertOrThrow("gnss_sessions", null, cv)
  }

  private def query[A](sql: String, args: Any*)(row: Cursor => A): Vector[A] =
    Using.resource(r.rawQuery(sql, args.map(_.toString).toArray)) { c =>
      val out = Vector.newBuilder[A]
      while c.moveToNext() do out += row(c)
      out.result()
    }

  private def dbl(c: Cursor, i: Int): Double = if c.isNull(i) then Double.NaN else c.getDouble(i)
  private def flt(c: Cursor, i: Int): Float = if c.isNull(i) then Float.NaN else c.getFloat(i)

  /** The rows of `table` a reader leaves out of SQLite while a commit of it
    * is unfinished and its new file is in place (ArchiveStore.reading, rule
    * C): `AND NOT (...)` and its arguments, or nothing.
    */
  private def notPending(table: String): (String, Seq[Any]) =
    val col = Db.ArchivedTables(table)
    AshidoriApp.archive.hotExclusion(table).fold(("", Seq.empty[Any])) { (f, t, m) =>
      (s" AND NOT ($col>=? AND $col<? AND rowid<=?)", Seq(f, t, m))
    }

  /** Positions in [from, to): archived days from Parquet, recent ones from SQLite. */
  def fixes(from: Long, to: Long): Vector[Fix] = AshidoriApp.archive.reading(Archive.Fixes) {
    once("fixes", AshidoriApp.archive.read(Archive.Fixes, from, to).map(Archive.fixOf) ++ hotFixes(from, to))(f =>
      Archive.fixRow(f).toIndexedSeq).sortBy(_.time)
  }

  /** `xs` with equal rows once, while an unfinished commit of `table` has
    * two copies of some rows read (ArchiveStore.dedupe, rule C).
    */
  private def once[A](table: String, xs: Vector[A])(row: A => IndexedSeq[Any]): Vector[A] =
    if AshidoriApp.archive.dedupe(table) then CommitRepair.distinctRows(xs)(row) else xs

  /** Every position in [from, to) to `out` in time order, a day at a time
    * and a row group or a page of SQLite at a time: memory stays flat
    * however many there are (a week at 1 Hz is 600,000).
    */
  def foreachFix(from: Long, to: Long)(out: Fix => Unit): Unit =
    val zone = AshidoriApp.zone
    var d = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate
    def startOf(x: java.time.LocalDate) = x.atStartOfDay(zone).toInstant.toEpochMilli
    while startOf(d) < to do
      val (a, b) = (math.max(from, startOf(d)), math.min(to, startOf(d.plusDays(1))))
      AshidoriApp.archive.reading(Archive.Fixes) {
        val (archived, close) = AshidoriApp.archive.stream(Archive.Fixes, a, b)
        try
          // Rule C: rows of an unfinished commit whose file is checked are
          // left out of SQLite; equal rows from both are kept once (merge).
          val (x, xa) = notPending("fixes")
          val hot = ArchiveMerge.pages(a, at =>
            query(s"SELECT rowid,$fixCols FROM fixes WHERE time>=? AND time<? AND (time>? OR (time=? AND rowid>?))$x ORDER BY time, rowid LIMIT 5000",
              (Seq[Any](math.max(a, at.time), b, at.time, at.time, at.rowid) ++ xa)*)(c => (c.getLong(0), fixOf(c, 1))),
            (r: (Long, Fix)) => ArchiveMerge.Cursor(r._2.time, r._1)).flatten.map(r => Archive.fixRow(r._2).toIndexedSeq)
          ArchiveMerge.merge(archived, hot, r => out(Archive.fixOf(r)))
        finally close()
      }
      d = d.plusDays(1)

  def hotFixes(from: Long, to: Long): Vector[Fix] =
    val (x, xa) = notPending("fixes")
    query(s"SELECT $fixCols FROM fixes WHERE time>=? AND time<?$x ORDER BY time", (Seq(from, to) ++ xa)*)(fixOf(_))

  def lastFix(): Option[Fix] = AshidoriApp.archive.reading(Archive.Fixes) {
    hotLastFix().orElse(AshidoriApp.archive.lastRow(Archive.Fixes).map(Archive.fixOf))
  }

  private def hotLastFix(): Option[Fix] =
    val (x, xa) = notPending("fixes")
    query(s"SELECT $fixCols FROM fixes WHERE 1$x ORDER BY time DESC LIMIT 1", xa*)(fixOf(_)).headOption

  /** The columns of a fix. The MSL altitude converted after the fix was
    * saved is in `fix_msl` ([[updateFixMsl]]), joined here: derived data,
    * never written into a fix row.
    */
  private val fixCols = "time,lat,lon,acc,alt,speed,bearing,provider,sats,alt_acc," +
    "COALESCE(msl,(SELECT m.msl FROM fix_msl m WHERE m.time=fixes.time LIMIT 1))," +
    "CASE WHEN msl IS NULL THEN (SELECT m.msl_acc FROM fix_msl m WHERE m.time=fixes.time LIMIT 1) ELSE msl_acc END"
  private def fixOf(c: Cursor, at: Int = 0): Fix =
    Fix(c.getLong(at), c.getDouble(at + 1), c.getDouble(at + 2), flt(c, at + 3), dbl(c, at + 4), flt(c, at + 5), flt(c, at + 6),
      c.getString(at + 7), c.getInt(at + 8), flt(c, at + 9), dbl(c, at + 10), flt(c, at + 11))

  def insertPressure(ps0: Iterable[PressureSample]): Unit = tx { db =>
    val del = deletedIn(db)
    val ps = ps0.filter(p => Deletion.kept(del, p.time))
    val st = db.compileStatement("INSERT INTO pressure(rowid, time, hpa) VALUES(?, ?, ?)")
    try
      var id = claimRowids(db, "pressure", ps.size)
      for p <- ps do
        st.bindLong(1, id)
        id += 1
        st.bindLong(2, p.time)
        st.bindDouble(3, p.hPa.toDouble)
        st.executeInsert()
    finally st.close()
  }

  /** Barometric pressure in [from, to), archived and recent, for [[Elevation.estimate]]. */
  def pressure(from: Long, to: Long): Vector[PressureSample] = AshidoriApp.archive.reading(Archive.Pressure) {
    once("pressure", AshidoriApp.archive.read(Archive.Pressure, from, to).map(Archive.pressureOf) ++ hotPressure(from, to))(p =>
      Archive.pressureRow(p).toIndexedSeq).sortBy(_.time)
  }

  private def hotPressure(from: Long, to: Long, maxRowid: Long = Long.MaxValue): Vector[PressureSample] =
    val (x, xa) = notPending("pressure")
    query(s"SELECT time,hpa FROM pressure WHERE time>=? AND time<? AND rowid<=?$x ORDER BY time, rowid", (Seq[Any](from, to, maxRowid) ++ xa)*) { c =>
      PressureSample(c.getLong(0), c.getFloat(1))
    }

  def motion(from: Long, to: Long): Vector[MotionWindow] = AshidoriApp.archive.reading(Archive.Motion) {
    once("motion", AshidoriApp.archive.read(Archive.Motion, from, to).map(Archive.motionOf) ++ hotMotion(from, to))(m =>
      Archive.motionRow(m).toIndexedSeq).sortBy(_.start)
  }

  private def hotMotion(from: Long, to: Long): Vector[MotionWindow] =
    val (x, xa) = notPending("motion")
    query(s"SELECT start,end,std,mean,steps FROM motion WHERE start>=? AND start<?$x ORDER BY start", (Seq(from, to) ++ xa)*) { c =>
      MotionWindow(c.getLong(0), c.getLong(1), flt(c, 2), flt(c, 3), c.getInt(4))
    }

  def steps(from: Long, to: Long): Int = AshidoriApp.archive.reading(Archive.Motion) {
    if AshidoriApp.archive.dedupe("motion") then motion(from, to).map(_.steps).sum
    else
    AshidoriApp.archive.read(Archive.Motion, from, to).map(r => Option(r(4)).map(_.asInstanceOf[Int]).getOrElse(0)).sum +
      { val (x, xa) = notPending("motion")
        query(s"SELECT COALESCE(SUM(steps),0) FROM motion WHERE start>=? AND start<?$x", (Seq(from, to) ++ xa)*)(_.getInt(0)).head }
  }

  def battery(from: Long): Vector[DrainEstimator.Sample] = AshidoriApp.archive.reading(Archive.Battery) {
    once("battery", AshidoriApp.archive.read(Archive.Battery, from, Long.MaxValue).map { r =>
      DrainEstimator.Sample(r(0).asInstanceOf[Long], r(1).asInstanceOf[Int], r(2) == 1, r(3) == 1)
    } ++ hotBattery(from))(b => IndexedSeq(b.time, b.pct, b.charging, b.screenOn))
  }

  private def hotBattery(from: Long): Vector[DrainEstimator.Sample] =
    val (x, xa) = notPending("battery")
    query(s"SELECT time,pct,charging,screen_on FROM battery WHERE time>=?$x ORDER BY time", (Seq(from) ++ xa)*) { c =>
      DrainEstimator.Sample(c.getLong(0), c.getInt(1), c.getInt(2) != 0, c.getInt(3) != 0)
    }

  def counts(): Map[String, Long] =
    Seq("fixes", "motion", "battery", "energy_state", "pressure", "places", "checkins", "gnss_sessions").map { t =>
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

  /** This device's corrections overlapping `[from, to)`, in the order they
    * win ([[ModeOverride.newestFirst]]): `updated_at`, then the change
    * counter (`sync_rev`, the order they are uploaded in), newest first.
    */
  def modeOverrides(from: Long, to: Long): Vector[ModeOverride] =
    query("SELECT start,end,mode,updated_at,COALESCE(sync_rev,rowid) FROM mode_overrides WHERE end>? AND start<? " +
      "ORDER BY updated_at DESC, COALESCE(sync_rev,rowid) DESC", from, to) { c =>
      ModeOverride(c.getLong(0), c.getLong(1), TransportMode.fromKey(c.getString(2)), c.getLong(3), seq = c.getLong(4))
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

  /** The correction stored for exactly this leg, if any (for 取り消し). */
  def modeOverride(start: Long, end: Long): Option[TransportMode] =
    query("SELECT mode FROM mode_overrides WHERE start=? AND end=?", start, end)(c => TransportMode.fromKey(c.getString(0))).headOption

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

  /** Approximate area names ("天王台付近") for places the user has not named. */
  def placeAreas(): Map[Long, String] =
    query("SELECT id, area FROM places WHERE area IS NOT NULL")(c => c.getLong(0) -> c.getString(1)).toMap

  def setPlaceArea(id: Long, area: String): Unit =
    val cv = ContentValues()
    cv.put("area", area)
    w.update("places", cv, "id=?", Array(id.toString))

  /** One fix per time bucket, for drawing long periods cheaply. */
  def fixesSampled(from: Long, to: Long, bucketMs: Long): Vector[Fix] = AshidoriApp.archive.reading(Archive.Fixes) {
    val archived = AshidoriApp.archive.read(Archive.Fixes, from, to).map(Archive.fixOf)
      .filter(f => !f.hasAccuracy || f.accuracy < 100)
      .groupBy(_.time / bucketMs).values.map(_.minBy(_.time)).toVector
    val all = (archived ++ hotFixesSampled(from, to, bucketMs)).sortBy(_.time)
    if AshidoriApp.archive.dedupe("fixes") then all.groupBy(_.time / bucketMs).values.map(_.minBy(_.time)).toVector.sortBy(_.time) else all
  }

  private def hotFixesSampled(from: Long, to: Long, bucketMs: Long): Vector[Fix] =
    val (x, xa) = notPending("fixes")
    query(
      s"SELECT MIN(time), lat, lon FROM fixes WHERE time>=? AND time<? AND (acc IS NULL OR acc<100)$x GROUP BY time/$bucketMs ORDER BY 1",
      (Seq(from, to) ++ xa)*
    )(c => Fix(c.getLong(0), c.getDouble(1), c.getDouble(2)))

  def firstFixTime(): Option[Long] =
    query("SELECT MIN(time) FROM fixes")(c => if c.isNull(0) then None else Some(c.getLong(0))).head

  @volatile private var firstDay: Option[java.time.LocalDate] = None
  /** [[firstDataDate]], remembered once known (it only moves on import). */
  def firstDataDateCached: Option[java.time.LocalDate] =
    if firstDay.isEmpty then firstDay = firstDataDate()
    firstDay

  /** First day with any position, archived or not: the archive's files and
    * SQLite read under the positions' lock as the other readers do (rule
    * C), so no commit moves the oldest rows from one to the other between
    * the two reads.
    */
  def firstDataDate(): Option[java.time.LocalDate] = AshidoriApp.archive.reading(Archive.Fixes) {
    val archived = AshidoriApp.archive.archivedFiles(Archive.Fixes).headOption.map { f =>
      val n = f.getName.stripSuffix(".parquet")
      if n.length == 7 then java.time.YearMonth.parse(n).atDay(1) else java.time.LocalDate.parse(n)
    }
    val hot = firstFixTime().map(t => java.time.Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).toLocalDate)
    (archived.toSeq ++ hot).minOption
  }

  /** Days (epoch-day numbers in the given offset) that have any fix, for calendar dots. */
  def daysWithData(from: Long, to: Long, offsetMs: Long): Set[Long] =
    query(s"SELECT DISTINCT (time+$offsetMs)/86400000 FROM fixes WHERE time>=? AND time<?", from, to)(_.getLong(0)).toSet

  // --- GNSS status history ------------------------------------------------------

  def insertGnssStatus(s: GnssSnapshot, env: Environment, acc: Float): Unit =
    insertGnssStatusRow(s.time, s.visible, s.used, s.top4Cn0, s.l5, env.toString, acc)

  def insertGnssStatusRow(time: Long, visible: Int, used: Int, top4: Double, l5: Int, env: String, acc: Float): Unit =
    val cv = ContentValues()
    cv.put("time", java.lang.Long.valueOf(time))
    cv.put("visible", java.lang.Integer.valueOf(visible))
    cv.put("used", java.lang.Integer.valueOf(used))
    cv.put("top4", java.lang.Double.valueOf(top4))
    cv.put("l5", java.lang.Integer.valueOf(l5))
    cv.put("env", env)
    nanToNull(cv, "acc", acc)
    tx { db =>
      if Deletion.kept(deletedIn(db), time) then
        cv.put("rowid", java.lang.Long.valueOf(claimRowids(db, "gnss_status", 1)))
        db.insertOrThrow("gnss_status", null, cv)
    }

  def gnssStatus(from: Long): Vector[(GnssSnapshot, Environment, Float)] = AshidoriApp.archive.reading(Archive.GnssStatus) {
    once("gnss_status", AshidoriApp.archive.read(Archive.GnssStatus, from, Long.MaxValue).map { r =>
      def i(k: Int) = Option(r(k)).map(_.asInstanceOf[Int]).getOrElse(0)
      (GnssSnapshot(r(0).asInstanceOf[Long], i(1), i(2), Option(r(3)).map(_.asInstanceOf[Double]).getOrElse(0.0), i(4)),
        scala.util.Try(Environment.valueOf(r(5).toString)).getOrElse(Environment.Unknown),
        Option(r(6)).map(_.asInstanceOf[Float]).getOrElse(Float.NaN))
    } ++ hotGnssStatus(from))((g, e, a) => IndexedSeq(g.time, g.visible, g.used, g.top4Cn0, g.l5, e.toString, a))
  }

  private def hotGnssStatus(from: Long): Vector[(GnssSnapshot, Environment, Float)] =
    val (x, xa) = notPending("gnss_status")
    query(s"SELECT time,visible,used,top4,l5,env,acc FROM gnss_status WHERE time>=?$x ORDER BY time", (Seq(from) ++ xa)*) { c =>
      (GnssSnapshot(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4)),
        scala.util.Try(Environment.valueOf(c.getString(5))).getOrElse(Environment.Unknown), flt(c, 6))
    }

  // --- raw lines and archiving ----------------------------------------------------

  def insertRawLines(lines: Iterable[(Long, String)]): Unit = tx(insertRaw(_, lines))

  private def insertRaw(db: SQLiteDatabase, lines0: Iterable[(Long, String)]): Unit =
    val del = deletedIn(db)
    val lines = if del.isEmpty then lines0 else lines0.filter(l => Deletion.kept(del, l._1))
    val st = db.compileStatement("INSERT INTO raw_lines(rowid, time, line) VALUES(?, ?, ?)")
    try
      var id = claimRowids(db, "raw_lines", lines.size)
      for (t, l) <- lines do
        st.bindLong(1, id)
        st.bindLong(2, t)
        st.bindString(3, l)
        if st.executeInsert() < 0 then throw android.database.SQLException(s"raw line not inserted (rowid $id)")
        id += 1
    finally st.close()

  /** Pre-Parquet text log import: lines of `file` (its name, size and
    * modification time) already in raw_lines.
    */
  def legacyImported(file: String): Long =
    query("SELECT lines FROM legacy_import WHERE file=?", file)(_.getLong(0)).headOption.getOrElse(0L)

  /** Inserts a chunk of an imported text log and records how far the
    * import got, in one transaction: an import cut short resumes after the
    * last chunk, never inserting a line twice.
    */
  def insertLegacyChunk(lines: Iterable[(Long, String)], file: String, done: Long): Unit = tx { db =>
    insertRaw(db, lines)
    db.execSQL("INSERT OR REPLACE INTO legacy_import(file, lines) VALUES(?, ?)", Array[AnyRef](file, java.lang.Long.valueOf(done)))
  }

  def legacyDone(file: String): Unit = w.delete("legacy_import", "file=?", Array(file))

  /** Runs `f` (inserts of a spilled batch) and marks `key` imported, in one
    * transaction ([[SmallOverflow]]).
    */
  def importMarked(key: String)(f: => Unit): Unit = tx { db =>
    f
    db.execSQL("INSERT OR REPLACE INTO legacy_import(file, lines) VALUES(?, 1)", Array[AnyRef](key))
  }

  /** MSL altitudes computed after their fixes were saved ([[MslAltitude]]):
    * derived data, appended to `fix_msl` and joined to the fixes while they
    * are in SQLite (also when a day is archived). A fix row is never
    * written to, so archiving, which may have read the fix already, is not
    * affected: an altitude that comes after its day was read is simply not
    * in the archive (null there, shown as 「—」).
    *
    * An altitude of a fix whose day ended [[Db.MslLateMs]] ago or more is
    * not added (a batch that waited in an overflow file): archiving reads a
    * day only once it has been over [[ArchiveStore.SettleMs]], so what it
    * reads of the join (also when a repair checks the day's rows against
    * the archive) no longer changes.
    */
  def updateFixMsl(vs: Seq[(Long, Double, Float)]): Unit = tx { db =>
    val st = db.compileStatement("INSERT INTO fix_msl(time, msl, msl_acc) VALUES(?, ?, ?)")
    val zone = AshidoriApp.zone
    val now = System.currentTimeMillis()
    def dayOver(t: Long) =
      java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
    try
      val del = deletedIn(db)
      for (t, m, a) <- vs if !m.isNaN && Deletion.kept(del, t) && now < dayOver(t) + Db.MslLateMs do
        st.bindLong(1, t)
        st.bindDouble(2, m)
        if a.isNaN then st.bindNull(3) else st.bindDouble(3, a.toDouble)
        st.executeInsert()
    finally st.close()
  }

  /** Raw lines of [from, to) in insertion order (the export of days not
    * archived yet).
    */
  def rawLines(from: Long, to: Long): Vector[String] =
    query("SELECT line FROM raw_lines WHERE time>=? AND time<? ORDER BY rowid", from, to)(_.getString(0))

  /** Largest rowid of each archived table: a compaction reads and deletes
    * only rows up to these, so rows the logger inserts meanwhile (also late
    * ones of the day being archived) are left for the next run.
    */
  def maxRowids(): Map[String, Long] = tx { db =>
    // The floor of new rowids is raised above them in the same transaction
    // (see ArchiveSql).
    Db.ArchivedTables.keys.map { t =>
      db.execSQL(ArchiveSql.raiseFloor(t))
      t -> Using.resource(db.rawQuery(ArchiveSql.maxRowid(t), null))(c => if c.moveToFirst() then c.getLong(0) else 0L)
    }.toMap
  }

  /** One chunk of raw lines of [from, to) with rowid <= `maxRowid`, in
    * (time, rowid) order after `after`: (rowid, time, line).
    */
  def rawLinesAfter(from: Long, to: Long, maxRowid: Long, after: ArchiveMerge.Cursor, limit: Int): Vector[(Long, Long, String)] =
    val (sql, args) = ArchiveSql.rawAfter(from, to, maxRowid, after, limit)
    query(sql, args*)(c => (c.getLong(0), c.getLong(1), c.getString(2)))

  /** Raw lines by rowid (the late ones a portion kept): (rowid, time, line). */
  def rawLinesById(rowids: Seq[Long]): Vector[(Long, Long, String)] =
    rowids.grouped(500).flatMap { ids =>
      query(s"SELECT rowid, time, line FROM raw_lines WHERE rowid IN (${ids.mkString(",")})")(c => (c.getLong(0), c.getLong(1), c.getString(2)))
    }.toVector

  /** One chunk of the raw lines of a compaction portion (see
    * [[countRawPortion]]) after `cursor`: (rowid, time, line).
    */
  def rawPortionLines(from: Long, to: Long, maxRowid: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor,
                      cursor: ArchiveMerge.Cursor, limit: Int): Vector[(Long, Long, String)] =
    val (sql, args) = ArchiveSql.rawPortionAfter(from, to, maxRowid, after, upTo, cursor, limit)
    query(sql, args*)(c => (c.getLong(0), c.getLong(1), c.getString(2)))

  /** The raw lines a compaction portion read: after `after` (where it
    * started) up to `upTo`, of [from, to) and rowid <= `maxRowid`.
    */
  def countRawPortion(from: Long, to: Long, maxRowid: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor): Long =
    val (where, args) = ArchiveSql.rawPortion(from, to, maxRowid, after, upTo)
    query(s"SELECT COUNT(*) FROM raw_lines WHERE $where", args*)(_.getLong(0)).head

  /** Deletes the raw lines a compaction portion archived (see
    * [[countRawPortion]]) but `except` (lines it did not archive), in small
    * transactions so the logger is never kept waiting for the write lock.
    */
  def deleteRawPortion(from: Long, to: Long, maxRowid: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor,
                       except: Seq[Long] = Nil): Long =
    val (where, args) = ArchiveSql.rawPortion(from, to, maxRowid, after, upTo)
    deleteChunked("raw_lines", ArchiveSql.except(where, except), args)

  /** Deletes raw lines by rowid (late lines archived in a sorted pass). */
  def deleteRawById(rowids: Seq[Long]): Long =
    rowids.grouped(500).map(ids => tx(_.delete("raw_lines", s"rowid IN (${ids.mkString(",")})", null).toLong)).sum

  /** Whether any archived table still holds rows of [from, to) in SQLite. */
  def hasHotRows(from: Long, to: Long): Boolean =
    Db.ArchivedTables.exists { (t, col) =>
      query(s"SELECT EXISTS(SELECT 1 FROM $t WHERE $col>=? AND $col<? AND ${DbSchema.archivable(t)})", from, to)(_.getInt(0)).head == 1
    }

  /** Rows of an archived table in [from, to) up to `maxRowid`. */
  def countArchived(table: String, from: Long, to: Long, maxRowid: Long): Long =
    val col = Db.ArchivedTables(table)
    query(s"SELECT COUNT(*) FROM $table WHERE $col>=? AND $col<? AND rowid<=? AND ${DbSchema.archivable(table)}", from, to, maxRowid)(_.getLong(0)).head

  /** Deletes rows of an archived table in [from, to) up to `maxRowid`. */
  def deleteArchived(table: String, from: Long, to: Long, maxRowid: Long): Long =
    val col = Db.ArchivedTables(table)
    val n = deleteChunked(table, s"$col>=? AND $col<? AND rowid<=? AND ${DbSchema.archivable(table)}", Seq(from, to, maxRowid))
    // The derived MSL altitudes of the archived fixes go with them, once
    // none of the day's fixes is left in SQLite (an altitude that came
    // after the archive read the fixes is dropped: it can be derived again).
    if table == "fixes" && countArchived("fixes", from, to, Long.MaxValue) == 0 then
      deleteChunked("fix_msl", "time>=? AND time<?", Seq(from, to))
    n

  private def deleteChunked(table: String, where: String, args: Seq[Long]): Long =
    val st = w.compileStatement(ArchiveSql.deleteChunk(table, where, Db.DeleteChunk))
    try
      args.zipWithIndex.foreach((v, i) => st.bindLong(i + 1, v))
      var total = 0L
      var n = Db.DeleteChunk
      while n == Db.DeleteChunk do
        n = st.executeUpdateDelete()
        total += n
      total
    finally st.close()

  /** Earliest time still held in SQLite for an archived table (None if empty). */
  def oldestHot(): Option[Long] =
    Db.ArchivedTables.toSeq.flatMap((t, col) =>
      query(s"SELECT MIN($col) FROM $t WHERE ${DbSchema.archivable(t)}")(c => if c.isNull(0) then None else Some(c.getLong(0))).head)
      .minOption

  /** Rows of one archived table in [from, to) up to `maxRowid`, typed as in
    * [[Archive]], in time order. Small tables (a day of 1 Hz fixes is 86,400
    * rows): read whole.
    */
  def hotRows(t: Archive.TableDef, from: Long, to: Long, maxRowid: Long = Long.MaxValue): Vector[Seq[Any]] =
    if !Db.ArchivedTables.contains(t.name) then return Vector.empty
    val (x, xa) = notPending(t.name)
    val args = Seq[Any](from, to, maxRowid) ++ xa
    t match
      case Archive.Fixes =>
        query(s"SELECT $fixCols FROM fixes WHERE time>=? AND time<? AND rowid<=?$x ORDER BY time, rowid", args*) { c =>
          Archive.fixRow(fixOf(c))
        }
      case Archive.Motion =>
        query(s"SELECT start,end,std,mean,steps FROM motion WHERE start>=? AND start<? AND rowid<=?$x ORDER BY start, rowid",
          args*) { c =>
          Archive.motionRow(MotionWindow(c.getLong(0), c.getLong(1), flt(c, 2), flt(c, 3), c.getInt(4)))
        }
      case Archive.Battery =>
        def opt(c: Cursor, i: Int, v: => Any): Any = if c.isNull(i) then null else v
        query(s"""SELECT time,pct,charging,screen_on,mode,charge_uah,current_avg_ua,current_now_ua,voltage_mv,temp_c,plug
          |FROM battery WHERE time>=? AND time<? AND rowid<=?$x ORDER BY time, rowid""".stripMargin, args*) { c =>
          Seq(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getString(4), opt(c, 5, c.getLong(5)),
            opt(c, 6, c.getInt(6)), opt(c, 7, c.getInt(7)), opt(c, 8, c.getInt(8)), flt(c, 9), c.getString(10))
        }
      // Closed spans only: the open one is archived once it is closed.
      case Archive.EnergyStates =>
        query(s"SELECT $energyCols FROM energy_state WHERE ${DbSchema.archivable("energy_state")} AND start>=? AND start<? AND rowid<=?$x ORDER BY start, rowid",
          args*) { c =>
          Archive.energyRow(energyOf(c, 0, c.getLong(1)))
        }
      case Archive.GnssStatus =>
        query(s"SELECT time,visible,used,top4,l5,env,acc FROM gnss_status WHERE time>=? AND time<? AND rowid<=?$x ORDER BY time, rowid",
          args*) { c =>
          Seq(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4), c.getString(5), flt(c, 6))
        }
      case Archive.Pressure => hotPressure(from, to, maxRowid).map(Archive.pressureRow)
      case _ => Vector.empty

  /** When the newest fix in SQLite was taken (the logger's last persisted
    * position), if any.
    */
  def lastPersistedFixTime(): Option[Long] =
    query("SELECT MAX(time) FROM fixes")(c => if c.isNull(0) then None else Some(c.getLong(0))).head

  /** A consistent copy of the whole database in `dest` (a backup): VACUUM
    * INTO on a read-only connection of its own, which reads one snapshot
    * (WAL: the logger keeps writing meanwhile, nothing waits for it).
    */
  def snapshotTo(dest: java.io.File): Unit =
    dest.delete()
    val ro = SQLiteDatabase.openDatabase(getReadableDatabase.getPath, null, SQLiteDatabase.OPEN_READONLY, Db.KeepOnCorruption)
    try ro.execSQL("VACUUM INTO ?", Array[AnyRef](dest.getPath))
    finally ro.close()

  // --- sync ---------------------------------------------------------------------

  def syncValue(name: String): Long =
    query("SELECT value FROM sync_state WHERE name=?", name)(_.getLong(0)).headOption.getOrElse(0L)

  def setSyncValue(name: String, v: Long): Unit =
    w.execSQL("INSERT OR REPLACE INTO sync_state(name, value) VALUES(?, ?)", Array[AnyRef](name, java.lang.Long.valueOf(v)))

  /** The seq the next batch of `stream` of registration `reg` takes. */
  def nextSeq(reg: String, stream: String): Long = syncValue(s"$reg|seq:$stream")

  /** Stores one batch sealed for slot `seq`, claims that seq and writes the
    * source progress in one small transaction: a crash leaves all of it or
    * none. The sealing happens before, outside the transaction. Refuses
    * (throws) if the stream's next seq is no longer `seq`.
    * `remove` are progress rows that end with this batch.
    */
  def enqueueSealed(reg: String, deviceId: String, cls: String, stream: String, seq: Long, blob: Array[Byte],
                    progress: Seq[(String, Long)], remove: Seq[String] = Nil, file: Option[String] = None): Unit = tx { db =>
    val key = s"$reg|seq:$stream"
    if syncValue(key) != seq then throw IllegalStateException(s"seq of $stream moved from $seq: not enqueued")
    val cv = ContentValues()
    cv.put("reg", reg)
    cv.put("device_id", deviceId)
    cv.put("stream", stream)
    cv.put("seq", java.lang.Long.valueOf(seq))
    cv.put("class", cls)
    cv.put("blob", blob)
    cv.put("created_at", java.lang.Long.valueOf(System.currentTimeMillis()))
    file.foreach(cv.put("file", _))
    db.insertOrThrow("sync_outbox", null, cv)
    setSyncValue(key, seq + 1)
    progress.foreach(setSyncValue)
    remove.foreach(n => db.delete("sync_state", "name=?", Array(n)))
  }

  /** Writes and removes progress rows in one transaction. */
  def updateSync(progress: Seq[(String, Long)], remove: Seq[String]): Unit = tx { db =>
    progress.foreach(setSyncValue)
    remove.foreach(n => db.delete("sync_state", "name=?", Array(n)))
  }

  /** Bytes of sealed batches of class `cls` waiting in the outbox of registration `reg`. */
  def outboxBytes(reg: String, cls: String): Long =
    query("SELECT COALESCE(SUM(length(blob)), 0) FROM sync_outbox WHERE reg=? AND class=?", reg, cls)(_.getLong(0)).head


  /** Outbox rows of a registration and constraint classes, oldest first (without the blob). */
  def outbox(reg: String, classes: Set[String]): Vector[Db.OutboxEntry] =
    query(s"SELECT id, device_id, stream, seq, class, file, created_at FROM sync_outbox WHERE reg=? AND class IN (${classes.toSeq.map(_ => "?").mkString(",")}) ORDER BY id",
      (reg +: classes.toSeq)*) { c =>
      Db.OutboxEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), Option(c.getString(5)), c.getLong(6))
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

  /** Discards the state of versions before registration-keyed sync
    * (unprefixed `seq:`, `up:`, `updated:` rows): it may belong to an earlier
    * registration, so no mark of it is trusted and the data is sent again.
    * Only seq numbers carry over, as a floor for `reg`'s counters: each
    * stream continues above the highest legacy counter and the highest
    * legacy pending seq (`pendingFloors`: stream → that seq + 1), so no slot
    * an older version may have filled is reused.
    */
  def discardLegacySync(reg: String, pendingFloors: Seq[(String, Long)]): Unit = tx { db =>
    val legacy = query("SELECT name, value FROM sync_state WHERE instr(name, '|')=0")(c => c.getString(0) -> c.getLong(1))
    val floors = legacy.collect { case (n, v) if n.startsWith("seq:") => n.stripPrefix("seq:") -> v } ++ pendingFloors
    for (stream, floor) <- floors do
      val key = s"$reg|seq:$stream"
      if syncValue(key) < floor then setSyncValue(key, floor)
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

  // --- venue cache ------------------------------------------------------------

  /** Every stored venue search with its venues. */
  def venueCache(): Vector[VenueCache.Entry] =
    val venues = query("SELECT cell_y,cell_x,radius,id,name,category,lat,lon FROM venue_cache_items") { c =>
      VenueCache.Key(c.getInt(0), c.getInt(1), c.getInt(2)) -> Venue(c.getString(3), c.getString(4), c.getString(5), c.getDouble(6), c.getDouble(7))
    }.groupMap(_._1)(_._2)
    query("SELECT cell_y,cell_x,radius,lat,lon,fetched_at,used_at FROM venue_cache") { c =>
      val k = VenueCache.Key(c.getInt(0), c.getInt(1), c.getInt(2))
      VenueCache.Entry(k, c.getDouble(3), c.getDouble(4), c.getLong(5), c.getLong(6), venues.getOrElse(k, Vector.empty))
    }

  /** Writes a change of the venue cache in one transaction. */
  def applyVenueCache(ch: VenueCache.Change): Unit = if !ch.isEmpty then tx { db =>
    def args(k: VenueCache.Key) = Array(k.y.toString, k.x.toString, k.radiusM.toString)
    val where = "cell_y=? AND cell_x=? AND radius=?"
    for k <- ch.delete do db.delete("venue_cache", where, args(k))
    val cv = ContentValues()
    for e <- ch.put do
      cv.clear()
      cv.put("cell_y", Integer.valueOf(e.key.y))
      cv.put("cell_x", Integer.valueOf(e.key.x))
      cv.put("radius", Integer.valueOf(e.key.radiusM))
      cv.put("lat", java.lang.Double.valueOf(e.lat))
      cv.put("lon", java.lang.Double.valueOf(e.lon))
      cv.put("fetched_at", java.lang.Long.valueOf(e.fetchedAt))
      cv.put("used_at", java.lang.Long.valueOf(e.usedAt))
      val exists = Using.resource(db.rawQuery(s"SELECT 1 FROM venue_cache WHERE $where", args(e.key)))(_.moveToFirst())
      if exists then db.update("venue_cache", cv, where, args(e.key))
      else db.insert("venue_cache", null, cv)
      db.delete("venue_cache_items", where, args(e.key))
      for v <- e.venues do
        cv.clear()
        cv.put("cell_y", Integer.valueOf(e.key.y))
        cv.put("cell_x", Integer.valueOf(e.key.x))
        cv.put("radius", Integer.valueOf(e.key.radiusM))
        cv.put("id", v.id)
        cv.put("name", v.name)
        cv.put("category", v.category)
        cv.put("lat", java.lang.Double.valueOf(v.lat))
        cv.put("lon", java.lang.Double.valueOf(v.lon))
        db.insert("venue_cache_items", null, cv)
  }

  /** Records only when entries were last used (no venue rows rewritten). */
  def touchVenueCache(entries: Seq[VenueCache.Entry]): Unit = if entries.nonEmpty then tx { db =>
    for e <- entries do
      db.execSQL("UPDATE venue_cache SET used_at=? WHERE cell_y=? AND cell_x=? AND radius=?",
        Array[AnyRef](java.lang.Long.valueOf(e.usedAt), Integer.valueOf(e.key.y), Integer.valueOf(e.key.x), Integer.valueOf(e.key.radiusM)))
  }

  def markGnssUploaded(rowid: Long): Unit =
    w.execSQL("UPDATE gnss_sessions SET uploaded=1 WHERE rowid=?", Array[AnyRef](java.lang.Long.valueOf(rowid)))

  /** Removes data inserted by the simulator (debug tool). */
  def deleteSimulated(): Int = tx { db =>
    db.delete("fixes", "provider='sim'", null) + db.delete("motion", "src='sim'", null)
  }

  /** Deletes all raw data in a range (used by "delete this day"). */
  /** Deletes every record of [from, to) from SQLite and keeps the range,
    * so nothing is written into it again: positions, motion, battery, GNSS
    * status, pressure and raw lines by their time; state spans and raw-data
    * sessions cut at the range's edges (a span crossing it keeps its parts
    * outside). Batches still queued, in the logger's buffers or in overflow
    * files are dropped when they are written ([[kept]]). Run it on the
    * write thread ([[LoggerWrites.deleteRange]]), after what was queued.
    *
    * The archive's day and month files are cut afterwards, in the
    * maintenance process ([[ArchiveStore.deleteRange]]); until then their
    * rows in the range are not shown ([[ArchiveStore.read]]).
    */
  def deleteRange(from: Long, to: Long): Unit =
    tx { db =>
      val a = Array(from.toString, to.toString)
      db.execSQL("INSERT INTO deleted_range(start, end, deleted_at) VALUES(?, ?, ?)",
        Array[AnyRef](java.lang.Long.valueOf(from), java.lang.Long.valueOf(to), java.lang.Long.valueOf(System.currentTimeMillis())))
      for t <- Seq("fixes", "battery", "gnss_status", "pressure", "raw_lines") do db.delete(t, "time>=? AND time<?", a)
      db.delete("motion", "start>=? AND start<?", a)
      // Spans: crossing ones replaced by their parts outside (new rowids, as
      // every change of energy_state).
      val crossing = query(s"SELECT rowid,$energyCols FROM energy_state WHERE start<? AND (end IS NULL OR end>?)", to, from) { c =>
        (c.getLong(0), rowOf(c, 1))
      }
      val parts = EnergyLog.clip(crossing.map(_._2), Seq((from, to)), System.currentTimeMillis())
      for (rowid, _) <- crossing do db.delete("energy_state", "rowid=?", Array(rowid.toString))
      insertEnergyRows(db, parts)
      db.delete("fix_msl", "time>=? AND time<?", a)
      // Raw-data sessions (not archived: updated in place).
      val sessions = query("SELECT rowid, start, end, file, reason FROM gnss_sessions WHERE start<? AND end>?", to, from) { c =>
        (c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4))
      }
      for (rowid, s, e, file, reason) <- sessions do
        db.delete("gnss_sessions", "rowid=?", Array(rowid.toString))
        for (ps, pe) <- Deletion.cut(Seq((from, to)), s, e) do
          val cv = ContentValues()
          cv.put("start", java.lang.Long.valueOf(ps))
          cv.put("end", java.lang.Long.valueOf(pe))
          cv.put("file", file)
          cv.put("reason", reason)
          db.insertOrThrow("gnss_sessions", null, cv)
      // The sync: batches sealed from archive files of the range are not
      // sent, and those files go out again from their first part once cut
      // (in the same transaction, so a run never sends one in between).
      val touched = (f: String) => AshidoriApp.archive.periodOf(f.split('/').last).exists((x, y) => x < to && y > from)
      val queued = query("SELECT id, file FROM sync_outbox WHERE file IS NOT NULL")(c => (c.getLong(0), c.getString(1)))
      for (id, f) <- queued if touched(f) do db.delete("sync_outbox", "id=?", Array(id.toString))
      for n <- query("SELECT name FROM sync_state WHERE name LIKE '%|up%:%'")(_.getString(0))
          if Db.uploadMarkFile(n).exists(touched) do db.delete("sync_state", "name=?", Array(n))
    }
    deletedCache = None

  /** Forgets that archive file `file` (`<table>/<name>`) was handed over or
    * partly enqueued, for every registration: it is sent again from part 0.
    */
  def forgetUpload(file: String): Unit = tx { db =>
    for n <- query("SELECT name FROM sync_state WHERE name LIKE '%|up%:%'")(_.getString(0)) if Db.uploadMarkFile(n).contains(file) do
      db.delete("sync_state", "name=?", Array(n))
  }

object Db:
  val Version = 8

  /** The archive file of an upload mark (`<reg>|up:<file>`, `upgen:`,
    * `uppart:`, `upbytes:`), if `name` is one.
    */
  def uploadMarkFile(name: String): Option[String] = ashidori.core.sync.ArchiveUpload.markFile(name)

  /** How long after its day ended a fix's MSL altitude is still added (half
    * of [[ArchiveStore.SettleMs]]).
    */
  val MslLateMs: Long = 30 * 60_000L

  /** The schema version of the database on disk, without opening it for
    * this app (None if there is none or it cannot be read).
    */
  def versionOnDisk(ctx: Context, name: String = "ashidori.db"): Option[Int] =
    val f = ctx.getDatabasePath(name)
    if !f.exists then None
    else
      scala.util.Try {
        // Never Android's default handler, which deletes a database it finds
        // corrupt (also one opened read-only): the same one as every open.
        val db = SQLiteDatabase.openDatabase(f.getPath, null, SQLiteDatabase.OPEN_READONLY, Db.KeepOnCorruption)
        try db.getVersion
        finally db.close()
      }.toOption

  /** The message shown when the database is of a newer app version. */
  val NewerMessage = "記録は新しい版の ashidori で作成されているため、この版では開けません。記録は端末内に残っています。新しい版に更新してください。"


  /** On corruption, keep the files (and say so): Android's default handler
    * deletes the database, which would lose everything recorded.
    */
  object KeepOnCorruption extends android.database.DatabaseErrorHandler:
    /** Corruptions reported to this handler in this process (a test checks it is the one in effect). */
    @volatile var reported = 0
    override def onCorruption(db: SQLiteDatabase): Unit =
      reported += 1
      Log.e(s"database corruption reported: ${db.getPath}; files kept")
      LifeLog.add(LifeKind.Note, "データベースの破損を検出（ファイルは削除せずに保持）")

  /** Tables archived to Parquet, and their time column. */
  val ArchivedTables: Map[String, String] = scala.collection.immutable.ListMap(
    "fixes" -> "time", "motion" -> "start", "battery" -> "time", "gnss_status" -> "time", "energy_state" -> "start",
    "pressure" -> "time", "raw_lines" -> "time")

  /** Rows per DELETE when archiving: short write transactions. */
  val DeleteChunk = 5000

  /** A sealed batch waiting in the outbox. `cls` is the constraint class:
    * "small" (any allowed network) or "raw" (unmetered only).
    */
  /** `file`: the archive file a batch is a part of (`<table>/<name>`), if
    * it is one (and was sealed by this version); `createdAt` when sealed.
    */
  final case class OutboxEntry(id: Long, deviceId: String, stream: String, seq: Long, cls: String,
                               file: Option[String] = None, createdAt: Long = 0L)

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
