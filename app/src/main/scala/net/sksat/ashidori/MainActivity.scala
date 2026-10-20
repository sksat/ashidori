package net.sksat.ashidori

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.{Gravity, View, ViewGroup}
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

  private var docCreated: android.net.Uri => Unit = _ => ()
  private val createLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"),
      (uri: android.net.Uri) => if uri != null then docCreated(uri))

  /** Asks where to save a new ZIP file (SAF), suggesting `name`. */
  def createZip(name: String)(onCreated: android.net.Uri => Unit): Unit =
    docCreated = onCreated
    createLauncher.launch(name)

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
    // Recreated (process killed in the background, configuration change):
    // the day the map showed. The camera is restored by the map tab.
    Option(savedInstanceState).flatMap(b => Option(b.getString(MainActivity.StateDate)))
      .foreach(d => selectedDate = LocalDate.parse(d))

    toolbar = MaterialToolbar(this)
    toolbar.setNavigationOnClickListener(_ => pop())

    screenHost = FrameLayout(this)
    fab = ExtendedFloatingActionButton(this)
    fab.setText("チェックイン")
    fab.setIconResource(R.drawable.ic_checkin)
    // Check-ins are purple everywhere (map dots, chips).
    fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(attr.tertiaryContainer)))
    fab.setTextColor(color(attr.onTertiaryContainer))
    fab.setIconTint(android.content.res.ColorStateList.valueOf(color(attr.onTertiaryContainer)))
    fab.setOnClickListener(_ => CheckInSheet(this, None, () => current.foreach(_.onShow())).show())
    val fabLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END)
    fabLp.setMargins(0, 0, dp(16), dp(16))
    content = frame(screenHost)
    content.addView(fab, fabLp)

    nav = BottomNavigationView(this)
    // Always show labels: icon-only tabs made the timeline hard to find.
    nav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED)
    // The timeline is the home and sits in the middle, within thumb reach.
    for ((id, title, icon), order) <- Seq(
        (MainActivity.TabFeed, "タイムライン", R.drawable.ic_timeline),
        (MainActivity.TabTimeline, "マップ", R.drawable.ic_map),
        (MainActivity.TabLogger, "ステータス", R.drawable.ic_logger),
        (MainActivity.TabSettings, "設定", R.drawable.ic_settings)
      ).zipWithIndex
    do nav.getMenu.add(0, id, order, title).setIcon(icon)

    tabs = Map(
      MainActivity.TabTimeline -> TimelineScreen(this, savedInstanceState),
      MainActivity.TabFeed -> FeedScreen(this, savedInstanceState),
      MainActivity.TabLogger -> LoggerScreen(this),
      MainActivity.TabSettings -> SettingsScreen(this)
    )
    // Recreated: where each tab was scrolled to (restored when it is shown).
    for (id, screen) <- tabs; b <- Option(savedInstanceState) do
      val y = b.getInt(MainActivity.StateScroll + id, 0)
      if y > 0 then scrollY(screen) = y
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
            import net.sksat.ashidori.sync.*
            val id = Registration.registerBlocking(url, "pair", code, "emulator")
            val key = ashidori.core.sync.Crypto.newMasterKey()
            if !SyncConfig.confirmKey(key, SyncConfig.registration) then throw IllegalStateException("key not set")
            Log.i(s"debug sync paired device=$id token=${SyncConfig.token.getOrElse("")} recovery=${ashidori.core.sync.RecoveryCode.encode(key)}")
            val sent = SyncLock.run(SyncRun.current().fold(0)(Uploader.uploadSmall))
            Log.i(s"debug sync uploaded batches=$sent")
          catch case e: Exception => Log.e("debug sync failed", e)
        }
    if debuggable then debugDownloads(getIntent)
    if debuggable && getIntent.getBooleanExtra("ashidori.sim", false) then
      AshidoriApp.io.execute { () =>
        val today = LocalDate.now(AshidoriApp.zone)
        // `--ei ashidori.simDays N` seeds N days back: weekdays commute, weekends out, a trip now and then.
        val n = getIntent.getIntExtra("ashidori.simDays", 2)
        for i <- (n - 1) to 0 by -1; d = today.minusDays(i) if i % 9 != 4 do
          import ashidori.core.sim.Scenarios
          val sc =
            if i % 23 == 6 then Scenarios.flight(d, i)
            else if d.getDayOfWeek.getValue >= 6 then Scenarios.weekend(d, i)
            else Scenarios.commute(d, i)
          val out = ashidori.core.sim.TrajectorySimulator.run(sc)
          val now = System.currentTimeMillis()
          AshidoriApp.db.insertFixes(out.fixes.filter(_.time < now).map(_.copy(provider = "sim")))
          AshidoriApp.db.insertMotion(out.motion.filter(_.start < now), "sim")
        TimelineRepo.invalidate()
        runOnUiThread(() => current.foreach(_.onShow()))
      }

    LoggerState.observe(() => updateStatusChip())
    updateStatusChip()
    net.sksat.ashidori.sync.SyncScheduler.schedule(this)

    // A recreated activity does not open its launching link again.
    val fresh = savedInstanceState == null
    if !Settings.onboarded then
      push(OnboardingScreen(this, permissionsOnly = false, onFinish = () => finishOnboarding()))
      if fresh then handleIntent(getIntent)
    else
      // Recreated: the tab that was open.
      val tab = Option(savedInstanceState).map(_.getInt(MainActivity.StateTab, MainActivity.TabTimeline))
        .filter(tabs.contains).getOrElse(MainActivity.TabTimeline)
      nav.setSelectedItemId(tab)
      if fresh then handleIntent(getIntent)

  override def onNewIntent(intent: Intent): Unit =
    super.onNewIntent(intent)
    if BasemapDownload.debuggable then debugDownloads(intent)
    handleIntent(intent)

  /** A registration link that arrived during onboarding, opened when it ends. */
  private var pendingLink: Option[String] = None

  /** Debug builds: the offline basemap and venue index without a release
    * (also when the intent reaches a running activity).
    *   --es ashidori.basemapUrl http://10.0.2.2:8000  download <url>/<pinned file> ("" = pinned sources)
    *   --ez ashidori.basemapImport true                install <external files>/basemap/<pinned file>
    *   --es ashidori.venuesUrl, --ez ashidori.venuesImport   the same for the venue index (venues/)
    * Either way the archive must match the pin (app/src/main/assets/{basemap,venues}-pin.json).
    */
  private def debugDownloads(intent: Intent): Unit =
    Option(intent.getStringExtra("ashidori.basemapUrl")).foreach { u =>
      BasemapDownload.debugUrl = u
      if u.trim.nonEmpty then BasemapDownload.start(this, allowMetered = true)
    }
    if intent.getBooleanExtra("ashidori.basemapImport", false) then
      Basemap.pin.foreach(p => BasemapDownload.importFile(this, java.io.File(getExternalFilesDir(null), s"basemap/${p.file}")))
    Option(intent.getStringExtra("ashidori.venuesUrl")).foreach { u =>
      VenueDownload.debugUrl = u
      if u.trim.nonEmpty then VenueDownload.start(this, allowMetered = true)
    }
    if intent.getBooleanExtra("ashidori.venuesImport", false) then
      VenueData.pin.foreach(p => VenueDownload.importFile(this, java.io.File(getExternalFilesDir(null), s"venues/${p.file}")))

  private def handleIntent(intent: Intent): Unit =
    if intent.getBooleanExtra(MainActivity.ExtraOpenDiagnostics, false) && Settings.onboarded then
      intent.removeExtra(MainActivity.ExtraOpenDiagnostics)
      openDiagnostics()
    if intent.getBooleanExtra(MainActivity.ExtraOpenLogger, false) && Settings.onboarded then
      stack.clear()
      nav.setSelectedItemId(MainActivity.TabLogger)
    if intent.getAction == Intent.ACTION_VIEW then
      Option(intent.getDataString).foreach { data =>
        if Settings.onboarded then openPairLink(data) else pendingLink = Some(data)
      }

  /** Opens the sync setup with a registration link's server and code filled
    * in (see [[SyncSettingsScreen]]); the user confirms before anything is sent.
    */
  private def openPairLink(data: String): Unit =
    val debuggable = (getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    ashidori.core.sync.PairLink.parse(data, allowHttp = debuggable) match
      case None =>
        android.widget.Toast.makeText(this, "登録リンクの形式が正しくありません", android.widget.Toast.LENGTH_LONG).show()
      case Some(link) =>
        stack.clear()
        if tabId == MainActivity.TabSettings then show(tabs(tabId)) else nav.setSelectedItemId(MainActivity.TabSettings)
        push(SyncSettingsScreen(this, Some(link)))

  /** 設定 › 端末診断 (from an alert or the status tab's banner). */
  def openDiagnostics(): Unit =
    stack.clear()
    if tabId == MainActivity.TabSettings then show(tabs(tabId)) else nav.setSelectedItemId(MainActivity.TabSettings)
    push(DiagnosticsScreen(this))

  private def finishOnboarding(): Unit =
    Settings.onboarded = true
    stack.clear()
    current = None
    tabId = MainActivity.TabTimeline
    nav.setSelectedItemId(MainActivity.TabTimeline)
    show(tabs(MainActivity.TabTimeline))
    pendingLink.foreach(openPairLink)
    pendingLink = None

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

  def openSearch(): Unit = push(SearchScreen(this))

  /** A day on the タイムライン tab (日), from the map. */
  def openTimeline(d: LocalDate): Unit =
    stack.clear()
    tabs(MainActivity.TabFeed) match
      case f: FeedScreen => f.showFrom(d)
      case _             => ()
    if tabId == MainActivity.TabFeed then show(tabs(tabId)) else nav.setSelectedItemId(MainActivity.TabFeed)

  /** Week / month / year summary, pushed over the current tab. */
  private lazy val periodScreen = TimelineScreen(this, null, Seq(Span.Week, Span.Month, Span.Year))
  def openPeriod(span: Span, d: LocalDate): Unit =
    periodScreen.showPeriod(span, d)
    push(periodScreen)

  /** Jump to a day on the map tab (from the feed, places, search). */
  def openDay(d: LocalDate): Unit =
    selectedDate = d
    stack.clear()
    if tabId == MainActivity.TabTimeline then show(tabs(tabId)) else nav.setSelectedItemId(MainActivity.TabTimeline)

  private def show(s: Screen): Unit =
    current.filterNot(_ eq s).foreach { old =>
      MainActivity.scroller(old.view).foreach(v => scrollY(old) = v.getScrollY)
      old.onHide()
    }
    setFabLift(0)
    screenHost.removeAllViews()
    Option(s.view.getParent).foreach(_.asInstanceOf[ViewGroup].removeView(s.view))
    screenHost.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    current = Some(s)
    val pushed = stack.nonEmpty
    val onboarding = s.isInstanceOf[OnboardingScreen]
    // The top bar is only for pushed pages (title and back); tab roots use the full height.
    toolbar.setVisibility(if onboarding || !pushed then View.GONE else View.VISIBLE)
    nav.setVisibility(if pushed then View.GONE else View.VISIBLE)
    fab.setVisibility(if !pushed && tabId == MainActivity.TabTimeline then View.VISIBLE else View.GONE)
    if pushed then
      toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
      toolbar.setNavigationContentDescription("戻る")
      toolbar.setTitle(s.title)
    else
      toolbar.setNavigationIcon(null)
      // Tab roots: the bottom bar already says where you are.
      toolbar.setTitle("")
    s.onShow()
    // Screens stay alive across tab switches, but a re-attached scroll view
    // may come back at the top: put it back where the user left it (unless
    // the screen started over in onShow, see forgetScroll).
    for y <- scrollY.remove(s) if y > 0; v <- MainActivity.scroller(s.view) do restoreScroll(v, y)

  def registerMap(m: TrackMap): Unit = maps += m

  /** Scroll position of each hidden screen's main scroll view. */
  private val scrollY = mutable.Map.empty[Screen, Int]

  /** `s` shows new content from the top: do not restore its old position. */
  def forgetScroll(s: Screen): Unit = scrollY -= s

  /** Scrolls `v` to `y`, again on each layout until its content is tall
    * enough (content loaded on showing, e.g. the feed's pages, may take a
    * moment); gives up after three seconds.
    */
  private def restoreScroll(v: View, y: Int): Unit =
    val until = System.currentTimeMillis() + 3000
    val obs = v.getViewTreeObserver
    // Only once the content reaches that far; never a clamped part-way position.
    def reaches = v match
      case g: ViewGroup if g.getChildCount > 0 => g.getChildAt(0).getHeight - v.getHeight >= y
      case _                                   => false
    lazy val listener: android.view.ViewTreeObserver.OnGlobalLayoutListener = () =>
      val ok = reaches
      if ok then v.scrollTo(0, y)
      if ok || System.currentTimeMillis() > until then v.getViewTreeObserver.removeOnGlobalLayoutListener(listener)
    obs.addOnGlobalLayoutListener(listener)
    v.post(() => if reaches then v.scrollTo(0, y))

  /** Raise the check-in button by `px` (the map tab keeps it above its sheet). */
  def setFabLift(px: Int): Unit =
    val lp = fab.getLayoutParams.asInstanceOf[FrameLayout.LayoutParams]
    if lp.bottomMargin != dp(16) + px then
      lp.bottomMargin = dp(16) + px
      fab.setLayoutParams(lp)

  /** A pushed screen whose title depends on its state calls this when it changes. */
  def updateTitle(): Unit = if stack.nonEmpty then current.foreach(s => toolbar.setTitle(s.title))

  /** Check-in is about "now": the timeline hides it for week/month/year. */
  def setCheckInVisible(v: Boolean): Unit =
    fab.setVisibility(if v && stack.isEmpty && tabId == MainActivity.TabTimeline then View.VISIBLE else View.GONE)

  override def onStart(): Unit =
    super.onStart()
    maps.foreach(_.onStart())
  override def onResume(): Unit =
    super.onResume()
    // Resume logging after an app update, a force stop, or a stop the
    // background could not recover from (the "記録が止まっています"
    // notification leads here). On every resume, not only in onCreate: the
    // activity may have outlived the service.
    if Settings.onboarded && Settings.loggingEnabled && !LoggerState.running && granted(Manifest.permission.ACCESS_FINE_LOCATION) then
      LoggerService.send(this, LoggerService.ActionStart)
    AshidoriApp.io.execute(() => LifeLog.checkState("アプリを表示"))
    maps.foreach(_.onResume())
    current.foreach(_.onShow())
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
    out.putInt(MainActivity.StateTab, tabId)
    for (id, screen) <- tabs do
      val y = if current.exists(_ eq screen) then MainActivity.scroller(screen.view).fold(0)(_.getScrollY)
        else scrollY.getOrElse(screen, 0)
      if y > 0 then out.putInt(MainActivity.StateScroll + id, y)
    out.putString(MainActivity.StateDate, selectedDate.toString)
    tabs.values.foreach {
      case t: TimelineScreen => t.saveState(out)
      case f: FeedScreen     => f.saveState(out)
      case _                 => ()
    }
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
  /** From an alert notification: 設定 › 端末診断. */
  val ExtraOpenDiagnostics = "ashidori.openDiagnostics"
  val TabTimeline = 1
  val TabFeed = 2
  val TabLogger = 4
  val TabSettings = 5
  private val StateTab = "tab"
  private val StateDate = "selectedDate"
  /** Prefix of a tab's scroll position, followed by its tab id. */
  private val StateScroll = "scroll."

  /** A screen's main vertical scroll view: the first one in its tree. */
  private def scroller(v: View): Option[View] = v match
    case _: android.widget.ScrollView | _: androidx.core.widget.NestedScrollView => Some(v)
    case g: ViewGroup => (0 until g.getChildCount).iterator.map(g.getChildAt).flatMap(scroller).nextOption()
    case _ => None
  val RequiredPermissions: Seq[String] = Seq(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACTIVITY_RECOGNITION
  )
