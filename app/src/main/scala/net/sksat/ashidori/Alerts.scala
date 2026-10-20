package net.sksat.ashidori

import android.app.{ActivityManager, ApplicationExitInfo, Notification, NotificationChannel, NotificationManager, PendingIntent}
import android.content.{Context, Intent}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import ashidori.core.*

/** Tells the user when recording is in trouble, instead of only logging it:
  * the main process ended abnormally, or logged data stopped reaching the
  * database. Notifications go to their own channel (not the ongoing
  * recording one) and open 設定 › 端末診断; the status tab shows a banner
  * for a day.
  */
object Alerts:
  val Channel = "alerts"
  private val CrashId = 2001
  private val StallId = 2002
  val DayMs: Long = 24 * 3_600_000L

  private def ctx: Context = AshidoriApp.app
  private def prefs = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)

  def createChannel(c: Context): Unit =
    c.getSystemService(classOf[NotificationManager])
      .createNotificationChannel(NotificationChannel(Channel, "記録の異常", NotificationManager.IMPORTANCE_DEFAULT))

  /** Exits of the main process (where the logger runs) that lost it:
    * crashes, ANRs, and low-memory kills while it was in the foreground or
    * running the recording service (a cached process killed for memory is
    * normal).
    */
  def problemExits(): Seq[ApplicationExitInfo] =
    Try {
      val am = ctx.getSystemService(classOf[ActivityManager])
      am.getHistoricalProcessExitReasons(ctx.getPackageName, 0, 0).asScala.toSeq.filter { i =>
        i.getProcessName == ctx.getPackageName && (i.getReason match
          case ApplicationExitInfo.REASON_CRASH | ApplicationExitInfo.REASON_CRASH_NATIVE | ApplicationExitInfo.REASON_ANR => true
          case ApplicationExitInfo.REASON_LOW_MEMORY =>
            i.getImportance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
          case _ => false)
      }
    }.getOrElse(Nil)

  def recentCrashes(now: Long = System.currentTimeMillis()): Int = problemExits().count(_.getTimestamp > now - DayMs)

  /** At the main process's start (off the main thread): notifies new
    * abnormal exits once, and switches optional background work off after
    * repeated crashes ([[CrashLoop]]).
    */
  def checkExits(): Unit =
    val now = System.currentTimeMillis()
    val exits = problemExits()
    val seen = prefs.getLong("exitSeen", 0L)
    val fresh = exits.filter(_.getTimestamp > seen)
    if fresh.nonEmpty then
      prefs.edit().putLong("exitSeen", fresh.map(_.getTimestamp).max).apply()
      val n = exits.count(_.getTimestamp > now - DayMs)
      if n > 0 then notify(CrashId, s"ashidori が異常終了しました（直近24時間で ${n} 回）")
    val since = math.max(Maintenance.build(), Maintenance.SafeMode.resetAt)
    if !Maintenance.SafeMode.active && CrashLoop.tripped(exits.map(_.getTimestamp), now, since) then
      Maintenance.SafeMode.set(ctx)
      LifeLog.add(LifeKind.SafeMode,
        s"異常終了が続いたため、記録のまとめと同期を停止（記録は継続。アプリの更新か「記録をまとめ直す」で再開）")

  def stall(): Unit =
    prefs.edit().putLong("stallAt", System.currentTimeMillis()).apply()
    notify(StallId, "記録が保存されていません（端末診断で確認）")

  def clearStall(): Unit =
    Try(ctx.getSystemService(classOf[NotificationManager]).cancel(StallId))

  def lastStall: Long = prefs.getLong("stallAt", 0L)

  /** The banner line for the status tab, if there was trouble in the last
    * day.
    */
  def issue(now: Long = System.currentTimeMillis()): Option[String] =
    val crashes = recentCrashes(now)
    val stalled = now - lastStall < DayMs
    if Maintenance.SafeMode.active then Some("異常終了が続いたため、自動のまとめ・同期を停止中")
    else if crashes == 0 && !stalled then None
    else if crashes > 0 && stalled then Some(s"異常終了 ${crashes} 回・保存の停止がありました")
    else if crashes > 0 then Some(s"直近24時間に異常終了が ${crashes} 回ありました")
    else Some("記録が保存されない時間がありました")

  def openDiagnostics(c: Context): PendingIntent =
    PendingIntent.getActivity(c, 3,
      Intent(c, classOf[MainActivity]).putExtra(MainActivity.ExtraOpenDiagnostics, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)

  private def notify(id: Int, text: String): Unit =
    Try {
      val c = ctx
      val n = Notification.Builder(c, Channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text)
        .setContentIntent(openDiagnostics(c))
        .setAutoCancel(true)
        .build()
      c.getSystemService(classOf[NotificationManager]).notify(id, n)
    }.failed.foreach(e => Log.w("alert notification failed", e))
