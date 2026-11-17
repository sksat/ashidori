package ashidori.core

import java.time.{Instant, ZoneId}

/** One battery reading, taken every 5 minutes while the logger runs and at
  * every change of [[EnergyState]].
  *
  * Currents are discharge-positive µA: Android documents CURRENT_NOW and
  * CURRENT_AVERAGE as positive into the battery, and Pixels report negative
  * values on battery, so the logger stores the negated value. A phone on
  * battery reads positive, one charging reads negative. Some devices deviate
  * (opposite sign, or mA); the analysis does not use the currents.
  * None where the device does not provide a value.
  */
final case class BatteryReading(
    time: Long,
    pct: Int,
    charging: Boolean,
    screenOn: Boolean,
    mode: String,
    /** BATTERY_PROPERTY_CHARGE_COUNTER: charge left, µAh */
    chargeUah: Option[Long] = None,
    currentAvgUa: Option[Int] = None,
    currentNowUa: Option[Int] = None,
    voltageMv: Option[Int] = None,
    /** °C; NaN if unknown */
    tempC: Float = Float.NaN,
    /** none, ac, usb, wireless or dock */
    plug: Option[String] = None
)

/** What the logger is doing, as far as it costs energy. A new
  * [[EnergySpan]] starts whenever any field changes.
  */
final case class EnergyState(
    /** the GNSS engine is requested */
    gnss: Boolean,
    /** requested location interval (any provider) */
    gnssIntervalMs: Long,
    /** registered location provider: gps, fused, network or passive */
    provider: String,
    /** a detail log (raw GNSS, IMU) is running */
    detail: Boolean,
    /** still mode: accelerometer off, waiting for significant motion */
    still: Boolean,
    screenOn: Boolean,
    /** plugged in or charging */
    charging: Boolean,
    /** the user's mode and the one in effect ([[LoggingMode.key]]) */
    mode: String,
    effMode: String,
    /** [[MotionState]] and [[Environment]] names */
    motion: String,
    env: String
)

final case class EnergySpan(start: Long, end: Long, state: EnergyState):
  def ms: Long = end - start

object EnergySpan:
  /** The span cut at local midnights, so each piece belongs to one day's archive. */
  def splitDays(s: EnergySpan, zone: ZoneId): Seq[EnergySpan] =
    val out = Vector.newBuilder[EnergySpan]
    var from = s.start
    while from < s.end do
      val next = Instant.ofEpochMilli(from).atZone(zone).toLocalDate.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
      val to = math.min(next, s.end)
      out += s.copy(start = from, end = to)
      from = to
    val r = out.result()
    if r.isEmpty then Seq(s) else r

/** Attributes battery drain to what the logger does.
  *
  * Consecutive battery readings outside charging make windows; each window's
  * drop in percent is modelled as a sum over its hours of exposure:
  * {{{
  *   drop = base·h + screen·h_screen + active·h_active + detail·h_detail + Σ gnss_c·h_gnss,c
  * }}}
  * `base` is the whole phone's drain with the screen off in still mode (the
  * logger's minimum); the other terms are extra on top of it. The
  * coefficients are fitted by non-negative least squares with Huber weights
  * (iteratively reweighted), so a window with other heavy use (a call,
  * tethering) weighs less. The drop comes from the charge counter when the
  * device has a plausible one, otherwise from the integer percentage over
  * windows of at least an hour.
  */
object EnergyModel:

  /** GNSS request interval classes; the profiles' intervals fall in different ones. */
  enum GnssClass(val key: String, val label: String, val short: String, val upToMs: Long):
    case S1 extends GnssClass("1s", "1秒", "1秒", 1_500L)
    case S5 extends GnssClass("2-5s", "2〜5秒", "5秒", 6_000L)
    case S15 extends GnssClass("10-15s", "10〜15秒", "15秒", 20_000L)
    case S30 extends GnssClass("30s+", "30秒以上", "30秒", Long.MaxValue)

  object GnssClass:
    def of(ms: Long): GnssClass = values.find(ms <= _.upToMs).get

  // Term indices.
  private val Base = 0
  private val Screen = 1
  private val Active = 2
  private val Detail = 3
  private val Gnss0 = 4
  private val P = Gnss0 + GnssClass.values.length
  private def gnssIx(c: GnssClass) = Gnss0 + c.ordinal

  /** Exposure needed before a coefficient is reported, hours. */
  private def minHours(term: Int): Double = term match
    case Base   => 6.0
    case Screen => 1.0
    case Active => 1.0
    case Detail => 0.25
    case _      => 0.5

  enum Source:
    /** charge counter (µAh) converted with the estimated full capacity */
    case Counter
    /** the integer percentage */
    case Percent

  /** One fitted term: % per hour of exposure, its standard error, the hours
    * of exposure behind it and the number of windows that had any.
    */
  final case class Coef(perHour: Double, se: Double, hours: Double, windows: Int, needHours: Double):
    /** Enough exposure, and precise enough to state (± no more than half the value, or 0.5 %/h). */
    def ok: Boolean = !perHour.isNaN && hours >= needHours && windows >= 3 && !(se > math.max(0.5 * perHour, 0.5))

  final case class Fit(
      baseline: Coef,
      screen: Coef,
      active: Coef,
      detail: Coef,
      gnss: Map[GnssClass, Coef],
      windows: Int,
      hours: Double,
      source: Source,
      /** estimated full charge capacity, mAh; NaN without a counter */
      capacityMah: Double,
      /** robust residual scale of a window, % */
      residualPct: Double
  ):
    private[EnergyModel] def coef(term: Int): Coef = term match
      case Base   => baseline
      case Screen => screen
      case Active => active
      case Detail => detail
      case g      => gnss(GnssClass.fromOrdinal(g - Gnss0))

  /** Why there is no fit, and how much usable data there was. */
  final case class NotEnough(reason: String, hours: Double)

  /** How the logger spent its time: hours per day of each term (charging
    * excluded), the share of the day in each motion/place, and the typical
    * detail log length.
    */
  final case class Usage(
      /** span hours / 24 */
      days: Double,
      perDay: Map[String, Double],
      /** (motion, env) -> hours per day on battery */
      profile: Map[(MotionState, Environment), Double],
      /** median detail log length, minutes; NaN if none */
      detailMinutes: Double
  )

  final case class Report(
      fit: Either[NotEnough, Fit],
      usage: Usage,
      /** % of battery per day spent on recording beyond the minimum (GNSS, detail logs, motion detection) */
      recordingPerDay: Either[String, Double],
      /** the same for each mode, from the coefficients and the user's typical day */
      modePerDay: Map[LoggingMode, Either[String, Double]],
      latest: Option[BatteryReading]
  )

  private val H = 3_600_000.0

  def analyse(samples: Seq[BatteryReading], spans: Seq[EnergySpan]): Report =
    val f = fit(samples, spans)
    val u = usage(spans)
    Report(f, u, f.left.map(_.reason).flatMap(recording(_, u)), LoggingMode.values.map(m => m -> f.left.map(_.reason).flatMap(perMode(_, u, m))).toMap,
      samples.maxByOption(_.time))

  // --- capacity -------------------------------------------------------------

  /** Full capacity in µAh implied by the counter, if it is plausible and
    * follows the percentage (the emulator reports a constant 10 mAh).
    */
  def capacityUah(samples: Seq[BatteryReading]): Option[Double] =
    val usable = samples.filter(s => s.chargeUah.exists(_ > 0) && s.pct >= 15 && s.pct <= 99)
    if usable.size < 3 then None
    else
      val ratios = usable.map(s => s.chargeUah.get * 100.0 / s.pct).sorted
      val cap = ratios(ratios.size / 2)
      val pctRange = usable.map(_.pct).max - usable.map(_.pct).min
      val cRange = (usable.map(_.chargeUah.get).max - usable.map(_.chargeUah.get).min).toDouble
      val plausible = cap >= 1e6 && cap <= 2e7
      val tracks = pctRange < 5 || cRange >= 0.5 * pctRange / 100.0 * cap
      Option.when(plausible && tracks)(cap)

  // --- windows --------------------------------------------------------------

  private final case class Window(start: Long, end: Long, drop: Double, x: Array[Double], quiet: Double, counter: Boolean):
    def hours: Double = (end - start) / H
    /** Prior noise of the drop, %: the integer percentage is off by up to
      * one at each end; the counter is fine-grained, but other apps vary.
      */
    def sd: Double = if counter then 0.05 + 0.03 * hours else 0.5

  /** Exposure of [a, b) from the spans: (features in hours, covered ms,
    * charging ms, quiet hours, index of the first span that can overlap
    * [a, ...)). Spans are sorted and do not overlap; `from` is where to
    * start looking.
    */
  private def exposure(spans: IndexedSeq[EnergySpan], from: Int, a: Long, b: Long): (Array[Double], Long, Long, Double, Int) =
    val x = new Array[Double](P)
    var covered = 0L
    var charging = 0L
    var quiet = 0.0
    var i = from
    while i < spans.size && spans(i).end <= a do i += 1
    val first = i
    while i < spans.size && spans(i).start < b do
      val s = spans(i)
      val ms = math.min(b, s.end) - math.max(a, s.start)
      if ms > 0 then
        val h = ms / H
        val st = s.state
        covered += ms
        if st.charging then charging += ms
        if st.screenOn then x(Screen) += h
        if !st.still then x(Active) += h
        if st.detail then x(Detail) += h
        if st.gnss then x(gnssIx(GnssClass.of(st.gnssIntervalMs))) += h
        if !st.screenOn && st.still && !st.gnss && !st.detail then quiet += h
      i += 1
    (x, covered, charging, quiet, first)

  /** Windows of consecutive usable readings: off the charger, covered by
    * spans, at least 10 minutes (charge counter) or an hour (percentage).
    * A window uses the counter when both its ends have one.
    */
  private def windows(samples: Seq[BatteryReading], spans: IndexedSeq[EnergySpan], cap: Option[Double]): Vector[Window] =
    val s = samples.sortBy(_.time).distinctBy(_.time).toIndexedSeq
    final case class Pair(a: BatteryReading, b: BatteryReading, x: Array[Double], quiet: Double)
    val chains = Vector.newBuilder[Vector[Pair]]
    var cur = Vector.newBuilder[Pair]
    var curN = 0
    def cut(): Unit =
      if curN > 0 then chains += cur.result()
      cur = Vector.newBuilder[Pair]
      curN = 0
    var ptr = 0
    for i <- 0 until s.size - 1 do
      val (a, b) = (s(i), s(i + 1))
      val len = b.time - a.time
      val (x, covered, charging, quiet, first) = exposure(spans, ptr, a.time, b.time)
      ptr = first
      // A rise without a charger in between means a missed charge.
      val rise = b.pct > a.pct + 1 || (for ca <- a.chargeUah; cb <- b.chargeUah; c <- cap yield (cb - ca) > 0.005 * c).getOrElse(false)
      val ok = !a.charging && !b.charging && charging == 0 && len > 0 && len <= 12 * H && covered >= 0.9 * len && !rise
      if ok then
        x(Base) = len / H
        cur += Pair(a, b, x, quiet)
        curN += 1
      else cut()
    cut()
    val minLen = if cap.isDefined then 10 * 60_000L else 60 * 60_000L
    def counterOf(r: BatteryReading) = r.chargeUah.filter(_ > 0 && cap.isDefined)
    def make(g: Vector[Pair]): Window =
      val x = new Array[Double](P)
      g.foreach(p => for k <- 0 until P do x(k) += p.x(k))
      val (a, b) = (g.head.a, g.last.b)
      val c = for ca <- counterOf(a); cb <- counterOf(b) yield (ca - cb) * 100.0 / cap.get
      Window(a.time, b.time, c.getOrElse((a.pct - b.pct).toDouble), x, g.map(_.quiet).sum, c.isDefined)
    val out = Vector.newBuilder[Window]
    for chain <- chains.result() do
      var group = Vector.empty[Pair]
      var done = Vector.empty[Vector[Pair]]
      for p <- chain do
        group :+= p
        if group.last.b.time - group.head.a.time >= minLen then
          done :+= group
          group = Vector.empty
      // A short tail joins the window before it.
      if group.nonEmpty then
        if group.last.b.time - group.head.a.time >= minLen / 2 || done.isEmpty then done :+= group
        else done = done.init :+ (done.last ++ group)
      for g <- done do
        val w = make(g)
        // Pixels show 100% for a while after unplugging.
        if (w.counter || g.head.a.pct < 100) && w.end - w.start >= minLen / 2 then out += w
    out.result()

  // --- fit ------------------------------------------------------------------

  def fit(samples: Seq[BatteryReading], spans0: Seq[EnergySpan]): Either[NotEnough, Fit] =
    val spans = spans0.filter(_.ms > 0).sortBy(_.start).toIndexedSeq
    val cap = capacityUah(samples)
    val ws = windows(samples, spans, cap)
    val hours = ws.map(_.hours).sum
    if ws.size < 6 || hours < 12 then
      Left(NotEnough(if spans.isEmpty then "データ不足" else f"データ不足・${hours}%.0f時間分", hours))
    else
      val n = ws.size
      val x = ws.map(_.x)
      val y = ws.map(_.drop).toArray
      val sd = ws.map(_.sd).toArray
      val present = (0 until P).map(k => x.exists(_(k) > 0))
      // Huber weights on residuals in units of each window's prior noise,
      // scaled by their spread (never below the prior).
      var w = sd.map(v => 1 / (v * v))
      var beta = new Array[Double](P)
      var scale = 1.0
      for _ <- 0 until 15 do
        beta = nnls(x, y, w, present, beta)
        val z = (0 until n).map(i => (y(i) - dot(x(i), beta)) / sd(i))
        scale = math.max(1.0, 1.4826 * median(z.map(math.abs)))
        val k = 1.345 * scale
        w = Array.tabulate(n)(i => (if math.abs(z(i)) <= k then 1.0 else k / math.abs(z(i))) / (sd(i) * sd(i)))
      val r = (0 until n).map(i => y(i) - dot(x(i), beta))
      val free = (0 until P).filter(present)
      val q = Array.tabulate(free.size, free.size)((a, b) => (0 until n).map(i => w(i) * x(i)(free(a)) * x(i)(free(b))).sum)
      val sigma2 = (0 until n).map(i => w(i) * r(i) * r(i)).sum / math.max(1, n - free.size)
      val inv = invert(q)
      val se = Array.fill(P)(Double.NaN)
      for (k, j) <- free.zipWithIndex do se(k) = math.sqrt(math.max(0, sigma2 * inv(j)(j)))
      def coef(k: Int) =
        val h = if k == Base then ws.map(_.quiet).sum else x.map(_(k)).sum
        Coef(if present(k) then beta(k) else Double.NaN, se(k), h, x.count(_(k) > 0), minHours(k))
      val counterHours = ws.filter(_.counter).map(_.hours).sum
      Right(Fit(coef(Base), coef(Screen), coef(Active), coef(Detail), GnssClass.values.map(c => c -> coef(gnssIx(c))).toMap,
        n, hours, if counterHours >= hours / 2 then Source.Counter else Source.Percent, cap.fold(Double.NaN)(_ / 1000),
        median(r.map(math.abs)) * 1.4826))

  private def dot(a: Array[Double], b: Array[Double]): Double =
    var s = 0.0
    for i <- a.indices do s += a(i) * b(i)
    s

  private def median(v: Seq[Double]): Double =
    if v.isEmpty then 0.0
    else
      val s = v.sorted
      if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  /** Weighted non-negative least squares by coordinate descent on the
    * normal equations (small, well-scaled problems only).
    */
  private def nnls(x: Seq[Array[Double]], y: Array[Double], w: Array[Double], present: Seq[Boolean], init: Array[Double]): Array[Double] =
    val q = Array.ofDim[Double](P, P)
    val c = new Array[Double](P)
    for i <- x.indices do
      val xi = x(i)
      for a <- 0 until P do
        c(a) += w(i) * xi(a) * y(i)
        for b <- 0 until P do q(a)(b) += w(i) * xi(a) * xi(b)
    val beta = init.clone()
    var it = 0
    var change = 1.0
    while it < 5000 && change > 1e-12 do
      change = 0.0
      for k <- 0 until P if present(k) && q(k)(k) > 0 do
        var g = c(k)
        for j <- 0 until P do g -= q(k)(j) * beta(j)
        val nb = math.max(0.0, beta(k) + g / q(k)(k))
        change = math.max(change, math.abs(nb - beta(k)))
        beta(k) = nb
      it += 1
    for k <- 0 until P if !present(k) do beta(k) = 0.0
    beta

  /** Inverse of a small symmetric positive semi-definite matrix (a tiny ridge keeps it regular). */
  private def invert(m: Array[Array[Double]]): Array[Array[Double]] =
    val n = m.length
    val tr = (0 until n).map(i => m(i)(i)).sum
    val a = Array.tabulate(n, 2 * n)((i, j) => if j < n then m(i)(j) + (if i == j then 1e-9 * (tr + 1) else 0) else if j - n == i then 1.0 else 0.0)
    for col <- 0 until n do
      val piv = (col until n).maxBy(r => math.abs(a(r)(col)))
      val t = a(col); a(col) = a(piv); a(piv) = t
      val d = a(col)(col)
      if d != 0 then
        for j <- 0 until 2 * n do a(col)(j) /= d
        for r <- 0 until n if r != col do
          val f = a(r)(col)
          if f != 0 then for j <- 0 until 2 * n do a(r)(j) -= f * a(col)(j)
    Array.tabulate(n, n)((i, j) => a(i)(j + n))

  // --- usage and per-day estimates ------------------------------------------

  def usage(spans0: Seq[EnergySpan]): Usage =
    val spans = spans0.filter(_.ms > 0)
    val total = spans.map(_.ms).sum / H
    val onBattery = spans.filterNot(_.state.charging)
    def perDay(h: Double) = if total <= 0 then 0.0 else h / total * 24
    val per = Map.newBuilder[String, Double]
    per += "screen" -> perDay(onBattery.filter(_.state.screenOn).map(_.ms).sum / H)
    per += "active" -> perDay(onBattery.filterNot(_.state.still).map(_.ms).sum / H)
    per += "detail" -> perDay(onBattery.filter(_.state.detail).map(_.ms).sum / H)
    for c <- GnssClass.values do
      per += s"gnss.${c.key}" -> perDay(onBattery.filter(s => s.state.gnss && GnssClass.of(s.state.gnssIntervalMs) == c).map(_.ms).sum / H)
    def motion(s: String) = MotionState.values.find(_.toString == s).getOrElse(MotionState.Unknown)
    def env(s: String) = Environment.values.find(_.toString == s).getOrElse(Environment.Unknown)
    val profile = onBattery.groupMapReduce(s => (motion(s.state.motion), env(s.state.env)))(_.ms / H)(_ + _).view.mapValues(perDay).toMap
    // Detail logs: contiguous detail spans joined.
    val sessions = spans.filter(_.state.detail).sortBy(_.start).foldLeft(Vector.empty[(Long, Long)]) { (acc, s) =>
      acc.lastOption match
        case Some((a, b)) if s.start - b <= 2_000 => acc.init :+ (a, math.max(b, s.end))
        case _                                    => acc :+ (s.start, s.end)
    }
    Usage(total / 24, per.result(), profile, if sessions.isEmpty then Double.NaN else median(sessions.map((a, b) => (b - a) / 60_000.0)))

  private def termKey(k: Int): String = k match
    case Screen => "screen"
    case Active => "active"
    case Detail => "detail"
    case Base   => "base"
    case g      => s"gnss.${GnssClass.fromOrdinal(g - Gnss0).key}"

  def termLabel(k: Int): String = k match
    case Active => "移動の検出"
    case Detail => "詳細ログ"
    case g if g >= Gnss0 => s"GNSS ${GnssClass.fromOrdinal(g - Gnss0).label}"
    case _ => ""

  /** Hours per day of each recording term → % per day; Left if a term in use lacks a coefficient. */
  private def cost(f: Fit, hoursPerDay: Int => Double): Either[String, Double] =
    val terms = (Active +: Detail +: GnssClass.values.toSeq.map(gnssIx)).filter(k => hoursPerDay(k) > 0.02)
    terms.find(k => !f.coef(k).ok) match
      case Some(k) => Left(s"${termLabel(k)}の実測が不足")
      case None    => Right(terms.map(k => f.coef(k).perHour * hoursPerDay(k)).sum)

  private def recording(f: Fit, u: Usage): Either[String, Double] =
    if u.days < 1 then Left("データ不足・1日分未満")
    else cost(f, k => u.perDay.getOrElse(termKey(k), 0.0))

  /** Exposure per hour (fractions of each term) of `mode` in one motion state
    * and place, from the policy itself: averaged over the phases of indoor
    * GNSS probes, plus the periodic detail logs.
    */
  def planExposure(mode: LoggingMode, motion: MotionState, env: Environment): Array[Double] =
    val prof = SamplingPolicy.profiles(mode)
    val x = new Array[Double](P)
    val t0 = 1_000L * 86_400_000L
    val steps = (SamplingPolicy.IndoorProbeStillEveryMs / 10_000L).toInt
    var gnssShare = 0.0
    for i <- 0 until steps do
      val now = t0 + i * 10_000L
      val p = SamplingPolicy.plan(PolicyInput(mode, PowerState(80, charging = false, powerSave = false), motion, Double.NaN, now,
        lastDetailStart = now - 5 * 60_000L, environment = env, motionSince = t0))
      if !(p.accelHz == 0 && motion == MotionState.Stationary) then x(Active) += 1.0 / steps
      if p.rawGnss then x(Detail) += 1.0 / steps
      if p.source == LocationSource.Gnss then
        x(gnssIx(GnssClass.of(p.intervalMs))) += 1.0 / steps
        gnssShare += 1.0 / steps
    // Periodic detail logs (Max logs all the time, counted above); none indoors.
    if mode != LoggingMode.Max && env != Environment.Indoor then
      val every = if motion == MotionState.Stationary then prof.detailEveryStillMs else prof.detailEveryMovingMs
      val share = math.min(1.0, prof.detailDurationMs.toDouble / every)
      x(Detail) += share
      // The detail log turns GNSS on at 1 s when it was off.
      x(gnssIx(GnssClass.S1)) += share * (1 - gnssShare)
    x

  private def perMode(f: Fit, u: Usage, mode: LoggingMode): Either[String, Double] =
    if u.days < 2 then Left("データ不足・2日分未満")
    else
      val perDay = new Array[Double](P)
      for ((m, e), h) <- u.profile do
        val x = planExposure(mode, m, e)
        for k <- 0 until P do perDay(k) += x(k) * h
      cost(f, k => perDay(k))

  /** Plain-text summary for the diagnostics report. */
  def describe(r: Report): String =
    val b = Seq.newBuilder[String]
    def c(name: String, x: Coef, unit: String = "%/h") =
      if x.perHour.isNaN then s"$name=n/a (no exposure)"
      else f"$name=${x.perHour}%.3f$unit ±${x.se}%.3f (${x.hours}%.1fh, ${x.windows} windows${if x.ok then "" else ", not enough"})"
    r.fit match
      case Left(ne) => b += f"fit=none (${ne.reason}; usable ${ne.hours}%.1fh)"
      case Right(f) =>
        b += f"source=${f.source} capacity=${f.capacityMah}%.0fmAh windows=${f.windows} hours=${f.hours}%.1f resid=${f.residualPct}%.2f%%"
        b += c("baseline", f.baseline)
        b += c("screen+", f.screen)
        b += c("active+", f.active)
        b += c("detail+", f.detail)
        for g <- GnssClass.values do b += c(s"gnss.${g.key}+", f.gnss(g))
    b += f"days=${r.usage.days}%.2f detailMin=${r.usage.detailMinutes}%.2f usage/day: " +
      r.usage.perDay.toSeq.sortBy(_._1).map((k, v) => f"$k=$v%.2fh").mkString(" ")
    b += "recording/day=" + r.recordingPerDay.fold(identity, d => f"$d%.2f%%")
    b += LoggingMode.values.map(m => s"mode.${m.key}=" + r.modePerDay(m).fold(identity, d => f"$d%.2f%%/day")).mkString(" ")
    r.latest.foreach { l =>
      b += s"latest: pct=${l.pct} charging=${l.charging} charge=${l.chargeUah.fold("n/a")(_.toString + "uAh")} " +
        s"curAvg=${l.currentAvgUa.fold("n/a")(_.toString + "uA")} curNow=${l.currentNowUa.fold("n/a")(_.toString + "uA")} " +
        s"voltage=${l.voltageMv.fold("n/a")(_.toString + "mV")} temp=${if l.tempC.isNaN then "n/a" else f"${l.tempC}%.1fC"} " +
        s"plug=${l.plug.getOrElse("n/a")}"
    }
    b.result().mkString("\n")
