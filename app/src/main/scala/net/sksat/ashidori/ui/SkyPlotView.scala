package net.sksat.ashidori.ui

import android.content.Context
import android.graphics.{Canvas, Paint}
import android.location.GnssStatus
import android.view.View
import net.sksat.ashidori.SatSignal

object Constellations:
  def name(t: Int): String = t match
    case GnssStatus.CONSTELLATION_GPS     => "GPS"
    case GnssStatus.CONSTELLATION_QZSS    => "QZSS"
    case GnssStatus.CONSTELLATION_GLONASS => "GLONASS"
    case GnssStatus.CONSTELLATION_GALILEO => "Galileo"
    case GnssStatus.CONSTELLATION_BEIDOU  => "BeiDou"
    case GnssStatus.CONSTELLATION_SBAS    => "SBAS"
    case GnssStatus.CONSTELLATION_IRNSS   => "NavIC"
    case _                                => "?"

  def color(t: Int): Int = t match
    case GnssStatus.CONSTELLATION_GPS     => 0xff4285f4
    case GnssStatus.CONSTELLATION_QZSS    => 0xffe8710a
    case GnssStatus.CONSTELLATION_GLONASS => 0xffd93025
    case GnssStatus.CONSTELLATION_GALILEO => 0xff1e8e3e
    case GnssStatus.CONSTELLATION_BEIDOU  => 0xff9334e6
    case _                                => 0xff80868b

/** Polar sky plot: azimuth around, elevation inward. Filled = used in fix,
  * outer ring = L5-band signal also tracked.
  */
final class SkyPlotView(c: Context) extends View(c):
  private given Context = c
  var sats: Vector[SatSignal] = Vector.empty
  private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
  private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  grid.setStyle(Paint.Style.STROKE)
  grid.setStrokeWidth(Ui.dp(1).toFloat)
  label.setTextSize(Ui.dp(9).toFloat)
  label.setTextAlign(Paint.Align.CENTER)

  def update(s: Vector[SatSignal]): Unit =
    sats = s
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    val size = View.MeasureSpec.getSize(w)
    setMeasuredDimension(size, math.min(size, Ui.dp(240)))

  override def onDraw(canvas: Canvas): Unit =
    val cx = getWidth / 2f
    val cy = getHeight / 2f
    val r = math.min(cx, cy) - Ui.dp(12)
    grid.setColor(Ui.color(Ui.attr.outlineVariant))
    for f <- Seq(1f, 2f / 3, 1f / 3) do canvas.drawCircle(cx, cy, r * f, grid)
    canvas.drawLine(cx - r, cy, cx + r, cy, grid)
    canvas.drawLine(cx, cy - r, cx, cy + r, grid)
    label.setColor(Ui.color(Ui.attr.onSurfaceVariant))
    canvas.drawText("N", cx, cy - r - Ui.dp(2), label)
    // Group signals of the same satellite (L1 + L5) into one marker.
    val bySat = sats.groupBy(s => (s.constellation, s.svid))
    for ((const, svid), sigs) <- bySat do
      val s = sigs.maxBy(_.cn0)
      val rr = r * (90 - s.elevation) / 90
      val a = math.toRadians(s.azimuth - 90)
      val x = cx + rr * math.cos(a).toFloat
      val y = cy + rr * math.sin(a).toFloat
      val col = Constellations.color(const)
      dot.setColor(col)
      dot.setStyle(if sigs.exists(_.usedInFix) then Paint.Style.FILL else Paint.Style.STROKE)
      dot.setStrokeWidth(Ui.dp(1.5f).toFloat)
      canvas.drawCircle(x, y, Ui.dp(6).toFloat, dot)
      if sigs.exists(_.band == "L5") then
        dot.setStyle(Paint.Style.STROKE)
        canvas.drawCircle(x, y, Ui.dp(9).toFloat, dot)
      label.setColor(Ui.color(Ui.attr.onSurface))
      canvas.drawText(svid.toString, x, y - Ui.dp(10), label)

/** Horizontal C/N0 bars per signal, strongest first. */
final class Cn0BarsView(c: Context) extends View(c):
  private given Context = c
  var sats: Vector[SatSignal] = Vector.empty
  private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
  private val label = Paint(Paint.ANTI_ALIAS_FLAG)
  label.setTextSize(Ui.dp(8).toFloat)
  label.setTextAlign(Paint.Align.CENTER)

  def update(s: Vector[SatSignal]): Unit =
    sats = s.filter(_.cn0 > 0).sortBy(-_.cn0).take(32)
    invalidate()

  override def onMeasure(w: Int, h: Int): Unit =
    setMeasuredDimension(View.MeasureSpec.getSize(w), Ui.dp(96))

  override def onDraw(canvas: Canvas): Unit =
    if sats.isEmpty then return
    val h = getHeight - Ui.dp(14)
    val bw = getWidth.toFloat / math.max(sats.size, 12)
    label.setColor(Ui.color(Ui.attr.onSurfaceVariant))
    for (s, i) <- sats.zipWithIndex do
      val frac = math.min(1f, s.cn0 / 50f)
      bar.setColor(Constellations.color(s.constellation))
      bar.setAlpha(if s.usedInFix then 255 else 110)
      val x = i * bw
      canvas.drawRect(x + 1, h * (1 - frac), x + bw - 1, h.toFloat, bar)
      canvas.drawText(if s.band == "L5" then "5" else s.svid.toString, x + bw / 2, getHeight.toFloat - Ui.dp(2), label)
