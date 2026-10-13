package net.sksat.ashidori.sync

import android.content.Context
import android.os.{BatteryManager, Build, Handler, Looper}
import java.security.GeneralSecurityException
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
  * upload and kept in private preferences.
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

  def devices: Vector[Peer] =
    Try(ujson.read(prefs.getString("devices", "[]")).arr.toVector.map { d =>
      Peer(d("id").str, d("name").str, d.obj.get("revoked").exists(_.boolOpt.contains(true)),
        d.obj.get("lastSeenAt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L))
    }).getOrElse(Vector.empty)

  def devicesFetchedAt: Long = prefs.getLong("devicesAt", 0L)

  def entries: Map[String, PeerEntry] =
    Try(ujson.read(prefs.getString("status", "{}")).obj.toMap.map { (id, e) =>
      id -> PeerEntry(e("seq").num.toLong, e("fetchedAt").num.toLong, DeviceStatus.fromJson(e("status")),
        e.obj.get("area").flatMap(_.strOpt).getOrElse(""))
    }).getOrElse(Map.empty)

  private def saveEntries(es: Map[String, PeerEntry]): Unit =
    val js = ujson.Obj.from(es.map((id, e) => id -> ujson.Obj(
      "seq" -> ujson.Num(e.seq.toDouble), "fetchedAt" -> ujson.Num(e.fetchedAt.toDouble),
      "status" -> e.status.toJson, "area" -> e.area)))
    prefs.edit().putString("status", ujson.write(js)).apply()

  /** Other devices that are not revoked, in the server's order. */
  def others: Vector[Peer] = devices.filter(p => !p.revoked && p.id != SyncConfig.deviceId)

  /** The 端末 section is worth showing. */
  def visible: Boolean = SyncConfig.ready && others.nonEmpty

  def clear(): Unit = prefs.edit().clear().apply()

  // --- observers (main thread) ---

  private val listeners = mutable.LinkedHashSet.empty[() => Unit]
  private val main = Handler(Looper.getMainLooper)

  def observe(f: () => Unit): () => Unit =
    listeners += f
    () => listeners -= f

  private def changed(): Unit = main.post(() => listeners.toVector.foreach(_()))

  // --- network (blocking; call off the main thread) ---

  private def client = SyncClient(SyncConfig.serverUrl, SyncConfig.token)

  /** Device names and revocations, for the 端末 section. */
  def fetchDevices(): Unit = saveDevices(client.devices())

  /** Keeps a `GET /v1/devices` response (also from the 同期 screen's list). */
  def saveDevices(js: ujson.Value): Unit =
    prefs.edit().putString("devices", ujson.write(js)).putLong("devicesAt", System.currentTimeMillis()).apply()
    changed()

  /** For when the section is first shown, before any sync ran with this version. */
  def fetchDevicesAsync(): Unit =
    AshidoriApp.io.execute { () =>
      try fetchDevices()
      catch case e: Exception => Log.w("device list failed", e)
    }

  /** Lists new batches and fetches the newest `status` of each other device. */
  def pull(): Unit =
    if !SyncConfig.ready then return
    try fetchDevices()
    catch case e: Exception => Log.w("device list failed", e)
    val c = client
    val self = SyncConfig.deviceId
    var cursor = prefs.getLong("cursor", 0L)
    val seen = mutable.ArrayBuffer.empty[BatchMeta]
    var more = true
    var pages = 0
    while more && pages < MaxPages do
      val (items, next) = c.listBatches(cursor, PageSize)
      seen ++= items.filter(_.stream == DeviceStatus.Stream)
      cursor = (items.map(_.cursor) ++ next :+ cursor).max
      more = next.isDefined && items.nonEmpty
      pages += 1

    val key = Crypto.batchKey(SyncConfig.masterKey.get)
    var known = entries
    // A network error keeps the cursor, so the next run lists these again.
    // A blob that cannot be opened (other key, damaged) is skipped for good.
    var retry = false
    for (dev, seq) <- BatchMeta.newestStatus(seen.toSeq, self, known.view.mapValues(_.seq).toMap) do
      try
        val blob = c.getBatch(dev, DeviceStatus.Stream, seq)
        val plain = Crypto.gunzip(Crypto.open(key, Crypto.aad(dev, DeviceStatus.Stream, seq), blob))
        DeviceStatus.decode(plain).foreach { s =>
          val area = areaFor(s.lastFix, known.get(dev))
          known += dev -> PeerEntry(seq, System.currentTimeMillis(), s, area)
        }
      catch
        case e: (GeneralSecurityException | IllegalArgumentException | java.util.zip.ZipException | ujson.ParseException | NoSuchElementException) =>
          Log.w(s"status $dev/$seq unreadable", e)
        case e: Exception =>
          Log.w(s"status $dev/$seq not fetched", e)
          retry = true
    saveEntries(known)
    if !retry then prefs.edit().putLong("cursor", cursor).apply()
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
