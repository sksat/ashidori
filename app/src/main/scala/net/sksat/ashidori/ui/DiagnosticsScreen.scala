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

  private val debuggable =
    (act.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  val view: View =
    scroll(
      vbox(
        card(
          (Seq(
            text("端末診断", 18f, bold = true),
            caption("結果は「レポートをコピー」でまとめてコピーできます。"),
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
        card(body),
        spacer(24)
      )
    )

  override def onShow(): Unit =
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

  private def render(): Unit =
    body.setText(sections.map((k, v) => s"## $k\n$v").mkString("\n\n"))

  private def copy(): Unit =
    val cm = act.getSystemService(classOf[ClipboardManager])
    cm.setPrimaryClip(ClipData.newPlainText("ashidori diagnostics", s"ashidori ${BuildInfo.versionName}\n" + body.getText))
    Toast.makeText(act, "コピーしました", Toast.LENGTH_SHORT).show()

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
