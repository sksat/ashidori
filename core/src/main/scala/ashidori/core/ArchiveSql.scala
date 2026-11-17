package ashidori.core

/** The SQL archiving runs against SQLite (shared by the app and the tests,
  * which run it with the sqlite3 shell). Arguments are numbers only.
  *
  * Rows of an archived table are read and deleted as "rowid up to the
  * largest at the start". That is safe only if no row inserted later can
  * get a rowid at or below it: SQLite gives a new row the largest rowid plus
  * one, which reuses rowids once the rows holding the largest were deleted.
  * So rowids come from `rowid_floor`, which only grows: raised to above the
  * largest rowid when archiving starts (in the same transaction that reads
  * it) and when the database is opened, and past every rowid an insert
  * takes.
  */
object ArchiveSql:
  /** The columns of `battery` the archive reads from SQLite, in its order. */
  val BatteryColumns = "time,pct,charging,screen_on,mode,charge_uah,current_avg_ua,current_now_ua,voltage_mv,temp_c,plug"

  /** A battery row of the archive from the values SQLite gives for
    * [[BatteryColumns]] (null for NULL; Long, Double or String otherwise):
    * as the app reads it (a NULL temperature is NaN, a NULL count 0).
    */
  def batteryRow(v: IndexedSeq[Any]): Seq[Any] =
    def long(i: Int): Long = v(i) match { case null => 0L; case n: Long => n; case n: Int => n.toLong; case d: Double => d.toLong; case s => s.toString.toLong }
    def int(i: Int): Int = long(i).toInt
    def orNull(i: Int, f: Int => Any): Any = if v(i) == null then null else f(i)
    def str(i: Int): String = if v(i) == null then null else v(i).toString
    val temp: Float = v(9) match { case null => Float.NaN; case n: Long => n.toFloat; case n: Int => n.toFloat; case d: Double => d.toFloat; case s => s.toString.toFloat }
    Seq(long(0), int(1), int(2), int(3), str(4), orNull(5, long), orNull(6, int), orNull(7, int), orNull(8, int), temp, str(10))

  val CreateFloor = "CREATE TABLE IF NOT EXISTS rowid_floor(tbl TEXT PRIMARY KEY, next INTEGER NOT NULL)"

  /** Raises the floor of `table` to above its largest rowid. */
  def raiseFloor(table: String): String =
    s"INSERT INTO rowid_floor(tbl, next) SELECT '$table', COALESCE(MAX(rowid), 0) + 1 FROM $table WHERE 1 " +
      "ON CONFLICT(tbl) DO UPDATE SET next = MAX(next, excluded.next)"

  /** The first rowid an insert into `table` takes. */
  def nextRowid(table: String): String =
    s"SELECT MAX(COALESCE((SELECT MAX(rowid) FROM $table), 0) + 1, COALESCE((SELECT next FROM rowid_floor WHERE tbl='$table'), 0))"

  /** Records that rowids below `next` (one past the last taken) are used. */
  def takeRowids(table: String, next: Long): String =
    s"INSERT INTO rowid_floor(tbl, next) VALUES('$table', $next) ON CONFLICT(tbl) DO UPDATE SET next = MAX(next, excluded.next)"

  def maxRowid(table: String): String = s"SELECT COALESCE(MAX(rowid), 0) FROM $table"

  /** Raw lines of [from, to) with rowid <= `max` after `after` in (time, rowid)
    * order, `limit` of them: rowid, time, line.
    */
  def rawAfter(from: Long, to: Long, max: Long, after: ArchiveMerge.Cursor, limit: Int): (String, Seq[Long]) =
    ("SELECT rowid, time, line FROM raw_lines WHERE time>=? AND time<? AND rowid<=? AND (time>? OR (time=? AND rowid>?)) " +
      "ORDER BY time, rowid LIMIT ?",
      Seq(math.max(from, after.time), to, max, after.time, after.time, after.rowid, limit.toLong))

  /** The raw lines a portion read: of [from, to), rowid <= `max`, after
    * `after` (where the portion started) and up to `upTo` (its last line).
    * Lines kept from earlier portions lie before `after` and do not count.
    */
  def rawPortion(from: Long, to: Long, max: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor): (String, Seq[Long]) =
    ("time>=? AND time<? AND rowid<=? AND (time>? OR (time=? AND rowid>?)) AND (time<? OR (time=? AND rowid<=?))",
      Seq(from, to, max, after.time, after.time, after.rowid, upTo.time, upTo.time, upTo.rowid))

  /** The raw lines of a portion ([[rawPortion]]) after `cursor`, in (time,
    * rowid) order, `limit` of them: rowid, time, line.
    */
  def rawPortionAfter(from: Long, to: Long, max: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor,
                      cursor: ArchiveMerge.Cursor, limit: Int): (String, Seq[Long]) =
    val (where, args) = rawPortion(from, to, max, after, upTo)
    (s"SELECT rowid, time, line FROM raw_lines WHERE $where AND (time>? OR (time=? AND rowid>?)) ORDER BY time, rowid LIMIT ?",
      args ++ Seq(cursor.time, cursor.time, cursor.rowid, limit.toLong))

  /** `where` but not these rowids (lines that were not archived). */
  def except(where: String, rowids: Seq[Long]): String =
    if rowids.isEmpty then where else where + rowids.mkString(" AND rowid NOT IN (", ",", ")")

  /** Deletes at most `chunk` rows of `table` matching `where`. */
  def deleteChunk(table: String, where: String, chunk: Int): String =
    s"DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table WHERE $where LIMIT $chunk)"

  /** `sql` with its `?` replaced by `args` (numbers), for the sqlite3 shell. */
  def bind(sql: String, args: Seq[Long]): String =
    val it = args.iterator
    val out = StringBuilder()
    for c <- sql do if c == '?' then out.append(it.next()) else out.append(c)
    require(!it.hasNext, "more arguments than parameters")
    out.toString
