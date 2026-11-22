package ashidori.core

/** The advertisements of 高度化PICS roadside units (docs/design/pics.md,
  * 2.2): manufacturer specific data of company 0x01CE, without the company
  * ID (what `ScanRecord.getManufacturerSpecificData(0x01CE)` gives). The
  * format is not published: it comes from public observation, and every
  * field may be wrong. A packet is therefore never refused for its form:
  * whatever arrives with the company ID is recorded as it is (`data`), and
  * read here only as far as its bytes go.
  */
object PicsFormat:
  /** HOUWA SYSTEM DESIGN, k.k.: the ID PICS units send (not PICS's alone). */
  val CompanyId = 0x01CE
  /** The Bluetooth SIG's test ID: accepted by debug builds only, for a test
    * transmitter on a desk (one sending 0x01CE near a road could mislead
    * 信GO! users, design 7.5).
    */
  val TestCompanyId = 0xFFFF

  /** Whether advertisements of `company` are taken. */
  def accepts(company: Int, debug: Boolean): Boolean = company == CompanyId || debug && company == TestCompanyId

  /** A pedestrian signal's state: the low four bits of its byte. */
  enum Light(val code: Int, val label: String):
    case NoSignal extends Light(0, "信号なし")
    case Red extends Light(1, "赤")
    case GreenFlash extends Light(2, "青点滅")
    case Green extends Light(3, "青")
    case NotCovered extends Light(4, "対象なし")
    /** A value not in the observed table (5 to 14). */
    case Other extends Light(-1, "不明")

  object Light:
    def of(code: Int): Light = values.find(_.code == code).getOrElse(Other)

  /** One crosswalk's pedestrian signal (byte 10 + `index` of a kind 2
    * packet): its state, and the remaining-time gauge (the high four bits:
    * 0 to 7 are steps 1 to 8; 8 and above are not known). The gauge is a
    * step, not seconds (design 2.1).
    */
  final case class Ped(index: Int, light: Light, code: Int, step: Option[Int])

  /** What a packet says, as far as it is read. */
  enum Body:
    /** Kind 0: the static text (bytes 10–23, UTF-8, NUL padded). */
    case Text(text: String)
    /** Kind 1: latitude and longitude (bytes 10–17, signed 32-bit big-endian, 1e-6°). */
    case Position(lat: Double, lon: Double)
    /** Kind 2: the pedestrian signals (bytes 10–15, at most 6; 0xF in the low bits is an empty slot). */
    case Signals(peds: Seq[Ped])
    /** Kind 1 cut short, or another kind: nothing read beyond the common header. */
    case Unread

  /** A packet as received (`data` without the company ID). */
  final case class Packet(data: IndexedSeq[Byte]):
    private def u(i: Int): Int = data(i) & 0xff
    def size: Int = data.size
    /** Byte 2. */
    def kind: Option[Int] = Option.when(size >= 3)(u(2))
    /** Byte 3 (meaning unknown). */
    def msgId: Option[Int] = Option.when(size >= 4)(u(3))
    /** Bytes 6–9 in hex (8 characters). */
    def intersection: Option[String] = Option.when(size >= 10)(hex(data.slice(6, 10)))
    /** A reception that starts the receiving state (design 3.1): 10 bytes or
      * more, of kind 0, 1 or 2, with an intersection ID.
      */
    def qualifying: Boolean = size >= 10 && kind.exists(k => k >= 0 && k <= 2)
    /** The data without byte 3, the run's content ([[PicsRuns]]): a packet
      * that differs only there continues the run.
      */
    def content: String = if size < 4 then hex(data) else hex(data.take(3)) + hex(data.drop(4))
    def body: Body = kind match
      case Some(0) if size > 10 =>
        val bytes = data.slice(10, math.min(24, size)).takeWhile(_ != 0).toArray
        // A character cut at byte 23 is left out, not shown as a replacement.
        Body.Text(String(bytes, java.nio.charset.StandardCharsets.UTF_8).reverse.dropWhile(_ == '�').reverse)
      case Some(1) if size >= 18 => Body.Position(s32(10) / 1e6, s32(14) / 1e6)
      case Some(2) if size > 10 =>
        Body.Signals((10 until math.min(16, size)).flatMap { i =>
          val b = u(i)
          val code = b & 0x0f
          Option.when(code != 0x0f)(Ped(i - 10, Light.of(code), code, Option.when((b >> 4) < 8)((b >> 4) + 1)))
        })
      case _ => Body.Unread
    private def s32(i: Int): Int = (u(i) << 24) | (u(i + 1) << 16) | (u(i + 2) << 8) | u(i + 3)

  def packet(data: Array[Byte]): Packet = Packet(data.toIndexedSeq)

  def hex(bs: Iterable[Byte]): String =
    val sb = StringBuilder()
    for b <- bs do
      sb.append(Character.forDigit((b >> 4) & 0xf, 16).toUpper).append(Character.forDigit(b & 0xf, 16).toUpper)
    sb.toString

  /** Bytes of a hex string (spaces ignored); Left if it is not one. */
  def unhex(s: String): Either[String, Array[Byte]] =
    val t = s.filterNot(_.isWhitespace)
    if t.length % 2 != 0 then Left("odd number of hex digits")
    else if !t.forall(c => Character.digit(c, 16) >= 0) then Left("not hex")
    else Right(t.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray)

  /** An intersection ID (8 hex characters) as the unsigned decimal number
    * shown to people (00CD0849: 13437001).
    */
  def idNumber(iid: String): String = scala.util.Try(java.lang.Long.parseLong(iid, 16).toString).getOrElse(iid)
