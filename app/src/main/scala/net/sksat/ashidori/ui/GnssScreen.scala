package net.sksat.ashidori.ui

import android.view.View
import com.google.android.material.chip.Chip
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Compact live GNSS status ("🛰 12/30 ±5m") that opens the GNSS screen.
  * Call bind/unbind from the host screen's onShow/onHide.
  */
final class GnssChip(act: MainActivity):
  private given Ctx = act
  val view: Chip =
    val c = Chip(act)
    c.setOnClickListener(_ => act.push(GnssScreen(act)))
    c.setContentDescription("衛星測位の状態。タップで詳細")
    c
  private var unobserve: () => Unit = () => ()

  def bind(): Unit =
    unobserve()
    unobserve = LoggerState.observe(() => refresh())
    refresh()

  def unbind(): Unit = unobserve()

  def refresh(): Unit =
    val sats = LoggerState.satellites
    val acc = LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 2 * 60_000).map(_.accuracy)
    val accText = acc.filterNot(_.isNaN).map(a => f"・±$a%.0fm").getOrElse("")
    // Say what it means for the user, not the internal state.
    view.setText(
      if !LoggerState.running then "記録停止中"
      else if sats.isEmpty then s"🛰 省電力中$accText"
      else s"🛰 衛星${sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size}機$accText"
    )

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
        card(text("空の衛星", 16f, bold = true), caption("中心が真上、外周が地平線。塗りつぶしは測位に使用中、外側の輪は L5 帯も受信中。"), sky),
        card(text("信号の強さ（C/N0）", 16f, bold = true), bars, table),
        card(text("現在の測位", 16f, bold = true), fix),
        spacer(24)
      )
    )

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    refresh()
  override def onHide(): Unit = unobserve()

  private def refresh(): Unit =
    val sats = LoggerState.satellites
    sky.update(sats)
    bars.update(sats)
    val on = sats.nonEmpty
    wake.setVisibility(if on then View.GONE else View.VISIBLE)
    if !on then
      summary.setText(if LoggerState.running then "省電力のため衛星測位を休んでいます" else "記録が止まっています")
      detail.setText(
        if !LoggerState.running then "記録画面から再開できます。"
        else "止まっている間は衛星（GNSS）を使わず、Wi-Fi や基地局のおおまかな位置で見守っています。動き出すと自動で衛星測位に戻ります。"
      )
    else
      val used = sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
      val seen = sats.map(s => (s.constellation, s.svid)).distinct.size
      val top4 = sats.map(_.cn0).sorted.reverse.take(4)
      val mean = if top4.isEmpty then 0f else top4.sum / top4.size
      summary.setText(s"測位に $used 機を使用（捕捉 $seen 機）")
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
