package ashidori.core

/** SQL of the signal tables that the battery measurement and the elevation
  * recording extend, and of the migrations that extend them (database
  * versions 7 and 8). Shared by the app's `Db` and the tests, which run it
  * with the sqlite3 shell.
  *
  * The migrations only add: nullable columns at the end of `battery` and
  * `fixes`, and two new tables. Each step adds only what is missing, so it
  * can run again (a database created by a pre-release build that had some
  * of it already).
  */
object DbSchema:
  /** `fixes` as versions 1 to 6 (app up to 0.21.0) create it. */
  val CreateFixes: String =
    """CREATE TABLE fixes(time INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, alt REAL,
      |speed REAL, bearing REAL, provider TEXT, sats INTEGER)""".stripMargin
  /** `battery` as versions 1 to 6 create it. */
  val CreateBattery: String =
    "CREATE TABLE battery(time INTEGER NOT NULL, pct INTEGER, charging INTEGER, screen_on INTEGER, mode TEXT, plan TEXT)"

  /** Version 7: battery readings beyond the percentage. */
  val BatteryColumns: Seq[(String, String)] = Seq(
    "charge_uah" -> "INTEGER", "current_avg_ua" -> "INTEGER", "current_now_ua" -> "INTEGER", "voltage_mv" -> "INTEGER",
    "temp_c" -> "REAL", "plug" -> "TEXT")

  /** Version 7: the logger's state spans ([[EnergyState]]). The open span
    * (the current state) has a NULL `end`; it is closed by replacing its
    * row with the closed one(s), never by an update (see [[archivable]]).
    */
  val CreateEnergyState: String =
    """CREATE TABLE IF NOT EXISTS energy_state(start INTEGER NOT NULL, end INTEGER, gnss INTEGER,
      |gnss_interval_ms INTEGER, provider TEXT, detail INTEGER, still INTEGER, screen_on INTEGER, charging INTEGER,
      |mode TEXT, eff_mode TEXT, motion TEXT, env TEXT)""".stripMargin

  /** Version 8: the vertical accuracy of `alt` and the MSL altitude of fixes. */
  val FixColumns: Seq[(String, String)] = Seq("alt_acc" -> "REAL", "msl" -> "REAL", "msl_acc" -> "REAL")

  /** Version 8: barometric pressure ([[PressureSample]]). */
  val CreatePressure: String = "CREATE TABLE IF NOT EXISTS pressure(time INTEGER NOT NULL, hpa REAL NOT NULL)"

  /** From version 6 to 7. `battery`: the columns `battery` has now. */
  def toV7(battery: Set[String]): Seq[String] =
    BatteryColumns.filterNot((n, _) => battery(n)).map((n, t) => s"ALTER TABLE battery ADD COLUMN $n $t") ++
      Seq(CreateEnergyState, "CREATE INDEX IF NOT EXISTS energy_state_start ON energy_state(start)")

  /** From version 7 to 8. `fixes`: the columns `fixes` has now. */
  def toV8(fixes: Set[String]): Seq[String] =
    FixColumns.filterNot((n, _) => fixes(n)).map((n, t) => s"ALTER TABLE fixes ADD COLUMN $n $t") ++
      Seq(CreatePressure, "CREATE INDEX IF NOT EXISTS pressure_time ON pressure(time)")

  /** Which rows of an archived table archiving takes (an SQL condition):
    * all but the open span of `energy_state`, which stays in SQLite until
    * it is closed.
    *
    * Archiving deletes what it archived as "rows of the day with rowid up
    * to the largest at its start". A row that changes from open to closed
    * meanwhile must therefore get a new rowid, above that largest one:
    * closing a span deletes the open row and inserts the closed one with a
    * rowid from the floor ([[ArchiveSql.nextRowid]]). An update in place
    * would let the deletion take a row that was never archived.
    */
  def archivable(table: String): String = if table == "energy_state" then "end IS NOT NULL" else "1"
