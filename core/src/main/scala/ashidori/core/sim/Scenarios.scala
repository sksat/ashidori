package ashidori.core.sim

import java.time.{LocalDate, ZoneId}
import ashidori.core.*
import TrajectorySimulator.{Scenario, Step}
import TransportMode.*

/** Ready-made scenarios around Tsukuba and Tokyo, used by tests and the debug replay.
  * Every place is a public one: "home" is on the University of Tsukuba campus.
  */
object Scenarios:
  val Tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

  val Home = LatLng(36.1110, 140.1000) // University of Tsukuba, 天王台1-1-1
  val CampusBusStop = LatLng(36.1085, 140.1040)
  val TsukubaStation = LatLng(36.0826, 140.1114) // TX つくば駅 (つくばセンター)
  val KitaSenju = LatLng(35.7494, 139.8048)
  val Otemachi = LatLng(35.6848, 139.7663)
  val Office = LatLng(35.6870, 139.7700)
  val Lunch = LatLng(35.6895, 139.7712)
  val Dinner = LatLng(36.0848, 140.1118) // near つくばセンター
  val Park = LatLng(36.0705, 140.1218) // 洞峰公園
  val Haneda = LatLng(35.5494, 139.7798)
  val NewChitose = LatLng(42.7752, 141.6923)

  /** Tsukuba Express from つくば to 北千住: 研究学園, 守谷, 柏の葉キャンパス, 南流山, 八潮. */
  private val TxLine = Seq(
    LatLng(36.0818, 140.0825), LatLng(35.9510, 139.9926), LatLng(35.8932, 139.9522),
    LatLng(35.8380, 139.9032), LatLng(35.8076, 139.8448))
  /** Chiyoda Line from 北千住 to 大手町: 町屋, 西日暮里, 湯島, 新御茶ノ水. */
  private val ChiyodaLine = Seq(LatLng(35.7422, 139.7804), LatLng(35.7322, 139.7668), LatLng(35.7078, 139.7700), LatLng(35.6970, 139.7656))
  /** Campus bus from 筑波大学中央 to つくばセンター. */
  private val CampusBus = Seq(LatLng(36.1000, 140.1045), LatLng(36.0900, 140.1080))

  private def at(date: LocalDate, h: Int, m: Int): Long =
    date.atTime(h, m).atZone(Tokyo).toInstant.toEpochMilli

  /** A weekday commute from Tsukuba to Otemachi: walk, campus bus, Tsukuba Express,
    * subway (no GNSS), walk, office, lunch, then back by subway and train, dinner
    * near the station and a car home.
    */
  def commute(date: LocalDate = LocalDate.of(2026, 10, 5), seed: Long = 1): Scenario =
    Scenario(
      "commute",
      at(date, 7, 0),
      Home,
      Seq(
        Step.Stay(40, Home, "自宅"),
        Step.Move(Walking, CampusBusStop),
        Step.Move(Bus, TsukubaStation, via = CampusBus, cruiseKmh = 30),
        Step.Stay(3, TsukubaStation, "つくば駅"),
        Step.Move(Train, KitaSenju, via = TxLine, cruiseKmh = 110, stopEveryM = 6000),
        Step.Stay(3, KitaSenju, "北千住駅 乗り換え"),
        Step.Move(Subway, Otemachi, via = ChiyodaLine, underground = true),
        Step.Move(Walking, Office),
        Step.Stay(170, Office, "職場"),
        Step.Move(Walking, Lunch),
        Step.Stay(45, Lunch, "ランチ"),
        Step.Move(Walking, Office),
        Step.Stay(300, Office, "職場"),
        Step.Move(Walking, Otemachi),
        Step.Move(Subway, KitaSenju, via = ChiyodaLine.reverse, underground = true),
        Step.Stay(3, KitaSenju, "北千住駅 乗り換え"),
        Step.Move(Train, TsukubaStation, via = TxLine.reverse, cruiseKmh = 110, stopEveryM = 6000),
        Step.Move(Walking, Dinner),
        Step.Stay(60, Dinner, "夕食", indoor = true),
        Step.Move(Car, Home, via = CampusBus.reverse, cruiseKmh = 35),
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
        Step.Move(Cycling, Park, via = Seq(LatLng(36.0900, 140.1100))),
        Step.Stay(10, Park, "洞峰公園 駐輪場", indoor = false),
        Step.Move(Running, LatLng(36.0740, 140.1222), via = Seq(LatLng(36.0690, 140.1240), LatLng(36.0722, 140.1252))),
        Step.Move(Walking, Park),
        Step.Stay(30, Park, "洞峰公園", indoor = false),
        Step.Move(Cycling, Home, via = Seq(LatLng(36.0900, 140.1100))),
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
