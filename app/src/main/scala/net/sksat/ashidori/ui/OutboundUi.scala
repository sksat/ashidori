package net.sksat.ashidori.ui

import android.graphics.{Canvas, Paint}
import android.view.{Gravity, View, ViewGroup}
import android.widget.LinearLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.SyncConfig
import ashidori.core.*
import Ui.*
import scala.util.chaining.*

/** Names and lines of the external-send settings (docs/design/outbound-and-offline.md 5.5). */
object OutboundUi:
  def name(d: Dest): String = d match
    case Dest.Ofm => "オンライン地図（OpenFreeMap）"
    case Dest.Gsi => "オンライン地図（地理院タイル）"
    case Dest.CustomTiles => "オンライン地図（カスタム）"
    case Dest.Geocoder => "地名検索"
    case Dest.Overpass => "施設のオンライン検索"
    case Dest.Data => "地図・施設データの取得"

  /** What is sent. */
  def what(d: Dest): String = d match
    case Dest.Ofm | Dest.Gsi | Dest.CustomTiles => "表示範囲"
    case Dest.Geocoder => "滞在地・他の端末の位置"
    case Dest.Overpass => "位置"
    // Whole archives only until the region data (design 6).
    case Dest.Data => "全域"

  def whenLabel(w: When): String = w match
    case When.Never => "送信しない"
    case When.OnAction => "操作時のみ"
    case When.Auto => "自動"

  def levelLabel(d: Dest, l: Level): String = l match
    case Level.Area => "地域 約 10 km"
    case Level.District => "地区 約 1 km"
    case Level.Exact => if Dest.Tiles(d) then "制限なし" else "正確"
    case Level.Whole => "全域のみ"
    case Level.Cell => "選んだ区画"

  /** The granularity in a line, e.g. 「（約 1 km）」; nothing without a limit. */
  def levelShort(l: Level): String = l match
    case Level.Area => "（約 10 km）"
    case Level.District => "（約 1 km）"
    case _ => ""

  /** After 「表示範囲を送信」 on the map's button. */
  def levelSuffix(l: Level): String = l match
    case Level.Area => "・約 10 km"
    case Level.District => "・約 1 km"
    case _ => ""

  /** May be sent to now (✓): not never, not paused. */
  def allowed(d: Dest): Boolean = Settings.gate.when(d) != When.Never

  /** The custom map has no URL. */
  private def customUnset: Boolean = MapStyle.custom._1.isEmpty

  /** The row's second line: what · when (with the granularity), or why not. */
  def subtitle(d: Dest): String =
    val g = Settings.gate
    val r = g.rule(d)
    if g.paused then "停止中"
    else if d == Dest.CustomTiles && customUnset then "未設定"
    else if r.when == When.Never then what(d)
    else s"${what(d)}${levelShort(r.level)} · ${whenLabel(r.when)}"

  /** The hosts sent to (the geocoder: whatever the device's provides). */
  def hosts(d: Dest): Seq[String] = d match
    case Dest.Ofm => Seq("tiles.openfreemap.org")
    case Dest.Gsi => Seq("cyberjapandata.gsi.go.jp")
    case Dest.CustomTiles =>
      Seq(MapStyle.custom._1).filter(_.nonEmpty).flatMap(u => UrlType.origin(u.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")))
        .map(_.replaceFirst("^https?://", ""))
    case Dest.Geocoder => Seq("Android の地名検索サービス（端末による）")
    case Dest.Overpass => Policy.OverpassGroups.flatten.filterNot(Settings.gate.rule(Dest.Overpass).off)
    case Dest.Data =>
      Seq("github.com") ++ Option.when(SyncConfig.paired && SyncConfig.serverUrl.nonEmpty)(
        scala.util.Try(java.net.URI(SyncConfig.serverUrl).getHost).toOption.flatMap(Option(_)).getOrElse("同期サーバ"))

  /** The settings row's line. */
  def summary: String =
    val g = Settings.gate
    if g.problem.exists(!_.isInstanceOf[Policy.Problem.Missing]) then "データの手動取得のみ"
    else if g.paused then "外部への送信を停止中"
    else
      val n = Dest.values.count(allowed)
      if n == 0 then "外部への送信なし" else s"外部への送信 $n 件を許可"

  /** ✓ (allowed) or ⊖ (not sent): the shape tells, the colour follows. */
  def mark(ok: Boolean)(using Ctx): android.widget.TextView =
    val t = text(if ok then "✓" else "⊖", 18f, bold = true, colorAttr = if ok then attr.tertiary else attr.onSurfaceVariant)
    t.setGravity(Gravity.CENTER)
    t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    t.setLayoutParams(LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
    t

  /** A section header with the legend at its end. */
  def headerWithLegend(name: String)(using Ctx): View =
    val h = sectionHeader(name)
    h.setLayoutParams(weighted())
    val legend = caption("✓ 許可  ⊖ 送信しない")
    legend.setPadding(0, dp(24), dp(20), dp(8))
    hbox(h, legend)

/** The grid of a level drawn small: the cell a position is sent as, filled
  * (地域: a large cell, 地区: a small one, no limit: the point itself).
  */
final class GridLegend(c: android.content.Context, level: Level) extends View(c):
  private given android.content.Context = c
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)
  line.setStyle(Paint.Style.STROKE)
  line.setStrokeWidth(Ui.dp(1f).toFloat)
  line.setColor(Ui.color(Ui.attr.outlineVariant))
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  fill.setColor(Ui.color(Ui.attr.tertiary))
  setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)

  /** Always 28 dp square, whatever the row's layout asks. */
  override def onMeasure(w: Int, h: Int): Unit =
    val s = Ui.dp(28f)
    setMeasuredDimension(s, s)

  override def onDraw(canvas: Canvas): Unit =
    val s = math.min(getWidth, getHeight).toFloat
    val n = 4
    val cell = s / n
    fill.setAlpha(110)
    level match
      case Level.Area => canvas.drawRect(0, 0, cell * 2, cell * 2, fill)
      case Level.District => canvas.drawRect(cell, cell, cell * 2, cell * 2, fill)
      case _ => ()
    for i <- 0 to n do
      canvas.drawLine(i * cell, 0, i * cell, s, line)
      canvas.drawLine(0, i * cell, s, i * cell, line)
    // The position.
    fill.setAlpha(255)
    canvas.drawCircle(cell * 1.3f, cell * 1.2f, Ui.dp(2.5f).toFloat, fill)

/** 「データの扱い」: where the records are kept and what is sent where, as
  * ✓ / ⊖ rows (design 5.2). A row opens its destination ([[OutboundDestScreen]]).
  */
final class DataHandlingScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "データの扱い"
  private val list = vbox()
  val view: View = scroll(list)
  private var unwatch: () => Unit = () => ()
  /** A change of the pause under way; `stopping` while sends are still ending. */
  private var changing = false
  private var stopping = false
  override def holdsBack: Boolean = changing

  override def onShow(): Unit =
    unwatch()
    unwatch = Settings.gate.onChange(_ => act.runOnUiThread(() => if act.showing(this) then render()))
    render()
  override def onHide(): Unit =
    unwatch()
    unwatch = () => ()

  private def markRow(ok: Option[Boolean], title: String, sub: String, onTap: Option[() => Unit]): ListRow =
    val r = onTap match
      case Some(f) => navRow(title, sub)(f())
      case None => ListRow(act).tap { r => r.title.setText(title); r.setSubtitle(sub) }
    // The mark's column stays for rows without one, so the titles line up.
    val m = ok.fold(View(act).tap(_.setLayoutParams(LinearLayout.LayoutParams(dp(28), 1))))(OutboundUi.mark)
    r.addView(m, 0)
    r.setContentDescription(Seq(Some(title), ok.map(if _ then "許可" else "送信しない"), Some(sub).filter(_.nonEmpty)).flatten.mkString("、"))
    r

  private def render(): Unit =
    list.removeAllViews()
    val g = Settings.gate
    val device = markRow(Some(true), "この端末", "", None)
    AshidoriApp.io.execute { () =>
      val bytes = scala.util.Try(Seq(act.getFilesDir, act.getDatabasePath("x").getParentFile).map(size).sum).getOrElse(0L)
      act.runOnUiThread(() => device.setValue(f"${bytes / 1e6}%.0f MB"))
    }
    val sync =
      if SyncConfig.paired then markRow(Some(true), "同期サーバ", "暗号化して送信", Some(() => act.push(SyncSettingsScreen(act))))
      else markRow(Some(false), "同期サーバ", "未設定", Some(() => act.push(SyncSettingsScreen(act))))
    list.addView(sectionHeader("保存先"))
    list.addView(listGroup(device, sync))

    list.addView(OutboundUi.headerWithLegend("外部への送信"))
    if g.problem.exists(!_.isInstanceOf[Policy.Problem.Missing]) then
      val e = supportingText("設定を読み込めません（データの手動取得のみ）")
      e.setTextColor(color(attr.error))
      e.setPadding(e.getPaddingLeft, 0, e.getPaddingRight, dp(8))
      list.addView(e)
    lazy val pause: SwitchRow = switchRow("外部への送信を停止", g.paused,
        if stopping then "停止中…" else "同期、位置の取得、他のアプリは対象外") { on =>
      if !changing then
        // Off the main thread: done once what was in flight has ended.
        changing = true
        render()
        Net.change(Settings.gate.changePaused(on))(
          returned = c => c match
            case _: SendGate.Change.Stopping =>
              stopping = true
              if act.showing(this) then render()
            case _ => (),
          complete = () =>
            changing = false
            stopping = false
            if act.showing(this) then render())
    }
    pause.setEnabled(!changing)
    pause.switch.setEnabled(!changing)
    val rows = Dest.values.toSeq.map { d =>
      val r = markRow(Some(OutboundUi.allowed(d)), OutboundUi.name(d), OutboundUi.subtitle(d),
        Some(() => if !changing then act.push(OutboundDestScreen(act, d))))
      r.setEnabled(!changing)
      r
    }
    // The OS's positioning: a reference, the app does not control it.
    val location = markRow(None, "位置の取得", "OS の設定による", Some(() =>
      try act.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      catch case _: android.content.ActivityNotFoundException => ()))
    list.addView(listGroup((pause +: rows :+ location)*))
    list.addView(spacer(24))

  private def size(f: java.io.File): Long =
    if f.isFile then f.length else Option(f.listFiles).fold(0L)(_.map(size).sum)

/** One destination: when (送信しない / 操作時のみ / 自動) and how finely, as
  * two groups of radio buttons applied together with 「適用」 (design 5.3).
  * Nothing changes until then; leaving discards the choice.
  */
final class OutboundDestScreen(act: MainActivity, dest: Dest) extends Screen:
  private given Ctx = act
  override val title: String = OutboundUi.name(dest)
  private val list = vbox()
  private val buttons = hbox()
  val view: View =
    val v = vbox(scroll(list).tap(_.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))), buttons)
    v

  private val (whens, levels) = Policy.choices(dest)
  private var rule = Settings.gate.rule(dest)
  private var chosen = rule
  /** 「適用」 under way (inputs and back are held); `stopping` while sends are still ending. */
  private var applying = false
  private var stopping = false
  override def holdsBack: Boolean = applying

  override def onShow(): Unit =
    if !applying then
      rule = Settings.gate.rule(dest)
      chosen = rule
    render()

  private def radioRow(label: String, sub: String, on: Boolean, trailing: Option[View])(pick: => Unit): ListRow =
    val r = ListRow(act)
    r.title.setText(label)
    r.setSubtitle(sub)
    val b = MaterialRadioButton(act)
    b.setChecked(on)
    b.setClickable(false)
    b.setFocusable(false)
    b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    r.addView(b, 0)
    trailing.foreach(r.trailing)
    r.onTap(if !applying then pick)
    r.setEnabled(!applying)
    b.setEnabled(!applying)
    a11yRole(r, classOf[android.widget.RadioButton], Some(() => on))
    r

  private def legend(l: Level): View =
    val v = GridLegend(act, l)
    v.setLayoutParams(LinearLayout.LayoutParams(dp(28), dp(28)))
    v

  private def render(): Unit =
    list.removeAllViews()
    if stopping then
      val p = supportingText("停止中…")
      p.setPadding(p.getPaddingLeft, dp(12), p.getPaddingRight, 0)
      list.addView(p)
    else if Settings.gate.paused then
      val p = supportingText("外部への送信を停止中")
      p.setPadding(p.getPaddingLeft, dp(12), p.getPaddingRight, 0)
      list.addView(p)
    list.addView(sectionHeader("送信"))
    list.addView(listGroup(whens.map { w =>
      val sub = if w == When.OnAction && Dest.Tiles(dest) then "表示を許した地図は、戻すまで保持" else ""
      radioRow(OutboundUi.whenLabel(w), sub, chosen.when == w, None) { chosen = chosen.copy(when = w); render() }
    }*))
    // The data's level waits for the region data (design 6): whole archives only now.
    if dest != Dest.Data then
      list.addView(sectionHeader("送信する粒度"))
      list.addView(listGroup(levels.map { l =>
        radioRow(OutboundUi.levelLabel(dest, l), "", chosen.level == l, Some(legend(l))) { chosen = chosen.copy(level = l); render() }
      }*))
    if dest == Dest.Overpass then
      list.addView(sectionHeader("検索先"))
      val groups = Policy.OverpassGroups
      list.addView(listGroup(groups.map { hosts =>
        val on = !hosts.exists(chosen.off)
        val others = groups.filterNot(_ == hosts).exists(h => !h.exists(chosen.off))
        val row = switchRow(hosts.mkString("、"), on) { b =>
          if !applying then
            chosen = chosen.copy(off = if b then chosen.off -- hosts else chosen.off ++ hosts)
          render()
        }
        // All of them off is 「送信しない」.
        row.setEnabled(!applying && (!on || others))
        row.switch.setEnabled(!applying && (!on || others))
        row
      }*))
    val hostLine = OutboundUi.hosts(dest).mkString("、")
    val target =
      if dest == Dest.CustomTiles then
        navRow("送信先", if hostLine.nonEmpty then hostLine else "未設定")(if !applying then MapStyle.editCustom(act)(() => render()))
      else ListRow(act).tap { r => r.title.setText("送信先"); r.setSubtitle(hostLine) }
    val extra = Option.when(dest == Dest.Geocoder)(ListRow(act).tap { r => r.title.setText("対象"); r.setSubtitle(OutboundUi.what(dest)) })
    if dest != Dest.Overpass then
      list.addView(spacer(16))
      list.addView(listGroup((target +: extra.toSeq)*))
    list.addView(spacer(24))

    buttons.removeAllViews()
    buttons.setGravity(Gravity.END)
    buttons.setPadding(dp(16), dp(8), dp(16), dp(12))
    val cancel = com.google.android.material.button.MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle)
    cancel.setText("キャンセル")
    cancel.setOnClickListener(_ => if !applying && act.showing(this) then act.pop())
    lazy val apply: com.google.android.material.button.MaterialButton = button("適用") {
      if !applying then
        // Off the main thread; this screen closes once what was in flight has ended.
        applying = true
        render()
        val r = chosen
        Net.change(Settings.gate.changeRule(dest, r))(
          returned = c => c match
            case _: SendGate.Change.Stopping =>
              stopping = true
              if act.showing(this) then render()
            case _ => (),
          complete = () =>
            applying = false
            stopping = false
            // Only this screen, and only while it is the one shown.
            if act.showing(this) then act.pop())
    }
    apply.setEnabled(!applying && chosen != rule && Policy.valid(dest, chosen))
    cancel.setEnabled(!applying)
    val lp = wrap
    lp.setMarginStart(dp(8))
    apply.setLayoutParams(lp)
    buttons.addView(cancel)
    buttons.addView(apply)
