package net.sksat.ashidori.ui

import android.location.{Location, LocationManager}
import android.text.{Editable, TextWatcher}
import android.view.View
import android.widget.{EditText, LinearLayout, Toast}
import com.google.android.material.materialswitch.MaterialSwitch
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import scala.util.{Try, Using}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Swarm-style manual check-in with nearby OpenStreetMap venues. */
final class CheckInScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val net = Executors.newSingleThreadExecutor()
  private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")

  private var here: Option[LatLng] = None
  private var venues: Vector[(Venue, Double)] = Vector.empty
  private var selected: Option[Venue] = None

  private val streak = text("", 15f, bold = true)
  private val where = caption("現在地を取得中…")
  private val search = EditText(act)
  search.setHint("施設を検索 / 名前を入力")
  search.setSingleLine(true)
  private val venueList = vbox()
  private val selectedLabel = text("", 16f, bold = true)
  private val comment = EditText(act)
  comment.setHint("ひとこと（シャウト）")
  private val priv = MaterialSwitch(act)
  priv.setText("非公開（エクスポートや共有に含めない）")
  private val history = vbox()

  private val composer = card(
    selectedLabel,
    comment,
    priv,
    button("チェックイン")(checkIn())
  )
  composer.setVisibility(View.GONE)

  val view: View =
    scroll(
      vbox(
        card(streak, where),
        card(
          search,
          hbox(outlined("カスタム名でチェックイン") {
            val name = search.getText.toString.trim
            if name.nonEmpty then select(Venue("", name, "", here.map(_.lat).getOrElse(Double.NaN), here.map(_.lon).getOrElse(Double.NaN)))
          }),
          venueList,
          caption("近くの施設は OpenStreetMap (Overpass API) から取得します。取得時に現在地がそのサーバに送られます。")
        ),
        composer,
        card(text("チェックイン履歴", 16f, bold = true), history),
        spacer(24)
      )
    )

  search.addTextChangedListener(new TextWatcher:
    def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
    def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = renderVenues()
    def afterTextChanged(s: Editable): Unit = ()
  )

  override def onShow(): Unit =
    locate()
    loadHistory()

  override def onDestroy(): Unit = net.shutdown()

  private def locate(): Unit =
    val lm = act.getSystemService(classOf[LocationManager])
    val recent = LoggerState.lastFix.filter(f => System.currentTimeMillis() - f.time < 2 * 60_000)
    recent match
      case Some(f) => onLocation(f.latLng, f.accuracy)
      case None =>
        try
          lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, act.getMainExecutor, (l: Location) =>
            if l != null then onLocation(LatLng(l.getLatitude, l.getLongitude), l.getAccuracy)
            else where.setText("現在地を取得できませんでした")
          )
        catch case _: SecurityException => where.setText("位置情報の権限がありません")

  private def onLocation(p: LatLng, acc: Float): Unit =
    here = Some(p)
    where.setText(f"現在地 ${p.lat}%.5f, ${p.lon}%.5f (±$acc%.0fm)")
    if Settings.onlineVenues then
      venueList.removeAllViews()
      venueList.addView(caption("近くの施設を検索中…"))
      net.execute { () =>
        val r = Try(fetch(p))
        act.runOnUiThread { () =>
          r.fold(
            e => {
              venueList.removeAllViews()
              venueList.addView(caption(s"施設を取得できませんでした: ${e.getMessage}"))
            },
            vs => { venues = vs; renderVenues() }
          )
        }
      }

  private def fetch(p: LatLng): Vector[(Venue, Double)] =
    val conn = URL(Overpass.Endpoint).openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(20_000)
    conn.setRequestProperty("User-Agent", s"ashidori/${BuildInfo.versionName} (Android)")
    val body = "data=" + URLEncoder.encode(Overpass.query(p.lat, p.lon), StandardCharsets.UTF_8)
    Using.resource(conn.getOutputStream)(_.write(body.getBytes(StandardCharsets.UTF_8)))
    if conn.getResponseCode != 200 then throw RuntimeException(s"HTTP ${conn.getResponseCode}")
    val json = Using.resource(conn.getInputStream)(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
    Overpass.parse(json, p.lat, p.lon)

  private def renderVenues(): Unit =
    val q = search.getText.toString.trim.toLowerCase
    venueList.removeAllViews()
    val shown = venues.filter((v, _) => q.isEmpty || v.name.toLowerCase.contains(q) || v.category.contains(q)).take(25)
    if shown.isEmpty && venues.nonEmpty then venueList.addView(caption("該当なし。カスタム名でチェックインできます。"))
    for (v, d) <- shown do
      val name = text(v.name, 15f)
      name.setLayoutParams(weighted())
      val row = padded(hbox(name, caption(s"${v.category}  ${d.round}m")), 0, 8)
      row.setOnClickListener(_ => select(v))
      venueList.addView(row)

  private def select(v: Venue): Unit =
    selected = Some(v)
    selectedLabel.setText(s"✓ ${v.name}" + (if v.category.nonEmpty then s"（${v.category}）" else ""))
    composer.setVisibility(View.VISIBLE)

  private def checkIn(): Unit =
    selected.foreach { v =>
      val p = if v.lat.isNaN then here.getOrElse(LatLng(0, 0)) else v.latLng
      val ci = CheckIn(0, System.currentTimeMillis(), p.lat, p.lon, v.name, v.category, v.id, None,
        comment.getText.toString.trim, priv.isChecked)
      AshidoriApp.io.execute { () =>
        // Name the matching auto-detected place after the venue, if it has no name yet.
        val places = AshidoriApp.db.places()
        val visit = Visit(ci.time, ci.time, p.lat, p.lon, 30, 1)
        val placeId = Places.nearest(visit, places) match
          case Some(pl) =>
            if pl.name.isEmpty then AshidoriApp.db.updatePlace(pl.copy(name = v.name, externalId = v.id))
            pl.id
          case None => AshidoriApp.db.insertPlace(Place(0, p.lat, p.lon, 50, v.name, PlaceKind.Other, v.id))
        AshidoriApp.db.insertCheckIn(ci.copy(placeId = Some(placeId)))
        act.runOnUiThread { () =>
          Toast.makeText(act, s"${v.name} にチェックインしました", Toast.LENGTH_SHORT).show()
          comment.setText("")
          priv.setChecked(false)
          composer.setVisibility(View.GONE)
          loadHistory()
        }
      }
    }

  private def loadHistory(): Unit =
    AshidoriApp.io.execute { () =>
      val now = System.currentTimeMillis()
      val all = AshidoriApp.db.checkIns(0, now + 1)
      act.runOnUiThread(() => renderHistory(all))
    }

  private def renderHistory(all: Vector[CheckIn]): Unit =
    val zone = AshidoriApp.zone
    val dates = all.map(c => Stats.localDate(c.time, zone))
    val today = LocalDate.now(zone)
    streak.setText(s"🔥 ${Stats.weeklyStreak(dates, today)} 週連続・合計 ${all.size} 回・${all.map(_.venueName).distinct.size} か所")
    history.removeAllViews()
    if all.isEmpty then history.addView(caption("まだチェックインはありません"))
    for c <- all.take(50) do
      val t = Instant.ofEpochMilli(c.time).atZone(zone).format(fmt)
      val title = text((if c.isPrivate then "🔒 " else "") + c.venueName, 15f)
      val sub = caption(t + (if c.comment.nonEmpty then s"「${c.comment}」" else ""))
      history.addView(padded(vbox(title, sub), 0, 6))
