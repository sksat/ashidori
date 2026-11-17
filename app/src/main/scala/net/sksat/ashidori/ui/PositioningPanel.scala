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

  // --- satellites: 「現在」 then 「履歴」, in one card ---
  // The same rows in every state (receiving, acquiring, off); 「—」 where
  // there is nothing to show, and each value's age beside it.
  private val state = SensorUi.StateLine()
  private val fixRow = SensorUi.ValueRow("測位")
  private val elevationRow = SensorUi.ValueRow("標高")
  private val usedRow = SensorUi.ValueRow("衛星")
  private val signalRow = SensorUi.ValueRow("信号")
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
  private val wake = button(PositioningPanel.BurstLabel, tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val skyHelp = caption("● 使用中　◎ L5 も受信")

  private val history = GnssHistoryView(act)
  private val historyNote = oneLine(tabular(caption("")))
  private val rangeGroup = SensorUi.rangeToggle(PositioningPanel.day) { d =>
    PositioningPanel.day = d
    loadHistory()
  }
  /** A color sample and what it means, for the chart's legend. */
  private def swatch(col: Int, label: String, line: Boolean = false): View =
    val v = View(act)
    v.setBackgroundColor(col)
    v.setLayoutParams(android.widget.LinearLayout.LayoutParams(dp(if line then 14 else 10), dp(if line then 3 else 10)))
    SensorUi.legendItem(v, label)
  private val satellites = card(
    text("衛星測位", 16f, bold = true),
    SensorUi.section("現在"),
    state.view,
    fixRow.view,
    elevationRow.view,
    usedRow.view,
    signalRow.view,
    spacer(4),
    constellations,
    sky,
    skyHelp,
    spacer(6),
    bars,
    hbox(
      swatch(color(attr.onSurfaceVariant), "測位に使用"),
      swatch((color(attr.onSurfaceVariant) & 0x00ffffff) | 0x6e000000, "未使用")
    ),
    spacer(6),
    wake,
    SensorUi.section("履歴", rangeGroup),
    hbox(
      swatch(color(attr.primary), "使用衛星数"),
      swatch(color(attr.tertiary), "信号強度", line = true)
    ),
    hbox(swatch(color(attr.secondaryContainer), "屋内")),
    spacer(4),
    history,
    historyNote
  )

  val views: Seq[View] = Seq(satellites)

  private def sourceLabel(p: String): String = p match
    case "gps"                => "衛星"
    case "fused" | "network"  => "Wi-Fi・基地局"
    case Pdr.Provider         => "歩数と向きから推定"
    case "passive"            => "他アプリの測位"
    case "sim"                => "シミュレーション"
    case other                => other

  // Ages tick every second while shown; the history reloads every minute.
  private val main = android.os.Handler(android.os.Looper.getMainLooper)
  private var shown = false
  private var ticks = 0
  private val tick: Runnable = () =>
    if shown then
      ticks += 1
      if ticks % 60 == 0 then loadHistory()
      refresh()
      main.postDelayed(tick, 1_000)

  def onShow(): Unit =
    if !shown then
      shown = true
      ticks = 0
      main.postDelayed(tick, 1_000)
    loadHistory()

  def onHide(): Unit =
    shown = false
    main.removeCallbacks(tick)
    state.stopPulse()
  SensorUi.onPause(act)(onHide())

  /** A fix older than this says little about now. */
  private val StaleMs = 5 * 60_000L

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
        val stale = now - f.time > StaleMs
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
    val running = LoggerState.running
    val gnssOn = LoggerState.plan.exists(_.source == LocationSource.Gnss)
    // The plot and the bars stay in place, empty while nothing is received.
    sky.update(sats)
    bars.update(sats)
    // Always there; usable only when it would turn the receiver on.
    wake.setEnabled(!on && !gnssOn && running)

    // State of the receiver, and why.
    val how = LoggerState.plan.filter(_ => running).fold("")(PositioningPanel.sourceState)
    if !running then state.set("記録停止中", SensorUi.Tone.Off, "")
    else if on then state.set("受信中", SensorUi.Tone.Active, how)
    else if gnssOn then state.set("探索中", SensorUi.Tone.Waiting, "")
    else if LoggerState.environment == Environment.Indoor then
      state.set("停止中（屋内）", SensorUi.Tone.Off, s"$how・移動開始時に再確認")
    else if LoggerState.motion == MotionState.Stationary then
      state.set("停止中（静止）", SensorUi.Tone.Off, s"$how・移動開始時に再開")
    else state.set("停止中", SensorUi.Tone.Off, how)

    // 測位: the latest position, whatever its source; its age always shown,
    // in the error color once it is stale.
    LoggerState.lastFix match
      case Some(f) =>
        val stale = now - f.time > StaleMs
        val acc = if f.hasAccuracy then f"±${f.accuracy}%.0f m" else "±?"
        val speed = if !stale && f.hasSpeed then f"・${f.speed * 3.6}%.0f km/h" else ""
        fixRow.set(
          SensorUi.spans((acc, false), (s"  ${sourceLabel(f.provider)}$speed", true),
            (if stale then "・古い値" else "", true)),
          Some(now - f.time), stale = stale)
      case None => fixRow.none(if running then "測位待ち" else "")

    // 標高: MSL altitude of the latest fix that has one (network fixes and
    // dead reckoning usually have no altitude).
    LoggerState.recentFixes.findLast(!_.msl.isNaN) match
      case Some(f) =>
        val stale = now - f.time > StaleMs
        val acc = if f.mslAccuracy > 0 then f"  ±${f.mslAccuracy}%.0f m" else ""
        elevationRow.set(SensorUi.spans((f"${f.msl}%.0f m", false), (acc, true), (if stale then "・古い値" else "", true)),
          Some(now - f.time), stale = stale)
      case None =>
        elevationRow.none(
          if android.os.Build.VERSION.SDK_INT < 34 then "Android 14 以上が必要"
          else if !running then ""
          else if LoggerState.lastFix.exists(_.altitude.isNaN) then "直近の測位に高度なし"
          else "測位待ち")

    val satAge = Option(LoggerState.satellitesAt).filter(_ > 0).map(now - _)
    if on then
      val used = sats.filter(_.usedInFix).map(s => (s.constellation, s.svid)).distinct.size
      val seen = sats.map(s => (s.constellation, s.svid)).distinct.size
      val top4 = sats.map(_.cn0).sorted.reverse.take(4)
      val mean = if top4.isEmpty then 0f else top4.sum / top4.size
      usedRow.set(SensorUi.spans((s"$used 機", false), ("  使用", true), (s"・$seen 機", false), ("  捕捉", true)), satAge)
      signalRow.set(
        SensorUi.spans((f"$mean%.0f dB-Hz", false),
          ("  上位4機の平均・" + (if mean >= 35 then "良好" else if mean >= 25 then "やや弱い" else "弱い"), true)),
        satAge)
    else
      val why = if !running then "" else if gnssOn then "探索中" else "停止中"
      usedRow.none(why)
      signalRow.none(why)
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

  def loadHistory(): Unit =
    val now = System.currentTimeMillis()
    val span = if PositioningPanel.day then 24 * 3_600_000L else 3_600_000L
    AshidoriApp.io.execute { () =>
      val d = AshidoriApp.db.gnssStatus(now - span)
      act.runOnUiThread { () =>
        history.update(d, now - span, now)
        val gapMs = 120_000L
        val pairs = d.sliding(2).collect { case Seq(a, b) if b._1.time - a._1.time < gapMs => (a, b._1.time - a._1.time) }.toVector
        val onMs = pairs.map(_._2).sum
        val indoorMs = pairs.collect { case (a, ms) if a._2 == Environment.Indoor => ms }.sum
        historyNote.setText(
          if d.isEmpty then "この期間の受信なし"
          else s"受信 ${fmtDuration(onMs)}・うち屋内 ${fmtDuration(indoorMs)}"
        )
      }
    }

object PositioningPanel:
  /** History range: false = 1時間, true = 24時間. Kept while the app runs. */
  var day = false

  /** The one label of the 5-minute burst (1 Hz satellites + raw data), wherever it starts. */
  val BurstLabel = "5分間 生データを記録"

  private def every(ms: Long): String =
    if ms < 60_000 then s"${(ms / 1000.0).toString.stripSuffix(".0")} 秒間隔" else s"${ms / 60_000} 分間隔"

  /** How positions are obtained right now, as a state (not a sentence). */
  def sourceState(p: SamplingPlan): String =
    val how = p.source match
      case LocationSource.Gnss    => s"衛星・${every(p.intervalMs)}"
      case LocationSource.Network => s"Wi-Fi・基地局・${every(p.intervalMs)}"
      case LocationSource.Passive => "他アプリの測位のみ"
    how + (if p.rawGnss then "・生データ記録中" else "")

  /** Why the state is what it is, first part: the mode actually in effect
    * and why. None without a reason (also right after a switch, before the
    * new plan arrives).
    */
  def modeNote(p: SamplingPlan, chosen: LoggingMode): Option[String] =
    val other = p.modeReasons.map(_.label)
    if other.isEmpty then None
    else if p.effectiveMode != chosen then Some(s"「${p.effectiveMode.label}」で動作中: ${other.mkString("・")}")
    else Some(other.mkString("・"))

  /** A typical mode note, for the height its line keeps when empty. */
  val ModeNoteSample: String = s"「バランス」で動作中: ${PlanReason.Charging.label}"

  private def indoorText(gnss: Boolean): String =
    if gnss then "屋外判定のため衛星で測位中"
    else "屋内のため Wi-Fi・基地局で測位"

  /** When satellites are tried indoors, for the ⓘ on the indoor note. */
  def indoorProbeSchedule: String =
    s"移動開始時・移動中 ${SamplingPolicy.IndoorProbeMovingEveryMs / 60_000} 分間隔・" +
      s"静止中 ${SamplingPolicy.IndoorProbeStillEveryMs / 60_000} 分間隔"

  /** Second part: how positions are checked while indoors. */
  def indoorNote(p: SamplingPlan): Option[String] =
    if p.indoorProbing then Some(indoorText(p.source == LocationSource.Gnss)) else None

  /** Every form the indoor note takes, for the room it keeps. */
  def indoorNoteVariants: Seq[String] = Seq(indoorText(true), indoorText(false))
