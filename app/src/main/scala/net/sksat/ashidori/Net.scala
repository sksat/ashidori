package net.sksat.ashidori

import android.content.Context
import java.io.IOException
import okhttp3.{Call, Dispatcher, Interceptor, OkHttpClient}
import org.maplibre.android.module.http.HttpRequestUtil
import scala.jdk.CollectionConverters.*
import ashidori.core.*
import ashidori.core.MapGuard.Verdict

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

  lazy val outbound: Outbound = Outbound(Settings.gate, hosts, log)

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

  /** The map library's HTTP client: every request passes [[MapGuard.check]]
    * (marked, of its destination, under a valid permit, within the zoom of
    * its level) and goes out without its mark; each redirect hop is checked
    * again. A refusal is a plain IOException, which the map library treats
    * as a permanent failure and does not retry (prototypes 2.3).
    */
  def mapClient(ctx: Context): Unit = synchronized {
    if !installed then
      installed = true
      // The map library must be set up before its HTTP client is replaced.
      org.maplibre.android.MapLibre.getInstance(ctx.getApplicationContext)
      val check: Interceptor = chain =>
        val req = chain.request()
        guard.check(req.url.toString) match
          case p: Verdict.Pass =>
            val u = java.net.URI(p.url)
            log(s"pass holder=${p.key.holder} dest=${p.key.dest.key} mode=${p.permit.mode} level=${p.permit.level.key} " +
              s"z=${p.zoom.fold("-")(_.toString)} host=${u.getHost} path=${u.getRawPath}")
            chain.proceed(req.newBuilder().url(p.url).tag(classOf[Verdict.Pass], p).build())
          case Verdict.Reject(reason) =>
            log(s"reject reason=$reason url=${Outbound.redact(TileStyle.strip(req.url.toString)._1)}")
            throw IOException(s"ashidori: $reason")
      // Each hop on the network, redirects included.
      val hop: Interceptor = chain =>
        val req = chain.request()
        Option(req.tag(classOf[Verdict.Pass])) match
          case Some(p) if guard.recheck(p.key, p.permit, req.url.toString) => chain.proceed(req)
          case _ =>
            log(s"reject reason=redirect url=${Outbound.redact(req.url.toString)}")
            throw IOException("ashidori: redirect not allowed")
      val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .addInterceptor(check)
        .addNetworkInterceptor(hop)
        .retryOnConnectionFailure(false)
        .build()
      HttpRequestUtil.setOkHttpClient(client)
  }

  /** At app start: a change of a rule or the pause cuts what is in flight. */
  def install(ctx: Context): Unit =
    Settings.gate.onChange(changed(ctx.getApplicationContext, _))

  /** The map library's calls of these destinations (by their marks). */
  private def cancelMapCalls(ds: Set[Dest]): Unit =
    val calls: Seq[Call] = dispatcher.runningCalls().asScala.toSeq ++ dispatcher.queuedCalls().asScala
    for c <- calls do
      val dest = TileStyle.strip(c.request().url.toString)._2.flatMap(Settings.tokens.lookup).map(_.dest)
      if dest.forall(ds) then
        log(s"cut dest=${dest.fold("?")(_.key)} host=${c.request().url.host}")
        c.cancel()

  private def changed(ctx: Context, ds: Set[Dest]): Unit =
    log(s"change dests=${ds.map(_.key).toSeq.sorted.mkString(",")} paused=${Settings.gate.paused}")
    if ds.exists(Dest.Tiles) then
      cancelMapCalls(ds)
      AshidoriApp.app.getMainExecutor.execute(() => ui.TrackMap.privacyChanged(ctx, ds))
    if ds(Dest.Data) then
      // Stored work of the old generation would not send; it goes now.
      AshidoriApp.app.getMainExecutor.execute { () =>
        BasemapDownload.rulesChanged(ctx)
        VenueDownload.rulesChanged(ctx)
      }
