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
        caption("正しい場所を選ぶと、この場所の名前になります。"),
        candidates,
        spacer(12),
        text("名前と種別", 15f, bold = true),
        hbox(nameInput.tap(_.setLayoutParams(weighted())), outlined("保存")(save(current.copy(name = nameInput.getText.toString.trim)))),
        kinds,
        spacer(8),
        actions,
        spacer(12),
        text(if visits.nonEmpty then s"訪問履歴（${visits.size}回）" else "", 15f, bold = true),
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

  private def candidateRow(v: Venue, sub: String): View =
    val t = text(v.name, 15f)
    t.setLayoutParams(weighted())
    val r = hbox(t, caption(sub))
    r.setMinimumHeight(dp(48))
    r.setBackgroundResource(android.R.drawable.list_selector_background)
    r.setOnClickListener(_ => save(current.copy(name = v.name, externalId = v.id)))
    r

  /** Past check-ins nearby first (works offline), then OpenStreetMap venues. */
  private def loadCandidates(): Unit =
    AshidoriApp.io.execute { () =>
      val past = AshidoriApp.db.checkIns(0, Long.MaxValue)
        .map(c => Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon))
        .map(v => v -> v.latLng.distanceTo(place.latLng))
        .filter(_._2 <= 150).sortBy(_._2).distinctBy(_._1.name)
      act.runOnUiThread { () =>
        candidates.removeAllViews()
        for (v, d) <- past.take(4) do candidates.addView(candidateRow(v, s"以前チェックイン・${d.round}m"))
        if Settings.blockExternalLocation && VenueSearch.cached(place.latLng, 120).isEmpty then
          // Sent only on an explicit request.
          candidates.addView(caption("過去のチェックイン先のみを表示しています。検索すると、位置情報が OpenStreetMap のサーバに送られます。"))
          lazy val once: View = outlined("周辺の施設を検索（位置情報を送信）") {
            candidates.removeView(once)
            searchOnline(past)
          }
          candidates.addView(once)
        else searchOnline(past)
      }
    }

  /** OpenStreetMap venues near the place: sends its position to the server. */
  private def searchOnline(past: Seq[(Venue, Double)]): Unit =
    val loading = caption("近くの施設を検索中…")
    candidates.addView(loading)
    VenueSearch.nearby(place.latLng, 120) { r =>
      candidates.removeView(loading)
      r.fold(
        e => candidates.addView(caption(e match
          case v: VenueError => v.message
          case _             => "候補を取得できませんでした"
        )),
        vs =>
          val fresh = vs.filterNot(x => past.exists(_._1.name == x._1.name))
          if fresh.isEmpty && past.isEmpty then candidates.addView(caption("近くに候補がありません"))
          for (v, d) <- fresh.take(6) do candidates.addView(candidateRow(v, s"${v.category}・${d.round}m"))
      )
    }

  private def loadHistory(): Unit =
    AshidoriApp.io.execute { () =>
      val cs = AshidoriApp.db.checkInsAtPlace(place.id)
      act.runOnUiThread { () =>
        history.removeAllViews()
        if cs.isEmpty then history.addView(caption("まだありません"))
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
