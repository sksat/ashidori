package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint}
import android.os.Bundle
import android.view.{GestureDetector, Gravity, MotionEvent, View, ViewGroup}
import android.widget.{FrameLayout, LinearLayout}
import com.google.android.material.datepicker.{CalendarConstraints, DateValidatorPointBackward, MaterialDatePicker}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Vertical rail connecting timeline entries: a dot for visits, a line for trips. */
final class RailView(c: Context, val visit: Boolean, col: Int, top: Boolean, bottom: Boolean) extends View(c):
  private given Context = c
  private val p = Paint(Paint.ANTI_ALIAS_FLAG)
  setLayoutParams(LinearLayout.LayoutParams(Ui.dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
  override def onDraw(canvas: Canvas): Unit =
    val cx = getWidth / 2f
    p.setColor(col)
    p.setStrokeWidth(Ui.dp(if visit then 2 else 4).toFloat)
    val cy = Ui.dp(22).toFloat
    if top then canvas.drawLine(cx, 0, cx, cy, p)
    if bottom then canvas.drawLine(cx, cy, cx, getHeight.toFloat, p)
    if visit then
      p.setStyle(Paint.Style.FILL)
      canvas.drawCircle(cx, cy, Ui.dp(7).toFloat, p)
      p.setColor(Ui.color(Ui.attr.surface))
      canvas.drawCircle(cx, cy, Ui.dp(3).toFloat, p)

/** Day view: map of the day on top, the day's visits and trips below.
  * Swipe left/right or use the arrows to change day; tap the date for a calendar.
  */
final class TimelineScreen(act: MainActivity, saved: Bundle, spans: Seq[Span] = Seq(Span.Day)) extends Screen:
  private given Ctx = act
  override def title: String = s"${span.label}の記録"
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
  private val dateFmt = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
  private var current: Option[DayTimeline] = None

  private val map = TrackMap(act, saved, controlsOnTop = true)
  /** Day (the detailed timeline) or a longer period summary. */
  private var span = if spans.contains(Span.Month) then Span.Month else spans.head
  private val dayOnly = spans == Seq(Span.Day)
  /** The day tab follows the app-wide selected date (search, places and
    * insights jump to it); the period tab keeps its own position.
    */
  private var ownAnchor = LocalDate.now(AshidoriApp.zone)
  private def anchor: LocalDate = if spans.size > 1 then ownAnchor else act.selectedDate
  private def anchor_=(d: LocalDate): Unit = if spans.size > 1 then ownAnchor = d else act.selectedDate = d

  private val dateLabel = oneLine(text("", 18f, bold = true))
  private val summary = hbox()
  private val list = vbox()
  // Until the day has loaded, the totals' room is already taken ("—").
  summary.addView(stat("—", "歩数"))
  summary.addView(stat("—", "移動"))
  summary.addView(stat("—", "滞在"))
  padded(summary, 8, 8)

  private def arrow(label: String)(f: => Unit) =
    val b = outlined(label)(f)
    b.setStrokeWidth(0)
    b.setMinWidth(dp(48))
    b

  private val chipsId = View.generateViewId()
  private val header =
    val prev = arrow("◀")(shift(-1))
    val next = arrow("▶")(shift(1))
    prev.setContentDescription("前へ")
    next.setContentDescription("次へ")
    dateLabel.setGravity(Gravity.CENTER)
    dateLabel.setLayoutParams(weighted())
    dateLabel.setMinHeight(dp(48))
    dateLabel.setContentDescription("日付を選ぶ")
    dateLabel.setOnClickListener(_ => pickDate())
    val chips = com.google.android.material.chip.ChipGroup(act)
    chips.setId(chipsId)
    chips.setSingleSelection(true)
    chips.setSelectionRequired(true)
    for sp <- spans do
      val c = com.google.android.material.chip.Chip(act, null, com.google.android.material.R.attr.chipStyle)
      c.setText(sp.label)
      c.setCheckable(true)
      c.setChecked(sp == span)
      c.setOnCheckedChangeListener((_, on) => if on && span != sp then { span = sp; act.updateTitle(); load() })
      chips.addView(c)
    if spans.size > 1 then vbox(padded(chips, 16, 0), padded(hbox(prev, dateLabel, next), 12, 0))
    else padded(hbox(prev, dateLabel, next), 12, 0)

  /** The day's list lives in a bottom sheet over a full-height map, like
    * Google Maps: drag it down to see more map, up to read the list.
    */
  private val scroller =
    val s = androidx.core.widget.NestedScrollView(act)
    s.addView(vbox(summary, list, spacer(if spans == Seq(Span.Day) then 12 else 96)))
    s
  private val sheet =
    val handle = View(act)
    val d = android.graphics.drawable.GradientDrawable()
    d.setCornerRadius(dp(2).toFloat)
    d.setColor(color(attr.outlineVariant))
    handle.setBackground(d)
    val hlp = LinearLayout.LayoutParams(dp(32), dp(4))
    hlp.gravity = Gravity.CENTER_HORIZONTAL
    hlp.setMargins(0, dp(10), 0, dp(6))
    handle.setLayoutParams(hlp)
    val bg = android.graphics.drawable.GradientDrawable()
    bg.setCornerRadii(Array(dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0).map(_.toFloat))
    bg.setColor(color(attr.surface))
    val v = vbox(handle, header, scroller)
    v.setBackground(bg)
    v.setElevation(dp(6).toFloat)
    scroller.setLayoutParams(
      if spans == Seq(Span.Day) then matchWrap
      else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    v
  private val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior[LinearLayout]()

  val view: View =
    val coord = androidx.coordinatorlayout.widget.CoordinatorLayout(act)
    coord.addView(map.view, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val slp = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      if dayOnly then ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT)
    slp.setBehavior(behavior)
    coord.addView(sheet, slp)
    behavior.setHideable(false)
    if dayOnly then
      // Day: a short sheet (date, totals, link to the timeline) under a full map.
      behavior.setFitToContents(true)
      behavior.setPeekHeight(dp(150))
      behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED)
    else
      behavior.setFitToContents(false)
      behavior.setHalfExpandedRatio(0.5f)
      // Lowered sheet still shows the span chips and the date row.
      behavior.setPeekHeight(dp(150))
      behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED)
    coord.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // Fit the day's route into the part of the map the sheet does not cover.
    coord.addOnLayoutChangeListener((_, _, top, _, bottom, _, _, _, _) =>
      map.bottomInset = if dayOnly then math.max(0, bottom - top - sheet.getTop) else ((bottom - top) * 0.5).toInt
      liftFab())
    behavior.addBottomSheetCallback(new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback:
      override def onStateChanged(v: View, state: Int): Unit =
        map.bottomInset = math.max(0, coord.getHeight - v.getTop)
        liftFab()
      override def onSlide(v: View, offset: Float): Unit = liftFab()
    )
    installSwipe()
    coord

  /** Lower the sheet so the map shows what was just tapped. */
  private def revealMap(): Unit =
    behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED)

  private lazy val rows = DayRows(act, _ => load())
  private val dayMarkerClick: (String, String) => Unit = (layer, key) =>
    if layer == "visit" then
      current.foreach { tl =>
        tl.segments.collect { case v: Visit => v }.find(_.start.toString == key).foreach(rows.openVisit(_, tl))
      }
  map.onMarkerClick = dayMarkerClick

  private var unobserve: () => Unit = () => ()
  private var lastLive = 0L

  /** While today's day view is open, redraw as new fixes arrive (≤ every 20 s). */
  /** The check-in button rides on the top edge of the sheet, over the map. */
  private def liftFab(): Unit =
    if dayOnly && view.getHeight > 0 then act.setFabLift(math.max(0, view.getHeight - sheet.getTop))

  override def onShow(): Unit =
    view.post(() => liftFab())
    // First open: show where we last were right away, before the day loads.
    if fittedDate.isEmpty then
      LoggerState.lastFix match
        case Some(f) => map.focus(f.latLng, 15)
        case None =>
          AshidoriApp.io.execute { () =>
            AshidoriApp.db.lastFix().foreach(f => act.runOnUiThread(() => if fittedDate.isEmpty then map.focus(f.latLng, 15)))
          }
    load()
    unobserve()
    unobserve = LoggerState.observe { () =>
      updateLiveTail()
      val now = System.currentTimeMillis()
      if span == Span.Day && anchor == LocalDate.now(AshidoriApp.zone) && now - lastLive > 20_000 &&
        LoggerState.lastFix.exists(f => f.time > lastLive)
      then
        lastLive = now
        load()
    }

  override def onHide(): Unit =
    unobserve()
    // Leaving the screen ends the detailed (online) map of this view.
    map.endDetail()

  /** Time of the last point drawn by the latest full render. */
  private var drawnUntil = Long.MaxValue

  /** Between full redraws, extend the line from the drawn route to the
    * current position right away, so it does not lag behind the dot.
    */
  private def updateLiveTail(): Unit =
    if span == Span.Day && anchor == LocalDate.now(AshidoriApp.zone) && drawnUntil != Long.MaxValue then
      // End exactly where the position marker is, so line and dot move together.
      val end = LoggerState.lastFix.map(_.time).getOrElse(Long.MaxValue)
      val tail = LoggerState.recentFixes.filter(f => f.time >= drawnUntil && f.time <= end && (!f.hasAccuracy || f.accuracy < 100))
      map.setLive(if tail.size >= 2 then tail else Nil)
    else map.setLive(Nil)

  private def installSwipe(): Unit =
    val detector = GestureDetector(act, new GestureDetector.SimpleOnGestureListener:
      override def onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean =
        if e1 != null && math.abs(vx) > math.abs(vy) * 2 && math.abs(e2.getX - e1.getX) > dp(80) then
          shift(if vx < 0 then 1 else -1)
          true
        else false
    )
    scroller.setOnTouchListener((_, e) => { detector.onTouchEvent(e); false })

  private def shift(n: Int): Unit =
    val today = LocalDate.now(AshidoriApp.zone)
    val d = span.shift(anchor, n)
    if !span.range(d)._1.isAfter(today) then
      anchor = if d.isAfter(today) then today else d
      load()

  private def pickDate(): Unit =
    val picker = MaterialDatePicker.Builder.datePicker()
      .setTitleText("日付を選ぶ")
      .setSelection(anchor.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli)
      .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
      .build()
    picker.addOnPositiveButtonClickListener(sel => {
      anchor = Instant.ofEpochMilli(sel).atZone(ZoneOffset.UTC).toLocalDate
      load()
    })
    picker.show(act.getSupportFragmentManager, "date")

  private lazy val period = PeriodView(act, map, summary, list, d => act.openDay(d))

  /** Open a period (from a summary card in the feed). */
  def showPeriod(sp: Span, d: LocalDate): Unit =
    // A reused period screen opens another period with the offline map.
    map.endDetail()
    span = sp
    ownAnchor = d
    syncChips()

  /** Jump from a period to one of its days. */
  def showDay(d: LocalDate): Unit =
    span = Span.Day
    anchor = d
    syncChips()
    load()

  private def syncChips(): Unit =
    val g = header.findViewById[com.google.android.material.chip.ChipGroup](chipsId)
    for i <- 0 until g.getChildCount do
      val c = g.getChildAt(i).asInstanceOf[com.google.android.material.chip.Chip]
      c.setChecked(c.getText.toString == span.label)

  def load(): Unit =
    val date = anchor
    val today = LocalDate.now(AshidoriApp.zone)
    dateLabel.setText(span.title(date, today))
    act.setCheckInVisible(span == Span.Day)
    if span != Span.Day then
      current = None
      period.load(span, date)
      return
    map.onMarkerClick = dayMarkerClick
    AshidoriApp.io.execute { () =>
      val tl = TimelineRepo.day(date)
      act.runOnUiThread(() => if anchor == date then render(tl))
    }

  private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  private def stat(value: String, label: String): View =
    val v = vbox(tabular(oneLine(text(value, 20f, bold = true))), caption(label))
    v.setGravity(Gravity.CENTER)
    v.setLayoutParams(weighted())
    v

  /** Fit the camera only when a different day is shown, not on live refreshes. */
  private var fittedDate: Option[LocalDate] = None

  private def render(tl: DayTimeline): Unit =
    val fit = !fittedDate.contains(tl.date)
    // The live tail starts where this render's route ends.
    drawnUntil = tl.segments.collect { case t: Trip => t.path.lastOption.map(_.time) }.flatten.maxOption
      .orElse(tl.segments.lastOption.map(_.end)).getOrElse(Long.MaxValue)
    updateLiveTail()
    fittedDate = Some(tl.date)
    current = Some(tl)
    summary.removeAllViews()
    summary.addView(stat(f"${tl.summary.steps}%,d", "歩数"))
    summary.addView(stat(fmtDistance(tl.summary.totalDistanceM), "移動"))
    summary.addView(stat(tl.summary.visits.toString, "滞在"))
    padded(summary, 8, 8)

    val trips = tl.segments.collect { case t: Trip => t }
    map.setData(
      // One line per leg, colored by transport mode.
      trips.flatMap(t => t.legs.filter(_.mode != TransportMode.Still).map { l =>
        MapPath(Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), 4), l.mode.color, l.isUncertain)
      }),
      tl.segments.collect { case v: Visit => MapMarker(v.latLng, v.start.toString) },
      tl.checkIns.map(c => MapMarker(LatLng(c.lat, c.lon), c.id.toString, c.venueName)),
      fit
    )

    // The day's stays and trips are read on the timeline; here the map leads.
    // One row of one height for every day, with or without records, so the
    // sheet (and the map's free area) keeps its size while paging days.
    list.removeAllViews()
    val slot =
      if tl.segments.isEmpty then emptyState(tl.date)
      else outlined("この日のタイムライン")(act.openTimeline(tl.date))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
    lp.setMargins(dp(16), 0, dp(16), dp(4))
    slot.setLayoutParams(lp)
    list.addView(slot)

  private def emptyState(date: LocalDate): View =
    val label = oneLine(text(
      if !LoggerState.running then "記録停止中"
      else if date == LocalDate.now(AshidoriApp.zone) then "記録待ち"
      else "記録なし", 16f, bold = true))
    label.setLayoutParams(weighted())
    val row = hbox(label)
    if !LoggerState.running then row.addView(button("▶  記録を開始")(act.startLogging()))
    row
