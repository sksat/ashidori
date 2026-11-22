package ashidori.core

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The PICS reception only receives (docs/design/pics.md 7.1): the manifests
  * declare neither BLUETOOTH_CONNECT nor BLUETOOTH_ADVERTISE (without them
  * Android 12+ lets an app neither connect nor advertise), BLUETOOTH_SCAN
  * has no neverForLocation, and no source uses the advertiser or GATT. A
  * check of the sources, as DbOpenSuite does: not a proof of what the radio
  * sends (an active scan may send scan requests to scannable advertisers).
  */
class ReceiveOnlySuite extends munit.FunSuite:
  private val app = Paths.get("..", "app", "src")

  private def files(ext: String): Seq[(Path, String)] =
    assume(Files.isDirectory(app), s"no app sources at $app")
    Files.walk(app).iterator.asScala.filter(_.toString.endsWith(ext)).map(p => p -> Files.readString(p)).toSeq

  test("no manifest declares BLUETOOTH_CONNECT or BLUETOOTH_ADVERTISE; BLUETOOTH_SCAN without neverForLocation") {
    val manifests = files("AndroidManifest.xml")
    assert(manifests.nonEmpty)
    for (p, t) <- manifests; bad <- Seq("BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "BLUETOOTH_ADMIN", "BLUETOOTH_PRIVILEGED") do
      assert(!t.contains(bad), s"$p declares $bad")
    val scan = manifests.flatMap((_, t) => t.linesIterator.filter(_.contains("android.permission.BLUETOOTH_SCAN")))
    assertEquals(scan.size, 1, scan.toString)
    assert(!manifests.exists(_._2.contains("neverForLocation")))
    // The old permission (Android 11 and earlier) is not wanted: minSdk 33.
    assert(!manifests.exists(_._2.contains("\"android.permission.BLUETOOTH\"")))
    assert(manifests.exists(_._2.contains("android.hardware.bluetooth_le\" android:required=\"false\"")))
  }

  test("no source advertises, connects or uses GATT") {
    val src = files(".scala") ++ files(".kt") ++ files(".java")
    assert(src.nonEmpty)
    val banned = Seq("BluetoothLeAdvertiser", "getBluetoothLeAdvertiser", "AdvertiseSettings", "AdvertisingSet",
      "connectGatt", "BluetoothGatt", "BluetoothSocket", "createBond", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE",
      "startAdvertising", "listenUsingRfcomm", "listenUsingL2cap", "createL2capChannel", "createRfcommSocket")
    for (p, t) <- src; b <- banned do assert(!t.contains(b), s"$p uses $b")
    // The scanner is there (this test would pass trivially without it).
    assert(src.exists(_._2.contains("getBluetoothLeScanner")))
  }
