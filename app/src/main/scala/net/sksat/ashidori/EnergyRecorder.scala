package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import android.os.{BatteryManager, Handler}
import ashidori.core.*

/** Battery readings and the logger's state spans, for the energy analysis
  * ([[EnergyModel]]). Used on the logger thread; rows are saved through
  * [[LoggerWrites]], the logger's own write path.
  *
  * State changes are kept until stored ([[EnergyTracker]]): every record
  * handed over carries all changes not confirmed yet, so one that is lost
  * is covered by the next, and the table applies each change once
  * ([[EnergyLog]]). [[heartbeat]] confirms the current state now and then;
  * the time of the last confirmation is where the next process closes what
  * this one leaves open.
  *
  * Nothing here wakes the device: a reading is taken when the state changes
  * (the logger or the system is awake then anyway) and by the logger's own
  * 5-minute sampling. Screen and charger changes come from broadcasts the
  * system sends while awake.
  */
final class EnergyRecorder(ctx: Context, bm: BatteryManager, handler: Handler, onChange: () => Unit):
  private val tracker = EnergyTracker(EnergyRecorder.nextBoot())
  /** Screen and charger as last broadcast. */
  var screenOn: Boolean = true
  var plugged: Boolean = false
  private var registered = false
  private var lostNoted = 0L

  private val receiver = new BroadcastReceiver:
    override def onReceive(c: Context, i: Intent): Unit =
      i.getAction match
        case Intent.ACTION_SCREEN_ON          => screenOn = true
        case Intent.ACTION_SCREEN_OFF         => screenOn = false
        case Intent.ACTION_POWER_CONNECTED    => plugged = true
        case Intent.ACTION_POWER_DISCONNECTED => plugged = false
        case _                                => ()
      onChange()

  /** Listens for screen and charger changes. What a previous process left
    * open is closed by this one's first record ([[EnergyLog]]).
    */
  def start(interactive: Boolean): Unit =
    screenOn = interactive
    plugged = sticky().exists(i => i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0) || bm.isCharging
    if !registered then
      val f = IntentFilter()
      Seq(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED)
        .foreach(f.addAction)
      ctx.registerReceiver(receiver, f, null, handler, Context.RECEIVER_NOT_EXPORTED)
      registered = true

  def stop(now: Long): Unit =
    if registered then
      scala.util.Try(ctx.unregisterReceiver(receiver))
      registered = false
    if tracker.update(now, None) then submit(now)

  /** Records a new span and a battery reading when the state changed. */
  def update(now: Long, st: EnergyState, plan: String): Unit =
    if tracker.update(now, Some(st)) then
      LoggerWrites.small(SmallRecord.Battery(reading(now, st.screenOn), plan))
      submit(now)

  /** The current state still holds at `now`: stored as the checkpoint, with
    * any change not confirmed yet. Called with the 5-minute battery reading.
    */
  def heartbeat(now: Long): Unit = if tracker.current.isDefined || tracker.unconfirmed > 0 then submit(now)

  private def submit(now: Long): Unit =
    val r = tracker.record(now)
    val upTo = r.transitions.lastOption.map(_.seq)
    LoggerWrites.small(r, () => upTo.foreach(tracker.confirmed))
    if tracker.dropped > lostNoted then
      LifeLog.add(LifeKind.Note, s"記録の状態の変化 ${tracker.dropped - lostNoted} 件を保存できず")
      lostNoted = tracker.dropped

  private def sticky(): Option[Intent] =
    scala.util.Try(Option(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)))
      .toOption.flatten

  /** The battery now. Values the device does not provide are None (NaN). */
  def reading(now: Long, screen: Boolean): BatteryReading =
    def prop(id: Int): Option[Int] = Option(bm.getIntProperty(id)).filter(_ != Int.MinValue)
    val i = sticky()
    def extra(k: String): Option[Int] = i.map(_.getIntExtra(k, Int.MinValue)).filter(_ != Int.MinValue)
    BatteryReading(
      now,
      bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
      bm.isCharging,
      screen,
      Settings.mode.key,
      // 0 means "not supported" on some devices.
      prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).filter(_ > 0).map(_.toLong),
      // Android: positive into the battery. Stored discharge-positive.
      prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE).map(-_),
      prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).map(-_),
      extra(BatteryManager.EXTRA_VOLTAGE).filter(_ > 0),
      extra(BatteryManager.EXTRA_TEMPERATURE).fold(Float.NaN)(_ / 10f),
      extra(BatteryManager.EXTRA_PLUGGED).map(EnergyRecorder.plugName)
    )

object EnergyRecorder:
  private def bootFile = java.io.File(AshidoriApp.app.getFilesDir, "energy-boot")

  /** This process's recorder number: one above every earlier one, kept on
    * disk before it is used (atomically, fsynced). Above the highest the
    * database has seen as well, in case the file was lost (data restored
    * from a backup) or could not be written last time. Ordering by it, not
    * by the clock, keeps restarts in order when the clock goes back.
    */
  def nextBoot(): Long = synchronized {
    val onFile = scala.util.Try(String(java.nio.file.Files.readAllBytes(bootFile.toPath), "UTF-8").trim.toLong).getOrElse(0L)
    val inDb = scala.util.Try(AshidoriApp.db.energyMaxBoot()).getOrElse(0L)
    val n = math.max(onFile, inDb) + 1
    try Maintenance.write(bootFile, n.toString)
    catch case scala.util.control.NonFatal(e) =>
      Log.w("boot counter not saved", e)
      LifeLog.add(LifeKind.Note, s"記録の状態の通し番号を保存できず: ${LifeLog.describe(e)}")
    n
  }

  def plugName(p: Int): String = p match
    case 0                                   => "none"
    case BatteryManager.BATTERY_PLUGGED_AC       => "ac"
    case BatteryManager.BATTERY_PLUGGED_USB      => "usb"
    case BatteryManager.BATTERY_PLUGGED_WIRELESS => "wireless"
    case BatteryManager.BATTERY_PLUGGED_DOCK     => "dock"
    case other                               => s"other:$other"
