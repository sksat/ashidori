package net.sksat.ashidori.ui

import android.view.View
import android.widget.TextView
import androidx.work.{WorkInfo, WorkManager}
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.*
import ashidori.core.*
import ashidori.core.sync.*
import Ui.*

/** Shared formatting for another device's status (the 端末 rows and the
  * device page).
  */
object PeerFormat:
  /** Status older than this is shown as stale: the hourly sync stopped. */
  val StaleMs: Long = 3 * 3_600_000L

  def ago(t: Long, now: Long = System.currentTimeMillis()): String =
    val d = now - t
    if d < 60_000 then "1分以内"
    else if d < 3_600_000 then s"${d / 60_000}分前"
    else if d < 86_400_000 then s"${d / 3_600_000}時間前"
    else s"${d / 86_400_000}日前"

  def time(t: Long): String =
    Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

  def mode(s: DeviceStatus): LoggingMode = LoggingMode.fromKey(s.mode)

  /** "● バランス" in the mode's color, or "■ 記録停止中" in grey. */
  def recording(s: DeviceStatus, t: TextView)(using Ctx): Unit =
    if s.recording then
      val m = mode(s)
      t.setText(s"● ${m.label}")
      t.setTextColor(ModeInfo.color(m))
    else
      t.setText("■ 記録停止中")
      t.setTextColor(color(attr.onSurfaceVariant))

  /** "⚡ 82%" while charging, "🔋 82%" otherwise; low battery in red. */
  def battery(s: DeviceStatus, t: TextView, missing: String = "")(using Ctx): Unit =
    if s.batteryPct < 0 then t.setText(missing)
    else
      t.setText(s"${if s.charging then "⚡" else "🔋"} ${s.batteryPct}%")
      t.setTextColor(color(if !s.charging && s.batteryPct <= 15 then attr.error else attr.onSurface))

  def coords(f: StatusFix): String = f"${f.lat}%.5f, ${f.lon}%.5f"

  def place(e: PeerEntry): String =
    e.status.lastFix.fold("位置なし")(f => if e.area.nonEmpty then e.area else coords(f))

  /** A received status that could not be read, in the error color. */
  def problem(u: Unresolved)(using Ctx): TextView =
    val retry = if u.attempts >= StatusSyncState.MaxAttempts then "" else "・再試行中"
    text(s"⚠ 受信した状態を読み取れません（${u.attempts}回）$retry", 13f, colorAttr = attr.error)

/** The 端末 section at the top of the ステータス tab: one row per other
  * device of the user. Hidden unless sync is set up with another device.
  */
final class PeerPanel(act: MainActivity):
  private given Ctx = act

  private val rows = vbox()
  /** Runs the sync now: uploads, then fetches the other devices' status. */
  private val refreshButton =
    val b = button("更新", tonal = true) {
      // Also retries statuses that failed to read before, without waiting for their backoff.
      PeerStatus.requestRetry()
      SyncScheduler.syncNow(act)
    }
    // Wide enough for every label (更新 / 更新中 / 待機中), so it does not resize.
    b.setMinWidth(math.max(b.getMinWidth,
      b.getPaint.measureText("待機中").ceil.toInt + b.getPaddingLeft + b.getPaddingRight))
    b
  private val header =
    val t = text("端末", 16f, bold = true)
    t.setLayoutParams(weighted())
    hbox(t, refreshButton)

  val view: View =
    val c = card(header, rows)
    c.setVisibility(View.GONE)
    c

  private var unobserve: () => Unit = () => ()
  private val work = WorkManager.getInstance(act).getWorkInfosForUniqueWorkLiveData(SyncScheduler.SyncNow)
  /** "Sync now" in progress: the button says so and cannot be pressed again. */
  private val workObserver: androidx.lifecycle.Observer[java.util.List[WorkInfo]] = infos =>
    val state = infos.asScala.headOption.map(_.getState)
    val (label, enabled) = state match
      case Some(WorkInfo.State.RUNNING)  => ("更新中", false)
      // Waiting for a network, or for a retry after a failure.
      case Some(WorkInfo.State.ENQUEUED) => ("待機中", false)
      case _                             => ("更新", true)
    refreshButton.setText(label)
    refreshButton.setEnabled(enabled)

  def onShow(): Unit =
    // onShow repeats on activity resume without onHide.
    unobserve()
    unobserve = PeerStatus.observe(() => render())
    work.observe(act, workObserver)
    // First show after an update: the device list arrives with the next sync,
    // so fetch it once now.
    if SyncConfig.ready && PeerStatus.devicesFetchedAt == 0 then PeerStatus.fetchDevicesAsync()
    render()

  def onHide(): Unit =
    unobserve()
    work.removeObserver(workObserver)

  def render(): Unit =
    view.setVisibility(if PeerStatus.visible then View.VISIBLE else View.GONE)
    if PeerStatus.visible then
      val es = PeerStatus.entries
      val now = System.currentTimeMillis()
      rows.removeAllViews()
      val st = PeerStatus.syncState
      for p <- PeerStatus.others do rows.addView(row(p, es.get(p.id), st.problem(p.id), now))

  /** Name and status age on top; recording and battery; then the place. */
  private def row(p: Peer, e: Option[PeerEntry], problem: Option[Unresolved], now: Long): View =
    val name = text(p.name, 15f, bold = true)
    name.setSingleLine(true)
    name.setEllipsize(android.text.TextUtils.TruncateAt.END)
    name.setLayoutParams(weighted())
    val age = tabular(caption(""))
    val top = hbox(name, age)
    // The same three lines whether or not a status has arrived.
    val rec = tabular(text("", 14f))
    val bat = tabular(text("", 14f))
    bat.setPadding(dp(12), 0, 0, 0)
    val where = tabular(oneLine(caption("")))
    val body = vbox(top, hbox(rec, bat), where)
    e match
      case None =>
        age.setText("未受信")
        rec.setText("—")
        rec.setTextColor(color(attr.onSurfaceVariant))
        where.setText("📍 —")
      case Some(e) =>
        val s = e.status
        age.setText(PeerFormat.ago(s.t, now))
        if now - s.t > PeerFormat.StaleMs then age.setTextColor(color(attr.error))
        PeerFormat.recording(s, rec)
        PeerFormat.battery(s, bat)
        where.setText(s"📍 ${PeerFormat.place(e)}" + s.lastFix.fold("")(f => s"・${PeerFormat.ago(f.t, now)}"))
    // Shown, not skipped: the server may have served a damaged or foreign blob.
    problem.foreach(u => body.addView(PeerFormat.problem(u)))
    body.setLayoutParams(weighted())
    val chevron = text("›", 22f, colorAttr = attr.onSurfaceVariant)
    chevron.setPadding(dp(8), 0, 0, 0)
    val r = hbox(body, chevron)
    r.setPadding(0, dp(8), 0, dp(8))
    r.setMinimumHeight(dp(56))
    r.setBackgroundResource(android.R.drawable.list_selector_background)
    if e.isDefined then r.setOnClickListener(_ => act.push(DeviceScreen(act, p.id)))
    r
