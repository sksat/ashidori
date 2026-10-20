package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent}
import java.time.LocalDate

/** Debug builds only (declared in the debug manifest; ignored when the app
  * is not debuggable): test hooks that need no activity, for emulators
  * where starting one is unreliable.
  *
  *   am broadcast -n <pkg>/net.sksat.ashidori.DebugReceiver -a ashidori.debug.SYNTH --ei hours 5 [--ei startMinute 600] [--ei daysAgo 1] (or --ei minutes N)
  *   ... -a ashidori.debug.ODD [--ei daysAgo 1]       raw lines that do not convert, and late ones
  *   ... -a ashidori.debug.LOG --es mode max        start recording in a mode
  *   ... -a ashidori.debug.STOP                       stop recording
  *   ... -a ashidori.debug.MAINTAIN                   archive maintenance now (a job)
  *   ... -a ashidori.debug.MAINTAIN_NOW               the same outside JobScheduler (DebugMaintenanceService)
  *   ... -a ashidori.debug.CRASH                      crash the main process
  *   ... -a ashidori.debug.THREADS                    log every thread's stack
  *   ... -a ashidori.debug.BACKUP --es file name.zip  backup into the external files dir
  */
class DebugReceiver extends BroadcastReceiver:
  override def onReceive(c: Context, intent: Intent): Unit =
    if (c.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0 then return
    intent.getAction match
      case "ashidori.debug.SYNTH" =>
        val h = if intent.hasExtra("minutes") then intent.getIntExtra("minutes", 0) / 60.0 else intent.getIntExtra("hours", 5).toDouble
        val at = intent.getIntExtra("startMinute", 600)
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() => { DebugData.insertRaw(LocalDate.now(AshidoriApp.zone).minusDays(ago), h, at); () }, "ashidori-synth").start()
      case "ashidori.debug.ODD" =>
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() => Log.i(s"odd raw lines: ${DebugData.insertOdd(LocalDate.now(AshidoriApp.zone).minusDays(ago))}"), "ashidori-odd").start()
      case "ashidori.debug.LOG" =>
        Settings.onboarded = true
        Settings.mode = ashidori.core.LoggingMode.fromKey(Option(intent.getStringExtra("mode")).getOrElse("max"))
        Settings.loggingEnabled = true
        LoggerService.send(c, LoggerService.ActionStart)
      case "ashidori.debug.STOP" =>
        Settings.loggingEnabled = false
        LoggerService.send(c, LoggerService.ActionStop)
      case "ashidori.debug.MAINTAIN" => Maintenance.runNow(c)
      case "ashidori.debug.MAINTAIN_NOW" => c.startService(Intent(c, classOf[DebugMaintenanceService]))
      case "ashidori.debug.CRASH" =>
        // An uncaught exception in the main process (tests the alerts).
        android.os.Handler(android.os.Looper.getMainLooper).post(() => throw IllegalStateException("debug crash"))
      case "ashidori.debug.THREADS" =>
        import scala.jdk.CollectionConverters.*
        for (t, st) <- Thread.getAllStackTraces.asScala do
          Log.i(s"thread ${t.getName} ${t.getState}\n  " + st.take(12).mkString("\n  "))
      case "ashidori.debug.BACKUP" =>
        val f = java.io.File(c.getExternalFilesDir(null), Option(intent.getStringExtra("file")).getOrElse(Backup.defaultName()))
        Thread(() =>
          try
            val n = Backup.write(c, android.net.Uri.fromFile(f), (d, t) => Log.i(s"backup $d / $t"))
            Log.i(s"backup done: $n bytes to $f")
          catch case e: Throwable => Log.e("backup failed", e)
        , "ashidori-backup").start()
      case other => Log.w(s"debug receiver: unknown action $other")

/** Debug builds only (declared in the debug manifest, in the `:maintenance`
  * process): runs archive maintenance at once, outside JobScheduler and its
  * quota, for tests on emulators. `... -a ashidori.debug.MAINTAIN_NOW`
  */
class DebugMaintenanceService extends android.app.Service:
  override def onBind(i: Intent): android.os.IBinder = null
  override def onStartCommand(i: Intent, flags: Int, id: Int): Int =
    if (getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 then
      Thread(() =>
        try while Maintenance.run(manual = true, () => false) do ()
        finally stopSelf(id)
      , "ashidori-maintenance").start()
    android.app.Service.START_NOT_STICKY
