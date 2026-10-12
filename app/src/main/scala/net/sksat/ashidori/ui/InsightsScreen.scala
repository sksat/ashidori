package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.view.View
import android.widget.{HorizontalScrollView, LinearLayout}
import com.google.android.material.chip.{Chip, ChipGroup}
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Try
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Looking back over a period: how I moved, how much I walked, where I went. */
final class InsightsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val periods = Seq("1週間" -> 7, "1か月" -> 30, "1年" -> 365)
  private var days = 30
  private val body = vbox(padded(caption("集計中…"), 16, 16))
  private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")

  private val periodRow =
    val g = ChipGroup(act)
    g.setSingleSelection(true)
    g.setSelectionRequired(true)
    for (label, d) <- periods do
      val c = Chip(act, null, com.google.android.material.R.attr.chipStyle)
      c.setText(label)
      c.setCheckable(true)
      c.setChecked(d == days)
      c.setOnCheckedChangeListener((_, b) => if b && days != d then { days = d; onShow() })
      g.addView(c)
    padded(g, 12, 4)

  val view: View = scroll(vbox(periodRow, body, spacer(24)))

  private final case class Result(
      days: Vector[DayTimeline],
      byMode: Map[TransportMode, Double],
      places: Vector[(Place, Int, Long)],
      regions: Map[Long, (String, String)],
      checkIns: Vector[CheckIn]
  )

  override def onShow(): Unit =
    val n = days
    body.removeAllViews()
    body.addView(padded(caption("集計中…"), 16, 16))
    AshidoriApp.io.execute { () =>
      val today = LocalDate.now(AshidoriApp.zone)
      val tls = (0 until n).map(i => TimelineRepo.day(today.minusDays(i))).toVector
      val visits = tls.flatMap(_.segments.collect { case v: Visit => v })
      val db = AshidoriApp.db
      // Auto-label home / work once there is enough evidence.
      val known = db.places().map(p => p.id -> p).toMap
      for (id, k) <- Places.inferHomeWork(visits, AshidoriApp.zone); p <- known.get(id) if p.kind == PlaceKind.Other do
        db.updatePlace(p.copy(kind = k))
      val places = db.places().map(p => p.id -> p).toMap
      val ranked = visits.flatMap(v => v.placeId.map(_ -> v)).groupBy(_._1).toVector.flatMap { (id, vs) =>
        places.get(id).map(p => (p, vs.size, vs.map(_._2.durationMs).sum))
      }.sortBy((_, cnt, ms) => (-cnt, -ms))
      val regions = resolveRegions(ranked.map(_._1).take(40), db.placeRegions())
      val from = today.minusDays(n - 1).atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli
      val r = Result(
        tls,
        tls.flatMap(_.summary.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _),
        ranked,
        regions,
        db.checkIns(from, System.currentTimeMillis() + 1)
      )
      act.runOnUiThread(() => if days == n then render(r))
    }

  /** City / country per place via the platform geocoder, cached in the DB. */
  private def resolveRegions(ps: Seq[Place], cached: Map[Long, (String, String)]): Map[Long, (String, String)] =
    if !Geocoder.isPresent then cached
    else
      val g = Geocoder(act, Locale.JAPANESE)
      ps.filterNot(p => cached.contains(p.id)).take(10).foldLeft(cached) { (acc, p) =>
        @annotation.nowarn("cat=deprecation")
        val r = Try(g.getFromLocation(p.lat, p.lon, 1)).toOption.flatMap(l => Option(l).flatMap(_.asScala.headOption))
        r.fold(acc) { a =>
          val city = Option(a.getLocality).orElse(Option(a.getSubAdminArea)).getOrElse("")
          val country = Option(a.getCountryName).getOrElse("")
          AshidoriApp.db.setPlaceRegion(p.id, city, country)
          acc + (p.id -> (city, country))
        }
      }

  private def bar(frac: Double, col: Int): View =
    val outer = LinearLayout(act)
    val fill = View(act)
    val d = GradientDrawable()
    d.setCornerRadius(dp(4).toFloat)
    d.setColor(col)
    fill.setBackground(d)
    fill.setLayoutParams(LinearLayout.LayoutParams(0, dp(10), frac.toFloat.max(0.01f)))
    val rest = View(act)
    rest.setLayoutParams(LinearLayout.LayoutParams(0, dp(10), (1 - frac).toFloat.max(0f)))
    outer.addView(fill)
    outer.addView(rest)
    outer.setLayoutParams(weighted(2f))
    outer

  private def labeled(label: String, w: Float): View =
    val l = text(label, 14f)
    l.setLayoutParams(weighted(w))
    l

  private def render(r: Result): Unit =
    body.removeAllViews()
    val total = r.byMode.values.sum
    val steps = r.days.map(_.summary.steps)
    val active = r.days.count(_.segments.nonEmpty)
    body.addView(
      card(
        hbox(stat(fmtDistance(total), "移動"), stat(f"${steps.sum}%,d", "歩数"), stat(s"${r.places.size}", "場所")),
        caption(f"記録のある日 $active 日・1日平均 ${if active > 0 then steps.sum / active else 0}%,d 歩")
      )
    )

    val modeRows = r.byMode.toSeq.sortBy(-_._2).map { (m, d) =>
      padded(hbox(labeled(s"${m.emoji} ${m.label}", 1.2f), bar(if total > 0 then d / total else 0, color(attr.primary)), caption(fmtDistance(d))), 0, 4)
    }
    body.addView(card((text("移動手段", 16f, bold = true) +: orEmpty(modeRows))*))

    val maxSteps = steps.maxOption.getOrElse(0).max(1)
    val dayFmt = DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPANESE)
    val stepRows = r.days.take(14).map { d =>
      val row = padded(hbox(labeled(d.date.format(dayFmt), 0.9f), bar(d.summary.steps.toDouble / maxSteps, color(attr.tertiary)), caption(f"${d.summary.steps}%,d")), 0, 2)
      row.setMinimumHeight(dp(32))
      row.setOnClickListener(_ => act.openDay(d.date))
      row
    }
    body.addView(card((text("歩数", 16f, bold = true) +: stepRows)*))

    val placeRows = r.places.take(15).map { (p, n, ms) =>
      val region = r.regions.get(p.id).map((c, _) => c).filter(_.nonEmpty).map(c => s"・$c").getOrElse("")
      val name = vbox(text((if p.kind != PlaceKind.Other then s"[${p.kind.label}] " else "") + p.displayName, 15f), caption(s"${n}回・${fmtDuration(ms)}$region"))
      name.setMinimumHeight(dp(52))
      name.setOnClickListener(_ => PlaceSheet(act, p, None, () => onShow(), Some(act.openDay)).show())
      name
    }
    body.addView(card((text("よく行く場所", 16f, bold = true) +: orEmpty(placeRows))*))

    val byCountry = r.regions.values.groupBy(_._2).toSeq.filter(_._1.nonEmpty).sortBy(-_._2.size)
    val regionRows = byCountry.map { (country, cs) =>
      val cities = cs.map(_._1).filter(_.nonEmpty).toSeq.distinct
      vbox(text(s"$country（${cities.size} 都市）", 15f), caption(cities.take(12).mkString("・")))
    }
    body.addView(card((text("都市・国", 16f, bold = true) +: orEmpty(regionRows))*))

    val zone = AshidoriApp.zone
    val all = r.checkIns
    val dates = all.map(c => Stats.localDate(c.time, zone))
    val streak = Stats.weeklyStreak(dates, LocalDate.now(zone))
    val ciRows = all.take(20).map { c =>
      val t = Instant.ofEpochMilli(c.time).atZone(zone).format(timeFmt)
      val v = vbox(text((if c.isPrivate then "🔒 " else "") + c.venueName, 15f), caption(t + (if c.comment.nonEmpty then s"「${c.comment}」" else "")))
      v.setMinimumHeight(dp(48))
      v.setOnClickListener(_ => act.openDay(Stats.localDate(c.time, zone)))
      v
    }
    body.addView(
      card(
        (Seq(
          text("チェックイン", 16f, bold = true),
          caption(s"🔥 $streak 週連続・${all.size} 回・${all.map(_.venueName).distinct.size} か所")
        ) ++ orEmpty(ciRows))*
      )
    )

  private def orEmpty(rows: Seq[View]): Seq[View] = if rows.isEmpty then Seq(caption("この期間のデータはありません")) else rows

  private def stat(value: String, label: String): View =
    val v = vbox(text(value, 20f, bold = true), caption(label))
    v.setGravity(android.view.Gravity.CENTER)
    v.setLayoutParams(weighted())
    v
