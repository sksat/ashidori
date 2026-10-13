package net.sksat.ashidori

object Log:
  private val Tag = "ashidori"
  def d(msg: String): Unit = android.util.Log.d(Tag, msg)
  def i(msg: String): Unit = android.util.Log.i(Tag, msg)
  def w(msg: String, e: Throwable = null): Unit = android.util.Log.w(Tag, msg, e)
  def e(msg: String, e: Throwable = null): Unit = android.util.Log.e(Tag, msg, e)

object BuildInfo:
  def versionName: String =
    val app = AshidoriApp.app
    app.getPackageManager.getPackageInfo(app.getPackageName, 0).versionName
  def versionCode: Long =
    val app = AshidoriApp.app
    app.getPackageManager.getPackageInfo(app.getPackageName, 0).getLongVersionCode
