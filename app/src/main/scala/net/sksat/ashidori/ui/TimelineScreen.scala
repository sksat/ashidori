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
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
  private val dateFmt = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)
  private var current: Option[DayTimeline] = None

  private val map = TrackMap(act, saved, controlsOnTop = true)
  /** Day (the detailed timeline) or a longer period summary. */
  private var span = if spans.contains(Span.Month) then Span.Month else spans.head
  /** The day tab follows the app-wide selected date (search, places and
    * insights jump to it); the period tab keeps its own position.
    */
  private var ownAnchor = LocalDate.now(AshidoriApp.zone)
  private def anchor: LocalDate = if spans.size > 1 then ownAnchor else act.selectedDate
  private def anchor_=(d: LocalDate): Unit = if spans.size > 1 then ownAnchor = d else act.selectedDate = d

  private val dateLabel = text("", 18f, bold = true)
  private val summary = hbox()
  private val list = vbox()

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
      c.setOnCheckedChangeListener((_, on) => if on && span != sp then { span = sp; load() })
      chips.addView(c)
    if spans.size > 1 then vbox(padded(chips, 16, 0), padded(hbox(prev, dateLabel, next), 12, 0))
    else padded(hbox(prev, dateLabel, next), 12, 0)

  /** The day's list lives in a bottom sheet over a full-height map, like
    * Google Maps: drag it down to see more map, up to read the list.
    */
  private val scroller =
    val s = androidx.core.widget.NestedScrollView(act)
    s.addView(vbox(summary, list, spacer(96)))
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
    scroller.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    v
  private val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior[LinearLayout]()

  val view: View =
    val coord = androidx.coordinatorlayout.widget.CoordinatorLayout(act)
    coord.addView(map.view, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val slp = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    slp.setBehavior(behavior)
    coord.addView(sheet, slp)
    behavior.setFitToContents(false)
    behavior.setHalfExpandedRatio(0.5f)
    // Lowered sheet still shows the span chips and the date row.
    behavior.setPeekHeight(dp(150))
    behavior.setHideable(false)
    behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED)
    coord.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // Fit the day's route into the part of the map the sheet does not cover.
    coord.addOnLayoutChangeListener((_, _, top, _, bottom, _, _, _, _) => map.bottomInset = ((bottom - top) * 0.5).toInt)
    behavior.addBottomSheetCallback(new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback:
      override def onStateChanged(v: View, state: Int): Unit =
        map.bottomInset = math.max(0, coord.getHeight - v.getTop)
      override def onSlide(v: View, offset: Float): Unit = ()
    )
    installSwipe()
    coord

  /** Lower the sheet so the map shows what was just tapped. */
  private def revealMap(): Unit =
    behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED)

  private val dayMarkerClick: (String, String) => Unit = (layer, key) =>
    if layer == "visit" then
      current.foreach { tl =>
        tl.segments.collect { case v: Visit => v }.find(_.start.toString == key).foreach(openVisit(_, tl))
      }
  map.onMarkerClick = dayMarkerClick

  private var unobserve: () => Unit = () => ()
  private var lastLive = 0L

  /** While today's day view is open, redraw as new fixes arrive (≤ every 20 s). */
  override def onShow(): Unit =
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

  override def onHide(): Unit = unobserve()

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
    val v = vbox(text(value, 20f, bold = true), caption(label))
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

    list.removeAllViews()
    if tl.segments.isEmpty then list.addView(emptyState(tl.date))
    val n = tl.segments.size
    for (s, i) <- tl.segments.zipWithIndex do
      s match
        case v: Visit => list.addView(visitRow(v, tl, i > 0, i < n - 1))
        case t: Trip  => list.addView(tripRow(t, i > 0, i < n - 1))

  private def emptyState(date: LocalDate): View =
    if LoggerState.running then
      card(
        text(if date == LocalDate.now(AshidoriApp.zone) then "記録を始めました" else "この日の記録はありません", 16f, bold = true),
        caption("移動や滞在が検出されると、ここに表示されます。")
      )
    else
      card(
        text("記録停止中", 16f, bold = true),
        caption("記録を始めると、訪れた場所と移動がここに自動で並びます。"),
        spacer(8),
        button("記録を始める")(act.startLogging())
      )

  private def withRail(rail: RailView, content: View): View =
    content.setLayoutParams(weighted())
    val row = hbox(rail, content)
    row.setGravity(Gravity.TOP)
    padded(row, 8, 0)

  private def visitRow(v: Visit, tl: DayTimeline, top: Boolean, bottom: Boolean): View =
    val place = v.placeId.flatMap(tl.places.get)
    val name = place.map { p =>
      tl.suggestions.get(p.id).filter(_ => !p.named).map(n => s"$n？").getOrElse(p.displayName)
    }.getOrElse("不明な場所")
    val chips = hbox()
    place.filter(_.kind != PlaceKind.Other).foreach { p =>
      chips.addView(chip(p.kind.label, color(attr.secondaryContainer), color(attr.onSurface)))
    }
    tl.checkIns.filter(c => c.time >= v.start - 60_000 && c.time <= v.end + 60_000).foreach { c =>
      chips.addView(chip(s"✓ ${c.venueName}", color(attr.primaryContainer), color(attr.onPrimaryContainer)))
    }
    val suggestion = place.flatMap(p => tl.suggestions.get(p.id))
    suggestion match
      case Some(name) =>
        // A past check-in nearby: offer it as the name, confirm with one tap.
        val c = chip(s"「$name」にする", color(attr.primaryContainer), color(attr.onPrimaryContainer))
        c.setMinHeight(dp(36))
        c.setOnClickListener { _ =>
          place.foreach { p =>
            AshidoriApp.io.execute { () =>
              AshidoriApp.db.updatePlace(p.copy(name = name))
              TimelineRepo.invalidate()
              act.runOnUiThread(() => load())
            }
          }
        }
        chips.addView(c)
      case None =>
        if place.forall(!_.named) || v.confidence < 0.6 then
          chips.addView(chip("場所を確認", color(attr.surfaceContainerHigh), color(attr.primary)))
    val body = vbox(
      text(name, 16f, bold = true),
      caption(
        s"${time(v.start)} – ${time(v.end)}・${fmtDuration(v.durationMs)}" +
          // Say so when the position is only roughly known.
          (if v.radius > 80 then s"・範囲 約${(v.radius / 10).round * 10}m" else "")
      ),
      chips
    )
    padded(body, 8, 10)
    body.setMinimumHeight(dp(56))
    body.setBackgroundResource(android.R.drawable.list_selector_background)
    body.setOnClickListener(_ => openVisit(v, tl))
    withRail(RailView(act, true, color(attr.primary), top, bottom), body)

  private def openVisit(v: Visit, tl: DayTimeline): Unit =
    revealMap()
    map.focus(v.latLng)
    v.placeId.flatMap(tl.places.get).foreach { p =>
      PlaceSheet(act, p, Some(v), () => load()).show()
    }

  private def tripRow(t: Trip, top: Boolean, bottom: Boolean): View =
    val legs = t.legs.filter(_.mode != TransportMode.Still)
    val rows = legs.map { l =>
      val label = text(s"${l.mode.emoji} ${l.mode.label}" + (if l.confidence < 0.5 then "?" else ""), 15f)
      label.setLayoutParams(weighted())
      val detail = caption(
        // Rough legs (GNSS gaps, poor fixes): "約" on the distance; the map draws them dashed.
        s"${if l.isUncertain then "約" else ""}${fmtDistance(l.distanceM)}・${fmtDuration(l.durationMs)}" +
          (if l.steps > 0 then f"・${l.steps}%,d歩" else "")
      )
      val row = hbox(label, detail)
      row.setMinimumHeight(dp(48))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setContentDescription(s"${l.mode.label} ${fmtDistance(l.distanceM)}。タップで移動手段を変更")
      row.setOnClickListener { _ =>
        revealMap()
        map.highlight(t.path.filter(f => f.time >= l.start && f.time <= l.end))
        pickMode(l)
      }
      row
    }
    val body = vbox((caption(s"${time(t.start)} – ${time(t.end)}・${fmtDuration(t.durationMs)}") +: rows)*)
    padded(body, 8, 6)
    // The rail takes the color of the main transport mode, like the map line.
    withRail(RailView(act, false, android.graphics.Color.parseColor(t.mainMode.color), top, bottom), body)

  private def pickMode(l: Leg): Unit =
    val modes = TransportMode.values.filter(m => m != TransportMode.Still && m != TransportMode.Unknown)
    val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
    val items = modes.map { m =>
      val t = text(s"${m.emoji}  ${m.label}" + (if m == l.mode then "（現在）" else ""), 16f, bold = m == l.mode)
      t.setMinHeight(dp(52))
      t.setGravity(Gravity.CENTER_VERTICAL)
      t.setBackgroundResource(android.R.drawable.list_selector_background)
      t.setOnClickListener { _ =>
        dialog.dismiss()
        AshidoriApp.io.execute { () =>
          AshidoriApp.db.setModeOverride(l.start, l.end, m)
          TimelineRepo.invalidate()
          act.runOnUiThread(() => load())
        }
      }
      t
    }
    dialog.setContentView(scroll(padded(vbox((text("移動手段を変更", 20f, bold = true) +: items.toSeq)*), 20, 12)))
    dialog.show()
