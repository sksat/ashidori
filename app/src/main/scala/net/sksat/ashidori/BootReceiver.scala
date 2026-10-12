package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent}

/** Restarts logging after reboot or app update if it was enabled. */
class BootReceiver extends BroadcastReceiver:
  override def onReceive(ctx: Context, intent: Intent): Unit =
    if Settings.loggingEnabled then
      try LoggerService.send(ctx, LoggerService.ActionStart)
      catch case e: Exception => Log.w("could not restart logging", e)
