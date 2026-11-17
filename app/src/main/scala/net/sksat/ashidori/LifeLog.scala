package net.sksat.ashidori

import android.Manifest
import android.app.{ActivityManager, Notification, NotificationChannel, NotificationManager, PendingIntent}
import android.app.usage.UsageStatsManager
import android.content.{Context, Intent}
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import ashidori.core.*

/** Ring log of the logger's lifecycle (see [[LifeEvent]]): a small append-only
  * file in the app's private storage, compacted to the newest [[Keep]]
  * entries. Written synchronously so an entry made just before a crash is
  * kept. No positions are written.
  */
object LifeLog:
  val Keep = 600
  private def ctx: Context = AshidoriApp.app
  private def file = File(ctx.getFilesDir, "lifecycle.log")
  private def prefs = ctx.getSharedPreferences("lifelog", Context.MODE_PRIVATE)
  private var lineCount = -1

  def add(kind: LifeKind, detail: String = "", time: Long = System.currentTimeMillis()): Unit = synchronized {
    val e = LifeEvent(time, kind, LifeEvent.clean(detail).take(400))
    Log.i(s"life ${kind.key}: ${e.detail}")
    try
      // The maintenance process writes here too: appends and compaction
      // hold a file lock shared by the processes.
      scala.util.Using.resource(java.io.RandomAccessFile(File(ctx.getFilesDir, "lifecycle.lock"), "rw")) { lock =>
        val l = lock.getChannel.lock()
        try
          val f = file
          if lineCount < 0 then lineCount = if f.exists then readLines(f).size else 0
          val out = FileOutputStream(f, true)
          try out.write((LifeEvent.encode(e) + "\n").getBytes(UTF_8))
          finally out.close()
          lineCount += 1
          if LifeEvent.needsCompaction(lineCount, Keep) then
            val kept = LifeEvent.compact(readLines(f), Keep)
            val tmp = File(f.getPath + ".tmp")
            java.nio.file.Files.write(tmp.toPath, kept.map(_ + "\n").mkString.getBytes(UTF_8))
            if !tmp.renameTo(f) then throw java.io.IOException("rename failed")
            lineCount = kept.size
        finally l.release()
      }
    catch case ex: Exception => Log.w("lifecycle log write failed", ex)
  }

  /** All entries, oldest first. */
  def read(): Vector[LifeEvent] = synchronized {
    try
      val f = file
      if !f.exists then Vector.empty else readLines(f).flatMap(LifeEvent.decode).sortBy(_.time)
    catch case ex: Exception =>
      Log.w("lifecycle log read failed", ex)
      Vector.empty
  }

  private def readLines(f: File): Vector[String] =
    java.nio.file.Files.readAllLines(f.toPath, UTF_8).asScala.toVector.filter(_.nonEmpty)

  def describe(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"

  // --- what the OS says -----------------------------------------------------------

  /** Logs crashes on any thread before the process dies. */
  def installCrashHandler(): Unit =
    val prev = Thread.getDefaultUncaughtExceptionHandler
    Thread.setDefaultUncaughtExceptionHandler { (t, e) =>
      try
        val at = e.getStackTrace.headOption.map(s => s" at ${s.getClassName}.${s.getMethodName}:${s.getLineNumber}").getOrElse("")
        val proc = if AshidoriApp.isMaintenanceProcess then "整理処理 " else ""
        add(LifeKind.Crash, s"$proc${t.getName}: ${describe(e)}$at")
      catch case _: Throwable => ()
      if prev != null then prev.uncaughtException(t, e)
    }

  /** Logs process exits the OS recorded since the last check
    * (ApplicationExitInfo), at the time they happened.
    */
  def recordExitReasons(): Unit = synchronized {
    try
      val am = ctx.getSystemService(classOf[ActivityManager])
      val seen = prefs.getLong("exitSeen", 0L)
      val infos = am.getHistoricalProcessExitReasons(ctx.getPackageName, 0, 0).asScala
        .filter(_.getTimestamp > seen).sortBy(_.getTimestamp)
      for i <- infos do
        val desc = Option(i.getDescription).filter(_.nonEmpty).map(" " + _).getOrElse("")
        val proc = if i.getProcessName != null && i.getProcessName.endsWith(":maintenance") then "整理処理 " else ""
        add(LifeKind.ProcessExit,
          s"$proc${PlatformCodes.exitReason(i.getReason)} importance=${PlatformCodes.importance(i.getImportance)} status=${i.getStatus}$desc",
          i.getTimestamp)
      if infos.nonEmpty then prefs.edit().putLong("exitSeen", infos.map(_.getTimestamp).max).apply()
    catch case ex: Exception => Log.w("exit reasons", ex)
  }

  /** What decides whether recording may run and restart in the background. */
  final case class Health(
      fineLocation: Boolean,
      backgroundLocation: Boolean,
      batteryExempt: Boolean,
      backgroundRestricted: Boolean,
      standbyBucket: Int,
      locationOn: Boolean,
      notifications: Boolean,
      powerSave: Boolean
  ):
    def summary: String =
      Seq(
        s"位置=${if fineLocation then if backgroundLocation then "常に許可" else "使用中のみ" else "なし"}",
        s"電池の最適化=${if batteryExempt then "対象外" else "最適化"}",
        s"バックグラウンド制限=${if backgroundRestricted then "あり" else "なし"}",
        s"バケット=${PlatformCodes.standbyBucket(standbyBucket)}",
        s"位置情報=${if locationOn then "オン" else "オフ"}",
        s"通知=${if notifications then "許可" else "なし"}"
      ).mkString(" ")

  def health(c: Context = ctx): Health =
    def granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val pm = c.getSystemService(classOf[PowerManager])
    val am = c.getSystemService(classOf[ActivityManager])
    Health(
      granted(Manifest.permission.ACCESS_FINE_LOCATION),
      granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
      pm.isIgnoringBatteryOptimizations(c.getPackageName),
      am.isBackgroundRestricted,
      c.getSystemService(classOf[UsageStatsManager]).getAppStandbyBucket,
      c.getSystemService(classOf[LocationManager]).isLocationEnabled,
      granted(Manifest.permission.POST_NOTIFICATIONS),
      pm.isPowerSaveMode
    )

  /** Logs the permission and battery state when it changed since the last
    * check. The standby bucket moves with use, so it is shown but does not
    * count as a change by itself.
    */
  def checkState(where: String): Unit = synchronized {
    try
      val h = health()
      val key = h.copy(standbyBucket = 0, powerSave = false).summary
      if prefs.getString("state", "") != key then
        prefs.edit().putString("state", key).apply()
        add(LifeKind.State, s"$where: ${h.summary}")
    catch case ex: Exception => Log.w("state check", ex)
  }

  // --- telling the user ---------------------------------------------------------------

  val ChannelAlerts = "alerts"
  private val StoppedId = 2

  /** Recording stopped against the user's wish and could not be restarted
    * from the background: ask them to open the app (which restarts it).
    */
  def notifyStopped(c: Context, why: String): Unit =
    try
      val nm = c.getSystemService(classOf[NotificationManager])
      nm.createNotificationChannel(NotificationChannel(ChannelAlerts, "記録の停止", NotificationManager.IMPORTANCE_DEFAULT))
      val open = PendingIntent.getActivity(c, 4,
        Intent(c, classOf[MainActivity]).putExtra(MainActivity.ExtraOpenLogger, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
      nm.notify(StoppedId, Notification.Builder(c, ChannelAlerts)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("記録が止まっています")
        .setContentText(s"タップすると再開します（$why）")
        .setContentIntent(open)
        .setAutoCancel(true)
        .build())
    catch case ex: Exception => Log.w("stopped notification", ex)

  def clearStopped(c: Context): Unit =
    try c.getSystemService(classOf[NotificationManager]).cancel(StoppedId)
    catch case _: Exception => ()
