package ashidori.core

/** What an archive commit is about to do, recorded durably before it starts:
  * replace these files (each `<path>.tmp`, written and verified, renamed
  * over `<path>`) and then delete these rows from SQLite.
  *
  * A crash between the renames and the deletion leaves the rows both in
  * the archive and in SQLite (shown twice), or some files renamed and some
  * not. The next start reads the record and finishes the commit if every
  * file is either in place (the expected row count and size) or still
  * waiting as its verified tmp file; otherwise it drops the record, and
  * the rows stay in SQLite for the next run (which merges them again).
  */
final case class CommitIntent(files: Seq[CommitIntent.Target], delete: CommitIntent.Delete)

object CommitIntent:
  /** An archive file (relative to the archive root) and its new version. */
  final case class Target(path: String, rows: Long, size: Long)

  enum Delete:
    /** Rows of a small table in [from, to) with rowid <= max. */
    case Hot(table: String, from: Long, to: Long, max: Long)
    /** Raw lines of a portion ([[ArchiveSql.rawPortion]]) but `kept`. */
    case Raw(from: Long, to: Long, max: Long, after: ArchiveMerge.Cursor, upTo: ArchiveMerge.Cursor, kept: Seq[Long])
    /** Raw lines by rowid. */
    case Ids(rowids: Seq[Long])
    /** Nothing in SQLite (files replaced by others, as a month merge). */
    case Files(paths: Seq[String])

  /** State of one target on disk: (rows, size) of `<path>` and of `<path>.tmp`. */
  final case class OnDisk(dest: Option[(Long, Long)], tmp: Option[(Long, Long)])

  enum Recovery:
    /** Rename these tmp files into place, then do the deletion. */
    case Finish(renames: Seq[String])
    /** Nothing was published (no file renamed): forget it. */
    case Drop
    /** Some files are published and others cannot be: keep the record and
      * stop (no reader, backup or commit of these tables goes on).
      */
    case Stuck

  def recovery(i: CommitIntent, disk: Map[String, OnDisk]): Recovery =
    val states = i.files.map(t => t -> disk.getOrElse(t.path, OnDisk(None, None)))
    def is(v: Option[(Long, Long)], t: Target) = v.contains((t.rows, t.size))
    // Every new version is in place or still waiting, verified, as its tmp
    // file: finishing the commit is what it was about to do.
    if states.forall((t, s) => is(s.dest, t) || is(s.tmp, t)) then
      Recovery.Finish(states.collect { case (t, s) if !is(s.dest, t) => t.path })
    else if states.forall((t, s) => !is(s.dest, t)) then Recovery.Drop
    else Recovery.Stuck

  private def cursor(c: ArchiveMerge.Cursor) = s"${c.time}:${c.rowid}"
  private def cursorOf(s: String) = s.split(':') match
    case Array(t, r) => ArchiveMerge.Cursor(t.toLong, r.toLong)
    case _           => throw IllegalArgumentException(s"cursor $s")
  private def longs(s: String) = if s.isEmpty then Nil else s.split(',').toSeq.map(_.toLong)

  def encode(i: CommitIntent): String =
    val files = i.files.map(t => s"file\t${t.path}\t${t.rows}\t${t.size}")
    val delete = i.delete match
      case Delete.Hot(t, f, to, m)          => s"hot\t$t\t$f\t$to\t$m"
      case Delete.Raw(f, to, m, a, u, kept) => s"raw\t$f\t$to\t$m\t${cursor(a)}\t${cursor(u)}\t${kept.mkString(",")}"
      case Delete.Ids(ids)                  => s"ids\t${ids.mkString(",")}"
      case Delete.Files(ps)                 => s"files\t${ps.mkString("\t")}"
    (files :+ delete :+ "end").mkString("\n") + "\n"

  /** None for a record cut short (no `end`) or unknown. */
  def decode(s: String): Option[CommitIntent] =
    val lines = s.linesIterator.toVector
    if !lines.lastOption.contains("end") then None
    else
      scala.util.Try {
        val files = lines.filter(_.startsWith("file\t")).map(_.split('\t')).map(a => Target(a(1), a(2).toLong, a(3).toLong))
        val d = lines.filterNot(l => l.startsWith("file\t") || l == "end").head.split("\t", -1)
        val delete = d(0) match
          case "hot"   => Delete.Hot(d(1), d(2).toLong, d(3).toLong, d(4).toLong)
          case "raw"   => Delete.Raw(d(1).toLong, d(2).toLong, d(3).toLong, cursorOf(d(4)), cursorOf(d(5)), longs(d(6)))
          case "ids"   => Delete.Ids(longs(d(1)))
          case "files" => Delete.Files(d.drop(1).toSeq)
        CommitIntent(files, delete)
      }.toOption
