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
