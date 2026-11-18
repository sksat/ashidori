package ashidori.core

/** Which base map each map draws, under the rules of the map destinations
  * ([[SendGate]], docs/design/outbound-and-offline.md 4.7).
  *
  * Online tiles reveal the area being viewed. `when` gives, for each
  * online map, when its destination may be sent to now ([[SendGate.when]]:
  * never while paused). A map whose destination is sent to automatically is
  * used everywhere it is chosen. One sent to on action only is shown only on
  * an interactive map surface where the user explicitly chose it: the
  * consent remembered for that surface ([[SendGate.consent]]). Another
  * surface does not inherit it, and snapshots (feed, period, other devices)
  * and picker thumbnails then use the on-device basemap.
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

  /** `s` is an online map sent to automatically. */
  private def auto[S](when: S => When, s: S, offline: S): Boolean = s != offline && when(s) == When.Auto

  /** An interactive map on a surface with `consent`, the online map the
    * user chose there. The chosen map if its destination is automatic;
    * otherwise the consent while it is on action, else the offline map.
    * Off screen (its screen hidden, or the app in the background) it holds
    * no on-action online style at all, so none of its requests are left to
    * go out while another map has the network: it comes back to the consent
    * when shown again.
    */
  def interactive[S](when: S => When, chosen: S, offline: S, offlineAvailable: Boolean,
                     consent: Option[S], onScreen: Boolean): Outcome[S] =
    val style =
      if auto(when, chosen, offline) then chosen
      else if !onScreen then offline
      else consent.filter(c => c != offline && when(c) == When.OnAction).getOrElse(offline)
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
    * chosen; if that one's destination is never sent to, the first of
    * `others` that is not. None when every online map is never sent to.
    * What is remembered as the surface's consent.
    */
  def detailMap[S](when: S => When, chosen: S, lastOnline: S, offline: S, others: Seq[S]): Option[S] =
    val wanted = if chosen == offline then lastOnline else chosen
    (wanted +: others).find(s => s != offline && when(s) != When.Never)

  /** A still image rendered without the user looking at that area yet: the
    * chosen map only if its destination is automatic.
    */
  def snapshot[S](when: S => When, chosen: S, offline: S, offlineAvailable: Boolean): Outcome[S] =
    resolve(if auto(when, chosen, offline) then chosen else offline, offline, offlineAvailable)

  /** Whether the picker may render a thumbnail of `style` at the current
    * view: each candidate by its own destination's rule (automatic only).
    */
  def thumbnail[S](when: S => When, style: S, offline: S, offlineAvailable: Boolean): Boolean =
    if style == offline then offlineAvailable else when(style) == When.Auto

  /** Whether an interactive map without a base map offers the basemap
    * download and the per-view online map: only when the map an explicit
    * request would show (`detail`) is one sent to on action. With an
    * automatic map the user chose the offline map themselves, and the
    * settings screen offers the download.
    */
  def offerAlternatives[S](when: S => When, detail: Option[S], outcome: Outcome[S]): Boolean =
    outcome == NoBasemap && detail.exists(when(_) == When.OnAction)

  /** Whether an interactive map offers the button that switches its surface
    * to and from the online map: while the surface holds a consent, or while
    * the map an explicit request would show (`detail`) is on action; not
    * while the card without a base map offers it (`offered`).
    */
  def offerDetail[S](when: S => When, detail: Option[S], consented: Boolean, offered: Boolean): Boolean =
    !offered && (consented || detail.exists(when(_) == When.OnAction))
