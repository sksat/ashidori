package net.sksat.ashidori.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.{Gravity, View}
import android.widget.{LinearLayout, RadioButton, RadioGroup}
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** First-run flow: what the app does and how data is handled, a mode choice,
  * then one permission per step with its reason. Steps whose permission is
  * already granted are skipped. Denials are allowed; the app explains what
  * stops working and carries on.
  */
final class OnboardingScreen(act: MainActivity, permissionsOnly: Boolean, onFinish: () => Unit) extends Screen:
  private given Ctx = act
  override val title = "はじめに"

  private enum Step:
    case Welcome, Mode, Location, Notify, Activity, Background, Battery

  private val steps: Vector[Step] =
    if permissionsOnly then Vector(Step.Location, Step.Notify, Step.Activity, Step.Background, Step.Battery)
    else Step.values.toVector
  private var index = 0
  private val body = vbox()
  private val progress = caption("")

  val view: View =
    val v = vbox(padded(progress, 24, 16), body)
    body.setLayoutParams(LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    scroll(v)

  override def onShow(): Unit = render()

  private def pm = act.getSystemService(classOf[PowerManager])

  private def done(s: Step): Boolean = s match
    case Step.Location   => act.granted(Manifest.permission.ACCESS_FINE_LOCATION)
    case Step.Notify     => act.granted(Manifest.permission.POST_NOTIFICATIONS)
    case Step.Activity   => act.granted(Manifest.permission.ACTIVITY_RECOGNITION)
    case Step.Background => act.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    case Step.Battery    => pm.isIgnoringBatteryOptimizations(act.getPackageName)
    case _               => false

  private def next(): Unit =
    index += 1
    // Skip steps that are already satisfied (e.g. re-entering for one permission).
    while index < steps.size && done(steps(index)) do index += 1
    if index >= steps.size then finish() else render()

  private def finish(): Unit =
    if !permissionsOnly && act.granted(Manifest.permission.ACCESS_FINE_LOCATION) then
      Settings.loggingEnabled = true
      LoggerService.send(act, LoggerService.ActionStart)
    onFinish()

  private def page(heading: String, lines: Seq[String], primary: (String, () => Unit), secondary: Option[(String, () => Unit)] = None, extra: Seq[View] = Nil): Unit =
    body.removeAllViews()
    val h = text(heading, 26f, bold = true)
    val texts = lines.map { l =>
      val t = text(l, 16f, colorAttr = attr.onSurfaceVariant)
      t.setLineSpacing(0f, 1.2f)
      padded(t, 0, 6)
    }
    val p = button(primary._1)(primary._2())
    p.setMinHeight(dp(52))
    val buttons = vbox((p +: secondary.map((l, f) => outlined(l)(f())).toSeq)*)
    val col = vbox(((h +: spacer(12) +: texts) ++ extra ++ Seq(spacer(24), buttons))*)
    body.addView(padded(col, 24, 8))
    val visible = steps.indices.count(i => i <= index)
    progress.setText(s"${visible} / ${steps.size}")

  private def render(): Unit =
    steps(index) match
      case Step.Welcome =>
        page(
          "ashidori へようこそ",
          Seq(
            "訪れた場所と移動を自動で記録します。",
            "記録はこの端末に保存します。サーバ同期を使う場合も、サーバの管理者は記録を読めません。",
            "電池の消費を抑えて記録します。"
          ),
          "はじめる" -> (() => next())
        )
      case Step.Mode =>
        val group = RadioGroup(act)
        val opts = Seq(LoggingMode.Eco, LoggingMode.Balanced, LoggingMode.Precise).map { m =>
          val rb = RadioButton(act)
          rb.setId(View.generateViewId())
          rb.setText(s"${m.label}　${ModeInfo.battery(m)}\n${ModeInfo.summary(m)}")
          rb.setMinHeight(dp(64))
          rb.setChecked(m == Settings.mode)
          group.addView(rb)
          m -> rb
        }
        page(
          "記録モードを選ぶ",
          Seq("「ステータス」画面でいつでも変更できます。"),
          "次へ" -> (() => {
            opts.find(_._2.isChecked).foreach((m, _) => Settings.mode = m)
            next()
          }),
          extra = Seq(group)
        )
      case Step.Location =>
        page(
          "位置情報",
          Seq("訪れた場所と移動経路の記録に使用します。"),
          "許可する" -> (() => act.requestPermissions(Seq(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))(() => afterRequest(Step.Location))),
          Some("あとで" -> (() => next()))
        )
      case Step.Notify =>
        page(
          "通知",
          Seq("記録中は通知を常に表示します。"),
          "許可する" -> (() => act.requestPermissions(Seq(Manifest.permission.POST_NOTIFICATIONS))(() => afterRequest(Step.Notify))),
          Some("あとで" -> (() => next()))
        )
      case Step.Activity =>
        page(
          "身体活動",
          Seq("歩数の計測と、徒歩・自転車・乗り物の判別に使用します。"),
          "許可する" -> (() => act.requestPermissions(Seq(Manifest.permission.ACTIVITY_RECOGNITION))(() => afterRequest(Step.Activity))),
          Some("あとで" -> (() => next()))
        )
      case Step.Background =>
        page(
          "画面を閉じていても記録する",
          Seq("アプリを閉じている間の記録には、位置情報の「常に許可」が必要です。"),
          "設定を開く" -> (() => act.requestPermissions(Seq(Manifest.permission.ACCESS_BACKGROUND_LOCATION))(() => afterRequest(Step.Background))),
          Some("あとで" -> (() => next()))
        )
      case Step.Battery =>
        page(
          "電池の最適化",
          Seq("OS による記録の停止を防ぐため、電池の最適化の対象外にすることを推奨します。") ++
            OnboardingScreen.vendorHint.toSeq,
          "対象外にする" -> (() => {
            act.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse(s"package:${act.getPackageName}")))
            next()
          }),
          Some("このままにする" -> (() => next()))
        )

  private def afterRequest(s: Step): Unit =
    if done(s) then next()
    else
      val note = s match
        case Step.Location   => "位置情報がないと記録できません。"
        case Step.Background => "アプリを開いている間だけ記録します。あとで設定から変更できます。"
        case Step.Activity   => "歩数と移動手段の判定の精度が下がります。"
        case _               => "あとで設定から変更できます。"
      page("許可されませんでした", Seq(note), "次へ" -> (() => next()), Some("再試行" -> (() => render())))

object OnboardingScreen:
  /** Extra steps for vendors known to kill background apps aggressively. */
  def vendorHint: Option[String] =
    android.os.Build.MANUFACTURER.toLowerCase match
      case "oppo" | "realme" | "oneplus" =>
        Some("ColorOS では加えて「設定 → アプリ → ashidori → 電池」で「バックグラウンドでの実行を許可」と「自動起動」をオンにしてください。")
      case "xiaomi" =>
        Some("MIUI / HyperOS では加えて「自動起動」をオンにし、バッテリーセーバーを「制限なし」にしてください。")
      case "samsung" =>
        Some("Galaxy では「デバイスケア → 電池 → バックグラウンドでの使用制限」で ashidori を「スリープしないアプリ」に追加してください。")
      case _ => None
