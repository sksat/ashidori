package net.sksat.ashidori.sync

import android.content.Context
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.{AshidoriApp, Log}
import ashidori.core.sync.{DurableFile, DurableMap}

/** Values that are readable only once they are on disk: a [[DurableMap]] in
  * its own file (`no_backup/durable/<name>.json`), which nothing else writes.
  *
  * `SharedPreferences.commit()` updates its in-memory map before the disk
  * write and keeps that update when the write fails, and any later write to
  * the same file (by anyone) persists it; that is why these values no longer
  * live in SharedPreferences. Here a reader sees the records of the last
  * write that reached the disk, and a failed write leaves nothing to undo.
  *
  * `legacy`: keys this store took over from the SharedPreferences file of
  * the same name (None: all of them). They are copied once, when the store
  * has no file yet, and then removed there.
  *
  * Writes are serialized and blocking: call them off the main thread. Reads
  * never block.
  */
final class DurablePrefs(name: String, legacy: Option[Set[String]] = None):
  private lazy val map: DurableMap =
    val f = java.io.File(AshidoriApp.app.getNoBackupFilesDir, s"durable/$name.json")
    val fresh = !f.exists()
    val m = DurableMap(DurableFile(f))
    if fresh then migrate(m)
    m

  /** Copies the values written by an earlier version. At process start the
    * SharedPreferences map is what its file holds, and no code writes these
    * keys there any more, so what is copied was on disk.
    */
  private def migrate(m: DurableMap): Unit =
    val prefs = AshidoriApp.app.getSharedPreferences(name, Context.MODE_PRIVATE)
    val old = prefs.getAll.asScala.toMap.filter((k, _) => legacy.forall(_(k))).collect {
      case (k, v: String)            => k -> (v: String | Boolean | Null)
      case (k, v: java.lang.Boolean) => k -> (v.booleanValue: String | Boolean | Null)
    }
    if old.nonEmpty then
      try
        m.commit(old.toSeq*)
        val e = prefs.edit()
        old.keys.foreach(e.remove)
        e.commit()
      catch case e: Exception => Log.w(s"moving $name to its own file failed", e)

  def getString(k: String): Option[String] = map.getString(k)
  def getBoolean(k: String, default: Boolean): Boolean = map.getBoolean(k, default)
  def store: DurableMap = map

  /** Writes `values` (`null`: remove the key) and returns once they are on
    * disk. Throws [[java.io.IOException]] otherwise, with the previous values
    * in place.
    */
  def commit(values: (String, String | Boolean | Null)*): Unit =
    try map.commit(values*)
    catch
      case e: java.io.IOException =>
        Log.w(s"write of $name failed", e)
        throw java.io.IOException(s"設定（$name）を保存できませんでした", e)

  /** Removes every key and returns once that is on disk; throws like [[commit]]. */
  def clear(): Unit =
    try map.clear()
    catch
      case e: java.io.IOException =>
        Log.w(s"clearing $name failed", e)
        throw java.io.IOException(s"設定（$name）を消去できませんでした", e)
