package net.sksat.ashidori.ui

import android.view.View
import android.widget.{LinearLayout, TextView}
import com.google.android.material.button.{MaterialButton, MaterialButtonToggleGroup}
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Battery impact estimates shown next to each mode. Rough guidance until
  * measured on real devices.
  */
object ModeInfo:
  val summary: Map[LoggingMode, String] = Map(
    LoggingMode.Eco -> "移動中30秒ごと。静止中は GNSS 停止",
    LoggingMode.Balanced -> "移動中10秒ごと（乗り物5秒）。詳細ログは定期的に",
    LoggingMode.Precise -> "移動中1〜2秒ごと。詳細ログは高頻度",
    LoggingMode.Max -> "常時1秒ごと。詳細ログも常時"
  )
  /** Each mode has its own color and symbol so the choice reads as a mode. */
  val color: Map[LoggingMode, Int] = Map(
    LoggingMode.Eco -> 0xff2e7d32.toInt,
    LoggingMode.Balanced -> 0xff00796b.toInt,
    LoggingMode.Precise -> 0xff1565c0.toInt,
    LoggingMode.Max -> 0xffc62828.toInt
  )
  val symbol: Map[LoggingMode, String] = Map(
    LoggingMode.Eco -> "🍃",
    LoggingMode.Balanced -> "⚖️",
    LoggingMode.Precise -> "🎯",
    LoggingMode.Max -> "🔥"
  )
  val battery: Map[LoggingMode, String] = Map(
    LoggingMode.Eco -> "電池: ごくわずか",
    LoggingMode.Balanced -> "電池: 少なめ（おすすめ）",
    LoggingMode.Precise -> "電池: やや多い",
    LoggingMode.Max -> "電池: 多い（短時間向け）"
  )

/** Recording status and controls, opened from the status chip in the top bar. */
final class LoggerScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "ステータス"
  private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")

  // --- recorder header: REC badge with elapsed time, and the stop button ---
  private val recDot =
    val v = View(act)
    val d = android.graphics.drawable.GradientDrawable()
    d.setShape(android.graphics.drawable.GradientDrawable.OVAL)
    d.setColor(0xffe53935.toInt)
    v.setBackground(d)
    v.setLayoutParams(LinearLayout.LayoutParams(dp(12), dp(12)))
    v
  /** Slow blink while recording, like a recorder's REC lamp. */
  private val blink =
    val a = android.animation.ObjectAnimator.ofFloat(recDot, "alpha", 1f, 0.25f)
    a.setDuration(900)
    a.setRepeatMode(android.animation.ValueAnimator.REVERSE)
    a.setRepeatCount(android.animation.ValueAnimator.INFINITE)
    a
  private val status = text("", 20f, bold = true)
  private val elapsed = caption("")
  private val recBadge =
    val t = vbox(status, elapsed)
    t.setPadding(dp(12), 0, 0, 0)
    val h = hbox(recDot, t)
    h.setLayoutParams(weighted())
    h

  // Current state as label/value rows, kept apart from the explanations below.
  private def row(label: String): (View, TextView) =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(LinearLayout.LayoutParams(dp(72), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    val v = text("", 15f)
    v.setLayoutParams(weighted())
    val h = hbox(l, v)
    h.setPadding(0, dp(3), 0, dp(3))
    (h, v)
  private val (sourceRow, sourceValue) = row("測位")
  private val (motionRow, motionValue) = row("動き")
  private val (envRow, envValue) = row("場所")
  private val (batteryRow, batteryValue) = row("電池")
  private val stateRows = vbox(sourceRow, motionRow, envRow, batteryRow)
  /** Why it is like this: supplementary, so quieter and marked with ⓘ. */
  private val notes = vbox()
  /** Shown only while stopped. Recording is meant to stay on; the user
    * normally just changes the mode.
    */
  private val start =
    val b = button("▶  記録を開始")(act.startLogging())
    b.setLayoutParams(matchWrap)
    b.setMinHeight(dp(56))
    b.setTextSize(16f)
    b
  private val stop =
    val b = button("■  停止") {
      com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
        .setTitle("記録を停止しますか")
        .setMessage("停止している間の移動や滞在は残りません。電池を節約したいだけなら「省電力」モードがおすすめです。")
        .setNegativeButton("やめる", null)
        .setNeutralButton("省電力にする", (_, _) => selectMode(LoggingMode.Eco))
        .setPositiveButton("停止する", (_, _) => {
          Settings.loggingEnabled = false
          LoggerService.send(act, LoggerService.ActionStop)
        })
        .show()
    }
    tint(b, color(attr.error), android.graphics.Color.WHITE)
    b
  private val header = hbox(recBadge, stop)

  // --- mode: one tile per mode, in its own color; the chosen one filled ---
  private val modeTiles: Map[LoggingMode, LinearLayout] = LoggingMode.values.toSeq.map { m =>
    val sym = text(ModeInfo.symbol(m), 22f)
    val label = text(m.label, 13f, bold = true)
    Seq(sym, label).foreach(_.setGravity(android.view.Gravity.CENTER))
    val tile = vbox(sym, label)
    tile.setGravity(android.view.Gravity.CENTER)
    tile.setPadding(dp(4), dp(10), dp(4), dp(10))
    val lp = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    lp.setMargins(dp(3), 0, dp(3), 0)
    tile.setLayoutParams(lp)
    tile.setClickable(true)
    tile.setFocusable(true)
    tile.setContentDescription(s"${m.label}モード")
    tile.setOnClickListener(_ => selectMode(m))
    m -> tile
  }.toMap
  private val modeRow = hbox(LoggingMode.values.toSeq.map(modeTiles)*)
  private val modeInfo = caption("")
  modeInfo.setPadding(0, dp(8), 0, 0)

  private def paintModes(): Unit =
    val chosen = Settings.mode
    for (m, tile) <- modeTiles do
      val c = ModeInfo.color(m)
      val on = m == chosen
      val bg = android.graphics.drawable.GradientDrawable()
      bg.setCornerRadius(dp(14).toFloat)
      bg.setColor(if on then c else (c & 0x00ffffff) | 0x1f000000)
      tile.setBackground(bg)
      tile.setSelected(on)
      tile.getChildAt(1).asInstanceOf[TextView].setTextColor(if on then android.graphics.Color.WHITE else c)
    modeInfo.setText(s"${ModeInfo.summary(chosen)}・${ModeInfo.battery(chosen)}")

  private def selectMode(m: LoggingMode): Unit =
    if Settings.mode != m then
      Settings.mode = m
      if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
    paintModes()
    refresh()

  private val detailText = text("", 14f)
  /** When the automatic detail logs run in the current mode. */
  private val detailSchedule = caption("")
  private val burst = button("5分間 記録", tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val files = vbox()

  private val panel = PositioningPanel(act)
  private val sensorPanel = SensorPanel(act)

  /** Settings live here now that tab pages have no top bar. */
  private val settingsRow =
    val b = outlined("設定")(act.openSettings())
    b.setIconResource(R.drawable.ic_settings)
    b.setStrokeWidth(0)
    val row = hbox(spacer(0), b)
    row.setGravity(android.view.Gravity.END)
    row.setPadding(0, dp(4), dp(8), 0)
    row

  val view: View =
    scroll(
      vbox((Seq(
        settingsRow,
        card(header, spacer(10), panel.accuracyRow, spacer(6), stateRows, notes, spacer(4), start),
        card(text("記録モード", 16f, bold = true), spacer(8), modeRow, modeInfo)
      ) ++ panel.views ++ Seq(sensorPanel.view) ++ Seq(
        card(
          text("詳細ログ", 16f, bold = true),
          caption("衛星の電波と IMU の生データ（解析用）"),
          spacer(6),
          detailText,
          detailSchedule,
          spacer(4),
          burst,
          files
        ),
        spacer(24)
      ))*)
    )

  private var unobserve: () => Unit = () => ()
  override def onShow(): Unit =
    unobserve = LoggerState.observe(() => refresh())
    paintModes()
    refresh()
    panel.loadHistory()
    loadFiles()
  override def onHide(): Unit =
    unobserve()
    blink.cancel()

  private def loadFiles(): Unit =
    val dir = File(act.getExternalFilesDir(null), "gnss")
    val fs = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(-_.lastModified()).take(8)
    files.removeAllViews()
    for f <- fs do
      val t = text(Instant.ofEpochMilli(f.lastModified()).atZone(AshidoriApp.zone).format(timeFmt), 14f)
      t.setLayoutParams(weighted())
      val row = hbox(t, caption(f"${f.length / 1024.0 / 1024.0}%.1f MB "), outlined("共有")(act.shareFile(f)))
      row.setMinimumHeight(dp(48))
      row.setBackgroundResource(android.R.drawable.list_selector_background)
      row.setOnClickListener(_ => showStats(f))
      files.addView(row)

  /** What a raw log contains, summarized from the file itself. */
  private def showStats(f: File): Unit =
    AshidoriApp.io.execute { () =>
      val st = scala.util.Using.resource(scala.io.Source.fromFile(f, "UTF-8"))(src => GnssLogStats.summarize(src.getLines()))
      val consts = st.constellations.toSeq.sortBy(-_._2._1).map { case (c, (n, bands)) =>
        s"$c ${n}機（${bands.toSeq.sorted.mkString("/")}）"
      }
      val body =
        f"""記録 ${st.sessions} 回・計 ${fmtDuration(st.durationMs)}・${st.epochs}%,d エポック・測定値 ${st.measurements}%,d 件
           |平均 C/N0 ${st.meanCn0}%.1f dB-Hz
           |搬送波位相（ADR）有効 ${st.adrValidRatio * 100}%.0f%%
           |測位 ${st.fixes} 回・最良精度 ${if st.bestAccuracyM.isNaN then "-" else f"±${st.bestAccuracyM}%.1fm"}
           |
           |${consts.mkString("\n")}""".stripMargin
      act.runOnUiThread { () =>
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
          .setTitle(f.getName)
          .setMessage(body)
          .setPositiveButton("閉じる", null)
          .setNeutralButton("共有", (_, _) => act.shareFile(f))
          .show()
      }
    }

  def refresh(): Unit =
    val running = LoggerState.running
    status.setText(if running then "記録中" else "停止中")
    status.setTextColor(color(if running then attr.onSurface else attr.onSurfaceVariant))
    val since = Settings.loggingSince
    elapsed.setText(
      if running && since > 0 then s"${fmtDuration(System.currentTimeMillis() - since)}前から・${LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode).label}"
      else if running then LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode).label
      else "")
    recDot.getBackground.asInstanceOf[android.graphics.drawable.GradientDrawable]
      .setColor(if running then 0xffe53935.toInt else color(attr.outlineVariant))
    if running then { if !blink.isStarted then blink.start() }
    else
      blink.cancel()
      recDot.setAlpha(1f)
    start.setVisibility(if running then View.GONE else View.VISIBLE)
    stop.setVisibility(if running then View.VISIBLE else View.GONE)
    notes.removeAllViews()
    def note(n: String): Unit =
      val t = caption(s"ⓘ $n")
      t.setPadding(0, dp(6), 0, 0)
      notes.addView(t)
    LoggerState.plan match
      case Some(p) if running =>
        stateRows.setVisibility(View.VISIBLE)
        sourceValue.setText(PositioningPanel.sourceState(p))
        motionValue.setText(LoggerState.motion.label)
        motionRow.setVisibility(if LoggerState.motion == MotionState.Unknown then View.GONE else View.VISIBLE)
        envValue.setText(LoggerState.environment.label)
        envRow.setVisibility(if LoggerState.environment == Environment.Unknown then View.GONE else View.VISIBLE)
        val d = LoggerState.drainPctPerHour
        batteryValue.setText(s"${LoggerState.battery}%" + (if d.isNaN then "" else f"・$d%.1f%%/時"))
        batteryRow.setVisibility(if LoggerState.battery >= 0 then View.VISIBLE else View.GONE)
        PositioningPanel.notes(p, Settings.mode).foreach(note)
      case _ =>
        stateRows.setVisibility(View.GONE)
        ()

    val now = System.currentTimeMillis()
    val manualLeft = Settings.burstUntil - now
    detailText.setText(LoggerState.detailFile match
      case Some(_) =>
        (if manualLeft > 0 then s"● 記録中・あと${fmtDuration(manualLeft)}" else "● 記録中（自動）") +
          f"・測定値 ${LoggerState.rawMeasurementsSeen}%,d 件"
      case None =>
        val last = Settings.lastDetailStart
        if last == 0 then "待機中" else s"待機中・前回 ${fmtDuration(now - last)}前"
    )
    burst.setEnabled(running && manualLeft <= 0)
    detailSchedule.setText {
      val m = LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode)
      val p = SamplingPolicy.profiles(m)
      def every(ms: Long) = if ms >= 3_600_000 then s"${ms / 3_600_000}時間" else s"${ms / 60_000}分"
      if m == LoggingMode.Max then "自動: 常に"
      else s"自動: 移動中は${every(p.detailEveryMovingMs)}ごと・静止中は${every(p.detailEveryStillMs)}ごとに${fmtDuration(p.detailDurationMs)}"
    }

    panel.refresh()
    sensorPanel.refresh()
