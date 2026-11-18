// See LegacySendGate.scala.
package ashidori.core.legacy

/** Which base map each map draws under 「位置情報を外部サービスに送らない」.
  *
  * Online tiles reveal the area being viewed, so while the setting is on
  * every map and snapshot uses the on-device basemap. An online map is
  * shown only on an interactive map surface where the user explicitly
  * chose it: the consent remembered for that surface ([[SendGate.consent]]),
  * which names the online map chosen there. Another surface does not
  * inherit it, and snapshots (feed, period, other devices) and picker
  * thumbnails at the current view never go online then.
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

  /** An interactive map on a surface with `consent`, the online map the
    * user chose there (setting on; ignored with it off). A consent naming
    * the offline map, or one no longer known (None), keeps it offline.
    * Off screen (its screen hidden, or the app in the background) it holds
    * no online style at all, so none of its requests are left to go out
    * while another map has the network: it comes back to the consent when
    * shown again.
    */
  def interactive[S](block: Boolean, chosen: S, offline: S, offlineAvailable: Boolean,
                     consent: Option[S], onScreen: Boolean): Outcome[S] =
    val style =
      if !block then chosen
      else if !onScreen then offline
      else consent.getOrElse(offline)
    resolve(style, offline, offlineAvailable)

  /** A custom XYZ tile URL the app accepts: http(s), with {z} {x} {y}, and
    * no whitespace or control characters (it is stored and shown as one line).
    */
  def validTileUrl(u: String): Boolean =
    (u.startsWith("https://") || u.startsWith("http://")) &&
      Seq("{z}", "{x}", "{y}").forall(u.contains) &&
      !u.exists(c => c.isWhitespace || c.isControl)

  /** The online map an explicit request for detail asks for: the chosen
    * one, or the last online choice (`lastOnline`) when the offline map is
    * chosen. What is remembered as the surface's consent.
    */
  def detailMap[S](chosen: S, lastOnline: S, offline: S): S =
    if chosen == offline then lastOnline else chosen

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
