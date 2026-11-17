package net.sksat.ashidori.ui

import android.Manifest
import android.content.{ClipData, ClipboardManager}
import android.content.pm.PackageManager
import android.hardware.{Sensor, SensorManager}
import android.location.*
import android.os.{Build, Handler, Looper, PowerManager}
import android.view.View
import android.widget.{TextView, Toast}
import java.time.LocalDate
import java.util.concurrent.Executor
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try
import net.sksat.ashidori.*
import ashidori.core.*
import ashidori.core.sim.*
import Ui.*

/** Device capability report + self tests, meant to be copied and pasted back
  * to the developer during field testing.
  */
final class DiagnosticsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "端末診断"
  private lazy val lm = act.getSystemService(classOf[LocationManager])
  private lazy val sm = act.getSystemService(classOf[SensorManager])
  private val sections = mutable.LinkedHashMap.empty[String, String]
  private val body = mono("")
  private val probeButton = button("GNSS 60秒テスト（屋外で）", tonal = true)(runProbe())
  /** Recording in the background: what allows it, gaps, and the lifecycle log. */
  private val recording = vbox()
  /** The same as plain text, for the copied report. */
  private var recordingText = ""

  private val debuggable =
    (act.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  // --- saving, backup and archive maintenance ---------------------------------------

  private val savedRow = row("最後に保存された測位", "", "…")()
  private val crashRow = row("異常終了（直近24時間）", "", "…")()
  private val pendingRow = row("保存待ち", "", "…")()
  private val kindRow = row("種類ごとの最後の保存", "", "")()
  private val archiveRow = row("記録の整理", "", "…")()
  private val syncRow = row("自動同期", "", "…")(Some(() => resumeSync()))
  /** One line for the backup and maintenance below: progress, result or error. */
  private val dataStatus = caption("")
  dataStatus.setSingleLine(true)
  dataStatus.setEllipsize(android.text.TextUtils.TruncateAt.END)
  @volatile private var backupRunning = false
  private var backupLine: Option[String] = None
  private val backupButton = button("バックアップを書き出す", tonal = true)(startBackup(Backup.Mode.Normal))
  private val maintainButton = outlined("記録を再整理")(runMaintenance())
  // An archive commit that could not be finished, and files a repair
  // quarantined: shown only then.
  private val repairTitle = text("記録の整理（要確認）", 16f, bold = true, colorAttr = attr.error)
  private val repairText = text("", 14f)
  private val rescueButton = button("救出用バックアップ", tonal = true)(startBackup(Backup.Mode.Rescue))
  private val exportQuarantineButton = outlined("隔離したファイルを書き出す")(startBackup(Backup.Mode.Quarantine))
  private val repairButton = outlined("整理を修復")(confirmRepair())
  private val seenButton = outlined("確認済みにする")(acknowledge())
  private val repairCard = card(
    repairTitle,
    repairText,
    spacer(8),
    rescueButton,
    exportQuarantineButton,
    repairButton,
    seenButton
  )
  repairCard.setVisibility(View.GONE)
  private val ticker = Handler(Looper.getMainLooper)
  private val tick: Runnable = () => { showDataStatus(); ticker.postDelayed(tick, 1000) }

  val view: View =
    scroll(
      vbox(
        sectionHeader("記録の保存"),
        listGroup(savedRow, crashRow, pendingRow, kindRow, archiveRow, syncRow),
        repairCard,
        card(
          text("記録データ", 16f, bold = true),
          caption("地図と施設データは含みません。"),
          spacer(8),
          backupButton,
          maintainButton,
          dataStatus
        ),
        card(
          (Seq(
            text("端末診断", 18f, bold = true),
            spacer(8),
            button("レポートをコピー")(copy()),
            probeButton
          ) ++ (
            // Simulated data is a development aid; keep it out of normal builds.
            if debuggable then
              Seq(
                outlined("シミュレーションの1日分を昨日に投入")(insertSim()),
                outlined("シミュレーションデータを削除")(deleteSim())
              )
            else Nil
          ))*
        ),
        recording,
        card(body),
        spacer(24)
      )
    )

  override def onShow(): Unit =
    showSaving()
    ticker.removeCallbacks(tick)
    tick.run()
    showRecording()
    sections.clear()
    sections("device") = device()
    sections("gnss") = gnss()
    sections("sensors") = sensors()
    sections("permissions") = permissions()
    sections("scala") = scalaSelfTest()
    sections("db") = AshidoriApp.db.counts().toSeq.sorted.map((k, v) => s"$k=$v").mkString(" ")
    render()
    AshidoriApp.io.execute(() =>
      val r = simE2E()
      act.runOnUiThread(() =>
        // Runs the timeline algorithm on synthetic data to check that the
        // shrunk Scala code behaves on this device's runtime as on the JVM.
        sections("アルゴリズム自己テスト（合成データ、実際の記録とは無関係）") = r
        render()
      )
    )

  override def onHide(): Unit = ticker.removeCallbacks(tick)

  private def showSaving(): Unit =
    AshidoriApp.io.execute { () =>
      val now = System.currentTimeMillis()
      val fix = Try(AshidoriApp.db.lastFix()).toOption.flatten.map(_.time)
      val crashes = Alerts.recentCrashes(now)
      val skipped = Try(Maintenance.Guard.skipped()).getOrElse(Nil)
      val safe = Maintenance.SafeMode.maintenanceStopped
      val syncStopped = Maintenance.SafeMode.syncStopped
      val lost = LoggerWrites.Losses.total + LoggerWrites.Losses.uncounted
      val pendingCommit = AshidoriApp.archive.commitPending
      val stuck = CommitStatus.stuck()
      val attention = Attention.all()
      val quarantined = Attention.quarantineBytes()
      act.runOnUiThread { () =>
        savedRow.setValue(fix.map(t => s"${stamp(t)}（${fmtDuration(now - t)}前）").getOrElse("なし"))
        val stale = Settings.loggingEnabled && fix.forall(now - _ > 3 * 3_600_000L)
        savedRow.value.setTextColor(color(if stale then attr.error else attr.onSurfaceVariant))
        crashRow.setValue(s"$crashes 回")
        crashRow.value.setTextColor(color(if crashes > 0 then attr.error else attr.onSurfaceVariant))
        val pending = LoggerWrites.pending
        pendingRow.setValue(s"$pending 行")
        val commit = LoggerWrites.lastCommitAt
        pendingRow.setSubtitle(
          (if commit == 0 then "このプロセスではまだ書き込みなし" else s"最後の書き込み ${full(commit).drop(5)}") +
            (if lost > 0 then s"・保存できなかった行 累計 $lost" else ""))
        pendingRow.subtitle.setTextColor(color(if lost > 0 then attr.error else attr.onSurfaceVariant))
        val kinds = LoggerWrites.progress(Seq(WriteKind.Fixes, WriteKind.Motion, WriteKind.RawLines))
        kindRow.setSubtitle(kinds.map { (k, saved, unsaved) =>
          s"$k " + unsaved.map(u => s"${fmtDuration(u)}前から未保存").orElse(saved.map(a => s"${fmtDuration(a)}前")).getOrElse("—")
        }.mkString("・"))
        kindRow.subtitle.setTextColor(color(if kinds.exists(_._3.exists(_ > WriteStall.OldestMs)) then attr.error else attr.onSurfaceVariant))
        syncRow.setSubtitle(if syncStopped then "異常終了が続いたため停止中（タップで再開）" else "")
        syncRow.setValue(if syncStopped then "停止中" else "動作中")
        syncRow.value.setTextColor(color(if syncStopped then attr.error else attr.onSurfaceVariant))
        archiveRow.setSubtitle(
          if stuck.isDefined then "途中で中断した整理を自動では完了できません（記録は端末内に保持）"
          else if pendingCommit then "途中で中断した整理があります（自動で完了します）"
          else if safe then "異常終了が続いたため停止中（「記録を再整理」で再開）"
          else if skipped.nonEmpty then s"保留中: ${skipped.map(_.toString.drop(5)).mkString("、")}（データは端末内に保持）"
          else "充電中、または起動の30分後に自動で行います")
        archiveRow.setValue(
          if stuck.isDefined then "停止（要確認）" else if pendingCommit then "未完了" else if safe then "停止中"
          else if skipped.nonEmpty then "保留あり" else "自動")
        archiveRow.value.setTextColor(color(if stuck.isDefined || safe || skipped.nonEmpty then attr.error else attr.onSurfaceVariant))
        showRepair(pendingCommit, stuck, attention, quarantined)
        savingText = s"saving=lastFix:${fix.map(full).getOrElse("none")} crashes24h=$crashes pending=$pending " +
          s"safeMode=$safe syncStopped=$syncStopped lost=$lost skipped=${skipped.mkString(",")} " +
          s"commitPending=$pendingCommit stuck=${stuck.map(_.mkString("[", "; ", "]")).getOrElse("no")} " +
          s"quarantined=${attention.map(e => s"${e.path}->${e.quarantined}${if e.onlyCopy then "(only copy)" else ""}").mkString(",")}"
      }
    }

  private var savingText = ""

  /** The card of an unfinished commit and of quarantined files. */
  private def showRepair(pendingCommit: Boolean, stuck: Option[Seq[String]], attention: Seq[Attention.Entry], quarantined: Long): Unit =
    val show = pendingCommit || attention.nonEmpty || quarantined > 0
    repairCard.setVisibility(if show then View.VISIBLE else View.GONE)
    // Files that may hold rows found nowhere else are listed; the tmp files
    // of interrupted commits whose previous version is intact are counted.
    val (listed, quiet) = attention.partition(e => e.onlyCopy || !e.seen)
    val lines =
      stuck.map(ls => "記録は端末内に保持し、記録を続けています。" +: ls)
        .orElse(Option.when(pendingCommit)(Seq("途中で中断した整理があります。自動で完了します。"))).toSeq.flatten ++
      Option.when(!pendingCommit && attention.nonEmpty)(
        "記録の整理は再開しました。隔離したファイルの行は復元しません。タイムライン・書き出し・同期には含みません。").toSeq ++
      Option.when(listed.nonEmpty)("隔離したファイル（削除しません）:").toSeq ++
      listed.map(e => s"${Attention.label(e)}: ${e.why}" + (if e.onlyCopy then "。以前の記録はこのファイルにのみ残る可能性があります" else "")) ++
      Option.when(quiet.nonEmpty)(s"整理途中の一時ファイル ${quiet.size} 件（以前の版が残っているもの）").toSeq ++
      Option.when(quarantined > 0)(f"隔離したファイル 合計 ${quarantined / 1e6}%.1f MB（自動では削除しません）").toSeq ++
      Option.when(attention.nonEmpty)("取り出すには「隔離したファイルを書き出す」か救出用バックアップを使います。").toSeq
    repairText.setText(lines.mkString("\n"))
    repairTitle.setText(if stuck.isDefined then "記録の整理が停止（要確認）" else "記録の整理（要確認）")
    repairButton.setVisibility(if pendingCommit then View.VISIBLE else View.GONE)
    seenButton.setVisibility(if attention.exists(!_.seen) then View.VISIBLE else View.GONE)
    exportQuarantineButton.setVisibility(if attention.nonEmpty then View.VISIBLE else View.GONE)

  private def showDataStatus(): Unit =
    val line = backupLine.orElse {
      Maintenance.Progress.get().map { (t, running, l) =>
        if running then s"整理中: $l" else s"記録の整理: $l（${stamp(t)}）"
      }
    }
    dataStatus.setText(line.getOrElse(" "))
    val maintaining = Maintenance.Progress.get().exists(_._2)
    backupButton.setEnabled(!backupRunning)
    rescueButton.setEnabled(!backupRunning)
    exportQuarantineButton.setEnabled(!backupRunning)
    maintainButton.setEnabled(!maintaining)
    repairButton.setEnabled(!maintaining)
    // A repair or maintenance run just ended: the card follows.
    if maintaining != wasMaintaining then
      wasMaintaining = maintaining
      if !maintaining then showSaving()

  private def mb(b: Long) = f"${b / 1e6}%.0f MB"

  private var wasMaintaining = false

  private def confirmRepair(): Unit =
    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
      .setTitle("整理を修復")
      .setMessage("途中で中断した整理を表ごとに解決します。置き換え済みの表は、端末内に重複して残る行がすべてファイルにあることを確かめてから、その行を削除します。" +
        "以前から保存されていた行は、元のファイルが残っていないため行数だけを確かめます（残る中で最もよい確認）。" +
        "検証できないファイルは隔離し、削除しません。先に救出用バックアップを書き出すことを勧めます。")
      .setPositiveButton("修復する", (_, _) => {
        backupLine = None
        Maintenance.repairNow(act)
        showDataStatus()
      })
      .setNegativeButton("キャンセル", null)
      .show()

  private def acknowledge(): Unit =
    AshidoriApp.io.execute { () =>
      Try(Attention.acknowledge()).failed.foreach(e => Log.w("acknowledging quarantined files failed", e))
      act.runOnUiThread(() => showSaving())
    }

  private def startBackup(mode: Backup.Mode): Unit =
    val (kind, label) = mode match
      case Backup.Mode.Normal     => ("backup", "")
      case Backup.Mode.Rescue     => ("rescue", "救出用バックアップを")
      case Backup.Mode.Quarantine => ("quarantine", "隔離したファイルを")
    act.createZip(Backup.defaultName(kind = kind)) { uri =>
      backupRunning = true
      backupLine = Some("書き出しの準備中")
      showDataStatus()
      Thread(() =>
        val result =
          try
            val report = (done: Long, total: Long) =>
              act.runOnUiThread { () =>
                backupLine = Some(s"書き出し中… ${mb(done)} / ${mb(total)}")
                showDataStatus()
              }
            val n = mode match
              case Backup.Mode.Normal     => Backup.write(act, uri, report)
              case Backup.Mode.Rescue     => Backup.writeRescue(act, uri, report)
              case Backup.Mode.Quarantine => Backup.writeQuarantine(act, uri, report)
            s"${label}書き出しました（${mb(n)}）"
          catch
            case _: ArchiveStore.Pending =>
              Try(android.provider.DocumentsContract.deleteDocument(act.getContentResolver, uri))
              "整理待ちのため書き出せません（救出用バックアップを使用）"
            case e: Throwable =>
              Log.w("backup failed", e)
              Try(android.provider.DocumentsContract.deleteDocument(act.getContentResolver, uri))
              s"書き出せませんでした: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
        LifeLog.add(LifeKind.Note, s"バックアップ: $result")
        act.runOnUiThread { () =>
          backupRunning = false
          backupLine = Some(result)
          showDataStatus()
        }
      , "ashidori-backup").start()
    }

  private def resumeSync(): Unit =
    if Maintenance.SafeMode.syncStopped then
      Maintenance.SafeMode.resumeSync(act)
      Toast.makeText(act, "自動同期を再開しました", Toast.LENGTH_SHORT).show()
      showSaving()

  private def runMaintenance(): Unit =
    backupLine = None
    Maintenance.runNow(act)
    showDataStatus()

  private def render(): Unit =
    body.setText(sections.map((k, v) => s"## $k\n$v").mkString("\n\n"))

  private def copy(): Unit =
    val cm = act.getSystemService(classOf[ClipboardManager])
    cm.setPrimaryClip(ClipData.newPlainText("ashidori diagnostics",
      s"ashidori ${BuildInfo.versionName}\n" + savingText + "\n" + recordingText + "\n\n" + body.getText))
    Toast.makeText(act, "コピーしました", Toast.LENGTH_SHORT).show()

  // --- recording in the background ----------------------------------------------------

  private val stampFmt = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
  private val fullFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private def stamp(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(stampFmt)
  private def full(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(fullFmt)

  /** Rows shown; the copied report has every entry. */
  private val EventRows = 60
  private val GapMinMs = 30 * 60_000L
  private val GapDays = 7

  private def row(title: String, sub: CharSequence = "", value: CharSequence = "", bad: Boolean = false)(onTap: Option[() => Unit] = None): ListRow =
    val r = ListRow(act)
    r.title.setText(title)
    r.setSubtitle(sub)
    r.setValue(value)
    if bad then (if value.length > 0 then r.value else r.title).setTextColor(color(attr.error))
    onTap.foreach { f =>
      r.onTap(f())
      r.trailing(icon(net.sksat.ashidori.R.drawable.ic_chevron_right))
    }
    r

  private def openAppSettings(): Unit =
    act.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      android.net.Uri.parse(s"package:${act.getPackageName}")))

  private def showRecording(): Unit =
    val h = LifeLog.health(act)
    // Pixel's 「アプリの電池使用量」: 制限なし = exempt from battery
    // optimization, 制限 = background restricted, otherwise 最適化.
    val battery =
      if h.backgroundRestricted then "制限" else if h.batteryExempt then "制限なし" else "最適化"
    val location =
      if !h.fineLocation then "許可なし" else if h.backgroundLocation then "常に許可" else "使用中のみ"
    val running = LoggerState.running
    recording.removeAllViews()
    recording.addView(sectionHeader("バックグラウンドでの記録"))
    recording.addView(listGroup(
      row("記録サービス", if Settings.loggingEnabled then "" else "記録はオフ",
        if running then "動作中" else "停止中", bad = Settings.loggingEnabled && !running)(),
      row("位置情報の許可", "自動での再開に「常に許可」が必要",
        location, bad = location != "常に許可")(Some(() => openAppSettings())),
      row("アプリの電池使用量", "自動での再開に「制限なし」が必要",
        battery, bad = battery != "制限なし")(Some(() =>
          if battery == "最適化" then
            act.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
              android.net.Uri.parse(s"package:${act.getPackageName}")))
          else openAppSettings())),
      row("端末の位置情報", "", if h.locationOn then "オン" else "オフ", bad = !h.locationOn)(),
      row("アプリスタンバイ", "定期確認の頻度を OS が決める区分", PlatformCodes.standbyBucket(h.standbyBucket))()
    ))
    val gapsBox = vbox()
    val eventsBox = vbox()
    recording.addView(sectionHeader(s"記録の途切れ（直近${GapDays}日・${GapMinMs / 60_000}分以上）"))
    recording.addView(gapsBox)
    recording.addView(supportingText("精度 100 m 以内の測位がない時間。原因は下の動作ログから推定する。静止中の途切れは正常。"))
    recording.addView(sectionHeader("動作ログ"))
    recording.addView(eventsBox)
    recording.addView(supportingText(s"新しい順に${EventRows}件。「レポートをコピー」にはすべて（最大${LifeLog.Keep * 2}件）を含める。位置は記録しない。"))
    recordingText = s"## recording\nservice=${if running then "running" else "stopped"} enabled=${Settings.loggingEnabled} ${h.summary}"
    gapsBox.addView(listGroup(row("読み込み中…")()))
    AshidoriApp.io.execute { () =>
      LifeLog.recordExitReasons()
      val events = LifeLog.read()
      val now = System.currentTimeMillis()
      val from = now - GapDays * 86_400_000L
      val times = scala.util.Try(AshidoriApp.db.fixesSampled(from, now, 60_000L).map(_.time)).getOrElse(Vector.empty) ++
        LoggerState.recentFixes.filter(f => !f.hasAccuracy || f.accuracy < 100).map(_.time)
      // Before the first event there is nothing to explain a gap with.
      val start = math.max(from, events.headOption.map(_.time).getOrElse(now))
      val gaps = RecordingGaps.explain(RecordingGaps.find(times, start, now, GapMinMs), events).reverse
      act.runOnUiThread { () =>
        gapsBox.removeAllViews()
        gapsBox.addView(listGroup(
          (if gaps.isEmpty then Seq(row("なし")())
           else gaps.take(30).map { g =>
             val bad = g.cause == GapCause.ServiceDown || g.cause == GapCause.Unknown
             row(s"${stamp(g.start)} – ${stamp(g.end)}（${fmtDuration(g.durationMs)}）",
               g.cause.label + (if g.events.nonEmpty then s"・その間の記録 ${g.events.size}件" else ""))()
               .tap(r => if bad then r.subtitle.setTextColor(color(attr.error)))
           })*))
        eventsBox.removeAllViews()
        eventsBox.addView(listGroup(
          (if events.isEmpty then Seq(row("なし")())
           else events.reverse.take(EventRows).map { e =>
             // The time stays quiet; a problem shows in the title.
             val r = row(e.label, e.detail, stamp(e.time))()
             r.value.setTextColor(color(attr.onSurfaceVariant))
             if e.problem then r.title.setTextColor(color(attr.error))
             r
           })*))
        recordingText = recordingText +
          "\n\n## gaps\n" + (if gaps.isEmpty then "none" else gaps.map(g =>
            s"${full(g.start)} - ${full(g.end)} (${fmtDuration(g.durationMs)}) ${g.cause} events=${g.events.size}").mkString("\n")) +
          "\n\n## lifecycle\n" + events.map(e => s"${full(e.time)} ${e.kind.key} ${e.detail}").mkString("\n")
      }
    }

  extension (r: ListRow) private def tap(f: ListRow => Unit): ListRow = { f(r); r }

  // --- sections -----------------------------------------------------------------

  private def device(): String =
    s"""model=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
       |android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} patch=${Build.VERSION.SECURITY_PATCH}
       |abi=${Build.SUPPORTED_ABIS.mkString(",")}""".stripMargin

  private def gnss(): String =
    val c = lm.getGnssCapabilities
    val lines = mutable.ArrayBuffer(
      s"hw=${lm.getGnssHardwareModelName} year=${lm.getGnssYearOfHardware}",
      s"providers=${lm.getAllProviders.asScala.mkString(",")} fused=${lm.hasProvider(LocationManager.FUSED_PROVIDER)}",
      s"measurements=${c.hasMeasurements} navMessages=${c.hasNavigationMessages} antennaInfo=${c.hasAntennaInfo}",
      s"measurementCorrections=${c.hasMeasurementCorrections} satellitePvt=${c.hasSatellitePvt} lowPowerMode=${c.hasLowPowerMode}"
    )
    // API 34+ only. NoSuchMethodError is fatal for scala.util.Try, so check the SDK level.
    if Build.VERSION.SDK_INT >= 34 then
      lines += s"correlationVectors=${c.hasMeasurementCorrelationVectors} adr=${c.hasAccumulatedDeltaRange}"
    val types = if Build.VERSION.SDK_INT >= 34 then c.getGnssSignalTypes.asScala.toSeq else Nil
    lines += "signalTypes=" + types
      .map(t => s"${Constellations.name(t.getConstellationType)}:${bandOf(t.getCarrierFrequencyHz)}${t.getCodeType}")
      .distinct
      .mkString(" ")
    lines.mkString("\n")

  private def bandOf(hz: Double): String =
    if hz > 1.55e9 then "L1" else if hz > 1.2e9 then "L2/E6" else "L5"

  private def sensors(): String =
    val interesting = Seq(
      Sensor.TYPE_ACCELEROMETER -> "accel",
      Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accelUncal",
      Sensor.TYPE_GYROSCOPE -> "gyro",
      Sensor.TYPE_MAGNETIC_FIELD -> "mag",
      Sensor.TYPE_PRESSURE -> "baro",
      Sensor.TYPE_STEP_COUNTER -> "stepCounter",
      Sensor.TYPE_STEP_DETECTOR -> "stepDetector",
      Sensor.TYPE_SIGNIFICANT_MOTION -> "sigMotion",
      Sensor.TYPE_STATIONARY_DETECT -> "stationary",
      Sensor.TYPE_MOTION_DETECT -> "motionDetect"
    )
    interesting.map { (t, n) =>
      val nonWake = Option(sm.getDefaultSensor(t, false))
      val wake = Option(sm.getDefaultSensor(t, true))
      def d(s: Sensor) =
        s"${s.getName} fifo=${s.getFifoReservedEventCount}/${s.getFifoMaxEventCount} minDelay=${s.getMinDelay}us power=${s.getPower}mA"
      s"$n: " + (wake.map(s => "wake[" + d(s) + "]") ++ nonWake.map(s => "nonwake[" + d(s) + "]")).mkString(" ")
        .ifEmpty("none")
    }.mkString("\n")

  extension (s: String) private def ifEmpty(alt: String): String = if s.endsWith(": ") || s.isEmpty then s + alt else s

  private def permissions(): String =
    val perms = Seq(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_BACKGROUND_LOCATION,
      Manifest.permission.ACTIVITY_RECOGNITION,
      Manifest.permission.POST_NOTIFICATIONS
    )
    val pm = act.getSystemService(classOf[PowerManager])
    (perms.map(p => s"${p.split('.').last}=${act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED}") :+
      s"ignoreBatteryOptimizations=${pm.isIgnoringBatteryOptimizations(act.getPackageName)}" :+
      s"powerSave=${pm.isPowerSaveMode}").mkString("\n")

  /** Exercises Scala 3 features whose bytecode is unusual for ART/D8/R8. */
  private def scalaSelfTest(): String =
    object Lazy:
      lazy val value: Int = (1 to 10).sum
    enum Color:
      case Red, Green
    final case class P(a: Int, b: String)
    val checks: Seq[(String, () => Boolean)] = Seq(
      "lazy val" -> (() => Lazy.value == 55),
      "enum values" -> (() => Color.values.length == 2 && Color.valueOf("Green") == Color.Green),
      "lambdas/collections" -> (() => Vector(3, 1, 2).sortBy(-_).map(_ * 2) == Vector(6, 4, 2)),
      "pattern match" -> (() => (P(1, "x") match { case P(n, s) => n == 1 && s == "x" })),
      "f-interpolator" -> (() => f"${math.Pi}%.2f" == "3.14"),
      "Try/Either" -> (() => Try(1 / 0).isFailure && Right(1).map(_ + 1) == Right(2)),
      "core enum" -> (() => TransportMode.fromKey("IN_TRAIN") == TransportMode.Train),
      "ujson" -> (() => ujson.read("""{"a":[1,2]}""")("a").arr.size == 2),
      "java.time" -> (() => LocalDate.of(2026, 1, 1).plusDays(1).getDayOfMonth == 2)
    )
    checks.map { (n, f) =>
      val r = Try(f()).fold(e => s"FAIL ${e.getClass.getSimpleName}: ${e.getMessage}", ok => if ok then "ok" else "FAIL")
      s"$n: $r"
    }.mkString("\n")

  private def simE2E(): String =
    Try {
      val t0 = System.nanoTime()
      val out = TrajectorySimulator.run(Scenarios.commute())
      val t1 = System.nanoTime()
      val segs = TimelineBuilder.build(out.fixes, out.motion)
      val t2 = System.nanoTime()
      val r = Evaluation.evaluate(out.truth, segs)
      f"${r.summary}\nsimulate ${(t1 - t0) / 1e6}%.0f ms (${out.fixes.size} fixes), build ${(t2 - t1) / 1e6}%.0f ms"
    }.fold(e => s"FAIL $e\n${e.getStackTrace.take(8).mkString("\n")}", identity)

  // --- simulated data ---------------------------------------------------------------

  private def insertSim(): Unit =
    AshidoriApp.io.execute { () =>
      val out = TrajectorySimulator.run(Scenarios.commute(LocalDate.now(AshidoriApp.zone).minusDays(1)))
      AshidoriApp.db.insertFixes(out.fixes.map(_.copy(provider = "sim")))
      AshidoriApp.db.insertMotion(out.motion, "sim")
      act.runOnUiThread(() => Toast.makeText(act, s"${out.fixes.size} 点を昨日に投入しました", Toast.LENGTH_SHORT).show())
    }

  private def deleteSim(): Unit =
    AshidoriApp.io.execute { () =>
      val n = AshidoriApp.db.deleteSimulated()
      act.runOnUiThread(() => Toast.makeText(act, s"$n 行を削除しました", Toast.LENGTH_SHORT).show())
    }

  // --- GNSS probe ------------------------------------------------------------------------

  private def runProbe(): Unit =
    if act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED then
      act.ensurePermissions(() => runProbe())
    else
      probeButton.setEnabled(false)
      val main = Handler(Looper.getMainLooper)
      val exec: Executor = r => main.post(r)
      val start = System.currentTimeMillis()
      var firstFix = -1L
      var fixes = 0
      var bestAcc = Float.MaxValue
      var events = 0
      var measurements = 0
      var adrValid = 0
      val signals = mutable.Set.empty[(Int, Int, String)]
      val codeTypes = mutable.Set.empty[String]
      var navMessages = 0
      val fixListener: LocationListener = l =>
        if firstFix < 0 then firstFix = System.currentTimeMillis() - start
        fixes += 1
        if l.hasAccuracy then bestAcc = math.min(bestAcc, l.getAccuracy)
      val measCb = new GnssMeasurementsEvent.Callback:
        override def onGnssMeasurementsReceived(ev: GnssMeasurementsEvent): Unit =
          events += 1
          ev.getMeasurements.forEach { m =>
            measurements += 1
            if (m.getAccumulatedDeltaRangeState & GnssMeasurement.ADR_STATE_VALID) != 0 then adrValid += 1
            val band = if m.hasCarrierFrequencyHz then bandOf(m.getCarrierFrequencyHz) else "?"
            signals += ((m.getConstellationType, m.getSvid, band))
            if m.hasCodeType then codeTypes += s"${Constellations.name(m.getConstellationType)}:$band${m.getCodeType}"
          }
      val navCb = new GnssNavigationMessage.Callback:
        override def onGnssNavigationMessageReceived(m: GnssNavigationMessage): Unit = navMessages += 1
      lm.requestLocationUpdates(
        LocationManager.GPS_PROVIDER,
        LocationRequest.Builder(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(),
        exec,
        fixListener
      )
      lm.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), exec, measCb)
      lm.registerGnssNavigationMessageCallback(exec, navCb)
      var left = 60
      def countdown(): Unit =
        probeButton.setText(s"GNSS テスト中… 残り ${left} 秒（fix $fixes, 測定 $measurements）")
        left -= 1
        if left >= 0 then main.postDelayed(() => countdown(), 1000)
        else
          lm.removeUpdates(fixListener)
          lm.unregisterGnssMeasurementsCallback(measCb)
          lm.unregisterGnssNavigationMessageCallback(navCb)
          val byConst = signals.groupBy(_._1).toSeq.sortBy(_._1).map { (c, ss) =>
            s"${Constellations.name(c)}: ${ss.map(_._2).size} sv, bands ${ss.groupBy(_._3).map((b, x) => s"$b=${x.size}").mkString("/")}"
          }
          sections("gnss-probe") =
            s"""ttff=${if firstFix < 0 then "none" else s"${firstFix / 1000.0}s"} fixes=$fixes bestAcc=${if bestAcc == Float.MaxValue then "-" else f"$bestAcc%.1fm"}
               |measurementEvents=$events measurements=$measurements adrValid=$adrValid (${if measurements > 0 then adrValid * 100 / measurements else 0}%%)
               |navMessages=$navMessages
               |${byConst.mkString("\n")}
               |codes=${codeTypes.toSeq.sorted.mkString(" ")}""".stripMargin
          render()
          probeButton.setText("GNSS 60秒テスト（屋外で）")
          probeButton.setEnabled(true)
      countdown()
