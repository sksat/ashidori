package ashidori.core

/** Joins raw GNSS logs (GnssLogger format) into one file for a time range:
  * one header, then only records inside [from, to). Streams line by line so
  * a long range does not need to fit in memory.
  */
object GnssLogMerge:

  /** Time of a record, if the line carries one. */
  def timeOf(line: String): Option[Long] =
    val a = line.split(",", -1)
    def at(i: Int) = if i < a.length then a(i).toLongOption.orElse(a(i).toDoubleOption.map(_.toLong)) else None
    a(0) match
      case "Raw" | "Status" | "Accel" | "Gyro" | "Mag" => at(1)
      case "Fix"                      => at(8)
      case "NMEA"                     => at(a.length - 1)
      case _                          => None

  /** Merges `files` (each an iterator of lines, in time order) into `out`.
    * Lines without a time (navigation messages) follow the record before
    * them; a session marker is written just before its first record in
    * range. Returns the number of records written.
    */
  def merge(files: Seq[Iterator[String]], from: Long, to: Long, out: String => Unit): Int =
    var headerWritten = false
    var written = 0
    for lines <- files do
      val header = Vector.newBuilder[String]
      var inHeader = true
      var inRange = false
      var session: Option[String] = None
      for line <- lines do
        if inHeader && line.startsWith("#") && !line.startsWith("# Session") then header += line
        else
          if inHeader then
            inHeader = false
            if !headerWritten then
              header.result().foreach(out)
              headerWritten = true
          if line.startsWith("# Session") then
            session = Some(line)
            inRange = false
          else if !line.startsWith("#") then
            timeOf(line).foreach(t => inRange = t >= from && t < to)
            if inRange then
              session.foreach(out)
              session = None
              out(line)
              written += 1
    written
