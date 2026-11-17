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
  *
  * When some files were published and the rest cannot be (a tmp file gone
  * or damaged), the record stays ([[Recovery.Stuck]]) until
  * [[CommitRepair]] resolves it table by table. For that each target also
  * records what its new file must hold ([[Check]]). Records of 0.21.0 have
  * no check; the repair completes it from SQLite first.
  *
  * `repairing`: a repair of it began ([[CommitRepair]]); recovery then only
  * continues the repair. It is written as a line after the deletion,
  * which 0.21.0 does not read. For a commit of raw lines ([[guarded]]) it
  * is also written as a file line naming [[GuardDir]], a table 0.21.0 does
  * not know: 0.21.0 decodes the record but fails on that table's lock
  * before it acts, and every place 0.21.0 reads the raw tables catches
  * that (as for its own stuck commits). The small tables are read by
  * 0.21.0 on threads that do not catch it: their records get no guard.
  * What a repair leaves of them reads to 0.21.0 as a commit to drop or to
  * finish, which then loses nothing, as a repair moves every tmp file
  * aside before anything else (see [[CommitRepair]]).
  */
final case class CommitIntent(files: Seq[CommitIntent.Target], delete: CommitIntent.Delete, repairing: Boolean = false)

object CommitIntent:
  /** The guard's pseudo table (see above). */
  val GuardDir = "repair-guard"
  /** The line marking a record as being repaired (after the deletion). */
  val RepairingLine = "repairing"

  /** Whether a record being repaired gets the guard: a commit of raw lines. */
  def guarded(i: CommitIntent): Boolean = i.delete match
    case _: Delete.Raw | _: Delete.Ids => true
    case _                             => false

  /** An archive file (relative to the archive root) and its new version. */
  final case class Target(path: String, rows: Long, size: Long, check: Option[Check] = None)

  /** What the file a commit replaces held before it. */
  enum Prev:
    /** No file. */
    case Absent
    /** (rows in its footer, size). */
    case File(rows: Long, size: Long)
    /** Not recorded (a record of 0.21.0, completed by the repair). */
    case Unknown

  /** What the new version of a file holds, for verifying it once published
    * ([[CommitRepair.verify]]): the rows carried over from the previous
    * file and the rows of this commit's chunk (`carried + added` = its
    * footer's rows), the time range of the whole file and of the chunk, and
    * a few rows of the chunk as (time, [[ArchiveMerge.hash]]).
    */
  final case class Check(prev: Prev, carried: Long, added: Long, range: Option[(Long, Long)],
                         addedRange: Option[(Long, Long)], samples: Seq[(Long, Long)])

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

  private def range(r: Option[(Long, Long)]) = r.fold("-")((a, b) => s"$a:$b")
  private def rangeOf(s: String) = if s == "-" then None else
    s.split(':') match
      case Array(a, b) => Some((a.toLong, b.toLong))
      case _           => throw IllegalArgumentException(s"range $s")

  private def encodeCheck(c: Check): String =
    val prev = c.prev match
      case Prev.Absent     => "-"
      case Prev.Unknown    => "?"
      case Prev.File(r, s) => s"$r:$s"
    Seq("v2", prev, c.carried, c.added, range(c.range), range(c.addedRange),
      c.samples.map((t, h) => s"$t:$h").mkString(",")).mkString("\t")

  private def decodeCheck(a: Seq[String]): Check =
    val prev = a(0) match
      case "-" => Prev.Absent
      case "?" => Prev.Unknown
      case p   => rangeOf(p).map(Prev.File(_, _)).get
    val samples = if a(5).isEmpty then Nil else a(5).split(',').toSeq.map(s => rangeOf(s).get)
    Check(prev, a(1).toLong, a(2).toLong, rangeOf(a(3)), rangeOf(a(4)), samples)

  /** A file line is `file path rows size`, then the check (if any) after
    * `v2`: 0.21.0 reads the first four fields and ignores the rest.
    */
  def encode(i: CommitIntent): String =
    val files = i.files.map(t => s"file\t${t.path}\t${t.rows}\t${t.size}" + t.check.fold("")(c => "\t" + encodeCheck(c)))
    val delete = i.delete match
      case Delete.Hot(t, f, to, m)          => s"hot\t$t\t$f\t$to\t$m"
      case Delete.Raw(f, to, m, a, u, kept) => s"raw\t$f\t$to\t$m\t${cursor(a)}\t${cursor(u)}\t${kept.mkString(",")}"
      case Delete.Ids(ids)                  => s"ids\t${ids.mkString(",")}"
      case Delete.Files(ps)                 => s"files\t${ps.mkString("\t")}"
    val guard = Option.when(i.repairing && guarded(i))(s"file\t$GuardDir/begun\t-1\t-1").toSeq
    // The marker after the deletion: 0.21.0 reads the first line that is
    // not a file line as the deletion, and nothing after it.
    val marker = Option.when(i.repairing)(RepairingLine).toSeq
    (((guard ++ files) :+ delete) ++ marker :+ "end").mkString("\n") + "\n"

  /** None for a record cut short (no `end`) or unknown. */
  def decode(s: String): Option[CommitIntent] =
    val lines = s.linesIterator.toVector
    if !lines.lastOption.contains("end") then None
    else
      scala.util.Try {
        val (guards, fileLines) = lines.filter(_.startsWith("file\t")).partition(_.startsWith(s"file\t$GuardDir/"))
        val files = fileLines.map(_.split("\t", -1)).map { a =>
          Target(a(1), a(2).toLong, a(3).toLong, Option.when(a.length > 4 && a(4) == "v2")(decodeCheck(a.toSeq.drop(5))))
        }
        val d = lines.filterNot(l => l.startsWith("file\t") || l == "end" || l == RepairingLine).head.split("\t", -1)
        val delete = d(0) match
          case "hot"   => Delete.Hot(d(1), d(2).toLong, d(3).toLong, d(4).toLong)
          case "raw"   => Delete.Raw(d(1).toLong, d(2).toLong, d(3).toLong, cursorOf(d(4)), cursorOf(d(5)), longs(d(6)))
          case "ids"   => Delete.Ids(longs(d(1)))
          case "files" => Delete.Files(d.drop(1).toSeq)
        CommitIntent(files, delete, repairing = guards.nonEmpty || lines.contains(RepairingLine))
      }.toOption
