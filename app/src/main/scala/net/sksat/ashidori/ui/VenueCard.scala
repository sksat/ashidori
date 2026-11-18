package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View, ViewGroup}
import android.widget.{FrameLayout, ImageView, LinearLayout, ScrollView}
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.concurrent.{Executors, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The sheet of a venue picked on the map tab's map: its icon, name,
  * category and distance from the current position, 「チェックイン」 to the
  * target the context gives ([[VenueCheckIn.target]]; no button without
  * one) and 「訪れた日」. It lies over the day's sheet, which stays as it
  * is underneath. Nothing is sent.
  *
  * `context`: the day view's selected stay, the stays of the day shown and
  * whether that is today. `onClose`: the sheet closed (the map's pick
  * ends); `onChanged`: a check-in was made or taken back.
  */
final class VenueCard(act: MainActivity, context: () => VenueCard.Context, onClose: () => Unit,
                      onChanged: () => Unit, openDay: LocalDate => Unit):
  private given Ctx = act
  private var shown: Option[VenueLayer.Picked] = None
  private var target: Option[VenueCheckIn.Target] = None

  private val icon = FrameLayout(act)
  private val name = oneLine(text("", 17f, bold = true))
  private val meta = oneLine(caption(""))
  private val close = iconButton(R.drawable.ic_close, "閉じる")(hide())
  private val checkIn =
    val b = button("チェックイン")(doCheckIn())
    b.setIconResource(R.drawable.ic_checkin)
    tint(b, color(attr.tertiaryContainer), color(attr.onTertiaryContainer))
    b.setIconTint(android.content.res.ColorStateList.valueOf(color(attr.onTertiaryContainer)))
    b
  private val visitsButton = outlined("訪れた日")(toggleVisits())
  private val visits = VenueCard.VisitList(act, d => { hide(); openDay(d) })

  val view: LinearLayout =
    val texts = vbox(name, meta)
    val tlp = weighted()
    tlp.setMarginStart(dp(12))
    texts.setLayoutParams(tlp)
    icon.setLayoutParams(LinearLayout.LayoutParams(dp(40), dp(40)))
    val head = hbox(icon, texts, close)
    head.setGravity(Gravity.CENTER_VERTICAL)
    val clp = wrap
    clp.setMarginEnd(dp(8))
    checkIn.setLayoutParams(clp)
    val actions = hbox(checkIn, visitsButton)
    actions.setPadding(0, dp(8), 0, 0)
    val v = vbox(head, actions, visits.view)
    v.setPadding(dp(16), dp(12), dp(8), dp(12))
    val bg = GradientDrawable()
    bg.setCornerRadii(Array(dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0).map(_.toFloat))
    bg.setColor(color(attr.surface))
    v.setBackground(bg)
    v.setElevation(dp(10).toFloat)
    v.setVisibility(View.GONE)
    // Taps on the sheet stay on it (they never reach the map under it).
    v.setClickable(true)
    v

  /** Back closes the sheet while it is open. */
  private val back = new androidx.activity.OnBackPressedCallback(false):
    override def handleOnBackPressed(): Unit = hide()
  act.getOnBackPressedDispatcher.addCallback(back)

  def isOpen: Boolean = shown.nonEmpty

  /** Opens the sheet for `p` (or shows another venue in it). */
  def show(p: VenueLayer.Picked): Unit =
    val again = shown.exists(_.venue.id == p.venue.id)
    shown = Some(p)
    VenueCard.fillIcon(icon, p)
    name.setText(p.venue.name)
    if !again then visits.reset()
    refresh()
    view.setVisibility(View.VISIBLE)
    back.setEnabled(true)
    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(view, p.venue.name)
    if !again then view.post(() => name.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))

  /** The context may have changed (another stay selected, the day reloaded): the target again. */
  def refresh(): Unit = shown.foreach { p =>
    val now = System.currentTimeMillis()
    val here = LoggerState.lastFix.filter(f => now - f.time <= VenueCheckIn.HereMaxAgeMs).map(_.latLng)
    val dist = here.map(h => s"現在地から ${fmtDistance(h.distanceTo(p.venue.latLng))}")
    meta.setText((Seq(p.venue.category).filter(_.nonEmpty) ++ dist).mkString("・"))
    val c = context()
    target = VenueCheckIn.target(p.venue.latLng, c.selected, c.stays, c.today,
      LoggerState.lastFix.map(f => (f.latLng, f.time)), now)
    checkIn.setVisibility(if target.isDefined then View.VISIBLE else View.GONE)
  }

  /** Closes the sheet (the close button, back, or a tap elsewhere on the map). */
  def hide(): Unit =
    if shown.nonEmpty then
      shown = None
      target = None
      visits.reset()
      view.setVisibility(View.GONE)
      back.setEnabled(false)
      onClose()

  private def doCheckIn(): Unit =
    for p <- shown; t <- target do
      val v = p.venue
      hide()
      t match
        case VenueCheckIn.Target.Stay(stay, time) =>
          CheckIns.commit(act, CheckIn(0, time, v.lat, v.lon, v.name, v.category, v.id), v, CheckIns.message(v.name, Some(stay)), onChanged)
        case VenueCheckIn.Target.Now =>
          CheckIns.commit(act, CheckIn(0, System.currentTimeMillis(), v.lat, v.lon, v.name, v.category, v.id), v,
            CheckIns.message(v.name, None), onChanged)

  private def toggleVisits(): Unit = shown.foreach(p => visits.toggle(p.venue.latLng))

object VenueCard:
  /** What the day view says when a venue is picked. */
  final case class Context(selected: Option[Visit], stays: Seq[Visit], today: Boolean)

  /** The venue's icon as the map draws it, on a disc of its colour. */
  def fillIcon(host: FrameLayout, p: VenueLayer.Picked)(using c: Ctx): Unit =
    host.removeAllViews()
    val col = scala.util.Try(android.graphics.Color.parseColor(p.color)).getOrElse(0xff6f6a66)
    val bg = GradientDrawable()
    bg.setShape(GradientDrawable.OVAL)
    bg.setColor(col & 0x00ffffff | 0x33000000)
    bg.setStroke(dp(2), col)
    host.setBackground(bg)
    host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    val img = ImageView(c)
    p.icon match
      case Some(b) => img.setImageBitmap(b)
      case None =>
        val dot = GradientDrawable()
        dot.setShape(GradientDrawable.OVAL)
        dot.setColor(col)
        img.setImageDrawable(dot)
    host.addView(img, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))

  /** Days back that 「訪れた日」 looks at, and the days it lists at most. */
  val VisitDaysBack = 730
  val MaxVisitDays = 50

  /** One thread for the 「訪れた日」 scans, at background priority: a long
    * scan never holds up the I/O thread (check-ins, edits).
    */
  private lazy val scanner: ExecutorService = Executors.newSingleThreadExecutor { r =>
    val t = Thread(() => { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ashidori-venue-visits")
    t.setDaemon(true)
    t
  }

  /** The days with a stay at `venue`, newest first, from today back
    * [[VisitDaysBack]] days (or to the first record), at most
    * [[MaxVisitDays]]: `found` for each as it is found, then `done`, on the
    * main thread, while `live` holds. A day is built ([[TimelineRepo.day]])
    * only if a position of it (sampled by the minute) is within
    * [[VenueCheckIn.PrefilterM]]. Without a spatial index every day's
    * positions are read: about as costly as opening each day once, done in
    * the background and cut short when the sheet closes; the days found are
    * built on the I/O thread.
    */
  def scanVisits(act: MainActivity, venue: LatLng, live: () => Boolean)(found: (LocalDate, Visit) => Unit, done: () => Unit): Unit =
    scanner.execute { () =>
      val zone = AshidoriApp.zone
      val today = LocalDate.now(zone)
      val first = scala.util.Try(AshidoriApp.db.firstDataDateCached).toOption.flatten.getOrElse(today)
      val t0 = System.nanoTime()
      var n = 0
      var days = 0
      var built = 0
      var readNs = 0L
      var d = today
      val oldest = today.minusDays(VisitDaysBack - 1)
      while live() && n < MaxVisitDays && !d.isBefore(oldest) && !d.isBefore(first) do
        days += 1
        val from = d.atStartOfDay(zone).toInstant.toEpochMilli
        val to = d.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
        val r0 = System.nanoTime()
        val sampled = scala.util.Try(AshidoriApp.db.fixesSampled(from, to, 60_000)).getOrElse(Vector.empty)
        readNs += System.nanoTime() - r0
        if VenueCheckIn.mayHaveStay(venue, sampled.map(_.latLng)) then
          built += 1
          // Built on the I/O thread, as every day is: two threads building
          // one day could each store its new places.
          val day = d
          val stays = scala.util.Try(AshidoriApp.io.submit(new java.util.concurrent.Callable[Vector[Visit]]:
            def call(): Vector[Visit] = TimelineRepo.day(day).segments.collect { case v: Visit => v }).get())
            .getOrElse(Vector.empty)
          VenueCheckIn.atVenue(venue, stays, System.currentTimeMillis()).foreach { v =>
            n += 1
            act.runOnUiThread(() => if live() then found(day, v))
          }
        d = d.minusDays(1)
      Log.d(f"venues: visited days $n of $days days ($built built) in ${(System.nanoTime() - t0) / 1e6}%.0f ms, positions read in ${readNs / 1e6}%.0f ms")
      act.runOnUiThread(() => if live() then done())
    }

  /** 「訪れた日」: the list under the sheet's buttons, filled as the scan finds days. */
  final class VisitList(act: MainActivity, open: LocalDate => Unit)(using Ctx):
    private val gen = AtomicInteger()
    private val rows = vbox()
    private val progress =
      val p = com.google.android.material.progressindicator.LinearProgressIndicator(act)
      p.setIndeterminate(true)
      p
    private val none = caption("なし")
    private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
    private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)
    private val scroll =
      val maxH = (act.getResources.getDisplayMetrics.heightPixels * 0.35).toInt
      val s = new ScrollView(act):
        override def onMeasure(w: Int, h: Int): Unit =
          super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
      s.addView(rows)
      s
    val view: LinearLayout =
      val v = vbox(progress, none, scroll)
      v.setPadding(0, dp(8), dp(8), 0)
      v.setVisibility(View.GONE)
      v

    /** Hidden and empty; a scan on its way ends. */
    def reset(): Unit =
      gen.incrementAndGet()
      rows.removeAllViews()
      view.setVisibility(View.GONE)

    def toggle(venue: LatLng): Unit =
      if view.getVisibility == View.VISIBLE then reset()
      else
        val g = gen.incrementAndGet()
        rows.removeAllViews()
        none.setVisibility(View.GONE)
        progress.setVisibility(View.VISIBLE)
        view.setVisibility(View.VISIBLE)
        scanVisits(act, venue, () => gen.get == g)(
          (d, v) => rows.addView(row(d, v)),
          () => {
            progress.setVisibility(View.GONE)
            none.setVisibility(if rows.getChildCount == 0 then View.VISIBLE else View.GONE)
          })

    private def row(d: LocalDate, v: Visit): View =
      val day = text(d.format(DayParts.dateFmt), 15f)
      day.setLayoutParams(weighted())
      val r = hbox(day, tabular(caption(s"${time(v.start)} – ${time(v.end)}")))
      r.setGravity(Gravity.CENTER_VERTICAL)
      r.setMinimumHeight(dp(48))
      r.setPadding(dp(4), 0, dp(8), 0)
      ripple(r)
      a11yRole(r, classOf[android.widget.Button])
      r.setOnClickListener(_ => open(d))
      r
