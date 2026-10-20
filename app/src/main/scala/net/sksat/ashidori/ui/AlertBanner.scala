package net.sksat.ashidori.ui

import android.text.TextUtils
import android.view.{Gravity, View}
import com.google.android.material.card.MaterialCardView
import net.sksat.ashidori.*
import Ui.*

/** Red banner at the top of the status tab while there was trouble in the
  * last 24 hours (abnormal exits, data not saved): one line and a link to
  * 端末診断. Set when the screen is shown, not while it is open, so the
  * content below never moves under the user's finger.
  */
final class AlertBanner(act: MainActivity)(using Ctx):
  private val line = text("", 14f, colorAttr = attr.onErrorContainer)
  line.setSingleLine(true)
  line.setEllipsize(TextUtils.TruncateAt.END)
  private val link = text("端末診断", 14f, bold = true, colorAttr = attr.onErrorContainer)
  link.setPaddingRelative(dp(12), 0, 0, 0)

  val view: MaterialCardView =
    val row = hbox(line, link)
    row.setGravity(Gravity.CENTER_VERTICAL)
    line.setLayoutParams(weighted())
    val c = card(row)
    c.setCardBackgroundColor(color(attr.errorContainer))
    // A clickable card draws its own ripple.
    c.setOnClickListener(_ => act.openDiagnostics())
    c.setVisibility(View.GONE)
    c

  /** Call before the screen shows. */
  def refresh(): Unit =
    Alerts.issue() match
      case Some(s) =>
        line.setText(s)
        view.setVisibility(View.VISIBLE)
      case None => view.setVisibility(View.GONE)
