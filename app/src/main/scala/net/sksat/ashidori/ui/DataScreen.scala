package net.sksat.ashidori.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.util.{Try, Using}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Export to Google-compatible Timeline.json or GPX, and import Google
  * Timeline exports so older history shows up in ashidori.
  */
final class DataScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "エクスポート・インポート"
  private val status = caption("")
  private var days = 30

  val view: View =
    scroll(
      vbox(
        card(
          text("エクスポート", 16f, bold = true),
          caption("直近の記録をファイルにして共有します。非公開のチェックインは含めません。"),
          hbox(
            outlined("7日")(setDays(7)),
            outlined("30日")(setDays(30)),
            outlined("1年")(setDays(365))
          ),
          button("Timeline.json（Google 互換）", tonal = true)(exportAs("json")),
          button("GPX", tonal = true)(exportAs("gpx"))
        ),
        card(
          text("インポート", 16f, bold = true),
          caption("Google マップのタイムラインから書き出した Timeline.json、または旧 Takeout の Records.json を読み込みます。"),
          button("ファイルを選んで読み込む")(act.pickFile(importFile))
        ),
        card(status),
        spacer(24)
      )
    )

  override def onShow(): Unit = status.setText(s"エクスポートの期間: 直近 $days 日")

  private def setDays(d: Int): Unit =
    days = d
    onShow()

  private def exportAs(kind: String): Unit =
    val n = days
    status.setText("書き出し中…")
    AshidoriApp.io.execute { () =>
      val r = Try {
        val today = LocalDate.now(AshidoriApp.zone)
        val tls = (0 until n).reverse.map(i => TimelineRepo.day(today.minusDays(i)))
        val segs = tls.flatMap(_.segments)
        val places = tls.flatMap(_.places).toMap
        val content =
          if kind == "json" then TimelineJson.exportTimeline(segs, places, AshidoriApp.zone)
          else TimelineJson.exportGpx(segs, places, tls.flatMap(_.checkIns))
        val dir = File(act.getCacheDir, "export")
        dir.mkdirs()
        val f = File(dir, if kind == "json" then s"ashidori-Timeline-$today.json" else s"ashidori-$today.gpx")
        java.nio.file.Files.writeString(f.toPath, content)
        f
      }
      act.runOnUiThread { () =>
        r.fold(
          e => status.setText(s"書き出せませんでした: ${e.getMessage}"),
          f => {
            status.setText(s"${f.getName}（${f.length / 1024} KB）")
            val uri = FileProvider.getUriForFile(act, s"${act.getPackageName}.files", f)
            val send = Intent(Intent.ACTION_SEND)
              .setType(if kind == "json" then "application/json" else "application/gpx+xml")
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, f.getName))
          }
        )
      }
    }

  private def importFile(uri: Uri): Unit =
    status.setText("読み込み中…（大きなファイルは時間がかかります）")
    AshidoriApp.io.execute { () =>
      val r = Try {
        val json = Using.resource(act.getContentResolver.openInputStream(uri))(in => String(in.readAllBytes(), StandardCharsets.UTF_8))
        val imp = TimelineJson.importAny(json)
        val db = AshidoriApp.db
        imp.fixes.grouped(5000).foreach(db.insertFixes)
        // Google's place names and labels seed our places; its activity
        // types become mode corrections so trips keep Google's labels.
        val known = db.places()
        for (v, name, kind) <- imp.visits if Places.nearest(v, known).isEmpty do
          db.insertPlace(Place(0, v.lat, v.lon, 60, name.getOrElse(""), kind))
        for (s, e, m) <- imp.activities if m != TransportMode.Unknown do db.setModeOverride(s, e, m)
        TimelineRepo.invalidate()
        imp
      }
      act.runOnUiThread { () =>
        status.setText(r.fold(
          e => s"読み込めませんでした: ${e.getMessage}",
          i => f"${i.fixes.size}%,d 点、滞在 ${i.visits.size}%,d 件、移動 ${i.activities.size}%,d 件を読み込みました"
        ))
      }
    }
