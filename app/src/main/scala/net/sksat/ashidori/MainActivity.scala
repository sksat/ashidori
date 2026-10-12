package net.sksat.ashidori

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.{Bundle, PowerManager}
import android.provider.Settings as AndroidSettings
import android.view.{View, ViewGroup}
import android.widget.{EditText, FrameLayout, LinearLayout, Toast}
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.LocalDate
import ashidori.core.*
import ui.*
import ui.Ui.*

class MainActivity extends ComponentActivity:
  private given Ctx = this
  var selectedDate: LocalDate = LocalDate.now(AshidoriApp.zone)

  private var screens: Map[Int, Screen] = Map.empty
  private var mapScreen: MapScreen = null
  private var diagnostics: DiagnosticsScreen = null
  private var current: Option[Screen] = None
  private var content: FrameLayout = null
  private var nav: BottomNavigationView = null

  private var afterPermissions: () => Unit = () => ()
  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), (_: java.util.Map[String, java.lang.Boolean]) => onPermissionsResult())
  private val backgroundLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission(), (_: java.lang.Boolean) => finishPermissions())

  override def onCreate(savedInstanceState: Bundle): Unit =
    DynamicColors.applyToActivityIfAvailable(this)
    super.onCreate(savedInstanceState)
    content = FrameLayout(this)
    nav = BottomNavigationView(this)
    val tabs = Seq(
      (1, "タイムライン", R.drawable.ic_timeline),
      (2, "マップ", R.drawable.ic_map),
      (3, "チェックイン", R.drawable.ic_checkin),
      (4, "インサイト", R.drawable.ic_insights),
      (5, "ロガー", R.drawable.ic_logger)
    )
    for (id, title, icon) <- tabs do nav.getMenu.add(0, id, id, title).setIcon(icon)
    mapScreen = MapScreen(this, savedInstanceState)
    screens = Map(
      1 -> TimelineScreen(this),
      2 -> mapScreen,
      3 -> CheckInScreen(this),
      4 -> InsightsScreen(this),
      5 -> LoggerScreen(this)
    )
    diagnostics = DiagnosticsScreen(this)
    nav.setOnItemSelectedListener(item => { show(screens(item.getItemId)); true })

    val root = vbox(content, nav)
    content.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.setBackgroundColor(color(attr.surface))
    root.setOnApplyWindowInsetsListener { (v, insets) =>
      val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, 0)
      nav.setPadding(0, 0, 0, bars.bottom)
      insets
    }
    setContentView(root)
    // Open the logger first until logging has been enabled once.
    nav.setSelectedItemId(if Settings.loggingEnabled then 1 else 5)
    if Settings.loggingEnabled && !LoggerState.running then
      ensurePermissions(() => LoggerService.send(this, LoggerService.ActionStart))

  private def show(s: Screen): Unit =
    current.foreach(_.onHide())
    content.removeAllViews()
    content.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    current = Some(s)
    s.onShow()

  def showDiagnostics(): Unit = show(diagnostics)
  def closeDiagnostics(): Unit = show(screens(nav.getSelectedItemId))

  override def onBackPressed(): Unit =
    if current.contains(diagnostics) then closeDiagnostics() else super.onBackPressed()

  override def onStart(): Unit =
    super.onStart()
    mapScreen.onStart()
  override def onResume(): Unit =
    super.onResume()
    mapScreen.onResume()
    current.foreach(_.onShow())
  override def onPause(): Unit =
    mapScreen.onPause()
    super.onPause()
  override def onStop(): Unit =
    mapScreen.onStop()
    super.onStop()
  override def onLowMemory(): Unit =
    super.onLowMemory()
    mapScreen.onLowMemory()
  override def onSaveInstanceState(out: Bundle): Unit =
    super.onSaveInstanceState(out)
    mapScreen.onSaveInstanceState(out)
  override def onDestroy(): Unit =
    current.foreach(_.onHide())
    screens.values.foreach(_.onDestroy())
    super.onDestroy()

  // --- permissions ---------------------------------------------------------------

  private def granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  /** Requests foreground permissions, then background location, then runs `andThen`. */
  def ensurePermissions(andThen: () => Unit): Unit =
    afterPermissions = andThen
    val needed = Seq(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.POST_NOTIFICATIONS,
      Manifest.permission.ACTIVITY_RECOGNITION
    ).filterNot(granted)
    if needed.isEmpty then onPermissionsResult()
    else permissionLauncher.launch(needed.toArray)

  private def onPermissionsResult(): Unit =
    if !granted(Manifest.permission.ACCESS_FINE_LOCATION) then
      Toast.makeText(this, "位置情報の権限が必要です", Toast.LENGTH_LONG).show()
    else if !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) then
      MaterialAlertDialogBuilder(this)
        .setTitle("バックグラウンドでの位置情報")
        .setMessage("画面を閉じている間も記録するため、次の画面で「常に許可」を選んでください。データは端末内に保存されます。")
        .setPositiveButton("設定する", (_, _) => backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        .setNegativeButton("あとで", (_, _) => finishPermissions())
        .show()
    else finishPermissions()

  private def finishPermissions(): Unit =
    val pm = getSystemService(classOf[PowerManager])
    if !pm.isIgnoringBatteryOptimizations(getPackageName) && !getPreferences(0).getBoolean("askedBattery", false) then
      getPreferences(0).edit().putBoolean("askedBattery", true).apply()
      MaterialAlertDialogBuilder(this)
        .setTitle("電池の最適化")
        .setMessage("記録が OS に止められないよう、電池の最適化の対象外にすることをおすすめします。ashidori 自身が静止中は GNSS を止めて消費を抑えます。")
        .setPositiveButton("対象外にする", (_, _) =>
          startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse(s"package:$getPackageName")))
        )
        .setNegativeButton("しない", null)
        .setOnDismissListener(_ => afterPermissions())
        .show()
    else afterPermissions()

  // --- misc actions ---------------------------------------------------------------

  def shareLatestGnssLog(): Unit =
    val dir = File(getExternalFilesDir(null), "gnss")
    Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".txt")).sortBy(-_.lastModified()).headOption match
      case None => Toast.makeText(this, "まだ詳細ログがありません", Toast.LENGTH_SHORT).show()
      case Some(f) =>
        val uri = FileProvider.getUriForFile(this, s"$getPackageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, f.getName))

  def renamePlace(place: Option[Place], done: () => Unit): Unit =
    place.foreach { p =>
      val input = EditText(this)
      input.setText(p.name)
      val kinds = PlaceKind.values
      MaterialAlertDialogBuilder(this)
        .setTitle("場所の名前")
        .setView(padded(vbox(input), 20, 8))
        .setPositiveButton("保存", (_, _) => save(p.copy(name = input.getText.toString.trim), done))
        .setNeutralButton("種別…", (_, _) =>
          MaterialAlertDialogBuilder(this)
            .setTitle("場所の種別")
            .setItems(kinds.map(k => (if k.label.isEmpty then "なし" else k.label): CharSequence), (_, i) => save(p.copy(kind = kinds(i)), done))
            .show()
        )
        .setNegativeButton("キャンセル", null)
        .show()
    }

  private def save(p: Place, done: () => Unit): Unit =
    AshidoriApp.io.execute { () =>
      AshidoriApp.db.updatePlace(p)
      runOnUiThread(() => done())
    }

final class PlaceholderScreen(act: MainActivity, title: String, body: String) extends Screen:
  private given Ctx = act
  val view: View = vbox(card(text(title, 18f, bold = true), spacer(4), caption(body)))
