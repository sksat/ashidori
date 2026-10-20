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
