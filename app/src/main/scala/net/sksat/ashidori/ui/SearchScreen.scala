package net.sksat.ashidori.ui

import android.text.{Editable, TextWatcher}
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.sksat.ashidori.*
import ashidori.core.*
import Ui.*

/** Answers "when did I go to …?": places (by name, area or check-in venue)
  * with their visit history, check-ins, and dates typed as M/D.
  */
final class SearchScreen(act: MainActivity) extends Screen:
  private given Ctx = act
  override val title = "記録を探す"
  private val Days = 180
  private val dayFmt = DateTimeFormatter.ofPattern("yyyy/M/d (E)", Locale.JAPANESE)

  private val input = EditText(act)
  input.setHint("場所・地域・チェックイン先・日付")
  input.setSingleLine(true)
  input.setMinHeight(dp(56))
  input.setTextSize(17f)
  private val status = caption("")
  private val results = vbox()

  val view: View = vbox(padded(vbox(input, status), 16, 8), scroll(padded(results, 0, 0)))

  /** Index built once per visit to this screen: visits per place, check-ins. */
  private var visitsByPlace: Map[Long, Vector[Visit]] = Map.empty
  private var places: Vector[Place] = Vector.empty
  private var checkIns: Vector[CheckIn] = Vector.empty

  input.addTextChangedListener(new TextWatcher:
    def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
    def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = render()
    def afterTextChanged(s: Editable): Unit = ()
  )

  override def onShow(): Unit =
    input.requestFocus()
    act.getSystemService(classOf[InputMethodManager]).showSoftInput(input, 0)
    status.setText(s"直近${Days}日の記録を読み込み中…")
    AshidoriApp.io.execute { () =>
      val today = LocalDate.now(AshidoriApp.zone)
      val visits = (0 until Days).flatMap(i => TimelineRepo.day(today.minusDays(i)).segments.collect { case v: Visit => v })
      val ps = AshidoriApp.db.places()
      val cs = AshidoriApp.db.checkIns(0, Long.MaxValue)
      act.runOnUiThread { () =>
        visitsByPlace = visits.flatMap(v => v.placeId.map(_ -> v)).groupMap(_._1)(_._2).view.mapValues(_.sortBy(-_.start).toVector).toMap
        places = ps
        checkIns = cs
        status.setText("")
        render()
      }
    }

  override def onHide(): Unit =
    act.getSystemService(classOf[InputMethodManager]).hideSoftInputFromWindow(input.getWindowToken, 0)

  private def section(name: String): View = padded(text(name, 14f, bold = true, colorAttr = attr.primary), 16, 10)

  private def row(title: String, sub: String)(onClick: => Unit): View =
    val v = vbox(text(title, 16f), caption(sub))
    v.setMinimumHeight(dp(56))
    v.setBackgroundResource(android.R.drawable.list_selector_background)
    v.setOnClickListener(_ => onClick)
    padded(v, 16, 6)

  private def openPlace(p: Place): Unit =
    PlaceSheet(act, p, None, () => render(), Some(act.openDay), visitsByPlace.getOrElse(p.id, Vector.empty)).show()

  private def fmtDay(t: Long) = Instant.ofEpochMilli(t).atZone(AshidoriApp.zone).format(dayFmt)

  private val DatePattern = """(?:(\d{4})[/-])?(\d{1,2})[/-](\d{1,2})""".r

  private def render(): Unit =
    val q = input.getText.toString.trim.toLowerCase
    results.removeAllViews()
    if q.isEmpty then
      results.addView(padded(vbox(
        text("自分の記録から探します", 15f, bold = true),
        caption("・場所の名前や地域（例: 渋谷）… その場所に行った日の一覧\n・チェックイン先（例: スターバックス）\n・日付（例: 10/5、2026/9/1）… その日を開く")
      ), 16, 12))
      return
    // A typed date jumps straight to that day.
    q match
      case DatePattern(y, m, d) =>
        val today = LocalDate.now(AshidoriApp.zone)
        scala.util.Try(LocalDate.of(Option(y).map(_.toInt).getOrElse(today.getYear), m.toInt, d.toInt)).toOption
          .filterNot(_.isAfter(today))
          .foreach(date => results.addView(row(s"${date.format(dayFmt)} のタイムライン", "この日を開く")(act.openDay(date))))
      case _ => ()

    def matches(p: Place) =
      q.isEmpty || p.displayName.toLowerCase.contains(q) || p.area.toLowerCase.contains(q) ||
        checkIns.exists(c => c.placeId.contains(p.id) && c.venueName.toLowerCase.contains(q))
    val ranked = places.filter(matches).map(p => p -> visitsByPlace.getOrElse(p.id, Vector.empty))
      .filter((p, vs) => vs.nonEmpty || q.nonEmpty && p.named)
      .sortBy((_, vs) => (-vs.size, -vs.headOption.map(_.start).getOrElse(0L)))
    results.addView(section(if q.isEmpty then "よく行く場所" else "場所"))
    if ranked.isEmpty then results.addView(padded(caption("見つかりません"), 16, 4))
    for (p, vs) <- ranked.take(if q.isEmpty then 10 else 30) do
      val last = vs.headOption.map(v => s"・最後 ${fmtDay(v.start)}").getOrElse("")
      val total = vs.map(_.durationMs).sum
      results.addView(row(p.displayName, s"${vs.size}回・${fmtDuration(total)}$last")(openPlace(p)))

    val cs = checkIns.filter(c => q.isEmpty || c.venueName.toLowerCase.contains(q) || c.comment.toLowerCase.contains(q))
    if cs.nonEmpty then
      results.addView(section(if q.isEmpty then "最近のチェックイン" else "チェックイン"))
      for c <- cs.take(if q.isEmpty then 5 else 30) do
        results.addView(row((if c.isPrivate then "🔒 " else "") + c.venueName, fmtDay(c.time) + (if c.comment.nonEmpty then s"「${c.comment}」" else ""))(
          act.openDay(Stats.localDate(c.time, AshidoriApp.zone))
        ))
