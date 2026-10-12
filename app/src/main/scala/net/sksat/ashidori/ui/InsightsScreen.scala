package net.sksat.ashidori.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Last-30-days summary: distance by mode, steps per day, top places. */
final class InsightsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  private val Days = 30
  private val body = vbox(padded(caption("集計中…"), 16, 16))
  val view: View = scroll(vbox(body, spacer(24)))

  private final case class Result(
      days: Vector[DayTimeline],
      byMode: Map[TransportMode, Double],
      places: Vector[(Place, Int, Long)]
  )

  override def onShow(): Unit =
    AshidoriApp.io.execute { () =>
      val today = LocalDate.now(AshidoriApp.zone)
      val days = (0 until Days).map(i => TimelineRepo.day(today.minusDays(i))).toVector
      val visits = days.flatMap(_.segments.collect { case v: Visit => v })
      // Auto-label home / work once there is enough evidence.
      val kinds = Places.inferHomeWork(visits, AshidoriApp.zone)
      val known = AshidoriApp.db.places().map(p => p.id -> p).toMap
      for (id, k) <- kinds; p <- known.get(id) if p.kind == PlaceKind.Other do AshidoriApp.db.updatePlace(p.copy(kind = k))
      val places = AshidoriApp.db.places().map(p => p.id -> p).toMap
      val ranked = visits.flatMap(v => v.placeId.map(_ -> v)).groupBy(_._1).toVector.flatMap { (id, vs) =>
        places.get(id).map(p => (p, vs.size, vs.map(_._2.durationMs).sum))
      }.sortBy((_, n, ms) => (-n, -ms))
      val byMode = days.flatMap(_.summary.distanceByMode).groupMapReduce(_._1)(_._2)(_ + _)
      val r = Result(days, byMode, ranked)
      act.runOnUiThread(() => render(r))
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

  private def render(r: Result): Unit =
    body.removeAllViews()
    val total = r.byMode.values.sum
    val steps = r.days.map(_.summary.steps)
    val activeDays = r.days.count(d => d.segments.nonEmpty)
    body.addView(
      card(
        text(s"直近${Days}日", 18f, bold = true),
        caption(f"移動 ${fmtDistance(total)}・歩数 ${steps.sum}%,d（1日平均 ${if activeDays > 0 then steps.sum / activeDays else 0}%,d）・記録のある日 $activeDays")
      )
    )
    val modeRows = r.byMode.toSeq.sortBy(-_._2).map { (m, d) =>
      val l = text(s"${m.emoji} ${m.label}", 14f)
      l.setLayoutParams(weighted(1.2f))
      val v = caption(fmtDistance(d))
      v.setLayoutParams(weighted(0.8f))
      padded(hbox(l, bar(if total > 0 then d / total else 0, color(attr.primary)), v), 0, 4)
    }
    body.addView(card((text("移動手段別の距離", 16f, bold = true) +: (if modeRows.isEmpty then Seq(caption("データなし")) else modeRows))*))

    val maxSteps = steps.maxOption.getOrElse(0).max(1)
    val dayFmt = DateTimeFormatter.ofPattern("M/d")
    val stepRows = r.days.take(14).map { d =>
      val l = caption(d.date.format(dayFmt))
      l.setLayoutParams(weighted(0.6f))
      val v = caption(f"${d.summary.steps}%,d")
      v.setLayoutParams(weighted(0.8f))
      padded(hbox(l, bar(d.summary.steps.toDouble / maxSteps, color(attr.tertiary)), v), 0, 2)
    }
    body.addView(card((text("歩数（直近14日）", 16f, bold = true) +: stepRows)*))

    val placeRows = r.places.take(15).map { (p, n, ms) =>
      val name = text((if p.kind != PlaceKind.Other then s"[${p.kind.label}] " else "") + p.displayName, 14f)
      name.setLayoutParams(weighted())
      val row = padded(hbox(name, caption(s"${n}回・${fmtDuration(ms)}")), 0, 6)
      row.setOnClickListener(_ => act.renamePlace(Some(p), () => onShow()))
      row
    }
    body.addView(card((text("よく行く場所", 16f, bold = true) +: (if placeRows.isEmpty then Seq(caption("データなし")) else placeRows))*))
