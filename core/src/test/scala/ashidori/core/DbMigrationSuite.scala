package ashidori.core

import java.io.File
import scala.sys.process.*
import ashidori.core.ArchiveSql.*

/** The database migrations 6 → 7 → 8 against a real SQLite (the sqlite3
  * shell; skipped where it is not installed), from a database as app 0.21.0
  * (version 6) creates and fills it.
  */
class DbMigrationSuite extends munit.FunSuite:
  private lazy val shell = scala.util.Try("sqlite3 -version".!!).isSuccess

  /** Runs `script` against the database file `db`; its output lines. */
  private def run(db: File, script: String): Vector[String] =
    assume(shell, "sqlite3 not installed")
    val out = StringBuilder()
    val err = StringBuilder()
    val io = BasicIO.standard(in => { in.write(script.getBytes("UTF-8")); in.close() })
      .withOutput(o => out.append(String(o.readAllBytes(), "UTF-8")))
      .withError(e => err.append(String(e.readAllBytes(), "UTF-8")))
    val code = Process(Seq("sqlite3", "-bail", db.getPath)).run(io).exitValue()
    assertEquals(code, 0, s"$err\n$script")
    out.toString.linesIterator.toVector

  private def tempDb(): File =
    val f = File.createTempFile("ashidori", ".db")
    f.delete()
    f.deleteOnExit()
    f

  private def columns(db: File, table: String): Set[String] =
    run(db, s"SELECT name FROM pragma_table_info('$table');").toSet

  private def tableInfo(db: File, table: String): Vector[String] =
    run(db, s"SELECT cid, name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table');")

  /** As app 0.21.0 creates the tables these migrations touch (DbSchema holds
    * the statements of Db.onCreate unchanged) and its rowid floor.
    */
  private val v6 =
    s"""${DbSchema.CreateFixes};
       |CREATE INDEX fixes_time ON fixes(time);
       |${DbSchema.CreateBattery};
       |CREATE INDEX battery_time ON battery(time);
       |$CreateFloor;
       |""".stripMargin

  /** Inserts as 0.21.0's Db does: rowids from the floor. */
  private def insert(table: String, cols: String, values: String): String =
    s"""INSERT INTO $table(rowid, $cols) VALUES((${nextRowid(table)}), $values);
       |UPDATE rowid_floor SET next = MAX(next, (SELECT MAX(rowid) FROM $table) + 1) WHERE tbl='$table';
       |${takeRowids(table, 0)};
       |""".stripMargin

  private def migrate(db: File): Unit =
    // As SQLiteOpenHelper runs onUpgrade(6, 8): one transaction.
    def tx(sql: Seq[String]) = run(db, ("BEGIN" +: sql :+ "COMMIT").map(_ + ";").mkString("\n"))
    tx(DbSchema.toV7(columns(db, "battery"), columns(db, "energy_state")))
    tx(DbSchema.toV8(columns(db, "fixes")))

  private def create(db: File): Unit =
    run(db, v6)
    run(db, DbSchema.toV7(columns(db, "battery"), columns(db, "energy_state")).map(_ + ";").mkString("\n"))
    run(db, DbSchema.toV8(columns(db, "fixes")).map(_ + ";").mkString("\n"))

  /** Runs `script`; the shell's exit code (with -bail: not 0 after an error). */
  private def runCode(db: File, script: String): Int =
    assume(shell, "sqlite3 not installed")
    val io = BasicIO.standard(in => { in.write(script.getBytes("UTF-8")); in.close() })
      .withOutput(_.readAllBytes()).withError(_.readAllBytes())
    Process(Seq("sqlite3", "-bail", db.getPath)).run(io).exitValue()

  /** The upgrade as SQLiteOpenHelper runs it (getDatabaseLocked): on a WAL
    * connection (Db.onConfigure), one transaction holding onUpgrade(6, 8)
    * and setVersion(8). `inject` goes between the two steps.
    */
  private def helperUpgrade(db: File, inject: String = ""): String =
    val v7 = DbSchema.toV7(columns(db, "battery"), columns(db, "energy_state"))
    val v8 = DbSchema.toV8(columns(db, "fixes"))
    (Seq("PRAGMA journal_mode=WAL", "BEGIN IMMEDIATE") ++ v7 ++ Seq(inject).filter(_.nonEmpty) ++ v8 ++
      Seq("PRAGMA user_version=8", "COMMIT")).map(_ + ";").mkString("\n") + "\n"

  /** A 0.21.0 database (user_version 6) with a few rows, in WAL mode. */
  private def v6Db(): File =
    val db = tempDb()
    run(db, "PRAGMA journal_mode=WAL;\n" + v6 + "PRAGMA user_version=6;\n" +
      insert("fixes", "time, lat, lon, acc, alt, speed, bearing, provider, sats", "1000, 35.5, 139.5, 4.5, 40.25, NULL, NULL, 'gps', 12") +
      insert("battery", "time, pct, charging, screen_on, mode, plan", "1500, 80, 0, 1, 'balanced', 'GNSS 10秒間隔'"))
    db

  private def schemaOf(db: File): Vector[String] =
    run(db, "SELECT type, name, sql FROM sqlite_master ORDER BY type, name;")

  test("the helper's upgrade: user_version 8 with the tables, in one transaction") {
    val db = v6Db()
    val before = schemaOf(db)
    assertEquals(run(db, "PRAGMA user_version;"), Vector("6"))
    run(db, helperUpgrade(db))
    assertEquals(run(db, "PRAGMA user_version;"), Vector("8"))
    assertNotEquals(schemaOf(db), before)
    assertEquals(run(db, "SELECT time, pct FROM battery;"), Vector("1500|80"))
  }

  test("an upgrade cut short (the process killed between the steps) leaves version 6 as it was, and runs again") {
    assume(shell, "sqlite3 not installed")
    val db = v6Db()
    val before = schemaOf(db)
    val script = helperUpgrade(db)
    // Up to the end of step 7 (inside the transaction), then killed.
    val cut = script.linesIterator.takeWhile(!_.startsWith("ALTER TABLE fixes")).mkString("\n") + "\nSELECT 'reached';\n"
    val p = java.lang.ProcessBuilder("sqlite3", "-bail", db.getPath).redirectErrorStream(true).start()
    p.getOutputStream.write(cut.getBytes("UTF-8"))
    p.getOutputStream.flush()
    val out = java.io.BufferedReader(java.io.InputStreamReader(p.getInputStream))
    var line = out.readLine()
    while line != null && line != "reached" do line = out.readLine()
    assertEquals(line, "reached")
    p.destroyForcibly()
    p.waitFor()
    assertEquals(run(db, "PRAGMA user_version;"), Vector("6"))
    assertEquals(schemaOf(db), before)
    run(db, helperUpgrade(db))
    assertEquals(run(db, "PRAGMA user_version;"), Vector("8"))
    assertEquals(run(db, "SELECT COUNT(*) FROM fixes WHERE msl IS NULL;"), Vector("1"))
  }

  test("a failed upgrade leaves version 6 as it was; the next open upgrades") {
    val db = v6Db()
    val before = schemaOf(db)
    assertNotEquals(runCode(db, helperUpgrade(db, inject = "INSERT INTO no_such_table VALUES(1)")), 0)
    assertEquals(run(db, "PRAGMA user_version;"), Vector("6"))
    assertEquals(schemaOf(db), before)
    assertEquals(run(db, "SELECT time FROM fixes;"), Vector("1000"))
    run(db, helperUpgrade(db))
    assertEquals(run(db, "PRAGMA user_version;"), Vector("8"))
  }

  test("a 0.21.0 database migrates to version 8 with every row kept, and matches a new one") {
    val db = tempDb()
    run(db, v6 + Seq(raiseFloor("fixes"), raiseFloor("battery")).map(_ + ";").mkString("\n"))
    run(db,
      insert("fixes", "time, lat, lon, acc, alt, speed, bearing, provider, sats", "1000, 35.5, 139.5, 4.5, 40.25, NULL, NULL, 'gps', 12") +
        insert("fixes", "time, lat, lon, acc, alt, speed, bearing, provider, sats", "2000, 35.6, 139.6, 20.0, NULL, 1.5, 90.0, 'fused', -1") +
        insert("battery", "time, pct, charging, screen_on, mode, plan", "1500, 80, 0, 1, 'balanced', 'GNSS 10秒間隔'"))
    val before = run(db, "SELECT rowid, * FROM fixes ORDER BY rowid; SELECT rowid, * FROM battery ORDER BY rowid;")

    migrate(db)

    // Old rows: the same, with nulls in the new columns.
    assertEquals(run(db, "SELECT rowid, time, lat, lon, acc, alt, speed, bearing, provider, sats FROM fixes ORDER BY rowid; " +
      "SELECT rowid, time, pct, charging, screen_on, mode, plan FROM battery ORDER BY rowid;"), before)
    assertEquals(run(db, "SELECT COUNT(*) FROM fixes WHERE alt_acc IS NULL AND msl IS NULL AND msl_acc IS NULL;"), Vector("2"))
    assertEquals(run(db, "SELECT COUNT(*) FROM battery WHERE charge_uah IS NULL AND plug IS NULL;"), Vector("1"))
    assertEquals(run(db, "SELECT COUNT(*) FROM energy_state; SELECT COUNT(*) FROM pressure;"), Vector("0", "0"))

    // The same tables as a database created new.
    val fresh = tempDb()
    create(fresh)
    for t <- Seq("fixes", "battery", "energy_state", "energy_checkpoint", "pressure", "deleted_range", "fix_msl") do
      assertEquals(tableInfo(db, t), tableInfo(fresh, t), t)
    assertEquals(run(db, "SELECT name FROM sqlite_master WHERE type='index' ORDER BY name;"),
      run(fresh, "SELECT name FROM sqlite_master WHERE type='index' ORDER BY name;"))

    // Running a step again (a pre-release database had part of it) changes nothing.
    migrate(db)
    for t <- Seq("fixes", "battery", "energy_state", "energy_checkpoint", "pressure", "deleted_range", "fix_msl") do
      assertEquals(tableInfo(db, t), tableInfo(fresh, t), t)

    // New rows after the migration take rowids above the old ones.
    run(db, insert("fixes", "time, lat, lon, acc, alt, provider, sats, alt_acc, msl, msl_acc",
      "3000, 35.7, 139.7, 3.0, 80.0, 'gps', 20, 2.5, 41.5, 3.5"))
    assertEquals(run(db, "SELECT rowid, msl FROM fixes WHERE time=3000;"), Vector("3|41.5"))
  }

  test("closing a span while archiving runs: the closed row is above the commit's rowids and is not deleted") {
    val db = tempDb()
    create(db)
    val cols = "start, end, gnss, gnss_interval_ms, provider, detail, still, screen_on, charging, mode, eff_mode, motion, env"
    def span(start: Long, end: Option[Long]) =
      insert("energy_state", cols, s"$start, ${end.fold("NULL")(_.toString)}, 1, 1000, 'gps', 0, 0, 0, 0, 'balanced', 'balanced', 'Walking', 'Outdoor'")
    val day = (0L, 86_400_000L)
    val archivable = DbSchema.archivable("energy_state")
    run(db, raiseFloor("energy_state") + ";\n" + span(1000, Some(5000)) + span(5000, None))
    // Archiving starts: the largest rowid (2), and reads the closed spans of the day.
    val max = run(db, s"BEGIN; ${raiseFloor("energy_state")}; ${maxRowid("energy_state")}; COMMIT;").head.toLong
    assertEquals(max, 2L)
    val (from, to) = day
    val where = s"start>=$from AND start<$to AND rowid<=$max AND $archivable"
    assertEquals(run(db, s"SELECT rowid FROM energy_state WHERE $where;"), Vector("1"))
    // Meanwhile the logger closes the open span the way Db.switchEnergyState
    // does: the open row goes, the closed one is inserted with a new rowid.
    run(db, s"BEGIN; DELETE FROM energy_state WHERE rowid=2 AND end IS NULL;\n${span(5000, Some(9000))}${span(9000, None)}COMMIT;")
    // The commit deletes what it archived: only row 1.
    run(db, deleteChunk("energy_state", where, 100) + ";")
    assertEquals(run(db, "SELECT start, end FROM energy_state ORDER BY start;"), Vector("5000|9000", "9000|"))
    // With an update in place instead, a commit that read one row (rowid
    // 3; the open row 4 was below its largest rowid) would delete two.
    val where2 = s"start>=$from AND start<$to AND rowid<=4 AND $archivable"
    assertEquals(run(db, s"SELECT rowid FROM energy_state WHERE $where2;"), Vector("3"))
    assertEquals(run(db, s"UPDATE energy_state SET end=12000 WHERE start=9000; DELETE FROM energy_state WHERE $where2; SELECT changes();"),
      Vector("2"))
  }

  test("a 0.21.0 battery commit (5 columns) repaired after the upgrade: rows as SQLite gives them, NULL temperature as NaN") {
    val db = v6Db()
    val day = (0 until 40).map(i => (2000L + i * 300_000L, 90 - i % 20))
    run(db, day.map((t, p) => insert("battery", "time, pct, charging, screen_on, mode, plan", s"$t, $p, 0, 1, 'balanced', 'x'")).mkString)
    // As 0.21.0 archived the day: 5 columns, its check made on rows of 5.
    val chunk = day.map((t, p) => Seq[Any](t, p, 0, 1, "balanced"))
    val prev = Seq(Seq[Any](1500L, 80, 0, 1, "balanced"))
    val f = File.createTempFile("battery", ".parquet")
    f.deleteOnExit()
    scala.util.Using.resource(java.io.BufferedOutputStream(java.io.FileOutputStream(f))) { o =>
      val w = parquet.ParquetWriter(o, Archive.Battery.columns.take(5), 100)
      (prev ++ chunk).foreach(w.write)
      w.close()
    }
    val sampler = ChunkSampler()
    chunk.foreach(r => sampler.add(r.toIndexedSeq))
    val target = CommitIntent.Target("battery/2025-10-09.parquet", 41, f.length,
      Some(sampler.check(CommitIntent.Prev.File(1, 1234), carried = 1, added = 40, range = Some((1500L, day.last._1)))))
    run(db, helperUpgrade(db))
    // The chunk's rows read as the app reads them after the upgrade (ArchiveSql.batteryRow on SQLite's values).
    val json = run(db, s".mode json\nSELECT ${ArchiveSql.BatteryColumns} FROM battery WHERE time >= 2000 ORDER BY time, rowid;").mkString
    val rows = ujson.read(if json.isEmpty then "[]" else json).arr.toVector.map { o =>
      val v = ArchiveSql.BatteryColumns.split(',').toIndexedSeq.map(c => o(c) match
        case ujson.Null                        => null
        case ujson.Num(n) if n == math.rint(n) => n.toLong
        case ujson.Num(n)                      => n
        case x                                 => x.str)
      ArchiveSql.batteryRow(v).toIndexedSeq
    }
    assertEquals(rows.size, 40)
    assert(rows.forall(r => r(9).asInstanceOf[Float].isNaN), "NULL temperature reads as NaN")
    val bag = RowBag()
    rows.foreach(r => bag.add(CommitRepair.fit(r, 5)))
    val back = scala.util.Using.resource(parquet.ParquetReader.open(f.getPath))(_.rowIterator.toVector)
    assertEquals(CommitRepair.verify(target, 41, back.iterator, Some(bag)), Right(()))
    // A row with a temperature does not fit 5 columns: it matches nothing (kept in SQLite).
    assertEquals(CommitRepair.fit(rows.head.updated(9, 30.5f), 5).size, 11)
  }

  test("the outbox of 0.21.0 gets file and del_seq; its rows have neither (read from the batch when sent)") {
    val db = v6Db()
    // As 0.21.0's Db creates it, with an archive part waiting.
    run(db, """CREATE TABLE sync_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT,
      |reg TEXT NOT NULL, device_id TEXT NOT NULL, stream TEXT NOT NULL, seq INTEGER NOT NULL,
      |class TEXT NOT NULL, blob BLOB NOT NULL, created_at INTEGER NOT NULL, UNIQUE(reg, stream, seq));
      |INSERT INTO sync_outbox(reg, device_id, stream, seq, class, blob, created_at) VALUES('r', 'd', 'archive', 3, 'small', x'00', 1);
      |""".stripMargin)
    val sql = DbSchema.toV8(columns(db, "fixes"), columns(db, "sync_outbox"))
    run(db, sql.map(_ + ";").mkString("\n"))
    assert(Set("file", "del_seq").subsetOf(columns(db, "sync_outbox")))
    assertEquals(run(db, "SELECT seq, file IS NULL, del_seq IS NULL FROM sync_outbox;"), Vector("3|1|1"))
    // Again (a step run twice): nothing more to add.
    assert(DbSchema.toV8(columns(db, "fixes"), columns(db, "sync_outbox")).forall(!_.contains("sync_outbox")))
  }

  test("version 10: pics_adv and energy_state.ble; old spans keep ble NULL (not off); safe to run again") {
    val db = tempDb()
    create(db)
    val cols = "start, end, gnss, gnss_interval_ms, provider, detail, still, screen_on, charging, mode, eff_mode, motion, env"
    run(db, insert("energy_state", cols, "1000, 2000, 1, 10000, 'gps', 0, 0, 1, 0, 'balanced', 'balanced', 'OnFoot', 'Outdoor'"))
    def v10() = run(db, ("BEGIN" +: DbSchema.toV10(columns(db, "energy_state")) :+ "COMMIT").map(_ + ";").mkString("\n"))
    v10()
    assert(columns(db, "energy_state")("ble"))
    assertEquals(run(db, "SELECT start, ble IS NULL FROM energy_state;"), Vector("1000|1"))
    assertEquals(columns(db, "pics_adv"), Archive.PicsAdvs.colNames.toSet)
    assert(run(db, "SELECT name FROM sqlite_master WHERE type='index';").contains("pics_adv_start"))
    // Again: nothing to add (no ALTER), the same tables.
    assert(DbSchema.toV10(columns(db, "energy_state")).forall(!_.startsWith("ALTER")))
    val info = tableInfo(db, "energy_state")
    v10()
    assertEquals(tableInfo(db, "energy_state"), info)
    // Rows take rowids from the floor like the other archived tables.
    run(db, insert("pics_adv", "start, end, addr, msg_changes, n, rssi_max, rssi_max_at, rssi_min, data, scan",
      "5000, 5000, 'a', 0, 1, -60, 5000, -60, '58', 'debug'"))
    assertEquals(run(db, "SELECT COUNT(*), MIN(iid IS NULL) FROM pics_adv;"), Vector("1|1"))
    assertEquals(run(db, "SELECT parts, sub IS NULL FROM pics_adv;"), Vector("1|1"))
  }

  test("version 10 of a pre-release build (pics_adv without parts and sub): the columns are added, rows kept") {
    val db = tempDb()
    create(db)
    val old = DbSchema.CreatePicsAdv.replace(", parts INTEGER NOT NULL DEFAULT 1, sub TEXT", "")
    assert(old != DbSchema.CreatePicsAdv)
    run(db, old + ";\n" + insert("pics_adv", "start, end, addr, msg_changes, n, rssi_max, rssi_max_at, rssi_min, data, scan",
      "5000, 5000, 'a', 0, 1, -60, 5000, -60, '58', 'debug'"))
    assert(!columns(db, "pics_adv")("parts"))
    run(db, DbSchema.toV10(columns(db, "energy_state"), columns(db, "pics_adv")).map(_ + ";").mkString("\n"))
    assertEquals(columns(db, "pics_adv"), Archive.PicsAdvs.colNames.toSet)
    assertEquals(run(db, "SELECT start, parts, sub IS NULL FROM pics_adv;"), Vector("5000|1|1"))
    // Again: nothing more.
    assert(DbSchema.toV10(columns(db, "energy_state"), columns(db, "pics_adv")).forall(!_.startsWith("ALTER")))
  }
