package net.sksat.ashidori

import android.content.Context
import okhttp3.{Call, Dispatcher}
import org.maplibre.android.module.http.HttpRequestUtil
import scala.jdk.CollectionConverters.*
import ashidori.core.*

/** The app's paths to external destinations (docs/design/outbound-and-offline.md 4):
  *
  *  - [[outbound]]: every HTTP request the app makes itself (Overpass, the
  *    data downloads, OpenFreeMap's style), under a [[Permit]].
  *  - [[guard]]: every request of the map library, through an OkHttp
  *    interceptor ([[install]]), marked per holder ([[MapGuard]]).
  *  - The platform geocoder is gated in [[Geocoding]].
  *
  * A change of a rule or the pause cuts what is in flight: open
  * connections, the map library's calls of that destination, queued
  * downloads, and maps and snapshots are drawn again.
  *
  * Debug builds log every request and refusal (tag `AshiOut`).
  */
object Net:
  private val Tag = "AshiOut"
  private lazy val debug = (AshidoriApp.app.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  /** One line per request, refusal and cut: debug builds only (a tile path holds the area). */
  def log(msg: String): Unit = if debug then android.util.Log.i(Tag, msg)

  /** The allowed hosts of a destination now. */
  def hosts(d: Dest): Set[String] = d match
    case Dest.Ofm => Outbound.OfmHosts
    case Dest.Overpass => Policy.OverpassHosts -- Settings.gate.rule(Dest.Overpass).off
    case Dest.Data =>
      import net.sksat.ashidori.sync.SyncConfig
      val server = Option(SyncConfig.serverUrl).filter(_ => SyncConfig.paired).flatMap(hostOf)
      val dev = Seq(BasemapDownload, VenueDownload).flatMap(_.debugUrl).flatMap(hostOf)
      Outbound.GitHubHosts ++ server ++ dev
    case _ => Set.empty

  private def hostOf(url: String): Option[String] =
    try Option(java.net.URI(url).getHost).map(_.toLowerCase) catch case _: Exception => None

  /** OpenFreeMap: only its styles and TileJSON. */
  lazy val outbound: Outbound = Outbound(Settings.gate, hosts, log, d => Option.when(d == Dest.Ofm)(UrlType.OfmStyles))

  /** The URLs of a map destination (the custom one from the user's URL now). */
  def urlType(d: Dest): Option[UrlType] = d match
    case Dest.Ofm => Some(UrlType.Ofm)
    case Dest.Gsi => Some(UrlType.Gsi)
    case Dest.CustomTiles => Some(ui.MapStyle.custom._1).filter(_.nonEmpty).flatMap(UrlType.template)
    case _ => None

  lazy val guard: MapGuard = MapGuard(Settings.gate, Settings.tokens, urlType)

  private var installed = false
  private lazy val dispatcher =
    val d = Dispatcher()
    d.setMaxRequestsPerHost(20)
    d

  /** The map library's HTTP client ([[MapHttp]]): every request passes
    * [[MapGuard.check]] before any name is looked up or connection made, and
    * goes out without its mark; redirects are followed by the interceptor,
    * each hop checked again (holder, permit, grant, URL kind, zoom) before
    * it connects; only the map destinations' hosts are looked up.
    */
  def mapClient(ctx: Context): Unit = synchronized {
    if !installed then
      installed = true
      // The map library must be set up before its HTTP client is replaced.
      org.maplibre.android.MapLibre.getInstance(ctx.getApplicationContext)
      HttpRequestUtil.setOkHttpClient(mapHttp.client(dispatcher))
  }

  private lazy val mapHttp = MapHttp(guard, log)

  /** At app start: a change of a rule or the pause aborts what is in flight
    * (the map library's calls here, the app's own connections in
    * [[outbound]]), then the maps and downloads follow it.
    */
  def install(ctx: Context): Unit =
    Settings.gate.onRevoke(mapHttp.revoker(dispatcher))
    Settings.gate.onChange(changed(ctx.getApplicationContext, _))

  /** Changes of the rules and the pause run here, off the main thread: a
    * change returns once what was in flight was aborted, or with
    * [[SendGate.Change.Stopping]] when some sends were still ending at its
    * deadline ([[SendGate.RevokeWaitMs]]). `returned` then runs on the main
    * thread with that result, and `complete` once every aborted send has
    * ended (at once, unless it was Stopping).
    */
  private val changes = java.util.concurrent.Executors.newSingleThreadExecutor(r => Thread(r, "ashidori-rules"))
  def change(f: => SendGate.Change)(returned: SendGate.Change => Unit = _ => (), complete: () => Unit = () => ()): Unit =
    changes.execute { () =>
      val c = try f catch case e: Exception =>
        Log.w("change of the rules failed", e)
        SendGate.Change.Unchanged
      val main = AshidoriApp.app.getMainExecutor
      c match
        case SendGate.Change.Stopping(n, done) =>
          log(s"change stopping pending=$n")
          main.execute(() => returned(c))
          done.whenComplete { (_, _) =>
            log("change complete")
            main.execute(() => complete())
          }
        case _ =>
          main.execute { () =>
            returned(c)
            complete()
          }
    }

  private def changed(ctx: Context, ds: Set[Dest]): Unit =
    log(s"change dests=${ds.map(_.key).toSeq.sorted.mkString(",")} paused=${Settings.gate.paused}")
    if ds.exists(Dest.Tiles) then
      AshidoriApp.app.getMainExecutor.execute(() => ui.TrackMap.privacyChanged(ctx, ds))
    if ds(Dest.Data) then
      // Stored work of the old generation would not send; it goes now.
      AshidoriApp.app.getMainExecutor.execute { () =>
        BasemapDownload.rulesChanged(ctx)
        VenueDownload.rulesChanged(ctx)
      }
