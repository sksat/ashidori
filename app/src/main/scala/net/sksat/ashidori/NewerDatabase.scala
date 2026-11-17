package net.sksat.ashidori

import android.app.{Notification, NotificationChannel, NotificationManager, PendingIntent}
import android.content.{Context, Intent}

/** The database is of a newer app version (this one was installed over it):
  * nothing opens it, and the user is told once. The notification is posted
  * once per database version and app build, and stays (not cancelled on
  * tap) until an app that can open the database clears it at its start.
  */
object NewerDatabase:
  private val Id = 2003
  private def prefs(c: Context) = c.getSharedPreferences("lifelog", Context.MODE_PRIVATE)

  /** Posts the notification unless it was already posted for this database and build. */
  def notifyOnce(c: Context, onDisk: Int): Unit = synchronized {
    val key = s"$onDisk/${Maintenance.build(c)}"
    if prefs(c).getString("newerDatabase", "") != key then
      try
        val nm = c.getSystemService(classOf[NotificationManager])
        nm.createNotificationChannel(NotificationChannel(LifeLog.ChannelAlerts, "記録の停止", NotificationManager.IMPORTANCE_DEFAULT))
        // Request code: see LoggerService.Codes.
        val open = PendingIntent.getActivity(c, 7, Intent(c, classOf[MainActivity]), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(Id, Notification.Builder(c, LifeLog.ChannelAlerts)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle("記録停止")
          .setContentText(Db.NewerMessage)
          .setStyle(Notification.BigTextStyle().bigText(Db.NewerMessage))
          .setContentIntent(open)
          .setAutoCancel(false)
          .build())
        prefs(c).edit().putString("newerDatabase", key).commit()
        LifeLog.add(ashidori.core.LifeKind.Note, s"データベースが新しい版（$onDisk、この版は ${Db.Version}）のため記録を停止")
      catch case e: Exception => Log.w("newer database notification", e)
  }

  /** The database opens again (the app was updated): the notification goes. */
  def clear(c: Context): Unit =
    if prefs(c).contains("newerDatabase") then
      try c.getSystemService(classOf[NotificationManager]).cancel(Id)
      catch case _: Exception => ()
      prefs(c).edit().remove("newerDatabase").apply()
