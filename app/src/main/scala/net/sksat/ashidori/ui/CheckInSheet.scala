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

/** Why an online venue search returned nothing: `message` alone,
  * `withCache` when saved venues ([[VenueCache]]) are shown instead,
  * `withIndex` when the venue index ([[VenueData]]) is.
  */
enum VenueError(val message: String, val withCache: String, val withIndex: String) extends Exception(message):
  case Offline extends VenueError("オフライン: よく行く場所と過去のチェックイン先", "オフライン", "オフライン")
  case ServerBusy extends VenueError("検索サーバが応答しません",
    "検索サーバが応答しません（保存済みを表示）", "検索サーバが応答しません（施設データを表示）")

/** Online venue search on OpenStreetMap (Overpass), shared by the
  * check-in sheet and the place sheet. It runs only when the user presses
  * 「オンラインで検索（位置を送信）」: nothing sends a position to it by itself,
  * whatever 「位置情報を外部サービスに送らない」 says (the press is the
  * consent, as for any explicit action). The sheets show the venue index
  * ([[VenueData]]) and the saved results ([[VenueCache]], 90 days) without
  * sending anything; every online result is saved there.
  */
object VenueSearch:
  private val net = Executors.newSingleThreadExecutor()
  /** Ends a connection at the end of the search's time budget. */
  private val timer = Executors.newSingleThreadScheduledExecutor(r => Thread(r, "ashidori-venue-timer"))

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

  /** The answer of one search: the venues and the server that gave them. */
  final case class Answer(venues: Vector[(Venue, Double)], server: String)

  /** Asks each instance in turn ([[Overpass.order]]) within
    * [[Overpass.BudgetMs]] in all; an HTTP error moves on at once.
    */
  private def query(p: LatLng, radiusM: Int): (Vector[(Venue, Double)], Boolean, String) =
    val body = ("data=" + URLEncoder.encode(Overpass.query(p.lat, p.lon, radiusM), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)
    val start = System.currentTimeMillis()
    val order = health.synchronized(Overpass.order(Overpass.Endpoints, health.lastOk, health.failedAt, start))
    // Explicit: the user asked for this search, so each server may be tried.
    val result = SendGate.firstSuccess(order.zipWithIndex, () => true) { (url, i) =>
      val remaining = Overpass.BudgetMs - (System.currentTimeMillis() - start)
      val (connectMs, readMs) = Overpass.timeouts(i, remaining).getOrElse(throw OutOfTime)
      val conn = URL(url).openConnection().asInstanceOf[HttpURLConnection]
      // Read timeouts are per read: a server that trickles is cut at the budget.
      val deadline = timer.schedule((() => conn.disconnect()): Runnable, remaining, TimeUnit.MILLISECONDS)
      try
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(connectMs)
        conn.setReadTimeout(readMs)
        conn.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName} (Android)")
        Using.resource(conn.getOutputStream)(_.write(body))
        val code = conn.getResponseCode
        if code != 200 then throw RuntimeException(s"HTTP $code")
        val json = Using.resource(conn.getInputStream)(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
        val (vs, complete) = Overpass.parseAnswer(json, p.lat, p.lon)
        health.synchronized(health.lastOk = Some(url))
        (vs, complete, URL(url).getHost)
      catch
        case e: Exception =>
          health.synchronized {
            health.failedAt += url -> System.currentTimeMillis()
            if health.lastOk.contains(url) then health.lastOk = None
          }
          throw e
      finally deadline.cancel(false)
    }
    result match
      case Right(answer) => answer
      case Left(_) => throw (if online then VenueError.ServerBusy else VenueError.Offline)

  /** Venues near `p` from the server, always asked (no saved answer stands
    * in for it), and saved on success; a failure leaves the saved venues as
    * they are. Only for an explicit request of the user: it sends `p`.
    */
  def latest(p: LatLng, radiusM: Int = 200)(done: Try[Answer] => Unit): Unit =
    net.execute { () =>
      val r = Try {
        if !online then throw VenueError.Offline
        val (vs, complete, server) = query(p, radiusM)
        Try(AshidoriApp.db.applyVenueCache(store.put(p, radiusM, System.currentTimeMillis(), vs.map(_._1), complete)))
        Answer(vs, server)
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
  /** Names of the candidates shown from saved venues and not (yet) in an online answer. */
  private var savedNames = Set.empty[String]
  /** Matches of the typed text in the venue index (beyond the candidates near by). */
  private var searchHits: Vector[(Venue, Double)] = Vector.empty
  /** Candidates come from the venue index (installed and readable). */
  private var indexed = false

  private val search = EditText(act)
  search.setHint("施設を検索、または名前を入力")
  search.setSingleLine(true)
  private val NoLocation = "現在地を取得できません"
  private val NoVenues = "施設を取得できません"
  private val NoVenuesSaved = "施設を取得できません（保存済みを表示）"
  private val NoVenuesIndex = "施設を取得できません（施設データを表示）"
  private val LocalOnly = "過去の場所とチェックイン先のみ"
  private val IndexBroken = "施設データを読み込めません"
  private def savedFrom(hit: VenueCache.Hit) =
    s"保存済みの施設（${CheckInSheet.age(System.currentTimeMillis() - hit.fetchedAt)}に取得）"
  private def fromIndex(date: Option[String]) =
    s"施設データ${date.fold("")(d => s"（$d）")}"
  private def latestFrom(server: String) = s"OpenStreetMap から取得（$server）"
  /** As tall as its longest message, empty or not, so the candidates below
    * stay where they are as the search goes on.
    */
  private val status = stableCaption(
    VenueError.values.toSeq.flatMap(e => Seq(e.message, e.withCache, e.withIndex)) ++
      Seq(NoLocation, NoVenues, NoVenuesSaved, NoVenuesIndex, LocalOnly, IndexBroken, latestFrom("overpass.kumi.systems"),
        "近くの施設を検索中…", "保存済みの施設（88日前に取得）",
        fromIndex(Some("2026年12月31日時点"))))
  private val list = vbox()
  /** The only way to the online search: the user presses it. Sending is
    * the exception, so it has the tertiary (warning) colours.
    */
  private val searchOnce =
    val b = outlined("オンラインで検索（位置を送信）")(here.foreach(searchOnline))
    tint(b, color(attr.tertiaryContainer), color(attr.onTertiaryContainer))
    b.setVisibility(View.GONE)
    b

  // Compose step
  private val chosen = text("", 18f, bold = true)
  private val comment = EditText(act)
  comment.setHint("コメント")
  private val priv = MaterialSwitch(act)
  priv.setText("🔒 非公開")
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
      spacer(12)
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
      def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit =
        render()
        searchIndex()
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
    // A place's own name is offered as it is: with its venue id only when a
    // venue gave it, so a typed name never passes for a venue's.
    val fromPlaces = db.places().filter(_.name.nonEmpty).map(pl =>
      Venue(if pl.nameSource == NameSource.FromVenue then pl.externalId else "", pl.name, "よく行く場所", pl.lat, pl.lon))
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
    venues ++ CheckInSheet.fresh(venues, more)

  /** Once shown, the button keeps its room when hidden (INVISIBLE). */
  private def hideSearchOnce(): Unit =
    if searchOnce.getVisibility == View.VISIBLE then searchOnce.setVisibility(View.INVISIBLE)

  /** Adds saved venues below what is shown, marked as saved. */
  private def showSaved(hit: VenueCache.Hit): Unit =
    val added = CheckInSheet.fresh(venues, hit.venues)
    savedNames ++= added.map(_._1.name)
    venues = venues ++ added

  private def fetch(p: LatLng): Unit =
    here = Some(p)
    // Local candidates, the venue index and saved venues: nothing is sent,
    // so check-in works without a network. The online search runs only when
    // the user presses 「オンラインで検索」.
    AshidoriApp.io.execute { () =>
      val local = localCandidates(p)
      val offline = VenueData.lookup(p)
      val date = VenueData.dateLabel
      val hit = Try(VenueSearch.saved(p)).toOption.flatten
      act.runOnUiThread { () =>
        venues = merged(local)
        offline match
          case VenueData.Lookup.Found(vs) => venues = merged(vs)
          case _ => ()
        hit.foreach(showSaved)
        render()
        indexed = offline.isInstanceOf[VenueData.Lookup.Found]
        status.setText(offline match
          case VenueData.Lookup.Found(_) => fromIndex(date)
          case VenueData.Lookup.Broken => IndexBroken
          case VenueData.Lookup.Missing => hit.fold(LocalOnly)(savedFrom)
        )
        searchOnce.setVisibility(View.VISIBLE)
      }
    }

  /** Venue search on OpenStreetMap: sends the position to the server. Only
    * when the user pressed 「オンラインで検索」.
    */
  private def searchOnline(p: LatLng): Unit =
    hideSearchOnce()
    status.setText("近くの施設を検索中…")
    VenueSearch.latest(p) { r =>
      r.fold(
        e => {
          val saved = savedNames.nonEmpty
          status.setText(e match
            case v: VenueError => if indexed then v.withIndex else if saved then v.withCache else v.message
            case _             => if indexed then NoVenuesIndex else if saved then NoVenuesSaved else NoVenues
          )
          // It can be tried again.
          searchOnce.setVisibility(View.VISIBLE)
        },
        a => {
          venues = merged(a.venues)
          savedNames --= a.venues.map(_._1.name)
          status.setText(latestFrom(a.server))
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

  private val handler = android.os.Handler(android.os.Looper.getMainLooper)
  private val runSearch: Runnable = () =>
    val q = search.getText.toString.trim
    here.filter(_ => q.nonEmpty && indexed).foreach { p =>
      AshidoriApp.io.execute { () =>
        val hits = VenueData.search(q, p).getOrElse(Vector.empty)
        act.runOnUiThread { () =>
          if search.getText.toString.trim == q then
            searchHits = hits
            render()
        }
      }
    }

  /** Looks the typed text up in the venue index, once typing pauses. */
  private def searchIndex(): Unit =
    searchHits = Vector.empty
    handler.removeCallbacks(runSearch)
    handler.postDelayed(runSearch, 250)

  private def render(): Unit =
    val q = search.getText.toString.trim
    list.removeAllViews()
    if q.nonEmpty then
      list.addView(row(s"「$q」でチェックイン", "新しい場所")(select(Venue("", q, "", Double.NaN, Double.NaN))))
    val norm = VenueIndex.normalize(q)
    val near = venues.filter((v, _) => q.isEmpty || VenueIndex.normalize(v.name).contains(norm) || v.category.contains(q))
    // Matches farther away (the venue index) below the ones near by.
    val shown = if q.isEmpty then near else near ++ CheckInSheet.fresh(near, searchHits).sortBy(_._2)
    for (v, d) <- shown.take(30) do
      list.addView(row(v.name, s"${v.category}・${Ui.fmtDistance(d)}", savedNames(v.name))(select(v)))

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
      CheckIns.commit(act, ci, v, s"${v.name} にチェックインしました", onDone)
    }

object CheckInSheet:
  /** See [[VenueMerge.fresh]]. */
  def fresh(shown: Vector[(Venue, Double)], more: Vector[(Venue, Double)]): Vector[(Venue, Double)] =
    VenueMerge.fresh(shown, more)

  /** "3時間前" style, for how old saved venues are. */
  def age(ms: Long): String =
    val h = ms / 3_600_000L
    if h < 1 then "1時間以内" else if h < 24 then s"${h}時間前" else s"${h / 24}日前"

object CheckIns:
  /** What one action wrote, for its 取り消し: the check-in, and the changes
    * of place names it made (in order).
    */
  private final case class Action(done: VenueCheckIn.Done, placeEdits: Seq[(Long, PlaceEdit)] = Nil)

  /** Stores a check-in and links it to the matching place, naming the place
    * after the venue when it has no name yet, or creating one named after
    * it. On the I/O thread, in a transaction.
    */
  private def save(ci: CheckIn, v: Venue): Action =
    val db = AshidoriApp.db
    val visit = Visit(ci.time, ci.time, ci.lat, ci.lon, 30, 1)
    Places.nearest(visit, db.places()) match
      case Some(pl) =>
        val named = Option.when(pl.name.isEmpty && pl.kind == PlaceKind.Other)(PlaceEdit.rename(pl, v.name, Some(v.id))).filterNot(_.isNoop)
        named.foreach(e => db.updatePlace(e.applyTo(pl)))
        val row = ci.copy(placeId = Some(pl.id))
        Action(VenueCheckIn.Done(row.copy(id = db.insertCheckIn(row)), None), named.map(pl.id -> _).toSeq)
      case None =>
        val src = if v.id.nonEmpty then NameSource.FromVenue else NameSource.Typed
        val place = Place(0, ci.lat, ci.lon, 50, v.name, PlaceKind.Other, v.id, nameSource = src)
        val placeId = db.insertPlace(place)
        val row = ci.copy(placeId = Some(placeId))
        Action(VenueCheckIn.Done(row.copy(id = db.insertCheckIn(row)), None, Some(place.copy(id = placeId))))

  /** Applies `edit` to the place as stored now; the edit, if it changed it. */
  private def edit(placeId: Long, edit: Place => PlaceEdit): Option[(Long, PlaceEdit)] =
    AshidoriApp.db.places().find(_.id == placeId).flatMap { p =>
      val e = edit(p)
      Option.when(!e.isNoop) {
        AshidoriApp.db.updatePlace(e.applyTo(p))
        placeId -> e
      }
    }

  /** Takes back a change of a place, while it still holds what was set. */
  private def revert(change: (Long, PlaceEdit)): Unit =
    val (id, e) = change
    for p <- AshidoriApp.db.places().find(_.id == id); back <- e.revert(p) do AshidoriApp.db.updatePlace(back)

  /** 取り消し of `a`, in a transaction: only while its check-in holds
    * exactly what `a` wrote ([[VenueCheckIn.undo]]); then the place names it
    * set and the place it created go too. Whether anything was taken back.
    */
  private def takeBack(a: Action): Boolean =
    val db = AshidoriApp.db
    VenueCheckIn.undo(a.done, db.checkIn(a.done.written.id)) match
      case None => false
      case Some(u) =>
        u match
          case VenueCheckIn.Undo.Restore(row) => db.updateCheckIn(row)
          case VenueCheckIn.Undo.Remove(id)   => db.deleteCheckIn(id)
        a.placeEdits.reverse.foreach(revert)
        for
          p <- a.done.createdPlace
          id <- VenueCheckIn.placeToRemove(a.done, db.places().find(_.id == p.id), db.checkInsOfPlace(p.id))
        do db.deletePlace(id)
        true

  /** The day redrawn (`onDone`) and `message` with 取り消し; a 取り消し that
    * finds the check-in changed since does nothing.
    */
  private def done(act: MainActivity, a: Action, message: String, onDone: () => Unit): Unit =
    TimelineRepo.invalidate()
    act.runOnUiThread { () =>
      onDone()
      Edits.snackbar(act.snackbarAnchor, message) {
        AshidoriApp.io.execute { () =>
          val taken = AshidoriApp.db.atomically(takeBack(a))
          Log.d(s"check-ins: ${a.done.written.id} ${if taken then "taken back" else "changed since, kept"}")
          if taken then
            TimelineRepo.invalidate()
            act.runOnUiThread(() => onDone())
        }
      }
    }

  /** Stores `ci` ([[save]]) and shows `message` with 取り消し. `onDone` runs
    * on the main thread after each.
    */
  def commit(act: MainActivity, ci: CheckIn, v: Venue, message: String, onDone: () => Unit): Unit =
    AshidoriApp.io.execute { () =>
      done(act, AshidoriApp.db.atomically(save(ci, v)), message, onDone)
    }

  /** The check-in of a past stay, from the venue picker, to `v`. In one
    * transaction it reads the stay's check-in as stored and either rewrites
    * it in place ([[VenueCheckIn.correction]]; the same id, so that sync
    * carries the change as the row's latest snapshot) or adds one at the
    * stay's time; and it names the stay's place after the venue unless the
    * user named it ([[VenueCheckIn.renames]]). `onDone` runs on the main
    * thread once it is stored, and after a 取り消し.
    */
  def toStay(act: MainActivity, v: Venue, visit: Visit, message: String, onDone: () => Unit): Unit =
    val at = if v.lat.isNaN then visit.latLng else v.latLng
    AshidoriApp.io.execute { () =>
      val db = AshidoriApp.db
      val a = db.atomically {
        val plan = VenueCheckIn.correction(db.checkIns(visit.start - 60_000, visit.end + 60_001), visit, v, at)
        val renamed = visit.placeId.flatMap(id => db.places().find(_.id == id))
          .filter(VenueCheckIn.renames)
          .flatMap(p => edit(p.id, now => PlaceEdit.rename(now, v.name, Some(v.id))))
        plan match
          case VenueCheckIn.Plan.Replace(before, after) =>
            db.updateCheckIn(after)
            Action(VenueCheckIn.Done(after, Some(before)), renamed.toSeq)
          case VenueCheckIn.Plan.Add(row) =>
            val s = save(row, v)
            s.copy(placeEdits = renamed.toSeq ++ s.placeEdits)
      }
      Log.d(s"check-ins: stay ${visit.start}, ${if a.done.before.isDefined then "replaced" else "added"} ${a.done.written.id}" +
        (if a.placeEdits.nonEmpty then ", place named" else ""))
      done(act, a, message, onDone)
    }

  /** 「「スターバックス」にチェックイン（12:10 の滞在）」, or without the stay for now. */
  def message(name: String, stay: Option[Visit]): String = s"「$name」にチェックイン${stay.fold("")(v => s"（${stayLabel(v)}）")}"

  /** 「12:10 の滞在」: a stay by its start. */
  def stayLabel(v: Visit): String =
    s"${java.time.Instant.ofEpochMilli(v.start).atZone(AshidoriApp.zone).format(StayTime)} の滞在"

  private val StayTime = java.time.format.DateTimeFormatter.ofPattern("H:mm")
