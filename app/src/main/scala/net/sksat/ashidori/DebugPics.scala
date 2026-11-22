package net.sksat.ashidori

import android.os.SystemClock
import ashidori.core.*

/** Debug builds (DebugReceiver): PICS advertisements put where the scan's
  * callback puts them ([[PicsIngest.onResult]]), so an emulator (no BLE)
  * exercises everything after the scanner (docs/design/pics.md 8.2).
  * Injected rows have `scan` = `debug`. Synthetic values only.
  */
object DebugPics:
  val DefaultAddr = "02:00:00:00:00:01"

  /** One advertisement: `hex` without the company ID; `at` its time (now if 0). */
  def inject(hex: String, rssi: Int, addr: String, company: Int, at: Long): String =
    PicsFormat.unhex(hex) match
      case Left(e) => s"PICS: $e"
      case Right(b) =>
        val wall = if at > 0 then at else System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wall)
        val ok = PicsIngest.onResult(PicsResult(wall, addr, company, b.toIndexedSeq, rssi, "debug"), elapsed)
        s"PICS ${if ok then "taken" else "not taken"}: ${b.length} bytes from $addr (company ${company.toHexString}) at $wall"

  private def s32(v: Int): Seq[Int] = Seq(24, 16, 8, 0).map(s => (v >> s) & 0xff)

  /** Synthetic packets of intersection `id` (bytes 0–1 as observed, message ID `msg`). */
  def packet(kind: Int, id: Long, rest: Seq[Int], msg: Int = 0xff): Array[Byte] =
    val head = Seq(0x58, 0x01, kind, msg, 0, 0) ++ Seq(24, 16, 8, 0).map(s => ((id >> s) & 0xff).toInt)
    (head ++ rest ++ Seq.fill(24)(0)).take(24).map(_.toByte).toArray

  /** A crossing, streamed in real time on its own thread: 40 m of approach
    * at walking pace, a wait of `waitS` at red, green, and walking away,
    * with fixes and motion windows; advertisements of a listed site (by
    * name, else the first in Tokyo) every 200 ms: its name (kind 0), its
    * position (kind 1) and the signals (kind 2, crosswalk 1 red then
    * green, crosswalk 2 the opposite). `fast`: written at once, back-dated.
    */
  def walk(siteName: Option[String], waitS: Int, fast: Boolean, daysAgo: Int = 0): String =
    val sites = PicsSitesData.index.sites
    val site = siteName.flatMap(n => sites.find(_.name.contains(n)))
      .orElse(sites.find(_.pref == "東京都")).orElse(sites.headOption)
    site match
      case None => "PICS_WALK: no site list"
      case Some(s) =>
        val id = 13_000_000L + (sites.indexOf(s) + 1) * 37L
        val approach = 30
        val after = 30
        val total = approach + waitS + after
        val start =
          if daysAgo > 0 then
            // Back-dated to that day, 08:00 local.
            java.time.LocalDate.now(AshidoriApp.zone).minusDays(daysAgo).atTime(8, 0).atZone(AshidoriApp.zone).toInstant.toEpochMilli
          else if fast then System.currentTimeMillis() - total * 1000L
          else System.currentTimeMillis()
        val fast1 = fast || daysAgo > 0
        val run: Runnable = () =>
          try
            val m = 111_320.0
            val name = s.name.getBytes("UTF-8").toSeq.map(_ & 0xff)
            val kind0 = packet(0, id, name.take(14))
            val kind1 = packet(1, id, s32(math.round(s.lat * 1e6).toInt) ++ s32(math.round(s.lon * 1e6).toInt))
            var fixes = Vector.empty[Fix]
            var windows = Vector.empty[MotionWindow]
            for sec <- 0 until total do
              val at = start + sec * 1000L
              if !fast1 then
                val wait = at - System.currentTimeMillis()
                if wait > 0 then Thread.sleep(wait)
              // South of the site: 40 m away, at it during the wait, then 40 m north.
              val d = if sec < approach then -40.0 + sec * 40.0 / approach
                else if sec < approach + waitS then 0.0 else (sec - approach - waitS) * 1.3
              val walking = sec < approach || sec >= approach + waitS
              fixes :+= Fix(at, s.lat + d / m, s.lon, 4f, speed = if walking then 1.3f else 0f, provider = "sim")
              if sec % 10 == 9 then
                windows :+= MotionWindow(at - 9000L, at + 1000L, if walking then 1.5f else 0.05f, 0f, if walking then 18 else 0)
              val green = sec >= approach + waitS
              for k <- 0 until 5 do
                val t = at + k * 200L
                val step = if green then 7 - ((sec - approach - waitS) * 8 / after).min(7)
                  else 7 - ((sec * 8) / (approach + waitS)).min(7)
                val a = if green then (step << 4) | 3 else (step << 4) | 1
                val b = if green then (step << 4) | 1 else (step << 4) | 3
                val data = k % 3 match
                  case 0 => kind0
                  case 1 => kind1
                  case _ => packet(2, id, Seq(a, b, 0xff, 0xff, 0xff, 0xff))
                inject(PicsFormat.hex(data), -55 - (math.abs(d) / 2).toInt, DefaultAddr, PicsFormat.CompanyId, t)
              if !fast1 && sec % 5 == 4 then
                LoggerWrites.fixes(fixes)
                fixes = Vector.empty
                if windows.nonEmpty then LoggerWrites.small(SmallRecord.Motion(windows))
                windows = Vector.empty
            LoggerWrites.fixes(fixes)
            if windows.nonEmpty then LoggerWrites.small(SmallRecord.Motion(windows))
            Log.i(s"PICS_WALK done: ${s.name} (${s.lat}, ${s.lon}) id $id wait $waitS s")
          catch case e: Throwable => Log.e("PICS_WALK failed", e)
        Thread(run, "ashidori-pics-walk").start()
        s"PICS_WALK: ${s.name} id $id wait ${waitS}s ${if fast then "back-dated" else "in real time"} (${total}s)"

  /** `n` advertisements of changing content from 300 senders, as fast as
    * they go in (a false sender flooding): the caps refuse most; logging
    * goes on.
    */
  def flood(n: Int): String =
    val t0 = System.currentTimeMillis()
    var taken = 0
    for i <- 0 until n do
      val data = packet(2, 13_000_000L + i % 7, Seq(i & 0xff, (i >> 8) & 0xff, 0xff, 0xff, 0xff, 0xff))
      val addr = f"02:00:00:00:${(i % 300) / 256}%02X:${(i % 300) % 256}%02X"
      val wall = System.currentTimeMillis()
      if PicsIngest.onResult(PicsResult(wall, addr, PicsFormat.CompanyId, data.toIndexedSeq, -70, "debug"), SystemClock.elapsedRealtime()) then
        taken += 1
    s"PICS flood: $n sent, $taken queued in ${System.currentTimeMillis() - t0} ms"

  /** One run of `hex` kept in an overflow file (.rec), as a batch SQLite refused. */
  def spill(hex: String, at: Long): String =
    PicsFormat.unhex(hex) match
      case Left(e) => s"PICS spill: $e"
      case Right(b) =>
        val t = if at > 0 then at else System.currentTimeMillis()
        val p = PicsFormat.packet(b)
        val run = PicsAdv(t, t + 1000, DefaultAddr, p.intersection, p.kind, p.msgId, p.msgId, 0, 5, -60, t, -70, PicsFormat.hex(b), "debug")
        SmallOverflow.spill(SmallRecord.PicsAdvs(Seq(run)))
        s"PICS spill: a run at $t in an overflow file"

  /** The plan's inputs and result, the registration and the counts, to the lifecycle log. */
  def state(): String =
    val p = PicsLive.plan
    val line = s"plan=${p.state}（${p.why}） scan=${p.scan.key} delay=${p.reportDelayMs} registered=${PicsLive.registered} " +
      s"input=${PicsLive.input} heard=${PicsLive.heard.values.map(h => s"${h.iid}:${h.label}:${h.peds.map(_.light.label).mkString("/")}").mkString(",")} " +
      s"refused=${PicsIngest.refused} acceptedToday=${PicsIngest.acceptedToday} failure=${PicsLive.failure} broken=${PicsLive.broken} " +
      s"enabled=${Settings.picsEnabled} permission=${PicsScanner.hasPermission(AshidoriApp.app)} bt=${PicsScanner.bluetoothOn(AshidoriApp.app)} " +
      s"passive=${PicsScanner.passiveScan} sites=${PicsSitesData.index.sites.size}"
    LifeLog.add(LifeKind.Pics, s"PICS_STATE: $line")
    line
