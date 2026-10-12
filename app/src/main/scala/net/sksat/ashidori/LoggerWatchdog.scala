package net.sksat.ashidori

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Recording should run until the user stops it on purpose. If the OS killed
  * the service and did not restart it, bring it back. Starting a foreground
  * service from the background is allowed while the app is exempt from
  * battery optimizations, which onboarding asks for.
  */
class LoggerWatchdog(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  override def doWork(): ListenableWorker.Result =
    if Settings.loggingEnabled && !LoggerState.running then
      try LoggerService.send(getApplicationContext, LoggerService.ActionStart)
      catch case e: Exception => Log.w("watchdog could not restart logging", e)
    ListenableWorker.Result.success()

object LoggerWatchdog:
  def schedule(ctx: Context): Unit =
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
      "logger-watchdog",
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequest.Builder(classOf[LoggerWatchdog], 15, TimeUnit.MINUTES).build()
    )
