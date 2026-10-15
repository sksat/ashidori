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
    // Lifecycle log for 設定 › 端末診断: crashes on any thread, this start,
    // and the exits the OS recorded since the last start.
    LifeLog.installCrashHandler()
    LifeLog.add(LifeKind.ProcessStart, s"記録=${if Settings.loggingEnabled then "オン" else "オフ"}")
    AshidoriApp.io.execute { () =>
      LifeLog.recordExitReasons()
      LifeLog.checkState("起動時")
    }
    val nm = getSystemService(classOf[NotificationManager])
    nm.createNotificationChannel(
      NotificationChannel(AshidoriApp.ChannelLogging, "記録中", NotificationManager.IMPORTANCE_LOW)
    )
    LoggerWatchdog.schedule(this)
    Settings.migrate()
    // An unpairing cut short by a crash is finished before anything syncs.
    Thread(() => net.sksat.ashidori.sync.SyncConfig.resumeUnpair(this), "ashidori-unpair").start()
    // The offline basemap: drop older versions (and the copy older APKs made),
    // then queue the first download for an unmetered network.
    AshidoriApp.io.execute(() => Basemap.cleanup())
    BasemapDownload.autoStart(this)
    // The offline venue index (施設データ), likewise.
    AshidoriApp.io.execute(() => VenueData.cleanup())
    VenueDownload.autoStart(this)
    // Area names cached from the platform geocoder: recompute on the device.
    AshidoriApp.io.execute(() => Geocoding.migrateCachedNames())
    // Archive finished days (and migrate pre-Parquet data) in the background.
    AshidoriApp.io.execute(() => AshidoriApp.maintainArchive())

object AshidoriApp:
  val ChannelLogging = "logging"
  @volatile private var instance: AshidoriApp = null

  def app: AshidoriApp = instance
  lazy val db: Db = Db(instance)
  lazy val archive: ArchiveStore = ArchiveStore(java.io.File(instance.getFilesDir, "archive"), java.time.ZoneId.systemDefault())

  /** Due archive work; call on [[io]]. */
  def maintainArchive(): Unit =
    try archive.maintain(db, java.io.File(instance.getExternalFilesDir(null), "gnss"), step => io.execute(() => step()))
    catch case e: Exception => Log.w("archive maintenance failed", e)
  /** Single background thread for DB and file I/O so the UI never blocks. */
  lazy val io: ExecutorService = Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-io"))
  def zone: ZoneId = ZoneId.systemDefault()

/** Persistent user settings. */
object Settings:
  private def prefs: SharedPreferences = AshidoriApp.app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  def mode: LoggingMode = LoggingMode.fromKey(prefs.getString("mode", LoggingMode.Balanced.key))
  def mode_=(m: LoggingMode): Unit = prefs.edit().putString("mode", m.key).apply()

  /** First-run onboarding finished. */
  def onboarded: Boolean = prefs.getBoolean("onboarded", false)
  def onboarded_=(b: Boolean): Unit = prefs.edit().putBoolean("onboarded", b).apply()

  def loggingEnabled: Boolean = prefs.getBoolean("enabled", false)
  def loggingEnabled_=(b: Boolean): Unit =
    val e = prefs.edit().putBoolean("enabled", b)
    if b && !loggingEnabled then e.putLong("enabledSince", System.currentTimeMillis())
    e.apply()

  /** When recording was last switched on (0 if unknown). */
  def loggingSince: Long = prefs.getLong("enabledSince", 0L)

  def burstUntil: Long = prefs.getLong("burstUntil", 0L)
  def burstUntil_=(t: Long): Unit = prefs.edit().putLong("burstUntil", t).apply()

  def lastDetailStart: Long = prefs.getLong("lastDetailStart", 0L)
  def lastDetailStart_=(t: Long): Unit = prefs.edit().putLong("lastDetailStart", t).apply()

  /** Never send positions, current or past, to external services on the
    * app's own initiative: no platform geocoder, and maps and their
    * snapshots drawn from the on-device basemap only. An explicit user
    * action may still send: a venue search (「最新を取得」, whatever this
    * setting says; there is no automatic one),
    * the online map on the one map where the user chose it ("この範囲を詳しい
    * 地図で表示" or an online map in its picker; remembered for that map until
    * switched back or this setting changes), opening a position in another app (`geo:`), sharing an
    * export. Off: the platform geocoder fills names the offline index
    * lacks, and the chosen base map is used.
    *
    * It does not cover the OS location services: fixes come from the
    * fused / network providers as the device settings allow. Check-ins
    * shared with followers are readable by the server operator and the
    * followers, so they count as sending and need an explicit share action
    * whatever this setting is.
    *
    * Work in flight follows a change at once ([[gate]]).
    */
  def blockExternalLocation: Boolean = gate.block
  def blockExternalLocation_=(b: Boolean): Unit =
    // A change is stored with the (cleared) map consents in one write.
    if !gate.setBlock(b) then prefs.edit().putBoolean("blockExternalLocation", b).apply()

  /** The setting's generation, grants, the maps' remembered consents and
    * listeners (see [[SendGate]]). The setting and the consents are stored
    * together ([[MapConsents]]), and read back fail-safe.
    */
  lazy val gate: SendGate =
    import MapConsents.Stored
    def read[A](key: String)(get: => A): Stored[A] =
      if !prefs.contains(key) then Stored.Missing
      else try Stored.Ok(get) catch case _: ClassCastException => Stored.Bad
    val (block, consents) = MapConsents.load(
      read("blockExternalLocation")(prefs.getBoolean("blockExternalLocation", true)),
      read("mapConsents")(prefs.getString("mapConsents", "")),
      ui.MapStyle.Surfaces,
      id => ui.MapStyle.fromConsent(id).isDefined
    )
    SendGate(block, consents, (b, c) =>
      prefs.edit().putBoolean("blockExternalLocation", b).putString("mapConsents", MapConsents.encode(c)).apply())

  /** Upgrades stored settings; call once at start. */
  def migrate(): Unit =
    // 「近くの施設を OpenStreetMap で検索」 (onlineVenues) merged into
    // blockExternalLocation. Whoever had turned venue search off did not
    // want the position sent automatically: keep the stricter setting.
    if prefs.contains("onlineVenues") then
      val e = prefs.edit()
      if !prefs.getBoolean("onlineVenues", true) then e.putBoolean("blockExternalLocation", true)
      e.remove("onlineVenues").apply()
