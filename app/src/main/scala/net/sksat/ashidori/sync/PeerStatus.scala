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
      model = Build.MODEL
    )

/** One of the user's devices, as the server lists it. */
final case class Peer(id: String, name: String, revoked: Boolean, lastSeenAt: Long)

/** The latest status received from a device. `area` is resolved here, on
  * the receiving device; it is never uploaded.
  */
final case class PeerEntry(seq: Long, fetchedAt: Long, status: DeviceStatus, area: String)

/** Status of the user's other devices: downloaded after each small-stream
  * upload and kept in private preferences, tagged with the registration
  * ([[SyncConfig.registration]]); state of another registration reads as empty.
  *
  * Only the metadata listing is walked (from a stored cursor); of the blobs,
  * only the newest `status` batch per other device is fetched.
  */
object PeerStatus:
  private def prefs = AshidoriApp.app.getSharedPreferences("peers", Context.MODE_PRIVATE)
  private val PageSize = 1000
  /** Listing pages per run; a long backlog continues on the next run. */
  private val MaxPages = 20

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
    else Try(ujson.read(prefs.getString("devices", "[]")).arr.toVector.map { d =>
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

  /** Listing cursor, newest listed and read seq per device, unreadable batches. */
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

  /** Lists new batches and fetches the newest `status` of each other device.
    * Runs inside the sync run `run` (under [[SyncLock]]).
    *
    * The listing cursor moves on after each page: the newest listed seq per
    * device is stored with it. A network error leaves that device for the
    * next run. A blob that is received but cannot be read is recorded as
    * unresolved and retried up to [[StatusSyncState.MaxAttempts]] times; it
    * stays shown on the device's row until a newer status is read.
    */
  def pull(run: SyncRun): Unit =
    val reg = run.reg
    try saveDevices(run.client.devices(), reg)
    catch case e: Exception => Log.w("device list failed", e)
    def save(st: StatusSyncState, es: Option[Map[String, PeerEntry]] = None): Unit =
      edit(reg).foreach { e =>
        e.putString("statusSync", ujson.write(StatusSyncState.toJson(st)))
        es.foreach(m => e.putString("status", entriesJson(m)))
        e.commit()
      }
    var st = syncState
    var more = true
    var pages = 0
    while more && pages < MaxPages do
      run.checkStopped()
      val before = st.cursor
      val (items, next) = run.client.listBatches(st.cursor, PageSize)
      st = st.listed(items, run.deviceId)
      save(st)
      more = next.isDefined && items.nonEmpty && st.cursor > before
      pages += 1

    var known = entries
    for (dev, cand) <- st.targets do
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
          case e: Exception =>
            Log.w(s"status $dev/$seq not fetched", e)
            Right(None)
      blob match
        case Right(None) => ()
        case Left(e) =>
          Log.w(s"status $dev/$seq unreadable", e)
          st = st.failed(dev, seq, e.getMessage)
        case Right(Some(b)) =>
          try
            val plain = Crypto.gunzip(Crypto.open(run.batchKey, Crypto.aad(dev, DeviceStatus.Stream, seq), b), DeviceStatus.MaxPlain)
            val s = DeviceStatus.decode(plain).getOrElse(throw IllegalArgumentException("empty status batch"))
            known += dev -> PeerEntry(seq, System.currentTimeMillis(), s, areaFor(s.lastFix, known.get(dev)))
            st = st.succeeded(dev, seq)
          catch
            case e: Exception =>
              Log.w(s"status $dev/$seq unreadable", e)
              st = st.failed(dev, seq, e.getMessage)
      save(st, Some(known))
    changed()

  /** Area name of the position, reusing the previous one when it barely moved. */
  private def areaFor(fix: Option[StatusFix], prev: Option[PeerEntry]): String =
    fix.fold("") { f =>
      val reuse = prev.flatMap(p => p.status.lastFix.filter(_ => p.area.nonEmpty))
        .exists(pf => Geo.distance(pf.lat, pf.lon, f.lat, f.lon) < 200)
      if reuse then prev.get.area
      else if !android.location.Geocoder.isPresent then ""
      else
        val g = android.location.Geocoder(AshidoriApp.app, java.util.Locale.JAPANESE)
        @annotation.nowarn("cat=deprecation")
        val found = Try(g.getFromLocation(f.lat, f.lon, 1)).toOption.flatMap(l => Option(l))
        found.flatMap(l => scala.jdk.CollectionConverters.ListHasAsScala(l).asScala.headOption)
          .flatMap(a => Option(a.getAddressLine(0)).flatMap(AreaName.fromAddressLine).orElse(Option(a.getLocality)))
          .getOrElse("")
    }
