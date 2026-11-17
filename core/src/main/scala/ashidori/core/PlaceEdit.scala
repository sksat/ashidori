package ashidori.core

/** One change of a place made from the timeline: its name or its kind. It
  * is applied to the place as stored at that moment, and taken back
  * (取り消し) only in the field it changed and only while that field still
  * holds what it set, so other changes made meanwhile are kept.
  */
enum PlaceEdit:
  /** A new name, and the venue it came from ("" for a typed name). */
  case Rename(before: String, beforeId: String, after: String, afterId: String)
  case SetKind(before: PlaceKind, after: PlaceKind)

  def applyTo(p: Place): Place = this match
    case Rename(_, _, n, id) => p.copy(name = n, externalId = id)
    case SetKind(_, k)       => p.copy(kind = k)

  /** `p` with this change taken back, or None when its field no longer
    * holds what this change set (a later change wins).
    */
  def revert(p: Place): Option[Place] = this match
    case Rename(b, bid, n, id) => Option.when(p.name == n && p.externalId == id)(p.copy(name = b, externalId = bid))
    case SetKind(b, k)         => Option.when(p.kind == k)(p.copy(kind = b))

  /** Nothing changes (the field already holds the value). */
  def isNoop: Boolean = this match
    case Rename(b, bid, n, id) => b == n && bid == id
    case SetKind(b, k)         => b == k

object PlaceEdit:
  /** Renames `p`; a typed name keeps the venue id the place had. */
  def rename(p: Place, name: String, externalId: Option[String] = None): PlaceEdit =
    Rename(p.name, p.externalId, name, externalId.getOrElse(p.externalId))
  def kind(p: Place, k: PlaceKind): PlaceEdit = SetKind(p.kind, k)
