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
    val nm = getSystemService(classOf[NotificationManager])
    nm.createNotificationChannel(
      NotificationChannel(AshidoriApp.ChannelLogging, "記録中", NotificationManager.IMPORTANCE_LOW)
    )
    LoggerWatchdog.schedule(this)
    Settings.migrate()
    // Copy the offline basemap out of the APK before the first map needs it.
    Thread(() => { Basemap.prepare(); () }, "ashidori-basemap").start()
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
    * app's own initiative: no platform geocoder, no automatic venue search
    * (OpenStreetMap), and maps and their snapshots drawn from the on-device
    * basemap only. An explicit user action may still send: a venue search,
    * or the online map in one map view ("この範囲を詳しい地図で表示").
    * Off: venues are searched automatically, the platform geocoder fills
    * names the offline index lacks, and the chosen base map is used.
    */
  def blockExternalLocation: Boolean = prefs.getBoolean("blockExternalLocation", true)
  def blockExternalLocation_=(b: Boolean): Unit = prefs.edit().putBoolean("blockExternalLocation", b).apply()

  /** Upgrades stored settings; call once at start. */
  def migrate(): Unit =
    // 「近くの施設を OpenStreetMap で検索」 (onlineVenues) merged into
    // blockExternalLocation. Whoever had turned venue search off did not
    // want the position sent automatically: keep the stricter setting.
    if prefs.contains("onlineVenues") then
      val e = prefs.edit()
      if !prefs.getBoolean("onlineVenues", true) then e.putBoolean("blockExternalLocation", true)
      e.remove("onlineVenues").apply()
