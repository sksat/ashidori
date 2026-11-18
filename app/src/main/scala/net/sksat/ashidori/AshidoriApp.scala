package net.sksat.ashidori

import android.app.{Application, NotificationChannel, NotificationManager}
import android.content.{Context, SharedPreferences}
import java.time.ZoneId
import java.util.concurrent.{Executors, ExecutorService}
import ashidori.core.*

class AshidoriApp extends Application:
  override def onCreate(): Unit =
    super.onCreate()
    AshidoriApp.instance = this
    // Debug builds: storage I/O on the main thread logged (IoCheck).
    IoCheck.install()
    // Lifecycle log for 設定 › 端末診断: crashes on any thread, this start,
    // and the exits the OS recorded since the last start.
    LifeLog.installCrashHandler()
    // Archive maintenance runs in its own process (Maintenance): none of the
    // app's start belongs there.
    if !AshidoriApp.isMaintenanceProcess then startMain()

  private def startMain(): Unit =
    LifeLog.add(LifeKind.ProcessStart, s"記録=${if Settings.loggingEnabled then "オン" else "オフ"}")
    // A database of a newer version (the app was downgraded): nothing that
    // opens it starts; the screen and the logger say so (Db.NewerMessage).
    AshidoriApp.newerDatabase match
      case Some(v) =>
        NewerDatabase.notifyOnce(this, v)
        return
      // Opens again (updated): a notification an older build posted goes.
      case None => NewerDatabase.clear(this)
    val nm = getSystemService(classOf[NotificationManager])
    nm.createNotificationChannel(
      NotificationChannel(AshidoriApp.ChannelLogging, "記録中", NotificationManager.IMPORTANCE_LOW)
    )
    Alerts.createChannel(this)
    AshidoriApp.io.execute { () =>
      LifeLog.recordExitReasons()
      LifeLog.checkState("起動時")
      // Abnormal exits: a notification, and after repeated crashes no
      // optional background work. Then archive maintenance: not now, see
      // Maintenance.schedule.
      Alerts.checkExits()
      Maintenance.schedule(this)
    }
    // An archive commit a crash interrupted is finished now, so the timeline
    // does not show its rows twice (in the archive and in SQLite).
    Thread(() => scala.util.Try(AshidoriApp.archive.recover(AshidoriApp.db)).failed
      .foreach(e => Log.w("archive recovery failed", e)), "ashidori-recover").start()
    LoggerWatchdog.schedule(this)
    Settings.migrate()
    // Every request of the map library through the third line of defence.
    Net.install(this)
    // An unpairing cut short by a crash is finished before anything syncs.
    Thread(() => net.sksat.ashidori.sync.SyncConfig.resumeUnpair(this), "ashidori-unpair").start()
    // The offline basemap: drop older versions (and the copy older APKs made),
    // then queue the first download for an unmetered network.
    AshidoriApp.io.execute(() => Basemap.cleanup())
    BasemapDownload.autoStart(this)
    // The offline venue index (施設データ), likewise.
    AshidoriApp.io.execute(() => VenueData.cleanup())
    VenueDownload.autoStart(this)
    // The region packs: the state and the files reconciled before any use,
    // then (with the data rule automatic) updates and packs left planned.
    RegionDownload.autoStart(this)
    // The floor of the logging reserve, for the large writes of every process.
    Storage.start()
    // Area names cached from the platform geocoder: recompute on the device.
    AshidoriApp.io.execute(() => Geocoding.migrateCachedNames())

object AshidoriApp:
  val ChannelLogging = "logging"
  @volatile private var instance: AshidoriApp = null

  def app: AshidoriApp = instance
  /** The database. Never opened when it is of a newer app version (the
    * entry points check [[newerDatabase]] first; this is the last guard).
    */
  lazy val db: Db =
    newerDatabase.foreach(v => throw NewerDatabase.Refused(v))
    Db(instance)
  /** Every file it writes goes through the guard of the logging reserve (design 6.8), in either process. */
  lazy val archive: ArchiveStore =
    val a = ArchiveStore(java.io.File(instance.getFilesDir, "archive"), java.time.ZoneId.systemDefault())
    a.guard = Some(Storage.guard)
    a

  /** The version of the database on disk when it is newer than this app's. */
  lazy val newerDatabase: Option[Int] = Db.versionOnDisk(instance).filter(_ > Db.Version)

  /** The `:maintenance` process (archive maintenance), not the app's main one. */
  lazy val isMaintenanceProcess: Boolean = Application.getProcessName.endsWith(":maintenance")

  /** Background thread for the UI's DB and file I/O so the UI never blocks.
    * The logger does not use it ([[LoggerWrites]]), nor does archiving
    * (another process).
    */
  lazy val io: ExecutorService = Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-io"))
  def zone: ZoneId = ZoneId.systemDefault()

/** Persistent user settings. */
object Settings:
  private def prefs: SharedPreferences = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  def mode: LoggingMode = LoggingMode.fromKey(prefs.getString("mode", LoggingMode.Balanced.key))
  /** Sets the mode: the storage floor for it is written first (design 6.8), off the main thread. */
  def mode_=(m: LoggingMode): Unit = Storage.changeMode(m)
  /** Stores the mode (after the floor for it is written: [[Storage.changeMode]]). */
  private[ashidori] def storeMode(m: LoggingMode): Unit = prefs.edit().putString("mode", m.key).commit()

  /** First-run onboarding finished. */
  def onboarded: Boolean = prefs.getBoolean("onboarded", false)
  def onboarded_=(b: Boolean): Unit = prefs.edit().putBoolean("onboarded", b).apply()

  def loggingEnabled: Boolean = prefs.getBoolean("enabled", false)
  def loggingEnabled_=(b: Boolean): Unit =
    val e = prefs.edit().putBoolean("enabled", b)
    if b && !loggingEnabled then e.putLong("enabledSince", System.currentTimeMillis())
    e.apply()

  /** Show the estimates from this device's records (battery use by what
    * the logger does, positioning by screen state): experimental, off until
    * they are verified on real devices. Recording does not depend on it.
    */
  def showEstimates: Boolean = prefs.getBoolean("showEstimates", false)
  def showEstimates_=(b: Boolean): Unit = prefs.edit().putBoolean("showEstimates", b).apply()

  /** When recording was last switched on (0 if unknown). */
  def loggingSince: Long = prefs.getLong("enabledSince", 0L)

  def burstUntil: Long = prefs.getLong("burstUntil", 0L)
  def burstUntil_=(t: Long): Unit = prefs.edit().putLong("burstUntil", t).apply()

  def lastDetailStart: Long = prefs.getLong("lastDetailStart", 0L)
  def lastDetailStart_=(t: Long): Unit = prefs.edit().putLong("lastDetailStart", t).apply()

  /** The external-send settings (docs/design/outbound-and-offline.md):
    * per destination, when and how finely it may be sent to, the pause over
    * them, consents of map surfaces and the generations of permits
    * ([[SendGate]]). Read from [[ExternalSettings]]' keys fail-safe, migrated
    * from `blockExternalLocation` the first time, and stored in one write
    * with the values older versions read. Work in flight follows a change
    * at once ([[Net]]).
    *
    * It does not cover the OS location services (fixes come from the fused
    * / network providers as the device settings allow), the sync server,
    * or what the user hands to another app (`geo:`, sharing an export).
    */
  lazy val gate: SendGate =
    import MapConsents.Stored
    def read[A](key: String)(get: => A): Stored[A] =
      if !prefs.contains(key) then Stored.Missing
      else try Stored.Ok(get) catch case _: ClassCastException => Stored.Bad
    def str(key: String) = read(key)(prefs.getString(key, ""))
    def bool(key: String) = read(key)(prefs.getBoolean(key, true))
    import ExternalSettings.*
    val values = Values(str(PolicyKey), str(ConsentsKey), str(GensKey), bool(PausedKey), bool(LegacyBlockKey), str(LegacyConsentsKey))
    val rnd = java.security.SecureRandom()
    val (state, writes) = load(values, ui.MapStyle.Surfaces, id => ui.MapStyle.fromConsent(id).flatMap(_.dest), rnd)
    writes.foreach(store)
    SendGate(state, s => store(ExternalSettings.writes(s)), rnd)

  /** Writes the external-send settings at once (work cancelled after a
    * change must find the new generations stored).
    */
  private def store(w: ExternalSettings.Writes): Unit =
    val e = prefs.edit()
    w.strings.foreach((k, v) => e.putString(k, v))
    w.booleans.foreach((k, v) => e.putBoolean(k, v))
    e.commit()

  /** The marks of MapLibre's requests ([[TokenTable]]), kept across restarts. */
  lazy val tokens: TokenTable =
    val stored = try Option(prefs.getString(ExternalSettings.TokensKey, null)) catch case _: ClassCastException => None
    TokenTable(stored.fold(Map.empty)(TokenTable.read), t => prefs.edit().putString(ExternalSettings.TokensKey, TokenTable.encode(t)).apply())

  /** Upgrades stored settings; call once at start. */
  def migrate(): Unit =
    // 「近くの施設を OpenStreetMap で検索」 (onlineVenues) merged into
    // blockExternalLocation. Whoever had turned venue search off did not
    // want the position sent automatically: keep the stricter setting.
    if prefs.contains("onlineVenues") then
      val e = prefs.edit()
      if !prefs.getBoolean("onlineVenues", true) then e.putBoolean("blockExternalLocation", true)
      e.remove("onlineVenues").commit()
    // Then the per-destination settings, from blockExternalLocation the
    // first time (on reading them).
    val _ = gate
