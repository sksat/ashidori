package ashidori.core

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Every place the app opens a database passes the handler that keeps a
  * corrupt database (Db.KeepOnCorruption): Android's default handler
  * deletes the database and its WAL, also when it was opened read-only.
  * A check of the app's sources (they are not compiled into these tests).
  */
class DbOpenSuite extends munit.FunSuite:
  private val app = Paths.get("..", "app", "src", "main", "scala")

  private def sources: Seq[(Path, String)] =
    assume(Files.isDirectory(app), s"no app sources at $app")
    Files.walk(app).iterator.asScala.filter(_.toString.endsWith(".scala")).map(p => p -> Files.readString(p)).toSeq

  /** Every occurrence of `call` in `text` with the code that follows it, up to its closing parenthesis. */
  private def calls(text: String, call: String): Seq[String] =
    Iterator.iterate(text.indexOf(call))(i => text.indexOf(call, i + 1)).takeWhile(_ >= 0).map { i =>
      var depth = 0
      var j = i + call.length - 1
      var done = false
      while j < text.length && !done do
        text(j) match
          case '(' => depth += 1
          case ')' =>
            depth -= 1
            if depth == 0 then done = true
          case _ => ()
        j += 1
      text.substring(i, j)
    }.toSeq

  test("every SQLiteDatabase.openDatabase and SQLiteOpenHelper passes KeepOnCorruption") {
    val src = sources
    assert(src.nonEmpty)
    val opens = src.flatMap((p, t) => calls(t, "SQLiteDatabase.openDatabase(").map(p -> _))
    assert(opens.nonEmpty)
    for (p, c) <- opens do assert(c.contains("KeepOnCorruption"), s"$p: $c")
    val helpers = src.flatMap((p, t) => calls(t, "extends SQLiteOpenHelper(").map(p -> _))
    // The record's database, and the 「訪れた日」 index: derived data in its
    // own file, which is deleted and made again when corrupt (and only that).
    assertEquals(helpers.size, 2)
    val (derived, record) = helpers.partition((p, _) => p.getFileName.toString == "VisitDays.scala")
    assertEquals(record.size, 1)
    for (p, c) <- record do assert(c.contains("KeepOnCorruption"), s"$p: $c")
    for (p, c) <- derived do assert(c.contains("DerivedDeleteOnCorruption") && c.contains("file(c)"), s"$p: $c")
    val visitDays = src.collectFirst { case (p, t) if p.getFileName.toString == "VisitDays.scala" => t }.get
    assert(visitDays.contains("File(c.getNoBackupFilesDir, \"visit_index.db\")"))
    // The open parameters (which replace the constructor's) set it again.
    val params = src.flatMap((_, t) => calls(t, "setOpenParams("))
    assert(params.nonEmpty && params.forall(_.contains("setErrorHandler(Db.KeepOnCorruption)")), params.toString)
    // No other way to open one.
    for (p, t) <- src; bad <- Seq("openOrCreateDatabase(", "SQLiteDatabase.create(", "Room.databaseBuilder") do
      assert(!t.contains(bad), s"$p uses $bad")
  }
