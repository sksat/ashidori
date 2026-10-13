package net.sksat.ashidori.sync

import android.content.{Context, SharedPreferences}
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.{AshidoriApp, Log}

/** Preference values that are readable only once they are on disk.
  *
  * `SharedPreferences.commit()` updates the in-memory map first and keeps that
  * update when the disk write fails, so a plain read after a failed commit
  * returns a value that a restart loses. Here, a value being written reads as
  * absent until the write is confirmed. A failed write is undone in memory
  * (back to the previous values, which the disk still has) before it is
  * reported; if even that fails, its keys read as absent for the rest of the
  * process. A value read through this class is therefore on disk.
  *
  * Writes are serialized and blocking: call them off the main thread. Reads
  * never block.
  */
final class DurablePrefs(name: String):
  /** The underlying preferences, for values that need no such guarantee. */
  def raw: SharedPreferences = AshidoriApp.app.getSharedPreferences(name, Context.MODE_PRIVATE)

  private val writeLock = Object()
  // Guarded by `this`. A key is hidden while it is being written, or for good
  // after a failed write that could not be undone; `All` stands for every key.
  private val All = "\u0000all"
  private var writing = Set.empty[String]
  private var lost = Set.empty[String]

  private def visible(k: String): Boolean = synchronized {
    !writing(k) && !writing(All) && !lost(k) && !lost(All)
  }

  def getString(k: String): Option[String] = if visible(k) then Option(raw.getString(k, null)) else None
  def getBoolean(k: String, default: Boolean): Boolean = if visible(k) then raw.getBoolean(k, default) else default

  /** Writes `values` (`null`: remove the key) and returns once they are on
    * disk. Throws [[java.io.IOException]] otherwise, with the previous values
    * in place.
    */
  def commit(values: (String, String | Boolean | Null)*): Unit = write(values.map(_._1).toSet) { e =>
    values.foreach {
      case (k, null)       => e.remove(k)
      case (k, v: String)  => e.putString(k, v)
      case (k, v: Boolean) => e.putBoolean(k, v)
    }
  }

  /** Removes every key and returns once that is on disk; throws like [[commit]]. */
  def clear(): Unit = write(Set(All))(_.clear())

  private def write(keys: Set[String])(edit: SharedPreferences.Editor => Unit): Unit = writeLock.synchronized {
    val p = raw
    val before = p.getAll.asScala.toMap
    synchronized { writing = keys }
    val ok =
      try
        val e = p.edit()
        edit(e)
        e.commit()
      catch
        case e: Exception =>
          Log.w(s"write of $name failed", e)
          false
    if ok then synchronized {
      lost = if keys(All) then Set.empty else lost -- keys
      writing = Set.empty
    }
    else
      val undone =
        try
          restore(p, if keys(All) then before.keySet ++ p.getAll.asScala.keySet else keys, before)
          true
        catch
          case e: Exception =>
            Log.w(s"undoing a failed write of $name failed", e)
            false
      synchronized {
        if !undone then lost ++= keys
        writing = Set.empty
      }
      throw java.io.IOException(s"設定（$name）を保存できませんでした")
  }

  /** Puts `keys` back to `before` in memory. commit() updates memory even if
    * its disk write fails, and the disk still has these values.
    */
  private def restore(p: SharedPreferences, keys: Set[String], before: Map[String, ?]): Unit =
    val e = p.edit()
    keys.foreach { k =>
      before.get(k) match
        case Some(v: String)            => e.putString(k, v)
        case Some(v: java.lang.Boolean) => e.putBoolean(k, v)
        case Some(v: java.lang.Long)    => e.putLong(k, v)
        case Some(v: java.lang.Integer) => e.putInt(k, v)
        case Some(v: java.lang.Float)   => e.putFloat(k, v)
        case Some(v: java.util.Set[?])  => e.putStringSet(k, v.asInstanceOf[java.util.Set[String]])
        case _                          => e.remove(k)
    }
    e.commit()
