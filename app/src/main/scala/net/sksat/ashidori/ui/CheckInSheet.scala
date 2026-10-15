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
import java.util.concurrent.{Executors, TimeUnit}
import scala.util.{Try, Using}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Why a venue search returned nothing: `message` alone, `withCache` when
  * saved venues ([[VenueCache]]) are shown instead.
  */
enum VenueError(val message: String, val withCache: String) extends Exception(message):
  case Offline extends VenueError("オフライン: よく行く場所と過去のチェックイン先を表示しています。",
    "オフライン: 保存済みの施設を表示しています。")
  case ServerBusy extends VenueError("検索サーバがすべて応答しません。名前でチェックインできます。",
    "検索サーバがすべて応答しません。保存済みの施設を表示中です。")
  case Blocked extends VenueError("「位置情報を外部サービスに送らない」がオンのため、自動の検索を中止しました。",
    "自動の検索を中止しました。保存済みの施設を表示しています。")

/** Venue search on OpenStreetMap, shared by the check-in sheet and the place
  * sheet. Every result is kept on the device ([[VenueCache]], 90 days), and
  * searches are prefetched when the app opens or the user settles
  * somewhere, so the check-in sheet has candidates at once, also offline.
  * A spot searched within a day is not searched again automatically.
  *
  * A search is explicit (the user pressed a search button) or automatic
  * (prefetch, or a sheet searching by itself). An automatic one takes a
  * ticket of 「位置情報を外部サービスに送らない」 when it is asked for and
  * checks it right before it is sent and before each fallback server
  * ([[SendGate]]): after a change of the setting, queued automatic searches
  * end without sending and the one in flight is disconnected. Looking up
  * the saved venues sends nothing.
  */
object VenueSearch:
  private val net = Executors.newSingleThreadExecutor()
  /** Ends a connection at the end of the search's time budget. */
  private val timer = Executors.newSingleThreadScheduledExecutor(r => Thread(r, "ashidori-venue-timer"))
  /** The automatic search's connection in flight, to abort on a change of the setting. */
  private val inflight = java.util.concurrent.atomic.AtomicReference[HttpURLConnection](null)
  Settings.gate.onChange { () =>
    Option(inflight.getAndSet(null)).foreach(c => Thread(() => c.disconnect(), "ashidori-venue-abort").start())
  }

  /** Which instance answered last and when each last failed (this process only). */
  private object health:
    var lastOk: Option[String] = None
    var failedAt = Map.empty[String, Long]

  /** The saved venues, read from the DB on first use (off the main thread).
    * A DB that cannot be read gives an empty cache; what is stored stays.
    */
  private lazy val store: VenueCache =
    val now = System.currentTimeMillis()
    Try(VenueCache.load(AshidoriApp.db.venueCache(), now)) match
      case scala.util.Success((c, expired)) =>
        Try(AshidoriApp.db.applyVenueCache(expired))
        c
      case scala.util.Failure(_) => VenueCache.empty

  /** Saved venues near `p`, without sending anything. Blocks on the DB: call
    * it off the main thread.
    */
  def saved(p: LatLng, radiusM: Int = 200): Option[VenueCache.Hit] =
    val now = System.currentTimeMillis()
    val hit = store.lookup(p, radiusM, now)
    hit.foreach(h => Try(AshidoriApp.db.touchVenueCache(store.touch(h.keys, now).put)))
    hit

  private def online: Boolean =
    val cm = AshidoriApp.app.getSystemService(classOf[android.net.ConnectivityManager])
    Option(cm.getNetworkCapabilities(cm.getActiveNetwork))
      .exists(_.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))

  private object OutOfTime extends Exception("time budget spent")

  /** Asks each instance in turn ([[Overpass.order]]) within
    * [[Overpass.BudgetMs]] in all; an HTTP error moves on at once.
    * `allowed` is checked right before each server is asked. Returns the
    * venues and whether the answer is complete.
    */
  private def query(p: LatLng, radiusM: Int, allowed: () => Boolean, automatic: Boolean): (Vector[(Venue, Double)], Boolean) =
    val body = ("data=" + URLEncoder.encode(Overpass.query(p.lat, p.lon, radiusM), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)
    val start = System.currentTimeMillis()
    val order = health.synchronized(Overpass.order(Overpass.Endpoints, health.lastOk, health.failedAt, start))
    val result = SendGate.firstSuccess(order.zipWithIndex, allowed) { (url, i) =>
      val remaining = Overpass.BudgetMs - (System.currentTimeMillis() - start)
      val (connectMs, readMs) = Overpass.timeouts(i, remaining).getOrElse(throw OutOfTime)
      val conn = URL(url).openConnection().asInstanceOf[HttpURLConnection]
      if automatic then inflight.set(conn)
      // Read timeouts are per read: a server that trickles is cut at the budget.
      val deadline = timer.schedule((() => conn.disconnect()): Runnable, remaining, TimeUnit.MILLISECONDS)
      try
        // A change of the setting between the check and here: not sent.
        if !allowed() then throw VenueError.Blocked
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(connectMs)
        conn.setReadTimeout(readMs)
        conn.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName} (Android)")
        Using.resource(conn.getOutputStream)(_.write(body))
        val code = conn.getResponseCode
        if code != 200 then throw RuntimeException(s"HTTP $code")
        val json = Using.resource(conn.getInputStream)(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
        val answer = Overpass.parseAnswer(json, p.lat, p.lon)
        health.synchronized(health.lastOk = Some(url))
        answer
      catch
        case e: Exception =>
          if e != VenueError.Blocked then health.synchronized {
            health.failedAt += url -> System.currentTimeMillis()
            if health.lastOk.contains(url) then health.lastOk = None
          }
          throw e
      finally
        deadline.cancel(false)
        inflight.compareAndSet(conn, null)
    }
    result match
      case Right(answer) => answer
      case Left(SendGate.Blocked) => throw VenueError.Blocked
      case Left(_) =>
        if automatic && !allowed() then throw VenueError.Blocked
        throw (if online then VenueError.ServerBusy else VenueError.Offline)

  /** Venues near `p`: the saved ones when this spot was searched within a
    * day, otherwise from the server, saved on success (a failure leaves
    * the saved venues as they are). `explicit`: the user asked for this
    * search; otherwise it is automatic and ends with [[VenueError.Blocked]],
    * sending nothing, once the setting does not allow it (see [[VenueSearch]]).
    */
  def nearby(p: LatLng, radiusM: Int = 200, explicit: Boolean)(done: Try[Vector[(Venue, Double)]] => Unit): Unit =
    val gate = Settings.gate
    val ticket = gate.ticket()
    val allowed: () => Boolean = () => explicit || gate.automatic(ticket)
    def deliver(r: Try[Vector[(Venue, Double)]]) = AshidoriApp.app.getMainExecutor.execute(() => done(r))
    AshidoriApp.io.execute { () =>
      Try(saved(p, radiusM)).toOption.flatten.filter(_.fresh) match
        case Some(hit) => deliver(scala.util.Success(hit.venues))
        case None if !allowed() => deliver(scala.util.Failure(VenueError.Blocked))
        case None =>
          net.execute { () =>
            val r = Try {
              // Queued behind other searches: the setting may have changed since.
              if !allowed() then throw VenueError.Blocked
              if !online then throw VenueError.Offline
              val (vs, complete) = query(p, radiusM, allowed, automatic = !explicit)
              Try(AshidoriApp.db.applyVenueCache(store.put(p, radiusM, System.currentTimeMillis(), vs.map(_._1), complete)))
              vs
            }
            deliver(r)
          }
    }

  /** Fills the saved venues for a position in the background (no UI): automatic. */
  def prefetch(p: LatLng): Unit =
    if !Settings.blockExternalLocation then nearby(p, explicit = false)(_ => ())

/** Swarm-style check-in as a bottom sheet: search on top, nearby venues,
  * then a short compose step. `at` checks in to a past visit instead of the
  * current location.
  */
final class CheckInSheet(act: MainActivity, at: Option[(LatLng, Long)] = None, onDone: () => Unit = () => ()):
  private given Ctx = act
  private val dialog = BottomSheetDialog(act)
  private var here: Option[LatLng] = at.map(_._1)
  private var venues: Vector[(Venue, Double)] = Vector.empty
  /** Names of the candidates shown from saved venues and not (yet) in an online answer. */
  private var savedNames = Set.empty[String]

  private val search = EditText(act)
  search.setHint("施設を検索、または名前を入力")
  search.setSingleLine(true)
  private val NoLocation = "現在地を取得できませんでした。名前を入力してチェックインできます。"
  private val NoVenues = "施設を取得できませんでした。名前を入力してチェックインできます。"
  private val NoVenuesSaved = "施設を取得できませんでした。保存済みの施設を表示しています。"
  private val LocalOnly = "過去の場所とチェックイン先のみを表示しています。"
  private def savedFrom(hit: VenueCache.Hit) =
    s"保存済みの施設（${CheckInSheet.age(System.currentTimeMillis() - hit.fetchedAt)}に取得）を表示しています。"
  /** As tall as its longest message, empty or not, so the candidates below
    * stay where they are as the search goes on.
    */
  private val status = stableCaption(
    VenueError.values.toSeq.flatMap(e => Seq(e.message, e.withCache)) ++
      Seq(NoLocation, NoVenues, NoVenuesSaved, LocalOnly, "近くの施設を検索中…", "保存済みの施設（88日前に取得）を表示しています。"))
  private val list = vbox()
  /** Shown while sending location information is blocked: one explicit search. */
  private val searchOnce =
    val b = outlined("周辺の施設を検索（位置情報を送信）")(here.foreach(searchOnline(_, explicit = true)))
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
          else if known.isEmpty then status.setText(NoLocation)
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

  /** Candidates already listed keep their places; new ones (by distance)
    * go below them, so a row does not move under the finger as results arrive.
    */
  private def merged(more: Vector[(Venue, Double)]): Vector[(Venue, Double)] =
    val shown = venues.map(_._1.name).toSet
    venues ++ more.filterNot(v => shown(v._1.name)).distinctBy(_._1.name).sortBy(_._2)

  /** Once shown, the button keeps its room when hidden (INVISIBLE). */
  private def hideSearchOnce(): Unit =
    if searchOnce.getVisibility == View.VISIBLE then searchOnce.setVisibility(View.INVISIBLE)

  /** Adds saved venues below what is shown, marked as saved. */
  private def showSaved(hit: VenueCache.Hit): Unit =
    val shown = venues.map(_._1.name).toSet
    savedNames ++= hit.venues.map(_._1.name).filterNot(shown)
    venues = merged(hit.venues)

  private def fetch(p: LatLng): Unit =
    here = Some(p)
    // Local candidates and saved venues first (nothing is sent), so check-in
    // works without a network.
    AshidoriApp.io.execute { () =>
      val local = localCandidates(p)
      val hit = Try(VenueSearch.saved(p)).toOption.flatten
      act.runOnUiThread { () =>
        venues = merged(local)
        hit.foreach(showSaved)
        render()
        if Settings.blockExternalLocation then
          hit match
            case Some(h) =>
              status.setText(savedFrom(h))
              if h.fresh then hideSearchOnce() else searchOnce.setVisibility(View.VISIBLE)
            case None =>
              status.setText(LocalOnly)
              searchOnce.setVisibility(View.VISIBLE)
          footnote.setText("検索すると、位置情報が OpenStreetMap のサーバに送られます。")
        else hit.filter(_.fresh) match
          // Searched here within a day: no new search.
          case Some(h) =>
            status.setText(savedFrom(h))
            footnote.setText("近くの施設は OpenStreetMap から取得します（位置情報がそのサーバに送られます）。")
          case None => searchOnline(p, explicit = false)
      }
    }

  /** Venue search on OpenStreetMap: sends the position to the server.
    * `explicit`: the user pressed the search button; otherwise the sheet
    * searches by itself (setting off) and stops if the setting turns on.
    */
  private def searchOnline(p: LatLng, explicit: Boolean): Unit =
    hideSearchOnce()
    footnote.setText("近くの施設は OpenStreetMap から取得します（位置情報がそのサーバに送られます）。")
    status.setText("近くの施設を検索中…")
    VenueSearch.nearby(p, explicit = explicit) { r =>
      r.fold(
        e => {
          val saved = savedNames.nonEmpty
          status.setText(e match
            case v: VenueError => if saved then v.withCache else v.message
            case _             => if saved then NoVenuesSaved else NoVenues
          )
          // Stopped by the setting: searching stays possible on request.
          if e == VenueError.Blocked then
            searchOnce.setVisibility(View.VISIBLE)
            footnote.setText("検索すると、位置情報が OpenStreetMap のサーバに送られます。")
        },
        vs => {
          venues = merged(vs)
          savedNames --= vs.map(_._1.name)
          status.setText("")
        }
      )
      render()
    }

  /** `saved`: from the saved venues, not confirmed online in this search. */
  private def row(title: String, sub: String, saved: Boolean = false)(onClick: => Unit): View =
    val t = oneLine(text(title, 16f))
    t.setLayoutParams(weighted())
    val r = hbox(t)
    if saved then
      val c = chip("保存済み", color(attr.surfaceContainerHigh), color(attr.onSurfaceVariant))
      c.setTextSize(11f)
      val lp = wrap
      lp.setMargins(dp(8), 0, dp(8), 0)
      c.setLayoutParams(lp)
      r.addView(c)
    r.addView(tabular(caption(sub)))
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
      list.addView(row(v.name, s"${v.category}・${d.round}m", savedNames(v.name))(select(v)))

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

object CheckInSheet:
  /** "3時間前" style, for how old saved venues are. */
  def age(ms: Long): String =
    val h = ms / 3_600_000L
    if h < 1 then "1時間以内" else if h < 24 then s"${h}時間前" else s"${h / 24}日前"

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
