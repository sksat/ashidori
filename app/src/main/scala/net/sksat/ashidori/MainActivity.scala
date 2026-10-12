package net.sksat.ashidori

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.{Gravity, Menu, View, ViewGroup}
import android.widget.{FrameLayout, LinearLayout}
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File
import java.time.LocalDate
import scala.collection.mutable
import ashidori.core.*
import ui.*
import ui.Ui.*

/** Single activity. Three tabs for looking back (timeline, map, insights),
  * a check-in FAB for the one frequent action, and a status chip that leads
  * to recording controls. Settings and tools are pushed on a stack.
  */
class MainActivity extends AppCompatActivity:
  private given Ctx = this
  var selectedDate: LocalDate = LocalDate.now(AshidoriApp.zone)

  private var tabs: Map[Int, Screen] = Map.empty
  private var tabId = MainActivity.TabTimeline
  private val stack = mutable.Stack.empty[Screen]
  private var current: Option[Screen] = None
  private val maps = mutable.ArrayBuffer.empty[TrackMap]

  private var toolbar: MaterialToolbar = null
  private var content: FrameLayout = null
  private var screenHost: FrameLayout = null
  private var fab: ExtendedFloatingActionButton = null
  private var nav: BottomNavigationView = null

  def snackbarAnchor: View = content

  // --- permissions plumbing used by onboarding ------------------------------------

  private var permsDone: () => Unit = () => ()
  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), (_: java.util.Map[String, java.lang.Boolean]) => permsDone())
  private val singleLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission(), (_: java.lang.Boolean) => permsDone())

  private var filePicked: android.net.Uri => Unit = _ => ()
  private val fileLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument(), (uri: android.net.Uri) => if uri != null then filePicked(uri))

  def pickFile(onPicked: android.net.Uri => Unit): Unit =
    filePicked = onPicked
    fileLauncher.launch(Array("application/json", "*/*"))

  def requestPermissions(perms: Seq[String])(done: () => Unit): Unit =
    permsDone = done
    if perms.size == 1 then singleLauncher.launch(perms.head) else permissionLauncher.launch(perms.toArray)

  def granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  def allPermissionsGranted: Boolean =
    MainActivity.RequiredPermissions.forall(granted) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

  /** Runs `andThen` once the permissions logging needs are granted, walking
    * the user through the onboarding permission steps when some are missing.
    */
  def ensurePermissions(andThen: () => Unit): Unit =
    if allPermissionsGranted then andThen()
    else push(OnboardingScreen(this, permissionsOnly = true, onFinish = () => { pop(); andThen() }))

  def startLogging(): Unit =
    ensurePermissions { () =>
      if granted(Manifest.permission.ACCESS_FINE_LOCATION) then
        Settings.loggingEnabled = true
        LoggerService.send(this, LoggerService.ActionStart)
    }

  // --- lifecycle -------------------------------------------------------------------------

  override def onCreate(savedInstanceState: Bundle): Unit =
    // The UI is Japanese: pin the app language so kanji use Japanese glyphs
    // even when the system language is something else (otherwise CJK text
    // falls back to a Chinese font). Respect a choice made in system settings.
    val locales = getSystemService(classOf[android.app.LocaleManager])
    if locales.getApplicationLocales.isEmpty then
      locales.setApplicationLocales(android.os.LocaleList.forLanguageTags("ja-JP"))
    // Brand colors (themes.xml) rather than wallpaper-derived dynamic color.
    super.onCreate(savedInstanceState)

    toolbar = MaterialToolbar(this)
    val searchItem = toolbar.getMenu.add(Menu.NONE, 2, Menu.NONE, "検索")
    searchItem.setIcon(R.drawable.ic_search)
    searchItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
    val settingsItem = toolbar.getMenu.add(Menu.NONE, 1, Menu.NONE, "設定")
    settingsItem.setIcon(R.drawable.ic_settings)
    settingsItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
    toolbar.setOnMenuItemClickListener { item =>
      if item.getItemId == 2 then push(SearchScreen(this)) else push(SettingsScreen(this))
      true
    }
    toolbar.setNavigationOnClickListener(_ => pop())

    screenHost = FrameLayout(this)
    fab = ExtendedFloatingActionButton(this)
    fab.setText("チェックイン")
    fab.setIconResource(R.drawable.ic_checkin)
    fab.setOnClickListener(_ => CheckInSheet(this, None, () => current.foreach(_.onShow())).show())
    val fabLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END)
    fabLp.setMargins(0, 0, dp(16), dp(16))
    content = frame(screenHost)
    content.addView(fab, fabLp)

    nav = BottomNavigationView(this)
    // Always show labels: icon-only tabs made the timeline hard to find.
    nav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED)
    for (id, title, icon) <- Seq(
        (MainActivity.TabTimeline, "タイムライン", R.drawable.ic_timeline),
        (MainActivity.TabInsights, "インサイト", R.drawable.ic_insights),
        (MainActivity.TabLogger, "測位", R.drawable.ic_logger)
      )
    do nav.getMenu.add(0, id, id, title).setIcon(icon)

    tabs = Map(
      MainActivity.TabTimeline -> TimelineScreen(this, savedInstanceState),
      MainActivity.TabInsights -> TimelineScreen(this, savedInstanceState, Seq(Span.Week, Span.Month, Span.Year)),
      MainActivity.TabLogger -> LoggerScreen(this)
    )
    nav.setOnItemSelectedListener { item =>
      tabId = item.getItemId
      stack.clear()
      show(tabs(tabId))
      true
    }

    val root = vbox(toolbar, content, nav)
    content.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.setBackgroundColor(color(attr.surface))
    root.setOnApplyWindowInsetsListener { (v, insets) =>
      val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, 0)
      nav.setPadding(0, 0, 0, bars.bottom)
      insets
    }
    setContentView(root)
    // Dark status bar icons on light surfaces, light icons in dark mode.
    val night = (getResources.getConfiguration.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES
    getWindow.getInsetsController.setSystemBarsAppearance(
      if night then 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
      android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
    )

    getOnBackPressedDispatcher.addCallback(this, new OnBackPressedCallback(true):
      override def handleOnBackPressed(): Unit =
        if stack.nonEmpty && !current.exists(_.isInstanceOf[OnboardingScreen]) then pop()
        else if stack.isEmpty && tabId != MainActivity.TabTimeline then nav.setSelectedItemId(MainActivity.TabTimeline)
        else
          setEnabled(false)
          getOnBackPressedDispatcher.onBackPressed()
          setEnabled(true)
    )

    // Debug builds: `am start ... --ez ashidori.sim true` seeds a simulated day
    // (yesterday and today) for UI checks on emulators.
    val debuggable = (getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // Debug builds: `--es ashidori.syncUrl http://localhost:8787 --es ashidori.syncCode CODE`
    // pairs with a local dev server, creates a key and syncs, for end-to-end
    // tests. Secrets are logged only because this is a debug-only test hook.
    if debuggable then
      for url <- Option(getIntent.getStringExtra("ashidori.syncUrl")); code <- Option(getIntent.getStringExtra("ashidori.syncCode")) do
        AshidoriApp.io.execute { () =>
          try
            val (id, token) = net.sksat.ashidori.sync.SyncClient(url, None).register(code, "emulator")
            net.sksat.ashidori.sync.SyncConfig.savePairing(url, id, token)
            val key = ashidori.core.sync.Crypto.newMasterKey()
            net.sksat.ashidori.sync.SyncConfig.setMasterKey(key)
            Log.i(s"debug sync paired device=$id token=$token recovery=${ashidori.core.sync.RecoveryCode.encode(key)}")
            val sent = net.sksat.ashidori.sync.Uploader.uploadSmall()
            Log.i(s"debug sync uploaded batches=$sent")
          catch case e: Exception => Log.e("debug sync failed", e)
        }
    if debuggable && getIntent.getBooleanExtra("ashidori.sim", false) then
      AshidoriApp.io.execute { () =>
        val today = LocalDate.now(AshidoriApp.zone)
        for d <- Seq(today.minusDays(1), today) do
          val out = ashidori.core.sim.TrajectorySimulator.run(ashidori.core.sim.Scenarios.commute(d))
          val now = System.currentTimeMillis()
          AshidoriApp.db.insertFixes(out.fixes.filter(_.time < now).map(_.copy(provider = "sim")))
          AshidoriApp.db.insertMotion(out.motion.filter(_.start < now), "sim")
        TimelineRepo.invalidate()
        runOnUiThread(() => current.foreach(_.onShow()))
      }

    LoggerState.observe(() => updateStatusChip())
    updateStatusChip()
    net.sksat.ashidori.sync.SyncScheduler.schedule(this)

    if !Settings.onboarded then push(OnboardingScreen(this, permissionsOnly = false, onFinish = () => finishOnboarding()))
    else
      nav.setSelectedItemId(MainActivity.TabTimeline)
      // Resume logging after an app update or a force stop.
      if Settings.loggingEnabled && !LoggerState.running && granted(Manifest.permission.ACCESS_FINE_LOCATION) then
        LoggerService.send(this, LoggerService.ActionStart)
      handleIntent(getIntent)

  override def onNewIntent(intent: Intent): Unit =
    super.onNewIntent(intent)
    handleIntent(intent)

  private def handleIntent(intent: Intent): Unit =
    if intent.getBooleanExtra(MainActivity.ExtraOpenLogger, false) && Settings.onboarded then
      stack.clear()
      nav.setSelectedItemId(MainActivity.TabLogger)

  private def finishOnboarding(): Unit =
    Settings.onboarded = true
    stack.clear()
    current = None
    tabId = MainActivity.TabTimeline
    nav.setSelectedItemId(MainActivity.TabTimeline)
    show(tabs(MainActivity.TabTimeline))

  private def updateStatusChip(): Unit =
    val badge = nav.getOrCreateBadge(MainActivity.TabLogger)
    badge.setVisible(!LoggerState.running)
    badge.setBackgroundColor(color(attr.error))

  // --- navigation ----------------------------------------------------------------------

  def push(s: Screen): Unit =
    stack.push(s)
    show(s)

  def pop(): Unit =
    if stack.nonEmpty then
      val s = stack.pop()
      s.onHide()
      current = None
      show(if stack.nonEmpty then stack.top else tabs(tabId))

  /** Jump to a day in the timeline (from places, insights, map). */
  def openDay(d: LocalDate): Unit =
    selectedDate = d
    stack.clear()
    if tabId == MainActivity.TabTimeline then show(tabs(tabId)) else nav.setSelectedItemId(MainActivity.TabTimeline)

  private def show(s: Screen): Unit =
    current.filterNot(_ eq s).foreach(_.onHide())
    screenHost.removeAllViews()
    Option(s.view.getParent).foreach(_.asInstanceOf[ViewGroup].removeView(s.view))
    screenHost.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    current = Some(s)
    val pushed = stack.nonEmpty
    val onboarding = s.isInstanceOf[OnboardingScreen]
    toolbar.setVisibility(if onboarding then View.GONE else View.VISIBLE)
    nav.setVisibility(if pushed then View.GONE else View.VISIBLE)
    fab.setVisibility(if !pushed && tabId == MainActivity.TabTimeline then View.VISIBLE else View.GONE)
    toolbar.getMenu.findItem(1).setVisible(!pushed)
    toolbar.getMenu.findItem(2).setVisible(!pushed)
    if pushed then
      toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
      toolbar.setNavigationContentDescription("戻る")
      toolbar.setTitle(s.title)
    else
      toolbar.setNavigationIcon(null)
      toolbar.setTitle("ashidori")
    s.onShow()

  def registerMap(m: TrackMap): Unit = maps += m

  /** Check-in is about "now": the timeline hides it for week/month/year. */
  def setCheckInVisible(v: Boolean): Unit =
    fab.setVisibility(if v && stack.isEmpty && tabId == MainActivity.TabTimeline then View.VISIBLE else View.GONE)

  override def onStart(): Unit =
    super.onStart()
    maps.foreach(_.onStart())
  override def onResume(): Unit =
    super.onResume()
    maps.foreach(_.onResume())
    current.foreach(_.onShow())
    // Warm the venue cache from the logger's latest position (no extra GNSS).
    LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 10 * 60_000)
      .foreach(f => VenueSearch.prefetch(f.latLng))
  override def onPause(): Unit =
    maps.foreach(_.onPause())
    super.onPause()
  override def onStop(): Unit =
    maps.foreach(_.onStop())
    super.onStop()
  override def onLowMemory(): Unit =
    super.onLowMemory()
    maps.foreach(_.onLowMemory())
  override def onSaveInstanceState(out: Bundle): Unit =
    super.onSaveInstanceState(out)
    maps.foreach(_.onSaveInstanceState(out))
  override def onDestroy(): Unit =
    current.foreach(_.onHide())
    tabs.values.foreach(_.onDestroy())
    maps.foreach(_.onDestroy())
    super.onDestroy()

  // --- shared actions ------------------------------------------------------------------------

  def shareFile(f: File): Unit =
    val uri = FileProvider.getUriForFile(this, s"$getPackageName.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, f.getName))

object MainActivity:
  /** Intent extra: open the recording screen (from the notification). */
  val ExtraOpenLogger = "ashidori.openLogger"
  val TabTimeline = 1
  val TabInsights = 2
  val TabLogger = 4
  val RequiredPermissions: Seq[String] = Seq(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACTIVITY_RECOGNITION
  )
