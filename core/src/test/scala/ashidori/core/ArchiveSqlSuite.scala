package ashidori.core

import scala.sys.process.*
import ashidori.core.ArchiveMerge.Cursor
import ashidori.core.ArchiveSql.*

/** The archiving SQL against a real SQLite (the sqlite3 shell; skipped where
  * it is not installed).
  */
class ArchiveSqlSuite extends munit.FunSuite:
  private lazy val shell = scala.util.Try("sqlite3 -version".!!).isSuccess

  /** Runs `script` in a fresh in-memory database; its output lines. */
  private def run(script: String): Vector[String] =
    assume(shell, "sqlite3 not installed")
    val out = StringBuilder()
    val io = BasicIO.standard(in => { in.write(script.getBytes("UTF-8")); in.close() })
      .withOutput(o => out.append(String(o.readAllBytes(), "UTF-8")))
    val code = Process(Seq("sqlite3", ":memory:")).run(io).exitValue()
    assertEquals(code, 0, script)
    out.toString.linesIterator.toVector

  private val schema =
    s"""CREATE TABLE raw_lines(time INTEGER NOT NULL, line TEXT NOT NULL);
       |CREATE INDEX raw_lines_time ON raw_lines(time);
       |$CreateFloor;
       |""".stripMargin

  /** An insert as Db.insertRaw does it: rowids from nextRowid, floor raised. */
  private def insert(time: Long, line: String): String =
    s"""INSERT INTO raw_lines(rowid, time, line) VALUES((${nextRowid("raw_lines")}), $time, '$line');
       |${takeRowids("raw_lines", 0)};
       |UPDATE rowid_floor SET next = MAX(next, (SELECT MAX(rowid) FROM raw_lines) + 1) WHERE tbl='raw_lines';
       |""".stripMargin

  test("without the floor SQLite reuses the rowid of deleted rows; with it a late row is never deleted unread") {
    val rows = (1 to 10).map(i => s"INSERT INTO raw_lines(time, line) VALUES(${1000 + i}, 'old$i');").mkString("\n")
    // Plain SQLite: delete rows including the largest, insert: rowid 1 again.
    assertEquals(run(schema + rows + "\nDELETE FROM raw_lines;\nINSERT INTO raw_lines(time, line) VALUES(1005, 'late');\nSELECT rowid FROM raw_lines;"),
      Vector("1"))
    // Archiving: the floor is raised as the largest rowid is read (one
    // transaction), rows up to it are deleted, then the late row comes.
    val (where, args) = rawPortion(1000, 2000, 10, Cursor(1000, Long.MinValue), Cursor(1010, 10))
    val out = run(schema + rows + s"""
      |BEGIN; ${raiseFloor("raw_lines")}; SELECT ${maxRowid("raw_lines").stripPrefix("SELECT ")}; COMMIT;
      |${bind(deleteChunk("raw_lines", where, 3), args)};
      |${bind(deleteChunk("raw_lines", where, 3), args)};
      |${insert(1005, "late")}
      |${bind(deleteChunk("raw_lines", where, 3), args)};
      |${bind(deleteChunk("raw_lines", where, 3), args)};
      |SELECT rowid, line FROM raw_lines;
      |""".stripMargin)
    assertEquals(out, Vector("10", "11|late"))
  }

  test("the floor is raised on open: rows an older version inserted do not lower it") {
    val out = run(schema + s"""
      |INSERT INTO raw_lines(time, line) VALUES(1, 'a');
      |INSERT INTO raw_lines(time, line) VALUES(2, 'b');
      |${raiseFloor("raw_lines")};
      |DELETE FROM raw_lines;
      |${raiseFloor("raw_lines")};
      |SELECT next FROM rowid_floor;
      |${insert(3, "c")}
      |SELECT rowid FROM raw_lines;
      |""".stripMargin)
    assertEquals(out, Vector("3", "3"))
  }

  test("a portion counts and deletes only its own lines, not lines kept by an earlier portion") {
    val rows = (1 to 20).map(i => s"INSERT INTO raw_lines(time, line) VALUES(${1000 + i}, 'l$i');").mkString("\n")
    // Portion 1 read lines 1..10 and kept line 4 (it had no table).
    val (w1, a1) = rawPortion(1000, 2000, 20, Cursor(1000, Long.MinValue), Cursor(1010, 10))
    // Portion 2 read lines 11..20.
    val (w2, a2) = rawPortion(1000, 2000, 20, Cursor(1010, 10), Cursor(1020, 20))
    val out = run(schema + rows + s"""
      |SELECT COUNT(*) FROM raw_lines WHERE ${bind(w1, a1)};
      |${bind(deleteChunk("raw_lines", except(w1, Seq(4)), 100), a1)};
      |SELECT COUNT(*) FROM raw_lines WHERE ${bind(w2, a2)};
      |${bind(deleteChunk("raw_lines", w2, 100), a2)};
      |SELECT group_concat(line) FROM raw_lines;
      |""".stripMargin)
    assertEquals(out, Vector("10", "10", "l4"))
  }

  test("chunks are read in (time, rowid) order after the cursor, ties included") {
    val rows = Seq((5L, "a"), (3L, "b"), (5L, "c"), (4L, "d"), (5L, "e")).map((t, l) => s"INSERT INTO raw_lines(time, line) VALUES($t, '$l');").mkString("\n")
    val (q1, a1) = rawAfter(0, 10, 100, Cursor(0, Long.MinValue), 3)
    val (q2, a2) = rawAfter(0, 10, 100, Cursor(5, 1), 3)
    val out = run(schema + rows + s"\n${bind(q1, a1)};\nSELECT '-';\n${bind(q2, a2)};\n")
    assertEquals(out, Vector("2|3|b", "4|4|d", "1|5|a", "-", "3|5|c", "5|5|e"))
  }
