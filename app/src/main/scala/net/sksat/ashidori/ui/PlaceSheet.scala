package net.sksat.ashidori.ui

import android.view.View
import android.widget.EditText
import com.google.android.material.bottomsheet.{BottomSheetBehavior, BottomSheetDialog}
import com.google.android.material.chip.{Chip, ChipGroup}
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Details and corrections for one place, opened from a visit or the map.
  *
  * Order follows what users most often want: confirm or fix what the place
  * is (candidates), then name/kind, then actions.
  */
final class PlaceSheet(
    act: MainActivity,
    place: Place,
    visit: Option[Visit],
    onChanged: () => Unit,
    onOpenDay: Option[java.time.LocalDate => Unit] = None,
    /** past visits to show as history (newest first) */
    visits: Seq[Visit] = Nil
):
  private given Ctx = act
  private val dialog = BottomSheetDialog(act)
  private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
  private var current = place

  private val title = text(place.displayName, 20f, bold = true)
  private val sub = caption("")
  private val candidates = vbox(caption("近くの施設を検索中…"))
  private val nameInput = EditText(act)
  private val kinds = ChipGroup(act)
  private val history = vbox()
  private val visitList =
    val dayFmt = DateTimeFormatter.ofPattern("yyyy/M/d (E) HH:mm", java.util.Locale.JAPANESE)
    val v = vbox()
    for vis <- visits.take(30) do
      val row = vbox(text(Instant.ofEpochMilli(vis.start).atZone(AshidoriApp.zone).format(dayFmt), 15f), caption(fmtDuration(vis.durationMs)))
      row.setMinimumHeight(dp(48))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      onOpenDay.foreach { open =>
        row.setOnClickListener { _ =>
          dialog.dismiss()
          open(Stats.localDate(vis.start, AshidoriApp.zone))
        }
      }
      v.addView(row)
    v

  def show(): Unit =
    visit.foreach(v => sub.setText(s"${fmt(v.start)} – ${fmt(v.end)}・${fmtDuration(v.durationMs)}"))
    nameInput.setText(place.name)
    nameInput.setHint("名前")
    nameInput.setSingleLine(true)
    kinds.setSingleSelection(true)
    for k <- Seq(PlaceKind.Home, PlaceKind.Work, PlaceKind.School) do
      val c = Chip(act, null, com.google.android.material.R.attr.chipStyle)
      c.setText(k.label)
      c.setCheckable(true)
      c.setChecked(place.kind == k)
      c.setOnClickListener(_ => save(current.copy(kind = if c.isChecked then k else PlaceKind.Other)))
      kinds.addView(c)

    val actions = vbox()
    visit.foreach { v =>
      actions.addView(button("この滞在にチェックイン", tonal = true) {
        dialog.dismiss()
        CheckInSheet(act, Some((v.latLng, v.start + math.min(v.durationMs / 2, 10 * 60_000L))), onChanged).show()
      })
    }
    val root = padded(
      vbox(
        title,
        sub,
        spacer(12),
        text("近くの候補", 15f, bold = true),
        candidates,
        spacer(12),
        text("名前と種別", 15f, bold = true),
        hbox(nameInput.tap(_.setLayoutParams(weighted())), outlined("保存")(save(current.copy(name = nameInput.getText.toString.trim)))),
        kinds,
        spacer(8),
        actions,
        spacer(12),
        text(if visits.nonEmpty then s"滞在履歴（${visits.size}回）" else "", 15f, bold = true),
        visitList,
        text("この場所でのチェックイン", 15f, bold = true),
        history
      ),
      20,
      12
    )
    dialog.setContentView(scroll(root))
    Ui.fullHeight(dialog)
    dialog.show()
    loadCandidates()
    loadHistory()

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }

  private def fmt(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  /** A candidate, and at its end what a tap does (like the timeline's 「「X」にする」). */
  private def candidateRow(v: Venue, sub: String): View =
    val t = vbox(oneLine(text(v.name, 15f)), oneLine(caption(sub)))
    t.setLayoutParams(weighted())
    val r = hbox(t, chip("名前にする", color(attr.primaryContainer), color(attr.onPrimaryContainer)))
    r.setGravity(android.view.Gravity.CENTER_VERTICAL)
    r.setMinimumHeight(dp(48))
    r.setBackgroundResource(android.R.drawable.list_selector_background)
    r.setOnClickListener(_ => save(current.copy(name = v.name, externalId = v.id)))
    r

  /** Past check-ins nearby first, then OpenStreetMap venues from the venue
    * index and the saved results: nothing is sent. The online search runs
    * only when the user presses 「オンラインで検索」.
    */
  /** Candidate rows, then one status line (updated as the search goes on), then the button. */
  private val candidateRows = vbox()
  private val candidateStatus = caption("")

  private def setStatus(text: String): Unit =
    candidateStatus.setText(text)
    candidateStatus.setVisibility(if text.isEmpty then View.GONE else View.VISIBLE)

  private def loadCandidates(): Unit =
    AshidoriApp.io.execute { () =>
      val past = AshidoriApp.db.checkIns(0, Long.MaxValue)
        .map(c => Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon))
        .map(v => v -> v.latLng.distanceTo(place.latLng))
        .filter(_._2 <= 150).sortBy(_._2).distinctBy(_._1.name)
      // The venue index and saved venues from earlier searches: nothing is sent.
      val offline = VenueData.lookup(place.latLng, 120)
      val saved = scala.util.Try(VenueSearch.saved(place.latLng, 120)).toOption.flatten
      act.runOnUiThread { () =>
        candidates.removeAllViews()
        candidateRows.removeAllViews()
        candidates.addView(candidateRows)
        candidates.addView(candidateStatus)
        for (v, d) <- past.take(4) do candidateRows.addView(candidateRow(v, s"チェックイン済み・${d.round} m"))
        val fromIndex = offline match
          case VenueData.Lookup.Found(vs) => CheckInSheet.fresh(past.toVector, vs).take(6)
          case _ => Vector.empty
        for (v, d) <- fromIndex do candidateRows.addView(candidateRow(v, s"${v.category}・${d.round}m"))
        val shown = past ++ fromIndex ++ showSaved(past ++ fromIndex, saved)
        setStatus(
          if offline == VenueData.Lookup.Broken then "施設データを読み込めません"
          else if shown.isEmpty then "近くに候補なし"
          else "")
        latestButton(shown)
      }
    }

  /** Saved venues not among `past`, marked as saved; returns those shown. */
  private def showSaved(past: Seq[(Venue, Double)], saved: Option[VenueCache.Hit]): Seq[(Venue, Double)] =
    val shown = CheckInSheet.fresh(past.toVector, saved.toVector.flatMap(_.venues)).take(6)
    for (v, d) <- shown do candidateRows.addView(candidateRow(v, s"保存済み・${v.category}・${d.round}m"))
    shown

  /** 「オンラインで検索」: the only way to the online search. Sending is the exception: tertiary colours. */
  private def latestButton(shown: Seq[(Venue, Double)]): Unit =
    lazy val once: View = outlined("オンラインで検索（位置を送信）") {
      candidates.removeView(once)
      searchOnline(shown)
    }.tap(b => tint(b, color(attr.tertiaryContainer), color(attr.onTertiaryContainer)))
    candidates.addView(once)

  /** OpenStreetMap venues near the place: sends its position to the server,
    * only when the user pressed 「オンラインで検索」. `shown` are the candidates
    * already shown; new ones go below them, and the status line says how
    * the search ended.
    */
  private def searchOnline(shown: Seq[(Venue, Double)]): Unit =
    setStatus("近くの施設を検索中…")
    VenueSearch.latest(place.latLng, 120) { r =>
      r.fold(
        e => {
          setStatus(e match
            case v: VenueError => if VenueData.installed then v.withIndex else if shown.nonEmpty then v.withCache else v.message
            case _             => "候補を取得できませんでした"
          )
          latestButton(shown)
        },
        a =>
          val fresh = CheckInSheet.fresh(shown.toVector, a.venues)
          for (v, d) <- fresh.take(6) do candidateRows.addView(candidateRow(v, s"${v.category}・${d.round}m"))
          setStatus(
            if fresh.isEmpty && shown.isEmpty then "近くに候補なし（OpenStreetMap）"
            else "OpenStreetMap から取得")
      )
    }

  private def loadHistory(): Unit =
    AshidoriApp.io.execute { () =>
      val cs = AshidoriApp.db.checkInsAtPlace(place.id)
      act.runOnUiThread { () =>
        history.removeAllViews()
        if cs.isEmpty then history.addView(caption("なし"))
        for c <- cs.take(20) do
          val r = padded(vbox(text(c.venueName, 15f), caption(fmt(c.time) + (if c.comment.nonEmpty then s"「${c.comment}」" else ""))), 0, 4)
          onOpenDay.foreach { open =>
            r.setOnClickListener { _ =>
              dialog.dismiss()
              open(Stats.localDate(c.time, AshidoriApp.zone))
            }
          }
          history.addView(r)
      }
    }

  private def save(p: Place): Unit =
    current = p
    title.setText(p.displayName)
    nameInput.setText(p.name)
    AshidoriApp.io.execute { () =>
      AshidoriApp.db.updatePlace(p)
      TimelineRepo.invalidate()
      act.runOnUiThread(() => onChanged())
    }
