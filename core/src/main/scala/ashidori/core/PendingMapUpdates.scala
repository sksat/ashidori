package ashidori.core

import scala.collection.mutable

/** What a map that is not ready yet (its style loading, or its view
  * dropped) is asked to do, kept small however long it waits: the latest
  * update of each kind (data, live tail, a layer's visibility, ...) in the
  * order they were last set, and only the last camera intent (fit, focus,
  * show a path), which is applied after every update, so the camera ends
  * where it was last asked to be, over the latest data. Not thread-safe.
  */
final class PendingMapUpdates[U, C]:
  private val updates = mutable.LinkedHashMap.empty[String, U]
  private var cam: Option[C] = None

  /** Replaces an earlier update of `kind`; it now runs after the others. */
  def update(kind: String, u: U): Unit =
    updates.remove(kind)
    updates(kind) = u

  def has(kind: String): Boolean = updates.contains(kind)

  /** Replaces any earlier camera intent. */
  def camera(c: C): Unit = cam = Some(c)

  def size: Int = updates.size + cam.size

  /** The updates in order and the camera intent to apply after them; empties this. */
  def drain(): (Vector[U], Option[C]) =
    val r = (updates.values.toVector, cam)
    updates.clear()
    cam = None
    r
