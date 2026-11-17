package ashidori.core

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import ashidori.core.sync.*

class ArchiveUploadSuite extends munit.FunSuite:
  private val tmp = FunFixture[File](
    _ => Files.createTempDirectory("archive-upload").toFile,
    d => Option(d.listFiles()).toSeq.flatten.foreach(_.delete())
  )

  /** Like the app's fstat: the size of the open file, and an identity that
    * differs between the versions written below.
    */
  private def stat(f: File)(fd: java.io.FileDescriptor): FileStat =
    val ch = java.io.FileInputStream(fd).getChannel
    FileStat(ch.size(), Seq(ch.size()))

  /** Replaces `f` the way ArchiveStore does: a new file, renamed over it. */
  private def replace(f: File, bytes: Array[Byte]): Unit =
    val t = File(f.getParentFile, f.getName + ".tmp")
    Files.write(t.toPath, bytes)
    Files.move(t.toPath, f.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  /** Enqueues every part from `p` on, as the uploader does. */
  private def drain(snap: ArchiveSnapshot, p0: ArchiveUpload.Progress, partSize: Int): (Array[Byte], ArchiveUpload.Progress) =
    var p = p0
    val out = java.io.ByteArrayOutputStream()
    var part = ArchiveUpload.nextPart(snap, p, partSize)
    while part.isDefined do
      out.write(part.get.data)
      p = part.get.next
      part = ArchiveUpload.nextPart(snap, p, partSize)
    (out.toByteArray, p)

  test("a part of a file with a deleted range: held while the range is not cut, or if the file was opened before it") {
    val day = (1000L, 2000L)
    // Pending (not cut yet): held, whenever the file was opened.
    assert(ArchiveUpload.heldByDeletion(day, opened = 5, Seq((1500L, 1600L, 5L, true))))
    // Cut: a part of the file opened before the deletion (sequence 4 < 5) is held,
    // one of the file opened after it (the cut version) is sent.
    assert(ArchiveUpload.heldByDeletion(day, opened = 4, Seq((1500L, 1600L, 5L, false))))
    assert(!ArchiveUpload.heldByDeletion(day, opened = 5, Seq((1500L, 1600L, 5L, false))))
    // A part sealed by an earlier version (no sequence: 0) counts as before every deletion.
    assert(ArchiveUpload.heldByDeletion(day, opened = 0, Seq((1500L, 1600L, 1L, false))))
    // Another day: not held.
    assert(!ArchiveUpload.heldByDeletion(day, opened = 0, Seq((2000L, 2600L, 1L, true))))
    assertEquals(ArchiveUpload.markFile("reg1|upgen:pressure/2026-11-15.parquet"), Some("pressure/2026-11-15.parquet"))
    assertEquals(ArchiveUpload.markFile("reg1|up:fixes/2026-10.parquet"), Some("fixes/2026-10.parquet"))
    assertEquals(ArchiveUpload.markFile("reg1|rev:places"), None)
    assertEquals(ArchiveUpload.markFile("reg1|seq:archive"), None)
  }

  tmp.test("opened, then a deletion completes (cut and published), then the upload resumes: no part of the open version goes out") { dir =>
    val f = File(dir, "2026-10-01.parquet")
    val before = Array.tabulate[Byte](12)(_.toByte)
    val cut = Array.tabulate[Byte](8)(i => (50 + i).toByte)
    Files.write(f.toPath, before)
    val period = (0L, 86_400_000L)
    var ranges = Vector.empty[(Long, Long, Long, Boolean)]
    def seq = ranges.map(_._3).maxOption.getOrElse(0L)
    // As the uploader: the sequence read before the file is opened.
    val opened = seq
    val snap = ArchiveSnapshot.open(f, stat(f))
    val sent = java.io.ByteArrayOutputStream()
    try
      val first = ArchiveUpload.nextPart(snap, ArchiveUpload.resume(None, snap.generation), 4).get
      assert(!ArchiveUpload.heldByDeletion(period, opened, ranges))
      sent.write(first.data)
      // The deletion is recorded (pending), cut out of the file (a new version
      // renamed over it) and marked done, all before the next part.
      ranges = ranges :+ (1000L, 2000L, 1L, true)
      replace(f, cut)
      ranges = ranges.map(r => r.copy(_4 = false))
      // Resuming: the next part would come from the version before the cut.
      assert(ArchiveUpload.nextPart(snap, first.next, 4).isDefined)
      assert(ArchiveUpload.heldByDeletion(period, opened, ranges), "the open version is held")
      // So is the part already sealed from it (its sequence is the one read at open).
      assert(ArchiveUpload.heldByDeletion(period, opened, ranges))
    finally snap.close()
    // The next open reads the sequence again: the cut version, sent from part 0.
    val opened2 = seq
    val snap2 = ArchiveSnapshot.open(f, stat(f))
    try
      assert(!ArchiveUpload.heldByDeletion(period, opened2, ranges))
      val (all, _) = drain(snap2, ArchiveUpload.resume(Some(ArchiveUpload.Progress(snap.generation, 1, 4)), snap2.generation), 4)
      assertEquals(all.toSeq, cut.toSeq)
    finally snap2.close()
  }

  test("no range yet, a deletion recorded, the old file opened, the cut completes: the old file is held") {
    val period = Some((0L, 86_400_000L))
    var ranges = Vector.empty[(Long, Long, Long, Boolean)]
    def seq = ranges.map(_._3).maxOption.getOrElse(0L)
    // The uploader's gate: the check of pending ranges finds none, and right
    // after it the deletion is recorded (before the file is opened).
    val opened = ArchiveUpload.openGate(period, () => seq, () => {
      val now = ranges
      ranges = ranges :+ (1000L, 2000L, 1L, true)
      now
    })
    assertEquals(opened, Some(0L))
    // The file (the version before the cut) is opened; then the cut completes.
    ranges = ranges.map(_.copy(_4 = false))
    // The next part, and every part already sealed from it, is held.
    assert(ArchiveUpload.heldByDeletion(period.get, opened.get, ranges))
    // Had the sequence been read after the check (as before), it would be 1
    // and the old file would have gone out.
    assert(!ArchiveUpload.heldByDeletion(period.get, seq, ranges))
    // A deletion recorded before the sequence is read is seen pending: the file waits.
    ranges = Vector((1000L, 2000L, 1L, true))
    assertEquals(ArchiveUpload.openGate(period, () => seq, () => ranges), None)
    // Another period: opened as usual.
    assertEquals(ArchiveUpload.openGate(Some((5000L, 6000L)), () => seq, () => ranges), Some(1L))
  }

  test("a part sealed by an earlier version (no file recorded): its file is read from the batch") {
    val keys = BlobKeys.of(Array.fill[Byte](32)(7))
    val plain = Crypto.gzip(("""{"file":"fixes/2026-10-01.parquet","gen":"1","part":0,"parts":1,"data":""}""" + "\n").getBytes("UTF-8"))
    val aad = Crypto.aad("dev1", "archive", 42)
    val blob = Crypto.seal(keys, aad, plain)
    assertEquals(ArchiveUpload.fileOf(Crypto.open(keys, aad, blob)), Some("fixes/2026-10-01.parquet"))
    // Not an archive part: none (the caller then does not send it and sends the tables again).
    assertEquals(ArchiveUpload.fileOf(Crypto.gzip("{\"t\":1}\n".getBytes("UTF-8"))), None)
    // Under the wrong slot it does not open at all.
    intercept[java.security.GeneralSecurityException](Crypto.open(keys, Crypto.aad("dev1", "archive", 43), blob))
  }

  tmp.test("a file replaced while it is uploaded: every part comes from the version opened (R1)") { dir =>
    val f = File(dir, "2026-10-01.parquet")
    val v1 = Array.tabulate[Byte](10)(_.toByte)
    val v2 = Array.tabulate[Byte](25)(i => (100 + i).toByte)
    Files.write(f.toPath, v1)
    val snap = ArchiveSnapshot.open(f, stat(f))
    try
      assertEquals(snap.size, 10L)
      // Part 0 of the old version, then the file is replaced by a larger one.
      val first = ArchiveUpload.nextPart(snap, ArchiveUpload.resume(None, snap.generation), 4).get
      replace(f, v2)
      val (rest, done) = drain(snap, first.next, 4)
      assertEquals((first.data ++ rest).toSeq, v1.toSeq)
      assert(ArchiveUpload.complete(done, snap.size, 4))
      assertEquals(done.bytes, 10L)
    finally snap.close()
    // The new version is another generation: its upload starts from part 0.
    val snap2 = ArchiveSnapshot.open(f, stat(f))
    try
      assertNotEquals(snap2.generation, snap.generation)
      val p = ArchiveUpload.resume(Some(ArchiveUpload.Progress(snap.generation, 2, 8)), snap2.generation)
      assertEquals(p, ArchiveUpload.Progress(snap2.generation, 0, 0))
      val (all, done) = drain(snap2, p, 4)
      assertEquals(all.toSeq, v2.toSeq)
      assertEquals((done.part, done.bytes), (7L, 25L))
    finally snap2.close()
  }

  tmp.test("a file deleted while it is uploaded (merged into a month) is still read whole") { dir =>
    val f = File(dir, "2026-09-30.parquet")
    val v1 = Array.fill[Byte](9)(7)
    Files.write(f.toPath, v1)
    val snap = ArchiveSnapshot.open(f, stat(f))
    try
      f.delete()
      assertEquals(drain(snap, ArchiveUpload.resume(None, snap.generation), 4)._1.toSeq, v1.toSeq)
    finally snap.close()
    intercept[java.io.FileNotFoundException](ArchiveSnapshot.open(f, stat(f)))
  }

  tmp.test("progress resumes only within its generation, and completes only with all bytes of it") { dir =>
    val f = File(dir, "x.parquet")
    Files.write(f.toPath, Array.fill[Byte](10)(1))
    val snap = ArchiveSnapshot.open(f, stat(f))
    try
      val g = snap.generation
      assertEquals(ArchiveUpload.resume(Some(ArchiveUpload.Progress(g, 2, 8)), g), ArchiveUpload.Progress(g, 2, 8))
      val last = ArchiveUpload.nextPart(snap, ArchiveUpload.Progress(g, 2, 8), 4).get
      assert(last.last)
      assertEquals(last.data.length, 2)
      // Progress that does not add up (e.g. a part size changed) is refused, not completed.
      intercept[IllegalStateException](ArchiveUpload.nextPart(snap, ArchiveUpload.Progress(g, 2, 7), 4))
      intercept[IllegalArgumentException](ArchiveUpload.nextPart(snap, ArchiveUpload.Progress(g + 1, 0, 0), 4))
      assert(!ArchiveUpload.complete(ArchiveUpload.Progress(g, 3, 9), 10, 4))
      assertEquals(ArchiveUpload.nextPart(snap, ArchiveUpload.Progress(g, 3, 10), 4), None)
    finally snap.close()
    // An empty file has no parts and is complete at once.
    Files.write(f.toPath, Array.emptyByteArray)
    val empty = ArchiveSnapshot.open(f, stat(f))
    try
      assertEquals(ArchiveUpload.nextPart(empty, ArchiveUpload.resume(None, empty.generation)), None)
      assert(ArchiveUpload.complete(ArchiveUpload.resume(None, empty.generation), 0))
    finally empty.close()
  }

  tmp.test("a file handed over is sent again once it changed (a day archived in portions)") { dir =>
    val f = File(dir, "imu-2026-10-19.parquet")
    Files.write(f.toPath, Array.fill[Byte](100)(1))
    val first = ArchiveSnapshot.open(f, stat(f))
    val g1 = try first.generation finally first.close()
    // Handed over at g1: the mark is that generation.
    assert(ArchiveUpload.handedOver(ArchiveUpload.markOf(g1), g1))
    // The next portion rewrote the file: another generation, not handed over.
    replace(f, Array.fill[Byte](250)(2))
    val second = ArchiveSnapshot.open(f, stat(f))
    val g2 = try second.generation finally second.close()
    assertNotEquals(g1, g2)
    assert(!ArchiveUpload.handedOver(ArchiveUpload.markOf(g1), g2))
    assert(!ArchiveUpload.handedOver(0L, g2))
    // A mark of an earlier version is of no known generation: sent once more.
    assert(!ArchiveUpload.handedOver(ArchiveUpload.LegacyMark, g2))
    assert(ArchiveUpload.markOf(0L) != ArchiveUpload.LegacyMark && ArchiveUpload.markOf(1L) != ArchiveUpload.LegacyMark)
  }
