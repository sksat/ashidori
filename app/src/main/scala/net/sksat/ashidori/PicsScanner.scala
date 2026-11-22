package net.sksat.ashidori

import android.bluetooth.{BluetoothAdapter, BluetoothManager}
import android.bluetooth.le.{ScanCallback, ScanFilter, ScanResult, ScanSettings}
import android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import android.content.pm.PackageManager
import android.os.{Build, Handler, SystemClock}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import ashidori.core.*

/** The BLE scan for 高度化PICS, driven by [[LoggerService]] on its thread
  * (docs/design/pics.md 3): the plan ([[PicsScanPolicy]]) is made from the
  * logger's inputs at each evaluation and fix and at each qualifying
  * reception; the gate ([[PicsScanGate]]) starts and stops the one
  * registration. Receive only: a filtered scan of company 0x01CE (0xFFFF
  * too in debug builds), passive where the OS has it; the app declares no
  * permission to connect or advertise.
  *
  * Nothing here wakes the device: the plan runs when the logger is awake
  * anyway; the filtered scan's results come from the controller.
  */
final class PicsScanner(ctx: Context, handler: Handler, onRegistered: () => Unit):
  private lazy val manager = ctx.getSystemService(classOf[BluetoothManager])
  private def adapter: Option[BluetoothAdapter] = Option(manager).flatMap(m => Option(m.getAdapter))
  /** One for the process: a new logger (stop and start) starts no faster. */
  private def gate = PicsScanner.gate
  private var plan = PicsScanPolicy.Initial
  private var callback: Option[Callback] = None
  private var receiverOn = false
  private val debuggable =
    (ctx.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  private val replan: Runnable = () => PicsLive.replan()

  private val stateReceiver = new BroadcastReceiver:
    override def onReceive(c: Context, i: Intent): Unit =
      val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
      if s == BluetoothAdapter.STATE_OFF || s == BluetoothAdapter.STATE_TURNING_OFF then
        // The stack drops every scan: nothing to stop.
        if callback.isDefined then PicsIngest.scanChanged()
        callback = None
        gate.lost()
        publish()
      PicsLive.replan()

  def permission: Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

  def bluetoothOn: Boolean = Try(adapter.exists(_.isEnabled)).getOrElse(false)

  def start(): Unit =
    if !receiverOn then
      ctx.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler,
        Context.RECEIVER_NOT_EXPORTED)
      receiverOn = true

  def stop(): Unit =
    apply(PicsScanPolicy.Initial)
    if receiverOn then
      Try(ctx.unregisterReceiver(stateReceiver))
      receiverOn = false

  /** The scan's key now, for `energy_state.ble`. */
  def key: String = callback.fold(PicsScan.Off.key)(_.scan.key)

  /** Brings the registration to `p` (on the logger thread). */
  def apply(p: PicsScanPolicy.Plan): Unit =
    plan = p
    val now = SystemClock.elapsedRealtime()
    val d = gate.step(now, p.scan, p.reportDelayMs)
    if d.stop then stopScan()
    d.start.foreach(s => startScan(s, p.reportDelayMs, now))
    handler.removeCallbacks(replan)
    d.next.foreach(t => handler.postDelayed(replan, math.max(0L, t - now) + 50L))
    if d.stop || d.start.isDefined then publish()

  private def publish(): Unit =
    PicsLive.registered = callback.map(c => (c.scan, c.delayMs))
    PicsIngest.delayMs = callback.fold(0L)(_.delayMs)
    onRegistered()

  private final class Callback(val scan: PicsScan, val delayMs: Long) extends ScanCallback:
    /** Counted down when the results a flush asked for were handed over. */
    @volatile var flushed: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(0)
    private def take(r: ScanResult): Unit =
      val rec = r.getScanRecord
      if rec != null then
        val company = Seq(PicsFormat.CompanyId) ++ (if debuggable then Seq(PicsFormat.TestCompanyId) else Nil)
        for c <- company; data <- Option(rec.getManufacturerSpecificData(c)) do
          val elapsed = r.getTimestampNanos / 1_000_000L
          val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)
          PicsIngest.onResult(PicsResult(wall, r.getDevice.getAddress, c, data.toIndexedSeq, r.getRssi, scan.key), elapsed)
    override def onScanResult(callbackType: Int, r: ScanResult): Unit = take(r)
    // Taken before the latch opens: the stop that waits for them comes after.
    override def onBatchScanResults(rs: java.util.List[ScanResult]): Unit =
      rs.asScala.foreach(take)
      flushed.countDown()
    override def onScanFailed(code: Int): Unit = handler.post { () =>
      if callback.contains(this) then
        callback = None
        gate.failed(SystemClock.elapsedRealtime(), code)
        PicsLive.failure = Some((System.currentTimeMillis(), code))
        LifeLog.add(LifeKind.PicsProblem, s"信号機の電波の走査を開始できず（${scan.label}、コード $code）")
        publish()
        PicsLive.replan()
    }

  private def mode(s: PicsScan): Int = s match
    case PicsScan.Opportunistic => ScanSettings.SCAN_MODE_OPPORTUNISTIC
    case PicsScan.LowPower      => ScanSettings.SCAN_MODE_LOW_POWER
    case PicsScan.Balanced      => ScanSettings.SCAN_MODE_BALANCED
    // Batched: the window is 25 % with LOW_LATENCY requested (design 3.2).
    case PicsScan.BalancedBatch => ScanSettings.SCAN_MODE_LOW_LATENCY
    case _                      => ScanSettings.SCAN_MODE_LOW_LATENCY

  private def startScan(s: PicsScan, delayMs: Long, now: Long): Unit =
    val scanner = Try(adapter.flatMap(a => Option(a.getBluetoothLeScanner))).toOption.flatten
    scanner match
      case None =>
        // Bluetooth went off between the plan and here: the gate is told.
        gate.lost()
      case Some(sc) =>
        val ids = Seq(PicsFormat.CompanyId) ++ (if debuggable then Seq(PicsFormat.TestCompanyId) else Nil)
        val filters = ids.map(id => ScanFilter.Builder().setManufacturerData(id, Array.emptyByteArray).build())
        val b = ScanSettings.Builder()
          .setScanMode(mode(s))
          .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
          .setReportDelay(delayMs)
          .setLegacy(true)
        if PicsScanner.passiveScan then b.setScanType(ScanSettings.SCAN_TYPE_PASSIVE)
        val cb = Callback(s, delayMs)
        try
          sc.startScan(filters.asJava, b.build(), cb)
          Log.i(s"PICS scan started: ${s.key} delay $delayMs at elapsed $now")
          callback = Some(cb)
          PicsIngest.scanChanged()
        catch case e: Exception =>
          gate.failed(now, -1)
          PicsLive.failure = Some((System.currentTimeMillis(), -1))
          LifeLog.add(LifeKind.PicsProblem, s"信号機の電波の走査を開始できず（${s.label}: ${LifeLog.describe(e)}）")

  /** Stops the scan (a change, the reopening, the end): a batched one
    * flushed first, and stopped once its results came (on the main thread,
    * where the callbacks run: waiting here does not hold them up), at most
    * [[PicsScanStop.TimeoutMs]]; results after that are still taken.
    */
  private def stopScan(): Unit =
    callback.foreach { cb =>
      Try(adapter.flatMap(a => Option(a.getBluetoothLeScanner))).toOption.flatten.foreach { sc =>
        val came = PicsScanStop.run(cb.delayMs > 0,
          () => { cb.flushed = java.util.concurrent.CountDownLatch(1); Try(sc.flushPendingScanResults(cb)) },
          ms => cb.flushed.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS),
          () => Try(sc.stopScan(cb)).failed.foreach(e => Log.w("PICS scan stop", e)))
        if !came then Log.w("PICS: the flushed results did not come before the stop")
      }
    }
    if callback.isDefined then PicsIngest.scanChanged()
    callback = None

  /** For 端末診断: the controller's filtering and batching, and the scan type. */
  def offloadedFiltering: Option[Boolean] = Try(adapter.map(_.isOffloadedFilteringSupported)).toOption.flatten
  def offloadedBatching: Option[Boolean] = Try(adapter.map(_.isOffloadedScanBatchingSupported)).toOption.flatten

object PicsScanner:
  /** The start gate of the process ([[PicsScanGate]]): kept across loggers. */
  private[ashidori] val gate = PicsScanGate()

  /** SCAN_TYPE_PASSIVE: Android 16 QPR2 (36.1) and later. */
  def passiveScan: Boolean =
    Build.VERSION.SDK_INT >= 37 || Build.VERSION.SDK_INT == 36 && Try(Build.VERSION.SDK_INT_FULL >= 3_600_001).getOrElse(false)

  private def adapterOf(ctx: Context): Option[BluetoothAdapter] =
    Option(ctx.getSystemService(classOf[BluetoothManager])).flatMap(m => Option(m.getAdapter))

  def hasPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
  def bluetoothOn(ctx: Context): Option[Boolean] = Try(adapterOf(ctx).map(_.isEnabled)).toOption.flatten
  def offloadedFiltering(ctx: Context): Option[Boolean] = Try(adapterOf(ctx).map(_.isOffloadedFilteringSupported)).toOption.flatten
  def offloadedBatching(ctx: Context): Option[Boolean] = Try(adapterOf(ctx).map(_.isOffloadedScanBatchingSupported)).toOption.flatten
