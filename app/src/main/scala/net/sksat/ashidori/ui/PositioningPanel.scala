package net.sksat.ashidori.ui

import android.graphics.Typeface
import android.view.{Gravity, View}
import com.google.android.material.chip.Chip
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
    val t = tabular(oneLine(text("", 40f)))
    t.setTypeface(Typeface.DEFAULT_BOLD)
    // A fixed column up to two digits, so the source beside it does not
    // move as the accuracy changes (±5m, ±12m, —).
    t.setMinWidth(t.getPaint.measureText("±99m").ceil.toInt)
    t
  private val source = oneLine(tabular(text("", 16f)))
  private val age = oneLine(tabular(caption("")))
  /** Accuracy of the latest position, shown in the status card. */
  val accuracyRow: View =
    val col = padded(vbox(source, age), 12, 0)
    col.setLayoutParams(weighted())
    hbox(accuracy, col)

  // --- satellites ---
  // The same rows in every state (receiving, acquiring, off); "—" where
  // there is nothing to show.
  private def row(label: String): (View, android.widget.TextView) =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(android.widget.LinearLayout.LayoutParams(dp(72), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    val v = tabular(oneLine(text("", 15f)))
    v.setLayoutParams(weighted())
    val h = hbox(l, v)
    h.setPadding(0, dp(3), 0, dp(3))
    (h, v)
  private val (stateRow, satState) = row("受信")
  private val (usedRow, satUsed) = row("使用")
  private val (signalRow, satSignal) = row("信号")
  /** Why receiving is off, or what to expect; an empty line otherwise. */
  private val satNote =
    val t = oneLine(caption(""))
    t.setPadding(0, dp(2), 0, dp(4))
    t
  /** Fixed cells in a fixed order, one per constellation: a constellation
    * coming into view changes a value, not the layout.
    */
  private val constellationKinds: Seq[(String, Int => Boolean)] = Seq(
    android.location.GnssStatus.CONSTELLATION_GPS,
    android.location.GnssStatus.CONSTELLATION_QZSS,
    android.location.GnssStatus.CONSTELLATION_GALILEO,
    android.location.GnssStatus.CONSTELLATION_BEIDOU,
    android.location.GnssStatus.CONSTELLATION_GLONASS
  ).map(c => (Constellations.name(c), (_: Int) == c)) :+
    ("その他", (c: Int) => !Set(
      android.location.GnssStatus.CONSTELLATION_GPS,
      android.location.GnssStatus.CONSTELLATION_QZSS,
      android.location.GnssStatus.CONSTELLATION_GALILEO,
      android.location.GnssStatus.CONSTELLATION_BEIDOU,
      android.location.GnssStatus.CONSTELLATION_GLONASS
    ).contains(c))
  private val constellationChips: Seq[Chip] = constellationKinds.map { _ =>
    val c = Chip(act)
    c.setClickable(false)
    c.setEnsureMinTouchTargetSize(false)
    c.setSingleLine(true)
    c.setEllipsize(android.text.TextUtils.TruncateAt.END)
    c.setFontFeatureSettings("tnum")
    val lp = weighted()
    lp.setMargins(0, dp(2), dp(4), dp(2))
    c.setLayoutParams(lp)
    c
  }
  private val constellations = vbox(constellationChips.grouped(2).map(cs => hbox(cs*)).toSeq*)
  private val sky = SkyPlotView(act)
  private val bars = Cn0BarsView(act)
  private val wake = button("5分間 衛星で測位", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val skyHelp = caption("中心＝真上・外周＝地平線　● 使用中　◎ L5 も受信")
  private val satellites = card(text("衛星", 16f, bold = true), spacer(4), stateRow, usedRow, signalRow, satNote,
    constellations, sky, skyHelp, bars, wake)

  // --- last hour ---
  private val history = GnssHistoryView(act)
  private val historyNote = oneLine(tabular(caption("")))
  /** A color sample and what it means, for the chart's legend. */
  private def swatch(col: Int, label: String, line: Boolean = false): View =
    val v = View(act)
    v.setBackgroundColor(col)
    v.setLayoutParams(android.widget.LinearLayout.LayoutParams(dp(if line then 14 else 10), dp(if line then 3 else 10)))
    val t = caption(label)
    t.setPadding(dp(4), 0, dp(12), 0)
    hbox(v, t)
  private val hour = card(
    text("受信状況（直近1時間）", 16f, bold = true),
    hbox(
      swatch(color(attr.primary), "使用衛星数"),
      swatch(color(attr.tertiary), "信号強度", line = true),
      swatch(color(attr.secondaryContainer), "屋内")
    ),
    history,
    historyNote
  )

  val views: Seq[View] = Seq(satellites, hour)

  private def sourceLabel(p: String): String = p match
    case "gps"                => "衛星（GNSS）"
    case "fused" | "network"  => "Wi-Fi・基地局"
    case Pdr.Provider         => "歩数と向きからの推定"
    case "passive"            => "他アプリの測位"
    case "sim"                => "シミュレーション"
    case other                => other

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
            else if LoggerState.motion == MotionState.Stationary then "（静止中）"
            else "（更新待ち）") +
            (if !stale && f.hasSpeed then f"・${f.speed * 3.6}%.0f km/h" else "")
        )
      case None =>
        accuracy.setText("—")
        source.setText(if LoggerState.running then "測位待ち" else "記録停止中")
        age.setText("")

    val sats = LoggerState.satellites
    val on = sats.nonEmpty
    // The plot and the bars stay in place, empty while nothing is received.
    sky.update(sats)
    bars.update(sats)
    val gnssOn = LoggerState.plan.exists(_.source == LocationSource.Gnss)
    // Always there; usable only when it would turn the receiver on.
    wake.setEnabled(!on && !gnssOn && LoggerState.running)
    for ((name, matches), ch) <- constellationKinds.zip(constellationChips) do
      val ss = sats.filter(s => matches(s.constellation))
      if ss.isEmpty then
        ch.setText(s"$name —")
        ch.setTextColor(color(attr.onSurfaceVariant))
        ch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(color(attr.surfaceContainerHigh)))
      else
        val n = ss.map(s => (s.constellation, s.svid)).distinct.size
        val u = ss.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
        val l5 = ss.count(_.band == "L5")
        ch.setText(s"$name $u/$n" + (if l5 > 0 then s"・L5 $l5" else ""))
        ch.setTextColor(color(attr.onSurface))
        ch.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Constellations.color(ss.head.constellation) & 0x33ffffff))
    if on then
      val used = sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
      val seen = sats.map(s => (s.constellation, s.svid)).distinct.size
      val top4 = sats.map(_.cn0).sorted.reverse.take(4)
      val mean = if top4.isEmpty then 0f else top4.sum / top4.size
      satState.setText("受信中")
      satUsed.setText(s"${used} 機（捕捉 ${seen} 機）")
      satSignal.setText(
        f"強い4機の平均 ${mean}%.0f dB-Hz" +
          (if mean >= 35 then "・良好" else if mean >= 25 then "・やや弱い" else "・弱い")
      )
      satNote.setText("")
    else
      satUsed.setText("—")
      satSignal.setText("—")
      if LoggerState.running && gnssOn then
        // GNSS is on but nothing tracked yet.
        satState.setText("捕捉中")
        satNote.setText("ⓘ 屋外では通常数十秒以内")
      else if LoggerState.running then
        satState.setText("停止中")
        satNote.setText(
          if LoggerState.environment == Environment.Indoor then "ⓘ 屋内のため（移動開始時に再確認）"
          else "ⓘ 静止中のため（移動開始時に再開）"
        )
      else
        satState.setText("停止中")
        satNote.setText("")

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
          if d.isEmpty then "受信なし"
          else s"屋内 約${indoorMs / 60_000}分"
        )
      }
    }

object PositioningPanel:
  private def every(ms: Long): String =
    if ms < 60_000 then s"${(ms / 1000.0).toString.stripSuffix(".0")}秒ごと" else s"${ms / 60_000}分ごと"

  /** How positions are obtained right now, as a state (not a sentence). */
  def sourceState(p: SamplingPlan): String =
    val how = p.source match
      case LocationSource.Gnss    => s"衛星・${every(p.intervalMs)}"
      case LocationSource.Network => s"Wi-Fi・基地局・${every(p.intervalMs)}"
      case LocationSource.Passive => "休止（他アプリの位置のみ）"
    how + (if p.rawGnss then " + 詳細ログ" else "")

  /** Why the state is what it is, first part: the mode actually in effect
    * and why. None without a reason (also right after a switch, before the
    * new plan arrives).
    */
  def modeNote(p: SamplingPlan, chosen: LoggingMode): Option[String] =
    val other = p.reasons.filterNot(_ == "屋内")
    if other.isEmpty then None
    else if p.effectiveMode != chosen then Some(s"「${p.effectiveMode.label}」で動作中: ${other.mkString("・")}")
    else Some(other.mkString("・"))

  /** A typical mode note, for the height its line keeps when empty. */
  val ModeNoteSample: String = "「バランス」で動作中: 充電中のため1段階上げ"

  private def indoorText(gnss: Boolean): String =
    val head =
      if gnss then "屋外判定のため衛星で測位中"
      else "屋内のため Wi-Fi・基地局で測位"
    head + s"（屋外判定: 移動開始時・移動中${SamplingPolicy.IndoorProbeMovingEveryMs / 60_000}分ごと・" +
      s"静止中${SamplingPolicy.IndoorProbeStillEveryMs / 60_000}分ごと）"

  /** Second part: how positions are checked while indoors. */
  def indoorNote(p: SamplingPlan): Option[String] =
    if p.reasons.contains("屋内") then Some(indoorText(p.source == LocationSource.Gnss)) else None

  /** Every form the indoor note takes, for the room it keeps. */
  def indoorNoteVariants: Seq[String] = Seq(indoorText(true), indoorText(false))
