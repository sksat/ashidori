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
  * holds the old or the new records, the keys that write changed are
  * unknown: they read as absent ([[get]]), and [[view]] tells them apart
  * from keys that are absent on disk, for a caller to whom absent is not the
  * safe reading. The next write first reads the file again: if it can, the
  * records found there are what the write starts from, so a write never puts
  * back a value that the failed write may have replaced. If it still cannot
  * read the file, a write that does not set every unknown key is refused,
  * since it would have to guess them. A file that does not parse or whose
  * checksum does not match reads as empty at start; [[View.unreadable]]
  * tells that apart from a missing file (a first run).
  *
  * The file is owned by this instance: nothing else may write it. Writes
  * are serialized and blocking.
  */
final class DurableMap(file: DurableFile):
  import DurableMap.*

  private val writeLock = Object()
  @volatile private var state: View = readBack() match
    case Some(m) => View(m, Set.empty)
    case None    => View(Map.empty, Set.empty, unreadable = true)

  /** Reads the file again if it could not be read at start ([[View.unreadable]])
    * and nothing was written since. True when its records are known now.
    */
  def reload(): Boolean = writeLock.synchronized {
    if !state.unreadable then true
    else readBack() match
      case Some(m) =>
        state = View(m, Set.empty)
        true
      case None => false
  }

  /** The records in the file; None when it cannot be read or does not parse. */
  private def readBack(): Option[Map[String, Value]] =
    try file.read() match
      case None    => Some(Map.empty)
      case Some(b) => decode(b)
    catch case _: Exception => None

  /** The records as one consistent snapshot, with the unknown keys. Never blocks. */
  def view: View = state

  /** The value of `k`, if it is on disk (absent while unknown). Never blocks. */
  def get(k: String): Option[Value] = state.get(k)

  def getString(k: String): Option[String] = state.getString(k)
  def getBoolean(k: String, default: Boolean): Boolean = state.getBoolean(k, default)

  /** All values that are on disk. */
  def snapshot: Map[String, Value] =
    val s = state
    s.values -- s.unknown

  /** Writes `values` (`null`: remove the key) and returns once they are on
    * disk. Throws [[IOException]] otherwise; the previous values then stay.
    */
  def commit(values: (String, Value | Null)*): Unit =
    write(Some(values.map(_._1).toSet), old => values.foldLeft(old) {
      case (m, (k, null))       => m - k
      case (m, (k, v: String))  => m.updated(k, v)
      case (m, (k, v: Boolean)) => m.updated(k, v)
    })

  /** Removes every key and returns once that is on disk; throws like [[commit]]. */
  def clear(): Unit = write(None, _ => Map.empty)

  /** The records a write of `keys` (None: all) starts from. After a failed
    * write that could not be read back, the file is read again and its
    * records count. If it still cannot be read, the write may only go ahead
    * when it sets every unknown key itself; the others keep their values.
    */
  private def settle(keys: Option[Set[String]]): Map[String, Value] =
    val s = state
    if s.unknown.isEmpty then s.values
    else readBack() match
      case Some(m) =>
        state = View(m, Set.empty)
        m
      case None if keys.forall(ks => s.unknown.subsetOf(ks)) => s.values
      case None =>
        throw IOException(s"the file cannot be read back; not writing a guess for ${s.unknown.toSeq.sorted.mkString(", ")}")

  private def write(keys: Option[Set[String]], change: Map[String, Value] => Map[String, Value]): Unit =
    writeLock.synchronized {
      val before = settle(keys)
      val next = change(before)
      val changed = (before.keySet ++ next.keySet).filter(k => before.get(k) != next.get(k))
      try
        file.replace(encode(next))
        state = View(next, Set.empty)
      catch
        case e: Exception =>
          // Old or new records: which one decides what may be read.
          val written = readBack() match
            case Some(m) if m == next   => true
            case Some(m) if m == before =>
              state = View(before, Set.empty)
              false
            case _ =>
              state = View(before, state.unknown ++ changed, state.unreadable)
              false
          if written then state = View(next, Set.empty)
          else throw e match
            case io: IOException => io
            case other           => IOException("write failed", other)
    }

object DurableMap:
  type Value = String | Boolean

  /** A snapshot of the records. `values` holds, for an unknown key, its value
    * before the write that failed; [[get]] does not return it. `unreadable`:
    * the file existed but could not be read or did not parse at start, and
    * nothing was written since; the records read as empty, for a caller to
    * whom that is the safe reading (a first run is a missing file instead).
    */
  final case class View(values: Map[String, Value], unknown: Set[String], unreadable: Boolean = false):
    def get(k: String): Option[Value] = if unknown(k) then None else values.get(k)
    def getString(k: String): Option[String] = get(k).collect { case v: String => v }
    def getBoolean(k: String, default: Boolean): Boolean = get(k).collect { case v: Boolean => v }.getOrElse(default)
    /** The file may hold either value of `k` (a failed write could not be read back). */
    def isUnknown(k: String): Boolean = unknown(k)

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
