package ashidori.core

import ashidori.core.sync.{PairLink, PairQr}

class PairQrSuite extends munit.FunSuite:
  private val Link = PairLink.url("https://sync.example.net", "7K3M9QXZ")

  /** `m` drawn as a camera frame would hold it: `scale` pixels per module,
    * the quiet zone, a light margin, rows padded to the returned stride,
    * optionally turned by 90°.
    */
  private def frame(m: PairQr.Modules, scale: Int, margin: Int, padding: Int, turned: Boolean = false): (Array[Byte], Int, Int, Int) =
    val side = (m.size + 2 * PairQr.QuietZone) * scale + 2 * margin
    val stride = side + padding
    val px = Array.fill[Byte](stride * side)(0xe0.toByte)
    for y <- 0 until side; x <- 0 until side do
      val (mx, my) = (Math.floorDiv(x - margin, scale) - PairQr.QuietZone, Math.floorDiv(y - margin, scale) - PairQr.QuietZone)
      val inside = mx >= 0 && my >= 0 && mx < m.size && my < m.size
      val dark = inside && (if turned then m.isDark(my, m.size - 1 - mx) else m.isDark(mx, my))
      if dark then px(y * stride + x) = 0x20.toByte
    (px, side, side, stride)

  test("a registration link round-trips through a QR code"):
    val m = PairQr.encode(Link)
    // A small version (29 modules: version 3) holds the link at level M.
    assert(m.size <= 29, m.size)
    for (scale, padding, turned) <- Seq((3, 0, false), (4, 64, false), (5, 12, true)) do
      val (px, w, h, stride) = frame(m, scale, margin = 20, padding = padding, turned = turned)
      val text = PairQr.decode(px, w, h, stride)
      assertEquals(text, Some(Link), s"scale $scale, padding $padding, turned $turned")
      assertEquals(text.flatMap(PairLink.parse(_)), Some(PairLink("https://sync.example.net", "7K3M9QXZ")))

  test("the custom-scheme form round-trips too"):
    val link = "ashidori://pair?server=https%3A%2F%2Fsync.example.net&c=7K3M9QXZ"
    val (px, w, h, stride) = frame(PairQr.encode(link), 4, 8, 0)
    assertEquals(PairQr.decode(px, w, h, stride).flatMap(PairLink.parse(_)), Some(PairLink("https://sync.example.net", "7K3M9QXZ")))

  test("an image without a code decodes to nothing"):
    val w = 320
    val h = 240
    assertEquals(PairQr.decode(Array.fill[Byte](w * h)(0x80.toByte), w, h, w), None)
    val noise = scala.util.Random(1)
    assertEquals(PairQr.decode(Array.fill[Byte](w * h)(noise.nextInt(256).toByte), w, h, w), None)

  test("the last row need not be padded"):
    val (px, w, h, stride) = frame(PairQr.encode(Link), 3, 10, 16)
    assertEquals(PairQr.decode(px.take(stride * (h - 1) + w), w, h, stride), Some(Link))
    intercept[IllegalArgumentException](PairQr.decode(px.take(stride * (h - 1)), w, h, stride))
