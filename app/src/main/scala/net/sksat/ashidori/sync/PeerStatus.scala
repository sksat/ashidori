package net.sksat.ashidori.sync

import android.content.Context
import android.os.{BatteryManager, Build, Handler, Looper}
import scala.collection.mutable
import scala.util.Try
import net.sksat.ashidori.*
import ashidori.core.*
import ashidori.core.sync.*

/** This device's `status` record, from state the app already keeps. */
object StatusReport:
  def current(): DeviceStatus =
    val app = AshidoriApp.app
    val bm = app.getSystemService(classOf[BatteryManager])
    // The logger batches DB writes, so its latest fix may be newer than the DB's.
    val fix = (Try(AshidoriApp.db.lastFix()).toOption.flatten.toSeq ++ LoggerState.lastFix).maxByOption(_.time)
    val on = Settings.loggingEnabled
    DeviceStatus(
      t = System.currentTimeMillis(),
      batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
      charging = bm.isCharging,
      recording = on,
      mode = (if LoggerState.running then LoggerState.plan.map(_.effectiveMode) else None).getOrElse(Settings.mode).key,
      since = if on then Settings.loggingSince else 0L,
      lastFix = fix.map(f => StatusFix(f.time, f.lat, f.lon, f.accuracy.toDouble)),
      versionName = BuildInfo.versionName,
      versionCode = BuildInfo.versionCode,
      model = Build.MODEL,
      // The cut-over record this device knows, so the others learn it.
      authCut = AuthCutover.knownCut
    )

/** One of the user's devices, as the server lists it. */
final case class Peer(id: String, name: String, revoked: Boolean, lastSeenAt: Long)

/** The latest status received from a device. `area` is resolved here, on
  * the receiving device; it is never uploaded.
  */
final case class PeerEntry(seq: Long, fetchedAt: Long, status: DeviceStatus, area: String)

/** Status of the user's other devices: downloaded by each small-stream job,
  * whether or not its upload succeeded, and kept in private preferences, tagged with the registration
  * ([[SyncConfig.registration]]); state of another registration reads as empty.
  *
  * Only the metadata listing is walked (from a stored cursor); of the blobs,
  * only the newest readable `status` batch per other device is fetched.
  */
object PeerStatus:
  private def prefs = AshidoriApp.app.getSharedPreferences("peers", Context.MODE_PRIVATE)
  private val PageSize = 1000
  /** Listing pages per run; a long backlog continues on the next run. */
  private val MaxPages = 20
  /** Pages of [[MaxPages]] the main walk leaves to a due rescan. */
  private val RescanPages = 5

  // --- stored state ---

  /** The stored state belongs to the current registration. */
  private def current: Boolean =
    val reg = SyncConfig.registration
    reg.nonEmpty && prefs.getString("reg", "") == reg

  /** Starts an edit for registration `reg`: none if that is no longer the
    * current one; a reset one if the stored state belongs to another.
    */
  private def edit(reg: String): Option[android.content.SharedPreferences.Editor] =
    if reg.isEmpty || reg != SyncConfig.registration then None
    else if prefs.getString("reg", "") == reg then Some(prefs.edit())
    else Some(prefs.edit().clear().putString("reg", reg))

  def devices: Vector[Peer] =
    if !current then Vector.empty
    // Browsers (kind "viewer") read only; they send no status, so they are not peers.
    else Try(ujson.read(prefs.getString("devices", "[]")).arr.toVector
      .filterNot(_.obj.get("kind").flatMap(_.strOpt).contains("viewer")).map { d =>
      Peer(d("id").str, d("name").str, d.obj.get("revoked").exists(_.boolOpt.contains(true)),
        d.obj.get("lastSeenAt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L))
    }).getOrElse(Vector.empty)

  def devicesFetchedAt: Long = if current then prefs.getLong("devicesAt", 0L) else 0L

  def entries: Map[String, PeerEntry] =
    if !current then Map.empty
    else Try(ujson.read(prefs.getString("status", "{}")).obj.toMap.map { (id, e) =>
      id -> PeerEntry(e("seq").num.toLong, e("fetchedAt").num.toLong, DeviceStatus.fromJson(e("status")),
        e.obj.get("area").flatMap(_.strOpt).getOrElse(""))
    }).getOrElse(Map.empty)

  /** Listing cursor, listed candidates and read seq per device, unreadable batches. */
  def syncState: StatusSyncState =
    if !current then StatusSyncState.empty
    else Option(prefs.getString("statusSync", null)).flatMap(s => Try(StatusSyncState.fromJson(ujson.read(s))).toOption)
      .getOrElse(StatusSyncState.empty)

  /** A status of the device that was received but could not be read. */
  def problem(deviceId: String): Option[Unresolved] = syncState.problem(deviceId)

  private def entriesJson(es: Map[String, PeerEntry]): String =
    ujson.write(ujson.Obj.from(es.map((id, e) => id -> ujson.Obj(
      "seq" -> ujson.Num(e.seq.toDouble), "fetchedAt" -> ujson.Num(e.fetchedAt.toDouble),
      "status" -> e.status.toJson, "area" -> e.area))))

  /** Other devices that are not revoked, in the server's order. */
  def others: Vector[Peer] = devices.filter(p => !p.revoked && p.id != SyncConfig.deviceId)

  /** The 端末 section is worth showing. */
  def visible: Boolean = SyncConfig.ready && others.nonEmpty

  def clear(): Unit =
    prefs.edit().clear().commit()
    changed()

  // --- observers (main thread) ---

  private val listeners = mutable.LinkedHashSet.empty[() => Unit]
  private val main = Handler(Looper.getMainLooper)

  def observe(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  private def changed(): Unit = main.post(() => listeners.toVector.foreach(_()))

  // --- network (blocking; call off the main thread) ---

  /** Keeps a `GET /v1/devices` response (also from the 同期 screen's list),
    * if the registration `reg` it was fetched for is still the current one.
    */
  def saveDevices(js: ujson.Value, reg: String): Unit =
    edit(reg).foreach { e =>
      e.putString("devices", ujson.write(js)).putLong("devicesAt", System.currentTimeMillis()).apply()
      changed()
    }

  /** For when the section is first shown, before any sync ran with this version. */
  def fetchDevicesAsync(): Unit =
    val reg = SyncConfig.registration
    AshidoriApp.io.execute { () =>
      try saveDevices(SyncClient(SyncConfig.serverUrl, SyncConfig.token).devices(), reg)
      catch case e: Exception => Log.w("device list failed", e)
    }

  /** Asks the next pull to retry every exhausted status now (the 更新 button). */
  def requestRetry(): Unit =
    edit(SyncConfig.registration).foreach(_.putBoolean("retryNow", true).apply())

  /** Lists new batches and fetches the newest `status` of each other device.
    * Runs inside the sync run `run` (under [[SyncLock]]).
    *
    * The listing cursor moves on after each page: the newest few listed
    * statuses per device are stored with it. A network error leaves that
    * device for the next run. A blob that is received but cannot be read is
    * recorded as unresolved and retried up to [[StatusSyncState.MaxAttempts]]
    * times, after which the next newest candidate is tried; it stays shown on
    * the device's row until a status at least as new is read. An exhausted
    * candidate is retried later with a backoff, from its own budget
    * ([[StatusSyncState.retries]]). Now and then the listing is walked again
    * from cursor 0 ([[StatusSyncState.rescanIfDue]]), so a row the server made
    * up cannot hide later ones for good; the kept candidates rotate through
    * the listing ([[StatusSyncState.sweep]]), so more made-up rows than are
    * kept cannot either.
    *
    * The three steps do not depend on each other: a failing main walk (e.g.
    * a cursor the server refuses) neither stops the rescan nor the fetches,
    * and a due rescan keeps [[RescanPages]] of the page budget.
    */
  def pull(run: SyncRun): Unit =
    val reg = run.reg
    try saveDevices(run.client.devices(), reg)
    catch case e: Exception => Log.w("device list failed", e)
    def save(st: StatusSyncState, es: Option[Map[String, PeerEntry]] = None, retried: Boolean = false): Unit =
      edit(reg).foreach { e =>
        e.putString("statusSync", ujson.write(StatusSyncState.toJson(st)))
        es.foreach(m => e.putString("status", entriesJson(m)))
        if retried then e.remove("retryNow")
        e.commit()
      }
    /** Runs one listing step; an error other than a stop is logged, and `refused` is told unless it was the network. */
    def listing(what: String)(step: => Unit)(refused: => Unit): Unit =
      try step
      catch
        case e: SyncRun.Stopped => throw e
        case e: java.io.IOException => Log.w(s"$what failed", e)
        case e: Exception =>
          Log.w(s"$what failed", e)
          refused
    val now = System.currentTimeMillis()
    var st = syncState
    if current && prefs.getBoolean("retryNow", false) then
      st = st.retryNow
      save(st, retried = true)
    st = st.rescanIfDue(now)
    save(st)
    var pages = 0
    val mainPages = if st.rescan.isDefined then MaxPages - RescanPages else MaxPages
    listing("status listing") {
      var more = true
      while more && pages < mainPages do
        run.checkStopped()
        val before = st.cursor
        pages += 1
        val (items, next) = run.client.listBatches(st.cursor, PageSize)
        st = st.listed(items, run.deviceId, PageSize)
        save(st)
        AuthCutover.noteListed(reg, items)
        more = next.isDefined && items.nonEmpty && st.cursor > before
    } {
      st = st.listFailed
      save(st)
    }
    // A rescan from cursor 0 (weekly, or sooner when wanted) continues on the next run.
    listing("status rescan") {
      while st.rescan.isDefined && pages < MaxPages do
        run.checkStopped()
        pages += 1
        val (items, next) = run.client.listBatches(st.rescan.get, PageSize)
        st = st.rescanned(items, run.deviceId, PageSize, next.isDefined && items.nonEmpty)
        save(st)
        AuthCutover.noteListed(reg, items)
    }(())

    var known = entries
    // Listed rows of a pair, for the digest of the cut-over (read at most
    // once per run). None: the listing failed (next run); Some(None): it is
    // longer than the budget, so the digest cannot be checked (refused).
    var listedFor = Map.empty[StreamKey, Option[Option[Vector[BatchMeta]]]]
    def listedRows(k: StreamKey): Option[Option[Vector[BatchMeta]]] =
      listedFor.getOrElse(k, {
        val rows =
          try Some(AuthCutover.listAll(run, AuthCutover.MaxDigestPages, b => b.deviceId == k.deviceId && b.stream == k.stream))
          catch
            case e: SyncRun.Stopped => throw e
            case e: Exception =>
              Log.w(s"listing for the cut-over digest of ${k.deviceId}/${k.stream} failed", e)
              None
        listedFor += k -> rows
        rows
      })
    /** Opens a status blob as an app (ASHM with its MAC, ASH1 under the
      * cut-over rules) and stores its acceptance. None: not decided this run
      * (the listing for the digest, or storing the state, failed).
      */
    def openStatus(dev: String, seq: Long, b: Array[Byte]): Option[BlobAuth.Opened] =
      val k = StreamKey(dev, DeviceStatus.Stream)
      val opened = BlobAuth.open(AuthCutover.state(reg), run.keys, k, seq, b) match
        case o: BlobAuth.Opened => Some(o)
        case BlobAuth.NeedListing(_) =>
          listedRows(k).map(_.getOrElse(throw BlobRejected("the listing is too long to check the cut-over digest")))
            .map(rows => BlobAuth.open(AuthCutover.state(reg), run.keys, k, seq, b, Some(rows))).collect {
              case o: BlobAuth.Opened => o
            }
      opened.filter { o =>
        try
          AuthCutover.update(reg)(o.accept)
          true
        catch
          case e: java.io.IOException =>
            Log.w(s"status $dev/$seq: storing its acceptance failed", e)
            false
      }
    def fetch(dev: String, cand: StatusCandidate): Unit =
      run.checkStopped()
      val seq = cand.seq
      // Left: received but unusable; None: not received (network), next run.
      // A blob that is not the listed one (size or SHA-256) is not opened.
      val blob: Either[Exception, Option[Array[Byte]]] =
        try
          val b = run.client.getBatch(dev, DeviceStatus.Stream, seq, DeviceStatus.MaxBlob)
          cand.mismatch(b).fold(Right(Some(b)))(m => Left(IllegalStateException(s"not the listed blob: $m")))
        catch
          case e: (LimitExceeded | BatchMissing) => Left(e)
          case e: SyncRun.Stopped => throw e
          case e: Exception =>
            Log.w(s"status $dev/$seq not fetched", e)
            Right(None)
      val at = System.currentTimeMillis()
      blob match
        case Right(None) => ()
        case Left(e) =>
          Log.w(s"status $dev/$seq unreadable", e)
          st = st.failed(dev, cand, e.getMessage, at)
        case Right(Some(b)) =>
          try
            openStatus(dev, seq, b).foreach { o =>
              val plain = Crypto.gunzip(o.plain, DeviceStatus.MaxPlain)
              val s = DeviceStatus.decode(plain).getOrElse(throw IllegalArgumentException("empty status batch"))
              // An authenticated status that names C: C exists, so this
              // device enters the required state, on disk before the status
              // counts as read.
              val stored = s.authCut.filter(_ => o.format == BlobFormat.Ashm).forall { ref =>
                try
                  AuthCutover.update(reg)(_.named(ref))
                  true
                catch
                  case e: java.io.IOException =>
                    Log.w(s"status $dev/$seq: storing the named cut-over failed", e)
                    false
              }
              if stored then
                known += dev -> PeerEntry(seq, at, s, areaFor(s.lastFix, known.get(dev)))
                st = st.succeeded(dev, seq)
            }
          catch
            case e: SyncRun.Stopped => throw e
            case e: Exception =>
              Log.w(s"status $dev/$seq unreadable", e)
              st = st.failed(dev, cand, e.getMessage, at)
      save(st, Some(known))
    // The normal targets, then exhausted ones that are due, from their own budget.
    for (dev, cand) <- st.targets do fetch(dev, cand)
    for (dev, cand) <- st.retries(now) do fetch(dev, cand)
    // The cut-over record named by a status or listed, if not fetched yet.
    AuthCutover.learn(run)
    changed()

  /** Area name of the position, reusing the previous one when it barely moved. */
  private def areaFor(fix: Option[StatusFix], prev: Option[PeerEntry]): String =
    fix.fold("") { f =>
      val reuse = prev.flatMap(p => p.status.lastFix.filter(_ => p.area.nonEmpty))
        .exists(pf => Geo.distance(pf.lat, pf.lon, f.lat, f.lon) < 200)
      if reuse then prev.get.area
      else Geocoding.area(f.lat, f.lon).getOrElse("")
    }
