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
  def loggingEnabled_=(b: Boolean): Unit = prefs.edit().putBoolean("enabled", b).apply()

  def burstUntil: Long = prefs.getLong("burstUntil", 0L)
  def burstUntil_=(t: Long): Unit = prefs.edit().putLong("burstUntil", t).apply()

  def lastDetailStart: Long = prefs.getLong("lastDetailStart", 0L)
  def lastDetailStart_=(t: Long): Unit = prefs.edit().putLong("lastDetailStart", t).apply()

  /** Fetch nearby venues from OpenStreetMap (Overpass) for check-ins. */
  def onlineVenues: Boolean = prefs.getBoolean("onlineVenues", true)
  def onlineVenues_=(b: Boolean): Unit = prefs.edit().putBoolean("onlineVenues", b).apply()
