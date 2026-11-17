package ashidori.core

import scala.util.Try

/** The number of a recorder of state changes (one per process,
  * [[EnergyTracker]]): above every number used before, wherever it may
  * still be. A number is used only once it is on disk.
  *
  * Used numbers are found in the counter file, in the database
  * (`energy_checkpoint`) and in spilled records not imported yet (`.rec`,
  * and a `.rec.tmp` cut short, read as text). The database and the spill
  * files are in both backups, so after a restore (the counter file not in
  * it) the next number is still above every one in the data. A number that
  * was allocated but never stored anywhere may be given again: no record
  * has it.
  */
object EnergyBoot:
  private val InRecord = """"boot"\s*:\s*"(\d+)"""".r

  /** The highest recorder number named in spilled records (0: none). */
  def inSpills(texts: Iterable[String]): Long =
    texts.iterator.flatMap(t => InRecord.findAllMatchIn(t).flatMap(m => m.group(1).toLongOption)).maxOption.getOrElse(0L)

  /** The next number, or None if any source cannot be read or the number
    * cannot be put on disk (then none is used: the caller tries again later).
    */
  def allocate(onFile: () => Long, inDb: () => Long, spills: () => Iterable[String], persist: Long => Unit): Option[Long] =
    // The spill files before the database: one imported meanwhile (gone
    // from the listing, or from under it) is in the database by then.
    for
      f <- Try(onFile()).toOption
      s <- Try(inSpills(spills())).toOption
      d <- Try(inDb()).toOption
      n = math.max(f, math.max(d, s)) + 1
      _ <- Try(persist(n)).toOption
    yield n
