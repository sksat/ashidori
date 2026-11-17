package net.sksat.ashidori.ui

import android.text.{Editable, TextWatcher}
import android.view.{Gravity, View, ViewGroup}
import android.view.inputmethod.EditorInfo
import android.widget.{EditText, LinearLayout}
import com.google.android.material.bottomsheet.{BottomSheetDialog, BottomSheetDragHandleView}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** 場所を選択: what a stay was, from candidates nearby (past check-ins, the
  * on-device venue data, venues saved from earlier searches), closest first,
  * and a search of the venue data by name. Nothing is sent. A choice renames
  * the place at once; the snackbar takes it back.
  */
final class PlacePicker(act: MainActivity, place: Place, visit: Option[Visit], onChanged: () => Unit):
  private given Ctx = act
  private val dialog = BottomSheetDialog(act)
  private val at = visit.map(_.latLng).getOrElse(place.latLng)
  private val list = vbox()
  private val status = caption("")
  private var nearby = Vector.empty[(Venue, Double)]
  @volatile private var generation = 0

  def show(): Unit =
    val search = EditText(act)
    search.setHint("🔍  場所を検索")
    search.setSingleLine(true)
    search.setImeOptions(EditorInfo.IME_ACTION_SEARCH)
    search.setMinHeight(dp(48))
    val sbg = android.graphics.drawable.GradientDrawable()
    sbg.setCornerRadius(dp(24).toFloat)
    sbg.setStroke(dp(1), color(attr.outlineVariant))
    search.setBackground(sbg)
    search.setPadding(dp(16), dp(8), dp(16), dp(8))
    search.addTextChangedListener(new TextWatcher:
      override def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      override def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      override def afterTextChanged(s: Editable): Unit = query(s.toString.trim)
    )
    val cancel = com.google.android.material.button.MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle)
    cancel.setText("キャンセル")
    cancel.setOnClickListener(_ => dialog.dismiss())
    val buttons = hbox(cancel)
    buttons.setGravity(Gravity.END)
    val slp = matchWrap
    slp.setMargins(dp(16), dp(8), dp(16), 0)
    search.setLayoutParams(slp)
    status.setPadding(dp(20), dp(4), dp(20), dp(4))
    val root = vbox(
      DayParts.dragHandle(act),
      padded(text("場所を選択", 18f, bold = true), 20, 4),
      list,
      status,
      search,
      padded(buttons, 12, 8)
    )
    dialog.setContentView(scroll(root))
    dialog.show()
    load()

  private def current: View =
    row("✓", place.displayName, "現在の名前", "", chosen = true)(dialog.dismiss())

  private def load(): Unit =
    list.removeAllViews()
    list.addView(current)
    AshidoriApp.io.execute { () =>
      val past = AshidoriApp.db.checkIns(0, Long.MaxValue)
        .map(c => Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon))
        .map(v => v -> v.latLng.distanceTo(at)).filter(_._2 <= 150).sortBy(_._2).distinctBy(_._1.name)
      val offline = VenueData.lookup(at, 120) match
        case VenueData.Lookup.Found(vs) => vs
        case _                          => Vector.empty
      val saved = scala.util.Try(VenueSearch.saved(at, 120)).toOption.flatten.toVector.flatMap(_.venues)
      val all = VenueMerge.fresh(VenueMerge.fresh(past.toVector, offline), saved)
      val merged = (past.toVector ++ all).distinctBy(_._1.name).filterNot(_._1.name == place.name).sortBy(_._2).take(8)
      act.runOnUiThread { () =>
        nearby = merged
        showVenues(merged, "")
      }
    }

  private def showVenues(vs: Seq[(Venue, Double)], q: String): Unit =
    list.removeAllViews()
    list.addView(current)
    // The typed name as it is, when no venue has it.
    if q.nonEmpty && !vs.exists(_._1.name == q) then
      list.addView(row("✎", s"「$q」", "この名前にする", "")(choose(q, None)))
    for (v, d) <- vs do list.addView(row("📍", v.name, v.category, fmtDistance(d))(choose(v.name, Some(v.id))))
    status.setText(if vs.isEmpty && q.isEmpty then "近くに候補なし" else "")
    status.setVisibility(if status.getText.length == 0 then View.GONE else View.VISIBLE)

  /** Searches the venue data by name (nothing is sent); empty: the nearby candidates. */
  private def query(q: String): Unit =
    val my = { generation += 1; generation }
    if q.isEmpty then showVenues(nearby, "")
    else
      AshidoriApp.io.execute { () =>
        val found = VenueData.search(q, at).getOrElse(Vector.empty).take(12)
        act.runOnUiThread(() => if my == generation then showVenues(found, q))
      }

  private def choose(name: String, externalId: Option[String]): Unit =
    dialog.dismiss()
    Edits.updatePlace(act, place.id, PlaceEdit.rename(place, name, externalId), s"場所を「$name」に変更", onChanged)

  private def row(icon: String, title: String, sub: String, trailing: String, chosen: Boolean = false)(onClick: => Unit): View =
    val ic = text(icon, 16f)
    ic.setGravity(Gravity.CENTER)
    val ibg = android.graphics.drawable.GradientDrawable()
    ibg.setShape(android.graphics.drawable.GradientDrawable.OVAL)
    ibg.setColor(color(attr.surfaceContainerHigh))
    ic.setBackground(ibg)
    ic.setLayoutParams(LinearLayout.LayoutParams(dp(36), dp(36)))
    ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val texts = vbox(oneLine(text(title, 15f)))
    if sub.nonEmpty then texts.addView(oneLine(caption(sub)))
    val tlp = weighted()
    tlp.setMarginStart(dp(12))
    texts.setLayoutParams(tlp)
    val r = hbox(ic, texts)
    if trailing.nonEmpty then r.addView(tabular(caption(trailing)))
    r.setGravity(Gravity.CENTER_VERTICAL)
    r.setMinimumHeight(dp(56))
    r.setPadding(dp(20), dp(6), dp(20), dp(6))
    if chosen then r.setBackgroundColor(color(attr.primaryContainer))
    else ripple(r)
    r.setOnClickListener(_ => onClick)
    r.setContentDescription(Seq(title, sub, trailing).filter(_.nonEmpty).mkString("、"))
    r
