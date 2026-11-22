package net.sksat.ashidori.ui

import android.view.View
import com.google.android.material.progressindicator.LinearProgressIndicator
import net.sksat.ashidori.*
import Ui.*

/** What the bar of a download shows. */
enum Progress:
  /** Nothing runs (it waits, is paused, or is done): no bar. */
  case Hidden
  /** It runs without a size to tell (a bundle, the check, the unpacking): a moving bar. */
  case Busy
  /** `done` of `total` bytes. */
  case Part(done: Long, total: Long)

/** The bar of a download (Material's linear indicator) and its short text. */
object DownloadBar:
  def apply()(using c: Ctx): LinearProgressIndicator =
    val b = LinearProgressIndicator(c)
    b.setMax(1000)
    b.setVisibility(View.GONE)
    b

  def show(b: LinearProgressIndicator, p: Progress): Unit = p match
    case Progress.Hidden => b.setVisibility(View.GONE)
    case Progress.Busy =>
      if !b.isIndeterminate then
        // The mode changes only while it is hidden.
        b.setVisibility(View.INVISIBLE)
        b.setIndeterminate(true)
      b.setVisibility(View.VISIBLE)
    case Progress.Part(d, t) =>
      if b.isIndeterminate then
        b.setVisibility(View.INVISIBLE)
        b.setIndeterminate(false)
      val v = if t <= 0 then 0 else (d.min(t) * 1000 / t).toInt
      b.setProgressCompat(v, b.getVisibility == View.VISIBLE)
      b.setVisibility(View.VISIBLE)

  /** 「12.3 / 45.6 MB」 (SI units, as the sizes elsewhere). */
  def bytes(done: Long, total: Long): String =
    if total >= 1_000_000_000L then f"${done / 1e9}%.2f / ${total / 1e9}%.2f GB"
    else f"${done / 1e6}%.1f / ${total / 1e6}%.1f MB"

  /** The bar of a pinned download: its bytes, then (all there) the check and the unpacking. */
  def of(st: BasemapStatus): Progress = st match
    case BasemapStatus.Running(d, t, _) => if t > 0 && d < t then Progress.Part(d, t) else Progress.Busy
    case _ => Progress.Hidden

  /** The short text beside that bar in a list row. */
  def line(st: BasemapStatus): Option[String] = st match
    case BasemapStatus.Running(d, t, _) => Some(if t > 0 && d < t then s"取得中 · ${bytes(d, t)}" else "照合・展開中")
    case _ => None

  /** Under the bar where the state is told above it (a dialog): the bytes so far. */
  def amount(st: BasemapStatus): Option[String] = st match
    case BasemapStatus.Running(d, t, _) if t > 0 && d < t => Some(bytes(d, t))
    case _ => None

  /** The bar of a region in the list (its bytes on the device, of all it needs). */
  def of(a: ashidori.core.RegionProgress): Progress = a match
    case ashidori.core.RegionProgress.Bytes(d, t, _) if t > 0 => Progress.Part(d, t)
    case ashidori.core.RegionProgress.Idle => Progress.Hidden
    case _ => Progress.Busy
