package net.sksat.ashidori.ui

import android.location.{Location, LocationManager}
import android.text.{Editable, TextWatcher}
import android.view.View
import android.widget.EditText
import com.google.android.material.bottomsheet.{BottomSheetBehavior, BottomSheetDialog}
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.util.{Try, Using}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Venue search on OpenStreetMap, shared by the check-in sheet and the place sheet. */
object VenueSearch:
  private val net = Executors.newSingleThreadExecutor()

  def nearby(p: LatLng, radiusM: Int = 200)(done: Try[Vector[(Venue, Double)]] => Unit): Unit =
    net.execute { () =>
      val r = Try {
        val conn = URL(Overpass.Endpoint).openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(10_000)
        conn.setReadTimeout(20_000)
        conn.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName} (Android)")
        val body = "data=" + URLEncoder.encode(Overpass.query(p.lat, p.lon, radiusM), StandardCharsets.UTF_8)
        Using.resource(conn.getOutputStream)(_.write(body.getBytes(StandardCharsets.UTF_8)))
        if conn.getResponseCode != 200 then throw RuntimeException(s"HTTP ${conn.getResponseCode}")
        val json = Using.resource(conn.getInputStream)(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
        Overpass.parse(json, p.lat, p.lon)
      }
      AshidoriApp.app.getMainExecutor.execute(() => done(r))
    }

/** Swarm-style check-in as a bottom sheet: search on top, nearby venues,
  * then a short compose step. `at` checks in to a past visit instead of the
  * current location.
  */
final class CheckInSheet(act: MainActivity, at: Option[(LatLng, Long)] = None, onDone: () => Unit = () => ()):
  private given Ctx = act
  private val dialog = BottomSheetDialog(act)
  private var here: Option[LatLng] = at.map(_._1)
  private var venues: Vector[(Venue, Double)] = Vector.empty

  private val search = EditText(act)
  search.setHint("施設を検索、または名前を入力")
  search.setSingleLine(true)
  private val status = caption("")
  private val list = vbox()

  // Compose step
  private val chosen = text("", 18f, bold = true)
  private val comment = EditText(act)
  comment.setHint("ひとこと（任意）")
  private val priv = MaterialSwitch(act)
  priv.setText("非公開にする（エクスポートや共有に含めない）")
  priv.setMinHeight(dp(48))
  private var selected: Option[Venue] = None
  private val commitButton =
    val b = button("チェックインする")(commit())
    b.setMinHeight(dp(56))
    b.setTextSize(16f)
    b
  private val compose = vbox(chosen, comment, priv, spacer(8), commitButton, outlined("施設を選び直す")(showPick()))
  private val pick = vbox(
    text(if at.isDefined then "この滞在にチェックイン" else "チェックイン", 20f, bold = true),
    spacer(4),
    search,
    status,
    list,
    spacer(8),
    caption("近くの施設は OpenStreetMap から取得します。取得の際、位置がそのサーバに送られます。")
  )

  def show(): Unit =
    val root = padded(vbox(pick, compose), 20, 12)
    compose.setVisibility(View.GONE)
    dialog.setContentView(scroll(root))
    dialog.getBehavior.setState(BottomSheetBehavior.STATE_EXPANDED)
    dialog.show()
    search.addTextChangedListener(new TextWatcher:
      def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = render()
      def afterTextChanged(s: Editable): Unit = ()
    )
    here match
      case Some(p) => fetch(p)
      case None    => locate()

  private def locate(): Unit =
    status.setText("現在地を取得中…")
    LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 2 * 60_000) match
      case Some(f) => fetch(f.latLng)
      case None =>
        try
          act.getSystemService(classOf[LocationManager]).getCurrentLocation(
            LocationManager.FUSED_PROVIDER, null, act.getMainExecutor, (l: Location) =>
              if l != null then fetch(LatLng(l.getLatitude, l.getLongitude))
              else status.setText("現在地を取得できませんでした。名前を入力してチェックインできます。")
          )
        catch case _: SecurityException => status.setText("位置情報の権限がありません")

  private def fetch(p: LatLng): Unit =
    here = Some(p)
    if !Settings.onlineVenues then
      status.setText("施設の検索はオフです。名前を入力してチェックインできます。")
      render()
    else
      status.setText("近くの施設を探しています…")
      VenueSearch.nearby(p) { r =>
        r.fold(
          e => status.setText(s"施設を取得できませんでした（${e.getMessage}）。名前を入力してチェックインできます。"),
          vs => { venues = vs; status.setText("") }
        )
        render()
      }

  private def row(title: String, sub: String)(onClick: => Unit): View =
    val t = text(title, 16f)
    t.setLayoutParams(weighted())
    val r = hbox(t, caption(sub))
    r.setMinimumHeight(dp(52))
    r.setOnClickListener(_ => onClick)
    r.setBackgroundResource(android.R.drawable.list_selector_background)
    r

  private def render(): Unit =
    val q = search.getText.toString.trim
    list.removeAllViews()
    if q.nonEmpty then
      list.addView(row(s"「$q」でチェックイン", "新しい場所")(select(Venue("", q, "", Double.NaN, Double.NaN))))
    val shown = venues.filter((v, _) => q.isEmpty || v.name.toLowerCase.contains(q.toLowerCase) || v.category.contains(q))
    for (v, d) <- shown.take(30) do
      list.addView(row(v.name, s"${v.category}・${d.round}m")(select(v)))

  private def select(v: Venue): Unit =
    selected = Some(v)
    chosen.setText(v.name + (if v.category.nonEmpty then s"（${v.category}）" else ""))
    pick.setVisibility(View.GONE)
    compose.setVisibility(View.VISIBLE)

  private def showPick(): Unit =
    compose.setVisibility(View.GONE)
    pick.setVisibility(View.VISIBLE)

  private def commit(): Unit =
    selected.foreach { v =>
      val p = if v.lat.isNaN then here.getOrElse(LatLng(0, 0)) else v.latLng
      val time = at.map(_._2).getOrElse(System.currentTimeMillis())
      val ci = CheckIn(0, time, p.lat, p.lon, v.name, v.category, v.id, None, comment.getText.toString.trim, priv.isChecked)
      dialog.dismiss()
      AshidoriApp.io.execute { () =>
        val id = CheckIns.save(ci, v)
        TimelineRepo.invalidate()
        act.runOnUiThread { () =>
          onDone()
          Snackbar.make(act.snackbarAnchor, s"${v.name} にチェックインしました", com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_LONG)
            .setAction("取り消し", _ => AshidoriApp.io.execute { () =>
              AshidoriApp.db.deleteCheckIn(id)
              TimelineRepo.invalidate()
              act.runOnUiThread(() => onDone())
            })
            .show()
        }
      }
    }

object CheckIns:
  /** Stores a check-in and links it to the matching place, naming the place
    * after the venue when it has no name yet. Runs on the I/O thread.
    */
  def save(ci: CheckIn, v: Venue): Long =
    val db = AshidoriApp.db
    val visit = Visit(ci.time, ci.time, ci.lat, ci.lon, 30, 1)
    val placeId = Places.nearest(visit, db.places()) match
      case Some(pl) =>
        if pl.name.isEmpty then db.updatePlace(pl.copy(name = v.name, externalId = v.id))
        pl.id
      case None => db.insertPlace(Place(0, ci.lat, ci.lon, 50, v.name, PlaceKind.Other, v.id))
    db.insertCheckIn(ci.copy(placeId = Some(placeId)))
