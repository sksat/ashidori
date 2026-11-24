package ashidori.core

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import PicsFormat.Ped

/** The ステータス row 「信号機」 (docs/design/pics.md 6), shown whenever the
  * reception is on, in the same two lines in every state: the first says
  * what is received or the state, the second the last reception. Receiving:
  * the intersection and its signals ([[PicsDisplay]]). Otherwise the scan
  * state and the distance to the nearest listed intersection. Without the
  * permission or with Bluetooth off: that, and the settings to open.
  * Times on the monotonic clock (elapsed ms) unless named `wall`.
  */
object PicsStatus:
  /** An intersection heard in this process: its name (else its ID), its
    * signals and when they came, when anything of it came last.
    */
  final case class Heard(label: String, peds: Seq[Ped], signalsAt: Long, lastAt: Long, lastWall: Long)
  /** A reception: where (its name, else its ID) and when (wall ms). */
  final case class Last(label: String, wall: Long)
  /** The distance from the latest fix to the nearest listed intersection, and when the fix came. */
  final case class Nearest(distanceM: Double, fixAt: Long)

  /** The colour of the first line's state. */
  enum Tone:
    /** scanning near an intersection, or the test */
    case Active
    case Normal
    /** stopped */
    case Muted
    /** the permission missing or Bluetooth off */
    case Error

  /** The settings a tap opens. */
  enum Opens:
    case AppPermission, BluetoothSettings

  final case class Input(
      now: Long,
      nowWall: Long,
      zone: ZoneId,
      /** BLUETOOTH_SCAN granted */
      permission: Boolean,
      /** None: no Bluetooth adapter */
      bluetooth: Option[Boolean],
      state: PicsScanPolicy.State,
      /** the intersection to show, of this process (`PicsLive.current` in the app) */
      current: Option[Heard],
      nearest: Option[Nearest],
      /** the last reception stored (before this process, or not yet heard here) */
      stored: Option[Last])

  /** `main`: the intersection or the state, in `tone`; `signals`: the
    * crosswalks and whether their colours are fresh; `detail`: after the
    * state (the distance); `sub`: the last reception; `opens`: what a tap
    * opens; `nextAt`: when it changes next by itself (Long.MaxValue: never).
    */
  final case class Row(main: String, tone: Tone, signals: Option[(Seq[Ped], Boolean)], detail: Option[String],
                       sub: String, opens: Option[Opens], nextAt: Long)

  val NoPermission = "付近のデバイスの権限なし"
  val BluetoothOff = "Bluetooth がオフ"
  val NoBluetooth = "Bluetooth なし"
  val NoneToday = "本日の受信なし"

  private val timeFmt = DateTimeFormatter.ofPattern("H:mm:ss")
  def time(wall: Long, zone: ZoneId): String = Instant.ofEpochMilli(wall).atZone(zone).format(timeFmt)

  /** 「320 m」 (10 m steps), 「1.2 km」, 「23 km」. */
  def distance(m: Double): String =
    if m < 995 then s"${math.round(m / 10) * 10} m"
    else if m < 9_950 then f"${m / 1000}%.1f km"
    else s"${math.round(m / 1000)} km"

  def row(in: Input): Row =
    val zone = in.zone
    val dayStart = Instant.ofEpochMilli(in.nowWall).atZone(zone).toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli
    val dayEnd = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
    val heardLast = in.current.map(h => Last(h.label, h.lastWall))
    val last = (heardLast.toSeq ++ in.stored).filter(l => l.wall >= dayStart && l.wall < dayEnd).maxByOption(_.wall)
    // The second line changes at midnight (「本日」).
    val midnight = if last.isDefined then in.now + math.max(1L, dayEnd - in.nowWall) else Long.MaxValue
    val sub = last.fold(NoneToday)(l => s"最終受信 ${time(l.wall, zone)}・${l.label}")
    val receiving = in.state == PicsScanPolicy.State.Receiving || in.state == PicsScanPolicy.State.Test
    in.current.filter(_ => receiving && in.permission && in.bluetooth.contains(true)) match
      case Some(h) =>
        val v = PicsDisplay.at(in.now, h.signalsAt)
        Row(h.label, Tone.Normal, Option.when(v.signals && h.peds.nonEmpty)((h.peds, v.fresh)), None,
          s"最終受信 ${time(h.lastWall, zone)}", None, v.nextAt)
      case None =>
        if !in.permission then Row(NoPermission, Tone.Error, None, None, sub, Some(Opens.AppPermission), midnight)
        else if in.bluetooth.contains(false) then Row(BluetoothOff, Tone.Error, None, None, sub, Some(Opens.BluetoothSettings), midnight)
        else if in.bluetooth.isEmpty then Row(NoBluetooth, Tone.Muted, None, None, sub, None, midnight)
        else
          val near = in.nearest.filter(n => in.now - n.fixAt <= PicsScanPolicy.FixMaxAgeMs)
          val tone = in.state match
            case PicsScanPolicy.State.Stopped                       => Tone.Muted
            case PicsScanPolicy.State.Opportunistic                 => Tone.Normal
            case _                                                  => Tone.Active
          // The distance goes with its fix (5 min).
          val expires = near.fold(Long.MaxValue)(_.fixAt + PicsScanPolicy.FixMaxAgeMs + 1)
          Row(in.state.label, tone, None, near.map(n => s"最寄り ${distance(n.distanceM)}"), sub, None,
            math.min(expires, midnight))

  /** The last qualifying reception of `rows` (the stored runs, newest
    * first or not) at or after `from` (wall): its intersection, named by a
    * kind 1 position of the same ID among the rows (`siteOf`), else
    * `label(iid)`.
    */
  def lastStored(rows: Seq[PicsAdv], from: Long, siteOf: (Double, Double) => Option[String],
                 label: String => String): Option[Last] =
    def qualifying(a: PicsAdv) =
      a.iid.isDefined && PicsFormat.unhex(a.data).toOption.exists(b => PicsFormat.packet(b).qualifying)
    rows.filter(a => a.end >= from && qualifying(a)).maxByOption(_.end).map { a =>
      val iid = a.iid.get
      val named = rows.iterator.filter(r => r.iid.contains(iid) && r.kind.contains(1)).flatMap { r =>
        PicsFormat.unhex(r.data).toOption.map(PicsFormat.packet(_).body).collect {
          case PicsFormat.Body.Position(lat, lon) => siteOf(lat, lon)
        }.flatten
      }.nextOption()
      Last(named.getOrElse(label(iid)), a.end)
    }
