package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent}
import ashidori.core.LifeKind

/** Restarts logging after reboot or app update if it was enabled. */
class BootReceiver extends BroadcastReceiver:
  override def onReceive(ctx: Context, intent: Intent): Unit =
    val on = Settings.loggingEnabled
    LifeLog.add(LifeKind.Boot, s"${Option(intent.getAction).map(_.split('.').last).getOrElse("")} 記録=${if on then "オン" else "オフ"}")
    if on then LoggerService.restart(ctx, "起動・更新")
