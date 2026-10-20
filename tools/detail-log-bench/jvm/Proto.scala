import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

/** Prototype of the journal's IMU frame encoder: preallocated primitive
  * buffers per series, a frame per series every `flushMs` of data, the time
  * as zigzag-varint deltas of elapsedRealtimeNanos, the wall time as a
  * varint residual, floats as raw IEEE bits per column, optionally Deflate
  * (zstd is not available on the JVM here). Measures only this encoding and
  * the write()/fsync calls: not sensor delivery, LoggerState updates, other
  * series or buffer growth. It writes no frame header or CRC, and with
  * `fsync` it syncs after every frame (not every 30 s). Times are wall-clock
  * (System.nanoTime).
  *
  *   Proto <dir with imu_t.bin imu_s.bin imu_v.bin> <flushMs> <deflate level, -1 = none> [fsync]
  */
object Proto:
  private val tm = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def alloc(): Long = tm.getThreadAllocatedBytes(Thread.currentThread().getId)

  final class Series(cap: Int, level: Int, ch: FileChannel, sync: Boolean):
    val ts = new Array[Long](cap); val wall = new Array[Long](cap)
    val x = new Array[Float](cap); val y = new Array[Float](cap); val z = new Array[Float](cap)
    var n = 0
    private val raw = new Array[Byte](cap * 40 + 64)
    private val out = new Array[Byte](cap * 40 + 1024)
    private val defl = java.util.zip.Deflater(math.max(level, 0))
    private val bb = ByteBuffer.wrap(out)
    var bytes = 0L; var frames = 0L
    private var p = 0
    private def varint(v0: Long): Unit =
      var v = v0
      while (v & ~0x7fL) != 0 do { raw(p) = ((v & 0x7f) | 0x80).toByte; p += 1; v >>>= 7 }
      raw(p) = v.toByte; p += 1
    private def zz(v: Long) = (v << 1) ^ (v >> 63)
    private def f32(a: Array[Float]): Unit =
      var i = 0
      while i < n do
        val b = java.lang.Float.floatToRawIntBits(a(i))
        raw(p) = b.toByte; raw(p + 1) = (b >> 8).toByte; raw(p + 2) = (b >> 16).toByte; raw(p + 3) = (b >> 24).toByte
        p += 4; i += 1
    def add(t: Long, w: Long, a: Float, b: Float, c: Float): Unit =
      ts(n) = t; wall(n) = w; x(n) = a; y(n) = b; z(n) = c; n += 1
    def flush(): Unit =
      if n > 0 then
        p = 0
        varint(n.toLong)
        var prev = 0L; var i = 0
        while i < n do { varint(zz(ts(i) - prev)); prev = ts(i); i += 1 }
        i = 0; var pr = 0L
        while i < n do { val r = wall(i) - ts(i) / 1_000_000; varint(zz(r - pr)); pr = r; i += 1 }
        f32(x); f32(y); f32(z)
        var m = 0
        if level < 0 then { System.arraycopy(raw, 0, out, 0, p); m = p }
        else
          defl.reset(); defl.setInput(raw, 0, p); defl.finish()
          while !defl.finished() do m += defl.deflate(out, m, out.length - m)
        bb.clear(); bb.limit(m)
        ch.write(bb)
        if sync then ch.force(false)
        bytes += m + 32; frames += 1; n = 0

  def main(args: Array[String]): Unit =
    val dir = args(0); val flushMs = args(1).toLong; val level = args(2).toInt; val sync = args.length > 3 && args(3) == "fsync"
    def load(f: String) = ByteBuffer.wrap(Files.readAllBytes(Paths.get(dir, f))).order(ByteOrder.LITTLE_ENDIAN)
    val tb = load("imu_t.bin"); val sb = load("imu_s.bin"); val vb = load("imu_v.bin")
    val n = sb.limit()
    val times = new Array[Long](2 * n); tb.asLongBuffer().get(times)
    val sens = new Array[Byte](n); sb.get(sens)
    val vals = new Array[Float](3 * n); vb.asFloatBuffer().get(vals)
    val ch = FileChannel.open(Paths.get(dir, "proto.bin"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    for round <- 0 until 2 do
      val ss = Array.fill(3)(Series(16384, level, ch, sync))
      val last = Array.fill(3)(Long.MinValue)
      val t0 = System.nanoTime(); val a0 = alloc()
      var i = 0
      while i < n do
        val s = sens(i); val se = ss(s); val ern = times(2 * i + 1)
        if last(s) == Long.MinValue then last(s) = ern
        if ern - last(s) >= flushMs * 1_000_000 || se.n == 16384 then { se.flush(); last(s) = ern }
        se.add(ern, times(2 * i), vals(3 * i), vals(3 * i + 1), vals(3 * i + 2))
        i += 1
      ss.foreach(_.flush())
      if round == 1 then
        println(f"frames every $flushMs ms, deflate $level${if sync then ", fsync per frame" else ""}: ${ss.map(_.bytes).sum / 1e6}%.1f MB, ${ss.map(_.frames).sum} frames, ${(System.nanoTime() - t0).toDouble / n}%.0f ns/sample, ${(alloc() - a0).toDouble / n}%.2f B/sample")
    ch.close()
