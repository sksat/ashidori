package ashidori.core

/** One change of a place made from the timeline: its name or its kind. It
  * is applied to the place as stored at that moment, and taken back
  * (取り消し) only in the field it changed and only while that field still
  * holds what it set, so other changes made meanwhile are kept.
  */
enum PlaceEdit:
  /** A new name, the venue it came from ("" for a typed name), and where
    * each name came from ([[NameSource]]).
    */
  case Rename(before: String, beforeId: String, after: String, afterId: String,
              beforeSource: NameSource = NameSource.Unknown, afterSource: NameSource = NameSource.Unknown)
  case SetKind(before: PlaceKind, after: PlaceKind)

  def applyTo(p: Place): Place = this match
    case Rename(_, _, n, id, _, src) => p.copy(name = n, externalId = id, nameSource = src)
    case SetKind(_, k)       => p.copy(kind = k)

  /** `p` with this change taken back, or None when its field no longer
    * holds what this change set (a later change wins).
    */
  def revert(p: Place): Option[Place] = this match
    case Rename(b, bid, n, id, bs, as) =>
      Option.when(p.name == n && p.externalId == id && p.nameSource == as)(p.copy(name = b, externalId = bid, nameSource = bs))
    case SetKind(b, k)         => Option.when(p.kind == k)(p.copy(kind = b))

  /** Nothing changes (the field already holds the value). */
  def isNoop: Boolean = this match
    case Rename(b, bid, n, id, bs, as) => b == n && bid == id && bs == as
    case SetKind(b, k)         => b == k

object PlaceEdit:
  /** Renames `p`; a typed name keeps the venue id the place had. A name
    * given with a venue id is the venue's ([[NameSource.FromVenue]]); any
    * other is the user's.
    */
  def rename(p: Place, name: String, externalId: Option[String] = None): PlaceEdit =
    val src = if externalId.exists(_.nonEmpty) then NameSource.FromVenue else NameSource.Typed
    Rename(p.name, p.externalId, name, externalId.getOrElse(p.externalId), p.nameSource, src)
  def kind(p: Place, k: PlaceKind): PlaceEdit = SetKind(p.kind, k)
