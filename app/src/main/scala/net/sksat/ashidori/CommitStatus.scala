package net.sksat.ashidori

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try
import ashidori.core.*

/** An unfinished archive commit that waits for the user (shared by the
  * processes through `files/maintenance/commit-stuck`): recovery could not
  * finish it and the automatic repair would have to quarantine a file
  * ([[CommitRepair]]). It names the commit record it is about, so a
  * record changed or removed since does not count.
  */
object CommitStatus:
  private def file = File(Maintenance.dir, "commit-stuck")
  private def intentFile = File(Maintenance.dir, "commit")
  private def read(f: File) = Try(String(java.nio.file.Files.readAllBytes(f.toPath), UTF_8)).toOption

  private def crc(s: String): String =
    val c = java.util.zip.CRC32()
    c.update(s.getBytes(UTF_8))
    f"${c.getValue}%08x"

  /** The commit recorded as `intentText` waits for the user; `steps`: what
    * a repair would do with each table (shown in 端末診断).
    */
  def mark(intentText: String, steps: Seq[CommitRepair.Step]): Unit =
    Maintenance.write(file, (crc(intentText) +: steps.map(describe)).mkString("\n"))

  def waitsForUser(intentText: String): Boolean =
    read(file).flatMap(_.linesIterator.nextOption()).contains(crc(intentText))

  /** The commit record is gone: the mark with it. */
  def clear(): Unit =
    if file.delete() then Try(ArchiveStore.syncDir(file.getParentFile))

  /** Waiting for the user: one line per table. */
  def stuck(): Option[Seq[String]] =
    for
      text <- read(intentFile)
      mark <- read(file)
      lines = mark.linesIterator.toSeq
      if lines.headOption.contains(crc(text))
    yield lines.drop(1)

  def describe(s: CommitRepair.Step): String =
    import CommitRepair.{Action, State}
    val what = (s.state, s.action) match
      case (_, Action.DeleteRows) => "置き換え済み・検証済み"
      case (State.Quarantined, _) => "隔離済み"
      case (State.Published, Action.Quarantine(why, _)) => s"置き換え済み・検証できない（$why）"
      case (State.NotPublished, Action.Quarantine(why, _)) => s"置き換え前・$why"
      case (State.NotPublished, _) => "置き換え前（行は端末内に保持）"
      case (_, Action.Quarantine(why, _)) => s"状態が不明（$why）"
      case _ => s.state.toString
    s"${s.target.path}: $what"

/** Days that need the user's attention: files a repair moved to
  * quarantine (kept, never deleted). Kept in `files/maintenance/attention`,
  * one line per file; the banner shows until the user has seen them
  * (「確認済みにする」), 端末診断 always lists them.
  */
object Attention:
  /** `path`: the archive file; `quarantined`: where it is now (relative to
    * the archive root); `onlyCopy`: it holds rows archived before, which
    * exist nowhere else.
    */
  final case class Entry(time: Long, path: String, quarantined: String, onlyCopy: Boolean, why: String, seen: Boolean = false)

  private def file = File(Maintenance.dir, "attention")

  /** Both processes change the list: a read and its rewrite under a file
    * lock (and this process's monitor, as a JVM holds one lock per file).
    */
  private def locked[A](f: => A): A = synchronized {
    scala.util.Using.resource(java.io.RandomAccessFile(File(Maintenance.dir, ".lock-attention"), "rw")) { raf =>
      val l = raf.getChannel.lock()
      try f
      finally l.release()
    }
  }

  private def encode(e: Entry): String =
    Seq(e.time.toString, e.path, e.quarantined, if e.onlyCopy then "1" else "0", LifeEvent.clean(e.why), if e.seen then "1" else "0")
      .mkString("\t")

  private def decode(l: String): Option[Entry] = l.split("\t", -1) match
    case Array(t, p, q, o, w, s) => t.toLongOption.map(Entry(_, p, q, o == "1", w, s == "1"))
    case _ => None

  def all(): Seq[Entry] =
    Try(String(java.nio.file.Files.readAllBytes(file.toPath), UTF_8)).toOption.toSeq.flatMap(_.linesIterator.flatMap(decode))

  private def put(es: Seq[Entry]): Unit = Maintenance.write(file, es.map(encode).mkString("\n") + "\n")

  /** Durable when it returns (before the file is moved). */
  def add(e: Entry): Unit = locked {
    val es = all()
    if !es.exists(_.quarantined == e.quarantined) then put(es :+ e)
  }

  def acknowledge(): Unit = locked {
    val es = all()
    if es.exists(!_.seen) then put(es.map(_.copy(seen = true)))
  }

  def unseen: Boolean = all().exists(!_.seen)

  /** Total size of the quarantined files (archive/quarantine/). */
  def quarantineBytes(): Long =
    def size(f: File): Long = if f.isDirectory then Option(f.listFiles()).toSeq.flatten.map(size).sum else f.length
    size(File(AshidoriApp.archive.root, ArchiveStore.QuarantineDir))

  /** "11/10 imu" for a path like `imu/2026-11-10.parquet`. */
  def label(e: Entry): String =
    val name = e.path.drop(e.path.indexOf('/') + 1).stripSuffix(".tmp").stripSuffix(".parquet")
    s"${name.drop(5).replace('-', '/')} ${CommitRepair.tableOf(e.path)}"
