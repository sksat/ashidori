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
  val Akihabara = LatLng(35.6984, 139.7731)
  val Shimbashi = LatLng(35.6663, 139.7583) // JR 新橋駅
  val ShimbashiYurikamome = LatLng(35.6655, 139.7598) // ゆりかもめ 新橋駅
  val BigSightStation = LatLng(35.6301, 139.7898) // ゆりかもめ 東京ビッグサイト駅
  val Office = LatLng(35.6298, 139.7942) // 東京ビッグサイト, 有明
  val Lunch = LatLng(35.6377, 139.7922) // 有明ガーデン
  val Dinner = LatLng(36.0848, 140.1118) // near つくばセンター
  val Park = LatLng(36.0705, 140.1218) // 洞峰公園
  val Haneda = LatLng(35.5494, 139.7798)
  val NewChitose = LatLng(42.7752, 141.6923)

  /** Tsukuba Express from つくば to 北千住, above ground: 研究学園, 守谷, 柏の葉キャンパス, 南流山, 八潮. */
  private val TxLine = Seq(
    LatLng(36.0818, 140.0825), LatLng(35.9510, 139.9926), LatLng(35.8932, 139.9522),
    LatLng(35.8380, 139.9032), LatLng(35.8076, 139.8448))
  /** Tsukuba Express from 北千住 to 秋葉原, underground: 南千住, 浅草, 新御徒町. */
  private val TxUnderground = Seq(LatLng(35.7330, 139.7990), LatLng(35.7134, 139.7920), LatLng(35.7071, 139.7816))
  /** JR (京浜東北線・山手線) from 秋葉原 to 新橋: 神田, 東京, 有楽町. */
  private val JrLine = Seq(LatLng(35.6918, 139.7709), LatLng(35.6812, 139.7671), LatLng(35.6751, 139.7630))
  /** ゆりかもめ from 新橋 to 東京ビッグサイト, elevated: 汐留, 竹芝, 日の出, 芝浦ふ頭, レインボーブリッジ,
    * お台場海浜公園, 台場, 東京国際クルーズターミナル, テレコムセンター, 青海.
    */
  private val Yurikamome = Seq(
    LatLng(35.6628, 139.7614), LatLng(35.6545, 139.7621), LatLng(35.6478, 139.7603), LatLng(35.6420, 139.7569),
    LatLng(35.6365, 139.7633), LatLng(35.6296, 139.7785), LatLng(35.6258, 139.7714), LatLng(35.6197, 139.7728),
    LatLng(35.6177, 139.7793), LatLng(35.6245, 139.7811))
  /** Campus bus from 筑波大学中央 to つくばセンター. */
  private val CampusBus = Seq(LatLng(36.1000, 140.1045), LatLng(36.0900, 140.1080))

  private def at(date: LocalDate, h: Int, m: Int): Long =
    date.atTime(h, m).atZone(Tokyo).toInstant.toEpochMilli

  /** A weekday commute from Tsukuba to Ariake: walk, campus bus, Tsukuba Express
    * (underground, without GNSS, south of 北千住), JR, ゆりかもめ, walk, office, lunch,
    * then the same way back, dinner near the station and a car home.
    */
  def commute(date: LocalDate = LocalDate.of(2026, 10, 5), seed: Long = 1): Scenario =
    Scenario(
      "commute",
      at(date, 7, 0),
      Home,
      Seq(
        Step.Stay(30, Home, "自宅"),
        Step.Move(Walking, CampusBusStop),
        Step.Move(Bus, TsukubaStation, via = CampusBus, cruiseKmh = 30),
        Step.Stay(3, TsukubaStation, "つくば駅"),
        Step.Move(Train, KitaSenju, via = TxLine, cruiseKmh = 110, stopEveryM = 6000),
        Step.Move(Train, Akihabara, via = TxUnderground, underground = true, cruiseKmh = 80, stopEveryM = 2000),
        Step.Stay(3, Akihabara, "秋葉原駅 乗り換え"),
        Step.Move(Train, Shimbashi, via = JrLine, cruiseKmh = 60, stopEveryM = 1100),
        Step.Move(Walking, ShimbashiYurikamome),
        Step.Stay(3, ShimbashiYurikamome, "新橋駅 乗り換え"),
        Step.Move(Train, BigSightStation, via = Yurikamome, cruiseKmh = 45, stopEveryM = 1000),
        Step.Move(Walking, Office),
        Step.Stay(170, Office, "職場"),
        Step.Move(Walking, Lunch),
        Step.Stay(45, Lunch, "ランチ"),
        Step.Move(Walking, Office),
        Step.Stay(300, Office, "職場"),
        Step.Move(Walking, BigSightStation),
        Step.Move(Train, ShimbashiYurikamome, via = Yurikamome.reverse, cruiseKmh = 45, stopEveryM = 1000),
        Step.Move(Walking, Shimbashi),
        Step.Stay(3, Shimbashi, "新橋駅 乗り換え"),
        Step.Move(Train, Akihabara, via = JrLine.reverse, cruiseKmh = 60, stopEveryM = 1100),
        Step.Stay(3, Akihabara, "秋葉原駅 乗り換え"),
        Step.Move(Train, KitaSenju, via = TxUnderground.reverse, underground = true, cruiseKmh = 80, stopEveryM = 2000),
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
