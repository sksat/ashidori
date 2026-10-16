package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View}
import android.widget.{LinearLayout, TextView}
import net.sksat.ashidori.*
import Ui.*

/** The small chip on a map drawn without a base map (the offline map is
  * needed and missing): 「簡易地図がありません」 and what can be done, in one
  * line — 「取得」 (starts the download), 「取得中 nn%」, or 「取得できません」
  * in the error colour after a failed download (opens the 簡易地図 dialog
  * with the reason). Nothing happens until it is tapped, and the download
  * sends no location ([[BasemapUi.PrivacyNote]]).
  *
  * Every chip follows the download's state ([[NoBasemapChip.refreshAll]]).
  * Main thread only.
  */
final class NoBasemapChip(act: MainActivity) extends LinearLayout(act):
  private given Ctx = act
  private val title = text("", 12f, colorAttr = attr.onSurfaceVariant)
  private val action = text("", 12f, bold = true)
  setOrientation(LinearLayout.HORIZONTAL)
  setGravity(Gravity.CENTER_VERTICAL)
  title.setSingleLine(true)
  action.setSingleLine(true)
  addView(title)
  private val alp = Ui.wrap
  alp.setMarginStart(dp(8))
  addView(action, alp)
  setPadding(dp(10), dp(6), dp(10), dp(6))
  private val bg = GradientDrawable()
  bg.setCornerRadius(dp(8).toFloat)
  bg.setColor((color(attr.surface) & 0x00ffffff) | 0xe6000000)
  bg.setStroke(dp(1), color(attr.outlineVariant))
  setBackground(bg)
  setVisibility(View.GONE)

  /** Shows the chip (with the current state) or hides it. */
  def show(on: Boolean): Unit =
    if on then refresh()
    setVisibility(if on then View.VISIBLE else View.GONE)

  def refresh(): Unit =
    val st = BasemapDownload.status
    title.setText(BasemapUi.chipTitle(st))
    BasemapUi.chipAction(st) match
      case None =>
        action.setVisibility(View.GONE)
        setOnClickListener(null)
        setClickable(false)
        setContentDescription(title.getText)
      case Some(a) =>
        action.setVisibility(View.VISIBLE)
        val (label, failed) = a match
          case BasemapUi.ChipAction.Download => ("取得", false)
          case BasemapUi.ChipAction.Details(l, f) => (l, f)
        action.setText(label)
        action.setTextColor(color(if failed then attr.error else attr.primary))
        setContentDescription(s"${title.getText}。$label")
        setOnClickListener(_ => a match
          case BasemapUi.ChipAction.Download => BasemapUi.requestDownload(act)
          case _ => BasemapUi.showDialog(act))

  NoBasemapChip.chips.add(this)

object NoBasemapChip:
  private val chips = java.util.Collections.newSetFromMap(java.util.WeakHashMap[NoBasemapChip, java.lang.Boolean]())
  /** Kept for the process, like the chips' screens. */
  private val stop = BasemapDownload.listen(() => refreshAll())
  def refreshAll(): Unit =
    import scala.jdk.CollectionConverters.*
    chips.asScala.toSeq.filter(_.getVisibility == View.VISIBLE).foreach(_.refresh())

/** The faint square grid under a picture drawn without a base map. */
object MapGrid:
  def paint(c: android.content.Context): android.graphics.Paint =
    given Ctx = c
    val p = android.graphics.Paint()
    p.setStrokeWidth(dp(1).toFloat)
    p.setColor((color(attr.outlineVariant) & 0x00ffffff) | 0x66000000)
    p

  def draw(canvas: android.graphics.Canvas, w: Float, h: Float, paint: android.graphics.Paint)(using c: Ctx): Unit =
    val step = dp(24).toFloat
    var x = step
    while x < w do
      canvas.drawLine(x, 0, x, h, paint)
      x += step
    var y = step
    while y < h do
      canvas.drawLine(0, y, w, y, paint)
      y += step
