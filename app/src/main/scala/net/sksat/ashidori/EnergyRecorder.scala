package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import android.os.{BatteryManager, Handler}
import ashidori.core.*

/** Battery readings and the logger's state spans, for the energy analysis
  * ([[EnergyModel]]). Used on the logger thread; writes go to
  * [[AshidoriApp.io]].
  *
  * Nothing here wakes the device: a reading is taken when the state changes
  * (the logger or the system is awake then anyway) and by the logger's own
  * 5-minute sampling. Screen and charger changes come from broadcasts the
  * system sends while awake.
  */
final class EnergyRecorder(ctx: Context, bm: BatteryManager, db: Db, handler: Handler, onChange: () => Unit):
  private var state: Option[EnergyState] = None
  /** Screen and charger as last broadcast. */
  var screenOn: Boolean = true
  var plugged: Boolean = false
  private var registered = false

  private val receiver = new BroadcastReceiver:
    override def onReceive(c: Context, i: Intent): Unit =
      i.getAction match
        case Intent.ACTION_SCREEN_ON          => screenOn = true
        case Intent.ACTION_SCREEN_OFF         => screenOn = false
        case Intent.ACTION_POWER_CONNECTED    => plugged = true
        case Intent.ACTION_POWER_DISCONNECTED => plugged = false
        case _                                => ()
      onChange()

  /** Closes spans a killed process left open (at its last battery reading),
    * then listens for screen and charger changes.
    */
  def start(interactive: Boolean): Unit =
    screenOn = interactive
    plugged = sticky().exists(i => i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0) || bm.isCharging
    AshidoriApp.io.execute(() => scala.util.Try(db.switchEnergyState(db.lastBatteryTime(), None)).failed
      .foreach(e => Log.w("closing energy spans", e)))
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
    if state.isDefined then
      state = None
      AshidoriApp.io.execute(() => db.switchEnergyState(now, None))

  /** Records a new span and a battery reading when the state changed. */
  def update(now: Long, st: EnergyState, plan: String): Unit =
    if !state.contains(st) then
      state = Some(st)
      val r = reading(now, st.screenOn)
      AshidoriApp.io.execute { () =>
        db.switchEnergyState(now, Some(st))
        db.insertBattery(r, plan)
      }

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
  def plugName(p: Int): String = p match
    case 0                                   => "none"
    case BatteryManager.BATTERY_PLUGGED_AC       => "ac"
    case BatteryManager.BATTERY_PLUGGED_USB      => "usb"
    case BatteryManager.BATTERY_PLUGGED_WIRELESS => "wireless"
    case BatteryManager.BATTERY_PLUGGED_DOCK     => "dock"
    case other                               => s"other:$other"
