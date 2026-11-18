package ashidori.core

import java.io.File
import scala.sys.process.*

/** Version 9's deletion of check-ins and places by a tombstone, against a
  * real SQLite (the sqlite3 shell; skipped where it is not installed): the
  * row stays, marked deleted, and the change counter takes it to the next
  * upload as the row's latest snapshot; live readers leave it out.
  */
class TombstoneSuite extends munit.FunSuite:
  private lazy val shell = scala.util.Try("sqlite3 -version".!!).isSuccess

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

  private def columns(db: File, table: String): Set[String] =
    run(db, s"SELECT name FROM pragma_table_info('$table');").toSet

  /** A version 8 database: the editable tables with their change counter, and rows synced up to rev 2. */
  private def v8(): File =
    val db = File.createTempFile("ashidori", ".db")
    db.delete()
    db.deleteOnExit()
    val triggers = DbSchema.RevColumns.filter((t, _) => t != "mode_overrides").flatMap((t, cols) =>
      Seq(s"ALTER TABLE $t ADD COLUMN sync_rev INTEGER") ++ DbSchema.revTriggers(t, cols))
    run(db, (Seq(DbSchema.CreatePlaces, DbSchema.CreateCheckIns,
      "CREATE TABLE sync_counter(id INTEGER PRIMARY KEY CHECK(id=0), value INTEGER NOT NULL)",
      "INSERT INTO sync_counter(id, value) VALUES(0, 0)") ++ triggers ++ Seq(
      "INSERT INTO places(lat, lon, radius, name, kind, external_id, updated_at) VALUES(35.6, 139.7, 50, 'タリーズ', 'UNKNOWN', 'node/2', 100)",
      "INSERT INTO checkins(time, lat, lon, venue, category, external_id, place_id, comment, private, updated_at) " +
        "VALUES(1000, 35.6, 139.7, 'タリーズ', 'カフェ', 'node/2', 1, '', 0, 100)")).map(_ + ";").mkString("\n"))
    db

  private def migrate(db: File): Unit =
    run(db, ("BEGIN" +: DbSchema.toV9(columns(db, "checkins"), columns(db, "places")) :+ "COMMIT").map(_ + ";").mkString("\n"))

  test("version 9 adds deleted (0 for the rows there) to both tables, and runs again without change") {
    val db = v8()
    migrate(db)
    assert(columns(db, "checkins")("deleted"))
    assert(columns(db, "places")("deleted"))
    assertEquals(run(db, "SELECT deleted, sync_rev FROM checkins; SELECT deleted, sync_rev FROM places;"), Vector("0|2", "0|1"))
    assertEquals(DbSchema.toV9(columns(db, "checkins"), columns(db, "places")), Nil)
  }

  test("a tombstone keeps the row, marks it deleted and takes it to the next upload; live readers leave it out") {
    val db = v8()
    migrate(db)
    run(db, DbSchema.tombstone("checkins").replaceFirst("\\?", "200").replaceFirst("\\?", "1") + ";")
    // The upload of rows changed after rev 2 (Db.rowsChangedAfter) takes it, with deleted 1.
    assertEquals(run(db, "SELECT id, venue, deleted, updated_at, sync_rev FROM checkins WHERE sync_rev>2;"), Vector("1|タリーズ|1|200|3"))
    assertEquals(run(db, s"SELECT COUNT(*) FROM checkins WHERE ${DbSchema.Live};"), Vector("0"))
    // Again: already deleted, nothing changes (no new upload).
    run(db, DbSchema.tombstone("checkins").replaceFirst("\\?", "300").replaceFirst("\\?", "1") + ";")
    assertEquals(run(db, "SELECT updated_at, sync_rev FROM checkins;"), Vector("200|3"))
    // A place likewise.
    run(db, DbSchema.tombstone("places").replaceFirst("\\?", "400").replaceFirst("\\?", "1") + ";")
    assertEquals(run(db, "SELECT deleted, updated_at, sync_rev FROM places;"), Vector("1|400|4"))
    assertEquals(run(db, s"SELECT COUNT(*) FROM places WHERE ${DbSchema.Live};"), Vector("0"))
  }

  test("rows added after the upgrade are live (deleted defaults to 0)") {
    val db = v8()
    migrate(db)
    run(db, "INSERT INTO checkins(time, lat, lon, venue, updated_at) VALUES(2000, 35.6, 139.7, 'ドトール', 500);")
    assertEquals(run(db, s"SELECT venue FROM checkins WHERE ${DbSchema.Live};"), Vector("タリーズ", "ドトール"))
  }
