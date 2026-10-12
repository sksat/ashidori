package ashidori.core

/** Short area names for unnamed places from a reverse-geocoded address. */
object AreaName:
  private val Country = """^(日本|Japan)[、,]\s*""".r
  private val Postal = """^〒?\s*\d{3}-?\d{4}\s*""".r
  private val Prefecture = """^(東京都|北海道|(?:京都|大阪)府|.{2,3}県)""".r
  /** First block/lot number (ASCII or full-width digits, or kanji numerals before 丁目). */
  private val Numbers = """([0-9０-９]|[一二三四五六七八九十]+丁目).*$""".r

  /** "日本、〒154-0024 東京都世田谷区三軒茶屋２丁目１１−２" → "世田谷区三軒茶屋".
    * Returns None when nothing useful is left (e.g. a bare prefecture).
    */
  def fromAddressLine(line: String): Option[String] =
    var s = line.trim
    s = Country.replaceFirstIn(s, "")
    s = Postal.replaceFirstIn(s, "")
    s = Prefecture.replaceFirstIn(s, "")
    s = Numbers.replaceFirstIn(s, "").trim.stripSuffix("、").trim
    Option(s).filter(_.length >= 2)
