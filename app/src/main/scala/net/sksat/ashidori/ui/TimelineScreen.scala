package net.sksat.ashidori.ui

import android.app.DatePickerDialog
import android.view.{Gravity, View}
import android.widget.{LinearLayout, TextView}
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Day view: summary, then alternating visit and trip cards. */
final class TimelineScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
  private val dateFmt = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.JAPANESE)

  private val dateLabel = text("", 18f, bold = true)
  private val summary = hbox()
  private val list = vbox()
  private val header =
    val prev = outlined("◀")(shift(-1))
    val next = outlined("▶")(shift(1))
    dateLabel.setGravity(Gravity.CENTER)
    dateLabel.setLayoutParams(weighted())
    dateLabel.setOnClickListener(_ => pickDate())
    padded(hbox(prev, dateLabel, next), 12, 8)

  val view: View = vbox(header, scroll(vbox(card(summary), list, spacer(24))))

  override def onShow(): Unit = load()

  private def shift(days: Int): Unit =
    act.selectedDate = act.selectedDate.plusDays(days)
    load()

  private def pickDate(): Unit =
    val d = act.selectedDate
    DatePickerDialog(act, (_, y, m, dd) => {
      act.selectedDate = LocalDate.of(y, m + 1, dd)
      load()
    }, d.getYear, d.getMonthValue - 1, d.getDayOfMonth).show()

  def load(): Unit =
    val date = act.selectedDate
    dateLabel.setText(
      (if date == LocalDate.now(AshidoriApp.zone) then "今日 " else "") + date.format(dateFmt)
    )
    list.removeAllViews()
    list.addView(padded(caption("読み込み中…"), 16, 8))
    AshidoriApp.io.execute { () =>
      val tl = TimelineRepo.day(date)
      act.runOnUiThread(() => if act.selectedDate == date then render(tl))
    }

  private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  private def stat(value: String, label: String): View =
    val v = vbox(text(value, 18f, bold = true), caption(label))
    v.setGravity(Gravity.CENTER)
    v.setLayoutParams(weighted())
    v

  private def render(tl: DayTimeline): Unit =
    summary.removeAllViews()
    summary.addView(stat(f"${tl.summary.steps}%,d", "歩数"))
    summary.addView(stat(fmtDistance(tl.summary.totalDistanceM), "移動距離"))
    summary.addView(stat(tl.summary.visits.toString, "滞在"))
    list.removeAllViews()
    if tl.segments.isEmpty then
      list.addView(card(text("この日の記録はありません", 15f), caption("ロガー画面で記録を開始してください。")))
    for s <- tl.segments do
      s match
        case v: Visit => list.addView(visitCard(v, tl))
        case t: Trip  => list.addView(tripCard(t))

  private def visitCard(v: Visit, tl: DayTimeline): View =
    val place = v.placeId.flatMap(tl.places.get)
    val name = place.map(_.displayName).getOrElse(f"${v.lat}%.4f, ${v.lon}%.4f")
    val title = text(s"📍 $name", 16f, bold = true)
    val sub = caption(s"${time(v.start)} – ${time(v.end)}・${fmtDuration(v.durationMs)}")
    val chips = hbox()
    place.filter(_.kind != PlaceKind.Other).foreach { p =>
      chips.addView(chip(p.kind.label, color(attr.secondaryContainer), color(attr.onSurface)))
    }
    tl.checkIns.filter(c => c.time >= v.start && c.time <= v.end).foreach { c =>
      chips.addView(chip(s"✓ ${c.venueName}", color(attr.primaryContainer), color(attr.onPrimaryContainer)))
    }
    if v.confidence < 0.6 then chips.addView(chip("この場所で合ってる?", color(attr.surfaceContainerHigh), color(attr.onSurfaceVariant)))
    val c = card(title, sub, chips)
    c.setOnClickListener(_ => act.renamePlace(place, () => load()))
    c

  private def tripCard(t: Trip): View =
    val rows = t.legs.filter(_.mode != TransportMode.Still).map { l =>
      val label = text(s"${l.mode.emoji} ${l.mode.label}", 15f, bold = true)
      label.setLayoutParams(weighted())
      val detail = caption(
        s"${fmtDistance(l.distanceM)}・${fmtDuration(l.durationMs)}" +
          (if l.steps > 0 then f"・${l.steps}%,d歩" else "") +
          (if l.confidence < 0.5 then "・?" else "")
      )
      val row = padded(hbox(label, detail), 0, 4)
      row.setOnClickListener(_ => pickMode(l))
      row
    }
    val head = caption(s"${time(t.start)} – ${time(t.end)}・${fmtDuration(t.durationMs)}・${fmtDistance(t.distanceM)}")
    val c = card((head +: rows)*)
    c.setCardBackgroundColor(color(attr.surface))
    c.setStrokeWidth(dp(1))
    c.setStrokeColor(color(attr.outlineVariant))
    c

  private def pickMode(l: Leg): Unit =
    val modes = TransportMode.values.filter(m => m != TransportMode.Still && m != TransportMode.Unknown)
    MaterialAlertDialogBuilder(act)
      .setTitle("移動手段を変更")
      .setItems(modes.map(m => s"${m.emoji} ${m.label}": CharSequence), (_, i) => {
        AshidoriApp.io.execute { () =>
          AshidoriApp.db.setModeOverride(l.start, l.end, modes(i))
          act.runOnUiThread(() => load())
        }
      })
      .show()
