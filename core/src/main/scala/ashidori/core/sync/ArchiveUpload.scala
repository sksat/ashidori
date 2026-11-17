package ashidori.core.sync

import java.io.{EOFException, File, FileDescriptor, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8

/** What `fstat` says about an open file: its size, and values that tell its
  * versions apart (device, inode, modification time).
  */
final case class FileStat(size: Long, identity: Seq[Long])

/** One version of an archive file, opened once. Its size, its generation and
  * every byte read come from the same open descriptor.
  *
  * Archive files are only ever replaced by an atomic rename of a new file,
  * never rewritten in place (see `ArchiveStore`). An open descriptor keeps
  * the version it opened, also after the path names a newer file or is
  * deleted, so the parts read here always belong to [[generation]].
  */
final class ArchiveSnapshot private (raf: RandomAccessFile, val size: Long, val generation: Long) extends AutoCloseable:
  private val ch: FileChannel = raf.getChannel

  /** Exactly `len` bytes at `offset`; throws if this version is shorter. */
  def read(offset: Long, len: Int): Array[Byte] =
    val buf = ByteBuffer.allocate(len)
    while buf.hasRemaining do
      val n = ch.read(buf, offset + buf.position())
      if n < 0 then throw EOFException(s"archive version ended at ${offset + buf.position()} of $size")
    buf.array()

  def close(): Unit = raf.close()

object ArchiveSnapshot:
  /** Opens `f` and takes its size and identity with `stat` on the same
    * descriptor (`android.system.Os.fstat` in the app). Throws
    * [[java.io.FileNotFoundException]] when `f` is gone.
    */
  def open(f: File, stat: FileDescriptor => FileStat): ArchiveSnapshot =
    val raf = RandomAccessFile(f, "r")
    try
      val st = stat(raf.getFD)
      ArchiveSnapshot(raf, st.size, ArchiveUpload.generation(st))
    catch
      case e: Throwable =>
        raf.close()
        throw e

/** Upload of one archive file as `archive` batches of parts (see
  * docs/sync-protocol.md, "Archive"). Pure: the app stores the
  * [[ArchiveUpload.Progress]] with each part's outbox row.
  */
object ArchiveUpload:
  /** The archive file of an upload mark (`<reg>|up:<file>`, `upgen:`,
    * `uppart:`, `upbytes:`), if `name` is one.
    */
  def markFile(name: String): Option[String] =
    val i = name.indexOf('|')
    val rest = if i < 0 then "" else name.drop(i + 1)
    val j = rest.indexOf(':')
    Option.when(j > 0 && Set("up", "upgen", "uppart", "upbytes")(rest.take(j)))(rest.drop(j + 1)).filter(_.endsWith(".parquet"))

  /** Whether a part of a file covering `period` ([from, to)) may hold rows
    * of a deleted range: one not cut out of the files yet (`pending`), or
    * one recorded after the file was opened for the part. `opened`: the
    * deletion sequence (the largest `deleted_range` rowid, which only grows)
    * read before the file was opened; 0 for a part sealed by an earlier
    * version (not known: taken as before every deletion). `ranges`: (from,
    * to, its sequence, pending).
    */
  def heldByDeletion(period: (Long, Long), opened: Long, ranges: Seq[(Long, Long, Long, Boolean)]): Boolean =
    val (a, b) = period
    ranges.exists((x, y, seq, pending) => x < b && y > a && (pending || seq > opened))

  /** The archive file (`<table>/<name>`) a batch of stream `archive` is a
    * part of, from its plaintext (gzipped JSON Lines; see [[nextPart]]'s
    * caller), if it names one.
    */
  def fileOf(plainGz: Array[Byte]): Option[String] =
    val text = String(Crypto.gunzip(plainGz), java.nio.charset.StandardCharsets.UTF_8)
    text.linesIterator.nextOption().flatMap(l => scala.util.Try(ujson.read(l)("file").str).toOption).filter(_.endsWith(".parquet"))
  /** Archive files are sent in parts of at most this size, one batch each. */
  val PartSize: Int = 3 * 1024 * 1024

  /** Where the upload of one generation stands: `part` is the next part to
    * enqueue, `bytes` the bytes of parts `0 until part` already enqueued.
    */
  final case class Progress(gen: Long, part: Long, bytes: Long)

  /** One part to enqueue, and the progress once it is enqueued. */
  final case class Part(index: Long, parts: Long, data: Array[Byte], next: Progress):
    def last: Boolean = next.part == parts

  def generation(st: FileStat): Long =
    val h = java.security.MessageDigest.getInstance("SHA-256")
      .digest((st.size +: st.identity).mkString("/").getBytes(UTF_8))
    java.nio.ByteBuffer.wrap(h, 0, 8).getLong & Long.MaxValue

  def parts(size: Long, partSize: Int = PartSize): Long = (size + partSize - 1) / partSize

  /** The stored progress if it belongs to generation `gen`; otherwise part 0
    * (a file replaced since is sent again from the start, under new seqs).
    */
  def resume(stored: Option[Progress], gen: Long): Progress =
    stored.filter(p => p.gen == gen && p.part >= 0 && p.bytes >= 0).getOrElse(Progress(gen, 0, 0))

  /** Mark of a file handed over completely: its generation ([[markOf]]).
    * Earlier app versions marked a file with 1, whatever version they sent;
    * such a mark is of no known generation, so the file is sent once more
    * (the receiver keeps every complete generation and drops exact repeats).
    */
  val LegacyMark = 1L

  /** Whether the file's version `gen` is handed over already (`mark` 0: never).
    * A file that changed since (a day archived in portions, late rows) has
    * another generation and is sent again.
    */
  def handedOver(mark: Long, gen: Long): Boolean = mark != 0 && mark == markOf(gen)

  /** The mark stored for generation `gen` (never 0 or the legacy mark). */
  def markOf(gen: Long): Long = if gen <= LegacyMark then gen + 2 else gen

  /** The generation is complete: every part was enqueued, and their bytes add
    * up to the size of that very version.
    */
  def complete(p: Progress, size: Long, partSize: Int = PartSize): Boolean =
    p.part == parts(size, partSize) && p.bytes == size

  /** The next part of `snap` after `p`, read from the snapshot; None when
    * every part is enqueued. Throws if `p` is not of `snap`'s generation, or
    * if the last part would not complete it ([[complete]]): the file is then
    * not marked uploaded.
    */
  def nextPart(snap: ArchiveSnapshot, p: Progress, partSize: Int = PartSize): Option[Part] =
    require(p.gen == snap.generation, "progress of another generation")
    val n = parts(snap.size, partSize)
    if p.part >= n then None
    else
      val off = p.part * partSize
      if off != p.bytes then throw IllegalStateException(s"progress at part ${p.part} has ${p.bytes} bytes, expected $off")
      val data = snap.read(off, math.min(partSize.toLong, snap.size - off).toInt)
      val next = Progress(p.gen, p.part + 1, p.bytes + data.length)
      if next.part == n && !complete(next, snap.size, partSize) then
        throw IllegalStateException(s"parts add up to ${next.bytes} bytes, not ${snap.size}")
      Some(Part(p.part, n, data, next))
