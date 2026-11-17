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
  private val today = LocalDate.now(AshidoriApp.zone)
  /** Export range, both ends included. */
  private var from = today.minusDays(6)
  private var to = today
  private val rangeLabel = text("", 18f, bold = true)
  private val dayFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d (E)", java.util.Locale.JAPANESE)

  private def setRange(a: LocalDate, b: LocalDate): Unit =
    from = if a.isAfter(b) then b else a
    to = if a.isAfter(b) then a else b
    val n = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
    rangeLabel.setText(
      if from == to then from.format(dayFmt)
      else s"${from.format(dayFmt)}\n〜 ${to.format(dayFmt)}（${n}日間）")

  /** Quick ranges; the calendar picks anything else. */
  private val presets: Seq[(String, () => (LocalDate, LocalDate))] =
    val monday = today.`with`(java.time.DayOfWeek.MONDAY)
    Seq(
      "今日" -> (() => (today, today)),
      "昨日" -> (() => (today.minusDays(1), today.minusDays(1))),
      "今週" -> (() => (monday, today)),
      "先週" -> (() => (monday.minusWeeks(1), monday.minusDays(1))),
      "今月" -> (() => (today.withDayOfMonth(1), today)),
      "先月" -> (() => (today.minusMonths(1).withDayOfMonth(1), today.withDayOfMonth(1).minusDays(1))),
      "今年" -> (() => (today.withDayOfYear(1), today)),
      "すべて" -> (() => (AshidoriApp.db.firstDataDateCached.getOrElse(today.minusYears(1)), today))
    )
  private val presetChips =
    val g = com.google.android.material.chip.ChipGroup(act)
    for (label, f) <- presets do
      val c = com.google.android.material.chip.Chip(act)
      c.setText(label)
      c.setOnClickListener { _ =>
        val (a, b) = f()
        setRange(a, b)
      }
      g.addView(c)
    g

  private def pickRange(): Unit =
    import com.google.android.material.datepicker.*
    def utc(d: LocalDate): java.lang.Long = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant.toEpochMilli
    val picker = MaterialDatePicker.Builder.dateRangePicker()
      .setTitleText("書き出す期間")
      .setSelection(androidx.core.util.Pair(utc(from), utc(to)))
      .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
      .build()
    picker.addOnPositiveButtonClickListener { sel =>
      def day(ms: java.lang.Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate
      setRange(day(sel.first), day(sel.second))
    }
    picker.show(act.getSupportFragmentManager, "export-range")

  private def format(label: String, note: String)(f: => Unit): View =
    navRow(label, note, trailingIcon = net.sksat.ashidori.R.drawable.ic_share)(f)

  /** Result of the last export or import; hidden until there is one. */
  private val statusCard =
    val c = card(status)
    c.setVisibility(View.GONE)
    c

  val view: View =
    scroll(
      vbox(
        sectionHeader("エクスポート"),
        card(
          rangeLabel,
          spacer(4),
          outlined("カレンダーで選ぶ")(pickRange()),
          presetChips
        ),
        spacer(6),
        listGroup(
          format("Timeline.json", "Google マップのタイムライン互換")(exportAs("json")),
          format("GPX", "経路と滞在地")(exportAs("gpx")),
          format("記録データ（Parquet・zip）", "位置・動き・電池・衛星の状態・生データ")(exportAs("parquet")),
          format("GNSS ログ（GnssLogger 形式・zip）", "衛星の生データ。解析ツール向け")(exportAs("gnsslog"))
        ),
        supportingText("非公開のチェックインは含めません。"),
        sectionHeader("インポート"),
        listGroup(
          navRow("ファイルを選んで読み込む", "Google マップの Timeline.json、旧 Takeout の Records.json")(act.pickFile(importFile))
        ),
        spacer(12),
        statusCard,
        spacer(24)
      )
    )

  setRange(from, to)
  AshidoriApp.io.execute(() => AshidoriApp.db.firstDataDateCached)

  private def showStatus(s: String): Unit =
    status.setText(s)
    statusCard.setVisibility(View.VISIBLE)

  private def exportAs(kind: String): Unit =
    val (a, b) = (from, to)
    showStatus("書き出し中…")
    def progress(msg: String): Unit = act.runOnUiThread(() => status.setText(msg))
    AshidoriApp.io.execute { () =>
      val r = Try {
        val dir = File(act.getCacheDir, "export")
        dir.mkdirs()
        Option(dir.listFiles()).toSeq.flatten.foreach(_.delete())
        val range = if a == b then s"$a" else s"${a}_$b"
        kind match
          case "parquet" =>
            val f = File(dir, s"ashidori-data-$range.zip")
            if Exporter.parquetZip(a, b, f, progress) == 0 then throw IllegalStateException("この期間の記録はありません")
            f
          case "gnsslog" =>
            val f = File(dir, s"ashidori-gnss-$range.zip")
            if Exporter.gnssLogZip(a, b, f, progress) == 0 then throw IllegalStateException("この期間の生データはありません")
            f
          case _ =>
            val tls = Iterator.iterate(a)(_.plusDays(1)).takeWhile(!_.isAfter(b)).map(TimelineRepo.day).toVector
            val segs = tls.flatMap(_.segments)
            val places = tls.flatMap(_.places).toMap
            val content =
              if kind == "json" then TimelineJson.exportTimeline(segs, places, AshidoriApp.zone)
              else TimelineJson.exportGpx(segs, places, tls.flatMap(_.checkIns))
            val f = File(dir, if kind == "json" then s"ashidori-Timeline-$range.json" else s"ashidori-$range.gpx")
            java.nio.file.Files.writeString(f.toPath, content)
            f
      }
      act.runOnUiThread { () =>
        r.fold(
          e => status.setText(s"書き出せませんでした: ${e.getMessage}"),
          f => {
            status.setText(s"${f.getName}（${if f.length >= (1L << 20) then f"${f.length / 1048576.0}%.1f MB" else s"${f.length / 1024} KB"}）")
            val uri = FileProvider.getUriForFile(act, s"${act.getPackageName}.files", f)
            val send = Intent(Intent.ACTION_SEND)
              .setType(kind match
                case "json" => "application/json"
                case "gpx"  => "application/gpx+xml"
                case _      => "application/zip")
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, f.getName))
          }
        )
      }
    }

  private def importFile(uri: Uri): Unit =
    showStatus("読み込み中…")
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
