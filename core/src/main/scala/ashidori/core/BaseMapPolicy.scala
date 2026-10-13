package ashidori.core

/** Which base map each map draws under 「位置情報を外部サービスに送らない」.
  *
  * Online tiles reveal the area being viewed, so while the setting is on
  * every map and snapshot uses the on-device basemap. The user's chosen
  * online map is shown only in an interactive map where they explicitly
  * asked for it ("detailed"), and only until that map is closed. Snapshots
  * (feed, period, other devices) and picker thumbnails never go online then.
  * With the setting off, the user's choice applies everywhere.
  *
  * `S` is the app's base map type; `offline` is its on-device value.
  */
object BaseMapPolicy:
  /** An interactive map. `lastOnline` is the online map to use when the
    * user chose the offline one but asks for detail.
    */
  def interactive[S](block: Boolean, chosen: S, lastOnline: S, offline: S, detailed: Boolean): S =
    if !block then chosen
    else if !detailed then offline
    else if chosen == offline then lastOnline
    else chosen

  /** A still image rendered without the user looking at that area yet. */
  def snapshot[S](block: Boolean, chosen: S, offline: S): S =
    if block then offline else chosen

  /** Whether the picker may render a thumbnail of `style` at the current view. */
  def thumbnail[S](block: Boolean, style: S, offline: S): Boolean =
    !block || style == offline

  /** Whether the map library may use the network at all: only with the
    * setting off, or while a map the user switched to detail is open.
    */
  def network(block: Boolean, detailedOpen: Boolean): Boolean =
    !block || detailedOpen
