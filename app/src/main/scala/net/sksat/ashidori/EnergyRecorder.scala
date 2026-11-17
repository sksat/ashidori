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
  // Made once this process has a recorder number on disk (EnergyBoot); until
  // then the state is not recorded (tried again at the next change).
  private var trackerNow: Option[EnergyTracker] = None
  private var unrecorded = 0L
  private def tracker: Option[EnergyTracker] =
    if trackerNow.isEmpty then trackerNow = EnergyRecorder.nextBoot().map(EnergyTracker(_))
    EnergyRecorder.live = trackerNow.map(_.boot)
    trackerNow
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
    tracker.foreach(t => if t.update(now, None) then submit(t, now))
    EnergyRecorder.live = None

  /** Records a new span and a battery reading when the state changed. */
  def update(now: Long, st: EnergyState, plan: String): Unit =
    tracker match
      case Some(t) =>
        if t.update(now, Some(st)) then
          LoggerWrites.small(SmallRecord.Battery(reading(now, st.screenOn), plan))
          submit(t, now)
      case None =>
        unrecorded += 1
        if unrecorded == 1 then LifeLog.add(LifeKind.Note, "記録の状態の通し番号を得られないため、状態の変化を保存しない（次の変化で再試行）")

  /** The current state still holds at `now`: stored as the checkpoint, with
    * any change not confirmed yet. Called with the 5-minute battery reading.
    */
  def heartbeat(now: Long): Unit =
    trackerNow.foreach(t => if t.current.isDefined || t.unconfirmed > 0 then submit(t, now))

  private def submit(t: EnergyTracker, now: Long): Unit =
    val r = t.record(now)
    val upTo = r.transitions.lastOption.map(_.seq)
    LoggerWrites.small(r, () => upTo.foreach(t.confirmed))
    if t.dropped > lostNoted then
      LifeLog.add(LifeKind.Note, s"記録の状態の変化 ${t.dropped - lostNoted} 件を保存できず")
      lostNoted = t.dropped

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
  /** The number of this process's running recorder, if it has one: only its
    * open span holds until now. Another open span (a process that is gone,
    * or this one while it has no number) is known only up to its last
    * confirmation ([[EnergyLog.openSpan]]).
    */
  @volatile private[ashidori] var live: Option[Long] = None

  private def bootFile = java.io.File(AshidoriApp.app.getFilesDir, "energy-boot")

  /** This process's recorder number ([[EnergyBoot]]): above the counter
    * file, the database and every spilled record (`.rec`, `.rec.tmp`), and
    * on disk (atomically, fsynced) before it is used. None if any of them
    * cannot be read or the counter cannot be written: no number is used then.
    */
  def nextBoot(): Option[Long] = synchronized {
    val f = bootFile
    val n = EnergyBoot.allocate(
      () => if !f.exists then 0L else String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8").trim.toLong,
      () => AshidoriApp.db.energyMaxBoot(),
      // A spill directory or file that cannot be read aborts (EnergyBoot.spillTexts).
      () => EnergyBoot.spillTexts(LoggerWrites.Overflow.dir),
      n => Maintenance.write(f, n.toString))
    if n.isEmpty then Log.w("no recorder number: a source could not be read or the counter not written")
    n
  }

  def plugName(p: Int): String = p match
    case 0                                   => "none"
    case BatteryManager.BATTERY_PLUGGED_AC       => "ac"
    case BatteryManager.BATTERY_PLUGGED_USB      => "usb"
    case BatteryManager.BATTERY_PLUGGED_WIRELESS => "wireless"
    case BatteryManager.BATTERY_PLUGGED_DOCK     => "dock"
    case other                               => s"other:$other"
