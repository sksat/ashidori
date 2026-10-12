package ashidori.core

class GnssLogMergeSuite extends munit.FunSuite:
  private val header = GnssLogFormat.header("t", "15", "SHARP", "SH-M26").linesIterator.toVector
  private def raw(t: Long) = s"Raw,$t,1,,,,,,,,0,3"
  private def fix(t: Long) = GnssLogFormat.row("Fix", "gps", 35.0, 139.0, 1.0, 1f, 5f, Float.NaN, t)

  test("one header, only records in range, session markers before their first kept record") {
    val day1 = (header ++ Seq("# Session a", raw(100), fix(100), "Nav,3,1,1,1,1,0,0", raw(200), "# Session b", raw(900))).iterator
    val day2 = (header ++ Seq("# Session c", raw(1500), raw(2500))).iterator
    val out = Vector.newBuilder[String]
    val n = GnssLogMerge.merge(Seq(day1, day2), 150, 2000, out += _)
    val lines = out.result()
    assertEquals(lines.count(_.startsWith("# Raw,")), 1)
    assertEquals(lines.filterNot(_.startsWith("#")), Vector(raw(200), raw(900), raw(1500)))
    // "# Session a" had records before the range only up to 200 > 150, so it is kept.
    assertEquals(lines.filter(_.startsWith("# Session")), Vector("# Session a", "# Session b", "# Session c"))
    assertEquals(n, 3)
  }

  test("navigation messages follow the preceding record's time") {
    val f = (header ++ Seq(raw(100), "Nav,3,1,1,1,1,0", raw(300), "Nav,5,1,1,1,1,0")).iterator
    val out = Vector.newBuilder[String]
    GnssLogMerge.merge(Seq(f), 200, 1000, out += _)
    assertEquals(out.result().filterNot(_.startsWith("#")), Vector(raw(300), "Nav,5,1,1,1,1,0"))
  }
