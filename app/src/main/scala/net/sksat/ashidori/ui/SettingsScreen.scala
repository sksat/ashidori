package net.sksat.ashidori.ui

import android.view.{Gravity, View}
import com.google.android.material.materialswitch.MaterialSwitch
import net.sksat.ashidori.*
import net.sksat.ashidori.sync.SyncConfig
import Ui.*

/** Settings hub. Each row says what it is and its current state. */
final class SettingsScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "設定"

  private def item(titleText: String, sub: String)(onClick: => Unit): View =
    val v = vbox(text(titleText, 16f), caption(sub))
    v.setMinimumHeight(dp(64))
    v.setGravity(Gravity.CENTER_VERTICAL)
    v.setBackgroundResource(android.R.drawable.list_selector_background)
    v.setOnClickListener(_ => onClick)
    padded(v, 20, 8)

  private def section(name: String): View = padded(text(name, 14f, bold = true, colorAttr = attr.primary), 20, 12)

  private val list = vbox()
  val view: View = scroll(list)

  override def onShow(): Unit =
    list.removeAllViews()
    list.addView(section("記録"))
    list.addView(item("記録モード", s"${Settings.mode.label}・${ModeInfo.battery(Settings.mode)}")(act.push(LoggerScreen(act))))
    val venues = MaterialSwitch(act)
    venues.setText("近くの施設を OpenStreetMap で検索")
    venues.setChecked(Settings.onlineVenues)
    venues.setMinHeight(dp(56))
    venues.setOnCheckedChangeListener((_, b) => Settings.onlineVenues = b)
    list.addView(padded(venues, 20, 0))
    list.addView(padded(caption("オンにすると、チェックインや場所の候補を探すときに位置が OpenStreetMap のサーバに送られます。"), 20, 0))

    list.addView(section("同期とバックアップ"))
    val syncState =
      if SyncConfig.ready then s"同期中・${SyncConfig.serverUrl}"
      else if SyncConfig.paired then "鍵の設定が必要です"
      else "未設定（この端末だけに保存）"
    list.addView(item("サーバ同期（エンドツーエンド暗号化）", syncState)(act.push(SyncSettingsScreen(act))))

    list.addView(section("データ"))
    list.addView(item("エクスポート・インポート", "Timeline.json（Google 互換）、GPX")(act.push(DataScreen(act))))

    list.addView(section("開発者向け"))
    list.addView(item("端末診断", "GNSS とセンサーの能力、自己テスト、レポートのコピー")(act.push(DiagnosticsScreen(act))))
    list.addView(padded(caption(s"ashidori ${BuildInfo.versionName}"), 20, 16))
