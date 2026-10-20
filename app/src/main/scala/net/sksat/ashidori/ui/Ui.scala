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
    val errorContainer: Int = com.google.android.material.R.attr.colorErrorContainer
    val onErrorContainer: Int = com.google.android.material.R.attr.colorOnErrorContainer
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

  // --- layout stability: values change, the layout does not ---

  /** Digits of equal width, so a changing number does not jiggle. */
  def tabular[T <: TextView](t: T): T =
    t.setFontFeatureSettings("tnum")
    t

  /** One line, cut with "…" instead of wrapping to a second line. */
  def oneLine[T <: TextView](t: T): T =
    t.setSingleLine(true)
    t.setEllipsize(android.text.TextUtils.TruncateAt.END)
    t

  /** Text that is always as tall as the tallest of `variants` would be at
    * its width, so switching between them does not move what follows.
    * The current text counts as a variant too.
    */
  final class StableText(c: Ctx, variants: () => Seq[CharSequence]) extends TextView(c):
    /** Measures the variants the way this view would measure them: same
      * text attributes, same TextView code path (fallback fonts such as
      * emoji and CJK make some lines taller than others).
      */
    private lazy val twin =
      val t = TextView(c)
      // Never attached, but setText checks its layout params.
      t.setLayoutParams(ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      t
    override def onMeasure(w: Int, h: Int): Unit =
      super.onMeasure(w, h)
      if View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY then
        twin.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getTextSize)
        twin.setTypeface(getTypeface)
        twin.setFontFeatureSettings(getFontFeatureSettings)
        twin.setLetterSpacing(getLetterSpacing)
        twin.setLineSpacing(getLineSpacingExtra, getLineSpacingMultiplier)
        twin.setIncludeFontPadding(getIncludeFontPadding)
        twin.setFallbackLineSpacing(isFallbackLineSpacing)
        twin.setBreakStrategy(getBreakStrategy)
        twin.setHyphenationFrequency(getHyphenationFrequency)
        twin.setLineBreakStyle(getLineBreakStyle)
        twin.setLineBreakWordStyle(getLineBreakWordStyle)
        twin.setMaxLines(getMaxLines)
        twin.setEllipsize(getEllipsize)
        twin.setPadding(getPaddingLeft, getPaddingTop, getPaddingRight, getPaddingBottom)
        val ws = View.MeasureSpec.makeMeasureSpec(getMeasuredWidth, View.MeasureSpec.EXACTLY)
        val hs = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val need = variants().map { s =>
          twin.setText(s)
          twin.measure(ws, hs)
          twin.getMeasuredHeight
        }.maxOption.getOrElse(0)
        if need > getMeasuredHeight then
          setMeasuredDimension(getMeasuredWidth, View.resolveSize(need, h))

  /** A caption reserving the height of its tallest variant (see [[StableText]]). */
  def stableCaption(variants: => Seq[CharSequence])(using c: Ctx): StableText =
    val t = StableText(c, () => variants)
    t.setTextSize(12f)
    t.setTextColor(color(attr.onSurfaceVariant))
    t

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

  // --- Material 3 lists: what you tap or change, set apart from what you read ---
  //
  // Tappable rows are whole-row touch targets (56dp, 72dp with a subtitle)
  // with a ripple and a trailing mark: a chevron (opens something), a value
  // (a choice), a switch, or nothing but the error colour (destructive).
  // Section headers and supporting text have none of these.
  // Rows go in a `listGroup`, a rounded card with dividers between rows.

  /** Text inset of rows inside a [[listGroup]], from the screen edge; headers
    * and supporting text line up with it.
    */
  private val TextInset = 28f

  /** Ripple of a pressed row (or, borderless, of an icon button). */
  def ripple(v: View, borderless: Boolean = false)(using c: Ctx): Unit =
    val tv = android.util.TypedValue()
    c.getTheme.resolveAttribute(
      if borderless then android.R.attr.selectableItemBackgroundBorderless
      else android.R.attr.selectableItemBackground, tv, true)
    v.setBackgroundResource(tv.resourceId)

  /** What TalkBack calls the view ("button", "switch"), and for a switch,
    * whether it is on.
    */
  def a11yRole(v: View, cls: Class[?], checked: Option[() => Boolean] = None): Unit =
    androidx.core.view.ViewCompat.setAccessibilityDelegate(v, new androidx.core.view.AccessibilityDelegateCompat:
      override def onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat): Unit =
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.setClassName(cls.getName)
        checked.foreach { f => info.setCheckable(true); info.setChecked(f()) }
      override def onInitializeAccessibilityEvent(host: View, e: android.view.accessibility.AccessibilityEvent): Unit =
        super.onInitializeAccessibilityEvent(host, e)
        e.setClassName(cls.getName)
        checked.foreach(f => e.setChecked(f()))
    )

  /** A list row: title (body large), optional subtitle (secondary colour)
    * and trailing views. Without a click listener it is plain text.
    */
  class ListRow(c: Ctx) extends LinearLayout(c):
    private given Ctx = c
    val title: TextView = text("", 16f)
    val subtitle: TextView = text("", 14f, colorAttr = attr.onSurfaceVariant)
    /** The current value of a choice row, at the trailing edge. */
    val value: TextView = oneLine(text("", 14f, colorAttr = attr.primary))
    private val texts = vbox(title, subtitle)
    // Wrap Japanese at phrase boundaries ("…サービスに / 送らない").
    Seq(title, subtitle).foreach(_.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE))
    setOrientation(LinearLayout.HORIZONTAL)
    setGravity(Gravity.CENTER_VERTICAL)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    texts.setLayoutParams(weighted())
    addView(texts)
    value.setMaxWidth(dp(160))
    value.setPadding(dp(16), 0, 0, 0)
    value.setVisibility(View.GONE)
    addView(value)
    setSubtitle("")

    def setSubtitle(s: CharSequence): Unit =
      subtitle.setText(s)
      subtitle.setVisibility(if s.length == 0 then View.GONE else View.VISIBLE)
      setMinimumHeight(dp(if s.length == 0 then 56 else 72))

    def setValue(s: CharSequence): Unit =
      value.setText(s)
      value.setVisibility(if s.length == 0 then View.GONE else View.VISIBLE)

    /** Adds a view at the trailing edge, after the value. */
    def trailing(v: View): Unit =
      val lp = wrap
      lp.setMarginStart(dp(12))
      v.setLayoutParams(lp)
      addView(v)

    def onTap(f: => Unit): Unit =
      ripple(this)
      setOnClickListener(_ => f)

  private def listRow(title: String, subtitle: CharSequence)(using c: Ctx): ListRow =
    val r = ListRow(c)
    r.title.setText(title)
    r.setSubtitle(subtitle)
    r

  /** A decorative icon (the role is on the row). */
  def icon(res: Int, colorAttr: Int = attr.onSurfaceVariant)(using c: Ctx): android.widget.ImageView =
    val v = android.widget.ImageView(c)
    v.setImageResource(res)
    v.setImageTintList(ColorStateList.valueOf(color(colorAttr)))
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    v.setLayoutParams(LinearLayout.LayoutParams(dp(24), dp(24)))
    v

  /** A 48dp icon button with its own ripple, e.g. "詳しく" or "共有" inside a row. */
  def iconButton(res: Int, description: String)(onClick: => Unit)(using c: Ctx): android.widget.ImageButton =
    val b = android.widget.ImageButton(c)
    b.setImageResource(res)
    b.setImageTintList(ColorStateList.valueOf(color(attr.onSurfaceVariant)))
    b.setContentDescription(description)
    b.setMinimumWidth(dp(48))
    b.setMinimumHeight(dp(48))
    b.setPadding(dp(12), dp(12), dp(12), dp(12))
    ripple(b, borderless = true)
    b.setOnClickListener(_ => onClick)
    b

  /** Opens a screen or a detail dialog: trailing chevron (or `trailingIcon`,
    * e.g. share; 0 for none).
    */
  def navRow(title: String, subtitle: CharSequence = "", trailingIcon: Int = net.sksat.ashidori.R.drawable.ic_chevron_right)(onClick: => Unit)(using c: Ctx): ListRow =
    val r = listRow(title, subtitle)
    if trailingIcon != 0 then r.trailing(icon(trailingIcon))
    r.onTap(onClick)
    a11yRole(r, classOf[android.widget.Button])
    r

  /** A choice: the current value at the trailing edge; tapping opens the
    * choices (a dialog).
    */
  def choiceRow(title: String, value: CharSequence, subtitle: CharSequence = "")(onClick: => Unit)(using c: Ctx): ListRow =
    val r = listRow(title, subtitle)
    r.setValue(value)
    r.onTap(onClick)
    a11yRole(r, classOf[android.widget.Button])
    r

  final class SwitchRow(c: Ctx) extends ListRow(c):
    val switch: com.google.android.material.materialswitch.MaterialSwitch =
      com.google.android.material.materialswitch.MaterialSwitch(c)

  /** On/off: a switch at the trailing edge; tapping anywhere on the row
    * toggles it. `onInfo` adds an info button ("詳しく") before the switch.
    */
  def switchRow(title: String, checked: Boolean, subtitle: CharSequence = "", onInfo: Option[() => Unit] = None)(onChange: Boolean => Unit)(using c: Ctx): SwitchRow =
    val r = SwitchRow(c)
    r.title.setText(title)
    r.setSubtitle(subtitle)
    onInfo.foreach(f => r.trailing(iconButton(net.sksat.ashidori.R.drawable.ic_info, "詳しく")(f())))
    val s = r.switch
    s.setChecked(checked)
    // The row takes the touches and speaks for the switch.
    s.setClickable(false)
    s.setFocusable(false)
    s.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    s.setOnCheckedChangeListener((_, b) => onChange(b))
    r.trailing(s)
    r.onTap(s.toggle())
    a11yRole(r, classOf[android.widget.Switch], Some(() => s.isChecked))
    r

  /** A destructive action in the error colour, no chevron. Put it last in
    * its group.
    */
  def dangerRow(title: String, subtitle: CharSequence = "")(onClick: => Unit)(using c: Ctx): ListRow =
    val r = listRow(title, subtitle)
    r.title.setTextColor(color(attr.error))
    r.onTap(onClick)
    a11yRole(r, classOf[android.widget.Button])
    r

  /** Small label in the primary colour above a group; not tappable. */
  def sectionHeader(name: String)(using c: Ctx): TextView =
    val t = text(name, 14f, colorAttr = attr.primary)
    t.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false))
    t.setAccessibilityHeading(true)
    t.setPadding(dp(TextInset), dp(24), dp(TextInset), dp(8))
    t

  /** Explanation under the group (or row) it belongs to: smaller, secondary,
    * aligned with the rows' text.
    */
  def supportingText(s: CharSequence)(using c: Ctx): TextView =
    val t = caption(s)
    t.setPadding(dp(TextInset), dp(6), dp(TextInset), 0)
    t

  /** Rows in one rounded card, divided by inset lines. */
  def listGroup(rows: View*)(using c: Ctx): MaterialCardView =
    val g = MaterialCardView(c)
    g.setRadius(dp(16).toFloat)
    g.setCardElevation(0)
    g.setCardBackgroundColor(color(attr.surfaceContainer))
    g.setStrokeWidth(0)
    g.setClipToOutline(true)
    val inner = vbox()
    rows.zipWithIndex.foreach { (r, i) =>
      if i > 0 then inner.addView(divider(16))
      inner.addView(r, matchWrap)
    }
    g.addView(inner)
    val lp = matchWrap
    lp.setMargins(dp(12), 0, dp(12), 0)
    g.setLayoutParams(lp)
    g

  /** A hairline in the outline-variant colour, inset from the start. */
  def divider(insetStart: Float = 0)(using c: Ctx): View =
    val v = View(c)
    v.setBackgroundColor(color(attr.outlineVariant))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, math.max(1, dp(0.5f)))
    lp.setMarginStart(dp(insetStart))
    v.setLayoutParams(lp)
    v

  def fmtDuration(ms: Long): String =
    val m = ms / 60000
    if ms < 60_000 then s"${math.max(0, ms / 1000)}秒"
    else if m < 60 then s"${m}分"
    else s"${m / 60}時間${m % 60}分"

  def fmtDistance(m: Double): String =
    if m < 1000 then f"${m}%.0f m" else f"${m / 1000}%.1f km"
