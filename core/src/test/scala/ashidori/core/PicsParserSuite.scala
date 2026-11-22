package ashidori.core

import PicsFormat.*

/** Reading PICS advertisements (docs/design/pics.md 2.2): the one public
  * dump (デイリーポータルZ, design 2.3), synthetic packets of every kind, and
  * packets that are short, of an unknown kind or random. Only published and
  * synthetic values: no dump of the owner's (design 9).
  */
class PicsParserSuite extends munit.FunSuite:
  private def p(h: String): Packet = packet(unhex(h).toOption.get)

  /** A synthetic packet: bytes 0–1 as observed, `kind`, message ID, two
    * unknown bytes, the intersection ID, then `rest` padded to 24 bytes.
    */
  private def synth(kind: Int, id: Long, rest: Seq[Int], msg: Int = 0xff, size: Int = 24): Array[Byte] =
    val head = Seq(0x58, 0x01, kind, msg, 0, 0) ++ Seq(24, 16, 8, 0).map(s => ((id >> s) & 0xff).toInt)
    (head ++ rest ++ Seq.fill(math.max(0, size - head.size - rest.size))(0)).take(size).map(_.toByte).toArray

  private def s32(v: Int): Seq[Int] = Seq(24, 16, 8, 0).map(s => (v >> s) & 0xff)

  test("the published dump (Shinjuku, 2025): kind 2, its intersection, green step 5 and red step 6") {
    val x = p("58 01 02 FF 00 00 00 CD 08 49 43 51 FF FF FF FF 00 00 00 00 00 00 00 00")
    assertEquals(x.size, 24)
    assertEquals((x.kind, x.msgId, x.intersection), (Some(2), Some(0xff), Some("00CD0849")))
    assertEquals(idNumber("00CD0849"), "13437001")
    assert(x.qualifying)
    assertEquals(x.body, Body.Signals(Seq(Ped(0, Light.Green, 3, Some(5)), Ped(1, Light.Red, 1, Some(6)))))
    // The content leaves byte 3 out: another message ID is the same content.
    val y = p("58 01 02 07 00 00 00 CD 08 49 43 51 FF FF FF FF 00 00 00 00 00 00 00 00")
    assertEquals(x.content, y.content)
    assertNotEquals(x.content, p("58 01 02 FF 00 00 00 CD 08 49 33 51 FF FF FF FF 00 00 00 00 00 00 00 00").content)
    assertEquals(hex(x.data), "580102FF000000CD08494351FFFFFFFF0000000000000000")
  }

  test("kind 0: the UTF-8 text up to the first NUL") {
    val text = "新宿三丁目".getBytes("UTF-8").toSeq.map(_ & 0xff)
    assertEquals(text.size, 15)
    // 14 bytes fit (10–23): the text is cut there, the broken last character left out.
    val x = packet(synth(0, 13437001L, text))
    assertEquals(x.kind, Some(0))
    val Body.Text(t) = x.body: @unchecked
    assertEquals(t, "新宿三丁")
    val short = packet(synth(0, 13437001L, "PICS01".getBytes("UTF-8").toSeq.map(_ & 0xff)))
    assertEquals(short.body, Body.Text("PICS01"))
  }

  test("kind 1: signed big-endian 1e-6 degrees, negative values too; cut short, no position") {
    // The report's example (35.897104, 139.621165), its ID 11/06/237 put as a hypothesis.
    val x = packet(synth(1, 11006237L, s32(35897104) ++ s32(139621165)))
    assertEquals(x.intersection, Some("00A7F11D"))
    assertEquals(idNumber(x.intersection.get), "11006237")
    val Body.Position(lat, lon) = x.body: @unchecked
    assertEqualsDouble(lat, 35.897104, 1e-9)
    assertEqualsDouble(lon, 139.621165, 1e-9)
    val neg = packet(synth(1, 1L, s32(-33865143) ++ s32(-151209900)))
    assertEquals(neg.body, Body.Position(-33.865143, -151.2099))
    // 17 bytes: the longitude is not all there.
    assertEquals(packet(synth(1, 1L, s32(35897104) ++ s32(139621165), size = 17)).body, Body.Unread)
  }

  test("kind 2: every state and the gauge's bounds; empty slots; at most 6 crosswalks") {
    val bytes = Seq(0x00, 0x71, 0x82, 0x03, 0x14, 0xf5)
    val x = packet(synth(2, 5L, bytes ++ Seq(0x33, 0x33)))
    val Body.Signals(peds) = x.body: @unchecked
    assertEquals(peds.map(_.light), Seq(Light.NoSignal, Light.Red, Light.GreenFlash, Light.Green, Light.NotCovered, Light.Other))
    // 0–7: steps 1–8; 8 and above: not known.
    assertEquals(peds.map(_.step), Seq(Some(1), Some(8), None, Some(1), Some(2), None))
    assertEquals(peds.size, 6)
    // The low bits 0xF: an empty slot.
    val y = packet(synth(2, 5L, Seq(0x43, 0xff, 0x0f, 0x51, 0xff, 0xff)))
    assertEquals(y.body, Body.Signals(Seq(Ped(0, Light.Green, 3, Some(5)), Ped(3, Light.Red, 1, Some(6)))))
    // Cut after two crosswalks: those two.
    assertEquals(packet(synth(2, 5L, Seq(0x43, 0x51), size = 12)).body,
      Body.Signals(Seq(Ped(0, Light.Green, 3, Some(5)), Ped(1, Light.Red, 1, Some(6)))))
  }

  test("short packets: only what their bytes hold; never qualifying under 10 bytes") {
    assertEquals(p("").kind, None)
    assertEquals((p("5801").kind, p("5801").msgId, p("5801").intersection), (None, None, None))
    assertEquals((p("580102").kind, p("580102").msgId), (Some(2), None))
    assertEquals(p("580102FF").msgId, Some(0xff))
    val nine = p("580102FF000000CD08")
    assertEquals((nine.intersection, nine.qualifying, nine.body), (None, false, Body.Unread))
    val ten = p("580102FF000000CD0849")
    assertEquals((ten.intersection, ten.qualifying, ten.body), (Some("00CD0849"), true, Body.Unread))
    // Contents of short packets: as they are (byte 3 left out where there is one).
    assertEquals(p("58").content, "58")
    assertEquals(p("580102FF00").content, "58010200")
  }

  test("an unknown kind: the common part is read, it does not qualify, the body is not read") {
    val x = packet(synth(7, 13437001L, Seq(1, 2, 3)))
    assertEquals((x.kind, x.msgId, x.intersection, x.qualifying, x.body), (Some(7), Some(0xff), Some("00CD0849"), false, Body.Unread))
  }

  test("random bytes of any length never throw") {
    val rnd = scala.util.Random(7)
    for _ <- 0 until 20_000 do
      val b = Array.fill(rnd.nextInt(40))(rnd.nextInt(256).toByte)
      val x = packet(b)
      x.kind; x.msgId; x.intersection; x.qualifying; x.content; x.body
    for k <- 0 to 2; n <- 0 to 30 do packet(synth(k, 1L, Seq.fill(20)(0xff), size = n)).body
  }

  test("the company IDs: 0x01CE always, the test ID 0xFFFF only in debug builds") {
    assert(accepts(0x01ce, debug = false) && accepts(0x01ce, debug = true))
    assert(!accepts(0xffff, debug = false) && accepts(0xffff, debug = true))
    assert(!accepts(0x004c, debug = true))
  }

  test("hex in and out") {
    assertEquals(unhex("58 01 ff").map(_.toSeq), Right(Seq(0x58, 0x01, 0xff).map(_.toByte)))
    assert(unhex("5").isLeft && unhex("zz").isLeft)
    assertEquals(hex(Seq(0x0a, 0xb0).map(_.toByte)), "0AB0")
  }
