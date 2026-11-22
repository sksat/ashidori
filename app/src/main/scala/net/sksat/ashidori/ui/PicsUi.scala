package net.sksat.ashidori.ui

import android.os.{Handler, Looper, SystemClock}
import android.text.{SpannableStringBuilder, Spanned}
import android.text.style.ForegroundColorSpan
import android.view.View
import java.time.Instant
import java.time.format.DateTimeFormatter
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

  private val timeFmt = DateTimeFormatter.ofPattern("H:mm:ss")
  def time(wall: Long): String = Instant.ofEpochMilli(wall).atZone(AshidoriApp.zone).format(timeFmt)

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

  /** The ステータス row 「信号機」: the intersection, its signals while
    * receiving, the time of the last reception, and when it changes next
    * without a reception (elapsed ms: the grey at exactly 2 s,
    * [[PicsDisplay]]). None: not shown (off, or nothing for 10 minutes).
    */
  def statusLine(using Ctx): Option[(CharSequence, String, Long)] =
    if !Settings.picsEnabled then None
    else
      val now = SystemClock.elapsedRealtime()
      val receiving = Set(PicsScanPolicy.State.Receiving, PicsScanPolicy.State.Test).contains(PicsLive.plan.state)
      PicsLive.current.flatMap(h => PicsDisplay.at(now, receiving, h.signalsAt, h.lastAt).map(h -> _)).map { (h, v) =>
        val sb = SpannableStringBuilder(h.label)
        val showSignals = v.signals && h.peds.nonEmpty
        if showSignals then sb.append("  ").append(marks(h.peds, v.fresh))
        sb.append("  ").append(time(h.lastWall))
        (sb, s"${h.label}、" + (if showSignals then spoken(h.peds, v.fresh) + "、" else "") + s"最後の受信 ${time(h.lastWall)}",
          v.nextAt)
      }

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
        PicsLive.broken.map(e => s"処理の失敗のため停止: $e")).mkString("・"))
    stateRow.subtitle.setTextColor(color(if PicsLive.broken.isDefined then attr.error else attr.onSurfaceVariant))
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
