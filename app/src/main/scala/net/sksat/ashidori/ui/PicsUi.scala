package net.sksat.ashidori.ui

import android.os.{Handler, Looper, SystemClock}
import android.text.{SpannableStringBuilder, Spanned}
import android.text.style.ForegroundColorSpan
import android.view.{View, ViewGroup}
import android.widget.LinearLayout
import scala.util.Try
import net.sksat.ashidori.*
import ashidori.core.*
import ashidori.core.PicsFormat.{Light, Ped}
import Ui.*

/** How the PICS reception is shown (docs/design/pics.md 6): a signal's
  * colour only while fresh (2 s from its reception), grey when older, none
  * once the reception stops. No sound, no countdown (design 7.5).
  */
object PicsUi:
  val Green = 0xff00a37a.toInt
  val Red = 0xffe0372f.toInt

  def time(wall: Long): String = PicsStatus.time(wall, AshidoriApp.zone)

  /** One mark per crosswalk: ● green, ◐ flashing green, ● red, – none. */
  def mark(l: Light): String = l match
    case Light.Green | Light.Red => "●"
    case Light.GreenFlash        => "◐"
    case _                       => "–"

  def lightColor(l: Light)(using Ctx): Int = l match
    case Light.Green | Light.GreenFlash => Green
    case Light.Red                      => Red
    case _                              => color(attr.onSurfaceVariant)

  /** The marks of `peds`, in their colours (`fresh`) or all grey. */
  def marks(peds: Seq[Ped], fresh: Boolean)(using Ctx): CharSequence =
    val sb = SpannableStringBuilder()
    for (p, i) <- peds.sortBy(_.index).zipWithIndex do
      if i > 0 then sb.append(' ')
      val start = sb.length
      sb.append(mark(p.light))
      val c = if fresh then lightColor(p.light) else color(attr.outlineVariant)
      sb.setSpan(ForegroundColorSpan(c), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.setSpan(android.text.style.RelativeSizeSpan(1.4f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb

  /** For screen readers: 「歩行者信号1 青、歩行者信号2 赤」 (or 「…（古い表示）」). */
  def spoken(peds: Seq[Ped], fresh: Boolean): String =
    peds.sortBy(_.index).map(p => s"歩行者信号${p.index + 1} ${p.light.label}").mkString("、") + (if fresh then "" else "（受信から2秒以上）")

/** The ステータス row 「信号機」 (design 6), whenever the reception is on: the
  * label, two lines ([[PicsStatus.row]]) and a chevron while a tap opens
  * the settings to fix it. As tall in every state: the first line keeps the
  * height of the signals' marks. Redrawn on each change of the logger's
  * state (each fix and reception) and at the time it changes without one
  * (the colours go grey at exactly 2 s); nothing runs while hidden.
  */
final class PicsStatusRow(act: MainActivity, labelWidth: Int):
  private given Ctx = act
  private val label = text("信号機", 14f, colorAttr = attr.onSurfaceVariant)
  label.setLayoutParams(LinearLayout.LayoutParams(labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
  private val sample: CharSequence =
    SpannableStringBuilder("信号機").append("  ").append(PicsUi.marks(Seq(Ped(0, Light.Green, 3, None)), fresh = true))
  private val main =
    val t = tabular(oneLine(StableText(act, () => Seq(sample))))
    t.setTextSize(15f)
    t.setTextColor(color(attr.onSurface))
    t
  private val sub = tabular(oneLine(caption("")))
  private val chevron =
    val v = icon(net.sksat.ashidori.R.drawable.ic_chevron_right, attr.error)
    v.setLayoutParams(LinearLayout.LayoutParams(dp(20), dp(20)))
    v.setVisibility(View.INVISIBLE)
    v
  private val lines =
    val v = vbox(main, sub)
    v.setLayoutParams(weighted())
    v
  val view: LinearLayout =
    val h = hbox(label, lines, chevron)
    h.setPadding(0, dp(3), 0, dp(3))
    h.setVisibility(View.GONE)
    h
  ripple(view)
  /** The last reception stored today (read at onShow). */
  private var stored: Option[PicsStatus.Last] = None
  private var shown = false
  private val ticker = Handler(Looper.getMainLooper)
  private val tick: Runnable = () => show()

  def onShow(): Unit =
    shown = true
    show()
    if Settings.picsEnabled then
      AshidoriApp.io.execute { () =>
        val zone = AshidoriApp.zone
        val day = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant.toEpochMilli
        val last = Try(AshidoriApp.db.recentPicsAdv(50)).toOption.flatMap(rs =>
          PicsStatus.lastStored(rs, day, (lat, lon) => PicsSitesData.index.matching(lat, lon).map(_.name),
            iid => PicsLive.heard.get(iid).fold(s"ID ${PicsFormat.idNumber(iid)}")(_.label)))
        act.runOnUiThread { () =>
          stored = last
          if shown then show()
        }
      }

  def onHide(): Unit =
    shown = false
    ticker.removeCallbacks(tick)

  private def tone(t: PicsStatus.Tone): Int = color(t match
    case PicsStatus.Tone.Active => attr.primary
    case PicsStatus.Tone.Normal => attr.onSurface
    case PicsStatus.Tone.Muted  => attr.onSurfaceVariant
    case PicsStatus.Tone.Error  => attr.error)

  def show(): Unit =
    ticker.removeCallbacks(tick)
    if !shown || !Settings.picsEnabled then view.setVisibility(View.GONE)
    else
      val now = SystemClock.elapsedRealtime()
      val r = PicsStatus.row(PicsStatus.Input(now, System.currentTimeMillis(), AshidoriApp.zone,
        PicsScanner.hasPermission(act), PicsScanner.bluetoothOn(act),
        if LoggerState.running then PicsLive.plan.state else PicsScanPolicy.State.Stopped,
        PicsLive.current.map(_.status), PicsLive.nearest, stored))
      val sb = SpannableStringBuilder(r.main)
      sb.setSpan(ForegroundColorSpan(tone(r.tone)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      r.signals.foreach((ps, fresh) => sb.append("  ").append(PicsUi.marks(ps, fresh)))
      r.detail.foreach(d => sb.append("・").append(d))
      main.setText(sb)
      sub.setText(r.sub)
      chevron.setVisibility(if r.opens.isDefined then View.VISIBLE else View.INVISIBLE)
      view.setClickable(r.opens.isDefined)
      view.setOnClickListener(r.opens.fold(null: View.OnClickListener)(o => _ => open(o)))
      if r.opens.isDefined then a11yRole(view, classOf[android.widget.Button])
      else androidx.core.view.ViewCompat.setAccessibilityDelegate(view, null)
      view.setContentDescription((Seq("信号機", r.main) ++ r.signals.map(PicsUi.spoken(_, _)) ++ r.detail :+ r.sub).mkString("、"))
      view.setVisibility(View.VISIBLE)
      if r.nextAt != Long.MaxValue then
        ticker.postAtTime(tick, r.nextAt - now + SystemClock.uptimeMillis())

  private def open(o: PicsStatus.Opens): Unit =
    val i = o match
      case PicsStatus.Opens.AppPermission =>
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          android.net.Uri.fromParts("package", act.getPackageName, null))
      case PicsStatus.Opens.BluetoothSettings => android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    Try(act.startActivity(i)).failed.foreach(e => Log.w("PICS: settings not opened", e))

/** 端末診断 「信号機の電波」 (design 6): what the reception can do here, its
  * state, today's scans, what the caps refused, the last 50 rows and
  * 「受信の試験（60 秒）」.
  */
final class PicsPanel(act: MainActivity, onReport: String => Unit):
  private given Ctx = act
  private def row(title: String): ListRow =
    val r = ListRow(act)
    r.title.setText(title)
    r
  private val permRow = row("付近のデバイス")
  private val btRow = row("Bluetooth")
  private val deviceRow = row("端末の対応")
  private val stateRow = row("状態")
  private val todayRow = row("本日の走査")
  private val refusedRow = row("上限で書かなかった受信")
  private val testButton = button("受信の試験（60 秒）", tonal = true)(startTest())
  private val legend = caption("")
  private val log = mono("")
  private val ticker = Handler(Looper.getMainLooper)
  private val tick: Runnable = () => { showState(); ticker.postDelayed(tick, 1000) }

  val view: View = vbox(
    sectionHeader("信号機の電波"),
    listGroup(permRow, btRow, deviceRow, stateRow, todayRow, refusedRow),
    card(text("受信の記録（直近 50 行）", 16f, bold = true), legend, spacer(6), log, spacer(8), testButton))

  {
    val sb = SpannableStringBuilder()
    for (l, label) <- Seq(Light.Green -> "青", Light.GreenFlash -> "青点滅", Light.Red -> "赤", Light.NoSignal -> "信号なし・対象なし") do
      if sb.length > 0 then sb.append("　")
      val s = sb.length
      sb.append(PicsUi.mark(l))
      sb.setSpan(ForegroundColorSpan(PicsUi.lightColor(l)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.append(" ").append(label)
    legend.setText(sb)
  }

  def onShow(): Unit =
    ticker.removeCallbacks(tick)
    tick.run()
    load()

  def onHide(): Unit = ticker.removeCallbacks(tick)

  private def onOff(v: Option[Boolean], on: String, off: String) = v.fold("—")(if _ then on else off)

  private def showState(): Unit =
    val perm = PicsScanner.hasPermission(act)
    permRow.setValue(if perm then "許可" else "未許可")
    permRow.value.setTextColor(color(if perm || !Settings.picsEnabled then attr.onSurfaceVariant else attr.error))
    val bt = PicsScanner.bluetoothOn(act)
    btRow.setValue(onOff(bt, "オン", "オフ"))
    deviceRow.setSubtitle(Seq(
      s"フィルタ ${onOff(PicsScanner.offloadedFiltering(act), "対応", "非対応")}",
      s"一括受信 ${onOff(PicsScanner.offloadedBatching(act), "対応", "非対応")}",
      s"走査の種類 ${if PicsScanner.passiveScan then "受動的" else "能動的"}").mkString("・"))
    val p = PicsLive.plan
    val now = SystemClock.elapsedRealtime()
    stateRow.setValue(if !LoggerState.running then "停止" else p.state.label)
    val testLeft = PicsLive.testUntil - now
    stateRow.setSubtitle(
      (Seq(s"走査 ${PicsLive.registered.fold("なし")((s, _) => s.label)}") ++
        Option.when(LoggerState.running)(p.why) ++
        Option.when(p.state == PicsScanPolicy.State.Test && testLeft > 0)(s"残り ${(testLeft + 999) / 1000} 秒") ++
        PicsLive.failure.map((t, c) => s"開始の失敗 ${PicsUi.time(t)}（コード $c）") ++
        PicsLive.broken.map(e => s"処理の失敗のため停止: $e") ++
        Option.when(AshidoriApp.archive.unreadableFiles.nonEmpty)(
          s"形式の分からないファイル ${AshidoriApp.archive.unreadableFiles.size} 件（読み込まず保持）")).mkString("・"))
    stateRow.subtitle.setTextColor(color(
      if PicsLive.broken.isDefined || AshidoriApp.archive.unreadableFiles.nonEmpty then attr.error else attr.onSurfaceVariant))
    val refused = PicsIngest.refused
    refusedRow.setValue(s"${refused.values.sum} 件")
    refusedRow.value.setTextColor(color(if refused.nonEmpty then attr.error else attr.onSurfaceVariant))
    val (_, saved, unsaved) = LoggerWrites.progress(Seq(WriteKind.PicsAdv)).head
    refusedRow.setSubtitle((
      refused.toSeq.sortBy(-_._2).map((k, n) => s"${k.label} $n") :+
        ("保存 " + unsaved.map(u => s"${fmtDuration(u)}前から未保存").orElse(saved.map(a => s"${fmtDuration(a)}前")).getOrElse("—"))
    ).mkString("・"))
    testButton.setEnabled(LoggerState.running && perm && bt.contains(true) && p.state != PicsScanPolicy.State.Test)

  private def startTest(): Unit =
    PicsLive.testUntil = SystemClock.elapsedRealtime() + PicsScanPolicy.TestMs
    PicsLive.replan()
    showState()
    // The rows of the test, once it is over.
    ticker.postDelayed(() => load(), PicsScanPolicy.TestMs + 3000)

  /** Today's scans and rows, and the last 50 rows (on the I/O thread). */
  private def load(): Unit =
    AshidoriApp.io.execute { () =>
      val now = System.currentTimeMillis()
      val day = java.time.LocalDate.now(AshidoriApp.zone).atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
      val spans = Try(AshidoriApp.db.energyData(day, now)._2).getOrElse(Vector.empty)
      val time = spans.groupMapReduce(_.state.ble)(s => math.min(now, s.end) - math.max(day, s.start))(_ + _)
      val rows = Try(AshidoriApp.db.picsAdv(day, now)).getOrElse(Vector.empty)
      val recent = Try(AshidoriApp.db.recentPicsAdv(50)).getOrElse(Vector.empty)
      act.runOnUiThread { () =>
        val scans = PicsScan.values.filter(_ != PicsScan.Off).flatMap { s =>
          val ms = time.getOrElse(Some(s.key), 0L)
          val got = rows.filter(_.scan == s.key)
          Option.when(ms > 0 || got.nonEmpty)(s"${s.label} ${fmtDuration(ms)}・${got.map(_.n).sum} 件")
        }
        val unknown = time.getOrElse(None, 0L)
        todayRow.setSubtitle((
          (if scans.isEmpty then Seq("なし") else scans.toSeq) ++
            Option.when(unknown > 0)(s"不明 ${fmtDuration(unknown)}") ++
            Option.when(rows.exists(_.scan == "debug"))(s"注入 ${rows.filter(_.scan == "debug").map(_.n).sum} 件") ++
            Seq(s"行 ${rows.size}")).mkString("\n"))
        // Each run as received (a folded row restored to its runs).
        log.setText(if recent.isEmpty then "なし" else recentText(recent.flatMap(_.expand).sortBy(a => -a.start).take(50)))
        onReport(s"state=${PicsLive.plan.state} registered=${PicsLive.registered} refused=${PicsIngest.refused} " +
          s"today=${time.map((k, v) => s"${k.getOrElse("null")}:${v / 1000}s").mkString(",")} rows=${rows.size} " +
          s"receptions=${rows.map(_.n).sum} filtering=${PicsScanner.offloadedFiltering(act)} batching=${PicsScanner.offloadedBatching(act)} " +
          s"passive=${PicsScanner.passiveScan} broken=${PicsLive.broken}")
      }
    }

  /** Time, intersection, kind, RSSI and what it says (signals in their colours). */
  private def recentText(rs: Seq[PicsAdv]): CharSequence =
    val sb = SpannableStringBuilder()
    for (a, i) <- rs.zipWithIndex do
      if i > 0 then sb.append('\n')
      val where = a.iid.map(id => PicsLive.heard.get(id).fold(s"ID ${PicsFormat.idNumber(id)}")(_.label)).getOrElse("—")
      sb.append(f"${PicsUi.time(a.start)}%8s  $where  ${a.kind.fold("種別?")(k => s"種別$k")}  ${a.rssiMax} dBm  ")
      val packet = PicsFormat.unhex(a.data).toOption.map(PicsFormat.packet)
      packet.map(_.body) match
        case Some(PicsFormat.Body.Signals(ps)) => sb.append(PicsUi.marks(ps, fresh = true))
        case Some(PicsFormat.Body.Position(lat, lon)) => sb.append(f"$lat%.5f, $lon%.5f")
        case Some(PicsFormat.Body.Text(t)) => sb.append(t)
        case _ => sb.append(s"${a.data.length / 2} バイト")
      if a.n > 1 then sb.append(s"  ×${a.n}")
    sb
