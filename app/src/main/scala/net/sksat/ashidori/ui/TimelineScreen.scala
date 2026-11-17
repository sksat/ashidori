package net.sksat.ashidori.ui

import android.os.Bundle
import android.view.{GestureDetector, Gravity, MotionEvent, View, ViewGroup}
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.{BottomSheetBehavior, BottomSheetDragHandleView}
import com.google.android.material.datepicker.{CalendarConstraints, DateValidatorPointBackward, MaterialDatePicker}
import java.time.{Instant, LocalDate, ZoneOffset}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The map with a bottom sheet over it, like Google Maps.
  *
  * The map tab (day): the sheet has three heights. Lowered, it shows the date
  * (「‹ 11月17日（月） ▾ ›」: the arrows move a day, the date opens a calendar)
  * and one line of totals per mode; half and full, the day's stays and trips
  * on a rail ([[DayRows]]), the map kept above. Tapping an item draws it
  * thicker on the map and fits the camera to it. A small link at the end
  * opens the day on the タイムライン tab.
  *
  * Week / month / year (pushed from the feed): the period's map and its
  * summary ([[PeriodView]]).
  */
final class TimelineScreen(act: MainActivity, saved: Bundle, spans: Seq[Span] = Seq(Span.Day)) extends Screen:
  private given Ctx = act
  override def title: String = s"${span.period}の記録"
  private var current: Option[DayTimeline] = None

  /** The map tab (day) and the period view are separate map surfaces: an
    * online map chosen on one is not shown on the other.
    */
  private val map = TrackMap(act, saved, if spans == Seq(Span.Day) then MapStyle.SurfaceMap else MapStyle.SurfacePeriod, controlsOnTop = true)
  /** Day (the detailed timeline) or a longer period summary. */
  private var span = if spans.contains(Span.Month) then Span.Month else spans.head
  private val dayOnly = spans == Seq(Span.Day)
  /** The day tab follows the app-wide selected date (search, places and
    * insights jump to it); the period tab keeps its own position.
    */
  private var ownAnchor = LocalDate.now(AshidoriApp.zone)
  private def anchor: LocalDate = if spans.size > 1 then ownAnchor else act.selectedDate
  private def anchor_=(d: LocalDate): Unit = if spans.size > 1 then ownAnchor = d else act.selectedDate = d

  // --- the period sheet (week / month / year) -------------------------------------------

  private val dateLabel = oneLine(text("", 18f, bold = true))
  private val summary = hbox()
  private val list = vbox()
  // Until the period has loaded, the totals' room is already taken ("—").
  summary.addView(stat("—", "移動距離"))
  summary.addView(stat("—", "歩数"))
  summary.addView(stat("—", "場所"))
  padded(summary, 8, 8)

  private def arrow(label: String)(f: => Unit) =
    val b = outlined(label)(f)
    b.setStrokeWidth(0)
    b.setMinWidth(dp(48))
    b

  private val chipsId = View.generateViewId()
  private lazy val periodHeader =
    val prev = arrow("◀")(shift(-1))
    val next = arrow("▶")(shift(1))
    prev.setContentDescription("前へ")
    next.setContentDescription("次へ")
    dateLabel.setGravity(Gravity.CENTER)
    dateLabel.setLayoutParams(weighted())
    dateLabel.setMinHeight(dp(48))
    dateLabel.setContentDescription("日付を選択")
    dateLabel.setOnClickListener(_ => pickPeriodDate())
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
    vbox(padded(chips, 16, 0), padded(hbox(prev, dateLabel, next), 12, 0))

  // --- the day sheet (map tab) --------------------------------------------------------------

  private lazy val dateHeader = DayParts.DateHeader(act, shift, () => pickDay())
  private lazy val daySummary = DayParts.SummaryRow(act)
  private val dayList = vbox()
  /** To the same day on the タイムライン tab: small, at the end of the list. */
  private lazy val openLink =
    val t = text("タイムラインで開く ›", 14f, colorAttr = attr.primary)
    t.setGravity(Gravity.CENTER_VERTICAL | Gravity.END)
    t.setMinHeight(dp(48))
    t.setPadding(dp(16), 0, dp(20), 0)
    ripple(t)
    a11yRole(t, classOf[android.widget.Button])
    t.setOnClickListener(_ => current.foreach(tl => act.openTimeline(tl.date)))
    t

  private val scroller =
    val s = androidx.core.widget.NestedScrollView(act)
    if dayOnly then s.addView(vbox(dayList, openLink, spacer(24)))
    else s.addView(vbox(summary, list, spacer(96)))
    s.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    s

  private val handle =
    if dayOnly then BottomSheetDragHandleView(act)
    else
      val h = View(act)
      val d = android.graphics.drawable.GradientDrawable()
      d.setCornerRadius(dp(2).toFloat)
      d.setColor(color(attr.outlineVariant))
      h.setBackground(d)
      val hlp = LinearLayout.LayoutParams(dp(32), dp(4))
      hlp.gravity = Gravity.CENTER_HORIZONTAL
      hlp.setMargins(0, dp(10), 0, dp(6))
      h.setLayoutParams(hlp)
      h

  private val sheet =
    val bg = android.graphics.drawable.GradientDrawable()
    bg.setCornerRadii(Array(dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0).map(_.toFloat))
    bg.setColor(color(attr.surface))
    val v = if dayOnly then vbox(handle, dateHeader, daySummary, scroller) else vbox(handle, periodHeader, scroller)
    v.setBackground(bg)
    v.setElevation(dp(6).toFloat)
    v
  private val behavior = BottomSheetBehavior[LinearLayout]()

  val view: View =
    val coord = androidx.coordinatorlayout.widget.CoordinatorLayout(act)
    coord.addView(map.view, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val slp = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    slp.setBehavior(behavior)
    coord.addView(sheet, slp)
    behavior.setHideable(false)
    behavior.setFitToContents(false)
    behavior.setHalfExpandedRatio(0.5f)
    behavior.setPeekHeight(dp(150))
    if dayOnly then
      // Three heights: the totals, half, and nearly all (a strip of the map
      // stays above). Lowered at first: the map leads.
      behavior.setExpandedOffset(dp(72))
      behavior.setState(BottomSheetBehavior.STATE_COLLAPSED)
      // Lowered, the sheet shows the date and the totals, and a sliver of
      // the list below them says there is more.
      sheet.addOnLayoutChangeListener((_, _, _, _, _, _, _, _, _) =>
        val peek = handle.getHeight + dateHeader.getHeight + daySummary.getHeight + dp(20)
        if handle.getHeight > 0 && peek != behavior.getPeekHeight then behavior.setPeekHeight(peek))
    else
      // Lowered sheet still shows the span chips and the date row.
      behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED)
    coord.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // Fit the route into the part of the map the sheet does not cover.
    coord.addOnLayoutChangeListener((_, _, top, _, bottom, _, _, _, _) =>
      map.bottomInset = math.max(0, bottom - top - sheet.getTop)
      liftFab())
    behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback:
      override def onStateChanged(v: View, state: Int): Unit =
        map.bottomInset = math.max(0, coord.getHeight - v.getTop)
        liftFab()
      override def onSlide(v: View, offset: Float): Unit = liftFab()
    )
    installSwipe()
    coord

  /** The selected item of the day, on the map: its line thicker, or a ring at
    * the stay, with the camera fitted to it. A sheet raised to full height
    * comes down to half so the map shows it.
    */
  private def showOnMap(item: Option[DayItem]): Unit = item match
    case None => map.clearHighlight()
    case Some(it) =>
      if behavior.getState == BottomSheetBehavior.STATE_EXPANDED then
        behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED)
        map.bottomInset = (view.getHeight * 0.5).toInt
        // Keep the tapped item (and its actions) in the lowered sheet's sight.
        rows.viewOf(it.key).foreach(v => scroller.postDelayed(() => scroller.smoothScrollTo(0, math.max(0, v.getTop - dp(8))), 300))
      it match
        case DayItem.Stay(v) => map.highlightPlace(v.latLng)
        case other           => other.path.filter(_.points.size >= 2).foreach(p => map.highlight(p))

  private lazy val rows = DayRows(act, _ => load(), showOnMap)
  private val dayMarkerClick: (String, String) => Unit = (layer, key) =>
    if layer == "visit" then
      current.foreach { tl =>
        tl.segments.collect { case v: Visit => v }.find(_.start.toString == key).flatMap(v => v.placeId.flatMap(tl.places.get).map(v -> _))
          .foreach((v, p) => PlaceSheet(act, p, Some(v), () => { TimelineRepo.invalidate(); load() }, Some(d => act.openDay(d))).show())
      }
  map.onMarkerClick = dayMarkerClick

  private var unobserve: () => Unit = () => ()
  private var lastLive = 0L

  /** The check-in button rides on the top edge of the sheet, over the map;
    * with the sheet raised past half it steps aside for the list.
    */
  private def liftFab(): Unit =
    if dayOnly && view.getHeight > 0 && sheet.getTop > 0 then
      val covered = view.getHeight - sheet.getTop
      val half = (view.getHeight * 0.5).toInt
      act.setFabLift(math.max(0, math.min(covered, half)))
      act.setCheckInVisible(span == Span.Day && covered <= half + dp(8))

  override def onShow(): Unit =
    map.onScreen()
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
    // While today's day view is open, redraw as new fixes arrive (≤ every 20 s).
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
    // Leaving the screen ends the view's grant to use the network; the map
    // chosen here stays for when it is shown again.
    map.offScreen()

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

  /** The calendar: dots on days with a record, grey where only part of the day was. */
  private def pickDay(): Unit =
    val zone = AshidoriApp.zone
    def status(d: LocalDate): DayParts.Recorded =
      val tl = TimelineRepo.day(d)
      if tl.segments.isEmpty then DayParts.Recorded.None
      else
        val from = d.atStartOfDay(zone).toInstant.toEpochMilli
        val to = math.min(d.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli, System.currentTimeMillis())
        if Stats.partial(tl.segments, from, to) then DayParts.Recorded.Partial else DayParts.Recorded.Full
    DayParts.Calendar(act, anchor, AshidoriApp.db.firstDataDateCached, status, d => { anchor = d; load() }).show()

  private def pickPeriodDate(): Unit =
    val picker = MaterialDatePicker.Builder.datePicker()
      .setTitleText("日付を選択")
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
    if !dayOnly then
      val g = periodHeader.findViewById[com.google.android.material.chip.ChipGroup](chipsId)
      for i <- 0 until g.getChildCount do
        val c = g.getChildAt(i).asInstanceOf[com.google.android.material.chip.Chip]
        c.setChecked(c.getText.toString == span.label)

  def load(): Unit =
    val date = anchor
    val today = LocalDate.now(AshidoriApp.zone)
    if span == Span.Day then { act.setCheckInVisible(true); liftFab() } else act.setCheckInVisible(false)
    if span != Span.Day then
      dateLabel.setText(span.title(date, today))
      current = None
      period.load(span, date)
      return
    dateHeader.show(date, today)
    if !current.exists(_.date == date) then
      // Another day: its own totals and list, nothing selected.
      rows.clearSelection()
      map.clearHighlight()
      daySummary.clear()
    map.onMarkerClick = dayMarkerClick
    AshidoriApp.io.execute { () =>
      val tl = TimelineRepo.day(date)
      act.runOnUiThread(() => if anchor == date then render(tl))
    }

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
    daySummary.show(tl.summary)

    val items = DayItem.of(tl)
    map.setData(
      // One line per leg, colored by transport mode; grey dots where there is no record.
      items.flatMap(_.path).filter(_.points.size >= 2),
      tl.segments.collect { case v: Visit => MapMarker(v.latLng, v.start.toString) },
      tl.checkIns.map(c => MapMarker(LatLng(c.lat, c.lon), c.id.toString, c.venueName)),
      fit
    )
    // The selection survives a refresh (today, after an edit) while its item is there.
    rows.selection.flatMap(k => items.find(_.key == k)) match
      case Some(it) => if !fit then showSelected(it)
      case None     => rows.clearSelection()

    dayList.removeAllViews()
    if items.isEmpty then
      dayList.addView(DayParts.emptyDay(act, tl.date))
      openLink.setVisibility(View.GONE)
    else
      rows.rows(tl).foreach(dayList.addView)
      openLink.setVisibility(View.VISIBLE)

  /** Redraws the selected item's highlight (the day's data was replaced). */
  private def showSelected(it: DayItem): Unit = it match
    // Keep the camera: only the highlight is drawn again.
    case DayItem.Stay(v) => map.highlightPlace(v.latLng, fit = false)
    case other           => other.path.filter(_.points.size >= 2).foreach(map.highlight(_, fit = false))

  /** Camera and shown day, for a recreated activity (the day tab only). */
  def saveState(out: Bundle): Unit =
    if dayOnly then
      for d <- fittedDate; (p, zoom) <- map.camera do
        out.putString(TimelineScreen.KeyDate, d.toString)
        out.putDouble(TimelineScreen.KeyLat, p.lat)
        out.putDouble(TimelineScreen.KeyLon, p.lon)
        out.putDouble(TimelineScreen.KeyZoom, zoom)

  // Recreated after the process was killed (or a configuration change): the
  // camera where it was, and that day not fitted again.
  if dayOnly && saved != null && saved.containsKey(TimelineScreen.KeyZoom) then
    fittedDate = Option(saved.getString(TimelineScreen.KeyDate)).map(LocalDate.parse)
    map.moveTo(LatLng(saved.getDouble(TimelineScreen.KeyLat), saved.getDouble(TimelineScreen.KeyLon)), saved.getDouble(TimelineScreen.KeyZoom))

object TimelineScreen:
  private val KeyDate = "map.date"
  private val KeyLat = "map.lat"
  private val KeyLon = "map.lon"
  private val KeyZoom = "map.zoom"
