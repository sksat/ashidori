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

/** Why a venue search returned nothing. */
enum VenueError(val message: String) extends Exception(message):
  case Offline extends VenueError("オフライン: よく行く場所と過去のチェックイン先を表示しています。")
  case ServerBusy extends VenueError("施設の検索サーバ（OpenStreetMap）が応答しません。時間をおくか、名前を入力してチェックインしてください。")

/** Venue search on OpenStreetMap, shared by the check-in sheet and the place
  * sheet. Results are cached per ~200 m cell for a day and prefetched when the
  * app opens or the user settles somewhere, so the check-in sheet is instant.
  */
object VenueSearch:
  private val net = Executors.newSingleThreadExecutor()
  /** Public Overpass instances; the main one is often overloaded. */
  private val Endpoints = Seq(Overpass.Endpoint, "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter")
  private val CacheMs = 24 * 3_600_000L
  private val cache = java.util.concurrent.ConcurrentHashMap[(Long, Long, Int), (Long, Vector[Venue])]()

  private def cell(p: LatLng, radiusM: Int) = ((p.lat / 0.002).round, (p.lon / 0.0025).round, radiusM)

  /** Cached result for this spot, if fresh. */
  def cached(p: LatLng, radiusM: Int = 200): Option[Vector[(Venue, Double)]] =
    Option(cache.get(cell(p, radiusM))).filter(e => System.currentTimeMillis() - e._1 < CacheMs).map { (_, vs) =>
      vs.map(v => v -> v.latLng.distanceTo(p)).sortBy(_._2)
    }

  private def online: Boolean =
    val cm = AshidoriApp.app.getSystemService(classOf[android.net.ConnectivityManager])
    Option(cm.getNetworkCapabilities(cm.getActiveNetwork))
      .exists(_.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))

  private def query(p: LatLng, radiusM: Int): Vector[(Venue, Double)] =
    val body = ("data=" + URLEncoder.encode(Overpass.query(p.lat, p.lon, radiusM), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)
    // The main instance gets most of the time budget; mirrors are a quick
    // fallback (they are often unresponsive), so the whole search fails
    // within ~20 s instead of ~40 s.
    val attempts = Endpoints.iterator.zipWithIndex.map { (url, i) =>
      Try {
        val conn = URL(url).openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(if i == 0 then 6_000 else 3_000)
        conn.setReadTimeout(if i == 0 then 12_000 else 4_000)
        conn.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName} (Android)")
        Using.resource(conn.getOutputStream)(_.write(body))
        if conn.getResponseCode != 200 then throw RuntimeException(s"HTTP ${conn.getResponseCode}")
        val json = Using.resource(conn.getInputStream)(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
        Overpass.parse(json, p.lat, p.lon)
      }
    }
    attempts.find(_.isSuccess).map(_.get).getOrElse(throw (if online then VenueError.ServerBusy else VenueError.Offline))

  def nearby(p: LatLng, radiusM: Int = 200)(done: Try[Vector[(Venue, Double)]] => Unit): Unit =
    cached(p, radiusM) match
      case Some(hit) => done(scala.util.Success(hit))
      case None =>
        net.execute { () =>
          val r = Try {
            if !online then throw VenueError.Offline
            val vs = query(p, radiusM)
            cache.put(cell(p, radiusM), (System.currentTimeMillis(), vs.map(_._1)))
            vs
          }
          AshidoriApp.app.getMainExecutor.execute(() => done(r))
        }

  /** Searches run without an explicit user action. */
  def automatic: Boolean = !Settings.blockExternalLocation

  /** Warm the cache for a position in the background (no UI). */
  def prefetch(p: LatLng): Unit =
    if automatic && cached(p).isEmpty then nearby(p)(_ => ())

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
  /** Shown while sending location information is blocked: one explicit search. */
  private val searchOnce =
    val b = outlined("周辺の施設を検索（位置情報を送信）")(here.foreach(searchOnline))
    b.setVisibility(View.GONE)
    b
  private val footnote = caption("")

  // Compose step
  private val chosen = text("", 18f, bold = true)
  private val comment = EditText(act)
  comment.setHint("ひとこと（任意）")
  private val priv = MaterialSwitch(act)
  priv.setText("非公開にする（エクスポートや共有に含めない）")
  priv.setMinHeight(dp(48))
  private var selected: Option[Venue] = None
  private var composeHost: View = null
  private val commitButton =
    val b = button("チェックインする")(commit())
    b.setMinHeight(dp(56))
    b.setTextSize(16f)
    b
  private val compose = vbox(chosen, comment, priv, spacer(8), commitButton, outlined("施設を選び直す")(showPick()))
  /** Google Maps-like: the sheet opens at ~60% height, so its top (the
    * search field) sits mid-screen within thumb reach; candidates follow
    * right below; drag up (or start typing) for the full height.
    */
  private val pick =
    search.setMinHeight(dp(56))
    search.setTextSize(17f)
    vbox(
      text(if at.isDefined then "この滞在にチェックイン" else "チェックイン", 20f, bold = true),
      search,
      status,
      searchOnce,
      list,
      spacer(12),
      footnote
    )

  def show(): Unit =
    val root = padded(vbox(pick, compose), 20, 12)
    // Same opening height whether or not candidates have arrived yet.
    root.setMinimumHeight((act.getResources.getDisplayMetrics.heightPixels * 0.6).toInt)
    compose.setVisibility(View.GONE)
    composeHost = compose
    dialog.setContentView(scroll(root))
    dialog.getWindow.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val b = dialog.getBehavior
    b.setPeekHeight((act.getResources.getDisplayMetrics.heightPixels * 0.6).toInt)
    b.setState(BottomSheetBehavior.STATE_COLLAPSED)
    search.setOnFocusChangeListener((_, focused) => if focused then b.setState(BottomSheetBehavior.STATE_EXPANDED))
    dialog.show()
    search.addTextChangedListener(new TextWatcher:
      def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
      def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = render()
      def afterTextChanged(s: Editable): Unit = ()
    )
    here match
      case Some(p) => fetch(p)
      case None    => locate()

  /** Opens straight at the compose step for a venue picked elsewhere. */
  def showFor(v: Venue): Unit =
    show()
    select(v)

  /** Show candidates at once from the best position we already have, then
    * refine with a fresh fix if that moves us noticeably.
    */
  private def locate(): Unit =
    val lm = act.getSystemService(classOf[LocationManager])
    val now = System.currentTimeMillis()
    val known: Option[LatLng] =
      LoggerState.lastFix.filter(f => now - f.time < 30 * 60_000).map(_.latLng)
        .orElse(
          scala.util.Try(Option(lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER))).toOption.flatten
            .filter(l => now - l.getTime < 30 * 60_000)
            .map(l => LatLng(l.getLatitude, l.getLongitude))
        )
    known match
      case Some(p) => fetch(p)
      case None    => status.setText("現在地を取得中…")
    val fresh = LoggerState.lastFix.exists(f => now - f.time < 60_000)
    if !fresh then
      try
        lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, act.getMainExecutor, (l: Location) =>
          if l != null then
            val p = LatLng(l.getLatitude, l.getLongitude)
            if known.forall(_.distanceTo(p) > 100) then fetch(p)
          else if known.isEmpty then status.setText("現在地を取得できませんでした。名前を入力してチェックインできます。")
        )
      catch case _: SecurityException => status.setText("位置情報の権限がありません")

  /** Places and past check-in venues near `p`, from the local DB. Works offline. */
  private def localCandidates(p: LatLng): Vector[(Venue, Double)] =
    val db = AshidoriApp.db
    val fromPlaces = db.places().filter(_.name.nonEmpty).map(pl => Venue(pl.externalId, pl.name, "よく行く場所", pl.lat, pl.lon))
    val fromCheckIns = db.checkIns(0, Long.MaxValue).map(c => Venue(c.venueExternalId, c.venueName, c.venueCategory, c.lat, c.lon))
    (fromPlaces ++ fromCheckIns)
      .map(v => v -> v.latLng.distanceTo(p))
      .filter(_._2 <= 400)
      .sortBy(_._2)
      .distinctBy(_._1.name)

  private def fetch(p: LatLng): Unit =
    here = Some(p)
    // Local candidates first, so check-in works without a network.
    AshidoriApp.io.execute { () =>
      val local = localCandidates(p)
      act.runOnUiThread { () =>
        venues = (local ++ venues).distinctBy(_._1.name)
        render()
      }
    }
    if Settings.blockExternalLocation then
      // Results of an earlier explicit search stay usable; nothing is sent.
      VenueSearch.cached(p) match
        case Some(vs) =>
          venues = (venues ++ vs).distinctBy(_._1.name).sortBy(_._2)
          status.setText("")
          searchOnce.setVisibility(View.GONE)
          render()
        case None =>
          status.setText("過去の場所とチェックイン先のみを表示しています。")
          searchOnce.setVisibility(View.VISIBLE)
      footnote.setText("検索すると、位置情報が OpenStreetMap のサーバに送られます。")
    else searchOnline(p)

  /** Venue search on OpenStreetMap: sends the position to the server. */
  private def searchOnline(p: LatLng): Unit =
    searchOnce.setVisibility(View.GONE)
    footnote.setText("近くの施設は OpenStreetMap から取得します（位置情報がそのサーバに送られます）。")
    status.setText("近くの施設を検索中…")
    VenueSearch.nearby(p) { r =>
      r.fold(
        e => status.setText(e match
          case v: VenueError => v.message
          case _             => "施設を取得できませんでした。名前を入力してチェックインできます。"
        ),
        vs => {
          venues = (venues ++ vs).distinctBy(_._1.name).sortBy(_._2)
          status.setText("")
        }
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
    composeHost.setVisibility(View.VISIBLE)

  private def showPick(): Unit =
    composeHost.setVisibility(View.GONE)
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
