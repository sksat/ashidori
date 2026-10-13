package ashidori.core

/** Short area names for unnamed places from a reverse-geocoded address. */
object AreaName:
  private val Country = """^(日本|Japan)[、,]\s*""".r
  private val Postal = """^〒?\s*\d{3}-?\d{4}\s*""".r
  private val Prefecture = """^(東京都|北海道|(?:京都|大阪)府|.{2,3}県)""".r
  /** First block/lot number (ASCII or full-width digits, or kanji numerals before 丁目). */
  private val Repeated = """^(.*?)(.{2,})\2$""".r
  private val Numbers ="""([0-9０-９]|[一二三四五六七八九十]+丁目).*$""".r

  /** "日本、〒154-0024 東京都世田谷区三軒茶屋２丁目１１−２" → "世田谷区三軒茶屋".
    * Returns None when nothing useful is left (e.g. a bare prefecture).
    */
  def fromAddressLine(line: String): Option[String] =
    var s = line.trim
    s = Country.replaceFirstIn(s, "")
    s = Postal.replaceFirstIn(s, "")
    s = Prefecture.replaceFirstIn(s, "")
    s = Numbers.replaceFirstIn(s, "").trim.stripSuffix("、").trim
    // Geocoders append feature or road names after a space
    // ("大田区羽田空港 羽田空港 (HND)", "千歳市美々 道路"): keep the area.
    s = s.split("""[\s　]+""").headOption.getOrElse("")
    // ...or glue the feature name on: "大田区羽田空港羽田空港" → "大田区羽田空港".
    s = Repeated.replaceFirstIn(s, "$1$2")
    Option(s).filter(_.length >= 2)

  private val District = """^.+?郡(.+[町村])$""".r
  private val DesignatedWard = """^(.+?市).+区$""".r
  private val Aza = """^(大字|字)""".r

  /** Municipality as shown in an area name: the 郡 is dropped
    * ("入間郡毛呂山町" → "毛呂山町"); wards of designated cities stay
    * ("札幌市中央区"), as in [[fromAddressLine]].
    */
  def municipality(m: String): String = m.trim match
    case District(rest) => rest
    case other          => other

  /** City for per-period summaries: "札幌市中央区" → "札幌市", "渋谷区" → "渋谷区". */
  def city(m: String): String = municipality(m) match
    case DesignatedWard(c) => c
    case other             => other

  /** Area name from a municipality and a 大字・町丁目 name, in the same form as
    * [[fromAddressLine]]: ("渋谷区", "神宮前六丁目") → "渋谷区神宮前",
    * ("入間郡毛呂山町", "大字阿諏訪") → "毛呂山町阿諏訪".
    */
  def fromParts(municipality: String, town: String): Option[String] =
    val m = this.municipality(municipality)
    val t = Numbers.replaceFirstIn(Aza.replaceFirstIn(town.trim, ""), "").trim
    Option(m + t).filter(_.length >= 2)
