package ashidori.core

/** One state change of the logger for the `energy_state` table: from `at`
  * the logger is in `state` (None: stopped). `seq` orders the changes of
  * one recorder (one process, identified by a token) and makes applying
  * them again a no-op.
  */
final case class EnergyTransition(seq: Long, at: Long, state: Option[EnergyState])

/** A batch of the logger's small rows (everything but raw lines), as handed
  * to the write thread. It can be written to a file and read back
  * ([[encode]], [[decode]]): a batch that cannot be saved now (the queue is
  * full, or SQLite refused it) is kept in a file and imported later, like
  * raw lines.
  */
enum SmallRecord:
  case Fixes(fixes: Seq[Fix])
  case Motion(windows: Seq[MotionWindow])
  case Battery(reading: BatteryReading, plan: String)
  case GnssStatus(time: Long, visible: Int, used: Int, top4: Double, l5: Int, env: String, acc: Float)
  case RawSession(start: Long, end: Long, file: String, reason: String)
  case Pressure(samples: Seq[PressureSample])
  /** State changes not yet confirmed (in order), and the time the current
    * state was last confirmed (`current`; None when stopped).
    */
  case Energy(token: String, transitions: Seq[EnergyTransition], confirmedAt: Long, current: Option[EnergyState])
  /** MSL altitudes computed after their fixes were saved: (fix time, msl, accuracy). */
  case FixMsl(values: Seq[(Long, Double, Float)])

  def kind: WriteKind = this match
    case _: Fixes      => WriteKind.Fixes
    case _: Motion     => WriteKind.Motion
    case _: Battery    => WriteKind.Battery
    case _: GnssStatus => WriteKind.GnssStatus
    case _: RawSession => WriteKind.RawSessions
    case _: Pressure   => WriteKind.Pressure
    case _: Energy     => WriteKind.EnergyState
    case _: FixMsl     => WriteKind.FixMsl

  /** Rows, as the write queue counts them. */
  def rows: Int = this match
    case Fixes(fs)          => fs.size
    case Motion(ws)         => ws.size
    case Pressure(ps)       => ps.size
    case Energy(_, ts, _, _) => math.max(1, ts.size)
    case FixMsl(vs)         => vs.size
    case _                  => 1

object SmallRecord:
  import ujson.{Arr, Null, Num, Obj, Str, Value}

  private def f(v: Float): Value = if v.isNaN || v.isInfinite then Null else Num(v.toDouble)
  private def d(v: Double): Value = if v.isNaN || v.isInfinite then Null else Num(v)
  private def l(v: Long): Value = Str(v.toString)
  private def gf(v: Value): Float = if v.isNull then Float.NaN else v.num.toFloat
  private def gd(v: Value): Double = if v.isNull then Double.NaN else v.num
  private def gl(v: Value): Long = v.str.toLong
  private def oi(v: Option[Int]): Value = v.fold(Null)(x => Num(x.toDouble))
  private def gi(v: Value): Option[Int] = if v.isNull then None else Some(v.num.toInt)

  private def state(s: EnergyState): Value = Obj("gnss" -> s.gnss, "interval" -> l(s.gnssIntervalMs), "provider" -> s.provider,
    "detail" -> s.detail, "still" -> s.still, "screen" -> s.screenOn, "charging" -> s.charging, "mode" -> s.mode,
    "eff" -> s.effMode, "motion" -> s.motion, "env" -> s.env)
  private def stateOf(v: Value): EnergyState = EnergyState(v("gnss").bool, gl(v("interval")), v("provider").str, v("detail").bool,
    v("still").bool, v("screen").bool, v("charging").bool, v("mode").str, v("eff").str, v("motion").str, v("env").str)

  /** One line of JSON (no newline in it). Longs are strings: JSON numbers
    * are doubles, which do not hold every ms time exactly.
    */
  def encode(r: SmallRecord): String =
    val o: Value = r match
      case Fixes(fs) => Obj("t" -> "fixes", "v" -> Arr.from(fs.map(x => Arr(l(x.time), d(x.lat), d(x.lon), f(x.accuracy), d(x.altitude),
        f(x.speed), f(x.bearing), x.provider, Num(x.satellites), f(x.altAccuracy), d(x.msl), f(x.mslAccuracy)))))
      case Motion(ws) => Obj("t" -> "motion", "v" -> Arr.from(ws.map(m => Arr(l(m.start), l(m.end), f(m.accelStd), f(m.accelMean), Num(m.steps)))))
      case Battery(b, plan) => Obj("t" -> "battery", "plan" -> plan, "v" -> Arr(l(b.time), Num(b.pct), b.charging, b.screenOn, b.mode,
        b.chargeUah.fold(Null)(l), oi(b.currentAvgUa), oi(b.currentNowUa), oi(b.voltageMv), f(b.tempC), b.plug.fold(Null)(Str(_))))
      case GnssStatus(t, vis, used, top4, l5, env, acc) =>
        Obj("t" -> "gnss_status", "v" -> Arr(l(t), Num(vis), Num(used), d(top4), Num(l5), env, f(acc)))
      case RawSession(s, e, file, reason) => Obj("t" -> "raw_session", "v" -> Arr(l(s), l(e), file, reason))
      case Pressure(ps) => Obj("t" -> "pressure", "v" -> Arr.from(ps.map(p => Arr(l(p.time), f(p.hPa)))))
      case Energy(token, ts, at, cur) => Obj("t" -> "energy", "token" -> token, "at" -> l(at), "current" -> cur.fold(Null)(state),
        "v" -> Arr.from(ts.map(x => Arr(l(x.seq), l(x.at), x.state.fold(Null)(state)))))
      case FixMsl(vs) => Obj("t" -> "fix_msl", "v" -> Arr.from(vs.map((t, m, a) => Arr(l(t), d(m), f(a)))))
    ujson.write(o)

  /** The record of a line [[encode]] wrote; throws on anything else. */
  def decode(s: String): SmallRecord =
    val o = ujson.read(s)
    val v = o("v")
    o("t").str match
      case "fixes" => Fixes(v.arr.toSeq.map { a =>
        Fix(gl(a(0)), gd(a(1)), gd(a(2)), gf(a(3)), gd(a(4)), gf(a(5)), gf(a(6)), a(7).str, a(8).num.toInt, gf(a(9)), gd(a(10)), gf(a(11)))
      })
      case "motion" => Motion(v.arr.toSeq.map(a => MotionWindow(gl(a(0)), gl(a(1)), gf(a(2)), gf(a(3)), a(4).num.toInt)))
      case "battery" => Battery(BatteryReading(gl(v(0)), v(1).num.toInt, v(2).bool, v(3).bool, v(4).str,
        if v(5).isNull then None else Some(gl(v(5))), gi(v(6)), gi(v(7)), gi(v(8)), gf(v(9)), if v(10).isNull then None else Some(v(10).str)),
        o("plan").str)
      case "gnss_status" => GnssStatus(gl(v(0)), v(1).num.toInt, v(2).num.toInt, gd(v(3)), v(4).num.toInt, v(5).str, gf(v(6)))
      case "raw_session" => RawSession(gl(v(0)), gl(v(1)), v(2).str, v(3).str)
      case "pressure" => Pressure(v.arr.toSeq.map(a => PressureSample(gl(a(0)), gf(a(1)))))
      case "energy" => Energy(o("token").str, v.arr.toSeq.map(a => EnergyTransition(gl(a(0)), gl(a(1)),
        if a(2).isNull then None else Some(stateOf(a(2))))), gl(o("at")), if o("current").isNull then None else Some(stateOf(o("current"))))
      case "fix_msl" => FixMsl(v.arr.toSeq.map(a => (gl(a(0)), gd(a(1)), gf(a(2)))))
      case other => throw IllegalArgumentException(s"record type $other")
