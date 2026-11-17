package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, DashPathEffect, Paint}
import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{LinearLayout, TextView}
import com.google.android.material.chip.Chip
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** One entry of a day's rail list: a stay, one leg of a trip, or a stretch
  * without a record.
  */
enum DayItem:
  case Stay(v: Visit)
  case Move(t: Trip, l: Leg)
  case NoRecord(u: Unrecorded)

  def start: Long = this match
    case Stay(v)     => v.start
    case Move(_, l)  => l.start
    case NoRecord(u) => u.start
  def end: Long = this match
    case Stay(v)     => v.end
    case Move(_, l)  => l.end
    case NoRecord(u) => u.end
  /** Stable across reloads of the day (and an edit of the mode). */
  def key: String = this match
    case Stay(v)     => s"s${v.start}"
    case Move(_, l)  => s"m${l.start}"
    case NoRecord(u) => s"u${u.start}"
  /** Display colour: the shared palette. */
  def color: String = this match
    case Stay(_)     => Palette.Stay
    case Move(_, l)  => l.mode.color
    case NoRecord(_) => Palette.Unrecorded

  /** The line drawn for it on the map (none for a stay). */
  def path: Option[MapPath] = this match
    case Move(t, l)  => Some(MapPath(Geo.simplify(t.path.filter(f => f.time >= l.start && f.time <= l.end), 4), l.mode.color, l.isUncertain))
    case NoRecord(u) => Some(MapPath.unrecorded(u))
    case Stay(_)     => None

object DayItem:
  /** The day's items in time order. A leg that is only standing still is
    * part of the trip, not an item of its own.
    */
  def of(tl: DayTimeline): Vector[DayItem] =
    tl.segments.flatMap {
      case v: Visit      => Vector(Stay(v))
      case t: Trip       => t.legs.filter(_.mode != TransportMode.Still).map(Move(t, _))
      case u: Unrecorded => Vector(NoRecord(u))
    }

/** The rail beside an item: a line in the item's colour (dotted for 記録なし)
  * through a round icon. `first` and `last` end the line at the icon.
  */
final class RailIcon(c: Context, item: DayItem, emoji: String, first: Boolean, last: Boolean) extends View(c):
  private given Context = c
  private val col = android.graphics.Color.parseColor(item.color)
  private val gap = item.isInstanceOf[DayItem.NoRecord]
  private val stay = item.isInstanceOf[DayItem.Stay]
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(3).toFloat)
  line.setColor(col)
  line.setStrokeCap(if gap then Paint.Cap.ROUND else Paint.Cap.BUTT)
  if gap then line.setPathEffect(DashPathEffect(Array(0.1f, Ui.dp(7).toFloat), 0))
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
  ring.setStyle(Paint.Style.STROKE)
  ring.setStrokeWidth(Ui.dp(2).toFloat)
  ring.setColor(col)
  if gap then ring.setPathEffect(DashPathEffect(Array(Ui.dp(3).toFloat, Ui.dp(3).toFloat), 0))
  private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
  glyph.setTextAlign(Paint.Align.CENTER)
  glyph.setTextSize(Ui.dp(14).toFloat)
  glyph.setColor(if gap then col else Ui.color(Ui.attr.onSurface))
  setLayoutParams(LinearLayout.LayoutParams(Ui.dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
  setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)

  override def onDraw(canvas: Canvas): Unit =
    val cx = getWidth / 2f
    val cy = Ui.dp(26).toFloat
    val r = Ui.dp(15).toFloat
    val top = if first then cy else 0f
    val bottom = if last then cy else getHeight.toFloat
    if bottom > top then canvas.drawLine(cx, top, cx, bottom, line)
    fill.setColor(if stay then col else Ui.color(Ui.attr.surface))
    canvas.drawCircle(cx, cy, r, fill)
    if !stay then canvas.drawCircle(cx, cy, r, ring)
    val fm = glyph.getFontMetrics
    canvas.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2, glyph)

/** The rows of one day: stays, legs and stretches without a record on a rail,
  * three lines each (name; distance · time or the area; the time range).
  * Tapping an item selects it (`onSelect`, e.g. the map highlights it) and
  * shows what can be done under it: 編集 and 場所を見る. Edits apply at once
  * and can be taken back from the snackbar; `onChanged` redraws the day.
  * Used by the map tab's sheet and the タイムライン feed.
  */
final class DayRows(act: MainActivity, onChanged: DayTimeline => Unit, onSelect: Option[DayItem] => Unit = _ => ()):
  private given Ctx = act
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
  private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  /** The selected item's key, kept across a reload of its day. */
  private var selected: Option[String] = None
  /** Shown items by key, to move the selection without rebuilding the rows. */
  private val shown = scala.collection.mutable.Map.empty[String, ItemView]

  def selection: Option[String] = selected

  /** The shown view of an item, e.g. to scroll it into sight. */
  def viewOf(key: String): Option[View] = shown.get(key).filter(_.isAttachedToWindow)

  /** Selects an item before its day is shown (a recreated activity). */
  def restoreSelection(key: String): Unit = selected = Some(key)

  /** Drops the selection (another day is shown). */
  def clearSelection(): Unit =
    selected.flatMap(shown.get).foreach(_.setSelected(false))
    selected = None

  /** Rows for a day, in time order or newest first. */
  def rows(tl: DayTimeline, newestFirst: Boolean = false): Seq[View] =
    val items = DayItem.of(tl)
    val views = items.zipWithIndex.map { (it, i) =>
      val v = ItemView(it, tl, firstOfDay = i == 0, lastOfDay = i == items.size - 1, items.size)
      shown(it.key) = v
      v
    }
    val ordered = if newestFirst then views.reverse else views
    // The rail starts and ends at the icons of the outer items.
    ordered.zipWithIndex.foreach((v, i) => v.setRail(i == 0, i == ordered.size - 1))
    ordered

  private def changed(tl: DayTimeline): Unit =
    TimelineRepo.invalidate()
    act.runOnUiThread(() => onChanged(tl))

  private def select(it: DayItem): Unit =
    val same = selected.contains(it.key)
    selected.flatMap(shown.get).foreach(_.setSelected(false))
    selected = if same then None else Some(it.key)
    selected.flatMap(shown.get).foreach(_.setSelected(true))
    onSelect(if same then None else Some(it))

  private def placeOf(v: Visit, tl: DayTimeline): Option[Place] = v.placeId.flatMap(tl.places.get)

  private def stayEmoji(p: Option[Place]): String = p.map(_.kind) match
    case Some(PlaceKind.Home)   => "🏠"
    case Some(PlaceKind.Work)   => "🏢"
    case Some(PlaceKind.School) => "🏫"
    case _                      => "📍"

  /** The time line: the day's first stay says when the user left, the last
    * when they arrived.
    */
  private def rangeText(it: DayItem, first: Boolean, last: Boolean, count: Int): String =
    it match
      case DayItem.Stay(_) if first && count > 1 => s"出発 ${time(it.end)}"
      case DayItem.Stay(_) if last && count > 1  => s"到着 ${time(it.start)}"
      case _                                     => s"${time(it.start)} – ${time(it.end)}"

  /** One item: rail, three lines, and (selected) its actions. */
  private final class ItemView(it: DayItem, tl: DayTimeline, firstOfDay: Boolean, lastOfDay: Boolean, count: Int)
      extends LinearLayout(act):
    setOrientation(LinearLayout.HORIZONTAL)
    private val title = oneLine(text("", 15f, bold = !it.isInstanceOf[DayItem.NoRecord]))
    private val second = oneLine(tabular(caption("")))
    private val third = oneLine(tabular(caption(rangeText(it, firstOfDay, lastOfDay, count))))
    private val actions = hbox()
    private val modes = LinearLayout(act)
    private var rail: RailIcon = null
    private val body = vbox(title, second, third, actions, modes)
    private val (emoji, describe) = fill()

    body.setPadding(dp(8), dp(8), dp(12), dp(8))
    body.setLayoutParams(weighted())
    body.setMinimumHeight(dp(64))
    actions.setVisibility(View.GONE)
    modes.setVisibility(View.GONE)
    addView(body)
    setPadding(dp(8), 0, dp(8), 0)
    ripple(this)
    setOnClickListener(_ => select(it))
    setContentDescription(describe)
    a11yRole(this, classOf[android.widget.Button])
    if selected.contains(it.key) then setSelected(true)

    def setRail(first: Boolean, last: Boolean): Unit =
      if rail != null then removeView(rail)
      rail = RailIcon(act, it, emoji, first, last)
      addView(rail, 0)

    override def setSelected(on: Boolean): Unit =
      super.setSelected(on)
      if on then
        val bg = GradientDrawable()
        bg.setCornerRadius(dp(12).toFloat)
        bg.setColor(color(attr.primary) & 0x00ffffff | 0x17000000)
        body.setBackground(bg)
        if actions.getChildCount > 0 then actions.setVisibility(View.VISIBLE)
      else
        body.setBackground(null)
        actions.setVisibility(View.GONE)
        modes.setVisibility(View.GONE)

    private def actionChip(label: String, description: String = "")(onClick: => Unit): Chip =
      val c = Chip(act)
      c.setText(label)
      if description.nonEmpty then c.setContentDescription(description)
      c.setOnClickListener(_ => onClick)
      val lp = wrap
      lp.setMarginEnd(dp(8))
      c.setLayoutParams(lp)
      c

    /** Fills the lines and actions; returns the rail's glyph and the spoken summary. */
    private def fill(): (String, String) = it match
      case DayItem.Stay(v) =>
        val place = placeOf(v, tl)
        val suggestion = place.filter(!_.named).flatMap(p => tl.suggestions.get(p.id))
        val name = place.map(p => suggestion.map(n => s"$n（候補）").getOrElse(p.displayName)).getOrElse("不明な場所")
        title.setText(name)
        val checkIns = tl.checkIns.filter(c => c.time >= v.start - 60_000 && c.time <= v.end + 60_000).map(c => s"✓ ${c.venueName}")
        val area = place.filter(p => p.named && p.area.nonEmpty).map(_.area).toSeq
        val kind = place.filter(p => p.kind != PlaceKind.Other && p.displayName != p.kind.label).map(_.kind.label).toSeq
        val detail = (kind ++ area ++ checkIns).mkString("・")
        second.setText(if detail.nonEmpty then detail else fmtDuration(v.durationMs))
        if detail.nonEmpty && !third.getText.toString.startsWith("出発") && !third.getText.toString.startsWith("到着") then
          third.setText(s"${third.getText}・${fmtDuration(v.durationMs)}")
        place.foreach { p =>
          suggestion.foreach { s =>
            actions.addView(actionChip(s"「$s」にする")(Edits.updatePlace(act, p.id, PlaceEdit.rename(p, s), Edits.renameMessage(s), () => changed(tl))))
          }
          actions.addView(actionChip("✎ 編集", "場所を編集")(PlacePicker(act, p, Some(v), () => changed(tl)).show()))
          actions.addView(actionChip("場所を見る")(PlaceSheet(act, p, Some(v), () => changed(tl), Some(d => act.openDay(d))).show()))
        }
        (stayEmoji(place), s"$name、${third.getText}")
      case DayItem.Move(_, l) =>
        val corrected = if l.corrected then " ✎" else ""
        title.setText(s"${l.mode.label}${if l.confidence < 0.5 then "（推定）" else ""}$corrected")
        // Rough legs (GNSS gaps, poor fixes): "約" on the distance; the map draws them faded.
        second.setText(s"${if l.isUncertain then "約" else ""}${fmtDistance(l.distanceM)} · ${fmtDuration(l.durationMs)}" +
          (if l.steps > 0 then f" · ${l.steps}%,d歩" else ""))
        actions.addView(actionChip("✎ 編集", "移動手段を変更") {
          if modes.getChildCount == 0 then buildModes(l)
          modes.setVisibility(if modes.getVisibility == View.VISIBLE then View.GONE else View.VISIBLE)
        })
        (l.mode.emoji, s"${l.mode.label}${if l.corrected then "（編集済み）" else ""}、${second.getText}、${third.getText}")
      case DayItem.NoRecord(u) =>
        title.setText("記録なし")
        title.setTextColor(color(attr.onSurfaceVariant))
        second.setText(fmtDuration(u.durationMs))
        ("…", s"記録なし、${second.getText}、${third.getText}")

    /** The modes to pick from, the current one checked; a pick applies at once. */
    private def buildModes(l: Leg): Unit =
      val group = com.google.android.material.chip.ChipGroup(act)
      group.setSingleSelection(true)
      for m <- TransportMode.values if m != TransportMode.Still && m != TransportMode.Unknown do
        val c = Chip(act, null, com.google.android.material.R.attr.chipStyle)
        c.setText(s"${m.emoji} ${m.label}")
        c.setCheckable(true)
        c.setChecked(m == l.mode)
        c.setOnClickListener { _ =>
          modes.setVisibility(View.GONE)
          if m != l.mode then Edits.changeMode(act, l, m, () => changed(tl))
          else c.setChecked(true)
        }
        group.addView(c)
      // All the modes in sight, wrapping onto more lines.
      group.setChipSpacingVertical(0)
      modes.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

/** Edits made from the day's list: applied at once, with 取り消し on a snackbar. */
object Edits:
  def snackbar(anchor: View, message: String)(undo: => Unit): Unit =
    com.google.android.material.snackbar.Snackbar.make(anchor, message, com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_LONG)
      .setAction("取り消し", _ => undo)
      .show()

  /** Sets the leg's mode, for the whole leg (also across midnight).
    * 取り消し writes the mode shown before the change
    * ([[TimelineBuilder.undoMode]]): a new row that sync carries like any
    * other (a deleted row would not reach the web viewer); the inferred mode
    * leaves the leg as built.
    */
  def changeMode(act: MainActivity, l: Leg, m: TransportMode, changed: () => Unit): Unit =
    val (s, e) = (l.wholeStart, l.wholeEnd)
    AshidoriApp.io.execute { () =>
      val back = TimelineBuilder.undoMode(l)
      AshidoriApp.db.setModeOverride(s, e, m)
      changed()
      act.runOnUiThread { () =>
        snackbar(act.snackbarAnchor, s"移動手段を${m.label}に変更") {
          AshidoriApp.io.execute { () =>
            AshidoriApp.db.setModeOverride(s, e, back)
            changed()
          }
        }
      }
    }

  /** Applies one change to the place as stored now; 取り消し takes back only
    * that field, and only while it still holds what was set (a later change
    * stays). `done` and `undone` get the stored place, on the main thread.
    */
  def updatePlace(act: MainActivity, placeId: Long, edit: PlaceEdit, message: String, changed: () => Unit,
                  anchor: Option[View] = None, done: Place => Unit = _ => (), undone: Place => Unit = _ => ()): Unit =
    def stored() = AshidoriApp.db.places().find(_.id == placeId)
    if !edit.isNoop then
      AshidoriApp.io.execute { () =>
        stored().foreach { now =>
          val after = edit.applyTo(now)
          AshidoriApp.db.updatePlace(after)
          TimelineRepo.invalidate()
          changed()
          act.runOnUiThread { () =>
            done(after)
            snackbar(anchor.getOrElse(act.snackbarAnchor), message) {
              AshidoriApp.io.execute { () =>
                for now <- stored(); back <- edit.revert(now) do
                  AshidoriApp.db.updatePlace(back)
                  TimelineRepo.invalidate()
                  changed()
                  act.runOnUiThread(() => undone(back))
              }
            }
          }
        }
      }

  def renameMessage(name: String): String = if name.isEmpty then "名前を削除" else s"名前を「$name」に変更"
