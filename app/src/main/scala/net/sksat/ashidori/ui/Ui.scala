package net.sksat.ashidori.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{FrameLayout, LinearLayout, ScrollView, TextView}
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Tiny helpers for building views in code (no XML layouts, no Compose). */
object Ui:
  type Ctx = Context

  def dp(n: Float)(using c: Ctx): Int = (n * c.getResources.getDisplayMetrics.density).round

  def color(attr: Int)(using c: Ctx): Int = MaterialColors.getColor(c, attr, 0xff808080)

  object attr:
    val primary: Int = androidx.appcompat.R.attr.colorPrimary
    val onSurface: Int = com.google.android.material.R.attr.colorOnSurface
    val onSurfaceVariant: Int = com.google.android.material.R.attr.colorOnSurfaceVariant
    val surface: Int = com.google.android.material.R.attr.colorSurface
    val surfaceContainer: Int = com.google.android.material.R.attr.colorSurfaceContainer
    val surfaceContainerHigh: Int = com.google.android.material.R.attr.colorSurfaceContainerHigh
    val primaryContainer: Int = com.google.android.material.R.attr.colorPrimaryContainer
    val onPrimaryContainer: Int = com.google.android.material.R.attr.colorOnPrimaryContainer
    val secondaryContainer: Int = com.google.android.material.R.attr.colorSecondaryContainer
    val tertiary: Int = com.google.android.material.R.attr.colorTertiary
    val tertiaryContainer: Int = com.google.android.material.R.attr.colorTertiaryContainer
    val onTertiaryContainer: Int = com.google.android.material.R.attr.colorOnTertiaryContainer
    val onSecondaryContainer: Int = com.google.android.material.R.attr.colorOnSecondaryContainer
    val error: Int = androidx.appcompat.R.attr.colorError
    val outlineVariant: Int = com.google.android.material.R.attr.colorOutlineVariant

  def matchWrap: LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  def wrap: LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  def weighted(w: Float = 1f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

  def vbox(children: View*)(using c: Ctx): LinearLayout =
    val l = LinearLayout(c)
    l.setOrientation(LinearLayout.VERTICAL)
    children.foreach(l.addView)
    l

  def hbox(children: View*)(using c: Ctx): LinearLayout =
    val l = LinearLayout(c)
    l.setOrientation(LinearLayout.HORIZONTAL)
    l.setGravity(Gravity.CENTER_VERTICAL)
    children.foreach(l.addView)
    l

  def text(s: CharSequence, size: Float = 14f, bold: Boolean = false, colorAttr: Int = attr.onSurface)(using c: Ctx): TextView =
    val t = TextView(c)
    t.setText(s)
    t.setTextSize(size)
    t.setTextColor(color(colorAttr))
    if bold then t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    t

  def mono(s: CharSequence, size: Float = 12f)(using c: Ctx): TextView =
    val t = text(s, size)
    t.setTypeface(Typeface.MONOSPACE)
    t.setTextIsSelectable(true)
    t

  def caption(s: CharSequence)(using Ctx): TextView = text(s, 12f, colorAttr = attr.onSurfaceVariant)

  def button(label: String, tonal: Boolean = false)(onClick: => Unit)(using c: Ctx): MaterialButton =
    val b =
      if tonal then MaterialButton(c, null, com.google.android.material.R.attr.materialButtonTonalStyle)
      else MaterialButton(c)
    b.setText(label)
    b.setOnClickListener(_ => onClick)
    b

  def outlined(label: String)(onClick: => Unit)(using c: Ctx): MaterialButton =
    val b = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    b.setText(label)
    b.setOnClickListener(_ => onClick)
    b

  def card(children: View*)(using c: Ctx): MaterialCardView =
    val card = MaterialCardView(c)
    card.setRadius(dp(16).toFloat)
    card.setCardElevation(0)
    card.setCardBackgroundColor(color(attr.surfaceContainer))
    card.setStrokeWidth(0)
    val inner = vbox(children*)
    inner.setPadding(dp(16), dp(14), dp(16), dp(14))
    card.addView(inner)
    val lp = matchWrap
    lp.setMargins(dp(12), dp(6), dp(12), dp(6))
    card.setLayoutParams(lp)
    card

  def chip(label: String, bg: Int, fg: Int)(using c: Ctx): TextView =
    val t = text(label, 12f)
    t.setTextColor(fg)
    val d = GradientDrawable()
    d.setCornerRadius(dp(8).toFloat)
    d.setColor(bg)
    t.setBackground(d)
    t.setPadding(dp(8), dp(3), dp(8), dp(3))
    t

  def spacer(h: Float)(using c: Ctx): View =
    val v = View(c)
    v.setLayoutParams(LinearLayout.LayoutParams(1, dp(h)))
    v

  def scroll(content: View)(using c: Ctx): ScrollView =
    val s = ScrollView(c)
    s.setFillViewport(true)
    s.addView(content)
    s

  def frame(children: View*)(using c: Ctx): FrameLayout =
    val f = FrameLayout(c)
    children.foreach(f.addView)
    f

  def padded(v: View, h: Float, vert: Float)(using Ctx): v.type =
    v.setPadding(dp(h), dp(vert), dp(h), dp(vert))
    v

  def tint(b: MaterialButton, bg: Int, fg: Int): Unit =
    b.setBackgroundTintList(ColorStateList.valueOf(bg))
    b.setTextColor(fg)

  /** Opens a bottom sheet expanded to (nearly) the full screen height. */
  def fullHeight(d: com.google.android.material.bottomsheet.BottomSheetDialog): Unit =
    val b = d.getBehavior
    b.setSkipCollapsed(true)
    b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED)
    d.setOnShowListener { _ =>
      Option(d.findViewById[View](com.google.android.material.R.id.design_bottom_sheet)).foreach { sheet =>
        val lp = sheet.getLayoutParams
        lp.height = (sheet.getResources.getDisplayMetrics.heightPixels * 0.92).toInt
        sheet.setLayoutParams(lp)
      }
    }

  def fmtDuration(ms: Long): String =
    val m = ms / 60000
    if ms < 60_000 then s"${math.max(0, ms / 1000)}秒"
    else if m < 60 then s"${m}分"
    else s"${m / 60}時間${m % 60}分"

  def fmtDistance(m: Double): String =
    if m < 1000 then f"${m}%.0f m" else f"${m / 1000}%.1f km"
