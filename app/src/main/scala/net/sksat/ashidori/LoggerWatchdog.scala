package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import ashidori.core.LifeKind

/** Recording should run until the user stops it on purpose. If the OS killed
  * the service and did not restart it, bring it back. Starting a foreground
  * service from the background is allowed while the app is exempt from
  * battery optimizations, which onboarding asks for.
  *
  * Each run is noted in the lifecycle log when what it found changed, and at
  * least every few hours, so the log shows when the app was alive.
  */
class LoggerWatchdog(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    val c = getApplicationContext
    val enabled = Settings.loggingEnabled
    val running = LoggerState.running
    LifeLog.checkState("定期確認")
    val finding =
      if !enabled then "記録オフ"
      else if running then "記録サービス動作中"
      else "記録サービスなし → 再開を要求"
    val now = System.currentTimeMillis()
    val fixAge = scala.util.Try(AshidoriApp.db.lastFix()).toOption.flatten.map(f => ui.Ui.fmtDuration(now - f.time)).getOrElse("なし")
    val detail = s"$finding・最後の測位 $fixAge 前・${LoggerState.plan.map(_.describe).getOrElse("-")}・動き ${LoggerState.motion.label}" +
      (if LoggerState.sensors.motionTrigger then "・動き検知待ち" else "")
    if LoggerWatchdog.shouldLog(finding, now) then LifeLog.add(LifeKind.Watchdog, detail)
    if enabled && !running then LoggerService.restart(c, "定期確認")
    AshidoriApp.io.execute(() => AshidoriApp.maintainArchive())
    ListenableWorker.Result.success()

object LoggerWatchdog:
  /** A quiet run is noted at most this often. */
  private val QuietLogMs = 3 * 3_600_000L

  private def prefs = AshidoriApp.app.getSharedPreferences("lifelog", Context.MODE_PRIVATE)

  private def shouldLog(finding: String, now: Long): Boolean =
    val p = prefs
    val changed = p.getString("watchdogFinding", "") != finding
    if changed || now - p.getLong("watchdogLogged", 0L) >= QuietLogMs then
      p.edit().putString("watchdogFinding", finding).putLong("watchdogLogged", now).apply()
      true
    else false

  def schedule(ctx: Context): Unit =
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
      "logger-watchdog",
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequest.Builder(classOf[LoggerWatchdog], 15, TimeUnit.MINUTES).build()
    )
