package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.{Gravity, View}
import android.widget.{FrameLayout, ImageView, LinearLayout, ScrollView}
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** The sheet of a venue picked on the map tab's map: its icon, name,
  * category and distance from the current position, and 「チェックイン」 to
  * the target the context gives ([[VenueCheckIn.target]]; with the stay
  * when it is a past one, 「チェックイン（12:14 の滞在）」; no button
  * without a target), and 「訪れた日」 ([[VenueCard.VisitList]]). It lies
  * over the day's sheet, which stays as it is underneath. Nothing is sent.
  *
  * `context`: the day view's selected stay, the stays of the day shown and
  * whether that is today. `onClose`: the sheet closed (the map's pick
  * ends); `onChanged`: a check-in was made or taken back; `openDay`: a day
  * of 「訪れた日」 tapped.
  */
final class VenueCard(act: MainActivity, context: () => VenueCard.Context, onClose: () => Unit, onChanged: () => Unit,
                      openDay: LocalDate => Unit):
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
    checkIn.setText(target match
      case Some(VenueCheckIn.Target.Stay(v, _)) => s"チェックイン（${CheckIns.stayLabel(v)}）"
      case _                                    => "チェックイン")
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

  /** Checks in and closes the sheet at once: a second tap cannot check in again. */
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

  /** 「訪れた日」: under the sheet's buttons, the days with a stay at the
    * venue ([[VisitDays]]), newest first: date, time and duration; a tap
    * opens the day. What the index holds is shown at once; while days of
    * the record are still being built (on this list's thread, a run at a
    * time, the list refreshed after each), a last row says so
    * (「以前の日は集計中」). The work ends when the list closes. A failure
    * of the index is not "no days": 「読み込めません」 (or, with the days
    * found shown, 「以前の日は読み込めません」) and 「再試行」.
    */
  final class VisitList(act: MainActivity, open: LocalDate => Unit)(using Ctx):
    private val gen = AtomicInteger()
    private val rows = vbox()
    private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
    private def time(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(timeFmt)
    private var shownDays: Vector[VisitIndex.DayVisit] = Vector.empty
    private val scroll =
      val maxH = (act.getResources.getDisplayMetrics.heightPixels * 0.35).toInt
      val s = new ScrollView(act):
        override def onMeasure(w: Int, h: Int): Unit =
          super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
      s.addView(rows)
      s
    /** While nothing is known yet (the first query). */
    private val progress =
      val p = com.google.android.material.progressindicator.LinearProgressIndicator(act)
      p.setIndeterminate(true)
      p
    private val building = caption("以前の日は集計中")
    private val none = caption("なし")
    private val failedText = caption("読み込めません")
    private var venueNow: Option[LatLng] = None
    private val failed =
      failedText.setLayoutParams(weighted())
      val retry = outlined("再試行")(venueNow.foreach { v => reset(); toggle(v) })
      val f = hbox(failedText, retry)
      f.setGravity(Gravity.CENTER_VERTICAL)
      f
    val view: LinearLayout =
      for t <- Seq(building, none, failedText) do
        t.setMinimumHeight(dp(40))
        t.setGravity(Gravity.CENTER_VERTICAL)
        t.setPadding(dp(4), 0, 0, 0)
      val v = vbox(progress, scroll, building, none, failed)
      v.setPadding(0, dp(8), dp(8), 0)
      v.setVisibility(View.GONE)
      v

    /** Hidden and empty; the work on its way ends. */
    def reset(): Unit =
      gen.incrementAndGet()
      rows.removeAllViews()
      shownDays = Vector.empty
      view.setVisibility(View.GONE)

    def toggle(venue: LatLng): Unit =
      if view.getVisibility == View.VISIBLE then reset()
      else
        val g = gen.incrementAndGet()
        venueNow = Some(venue)
        rows.removeAllViews()
        shownDays = Vector.empty
        progress.setVisibility(View.VISIBLE)
        building.setVisibility(View.GONE)
        none.setVisibility(View.GONE)
        failed.setVisibility(View.GONE)
        view.setVisibility(View.VISIBLE)
        val live = () => gen.get == g
        def post(a: Option[VisitDays.Answer], more: Boolean): Unit =
          act.runOnUiThread(() => if live() then fill(a, more))
        VisitDays.exec.execute { () =>
          if live() then
            VisitDays.opened(() => !live())
            post(VisitDays.query(venue), more = true)
            // Runs until every day is built (or none could be), the list closed.
            var skip = Set.empty[LocalDate]
            var go = true
            while live() && go do
              VisitDays.run(VisitDays.RunDays, () => !live(), skip) match
                case Some(r) =>
                  skip ++= r.failed
                  go = r.built > 0 && r.left > skip.size
                  if r.built > 0 then post(VisitDays.query(venue), more = go)
                case None => go = false
            post(VisitDays.query(venue), more = false)
        }

    /** `a`: the answer (None: the index failed); `more`: days are still being built. */
    private def fill(a: Option[VisitDays.Answer], more: Boolean): Unit =
      progress.setVisibility(View.GONE)
      a match
        case None =>
          // The days shown before stay; the failure is said, not "none".
          building.setVisibility(View.GONE)
          none.setVisibility(View.GONE)
          failedText.setText(if shownDays.isEmpty then "読み込めません" else "以前の日は読み込めません")
          failed.setVisibility(View.VISIBLE)
        case Some(ans) =>
          val days = ans.days
          if days != shownDays then
            shownDays = days
            rows.removeAllViews()
            days.foreach(d => rows.addView(row(d)))
          val incomplete = ans.unbuilt > 0
          // Building has ended with days left: some could not be read.
          val stuck = incomplete && !more
          building.setVisibility(if incomplete && more then View.VISIBLE else View.GONE)
          none.setVisibility(if days.isEmpty && !incomplete && !more then View.VISIBLE else View.GONE)
          failedText.setText(if days.isEmpty then "読み込めません" else "以前の日は読み込めません")
          failed.setVisibility(if stuck then View.VISIBLE else View.GONE)

    private def row(d: VisitIndex.DayVisit): View =
      val date = d.day.format(DayParts.dateFmt)
      val range = s"${time(d.start)} – ${time(d.end)}" + (if d.stays.size > 1 then s"（${d.stays.size}回）" else "")
      val dur = fmtDuration(d.durationMs)
      val day = text(date, 15f)
      day.setLayoutParams(weighted())
      val r = hbox(day, tabular(caption(s"$range・$dur")))
      r.setGravity(Gravity.CENTER_VERTICAL)
      r.setMinimumHeight(dp(48))
      r.setPadding(dp(4), 0, dp(8), 0)
      ripple(r)
      a11yRole(r, classOf[android.widget.Button])
      r.setContentDescription(s"$date、$range、$dur")
      androidx.core.view.ViewCompat.replaceAccessibilityAction(r,
        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "この日を開く", null)
      r.setOnClickListener(_ => open(d.day))
      r
