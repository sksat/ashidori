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
  private val EmergencyId = 2003
  val DayMs: Long = 24 * 3_600_000L
  /** Quarantined files (never deleted automatically) above this: a warning. */
  val QuarantineWarnBytes: Long = 512L << 20
  /** Free space below this: a warning (the logger needs room). */
  val LowSpaceBytes: Long = 512L << 20

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
        "異常終了が続いたため、記録の整理と同期を停止（記録は継続）")

  private def nm = ctx.getSystemService(classOf[NotificationManager])

  /** The stall alert's notification and its state on disk
    * ([[WriteStall.Alert]] brings them to what its monitor says at every
    * check). `stallKinds` holds WriteKind keys.
    */
  object StallEffects extends WriteStall.Effects:
    def posted: Boolean = Try(nm.getActiveNotifications.exists(_.getId == StallId)).getOrElse(false)
    def post(): Boolean = Alerts.notify(StallId, "記録の保存が停止")
    def cancel(): Boolean =
      val r = Try(nm.cancel(StallId))
      r.failed.foreach(e => Log.w("cancelling the stall notification failed", e))
      r.isSuccess
    def stored: Option[Set[String]] =
      Option.when(prefs.getBoolean("stallUp", false))(storedKinds)
    def store(state: Option[Set[String]]): Boolean =
      val e = prefs.edit()
      state match
        case Some(kinds) =>
          if !prefs.getBoolean("stallUp", false) then e.putLong("stallAt", System.currentTimeMillis())
          e.putBoolean("stallUp", true).putStringSet("stallKinds", kinds.asJava)
        case None => e.putBoolean("stallUp", false).remove("stallKinds")
      val ok = e.commit()
      if !ok then Log.w("keeping the stall alert on disk failed")
      ok

  /** The kinds kept with the alert, as WriteKind keys (labels an earlier
    * build kept are migrated; anything else is dropped).
    */
  private def storedKinds: Set[String] =
    Option(prefs.getStringSet("stallKinds", null)).map(_.asScala.toSet).getOrElse(Set.empty)
      .flatMap(k => WriteKind.fromStored(k).map(_.key))

  /** A stall alert raised before this process started and not cleared: the
    * kinds it named. An alert of 0.21.0 (not kept on disk) is found by its
    * notification, still posted, and names no kind.
    */
  def restoredStall(): Option[Set[String]] =
    StallEffects.stored.orElse(Option.when(StallEffects.posted)(Set.empty[String]))

  def lastStall: Long = prefs.getLong("stallAt", 0L)

  /** The logger's emergency path began (a notification) or ended (cancelled). */
  def emergency(on: Boolean): Unit =
    if on then notify(EmergencyId, EmergencyText)
    else scala.util.Try(nm.cancel(EmergencyId))

  val EmergencyText = "空き容量不足: 記録を退避中"

  /** The banner line for the status tab, if there was trouble in the last
    * day.
    */
  def issue(now: Long = System.currentTimeMillis()): Option[String] =
    val crashes = recentCrashes(now)
    val stalled = now - lastStall < DayMs || prefs.getBoolean("stallUp", false)
    if Try(Storage.emergency.active).getOrElse(false) then Some(EmergencyText)
    else if CommitStatus.stuck().isDefined then Some("記録の整理が停止（要確認）")
    else if Attention.unseen then Some("記録の整理で隔離したファイルあり（要確認）")
    else if Attention.quarantineBytes() > QuarantineWarnBytes then Some("隔離したファイルが大きい（要確認）")
    else if ctx.getFilesDir.getUsableSpace < LowSpaceBytes then Some("端末の空き容量が少ない（要確認）")
    else if Maintenance.SafeMode.active then Some("異常終了が続いたため、記録の整理と同期を停止中")
    else if crashes == 0 && !stalled then None
    else if crashes > 0 && stalled then Some(s"異常終了 ${crashes} 回・保存の停止あり")
    else if crashes > 0 then Some(s"直近24時間に異常終了 ${crashes} 回")
    else Some("記録が保存されなかった時間あり")

  def openDiagnostics(c: Context): PendingIntent =
    PendingIntent.getActivity(c, 3,
      Intent(c, classOf[MainActivity]).putExtra(MainActivity.ExtraOpenDiagnostics, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)

  private def notify(id: Int, text: String): Boolean =
    val posted = Try {
      val c = ctx
      val n = Notification.Builder(c, Channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text)
        .setContentIntent(openDiagnostics(c))
        .setAutoCancel(true)
        .build()
      c.getSystemService(classOf[NotificationManager]).notify(id, n)
    }
    posted.failed.foreach(e => Log.w("alert notification failed", e))
    posted.isSuccess
