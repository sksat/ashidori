package ashidori.core

/** Which base map each map draws under 「位置情報を外部サービスに送らない」.
  *
  * Online tiles reveal the area being viewed, so while the setting is on
  * every map and snapshot uses the on-device basemap. The user's chosen
  * online map is shown only in an interactive map where they explicitly
  * asked for it ("detailed"), and only while it is on screen (see [[SendGate]]). Snapshots
  * (feed, period, other devices) and picker thumbnails never go online then.
  * With the setting off, the user's choice applies everywhere.
  *
  * The on-device basemap is downloaded separately and may be missing
  * (`offlineAvailable` false). A map that would use it then has
  * [[Outcome.NoBasemap]]: it draws its routes and stays without a base map,
  * and never falls back to an online one by itself.
  *
  * `S` is the app's base map type; `offline` is its on-device value.
  */
object BaseMapPolicy:
  /** What a map draws under its routes and stays. */
  enum Outcome[+S]:
    case Draw(style: S)
    /** The on-device basemap is needed but missing: no base map at all. */
    case NoBasemap

  import Outcome.*

  private def resolve[S](style: S, offline: S, offlineAvailable: Boolean): Outcome[S] =
    if style == offline && !offlineAvailable then NoBasemap else Draw(style)

  /** An interactive map. `lastOnline` is the online map to use when the
    * user chose the offline one but asks for detail.
    */
  def interactive[S](block: Boolean, chosen: S, lastOnline: S, offline: S, offlineAvailable: Boolean,
                     detailed: Boolean): Outcome[S] =
    val style =
      if !block then chosen
      else if !detailed then offline
      else if chosen == offline then lastOnline
      else chosen
    resolve(style, offline, offlineAvailable)

  /** A still image rendered without the user looking at that area yet. */
  def snapshot[S](block: Boolean, chosen: S, offline: S, offlineAvailable: Boolean): Outcome[S] =
    resolve(if block then offline else chosen, offline, offlineAvailable)

  /** Whether the picker may render a thumbnail of `style` at the current view. */
  def thumbnail[S](block: Boolean, style: S, offline: S, offlineAvailable: Boolean): Boolean =
    if style == offline then offlineAvailable else !block

  /** Whether an interactive map without a base map offers the basemap
    * download and the per-view online map: only when the setting is what
    * keeps it from the user's online map. With the setting off the user
    * chose the offline map themselves, and the settings screen offers the
    * download.
    */
  def offerAlternatives[S](block: Boolean, outcome: Outcome[S]): Boolean =
    block && outcome == NoBasemap

  /** Whether the map library may use the network at all: only with the
    * setting off, or while a map the user switched to detail is open.
    */
  def network(block: Boolean, detailedOpen: Boolean): Boolean =
    !block || detailedOpen
