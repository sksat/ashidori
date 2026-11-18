package net.sksat.ashidori.ui

import android.text.{Editable, TextWatcher}
import android.view.{Gravity, View, ViewGroup}
import android.view.inputmethod.EditorInfo
import android.widget.{EditText, FrameLayout, LinearLayout}
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** 滞在先を選択: where a past stay was, on a map of the stay (its area
  * marked, the venues of the on-device index drawn), with the candidates
  * below it: the venues nearest the stay and past check-ins there, and a
  * search of the venue index by name. Nothing is sent.
  *
  * A venue tapped on the map (or activated with TalkBack), or a row of the
  * list, checks in to the stay at once ([[CheckIns.toStay]]): the stay's
  * check-in is replaced, and the stay's place named after the venue unless
  * the user named it ([[VenueCheckIn.renames]]). The snackbar's 取り消し
  * restores both. The venue of the stay's check-in is marked on the map.
  * Until a save is stored (and until the stay's check-in is first read),
  * further picks are ignored. The one way to correct a past stay, from the
  * day's 編集 and from the place sheet.
  */
final class VenuePicker(act: MainActivity, visit: Visit, onChanged: () => Unit) extends Screen:
  private given Ctx = act
  override def title: String = "滞在先を選択"
  private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
  private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)

  private val map = TrackMap(act, null, MapStyle.SurfacePicker)
  private var destroyed = false
  /** The stay's check-in as stored (read by [[reload]]): what the map marks. */
  private var existing: Option[CheckIn] = None
  /** A save on its way, or the first read: picks are ignored meanwhile. */
  private var busy = true
  /** The venue being saved, marked on the map meanwhile. */
  private var inFlight: Option[VenueLayer.Picked] = None
  /** The candidates near the stay, and the matches of the typed name. */
  private var nearby = Vector.empty[(VenueLayer.Picked, Double)]
  @volatile private var generation = 0

  private val stayLine = oneLine(tabular(text(s"${time(visit.start)} – ${time(visit.end)}・${fmtDuration(visit.durationMs)}", 15f, bold = true)))
  private val current = oneLine(caption(""))
  private val list = vbox()
  private val search =
    val e = EditText(act)
    e.setHint("施設を検索、または名前を入力")
    e.setSingleLine(true)
    e.setImeOptions(EditorInfo.IME_ACTION_SEARCH)
    e.setMinHeight(dp(48))
    e.addTextChangedListener(new TextWatcher:
      override def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      override def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      override def afterTextChanged(s: Editable): Unit = query(s.toString.trim))
    e

  val view: View =
    val panel = vbox(padded(vbox(stayLine, current), 20, 8), padded(search, 16, 0), list, spacer(24))
    val sc = scroll(panel)
    sc.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    map.view.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f))
    vbox(map.view, sc)

  // The stay on the map: its area and its point, the camera on it.
  map.markStay(Some((visit.latLng, math.max(visit.radius, 15.0))))
  map.setData(Nil, Seq(MapMarker(visit.latLng, visit.start.toString)), Nil, fit = false)
  // The stay's marker lies among the venues: a tap there picks a venue.
  map.venuesFirst = true
  map.venueActionLabel = Some("チェックイン")
  // The area and a margin around it, so the venues there are in sight.
  locally {
    val r = math.max(visit.radius, 60.0) * 1.3
    val dLat = r / 111_195.0
    val dLon = dLat / math.cos(math.toRadians(visit.lat))
    map.show(Seq(LatLng(visit.lat - dLat, visit.lon - dLon), LatLng(visit.lat + dLat, visit.lon + dLon)))
  }
  map.onVenue = {
    case Some(p) => choose(p)
    // A tap elsewhere: the check-in's venue stays marked.
    case None => markCurrent()
  }
  reload(first = true)

  override def onShow(): Unit = if !destroyed then map.onScreen()

  /** Popped (or left for another tab): the map ends with the screen. */
  override def onHide(): Unit =
    if !destroyed then
      destroyed = true
      generation += 1
      act.getSystemService(classOf[android.view.inputmethod.InputMethodManager]).hideSoftInputFromWindow(search.getWindowToken, 0)
      map.offScreen()
      map.onDestroy()

  /** The stay's check-in and place as stored, and (`first`) the candidates. */
  private def reload(first: Boolean = false): Unit =
    AshidoriApp.io.execute { () =>
      val ci = VenueCheckIn.ofStay(AshidoriApp.db.checkIns(visit.start - 60_000, visit.end + 60_001), visit)
      val cands = if first then Some(candidates()) else None
      act.runOnUiThread { () =>
        if !destroyed then
          existing = ci
          busy = false
          inFlight = None
          current.setText(ci.fold("")(c => s"✓ ${c.venueName}"))
          cands.foreach { cs =>
            nearby = cs.map((v, tag, d) => (map.venueAsDrawn(v, tag), d))
            if search.getText.toString.trim.isEmpty then showRows(nearby, "")
          }
          markCurrent()
      }
    }

  /** The venue of the stay's check-in, marked on the map (as drawn when it is a candidate). */
  private def markCurrent(): Unit = map.pickVenue(inFlight.orElse(currentPick))

  private def currentPick: Option[VenueLayer.Picked] =
    existing.map { c =>
      nearby.map(_._1).find(p => c.venueExternalId.nonEmpty && p.venue.id == c.venueExternalId)
        .getOrElse(map.venueAsDrawn(Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon), None))
    }

  /** Venues near the stay from the index (with their tags), and past
    * check-ins there not among them, nearest first. I/O thread.
    */
  private def candidates(): Vector[(Venue, Option[(String, String)], Double)] =
    val at = visit.latLng
    val r = math.max(VenueCheckIn.StayM, visit.radius) + 50
    val dLat = r / 111_195.0
    val dLon = dLat / math.cos(math.toRadians(at.lat))
    val indexed = VenueData.index().toVector.flatMap(ix =>
      scala.util.Try(ix.within(at.lat - dLat, at.lon - dLon, at.lat + dLat, at.lon + dLon)).getOrElse(Vector.empty))
      .map(t => (t.venue.copy(category = VenueLayer.kindOf(t.venue.category)), Option((t.key, t.value)), t.venue.latLng.distanceTo(at)))
      .filter(_._3 <= r)
    val past = AshidoriApp.db.checkIns(0, Long.MaxValue)
      .map(c => Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon))
      .map(v => v -> v.latLng.distanceTo(at)).filter(_._2 <= VenueCheckIn.StayM)
    val fresh = VenueMerge.fresh(indexed.map(x => (x._1, x._3)), past.toVector)
    (indexed.sortBy(_._3).distinctBy(_._1.name) ++ fresh.map((v, d) => (v, None, d))).sortBy(_._3).take(30)

  /** Searches the venue index by name (nothing is sent); empty: the candidates near the stay. */
  private def query(q: String): Unit =
    val my = { generation += 1; generation }
    if q.isEmpty then showRows(nearby, "")
    else
      AshidoriApp.io.execute { () =>
        val found = VenueData.index().toVector.flatMap { ix =>
          scala.util.Try(ix.search(q, visit.latLng, limit = 20)).getOrElse(Vector.empty).map { (v0, d) =>
            val v = v0.copy(category = VenueLayer.kindOf(v0.category))
            // The tag, for the colour and icon the map gives it.
            val e = 0.0002
            val tag = scala.util.Try(ix.within(v.lat - e, v.lon - e, v.lat + e, v.lon + e)).toOption.flatMap(_.find(_.venue.id == v.id))
              .map(t => (t.key, t.value))
            (v, tag, d)
          }
        }
        act.runOnUiThread(() => if my == generation && !destroyed then showRows(found.map((v, t, d) => (map.venueAsDrawn(v, t), d)), q))
      }

  private def showRows(vs: Seq[(VenueLayer.Picked, Double)], q: String): Unit =
    list.removeAllViews()
    // The typed name as it is, when no venue has it.
    if q.nonEmpty && !vs.exists(_._1.venue.name == q) then
      list.addView(row(map.venueAsDrawn(Venue("", q, "", Double.NaN, Double.NaN), None), s"「$q」でチェックイン", ""))
    for (p, d) <- vs do
      list.addView(row(p, p.venue.name, (Seq(p.venue.category).filter(_.nonEmpty) :+ fmtDistance(d)).mkString("・")))
    if vs.isEmpty && q.isEmpty then list.addView(padded(caption("近くに候補なし"), 20, 8))

  private def row(p: VenueLayer.Picked, title: String, sub: String): View =
    val ic = FrameLayout(act)
    VenueCard.fillIcon(ic, p)
    ic.setLayoutParams(LinearLayout.LayoutParams(dp(36), dp(36)))
    val texts = vbox(oneLine(text(title, 15f)))
    if sub.nonEmpty then texts.addView(oneLine(tabular(caption(sub))))
    val tlp = weighted()
    tlp.setMarginStart(dp(12))
    texts.setLayoutParams(tlp)
    val r = hbox(ic, texts)
    r.setGravity(Gravity.CENTER_VERTICAL)
    r.setMinimumHeight(dp(56))
    r.setPadding(dp(20), dp(6), dp(20), dp(6))
    ripple(r)
    a11yRole(r, classOf[android.widget.Button])
    r.setContentDescription(Seq(title, sub).filter(_.nonEmpty).mkString("、"))
    r.setOnClickListener(_ => choose(p))
    r

  /** Checks in to the stay at `p`, replacing its check-in; the map marks it. */
  private def choose(p: VenueLayer.Picked): Unit =
    val v = p.venue
    // Ignored while busy: the ring stays on the venue being saved (or the stay's).
    if busy then markCurrent()
    else if existing.exists(c => c.venueName == v.name && c.venueExternalId == v.id) then markCurrent()
    else
      busy = true
      inFlight = Option.when(!v.lat.isNaN)(p)
      map.pickVenue(inFlight)
      // The stay's check-in is read again when it is saved: what is shown may be old.
      CheckIns.toStay(act, v, visit, CheckIns.message(v.name, Some(visit)), () => {
        reload()
        onChanged()
      })
