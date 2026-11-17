package net.sksat.ashidori.ui

import android.os.{Handler, Looper}
import android.view.View
import net.sksat.ashidori.*
import ashidori.core.*
import ashidori.core.EnergyModel.{Coef, GnssClass, Report}
import Ui.*

/** The energy analysis ([[EnergyModel]]) and the positioning by screen state
  * ([[FixQuality]]) of the last days, computed off the main thread and kept
  * for a few minutes. Only while [[Settings.showEstimates]] is on. The
  * positions stream through ([[Db.foreachFix]], [[FixQuality.Aggregator]]):
  * the logger's process never holds the week's fixes.
  */
object EnergyStats:
  val Days = 7
  final case class Analysis(energy: Report, fixes: FixQuality.Result)
  private val MaxAgeMs = 10 * 60_000L
  @volatile private var cache: Option[(Long, Analysis)] = None
  @volatile private var loading = false
  private val main = Handler(Looper.getMainLooper)
  private var waiting = Vector.empty[Analysis => Unit]

  def cached: Option[Report] = cache.map(_._2.energy)

  /** Calls `done` on the main thread with a recent analysis (at once if cached). */
  def load(done: Analysis => Unit): Unit =
    val now = System.currentTimeMillis()
    cache match
      case Some((at, r)) if now - at < MaxAgeMs => done(r)
      case _ =>
        waiting :+= done
        if !loading then
          loading = true
          AshidoriApp.io.execute { () =>
            val r = scala.util.Try {
              val from = now - Days * 86_400_000L
              val (readings, spans) = AshidoriApp.db.energyData(from, now)
              val fq = FixQuality.Aggregator(spans, readings)
              AshidoriApp.db.foreachFix(from, now)(fq.add)
              Analysis(EnergyModel.analyse(readings, spans), fq.result)
            }
            main.post { () =>
              loading = false
              r.failed.foreach(e => Log.w("energy analysis", e))
              r.foreach { rep =>
                cache = Some((now, rep))
                val ws = waiting
                waiting = Vector.empty
                ws.foreach(_(rep))
              }
            }
          }

/** Plain-term formatting of the estimates (formal Japanese, one short line each). */
object EnergyUi:
  def pct(d: Double): String = if d >= 10 then f"$d%.0f" else f"$d%.1f"

  /** The estimated line under a mode's description. */
  def modeLine(m: LoggingMode): String =
    EnergyStats.cached match
      case None    => "観測からの推定: 算出中"
      case Some(r) => r.modePerDay(m).fold(why => s"観測からの推定: —（$why）", d => s"観測からの推定: 記録による消費 1日 約${pct(d)}%")

/** What the battery went to over the last week: what recording adds
  * (「記録による消費」) and what the whole phone uses (「端末全体の消費」), in
  * two groups so neither needs a caption. The rows are always there;
  * values fill in when the analysis is done.
  */
final class EnergyPanel(using c: Ctx):
  import EnergyUi.pct
  private def mk(title: String): ListRow =
    val r = ListRow(c)
    r.title.setText(title)
    oneLine(r.subtitle)
    tabular(r.value)
    r.setValue("—")
    r.setSubtitle("算出中")
    r
  private val recording = mk("合計")
  private val gnss = mk("GNSS")
  private val detail = mk("生データ記録")
  private val active = mk("移動の検出")
  private val standby = mk("画面オフ・静止中")
  private val screen = mk("画面オン")
  private val data = mk("データ")

  val view: View = vbox(
    sectionHeader(s"記録による消費（直近${EnergyStats.Days}日・観測からの推定）"),
    listGroup(recording, gnss, detail, active),
    sectionHeader(s"端末全体の消費（直近${EnergyStats.Days}日・観測からの推定）"),
    listGroup(standby, screen, data)
  )

  private def set(r: ListRow, value: String, sub: String): Unit =
    r.setValue(value)
    r.setSubtitle(sub)

  private def hours(h: Double) = if h < 10 then f"$h%.1f時間分" else f"$h%.0f時間分"

  private def coef(r: ListRow, c: Coef, value: Double => String, sub: String, what: String): Unit =
    if !c.perHour.isNaN && !c.identifiable then set(r, "推定不可", "他の消費と分離できない")
    else if c.ok then set(r, value(c.perHour), sub)
    else if c.perHour.isNaN || c.hours < c.needHours || c.windows < 3 then set(r, "—", s"データ不足（$what${hours(c.hours)}）")
    else set(r, "—", s"精度不足（$what${hours(c.hours)}）")

  def show(rep: Report): Unit =
    val u = rep.usage
    rep.fit match
      case Left(ne) =>
        for r <- Seq(standby, gnss, detail, active, screen) do set(r, "—", "データ不足")
        set(recording, "—", ne.reason)
        set(data, if u.days > 0 then f"${u.days}%.1f日分" else "—", ne.reason)
      case Right(f) =>
        coef(standby, f.baseline, v => s"${pct(v)}%/時", hours(f.baseline.hours), "静止 ")
        val okClasses = GnssClass.values.toSeq.filter(c => f.gnss(c).ok)
        if okClasses.isEmpty then
          val h = f.gnss.values.map(_.hours).sum
          if f.gnss.values.exists(c => !c.perHour.isNaN && !c.identifiable) then set(gnss, "推定不可", "他の消費と分離できない")
          else set(gnss, "—", s"データ不足（GNSS ${hours(h)}）")
        else
          val h = okClasses.map(c => f.gnss(c).hours).sum
          val mean = okClasses.map(c => f.gnss(c).perHour * f.gnss(c).hours).sum / h
          set(gnss, s"+${pct(mean)}%/時", okClasses.map(c => s"${c.short} ${pct(f.gnss(c).perHour)}").mkString("・"))
        if u.detailMinutes.isNaN then coef(detail, f.detail, v => s"+${pct(v / 60)}%/分", "1分あたり", "")
        else
          val m = u.detailMinutes
          coef(detail, f.detail, v => s"+${pct(v * m / 60)}%/回", s"1回 平均${if m < 10 then f"$m%.1f" else f"$m%.0f"}分", "")
        coef(active, f.active, v => s"+${pct(v)}%/時", "加速度センサー・省電力測位", "移動 ")
        if f.screen.ok && f.baseline.ok then set(screen, s"${pct(f.baseline.perHour + f.screen.perHour)}%/時", hours(f.screen.hours))
        else if f.screen.ok then set(screen, "—", "画面オフ・静止中が未算出")
        else coef(screen, f.screen, _ => "—", "", "画面点灯 ")
        rep.recordingPerDay match
          case Right(d) => set(recording, s"${pct(d)}%/日", "推定")
          case Left(why) => set(recording, "—", why)
        set(data, f"${u.days}%.1f日分",
          if f.source == EnergyModel.Source.Counter then f"電池残量計（${f.capacityMah}%.0f mAh）から算出"
          else "電池残量（%）から算出")

/** 「画面オフ時の測位」: whether positioning gets sparser or coarser in the
  * background. One row per source, both screen states on two fixed lines.
  */
final class FixQualityPanel(using c: Ctx):
  private def mk(title: String): ListRow =
    val r = ListRow(c)
    r.title.setText(title)
    r.subtitle.setLines(2)
    tabular(r.subtitle)
    r.setSubtitle("画面オン: 算出中\n画面オフ: 算出中")
    r
  private val rows = FixQuality.Source.values.toSeq.map(s => s -> mk(s.label)).toMap

  val view: View = vbox(
    sectionHeader(s"画面オフ時の測位（直近${EnergyStats.Days}日の中央値）"),
    listGroup(FixQuality.Source.values.toSeq.map(rows)*)
  )

  private def line(cell: Option[FixQuality.Cell]): String =
    cell.fold("—（データ不足）") { c =>
      val iv = if c.intervalS < 10 then f"${c.intervalS}%.1f秒" else f"${c.intervalS}%.0f秒"
      val acc = if c.accuracyM.isNaN then "—" else f"${c.accuracyM}%.0f m"
      s"間隔 $iv・精度 $acc"
    }

  def show(r: FixQuality.Result): Unit =
    for (s, row) <- rows do row.setSubtitle(s"画面オン: ${line(r(s, true))}\n画面オフ: ${line(r(s, false))}")
