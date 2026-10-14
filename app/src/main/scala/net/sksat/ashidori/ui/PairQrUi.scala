package net.sksat.ashidori.ui

import android.Manifest
import android.graphics.{Bitmap, Color}
import android.view.Gravity
import android.widget.{ImageView, LinearLayout, Toast}
import androidx.camera.core.{CameraSelector, ImageAnalysis, ImageProxy, Preview}
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import scala.util.Try
import net.sksat.ashidori.MainActivity
import ashidori.core.sync.{PairLink, PairQr}
import Ui.*

/** QR codes of registration links: shown with ZXing (core), and scanned
  * with CameraX and ZXing. Frames are decoded on the device and never leave
  * it; no Google Play services. Only links are encoded, never the recovery
  * code. The scanner also reads the web viewer's pairing QR code
  * (BrowserPairingUi, [[scanFor]]).
  */
object PairQrUi:
  /** `link` as a QR code with its quiet zone, about `sizeDp` wide, dark on
    * white whatever the theme (readers expect it), in whole pixels per
    * module so that it stays sharp.
    */
  def image(link: String, sizeDp: Float = 220f)(using c: Ctx): ImageView =
    val m = PairQr.encode(link)
    val q = PairQr.QuietZone
    val n = m.size + 2 * q
    val k = math.max(1, dp(sizeDp) / n)
    val px = Array.tabulate(n * n * k * k) { i =>
      val (x, y) = ((i % (n * k)) / k - q, (i / (n * k)) / k - q)
      if x >= 0 && y >= 0 && x < m.size && y < m.size && m.isDark(x, y) then Color.BLACK else Color.WHITE
    }
    val v = ImageView(c)
    v.setImageBitmap(Bitmap.createBitmap(px, n * k, n * k, Bitmap.Config.ARGB_8888))
    v.setContentDescription("登録リンクの QR コード")
    val lp = LinearLayout.LayoutParams(n * k, n * k)
    lp.gravity = Gravity.CENTER_HORIZONTAL
    v.setLayoutParams(lp)
    v

  /** Scans a registration link and hands it to `onLink` (on the main
    * thread). The camera permission is asked for here, when the user starts
    * a scan. A code that is not a registration link is reported and the
    * scan goes on; nothing is registered here.
    */
  def scan(act: MainActivity, allowHttp: Boolean)(onLink: PairLink => Unit): Unit =
    scanFor(act, "登録リンクの QR コードを写してください。", "登録リンクの QR コードではありません。")(PairLink.parse(_, allowHttp))(onLink)

  /** Scans QR codes until `parse` accepts one, and hands its result to
    * `onFound` (on the main thread). `prompt` is shown under the preview,
    * `other` when a code was read that `parse` refuses (the scan goes on).
    */
  def scanFor[A](act: MainActivity, prompt: String, other: String)(parse: String => Option[A])(onFound: A => Unit): Unit =
    def toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    if act.granted(Manifest.permission.CAMERA) then scanner(act, prompt, other, parse, onFound)
    else
      act.requestPermissions(Seq(Manifest.permission.CAMERA)) { () =>
        if act.granted(Manifest.permission.CAMERA) then scanner(act, prompt, other, parse, onFound)
        else toast("カメラの使用が許可されていません。Android の設定で許可できます")
      }

  private def scanner[A](act: MainActivity, prompt: String, other: String, parse: String => Option[A], onFound: A => Unit): Unit =
    given Ctx = act
    val preview = PreviewView(act)
    // A TextureView: a SurfaceView would sit outside the dialog's window.
    preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE)
    preview.setLayoutParams(LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
    val status = caption(prompt)
    status.setPadding(0, dp(8), 0, 0)
    val worker = Executors.newSingleThreadExecutor()
    // Main thread only.
    var closed = false
    var provider: Option[ProcessCameraProvider] = None
    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    val d = MaterialAlertDialogBuilder(act)
      .setTitle("QR コードを読み取る")
      .setView(padded(vbox(preview, status), 24, 8))
      .setNegativeButton("やめる", null)
      .create()

    def found(text: String): Unit =
      if !closed then
        parse(text) match
          case Some(a) =>
            d.dismiss()
            onFound(a)
          case None => status.setText(other)

    // Reused between frames; the analyzer runs on `worker` only.
    var buf = Array.emptyByteArray
    def analyze(img: ImageProxy): Unit =
      try
        val y = img.getPlanes()(0)
        val b = y.getBuffer
        if buf.length != b.remaining then buf = new Array[Byte](b.remaining)
        b.get(buf)
        Try(PairQr.decode(buf, img.getWidth, img.getHeight, y.getRowStride)).toOption.flatten
          .foreach(text => act.runOnUiThread(() => found(text)))
      finally img.close()

    d.setOnDismissListener { _ =>
      closed = true
      analysis.clearAnalyzer()
      provider.foreach(_.unbindAll())
      worker.shutdown()
    }
    d.show()

    val future = ProcessCameraProvider.getInstance(act)
    future.addListener(
      () =>
        if !closed then
          Try {
            val p = future.get()
            provider = Some(p)
            val usePreview = Preview.Builder().build()
            usePreview.setSurfaceProvider(preview.getSurfaceProvider)
            analysis.setAnalyzer(worker, img => analyze(img))
            p.unbindAll()
            // Bound to the activity: the camera stops while it is in the background.
            p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, analysis)
          }.failed.foreach { e =>
            d.dismiss()
            Toast.makeText(act, s"カメラを使用できません: ${e.getMessage}", Toast.LENGTH_LONG).show()
          },
      act.getMainExecutor
    )
