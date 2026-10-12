package ashidori.core.sim

import java.time.{LocalDate, ZoneId}
import ashidori.core.*
import TrajectorySimulator.{Scenario, Step}
import TransportMode.*

/** Ready-made scenarios around Tokyo, used by tests and the debug replay. */
object Scenarios:
  val Tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

  val Home = LatLng(35.6466, 139.6532) // near Sangenjaya
  val HomeStation = LatLng(35.6436, 139.6710)
  val Shibuya = LatLng(35.6580, 139.7016)
  val Otemachi = LatLng(35.6848, 139.7663)
  val Office = LatLng(35.6870, 139.7700)
  val Lunch = LatLng(35.6895, 139.7712)
  val Park = LatLng(35.6719, 139.6949) // Yoyogi park
  val Haneda = LatLng(35.5494, 139.7798)
  val NewChitose = LatLng(42.7752, 141.6923)

  private def at(date: LocalDate, h: Int, m: Int): Long =
    date.atTime(h, m).atZone(Tokyo).toInstant.toEpochMilli

  /** A weekday commute: walk, train, subway (no GNSS), walk, office, lunch, bus home. */
  def commute(date: LocalDate = LocalDate.of(2026, 10, 5), seed: Long = 1): Scenario =
    Scenario(
      "commute",
      at(date, 7, 30),
      Home,
      Seq(
        Step.Stay(40, Home, "自宅"),
        Step.Move(Walking, HomeStation),
        Step.Move(Train, Shibuya, cruiseKmh = 70, stopEveryM = 1500),
        Step.Stay(2, Shibuya, "渋谷駅 乗り換え"),
        Step.Move(Subway, Otemachi, via = Seq(LatLng(35.6710, 139.7640)), underground = true),
        Step.Move(Walking, Office),
        Step.Stay(170, Office, "職場"),
        Step.Move(Walking, Lunch),
        Step.Stay(45, Lunch, "ランチ"),
        Step.Move(Walking, Office),
        Step.Stay(300, Office, "職場"),
        Step.Move(Walking, Otemachi),
        Step.Move(Bus, Shibuya, via = Seq(LatLng(35.6740, 139.7400)), cruiseKmh = 28),
        Step.Move(Walking, LatLng(35.6600, 139.6990)),
        Step.Stay(60, LatLng(35.6600, 139.6990), "夕食", indoor = true),
        Step.Move(Car, Home, cruiseKmh = 35),
        Step.Stay(120, Home, "自宅")
      ),
      seed
    )

  /** A weekend with cycling and a run in the park. */
  def weekend(date: LocalDate = LocalDate.of(2026, 10, 10), seed: Long = 2): Scenario =
    Scenario(
      "weekend",
      at(date, 9, 0),
      Home,
      Seq(
        Step.Stay(60, Home, "自宅"),
        Step.Move(Cycling, Park, via = Seq(LatLng(35.6620, 139.6800))),
        Step.Stay(10, Park, "代々木公園 駐輪場", indoor = false),
        Step.Move(Running, LatLng(35.6760, 139.6930), via = Seq(LatLng(35.6700, 139.6990), LatLng(35.6740, 139.6980))),
        Step.Move(Walking, Park),
        Step.Stay(30, Park, "代々木公園", indoor = false),
        Step.Move(Cycling, Home, via = Seq(LatLng(35.6620, 139.6800))),
        Step.Stay(90, Home, "自宅")
      ),
      seed
    )

  /** A flight from Haneda to New Chitose. */
  def flight(date: LocalDate = LocalDate.of(2026, 10, 12), seed: Long = 3): Scenario =
    Scenario(
      "flight",
      at(date, 8, 0),
      Haneda,
      Seq(
        Step.Stay(60, Haneda, "羽田空港"),
        Step.Move(Flying, NewChitose, cruiseKmh = 750),
        Step.Stay(30, NewChitose, "新千歳空港")
      ),
      seed
    )

  val all: Seq[() => Scenario] = Seq(() => commute(), () => weekend(), () => flight())
