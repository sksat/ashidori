package net.sksat.ashidori.ui

import android.view.{Gravity, View}
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The rows of one day's timeline: stays and trips on a rail, with what can
  * be done to them (name a place, confirm a suggestion, fix a transport mode).
  * Used by the タイムライン feed; `onChanged` is called after an edit.
  */
final class DayRows(act: MainActivity, onChanged: DayTimeline => Unit):
  private given Ctx = act
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
  private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  /** Rows for a day, newest first; the rail runs through all of them. */
  def rows(tl: DayTimeline): Seq[View] =
    tl.segments.reverse.flatMap {
      case v: Visit => Some(visitRow(v, tl))
      // A trip that is all "still" (e.g. drift overnight) has nothing to show.
      case t: Trip if t.legs.exists(_.mode != TransportMode.Still) => Some(tripRow(t, tl))
      case _ => None
    }

  private def withRail(rail: RailView, content: View): View =
    content.setLayoutParams(weighted())
    val row = hbox(rail, content)
    row.setGravity(Gravity.TOP)
    padded(row, 8, 0)

  private def changed(tl: DayTimeline): Unit =
    TimelineRepo.invalidate()
    act.runOnUiThread(() => onChanged(tl))

  def visitRow(v: Visit, tl: DayTimeline): View =
    val place = v.placeId.flatMap(tl.places.get)
    val name = place.map { p =>
      tl.suggestions.get(p.id).filter(_ => !p.named).map(n => s"$n？").getOrElse(p.displayName)
    }.getOrElse("不明な場所")
    val chips = hbox()
    // The kind as a chip, unless it already is the name ("自宅").
    place.filter(p => p.kind != PlaceKind.Other && p.displayName != p.kind.label).foreach { p =>
      chips.addView(chip(p.kind.label, color(attr.secondaryContainer), color(attr.onSurface)))
    }
    tl.checkIns.filter(c => c.time >= v.start - 60_000 && c.time <= v.end + 60_000).foreach { c =>
      chips.addView(chip(s"✓ ${c.venueName}", color(attr.primaryContainer), color(attr.onPrimaryContainer)))
    }
    place.flatMap(p => tl.suggestions.get(p.id)) match
      case Some(suggested) =>
        // A past check-in nearby: offer it as the name, confirm with one tap.
        val c = chip(s"「$suggested」にする", color(attr.primaryContainer), color(attr.onPrimaryContainer))
        c.setMinHeight(dp(36))
        c.setOnClickListener { _ =>
          place.foreach { p =>
            AshidoriApp.io.execute { () =>
              AshidoriApp.db.updatePlace(p.copy(name = suggested))
              changed(tl)
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
    withRail(RailView(act, true, color(attr.primary), true, true), body)

  def openVisit(v: Visit, tl: DayTimeline): Unit =
    v.placeId.flatMap(tl.places.get).foreach { p =>
      PlaceSheet(act, p, Some(v), () => changed(tl), Some(d => act.openDay(d))).show()
    }

  def tripRow(t: Trip, tl: DayTimeline): View =
    val legs = t.legs.filter(_.mode != TransportMode.Still).reverse
    val rows = legs.map { l =>
      val label = text(s"${l.mode.emoji} ${l.mode.label}" + (if l.confidence < 0.5 then "?" else ""), 15f)
      label.setLayoutParams(weighted())
      val detail = caption(
        // Rough legs (GNSS gaps, poor fixes): "約" on the distance; the map draws them faded.
        s"${if l.isUncertain then "約" else ""}${fmtDistance(l.distanceM)}・${fmtDuration(l.durationMs)}" +
          (if l.steps > 0 then f"・${l.steps}%,d歩" else "")
      )
      val row = hbox(label, detail)
      row.setMinimumHeight(dp(44))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setContentDescription(s"${l.mode.label} ${fmtDistance(l.distanceM)}。タップで移動手段を変更")
      row.setOnClickListener(_ => pickMode(l, tl))
      row
    }
    val body = vbox((caption(s"${time(t.start)} – ${time(t.end)}・${fmtDuration(t.durationMs)}") +: rows)*)
    padded(body, 8, 6)
    // The rail takes the color of the main transport mode, like the map line.
    withRail(RailView(act, false, android.graphics.Color.parseColor(t.mainMode.color), true, true), body)

  def pickMode(l: Leg, tl: DayTimeline): Unit =
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
          changed(tl)
        }
      }
      t
    }
    dialog.setContentView(scroll(padded(vbox((text("移動手段を変更", 20f, bold = true) +: items.toSeq)*), 20, 12)))
    dialog.show()
