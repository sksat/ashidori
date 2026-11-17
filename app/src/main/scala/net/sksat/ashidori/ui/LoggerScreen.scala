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
    LoggingMode.Eco -> "移動中 30 秒間隔・静止中は測位を停止",
    LoggingMode.Balanced -> "移動中 10 秒間隔（乗り物 5 秒）",
    LoggingMode.Precise -> "移動中 1〜2 秒間隔",
    LoggingMode.Max -> "静止中も 1 秒間隔"
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
    LoggingMode.Balanced -> "電池: 少なめ（推奨）",
    LoggingMode.Precise -> "電池: やや多い",
    LoggingMode.Max -> "電池: 多い（短時間向け）"
  )

/** Recording status and controls, opened from the status chip in the top bar. */
final class LoggerScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "ステータス"
  private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
  private val sinceFmt = DateTimeFormatter.ofPattern("M月d日 H:mm")
  /** 「3時間12分前から」, or from a day on the start itself: 「10月20日 14:05 から」. */
  private def sinceText(since: Long): String =
    val ago = System.currentTimeMillis() - since
    if ago < 24 * 3_600_000L then s"${fmtDuration(ago)}前から"
    else s"${Instant.ofEpochMilli(since).atZone(AshidoriApp.zone).format(sinceFmt)} から"

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
  private val status = oneLine(text("", 20f, bold = true))
  private val elapsed = tabular(oneLine(caption("")))
  private val recBadge =
    val t = vbox(status, elapsed)
    t.setPadding(dp(12), 0, 0, 0)
    t.setLayoutParams(weighted())
    val h = hbox(recDot, t)
    h.setLayoutParams(weighted())
    h

  // Current state as label/value rows, kept apart from the explanations below.
  private def row(label: String): (View, TextView) =
    val l = text(label, 14f, colorAttr = attr.onSurfaceVariant)
    l.setLayoutParams(LinearLayout.LayoutParams(dp(72), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    val v = tabular(oneLine(text("", 15f)))
    v.setLayoutParams(weighted())
    val h = hbox(l, v)
    h.setPadding(0, dp(3), 0, dp(3))
    (h, v)
  private val (sourceRow, sourceValue) = row("測位")
  private val (motionRow, motionValue) = row("動き")
  private val (envRow, envValue) = row("屋内外")
  private val (batteryRow, batteryValue) = row("電池")
  private val stateRows = vbox(sourceRow, motionRow, envRow, batteryRow)
  /** Why it is like this: supplementary, so quieter and marked with ⓘ.
    * The mode note keeps its line even when empty: switching modes adds or
    * removes it ("「高精度」で動作中: 充電中のため精度を1段階向上").
    */
  private val modeNote =
    val t = stableCaption(Seq("ⓘ " + PositioningPanel.ModeNoteSample))
    t.setPadding(0, dp(6), 0, 0)
    t
  /** Indoors, the indoor note's lines stay reserved in every mode (最高精度
    * has none). Tapping ⓘ tells when it checks for being outdoors again.
    */
  private val indoorNote =
    val t = stableCaption(PositioningPanel.indoorNoteVariants.map("ⓘ " + _))
    t.setPadding(0, dp(6), 0, 0)
    t.setOnClickListener(_ =>
      com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
        .setTitle("屋外判定")
        .setMessage(PositioningPanel.indoorProbeSchedule)
        .setPositiveButton("閉じる", null)
        .show())
    t
  /** Shown only while stopped. Recording is meant to stay on; the user
    * normally just changes the mode.
    */
  private val start =
    val b = button("▶  記録を開始")(act.startLogging())
    b.setLayoutParams(matchWrap)
    b.setMinHeight(dp(56))
    b.setTextSize(16f)
    b
  /** The one way to stop recording: from the stop button here, or the
    * notification's 停止 (which opens this screen), always with this
    * confirmation, so a stray tap cannot end it.
    */
  def confirmStop(): Unit =
    // Also right after the start, before the service reports running: the
    // saved wish to record is what stopping changes.
    if LoggerState.running || Settings.loggingEnabled then
      com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
        .setTitle("記録を停止しますか")
        .setMessage("停止中の移動と滞在は記録されません。")
        .setNegativeButton("キャンセル", null)
        .setNeutralButton("省電力にする", (_, _) => selectMode(LoggingMode.Eco))
        .setPositiveButton("停止する", (_, _) => {
          Settings.loggingEnabled = false
          LoggerService.send(act, LoggerService.ActionStop)
        })
        .show()
  private val stop =
    val b = button("■  停止")(confirmStop())
    // A clear recorder red, same as the REC lamp.
    tint(b, 0xffe53935.toInt, android.graphics.Color.WHITE)
    b
  private val header = { recBadge.setLayoutParams(matchWrap); recBadge }
  // Stop sits at the bottom of the card, away from the settings button at the top.
  private val stopRow =
    val r = hbox(stop)
    r.setPadding(0, dp(8), 0, 0)
    r
  /** Start and stop share one slot, so the card keeps its height. */
  private val controls =
    val f = frame(start, stopRow)
    f.setLayoutParams(matchWrap)
    f

  // --- mode: a slider from battery-saving to log-everything ---
  private val modes = LoggingMode.values.toSeq
  /** The chosen mode, large, in its color. */
  private def modeName(m: LoggingMode) = s"${ModeInfo.symbol(m)} ${m.label}"
  /** As tall as the tallest mode's name: the symbols' (emoji) heights differ. */
  private val modeTitle =
    val t = oneLine(StableText(act, () => modes.map(modeName)))
    t.setTextSize(22f)
    t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    t
  private val modeSlider =
    val sl = com.google.android.material.slider.Slider(act)
    sl.setValueFrom(0f)
    sl.setValueTo((modes.size - 1).toFloat)
    sl.setStepSize(1f)
    sl.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE)
    sl.setContentDescription("記録モード")
    sl.addOnChangeListener((_, v, fromUser) => if fromUser then selectMode(modes(v.round)))
    sl.setLayoutParams(matchWrap)
    sl
  /** Stop names under the slider's ticks; tapping one selects it. Each sits
    * in a box of one fixed width (the chosen one turns bold, which is wider),
    * the end ones aligned to the track's ends.
    */
  private val stopLabels = modes.zipWithIndex.map { (m, i) =>
    val t = oneLine(text(m.label, 12f))
    t.setPadding(dp(4), dp(6), dp(4), dp(6))
    t.setGravity(
      if i == 0 then android.view.Gravity.START
      else if i == modes.size - 1 then android.view.Gravity.END
      else android.view.Gravity.CENTER_HORIZONTAL)
    t.setOnClickListener(_ => selectMode(m))
    t
  }
  private val labelWidth =
    val bold = android.graphics.Paint(stopLabels.head.getPaint)
    bold.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
    modes.map(m => bold.measureText(m.label)).max.ceil.toInt + dp(8) + dp(4)
  private val labelFrame =
    val f = android.widget.FrameLayout(act)
    stopLabels.foreach(t => f.addView(t, android.widget.FrameLayout.LayoutParams(
      labelWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)))
    f.setLayoutParams(matchWrap)
    f
  // Put each name under its tick once the slider knows its track width.
  modeSlider.addOnLayoutChangeListener((_, l, _, r, _, _, _, _, _) =>
    val pad = modeSlider.getTrackSidePadding
    val track = (r - l) - 2 * pad
    for (t, i) <- stopLabels.zipWithIndex do
      t.post { () =>
        val cx = pad + track * i / (modes.size - 1).toFloat
        t.setTranslationX(math.max(0f, math.min((r - l - labelWidth).toFloat, cx - labelWidth / 2f)))
      }
  )
  private val modeRow = vbox(modeTitle, modeSlider, labelFrame)
  private def modeText(m: LoggingMode) = s"${ModeInfo.summary(m)}・${ModeInfo.battery(m)}"
  /** As tall as the longest mode's description, so the cards below stay put. */
  private val modeInfo = stableCaption(modes.map(modeText))
  modeInfo.setPadding(0, dp(4), 0, 0)

  private def paintModes(): Unit =
    val chosen = Settings.mode
    val c = ModeInfo.color(chosen)
    val tint = android.content.res.ColorStateList.valueOf(c)
    modeSlider.setValue(modes.indexOf(chosen).toFloat)
    modeSlider.setTrackActiveTintList(tint)
    modeSlider.setThumbTintList(tint)
    modeSlider.setHaloTintList(android.content.res.ColorStateList.valueOf((c & 0x00ffffff) | 0x33000000))
    modeSlider.setTickActiveTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE))
    modeSlider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(color(attr.surfaceContainerHigh)))
    modeTitle.setText(modeName(chosen))
    modeTitle.setTextColor(c)
    for (t, m) <- stopLabels.zip(modes) do
      t.setTextColor(if m == chosen then c else color(attr.onSurfaceVariant))
      t.setTypeface(null, if m == chosen then android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    modeInfo.setText(modeText(chosen))

  private def selectMode(m: LoggingMode): Unit =
    if Settings.mode != m then
      Settings.mode = m
      if LoggerState.running then LoggerService.send(act, LoggerService.ActionRefresh)
    paintModes()
    refresh()

  private val detailText = tabular(oneLine(text("", 14f)))
  private def schedule(m: LoggingMode): String =
    val p = SamplingPolicy.profiles(m)
    def every(ms: Long) = if ms >= 3_600_000 then s"${ms / 3_600_000}時間" else s"${ms / 60_000}分"
    if m == LoggingMode.Max then "自動: 常時"
    else s"自動: 移動中 ${every(p.detailEveryMovingMs)}間隔・静止中 ${every(p.detailEveryStillMs)}間隔で各 ${fmtDuration(p.detailDurationMs)}"
  /** When the automatic detail logs run in the current mode; as tall as the
    * longest mode's schedule.
    */
  private val detailSchedule = tabular(stableCaption(modes.map(schedule)))
  private val burst = button(PositioningPanel.BurstLabel, tonal = true) {
    act.ensurePermissions { () =>
      Settings.burstUntil = System.currentTimeMillis() + LoggerService.BurstMs
      LoggerService.send(act, LoggerService.ActionBurst)
    }
  }
  private val files = vbox()

  private val panel = PositioningPanel(act)
  /** The user's other devices; first, since it is about all of them. */
  private val peers = PeerPanel(act)
  private val sensorPanel = SensorPanel(act)
  /** Abnormal exits or unsaved data in the last day. */
  private val banner = AlertBanner(act)

  val view: View =
    scroll(
      vbox((Seq(
        banner.view,
        peers.view,
        card(header, spacer(10), panel.accuracyRow, spacer(6), stateRows, modeNote, indoorNote, spacer(4), controls),
        card(text("記録モード", 16f, bold = true), spacer(8), modeRow, modeInfo)
      ) ++ panel.views ++ Seq(sensorPanel.view) ++ Seq(
        card(
          { val t = text("生データ記録", 16f, bold = true); t.setLayoutParams(weighted())
            hbox(t, chip("開発者向け", color(attr.surfaceContainerHigh), color(attr.onSurfaceVariant))) },
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
    // onShow comes again on resume without an onHide: observe only once.
    unobserve()
    banner.refresh()
    unobserve = LoggerState.observe(() => refresh())
    peers.onShow()
    paintModes()
    refresh()
    panel.onShow()
    sensorPanel.onShow()
    loadFiles()
  override def onHide(): Unit =
    unobserve()
    peers.onHide()
    panel.onHide()
    sensorPanel.onHide()
    blink.cancel()

  private def loadFiles(): Unit =
    val dir = File(act.getExternalFilesDir(null), "gnss")
    val fs = Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(-_.lastModified()).take(8)
    files.removeAllViews()
    for (f, i) <- fs.zipWithIndex do
      // A list row: tap for the summary, the share button shares the file.
      val row = navRow(Instant.ofEpochMilli(f.lastModified()).atZone(AshidoriApp.zone).format(timeFmt), trailingIcon = 0)(showStats(f))
      row.setValue(f"${f.length / 1024.0 / 1024.0}%.1f MB")
      tabular(row.value).setTextColor(color(attr.onSurfaceVariant))
      row.trailing(iconButton(net.sksat.ashidori.R.drawable.ic_share, "共有")(act.shareFile(f)))
      row.setPaddingRelative(dp(16), 0, dp(4), 0)
      // Full card width, so the ripple reaches the card's edges.
      val lp = matchWrap
      lp.setMarginStart(-dp(16))
      lp.setMarginEnd(-dp(16))
      if i > 0 then files.addView(divider(0))
      files.addView(row, lp)

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
    status.setText(if running then "記録中" else "記録停止中")
    status.setTextColor(color(if running then attr.onSurface else attr.onSurfaceVariant))
    val since = Settings.loggingSince
    elapsed.setText(
      if running && since > 0 then s"${sinceText(since)}・${LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode).label}"
      else if running then LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode).label
      else "")
    recDot.getBackground.asInstanceOf[android.graphics.drawable.GradientDrawable]
      .setColor(if running then 0xffe53935.toInt else color(attr.outlineVariant))
    if running then { if !blink.isStarted then blink.start() }
    else
      blink.cancel()
      recDot.setAlpha(1f)
    // Both stay laid out (INVISIBLE, not GONE): the slot keeps its height.
    start.setVisibility(if running then View.INVISIBLE else View.VISIBLE)
    stopRow.setVisibility(if running then View.VISIBLE else View.INVISIBLE)
    // The same rows in every state; "—" where there is nothing to show.
    val plan = LoggerState.plan.filter(_ => running)
    sourceValue.setText(plan.fold("—")(PositioningPanel.sourceState))
    motionValue.setText(
      if plan.isEmpty || LoggerState.motion == MotionState.Unknown then "—" else LoggerState.motion.label)
    envValue.setText(
      if plan.isEmpty || LoggerState.environment == Environment.Unknown then "—" else LoggerState.environment.label)
    batteryValue.setText(
      if plan.isEmpty || LoggerState.battery < 0 then "—"
      else
        val d = LoggerState.drainPctPerHour
        s"${LoggerState.battery}%" + (if d.isNaN then "" else f"・$d%.1f%%/時"))
    modeNote.setText(plan.flatMap(PositioningPanel.modeNote(_, Settings.mode)).fold("")("ⓘ " + _))
    // Indoors the note keeps its room in every mode (最高精度 has none);
    // elsewhere it does not appear.
    indoorNote.setText(plan.flatMap(PositioningPanel.indoorNote).fold("")("ⓘ " + _))
    // Its room stays while empty, but only a note with text is a target.
    val hasNote = indoorNote.getText.length > 0
    indoorNote.setClickable(hasNote)
    indoorNote.setImportantForAccessibility(
      if hasNote then View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    indoorNote.setContentDescription(if hasNote then s"${indoorNote.getText.toString.stripPrefix("ⓘ ")}。タップで屋外判定の予定" else null)
    indoorNote.setVisibility(
      if plan.isDefined && LoggerState.environment == Environment.Indoor || indoorNote.getText.length > 0 then View.VISIBLE
      else View.GONE)

    val now = System.currentTimeMillis()
    val manualLeft = Settings.burstUntil - now
    detailText.setText(LoggerState.detailFile match
      case Some(_) =>
        (if manualLeft > 0 then s"● 記録中・残り${fmtDuration(manualLeft)}" else "● 記録中（自動）") +
          f"・測定値 ${LoggerState.rawMeasurementsSeen}%,d 件"
      case None =>
        val last = Settings.lastDetailStart
        if last == 0 then "待機中" else s"待機中・前回 ${fmtDuration(now - last)}前"
    )
    burst.setEnabled(running && manualLeft <= 0)
    detailSchedule.setText(schedule(LoggerState.plan.map(_.effectiveMode).getOrElse(Settings.mode)))

    panel.refresh()
    sensorPanel.refresh()
