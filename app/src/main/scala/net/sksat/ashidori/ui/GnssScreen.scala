package net.sksat.ashidori.ui

import android.view.View
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Live satellite view: sky plot, signal strength and a per-constellation
  * breakdown, reachable from the map and the recording screen.
  */
final class GnssScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "衛星測位"
  private val sky = SkyPlotView(act)
  private val bars = Cn0BarsView(act)
  private val summary = text("", 16f, bold = true)
  private val detail = caption("")
  private val table = vbox()
  private val fix = mono("")
  private val history = GnssHistoryView(act)
  private val skyCard = card(text("空の衛星", 16f, bold = true), caption("中心が真上、外周が地平線。塗りつぶしは測位に使用中、外側の輪は L5 帯も受信中。"), sky)
  private val cn0Card = card(text("信号の強さ（C/N0）", 16f, bold = true), bars, table)
  private val historyNote = caption("")
  private val wake = button("今すぐ衛星で測位する（5分）", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }

  val view: View =
    scroll(
      vbox(
        card(summary, detail, wake),
        skyCard,
        cn0Card,
        card(
          text("直近1時間", 16f, bold = true),
          caption("棒: 測位に使った衛星の数　線: 強い信号4機の C/N0　灰色の背景: 屋内と判定した時間"),
          history,
          historyNote
        ),
        card(text("現在の測位", 16f, bold = true), fix),
        spacer(24)
      )
    )

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    refresh()
    loadHistory()

  private def loadHistory(): Unit =
    val now = System.currentTimeMillis()
    AshidoriApp.io.execute { () =>
      val d = AshidoriApp.db.gnssStatus(now - 3_600_000L)
      act.runOnUiThread { () =>
        history.update(d, now - 3_600_000L, now)
        val indoorMs = d.sliding(2).collect {
          case Seq(a, b) if a._2 == Environment.Indoor && b._1.time - a._1.time < 120_000 => b._1.time - a._1.time
        }.sum
        historyNote.setText(
          if d.isEmpty then "この1時間の衛星測位の記録はありません（静止中は停止します）"
          else s"記録 ${d.size} 回・屋内と判定 約 ${indoorMs / 60_000} 分"
        )
      }
    }
  override def onHide(): Unit = unobserve()

  private def refresh(): Unit =
    val sats = LoggerState.satellites
    sky.update(sats)
    bars.update(sats)
    val on = sats.nonEmpty
    wake.setVisibility(if on then View.GONE else View.VISIBLE)
    skyCard.setVisibility(if on then View.VISIBLE else View.GONE)
    cn0Card.setVisibility(if on then View.VISIBLE else View.GONE)
    if !on then
      summary.setText(if LoggerState.running then "衛星測位: 一時停止中（静止中）" else "記録停止中")
      detail.setText(
        if !LoggerState.running then "記録画面から再開できます。"
        else "静止中は電池節約のため衛星測位を停止し、Wi-Fi・基地局による位置で記録しています。移動を検知すると自動で再開します。"
      )
    else
      val used = sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
      val seen = sats.map(s => (s.constellation, s.svid)).distinct.size
      val top4 = sats.map(_.cn0).sorted.reverse.take(4)
      val mean = if top4.isEmpty then 0f else top4.sum / top4.size
      val env = LoggerState.environment match
        case Environment.Unknown => ""
        case e                   => s"・${e.label}"
      summary.setText(s"測位に $used 機を使用（捕捉 $seen 機）$env")
      // Weak strongest signals usually mean the sky is blocked (indoors, under a roof).
      detail.setText(f"上位4機の平均 C/N0 ${mean}%.1f dB-Hz" + (if mean < 25 then "・屋内または上空が遮られている可能性" else ""))
    table.removeAllViews()
    for (c, ss) <- sats.groupBy(_.constellation).toSeq.sortBy(-_._2.size) do
      val name = text(Constellations.name(c), 14f)
      name.setTextColor(Constellations.color(c))
      name.setLayoutParams(weighted())
      val sv = ss.map(_.svid).distinct
      val bands = ss.groupBy(_.band).map((b, x) => s"$b ${x.size}").mkString(" / ")
      table.addView(hbox(name, caption(s"${sv.size}機・使用 ${ss.filter(_.usedInFix).map(_.svid).distinct.size}・$bands")))
    val now = System.currentTimeMillis()
    fix.setText(LoggerState.lastFix match
      case Some(f) =>
        f"${f.lat}%.6f, ${f.lon}%.6f\n精度 ±${f.accuracy}%.0fm・${f.provider}・${(now - f.time) / 1000}秒前" +
          (if f.hasSpeed then f"\n速度 ${f.speed * 3.6}%.1f km/h" else "")
      case None => "まだ測位していません"
    )
