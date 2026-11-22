package ashidori.core

/** SQL of the signal tables that the battery measurement, the elevation
  * recording and the PICS reception extend, and of the migrations that
  * extend them (database versions 7, 8 and 10). Shared by the app's `Db` and the tests, which run it
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
    * `end_unknown` 1: the state was last confirmed at `end` ([[EnergyLog]]).
    */
  val CreateEnergyState: String =
    """CREATE TABLE IF NOT EXISTS energy_state(start INTEGER NOT NULL, end INTEGER, gnss INTEGER,
      |gnss_interval_ms INTEGER, provider TEXT, detail INTEGER, still INTEGER, screen_on INTEGER, charging INTEGER,
      |mode TEXT, eff_mode TEXT, motion TEXT, env TEXT, end_unknown INTEGER NOT NULL DEFAULT 0,
      |boot INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL DEFAULT 0)""".stripMargin

  /** Version 7: per recorder (boot), the last state change applied and
    * when the state was last confirmed and stored ([[EnergyCheckpoint]]).
    * Not archived.
    */
  val CreateEnergyCheckpoint: String =
    """CREATE TABLE IF NOT EXISTS energy_checkpoint(boot INTEGER PRIMARY KEY, seq INTEGER NOT NULL,
      |confirmed_at INTEGER NOT NULL)""".stripMargin

  /** Version 8: the vertical accuracy of `alt` and the MSL altitude of fixes. */
  val FixColumns: Seq[(String, String)] = Seq("alt_acc" -> "REAL", "msl" -> "REAL", "msl_acc" -> "REAL")

  /** Version 8: MSL altitudes computed after their fixes were saved
    * (derived data, joined to `fixes` by time while the fixes are in SQLite;
    * see [[MslAltitude]] in the app). Not archived.
    */
  val CreateFixMsl: String = "CREATE TABLE IF NOT EXISTS fix_msl(time INTEGER NOT NULL, msl REAL NOT NULL, msl_acc REAL)"

  /** Version 8: barometric pressure ([[PressureSample]]). */
  val CreatePressure: String = "CREATE TABLE IF NOT EXISTS pressure(time INTEGER NOT NULL, hpa REAL NOT NULL)"

  /** From version 6 to 7. `battery`, `energyState`: the columns the tables
    * have now (none: the table does not exist).
    */
  def toV7(battery: Set[String], energyState: Set[String] = Set.empty): Seq[String] =
    BatteryColumns.filterNot((n, _) => battery(n)).map((n, t) => s"ALTER TABLE battery ADD COLUMN $n $t") ++
      Seq(CreateEnergyState, "CREATE INDEX IF NOT EXISTS energy_state_start ON energy_state(start)") ++
      // A pre-release build created the table without these.
      Seq("boot", "seq").filter(c => energyState.nonEmpty && !energyState(c))
        .map(c => s"ALTER TABLE energy_state ADD COLUMN $c INTEGER NOT NULL DEFAULT 0") ++
      Seq("CREATE INDEX IF NOT EXISTS energy_state_boot ON energy_state(boot, seq)") ++
      // A pre-release build created the table without it.
      Option.when(energyState.nonEmpty && !energyState("end_unknown"))(
        "ALTER TABLE energy_state ADD COLUMN end_unknown INTEGER NOT NULL DEFAULT 0") ++
      Seq(CreateEnergyCheckpoint)

  /** Version 8: ranges the user deleted ([[Deletion]]): nothing is written
    * into them again. Not archived.
    */
  val CreateDeletedRange: String =
    """CREATE TABLE IF NOT EXISTS deleted_range(start INTEGER NOT NULL, end INTEGER NOT NULL, deleted_at INTEGER NOT NULL,
      |archived_at INTEGER)""".stripMargin

  /** From version 7 to 8. `fixes`, `outbox`: the columns `fixes` and
    * `sync_outbox` have now (none: the table does not exist). The outbox
    * gets `file`, the archive file a batch is a part of, and `del_seq`, the
    * deletion sequence read before that file was opened, so that a batch
    * of a file holding a deleted range is never sent. Rows of earlier
    * versions have neither: their file is read from the batch when it is
    * about to be sent, and they count as opened before every deletion.
    */
  def toV8(fixes: Set[String], outbox: Set[String] = Set.empty): Seq[String] =
    FixColumns.filterNot((n, _) => fixes(n)).map((n, t) => s"ALTER TABLE fixes ADD COLUMN $n $t") ++
      Seq(CreatePressure, "CREATE INDEX IF NOT EXISTS pressure_time ON pressure(time)", CreateDeletedRange,
        CreateFixMsl, "CREATE INDEX IF NOT EXISTS fix_msl_time ON fix_msl(time)") ++
      Option.when(outbox.nonEmpty && !outbox("file"))("ALTER TABLE sync_outbox ADD COLUMN file TEXT") ++
      Option.when(outbox.nonEmpty && !outbox("del_seq"))("ALTER TABLE sync_outbox ADD COLUMN del_seq INTEGER")

  /** Version 10: received 高度化PICS advertisements, one row per run
    * ([[PicsAdv]], docs/design/pics.md 4.1). Archived like the other
    * signal tables (rowids from the floor).
    */
  val CreatePicsAdv: String =
    """CREATE TABLE IF NOT EXISTS pics_adv(start INTEGER NOT NULL, end INTEGER NOT NULL, addr TEXT NOT NULL, iid TEXT,
      |kind INTEGER, msg INTEGER, msg_last INTEGER, msg_changes INTEGER NOT NULL, n INTEGER NOT NULL,
      |rssi_max INTEGER NOT NULL, rssi_max_at INTEGER NOT NULL, rssi_min INTEGER NOT NULL, data TEXT NOT NULL,
      |scan TEXT NOT NULL, parts INTEGER NOT NULL DEFAULT 1, sub TEXT)""".stripMargin

  /** From version 9 to 10: `pics_adv` and its index, and `energy_state.ble`
    * (the PICS scan, NULL where not known: rows before it stay NULL, never
    * `off`). `energyState`, `picsAdv`: the columns the tables have now (none:
    * not there). Only what is missing: a pre-release build of version 10
    * made `pics_adv` without `parts` and `sub`.
    */
  def toV10(energyState: Set[String], picsAdv: Set[String] = Set.empty): Seq[String] =
    Seq(CreatePicsAdv, "CREATE INDEX IF NOT EXISTS pics_adv_start ON pics_adv(start)") ++
      Option.when(!energyState("ble"))("ALTER TABLE energy_state ADD COLUMN ble TEXT") ++
      Option.when(picsAdv.nonEmpty && !picsAdv("parts"))("ALTER TABLE pics_adv ADD COLUMN parts INTEGER NOT NULL DEFAULT 1") ++
      Option.when(picsAdv.nonEmpty && !picsAdv("sub"))("ALTER TABLE pics_adv ADD COLUMN sub TEXT")

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

  // ---- editable tables: places, check-ins (synced, docs/sync-protocol.md) ----

  /** `places` as versions 1 to 8 create it (version 2 added city, country, area to older ones). */
  val CreatePlaces: String =
    """CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL, lon REAL, radius REAL, name TEXT,
      |kind TEXT, external_id TEXT, city TEXT, country TEXT, area TEXT, updated_at INTEGER)""".stripMargin
  /** `checkins` as versions 1 to 8 create it. */
  val CreateCheckIns: String =
    """CREATE TABLE checkins(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, lat REAL, lon REAL, venue TEXT,
      |category TEXT, external_id TEXT, place_id INTEGER, comment TEXT, private INTEGER, updated_at INTEGER)""".stripMargin

  /** Editable tables and the columns whose change bumps `sync_rev` (the synced ones). */
  val RevColumns: Seq[(String, Seq[String])] = Seq(
    "places" -> Seq("lat", "lon", "radius", "name", "kind", "external_id", "updated_at"),
    "checkins" -> Seq("time", "lat", "lon", "venue", "category", "external_id", "place_id", "comment", "private", "updated_at"),
    "mode_overrides" -> Seq("start", "end", "mode", "updated_at")
  )

  /** The triggers that set `sync_rev` from the change counter on every
    * insert and every update of a synced column of `table` (version 5).
    */
  def revTriggers(table: String, cols: Seq[String]): Seq[String] =
    val bump = s"UPDATE sync_counter SET value=value+1; UPDATE $table SET sync_rev=(SELECT value FROM sync_counter) WHERE rowid=NEW.rowid;"
    Seq(
      s"CREATE TRIGGER IF NOT EXISTS ${table}_rev_ins AFTER INSERT ON $table BEGIN $bump END",
      s"CREATE TRIGGER IF NOT EXISTS ${table}_rev_upd AFTER UPDATE OF ${cols.map(c => "\"" + c + "\"").mkString(",")} ON $table BEGIN $bump END")

  /** Version 9: check-ins and places are deleted by a tombstone, `deleted`
    * 1 on the same row, so that the deletion is synced as the row's latest
    * snapshot (a deleted row is never uploaded); and a place's name keeps
    * where it came from, `name_source` ([[NameSource]]: "user", "venue";
    * NULL, not known, for the names stored before). `checkins`, `places`:
    * the columns the tables have now.
    */
  def toV9(checkins: Set[String], places: Set[String]): Seq[String] =
    Seq("checkins" -> checkins, "places" -> places).collect {
      case (t, cols) if !cols("deleted") => s"ALTER TABLE $t ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0"
    } ++ Option.when(!places("name_source"))("ALTER TABLE places ADD COLUMN name_source TEXT")

  /** The rows that are not deleted (an SQL condition). */
  val Live = "deleted=0"

  /** Deletes the row `id` of `checkins` or `places` (arguments: the time
    * now, `id`): the tombstone keeps its fields, marks it deleted and sets
    * `updated_at`, a synced column, so the change counter takes it to the
    * next upload. A row already deleted is left as it is.
    */
  def tombstone(table: String): String = s"UPDATE $table SET deleted=1, updated_at=? WHERE id=? AND deleted=0"
