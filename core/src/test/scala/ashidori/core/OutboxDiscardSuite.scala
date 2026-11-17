package ashidori.core

import java.io.File
import scala.sys.process.*
import ashidori.core.sync.ArchiveUpload

/** Discarding a batch that must not be sent and forgetting its file's
  * upload marks (ArchiveUpload.discard, run by Db.discardOutbox in one
  * transaction), against a real SQLite (the sqlite3 shell; skipped where it
  * is not installed), with the process killed between the two.
  */
class OutboxDiscardSuite extends munit.FunSuite:
  private lazy val shell = scala.util.Try("sqlite3 -version".!!).isSuccess

  private def run(db: File, script: String): Vector[String] =
    assume(shell, "sqlite3 not installed")
    val out = StringBuilder()
    val io = BasicIO.standard(in => { in.write(script.getBytes("UTF-8")); in.close() })
      .withOutput(o => out.append(String(o.readAllBytes(), "UTF-8"))).withError(_.readAllBytes())
    assertEquals(Process(Seq("sqlite3", "-bail", db.getPath)).run(io).exitValue(), 0, script)
    out.toString.linesIterator.toVector

  /** Runs `script` up to its line `SELECT 'reached';`, then kills the shell. */
  private def runKilled(db: File, script: String): Unit =
    assume(shell, "sqlite3 not installed")
    val p = java.lang.ProcessBuilder("sqlite3", "-bail", db.getPath).redirectErrorStream(true).start()
    p.getOutputStream.write(script.getBytes("UTF-8"))
    p.getOutputStream.flush()
    val out = java.io.BufferedReader(java.io.InputStreamReader(p.getInputStream))
    var line = out.readLine()
    while line != null && line != "reached" do line = out.readLine()
    assertEquals(line, "reached")
    p.destroyForcibly()
    p.waitFor()

  private val file = "fixes/2026-10-01.parquet"

  /** An outbox with a part of `file` sealed before a deletion, its progress
    * (part 2 of generation 77 enqueued), and other state.
    */
  private def setup(): File =
    val db = File.createTempFile("outbox", ".db")
    db.delete()
    db.deleteOnExit()
    run(db, s"""PRAGMA journal_mode=WAL;
      |CREATE TABLE sync_state(name TEXT PRIMARY KEY, value INTEGER);
      |CREATE TABLE sync_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, reg TEXT NOT NULL, device_id TEXT NOT NULL,
      |  stream TEXT NOT NULL, seq INTEGER NOT NULL, class TEXT NOT NULL, blob BLOB NOT NULL, created_at INTEGER NOT NULL,
      |  file TEXT, del_seq INTEGER, UNIQUE(reg, stream, seq));
      |INSERT INTO sync_outbox(reg, device_id, stream, seq, class, blob, created_at, file, del_seq)
      |  VALUES('r', 'd', 'archive', 5, 'small', x'00', 1, '$file', 0);
      |INSERT INTO sync_state VALUES('r|upgen:$file', 77), ('r|uppart:$file', 2), ('r|upbytes:$file', 8),
      |  ('r|up:motion/2026-10-01.parquet', 9), ('r|seq:archive', 6);
      |""".stripMargin)
    db

  private def names(db: File) = run(db, "SELECT name FROM sync_state ORDER BY name;").toSet
  private def outbox(db: File) = run(db, "SELECT seq FROM sync_outbox;")

  /** The statements as Db runs them (arguments in place), each followed by `after`. */
  private def statements(db: File, after: String): Seq[String] =
    ArchiveUpload.discard(1, names(db).toSeq.sorted, Set(file)).map { (sql, args) =>
      args.foldLeft(sql)((s, a) => s.replaceFirst("\\?", a match
        case n: Long => n.toString
        case x       => "'" + x.toString.replace("'", "''") + "'")) + ";\n" + after
    }

  test("the batch and its file's marks go together; other marks stay") {
    val db = setup()
    run(db, ("BEGIN;\n" +: statements(db, "") :+ "COMMIT;\n").mkString)
    assertEquals(outbox(db), Vector())
    assertEquals(names(db), Set("r|up:motion/2026-10-01.parquet", "r|seq:archive"))
  }

  test("stopped between discarding the batch and forgetting the marks: after the restart both are still there") {
    val db = setup()
    val before = names(db)
    // In one transaction (Db.discardOutbox), killed right after the batch's delete.
    val first = statements(db, "SELECT 'reached';\n").head
    runKilled(db, "BEGIN;\n" + first)
    assertEquals(outbox(db), Vector("5"), "the batch is still there: found and discarded again")
    assertEquals(names(db), before)
    // The next send discards it again, with the marks: the file goes out from part 0.
    run(db, ("BEGIN;\n" +: statements(db, "") :+ "COMMIT;\n").mkString)
    assertEquals(outbox(db), Vector())
    assert(!names(db).exists(_.contains(file)))
  }

  test("as two separate writes (no transaction), the same stop lost the batch but kept the marks") {
    val db = setup()
    runKilled(db, statements(db, "SELECT 'reached';\n").head)
    // Generation 77 resumes at part 2: the parts this batch held would never be sent.
    assertEquals(outbox(db), Vector())
    assert(names(db).contains(s"r|uppart:$file"))
  }
