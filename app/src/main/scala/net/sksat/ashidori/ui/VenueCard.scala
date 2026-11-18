package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View}
import android.widget.{FrameLayout, ImageView, LinearLayout}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The sheet of a venue picked on the map tab's map: its icon, name,
  * category and distance from the current position, and 「チェックイン」 to
  * the target the context gives ([[VenueCheckIn.target]]; with the stay
  * when it is a past one, 「チェックイン（12:14 の滞在）」; no button
  * without a target). It lies over the day's sheet, which stays as it is
  * underneath. Nothing is sent.
  *
  * `context`: the day view's selected stay, the stays of the day shown and
  * whether that is today. `onClose`: the sheet closed (the map's pick
  * ends); `onChanged`: a check-in was made or taken back.
  */
final class VenueCard(act: MainActivity, context: () => VenueCard.Context, onClose: () => Unit, onChanged: () => Unit):
  private given Ctx = act
  private var shown: Option[VenueLayer.Picked] = None
  private var target: Option[VenueCheckIn.Target] = None

  private val icon = FrameLayout(act)
  private val name = oneLine(text("", 17f, bold = true))
  private val meta = oneLine(caption(""))
  private val close = iconButton(R.drawable.ic_close, "閉じる")(hide())
  private val checkIn =
    val b = button("チェックイン")(doCheckIn())
    b.setIconResource(R.drawable.ic_checkin)
    tint(b, color(attr.tertiaryContainer), color(attr.onTertiaryContainer))
    b.setIconTint(android.content.res.ColorStateList.valueOf(color(attr.onTertiaryContainer)))
    b

  val view: LinearLayout =
    val texts = vbox(name, meta)
    val tlp = weighted()
    tlp.setMarginStart(dp(12))
    texts.setLayoutParams(tlp)
    icon.setLayoutParams(LinearLayout.LayoutParams(dp(40), dp(40)))
    val head = hbox(icon, texts, close)
    head.setGravity(Gravity.CENTER_VERTICAL)
    val actions = hbox(checkIn)
    actions.setPadding(0, dp(8), 0, 0)
    val v = vbox(head, actions)
    v.setPadding(dp(16), dp(12), dp(8), dp(12))
    val bg = GradientDrawable()
    bg.setCornerRadii(Array(dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0).map(_.toFloat))
    bg.setColor(color(attr.surface))
    v.setBackground(bg)
    v.setElevation(dp(10).toFloat)
    v.setVisibility(View.GONE)
    // Taps on the sheet stay on it (they never reach the map under it).
    v.setClickable(true)
    v

  /** Back closes the sheet while it is open. */
  private val back = new androidx.activity.OnBackPressedCallback(false):
    override def handleOnBackPressed(): Unit = hide()
  act.getOnBackPressedDispatcher.addCallback(back)

  def isOpen: Boolean = shown.nonEmpty

  /** Opens the sheet for `p` (or shows another venue in it). */
  def show(p: VenueLayer.Picked): Unit =
    val again = shown.exists(_.venue.id == p.venue.id)
    shown = Some(p)
    VenueCard.fillIcon(icon, p)
    name.setText(p.venue.name)
    refresh()
    view.setVisibility(View.VISIBLE)
    back.setEnabled(true)
    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(view, p.venue.name)
    if !again then view.post(() => name.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))

  /** The context may have changed (another stay selected, the day reloaded): the target again. */
  def refresh(): Unit = shown.foreach { p =>
    val now = System.currentTimeMillis()
    val here = LoggerState.lastFix.filter(f => now - f.time <= VenueCheckIn.HereMaxAgeMs).map(_.latLng)
    val dist = here.map(h => s"現在地から ${fmtDistance(h.distanceTo(p.venue.latLng))}")
    meta.setText((Seq(p.venue.category).filter(_.nonEmpty) ++ dist).mkString("・"))
    val c = context()
    target = VenueCheckIn.target(p.venue.latLng, c.selected, c.stays, c.today,
      LoggerState.lastFix.map(f => (f.latLng, f.time)), now)
    checkIn.setText(target match
      case Some(VenueCheckIn.Target.Stay(v, _)) => s"チェックイン（${CheckIns.stayLabel(v)}）"
      case _                                    => "チェックイン")
    checkIn.setVisibility(if target.isDefined then View.VISIBLE else View.GONE)
  }

  /** Closes the sheet (the close button, back, or a tap elsewhere on the map). */
  def hide(): Unit =
    if shown.nonEmpty then
      shown = None
      target = None
      view.setVisibility(View.GONE)
      back.setEnabled(false)
      onClose()

  /** Checks in and closes the sheet at once: a second tap cannot check in again. */
  private def doCheckIn(): Unit =
    for p <- shown; t <- target do
      val v = p.venue
      hide()
      t match
        case VenueCheckIn.Target.Stay(stay, time) =>
          CheckIns.commit(act, CheckIn(0, time, v.lat, v.lon, v.name, v.category, v.id), v, CheckIns.message(v.name, Some(stay)), onChanged)
        case VenueCheckIn.Target.Now =>
          CheckIns.commit(act, CheckIn(0, System.currentTimeMillis(), v.lat, v.lon, v.name, v.category, v.id), v,
            CheckIns.message(v.name, None), onChanged)

object VenueCard:
  /** What the day view says when a venue is picked. */
  final case class Context(selected: Option[Visit], stays: Seq[Visit], today: Boolean)

  /** The venue's icon as the map draws it, on a disc of its colour. */
  def fillIcon(host: FrameLayout, p: VenueLayer.Picked)(using c: Ctx): Unit =
    host.removeAllViews()
    val col = scala.util.Try(android.graphics.Color.parseColor(p.color)).getOrElse(0xff6f6a66)
    val bg = GradientDrawable()
    bg.setShape(GradientDrawable.OVAL)
    bg.setColor(col & 0x00ffffff | 0x33000000)
    bg.setStroke(dp(2), col)
    host.setBackground(bg)
    host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val img = ImageView(c)
    p.icon match
      case Some(b) => img.setImageBitmap(b)
      case None =>
        val dot = GradientDrawable()
        dot.setShape(GradientDrawable.OVAL)
        dot.setColor(col)
        img.setImageDrawable(dot)
    host.addView(img, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
