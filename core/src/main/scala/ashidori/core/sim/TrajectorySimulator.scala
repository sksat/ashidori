package ashidori.core.sim

import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import ashidori.core.*

/** A scripted day of movement, used to generate synthetic logs for end-to-end
  * tests of the timeline pipeline and for on-device replay in debug builds.
  */
object TrajectorySimulator:

  enum Step:
    /** Stay around `at` for the given minutes. `indoor` makes fixes noisier. */
    case Stay(minutes: Double, at: LatLng, name: String, indoor: Boolean = true)
    /** Move along `via :+ to` with the given mode. */
    case Move(
        mode: TransportMode,
        to: LatLng,
        via: Seq[LatLng] = Nil,
        cruiseKmh: Double = Double.NaN,
        /** no GNSS at all (subway, long tunnels) */
        underground: Boolean = false,
        /** distance between stops (stations, bus stops, traffic lights); 0 = none */
        stopEveryM: Double = Double.NaN
    )

  final case class Scenario(name: String, startTime: Long, origin: LatLng, steps: Seq[Step], seed: Long = 1L)

  /** Ground truth for one step of the scenario. */
  final case class Truth(start: Long, end: Long, mode: TransportMode, at: Option[LatLng], name: String):
    def isStay: Boolean = mode == TransportMode.Still

  final case class Output(fixes: Vector[Fix], motion: Vector[MotionWindow], truth: Vector[Truth])

  /** How the logger samples position, mirroring the default adaptive policy. */
  final case class Sampling(
      stillIntervalS: Int = 300,
      footIntervalS: Int = 10,
      vehicleIntervalS: Int = 5,
      outlierRate: Double = 0.01,
      motionWindowS: Int = 10
  )

  private def defaultCruise(m: TransportMode): Double = m match
    case TransportMode.Walking => 4.8
    case TransportMode.Running => 10.5
    case TransportMode.Cycling => 15.0
    case TransportMode.Car     => 40.0
    case TransportMode.Bus     => 25.0
    case TransportMode.Train   => 75.0
    case TransportMode.Subway  => 50.0
    case TransportMode.Flying  => 780.0
    case TransportMode.Ferry   => 30.0
    case _                     => 5.0

  private def defaultStopEvery(m: TransportMode): Double = m match
    case TransportMode.Car    => 600
    case TransportMode.Bus    => 450
    case TransportMode.Train  => 2500
    case TransportMode.Subway => 1100
    case _                    => 0

  /** Typical accelerometer behaviour per mode: (cadence steps/min, |a| stddev). */
  private def motionProfile(m: TransportMode, moving: Boolean): (Double, Double) = m match
    case TransportMode.Walking => (110, 2.6)
    case TransportMode.Running => (165, 6.0)
    case TransportMode.Cycling => (0, 1.6)
    case TransportMode.Car     => (0, if moving then 0.55 else 0.1)
    case TransportMode.Bus     => (0, if moving then 0.7 else 0.12)
    case TransportMode.Train   => (0, if moving then 0.18 else 0.08)
    case TransportMode.Subway  => (0, if moving then 0.22 else 0.08)
    case TransportMode.Flying  => (0, 0.12)
    case TransportMode.Ferry   => (0, 0.3)
    case _                     => (0, 0.03)

  /** Ground-truth state at one second. */
  private final case class Tick(t: Long, lat: Double, lon: Double, speed: Double, mode: TransportMode, underground: Boolean, indoor: Boolean)

  def run(sc: Scenario, sampling: Sampling = Sampling()): Output =
    val rnd = Random(sc.seed)
    val ticks = ArrayBuffer.empty[Tick]
    val truth = Vector.newBuilder[Truth]
    var t = sc.startTime
    var pos = sc.origin

    for step <- sc.steps do
      step match
        case Step.Stay(min, at, name, indoor) =>
          val end = t + (min * 60_000).toLong
          truth += Truth(t, end, TransportMode.Still, Some(at), name)
          while t < end do
            ticks += Tick(t, at.lat, at.lon, 0, TransportMode.Still, false, indoor)
            t += 1000
          pos = at
        case mv: Step.Move =>
          val start = t
          val pts = (pos +: mv.via) :+ mv.to
          val cruise = (if mv.cruiseKmh.isNaN then defaultCruise(mv.mode) else mv.cruiseKmh) / 3.6
          val stopEvery = if mv.stopEveryM.isNaN then defaultStopEvery(mv.mode) else mv.stopEveryM
          // Acceleration limits: walking is instant, trains are gentle.
          val accel = mv.mode match
            case TransportMode.Flying                        => 2.0
            case TransportMode.Train | TransportMode.Subway => 0.8
            case TransportMode.Walking | TransportMode.Running => 10.0
            case _                                           => 1.5
          var v = 0.0
          var sinceStop = 0.0
          var stopLeft = 0
          for seg <- pts.sliding(2) if seg.size == 2 do
            val a = seg(0)
            val b = seg(1)
            val len = a.distanceTo(b)
            var s = 0.0
            while s < len do
              if stopLeft > 0 then
                stopLeft -= 1
                v = 0
              else if stopEvery > 0 && sinceStop + v * v / (2 * accel) >= stopEvery then
                v = math.max(0.0, v - accel)
                if v == 0 then
                  sinceStop = 0
                  stopLeft = mv.mode match
                    case TransportMode.Train | TransportMode.Subway => 30 + rnd.nextInt(20)
                    case TransportMode.Bus                          => 15 + rnd.nextInt(30)
                    case _                                          => rnd.nextInt(45)
              else
                val target = cruise * (0.9 + 0.2 * rnd.nextDouble())
                v = if target > v then math.min(target, v + accel) else math.max(target, v - accel)
              s += v
              sinceStop += v
              val f = math.min(1.0, s / len)
              ticks += Tick(t, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, v, mv.mode, mv.underground, false)
              t += 1000
          truth += Truth(start, t, mv.mode, None, mv.mode.label)
          pos = mv.to

    Output(sampleFixes(ticks, sampling, rnd), motionWindows(ticks, sampling, rnd), truth.result())

  private def sampleFixes(ticks: collection.IndexedSeq[Tick], s: Sampling, rnd: Random): Vector[Fix] =
    val out = Vector.newBuilder[Fix]
    var nextAt = Long.MinValue
    for tk <- ticks do
      if !tk.underground && tk.t >= nextAt then
        val interval =
          if tk.mode == TransportMode.Still then s.stillIntervalS
          else if tk.mode.isVehicle then s.vehicleIntervalS
          else s.footIntervalS
        nextAt = tk.t + interval * 1000L
        val acc =
          if tk.mode == TransportMode.Flying then 20 + rnd.nextDouble() * 40
          else if tk.indoor then 12 + rnd.nextDouble() * 30
          else 3 + rnd.nextDouble() * 10
        val outlier = rnd.nextDouble() < s.outlierRate
        val sigma = if outlier then 250.0 else acc / 1.5
        val dn = rnd.nextGaussian() * sigma
        val de = rnd.nextGaussian() * sigma
        val lat = tk.lat + dn / 111_320.0
        val lon = tk.lon + de / (111_320.0 * math.cos(math.toRadians(tk.lat)))
        val speed = math.max(0.0, tk.speed + rnd.nextGaussian() * 0.25).toFloat
        out += Fix(tk.t, lat, lon, acc.toFloat, 40.0, speed, Float.NaN, "gps", 18)
    out.result()

  private def motionWindows(ticks: collection.IndexedSeq[Tick], s: Sampling, rnd: Random): Vector[MotionWindow] =
    ticks.grouped(s.motionWindowS).filter(_.nonEmpty).map { g =>
      val mid = g(g.size / 2)
      val moving = g.exists(_.speed > 0.5)
      val (cadence, std) = motionProfile(mid.mode, moving)
      val dur = (g.last.t - g.head.t) + 1000
      val steps = math.max(0, (cadence * dur / 60000.0 + rnd.nextGaussian() * 1.5).round.toInt)
      val noisyStd = math.max(0.01, std * (0.8 + 0.4 * rnd.nextDouble()))
      MotionWindow(g.head.t, g.head.t + dur, noisyStd.toFloat, 0f, if cadence == 0 then 0 else steps)
    }.toVector
