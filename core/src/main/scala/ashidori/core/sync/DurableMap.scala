package ashidori.core.sync

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8

/** Where a [[DurableMap]] keeps its records: one file, replaced as a whole. */
trait DurableFile:
  /** The file's content; None when there is none. */
  def read(): Option[Array[Byte]]

  /** Replaces the content and returns once the new content is on disk.
    * After a throw or a crash the file holds either the old or the new
    * content, never a mix (write a temporary file, sync it, rename it over).
    */
  def replace(content: Array[Byte]): Unit

object DurableFile:
  /** `f`, replaced through `f.tmp` (synced, then renamed over `f`). */
  def apply(f: java.io.File): DurableFile = new DurableFile:
    def read(): Option[Array[Byte]] =
      if !f.exists() then None else Some(java.nio.file.Files.readAllBytes(f.toPath))

    def replace(content: Array[Byte]): Unit =
      f.getParentFile.mkdirs()
      val tmp = java.io.File(f.getParentFile, f.getName + ".tmp")
      val out = java.io.FileOutputStream(tmp)
      try
        out.write(content)
        out.getFD.sync()
      finally out.close()
      java.nio.file.Files.move(tmp.toPath, f.toPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      // The rename itself reaches the disk with the directory; not every
      // platform lets a directory be synced, so this is best effort (as
      // with SharedPreferences, which syncs the file only).
      try
        val dir = java.nio.channels.FileChannel.open(f.getParentFile.toPath, java.nio.file.StandardOpenOption.READ)
        try dir.force(true)
        finally dir.close()
      catch case _: Exception => ()

/** A small key-value store whose reads return only values that are on disk.
  *
  * All records live in one file that is replaced as a whole ([[DurableFile]]).
  * Readers see an immutable snapshot, replaced only after the file holding
  * it was written: a value being written, or whose write failed, is never
  * returned, and a read never waits for disk I/O. Since the file holds
  * either the old or the new records after a failure or a crash, there is
  * nothing to undo, and after a restart the file is the truth.
  *
  * When a write fails and reading the file back cannot tell whether it
  * holds the old or the new records, the keys that write changed read as
  * absent until a later write succeeds (it writes every record, so the file
  * then matches the snapshot again). A file that does not parse or whose
  * checksum does not match reads as empty.
  *
  * The file is owned by this instance: nothing else may write it. Writes
  * are serialized and blocking.
  */
final class DurableMap(file: DurableFile):
  import DurableMap.*

  private final case class State(values: Map[String, Value], unknown: Set[String])

  private val writeLock = Object()
  @volatile private var state: State = State(load().getOrElse(Map.empty), Set.empty)

  private def load(): Option[Map[String, Value]] =
    try file.read().flatMap(decode)
    catch case _: Exception => None

  /** The value of `k`, if it is on disk. Never blocks. */
  def get(k: String): Option[Value] =
    val s = state
    if s.unknown(k) then None else s.values.get(k)

  def getString(k: String): Option[String] = get(k).collect { case v: String => v }
  def getBoolean(k: String, default: Boolean): Boolean = get(k).collect { case v: Boolean => v }.getOrElse(default)

  /** All values that are on disk. */
  def snapshot: Map[String, Value] =
    val s = state
    s.values -- s.unknown

  /** Writes `values` (`null`: remove the key) and returns once they are on
    * disk. Throws [[IOException]] otherwise; the previous values then stay.
    */
  def commit(values: (String, Value | Null)*): Unit =
    write(old => values.foldLeft(old) {
      case (m, (k, null))       => m - k
      case (m, (k, v: String))  => m.updated(k, v)
      case (m, (k, v: Boolean)) => m.updated(k, v)
    })

  /** Removes every key and returns once that is on disk; throws like [[commit]]. */
  def clear(): Unit = write(_ => Map.empty)

  private def write(change: Map[String, Value] => Map[String, Value]): Unit = writeLock.synchronized {
    val before = state
    val next = change(before.values)
    val changed = (before.values.keySet ++ next.keySet).filter(k => before.values.get(k) != next.get(k))
    try
      file.replace(encode(next))
      state = State(next, Set.empty)
    catch
      case e: Exception =>
        // Old or new records: which one decides what may be read.
        val written = load() match
          case Some(m) if m == next => true
          case Some(m) if m == before.values => false
          case _ =>
            state = before.copy(unknown = before.unknown ++ changed)
            false
        if written then state = State(next, Set.empty)
        else throw e match
          case io: IOException => io
          case other           => IOException("write failed", other)
  }

object DurableMap:
  type Value = String | Boolean

  private[sync] def encode(m: Map[String, Value]): Array[Byte] =
    val values = ujson.Obj.from(m.toSeq.sortBy(_._1).map {
      case (k, v: String)  => k -> ujson.Str(v)
      case (k, v: Boolean) => k -> ujson.Bool(v)
    })
    val body = ujson.write(values)
    ujson.write(ujson.Obj("v" -> 1, "sha256" -> Crypto.sha256Hex(body.getBytes(UTF_8)), "values" -> body)).getBytes(UTF_8)

  private[sync] def decode(b: Array[Byte]): Option[Map[String, Value]] =
    val js = ujson.read(String(b, UTF_8))
    val body = js("values").str
    if js("v").num != 1 || Crypto.sha256Hex(body.getBytes(UTF_8)) != js("sha256").str then None
    else
      Some(ujson.read(body).obj.toMap.collect {
        case (k, ujson.Str(s))  => k -> (s: Value)
        case (k, ujson.Bool(v)) => k -> (v: Value)
      })
