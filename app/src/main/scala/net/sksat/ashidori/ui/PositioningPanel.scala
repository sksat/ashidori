package net.sksat.ashidori.ui

import android.graphics.Typeface
import android.view.{Gravity, View}
import com.google.android.material.chip.{Chip, ChipGroup}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** "How well is my position known right now": accuracy and source, then the
  * satellites, then the last hour. The top of the 測位 tab.
  */
final class PositioningPanel(act: MainActivity):
  private given Ctx = act

  // --- current position ---
  private val accuracy =
    val t = text("", 40f, bold = true)
    t.setTypeface(Typeface.DEFAULT_BOLD)
    t
  private val source = text("", 16f)
  private val age = caption("")
  private val chips = ChipGroup(act)
  private val position = card(text("測位状況", 16f, bold = true), hbox(accuracy, padded(vbox(source, age), 12, 0)), chips)

  // --- satellites ---
  private val satHeadline = text("", 16f, bold = true)
  private val satDetail = caption("")
  private val constellations = ChipGroup(act)
  private val sky = SkyPlotView(act)
  private val bars = Cn0BarsView(act)
  private val wake = button("今すぐ衛星で測位する（5分）", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val skyHelp = caption("中心が真上、外周が地平線。塗りつぶしは測位に使用中、外側の輪は L5 帯も受信。")
  private val satellites = card(text("衛星", 16f, bold = true), satHeadline, satDetail, constellations, sky, skyHelp, bars, wake)

  // --- last hour ---
  private val history = GnssHistoryView(act)
  private val historyNote = caption("")
  private val hour = card(
    text("直近1時間", 16f, bold = true),
    caption("棒: 測位に使った衛星の数　線: 強い信号4機の C/N0　灰色: 屋内と判定"),
    history,
    historyNote
  )

  val views: Seq[View] = Seq(position, satellites, hour)

  private def sourceLabel(p: String): String = p match
    case "gps"                => "衛星（GNSS）"
    case "fused" | "network"  => "Wi-Fi・基地局"
    case Pdr.Provider         => "歩数と向きからの推定"
    case "passive"            => "他アプリの測位"
    case "sim"                => "シミュレーション"
    case other                => other

  private def chip(label: String, color: Int = 0): Chip =
    val c = Chip(act)
    c.setText(label)
    c.setClickable(false)
    if color != 0 then c.setChipIconTint(android.content.res.ColorStateList.valueOf(color))
    c

  def refresh(): Unit =
    val now = System.currentTimeMillis()
    LoggerState.lastFix match
      case Some(f) =>
        accuracy.setText(if f.hasAccuracy then f"±${f.accuracy}%.0fm" else "±?")
        // GNSS is the norm: name the source only when it is something else.
        source.setText(
          if f.provider == "gps" then
            val used = LoggerState.satellites.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
            if used > 0 then s"衛星 ${used}機" else ""
          else sourceLabel(f.provider)
        )
        val sec = (now - f.time) / 1000
        val when = if sec < 60 then s"${sec}秒前" else if sec < 3600 then s"${sec / 60}分前" else s"${sec / 3600}時間前"
        // An old fix says little about now: say so instead of looking precise.
        val stale = sec > 5 * 60
        accuracy.setAlpha(if stale then 0.4f else 1f)
        age.setText(
          when + (if !stale then ""
            else if LoggerState.motion == MotionState.Stationary then "の位置（静止中は更新を控えています）"
            else "の位置（新しい位置を待っています）") +
            (if !stale && f.hasSpeed then f"・${f.speed * 3.6}%.0f km/h" else "")
        )
      case None =>
        accuracy.setText("—")
        source.setText(if LoggerState.running then "位置を待っています" else "記録停止中")
        age.setText("")
    chips.removeAllViews()
    // Only what is known: an "unknown" chip says nothing useful.
    if LoggerState.environment != Environment.Unknown then chips.addView(chip(LoggerState.environment.label))
    LoggerState.motion match
      case MotionState.Unknown    => ()
      case MotionState.Stationary => chips.addView(chip("静止中"))
      case m                      => chips.addView(chip(m.label))
    if LoggerState.battery >= 0 then
      val d = LoggerState.drainPctPerHour
      chips.addView(chip(s"電池 ${LoggerState.battery}%" + (if d.isNaN then "" else f"・$d%.1f%%/時")))

    val sats = LoggerState.satellites
    val on = sats.nonEmpty
    sky.update(sats)
    bars.update(sats)
    Seq(sky, skyHelp, bars, constellations).foreach(_.setVisibility(if on then View.VISIBLE else View.GONE))
    val gnssOn = LoggerState.plan.exists(_.source == LocationSource.Gnss)
    wake.setVisibility(if on || gnssOn || !LoggerState.running then View.GONE else View.VISIBLE)
    if on then
      val used = sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
      val seen = sats.map(s => (s.constellation, s.svid)).distinct.size
      val top4 = sats.map(_.cn0).sorted.reverse.take(4)
      val mean = if top4.isEmpty then 0f else top4.sum / top4.size
      satHeadline.setText(s"測位に ${used} 機を使用（捕捉 ${seen} 機）")
      satDetail.setText(
        f"強い信号4機の平均 ${mean}%.0f dB-Hz" +
          (if mean >= 35 then "・良好" else if mean >= 25 then "・やや弱い" else "・弱い（屋内・上空が遮られている）")
      )
      constellations.removeAllViews()
      for (c, ss) <- sats.groupBy(_.constellation).toSeq.sortBy(-_._2.size) do
        val n = ss.map(_.svid).distinct.size
        val u = ss.filter(_.usedInFix).map(_.svid).distinct.size
        val l5 = ss.count(_.band == "L5")
        val ch = chip(s"${Constellations.name(c)} $u/$n" + (if l5 > 0 then s"・L5 $l5" else ""))
        ch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Constellations.color(c) & 0x33ffffff))
        constellations.addView(ch)
    else if LoggerState.running && LoggerState.plan.exists(_.source == LocationSource.Gnss) then
      // GNSS is on but nothing tracked yet.
      satHeadline.setText("衛星を探しています…")
      satDetail.setText("屋外では通常数秒〜数十秒で捕捉します。屋内では捕捉できないことがあります。")
    else if LoggerState.running then
      satHeadline.setText("衛星測位: 一時停止中")
      satDetail.setText(
        if LoggerState.environment == Environment.Indoor then "屋内のため Wi-Fi・基地局で測位しています。3分ごとに屋外かどうか確認します。"
        else "静止中は電池節約のため衛星測位を止めています。移動を検知すると自動で再開します。"
      )
    else
      satHeadline.setText("記録停止中")
      satDetail.setText("")

  def loadHistory(): Unit =
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

object PositioningPanel:
  private def every(ms: Long): String =
    if ms < 60_000 then s"${(ms / 1000.0).toString.stripSuffix(".0")}秒ごと" else s"${ms / 60_000}分ごと"

  /** What the logger is doing, in plain words. */
  def planSentence(p: SamplingPlan): String =
    val how = p.source match
      case LocationSource.Gnss    => s"${every(p.intervalMs)}に測位"
      case LocationSource.Network => s"Wi-Fi・基地局で${every(p.intervalMs)}に測位"
      case LocationSource.Passive => "測位を休止中（他のアプリが取得した位置のみ記録）"
    how + (if p.rawGnss then "・詳細ログ記録中" else "")
