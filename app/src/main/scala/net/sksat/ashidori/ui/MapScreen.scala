package net.sksat.ashidori.ui

import android.os.Bundle
import android.view.{Gravity, View, ViewGroup}
import android.widget.{FrameLayout, HorizontalScrollView, LinearLayout}
import com.google.android.material.chip.{Chip, ChipGroup}
import java.time.LocalDate
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Exploring history across a period: where have I been this week / month /
  * year / ever. Tapping a place opens its sheet, which can jump to a day.
  */
final class MapScreen(act: MainActivity, saved: Bundle) extends Screen:
  private given Ctx = act
  private val map = TrackMap(act, saved)

  private enum Period(val label: String, val days: Int):
    case Week extends Period("1週間", 7)
    case Month extends Period("1か月", 30)
    case Year extends Period("1年", 365)
    case All extends Period("全期間", 36500)

  private var period = Period.Month
  private var places: Map[Long, Place] = Map.empty
  private val status = chip("", color(attr.surface), color(attr.onSurface))

  private def chipRow(single: Boolean)(items: (String, Boolean, Boolean => Unit)*): View =
    val g = ChipGroup(act)
    g.setSingleSelection(single)
    g.setSingleLine(true)
    for (label, checked, onChange) <- items do
      val c = Chip(act, null, com.google.android.material.R.attr.chipStyle)
      c.setText(label)
      c.setCheckable(true)
      c.setChecked(checked)
      c.setOnCheckedChangeListener((_, b) => onChange(b))
      g.addView(c)
    val s = HorizontalScrollView(act)
    s.setHorizontalScrollBarEnabled(false)
    s.addView(g)
    padded(s, 12, 2)

  private val periodRow = chipRow(single = true)(Period.values.toSeq.map { p =>
    (p.label, p == period, (b: Boolean) => if b && period != p then { period = p; load() })
  }*)
  private val layerRow = chipRow(single = false)(
    ("経路", true, (b: Boolean) => map.setLayerVisible("track", b)),
    ("滞在地", true, (b: Boolean) => map.setLayerVisible("visit", b)),
    ("チェックイン", true, (b: Boolean) => map.setLayerVisible("checkin", b))
  )

  val view: View =
    val controls = vbox(periodRow, layerRow)
    controls.setBackgroundColor(color(attr.surface))
    val statusLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    statusLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL
    statusLp.topMargin = dp(8)
    status.setLayoutParams(statusLp)
    val mapFrame = frame(map.view, status)
    mapFrame.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    vbox(controls, mapFrame)

  map.onMarkerClick = (layer, key) =>
    layer match
      case "visit" =>
        places.get(key.toLong).foreach(p => PlaceSheet(act, p, None, () => load(), Some(act.openDay)).show())
      case _ => ()

  override def onShow(): Unit = load()

  private def load(): Unit =
    val p = period
    status.setText("読み込み中…")
    AshidoriApp.io.execute { () =>
      val db = AshidoriApp.db
      val now = System.currentTimeMillis()
      val from = if p == Period.All then 0L else now - p.days * 86_400_000L
      // Coarser sampling for longer periods keeps the map responsive.
      val bucket = p match
        case Period.Week  => 15_000L
        case Period.Month => 60_000L
        case _            => 300_000L
      val fixes = db.fixesSampled(from, now, bucket)
      // Split into separate lines at gaps so separate trips are not joined.
      val paths = fixes.foldLeft(Vector.empty[Vector[Fix]]) { (acc, f) =>
        acc.lastOption match
          case Some(cur) if f.time - cur.last.time < 20 * 60_000 && Geo.distance(cur.last, f) < 5_000 =>
            acc.init :+ (cur :+ f)
          case _ => acc :+ Vector(f)
      }.filter(_.size >= 2)
      val ps = db.places()
      val cs = db.checkIns(from, now + 1)
      act.runOnUiThread { () =>
        places = ps.map(pl => pl.id -> pl).toMap
        map.setData(
          paths.map(p => MapPath(p)),
          ps.map(pl => MapMarker(pl.latLng, pl.id.toString, pl.displayName)),
          cs.map(c => MapMarker(LatLng(c.lat, c.lon), c.id.toString, c.venueName))
        )
        status.setText(s"${p.label}・場所 ${ps.size}・チェックイン ${cs.size}")
      }
    }
