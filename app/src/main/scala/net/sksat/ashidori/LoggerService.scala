package net.sksat.ashidori

import android.app.{Notification, PendingIntent, Service}
import android.content.{Context, Intent}
import android.content.pm.ServiceInfo
import android.hardware.{Sensor, SensorEvent, SensorEventListener, SensorManager, TriggerEvent, TriggerEventListener}
import android.location.{GnssStatus, Location, LocationListener, LocationManager, LocationRequest}
import android.os.{BatteryManager, Handler, HandlerThread, IBinder, PowerManager, SystemClock}
import java.util.concurrent.Executor
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import ashidori.core.*

/** Foreground service that does all logging.
  *
  * Everything runs on one dedicated thread, so the state below needs no
  * locking. Power strategy:
  *  - stationary: GNSS off, accelerometer off, only the significant-motion
  *    trigger (sensor hub) plus a rare low-power network fix;
  *  - moving: GNSS at an interval that depends on the motion class, with
  *    location and sensor batching so the AP sleeps between deliveries;
  *  - raw GNSS detail sessions are duty-cycled by [[SamplingPolicy]].
  */
class LoggerService extends Service:
  import LoggerService.*

  private var thread: HandlerThread = null
  private var handler: Handler = null
  private lazy val exec: Executor = r => handler.post(r)
  private lazy val lm = getSystemService(classOf[LocationManager])
  private lazy val sm = getSystemService(classOf[SensorManager])
  private lazy val pm = getSystemService(classOf[PowerManager])
  private lazy val bm = getSystemService(classOf[BatteryManager])
  private lazy val db = AshidoriApp.db

  private val estimator = MotionStateEstimator()
  private val indoor = IndoorDetector()
  /** Dead reckoning while walking indoors (GNSS unusable). */
  private val pdr = Pdr()
  private var pdrSensors = false
  private var heading = Double.NaN
  /** Outdoor walk used to learn the stride: (start fix, steps since). */
  private var strideRef: Option[(Fix, Int)] = None
  private var lastStatusSaved = 0L
  private val accumulator = MotionAccumulator(10_000)
  private val pendingFixes = ArrayBuffer.empty[Fix]
  private val pendingMotion = ArrayBuffer.empty[MotionWindow]
  private var lastFlush = 0L
  private var lastEval = 0L
  /** When the current motion state began (indoor GNSS probes count from it). */
  private var motionSince = System.currentTimeMillis()
  private val replan: Runnable = () => evaluate(force = true)
  private var lastBatterySample = 0L
  private var drain = Double.NaN

  private var plan: Option[SamplingPlan] = None
  /** (provider, interval, batch, minDistance) currently registered */
  private var locReg: Option[(String, Long, Long, Float)] = None
  private var accelReg: Int = 0
  /** When the accelerometer was turned on; 0 while off. */
  private var accelSince = 0L
  private var statusRegistered = false
  private var recorder: Option[GnssRecorder] = None
  private var sigMotionArmed = false
  private var lastStepCount = -1f
  /** Steps since local midnight of [[stepsDay]]: the DB's count at start
    * (added once read) plus everything counted since.
    */
  private var stepsToday = 0
  private var stepsBaseKnown = false
  private var stepsDay = java.time.LocalDate.now(AshidoriApp.zone)
  private var lastSpeed = Double.NaN
  private var lastSpeedAt = 0L

  // --- service lifecycle ------------------------------------------------------

  override def onBind(intent: Intent): IBinder = null

  override def onCreate(): Unit =
    super.onCreate()
    thread = HandlerThread("ashidori-logger")
    thread.start()
    handler = Handler(thread.getLooper)

  override def onStartCommand(intent: Intent, flags: Int, startId: Int): Int =
    val action = Option(intent).flatMap(i => Option(i.getAction)).getOrElse(ActionStart)
    action match
      case ActionStop =>
        Settings.loggingEnabled = false
        stopSelf()
      // Sticky restarts arrive here without a permission check.
      case _ if !LoggerService.hasLocationPermission(this) =>
        Log.w("logger: no location permission, not starting")
        stopSelf()
        return Service.START_NOT_STICKY
      case _ =>
        if action == ActionBurst then Settings.burstUntil = System.currentTimeMillis() + BurstMs
        startForeground(NotificationId, notification("記録を開始しています"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        handler.post(() => start())
    Service.START_STICKY

  private var started = false
  private def start(): Unit =
    if !started then
      started = true
      Option(sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)).foreach { s =>
        // Steps are cheap (sensor hub) and batched for up to 5 minutes.
        sm.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_NORMAL, 5 * 60 * 1_000_000, handler)
      }
      // Today's steps so far, from before this start (off this thread).
      val midnight = stepsDay.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
      val day = stepsDay
      AshidoriApp.io.execute { () =>
        val base = scala.util.Try(db.steps(midnight, System.currentTimeMillis())).getOrElse(0)
        handler.post { () =>
          if stepsDay == day && !stepsBaseKnown then
            stepsToday += base
            stepsBaseKnown = true
            publishStepsToday()
        }
      }
      // While the status screen is open it asks for batched samples early,
      // so its "now" values are seconds old, not minutes. The screen is on
      // then anyway; the samples are the same ones delivered later otherwise.
      LoggerState.flushSensors = () => handler.post(() => sm.flush(sensorListener))
      LoggerState.onMain { LoggerState.running = true }
      tick()
    evaluate(force = true)

  override def onDestroy(): Unit =
    LoggerState.flushSensors = () => ()
    handler.post { () =>
      handler.removeCallbacksAndMessages(null)
      stopRecorder()
      lm.removeUpdates(locationListener)
      if statusRegistered then lm.unregisterGnssStatusCallback(statusCallback)
      sm.unregisterListener(sensorListener)
      disarmSigMotion()
      accumulator.flush().foreach(pendingMotion += _)
      flush(force = true)
      thread.quitSafely()
    }
    LoggerState.onMain {
      LoggerState.running = false
      LoggerState.plan = None
      LoggerState.satellites = Vector.empty
      LoggerState.satellitesAt = 0L
    }
    LoggerState.changed()
    super.onDestroy()

  /** Periodic re-evaluation. Handler time stops while the CPU sleeps, so this
    * never wakes the device by itself.
    */
  private def tick(): Unit =
    evaluate()
    handler.postDelayed(() => tick(), 60_000)

  // --- policy ------------------------------------------------------------------

  private def evaluate(force: Boolean = false): Unit =
    val now = System.currentTimeMillis()
    if !force && now - lastEval < 3_000 then return
    lastEval = now

    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val charging = bm.isCharging
    if now - lastBatterySample >= 5 * 60_000 then
      lastBatterySample = now
      val p = plan
      val screen = pm.isInteractive
      AshidoriApp.io.execute { () =>
        db.insertBattery(now, pct, charging, screen, Settings.mode.key, p.map(_.describe).getOrElse(""))
        drain = DrainEstimator.screenOffDrain(db.battery(now - 4 * 3_600_000L), now)
      }

    val speed = if now - lastSpeedAt < 60_000 then lastSpeed else Double.NaN
    val input = PolicyInput(
      Settings.mode,
      PowerState(pct, charging, pm.isPowerSaveMode, drain),
      estimator.current,
      speed,
      now,
      Settings.lastDetailStart,
      Settings.burstUntil,
      indoor.current,
      motionSince
    )
    val p = SamplingPolicy.plan(input)
    if p.rawGnss && recorder.isEmpty then
      val prof = SamplingPolicy.profiles(p.effectiveMode)
      if now - Settings.lastDetailStart >= prof.detailDurationMs then Settings.lastDetailStart = now
    applyPlan(p)
    handler.removeCallbacks(replan)
    if p.replanInMs > 0 then handler.postDelayed(replan, p.replanInMs + 500)
    val changed = !plan.contains(p)
    plan = Some(p)
    if changed then updateNotification(p)
    LoggerState.onMain {
      LoggerState.plan = Some(p)
      LoggerState.motion = estimator.current
      LoggerState.motionSince = motionSince
      LoggerState.battery = pct
      LoggerState.drainPctPerHour = drain
    }
    LoggerState.changed()

  private def applyPlan(p: SamplingPlan): Unit =
    // Location
    val provider = p.source match
      case LocationSource.Gnss    => LocationManager.GPS_PROVIDER
      case LocationSource.Network =>
        if lm.hasProvider(LocationManager.FUSED_PROVIDER) then LocationManager.FUSED_PROVIDER
        else LocationManager.NETWORK_PROVIDER
      case LocationSource.Passive => LocationManager.PASSIVE_PROVIDER
    val want = (provider, p.intervalMs, p.maxBatchDelayMs, p.minDistanceM)
    if !locReg.contains(want) then
      try
        lm.removeUpdates(locationListener)
        val quality = p.source match
          case LocationSource.Gnss    => LocationRequest.QUALITY_HIGH_ACCURACY
          case LocationSource.Network => LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
          case LocationSource.Passive => LocationRequest.QUALITY_LOW_POWER
        val req = LocationRequest.Builder(if p.source == LocationSource.Passive then 0L else p.intervalMs)
          .setQuality(quality)
          .setMinUpdateDistanceMeters(p.minDistanceM)
          .setMaxUpdateDelayMillis(p.maxBatchDelayMs)
          .setMinUpdateIntervalMillis(math.min(p.intervalMs, 1_000L))
          .build()
        lm.requestLocationUpdates(provider, req, exec, locationListener)
        locReg = Some(want)
      catch case e: SecurityException => Log.w("location permission missing", e)

    // Satellite status for the sky plot while the GNSS engine is on anyway.
    val wantStatus = p.source == LocationSource.Gnss
    if wantStatus then indoor.onGnssStarted(System.currentTimeMillis()) else indoor.onGnssStopped()
    if wantStatus != statusRegistered then
      try
        if wantStatus then lm.registerGnssStatusCallback(exec, statusCallback)
        else
          lm.unregisterGnssStatusCallback(statusCallback)
          LoggerState.onMain {
            LoggerState.satellites = Vector.empty
            LoggerState.satellitesAt = 0L
          }
        statusRegistered = wantStatus
      catch case e: SecurityException => Log.w("gnss status", e)

    // Raw GNSS detail session
    if p.rawGnss && recorder.isEmpty then
      val reason =
        if p.effectiveMode == LoggingMode.Max then "max"
        else if System.currentTimeMillis() < Settings.burstUntil then "burst"
        else "periodic"
      val r = GnssRecorder(this, lm, handler, fullTracking = p.effectiveMode == LoggingMode.Max || reason == "burst", reason)
      r.start()
      recorder = Some(r)
      LoggerState.onMain {
        LoggerState.detailFile = Some(r.label)
        LoggerState.detailStartedAt = r.startedAt
      }
    else if !p.rawGnss then stopRecorder()

    // Accelerometer
    if p.accelHz != accelReg then
      Option(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true))
        .orElse(Option(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)))
        .foreach { s =>
          sm.unregisterListener(sensorListener, s)
          if p.accelHz > 0 then
            sm.registerListener(sensorListener, s, 1_000_000 / p.accelHz, (p.accelBatchMs * 1000).toInt, handler)
        }
      if p.accelHz == 0 then accumulator.flush().foreach(onWindow)
      if p.accelHz == 0 then accelSince = 0L
      else if accelReg == 0 then accelSince = System.currentTimeMillis()
      accelReg = p.accelHz

    updatePdr()
    if p.accelHz == 0 && estimator.current == MotionState.Stationary then armSigMotion()
    else disarmSigMotion()
    LoggerState.sensors = SensorState(accelReg, if accelReg > 0 then p.accelBatchMs else 0L, sigMotionArmed, pdrSensors,
      recorder.map(_.imuHz).getOrElse(0), accelSince)

  /** Step detector + rotation vector only while walking indoors: the gyro
    * on some phones cannot batch, so this keeps the CPU awake.
    */
  private def updatePdr(): Unit =
    val want = indoor.current == Environment.Indoor &&
      (estimator.current == MotionState.OnFoot || estimator.current == MotionState.Unknown)
    if want != pdrSensors then
      val step = Option(sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)).orElse(Option(sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)))
      val rot = Option(sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
      if want && step.isDefined && rot.isDefined then
        sm.registerListener(sensorListener, step.get, SensorManager.SENSOR_DELAY_NORMAL, handler)
        sm.registerListener(sensorListener, rot.get, SensorManager.SENSOR_DELAY_UI, handler)
        LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 5 * 60_000).foreach(pdr.reset)
        pdrSensors = true
      else if !want then
        step.foreach(sm.unregisterListener(sensorListener, _))
        rot.foreach(sm.unregisterListener(sensorListener, _))
        pdr.stop()
        pdrSensors = false

  private def stopRecorder(): Unit =
    recorder.foreach { r =>
      r.stop()
      val end = System.currentTimeMillis()
      AshidoriApp.io.execute(() => db.insertGnssSession(r.startedAt, end, "", r.reason))
    }
    recorder = None
    LoggerState.onMain { LoggerState.detailFile = None }

  // --- significant motion ----------------------------------------------------------

  private val sigMotionListener = new TriggerEventListener:
    override def onTrigger(event: TriggerEvent): Unit =
      handler.post { () =>
        sigMotionArmed = false
        estimator.wake()
        motionSince = System.currentTimeMillis()
        evaluate(force = true)
      }

  private def armSigMotion(): Unit =
    if !sigMotionArmed then
      Option(sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)).foreach { s =>
        sigMotionArmed = sm.requestTriggerSensor(sigMotionListener, s)
      }

  private def disarmSigMotion(): Unit =
    if sigMotionArmed then
      Option(sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)).foreach(s => sm.cancelTriggerSensor(sigMotionListener, s))
      sigMotionArmed = false

  // --- callbacks ---------------------------------------------------------------------

  private val locationListener = new LocationListener:
    override def onLocationChanged(l: Location): Unit = onLocations(Seq(l))
    override def onLocationChanged(ls: java.util.List[Location]): Unit = onLocations(ls.asScala.toSeq)
    override def onProviderDisabled(provider: String): Unit = ()
    override def onProviderEnabled(provider: String): Unit = ()

  private def onLocations(ls: Seq[Location]): Unit =
    for l <- ls do
      val f = Fix(
        l.getTime,
        l.getLatitude,
        l.getLongitude,
        if l.hasAccuracy then l.getAccuracy else Float.NaN,
        if l.hasAltitude then l.getAltitude else Double.NaN,
        if l.hasSpeed then l.getSpeed else Float.NaN,
        if l.hasBearing then l.getBearing else Float.NaN,
        l.getProvider,
        -1
      )
      pendingFixes += f
      LoggerState.recentFixes = (LoggerState.recentFixes :+ f).takeRight(600)
      recorder.foreach(_.onFix(l))
      if f.provider == LocationManager.GPS_PROVIDER then indoor.onFix(f.time)
      // A good GNSS fix re-anchors dead reckoning; a coarse one bounds its drift.
      if f.provider == LocationManager.GPS_PROVIDER && f.hasAccuracy && f.accuracy < 20 then pdr.reset(f)
      else if f.provider != LocationManager.GPS_PROVIDER then pdr.onNetworkFix(f)
      // Learn the stride on good outdoor walks.
      if f.provider == LocationManager.GPS_PROVIDER && f.hasAccuracy && f.accuracy < 15 &&
        estimator.current == MotionState.OnFoot && indoor.current != Environment.Indoor
      then
        strideRef match
          case Some((a, steps)) if f.time - a.time > 120_000 =>
            pdr.calibrate(Geo.distance(a, f), steps)
            strideRef = Some((f, 0))
          case None => strideRef = Some((f, 0))
          case _    => ()
      else if estimator.current != MotionState.OnFoot then strideRef = None
      if f.hasSpeed then
        lastSpeed = f.speed
        lastSpeedAt = f.time
    ls.lastOption.foreach { l =>
      val f = pendingFixes.last
      LoggerState.onMain {
        LoggerState.lastFix = Some(f)
        LoggerState.fixesToday += ls.size
      }
    }
    flush()
    evaluate()

  private val statusCallback = new GnssStatus.Callback:
    override def onSatelliteStatusChanged(st: GnssStatus): Unit =
      recorder.foreach(_.onStatus(st))
      val sats = (0 until st.getSatelliteCount).map { i =>
        SatSignal(
          st.getConstellationType(i),
          st.getSvid(i),
          st.getCn0DbHz(i),
          st.getAzimuthDegrees(i),
          st.getElevationDegrees(i),
          st.usedInFix(i),
          if st.hasCarrierFrequencyHz(i) then st.getCarrierFrequencyHz(i) else Float.NaN
        )
      }.toVector
      val at = System.currentTimeMillis()
      LoggerState.onMain {
        LoggerState.satellites = sats
        LoggerState.satellitesAt = at
      }
      // Indoor/outdoor from what the receiver sees; re-plan when it flips.
      val now = System.currentTimeMillis()
      val snap = GnssSnapshot.of(now, sats.map(x => (x.constellation, x.svid, x.cn0, x.usedInFix, x.band == "L5")))
      val before = indoor.current
      val env = indoor.update(snap)
      LoggerState.onMain { LoggerState.environment = env }
      if now - lastStatusSaved >= 10_000 then
        lastStatusSaved = now
        val acc = LoggerState.lastFix.filter(f => now - f.time < 30_000).map(_.accuracy).getOrElse(Float.NaN)
        AshidoriApp.io.execute(() => db.insertGnssStatus(snap, env, acc))
      if env != before then evaluate(force = true)
      LoggerState.changed()

  private val sensorListener = new android.hardware.SensorEventListener2:
    override def onAccuracyChanged(sensor: Sensor, accuracy: Int): Unit = ()
    /** After a flush the step counter has delivered everything it held: its
      * count is current as of now even when no step came (it reports only
      * on change).
      */
    override def onFlushCompleted(sensor: Sensor): Unit =
      if sensor.getType == Sensor.TYPE_STEP_COUNTER then
        val now = System.currentTimeMillis()
        LoggerState.onMain { LoggerState.stepsAt = now }
        LoggerState.changed()
    override def onSensorChanged(e: SensorEvent): Unit =
      e.sensor.getType match
        case Sensor.TYPE_ACCELEROMETER =>
          val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
          accumulator.add(wall, e.values(0), e.values(1), e.values(2)).foreach(onWindow)
        case Sensor.TYPE_ROTATION_VECTOR =>
          val r = new Array[Float](9)
          val o = new Array[Float](3)
          SensorManager.getRotationMatrixFromVector(r, e.values)
          SensorManager.getOrientation(r, o)
          heading = (math.toDegrees(o(0).toDouble) + 360) % 360
        case Sensor.TYPE_STEP_DETECTOR =>
          if !heading.isNaN then
            val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
            pdr.onStep(wall, heading).foreach { f =>
              pendingFixes += f
              LoggerState.recentFixes = (LoggerState.recentFixes :+ f).takeRight(600)
            }
        case Sensor.TYPE_STEP_COUNTER =>
          val total = e.values(0)
          val received = System.currentTimeMillis()
          val wall = received - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
          val today = java.time.LocalDate.now(AshidoriApp.zone)
          if today != stepsDay then
            stepsDay = today
            stepsToday = 0
            stepsBaseKnown = true
          LoggerState.onMain { LoggerState.stepsAt = received }
          if lastStepCount >= 0 && total >= lastStepCount then
            val d = (total - lastStepCount).toInt
            if d > 0 then
              accumulator.addSteps(d)
              strideRef = strideRef.map((a, n) => (a, n + d))
              stepsToday += d
              publishStepsToday()
              LoggerState.onMain {
                LoggerState.stepsSession += d
                LoggerState.recentSteps = (LoggerState.recentSteps :+ (wall, d)).filter(_._1 >= wall - 5 * 60_000)
              }
              // Steps while "stationary" (accelerometer off) mean we are moving.
              if accelReg == 0 && estimator.current == MotionState.Stationary && d >= 10 then
                estimator.wake()
                motionSince = System.currentTimeMillis()
                evaluate(force = true)
          lastStepCount = total
        case _ => ()

  private def publishStepsToday(): Unit =
    val n = if stepsBaseKnown then stepsToday else -1
    LoggerState.onMain { LoggerState.stepsToday = n }
    LoggerState.changed()

  private def onWindow(w: MotionWindow): Unit =
    pendingMotion += w
    // Written on the same schedule as fixes, not only when a fix arrives:
    // without fixes (indoors, network off) windows would wait for onDestroy
    // and be lost if the process is killed.
    flush()
    val before = estimator.current
    val now = System.currentTimeMillis()
    estimator.update(w, if now - lastSpeedAt < 60_000 then lastSpeed else Double.NaN)
    LoggerState.onMain {
      LoggerState.lastWindow = Some(w)
      LoggerState.recentWindows = (LoggerState.recentWindows :+ w).filter(_.end >= w.end - 10 * 60_000)
    }
    LoggerState.changed()
    if estimator.current != before then
      motionSince = now
      // Settled somewhere: look up nearby venues now so a check-in is instant.
      if estimator.current == MotionState.Stationary then
        LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 10 * 60_000)
          .foreach(f => ui.VenueSearch.prefetch(f.latLng))
      evaluate(force = true)
    else evaluate()

  // --- persistence ----------------------------------------------------------------------

  private def flush(force: Boolean = false): Unit =
    val now = System.currentTimeMillis()
    if force || pendingFixes.size + pendingMotion.size >= 50 || now - lastFlush >= 60_000 then
      lastFlush = now
      val fs = pendingFixes.toVector
      val ms = pendingMotion.toVector
      pendingFixes.clear()
      pendingMotion.clear()
      if fs.nonEmpty || ms.nonEmpty then
        AshidoriApp.io.execute { () =>
          if fs.nonEmpty then db.insertFixes(fs)
          if ms.nonEmpty then db.insertMotion(ms)
        }

  // --- notification ------------------------------------------------------------------------

  private def notification(text: String): Notification =
    def pi(action: String, code: Int) =
      PendingIntent.getService(this, code, Intent(this, classOf[LoggerService]).setAction(action), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 0, Intent(this, classOf[MainActivity]), PendingIntent.FLAG_IMMUTABLE)
    Notification.Builder(this, AshidoriApp.ChannelLogging)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("ashidori 記録中")
      .setContentText(text)
      .setContentIntent(open)
      .setOngoing(true)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(Notification.Action.Builder(null, "詳細ログ5分", pi(ActionBurst, 1)).build())
      // No stop button here: recording is meant to run until stopped on
      // purpose in the app (with a confirmation), so a stray tap can't end it.
      .addAction(Notification.Action.Builder(null, "記録の設定", openLogger).build())
      .build()

  private def openLogger: PendingIntent =
    PendingIntent.getActivity(
      this,
      3,
      Intent(this, classOf[MainActivity]).putExtra(MainActivity.ExtraOpenLogger, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    )

  private def updateNotification(p: SamplingPlan): Unit =
    val nm = getSystemService(classOf[android.app.NotificationManager])
    nm.notify(NotificationId, notification(s"${p.effectiveMode.label}・${estimator.current.label}・${p.describe}"))

object LoggerService:
  val ActionStart = "net.sksat.ashidori.START"
  val ActionStop = "net.sksat.ashidori.STOP"
  val ActionBurst = "net.sksat.ashidori.BURST"
  /** Re-read settings (mode changed). */
  val ActionRefresh = "net.sksat.ashidori.REFRESH"
  val NotificationId = 1
  val BurstMs: Long = 5 * 60_000L

  def send(ctx: Context, action: String): Unit =
    if action == ActionStop then
      // Starting a foreground service only to stop it crashes the app when
      // startForeground() is never reached: stop it directly instead.
      Settings.loggingEnabled = false
      ctx.stopService(Intent(ctx, classOf[LoggerService]))
    // A location service without location permission is refused by the OS
    // (e.g. settings restored or imported before permissions were granted).
    else if hasLocationPermission(ctx) then
      ctx.startForegroundService(Intent(ctx, classOf[LoggerService]).setAction(action))
    else Log.w(s"not starting the logger ($action): no location permission")

  def hasLocationPermission(ctx: Context): Boolean =
    Seq(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
      .exists(p => ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED)
