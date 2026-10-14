package ashidori.core.sync

import com.google.zxing.{BinaryBitmap, PlanarYUVLuminanceSource, ReaderException}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** QR codes of registration links ([[PairLink]]), encoded and decoded on the
  * device with ZXing (core). Only the link is ever encoded: never the
  * recovery code.
  */
object PairQr:
  /** The modules of a QR code, without the quiet zone: `size` × `size`. */
  final class Modules private[PairQr] (val size: Int, dark: Array[Boolean]):
    def isDark(x: Int, y: Int): Boolean = dark(y * size + x)

  /** Error correction level M (15 %): a registration link stays a small code. */
  def encode(text: String): Modules =
    val m = Encoder.encode(text, ErrorCorrectionLevel.M).getMatrix
    val n = m.getWidth
    Modules(n, Array.tabulate(n * n)(i => m.get(i % n, i / n) == 1))

  /** Width of the light border a reader needs around the modules (in modules). */
  val QuietZone = 4

  /** The text of a QR code in an 8-bit luminance image (e.g. the Y plane of
    * a camera frame): row `y` starts at `y * rowStride`. None when no code
    * is found or it does not decode.
    */
  def decode(luminance: Array[Byte], width: Int, height: Int, rowStride: Int): Option[String] =
    require(rowStride >= width && luminance.length >= rowStride * (height - 1) + width, "luminance does not hold the image")
    val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
    try Some(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).getText)
    catch case _: ReaderException => None
