package net.sksat.ashidori

import android.content.{BroadcastReceiver, Context, Intent}
import java.time.LocalDate

/** Debug builds only (declared in the debug manifest; ignored when the app
  * is not debuggable): test hooks that need no activity, for emulators
  * where starting one is unreliable.
  *
  *   am broadcast -n <pkg>/net.sksat.ashidori.DebugReceiver -a ashidori.debug.SYNTH --ei hours 5 [--ei startMinute 600] [--ei daysAgo 1] (or --ei minutes N)
  *   ... -a ashidori.debug.ODD [--ei daysAgo 1]       raw lines that do not convert, and late ones
  *   ... -a ashidori.debug.CORRUPT_TEST                a corrupt test database is kept (not deleted)
  *   ... -a ashidori.debug.SPILL --ei files 100        overflow files, as if the write queue had been full
  *   ... -a ashidori.debug.LOG --es mode max        start recording in a mode
  *   ... -a ashidori.debug.STOP                       stop recording
  *   ... -a ashidori.debug.MAINTAIN                   archive maintenance now (a job)
  *   ... -a ashidori.debug.MAINTAIN_NOW               the same outside JobScheduler (DebugMaintenanceService)
  *   ... -a ashidori.debug.CRASH                      crash the main process
  *   ... -a ashidori.debug.THREADS                    log every thread's stack
  *   ... -a ashidori.debug.BACKUP --es file name.zip  backup into the external files dir
  *   ... -a ashidori.debug.RESCUE --es file name.zip  rescue backup (files as they are) likewise
  *   ... -a ashidori.debug.QUARANTINE_EXPORT --es file name.zip  quarantined files likewise
  *   ... -a ashidori.debug.ENERGY [--ei daysAgo 2]    state spans (two recorders, one left open), pressure,
  *                                                    battery readings and fixes of a past day
  *   ... -a ashidori.debug.CLOSE_LATE [--ei daysAgo 2] a late record of the day's first recorder (closes its span again)
  *   ... -a ashidori.debug.DELETE --ei daysAgo 2 --ei fromMinute 600 --ei minutes 60   delete a range (Db.deleteRange)
  *   ... -a ashidori.debug.SPILL_SMALL                a small-row batch (pressure) in an overflow file
  *   ... -a ashidori.debug.IMPORT                     import overflow files now (raw lines and small rows)
  *   ... -a ashidori.debug.AUDIT_SIGNALS [--ei daysAgo 2]  per signal table: rows archived, in SQLite, distinct
  *   ... -a ashidori.debug.REPAIR                     「整理を修復」 (a job)
  *   ... -a ashidori.debug.REPAIR_NOW                 the same outside JobScheduler (DebugMaintenanceService)
  *   ... -a ashidori.debug.AUDIT [--ei daysAgo 1]     per raw table: rows archived, in SQLite, and distinct (no locks)
  *   ... -a ashidori.debug.OUTBOUND --es dest gsi --es when auto --es level district [--es off h1,h2]
  *                                                    set a destination's rule (as 「適用」 does)
  *   ... -a ashidori.debug.PAUSE --ez on true         「外部への送信を停止」
  *   ... -a ashidori.debug.OUTBOUND_STATE             log the rules, generations and the pause
  *   ... -a ashidori.debug.CORRUPT_POLICY [--es value '{"v":9}']  store an unreadable policy (read at the next start)
  *   ... -a ashidori.debug.GEOCODE --ef lat 35.68 --ef lon 139.76   area and region names (the platform geocoder if allowed)
  *   ... -a ashidori.debug.VENUES --ef lat 35.68 --ef lon 139.76    the online venue search, as its button does
  *   ... -a ashidori.debug.CUSTOM_TILES --es url http://10.0.2.2:8799/{z}/{x}/{y}.png   the custom map's URL (none removes it)
  *   ... -a ashidori.debug.DATA_URL --es url http://10.0.2.2:8799   the data's debug source (none removes it)
  *   ... -a ashidori.debug.MAP_STYLE --es key gsi-std    the chosen base map (no consent)
  *   ... -a ashidori.debug.DETAIL --es key gsi-std       「オンライン地図で表示」 on every open map (none: back)
  *   ... -a ashidori.debug.DATA_DELETE --es kind venues    delete downloaded data (a download then sends)
  *   ... -a ashidori.debug.PAUSE_AT --es at checked:write [--ei stallMs 5000] [--ez pause false]   pause right after that check of the next request
  *   ... -a ashidori.debug.MAP_DELAY --ei ms 15000            each map request waits after the guard (renders held)
  *   ... -a ashidori.debug.STYLE_FAIL --ei n 3              the next style fetches fail before sending
  *   ... -a ashidori.debug.STYLE_RETRY                      as a network becoming available for the style fetches
  *   ... -a ashidori.debug.FETCH --es dest data --es url URL         one request through Outbound
  *   ... -a ashidori.debug.DATA_START [--es kind basemap|venues]    the download, as its button does
  *   ... -a ashidori.debug.CAMERA --ef lat 35.68 --ef lon 139.76 --ef zoom 17   move every open map
  *   ... -a ashidori.debug.VISITS --es op rebuild     「訪れた日」: the index deleted and built whole (timed);
  *                                                    `--es op reset`: only deleted; `fail`, `unfail`: queries
  *                                                    fail (`unfail` also ends `failbuild`: builds fail); `stale`: marked of unknown freshness
  *   ... -a ashidori.debug.VISITS --es op check --es at "35.63,139.79;35.68,139.76"  the index's answer for
  *                                                    each venue (timed) against every day built and filtered
  *
  * A commit that recovery cannot finish: create `files/maintenance/stuck-delete-tmp`
  * (or `stuck-corrupt-tmp`) with run-as, then archive (see ArchiveStore.debugBreakRest).
  */
class DebugReceiver extends BroadcastReceiver:
  override def onReceive(c: Context, intent: Intent): Unit =
    if (c.getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0 then return
    intent.getAction match
      case "ashidori.debug.SYNTH" =>
        val h = if intent.hasExtra("minutes") then intent.getIntExtra("minutes", 0) / 60.0 else intent.getIntExtra("hours", 5).toDouble
        val at = intent.getIntExtra("startMinute", 600)
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() => { DebugData.insertRaw(LocalDate.now(AshidoriApp.zone).minusDays(ago), h, at); () }, "ashidori-synth").start()
      case "ashidori.debug.ODD" =>
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() => Log.i(s"odd raw lines: ${DebugData.insertOdd(LocalDate.now(AshidoriApp.zone).minusDays(ago))}"), "ashidori-odd").start()
      case "ashidori.debug.SPILL" =>
        // Overflow files as if the queue had been full: `files` of 2000 lines.
        val n = intent.getIntExtra("files", 100)
        Thread(() =>
          val t0 = System.currentTimeMillis()
          for k <- 0 until n do
            LoggerWrites.Overflow.spill((0 until 2000).map(i => (t0 + k * 2000L + i, s"Accel,${t0 + k * 2000L + i},$i,0.1,0.2,9.8")))
          Log.i(s"spilled $n overflow files")
        , "ashidori-spill").start()
      case "ashidori.debug.CORRUPT_TEST" =>
        Thread(() => Log.i(DebugData.corruptionTest(c)), "ashidori-corrupt-test").start()
      case "ashidori.debug.LOG" =>
        Settings.onboarded = true
        Settings.mode = ashidori.core.LoggingMode.fromKey(Option(intent.getStringExtra("mode")).getOrElse("max"))
        Settings.loggingEnabled = true
        LoggerService.send(c, LoggerService.ActionStart)
        LoggerService.send(c, LoggerService.ActionRefresh)
      case "ashidori.debug.STOP" =>
        Settings.loggingEnabled = false
        LoggerService.send(c, LoggerService.ActionStop)
      case "ashidori.debug.MAINTAIN" => Maintenance.runNow(c)
      case "ashidori.debug.MAINTAIN_NOW" => c.startService(Intent(c, classOf[DebugMaintenanceService]))
      case "ashidori.debug.CRASH" =>
        // An uncaught exception in the main process (tests the alerts).
        android.os.Handler(android.os.Looper.getMainLooper).post(() => throw IllegalStateException("debug crash"))
      case "ashidori.debug.THREADS" =>
        import scala.jdk.CollectionConverters.*
        for (t, st) <- Thread.getAllStackTraces.asScala do
          Log.i(s"thread ${t.getName} ${t.getState}\n  " + st.take(12).mkString("\n  "))
      case "ashidori.debug.BACKUP" =>
        val f = java.io.File(c.getExternalFilesDir(null), Option(intent.getStringExtra("file")).getOrElse(Backup.defaultName()))
        Thread(() =>
          try
            val n = Backup.write(c, android.net.Uri.fromFile(f), (d, t) => Log.i(s"backup $d / $t"))
            Log.i(s"backup done: ${n.bytes} bytes to $f, missing ${n.missing.size}")
          catch case e: Throwable =>
            // As the screen does: an output that did not complete is not kept.
            f.delete()
            Log.e("backup failed", e)
        , "ashidori-backup").start()
      case "ashidori.debug.VACUUM_CANCEL" =>
        // The backup's database copy, cancelled after `ms` (as its watcher does
        // when the room is gone): shows that VACUUM INTO stops part-way.
        val ms = intent.getIntExtra("ms", 50).toLong
        val run: Runnable = () =>
          val dest = java.io.File(c.getNoBackupFilesDir, "vacuum-test.db")
          val sig = android.os.CancellationSignal()
          val cancel: Runnable = () => { Thread.sleep(ms); sig.cancel() }
          val t0 = System.currentTimeMillis()
          Thread(cancel).start()
          val r = scala.util.Try(AshidoriApp.db.snapshotTo(dest, Some(sig)))
          Log.i(s"vacuum cancel after $ms ms: ${r.fold(e => e.getClass.getSimpleName, _ => "completed")} in ${System.currentTimeMillis() - t0} ms, copy ${dest.length} bytes")
          dest.delete()
        Thread(run, "ashidori-vacuum-test").start()
      case "ashidori.debug.HOLD_READ" =>
        // A long-running reader for `seconds`: a query that never ends on its
        // own, cancelled then. Its snapshot keeps SQLite from reusing the WAL.
        val s = intent.getIntExtra("seconds", 60).toLong
        val run: Runnable = () =>
          val ro = android.database.sqlite.SQLiteDatabase.openDatabase(AshidoriApp.db.getReadableDatabase.getPath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY, Db.KeepOnCorruption)
          val sig = android.os.CancellationSignal()
          val stop: Runnable = () => { Thread.sleep(s * 1000); sig.cancel() }
          Thread(stop).start()
          val t0 = System.currentTimeMillis()
          Log.i(s"hold read: started for $s s")
          val r = scala.util.Try { val c = ro.rawQuery("SELECT COUNT(*) FROM raw_lines a, raw_lines b, raw_lines c", null, sig); try c.moveToFirst() finally c.close() }
          ro.close()
          Log.i(s"hold read: ended after ${System.currentTimeMillis() - t0} ms (${r.fold(_.getClass.getSimpleName, _ => "completed")})")
        Thread(run, "ashidori-hold-read").start()
      case "ashidori.debug.SCATTER_DELETE" =>
        // Deletes every `every`-th raw line (by rowid) of a day, through the
        // bulk delete of archived rows (its guard and batch sizing), each batch logged.
        val d = LocalDate.now(AshidoriApp.zone).minusDays(intent.getIntExtra("daysAgo", 8))
        val every = intent.getIntExtra("every", 7)
        Thread(() =>
          Db.debugBulk = true
          try
            val ids = Db.debugScattered(AshidoriApp.db, AshidoriApp.archive.startOfDay(d), AshidoriApp.archive.startOfDay(d.plusDays(1)), every)
            Log.i(s"scatter delete: ${ids.size} rows of $d")
            val r = scala.util.Try(AshidoriApp.db.deleteRawById(ids))
            Log.i(s"scatter delete: ${r.fold(e => e.getClass.getSimpleName, n => s"$n deleted")}")
          finally Db.debugBulk = false
        , "ashidori-scatter").start()
      case "ashidori.debug.STORAGE" =>
        // The free space, L (main process) and H, and what the reservations will still write.
        AshidoriApp.io.execute(() => Log.i(s"storage: free ${Storage.free()}, L ${Storage.reserve()}, H ${ashidori.core.Headroom.H}, outstanding ${Storage.budget.outstanding}"))
      case "ashidori.debug.RESCUE" =>
        val f = java.io.File(c.getExternalFilesDir(null), Option(intent.getStringExtra("file")).getOrElse("rescue.zip"))
        Thread(() =>
          try
            val n = Backup.writeRescue(c, android.net.Uri.fromFile(f), (d, t) => Log.i(s"rescue $d / $t"))
            Log.i(s"rescue done: ${n.bytes} bytes to $f, missing ${n.missing.size}")
          catch case e: Throwable => Log.e("rescue failed", e)
        , "ashidori-rescue").start()
      case "ashidori.debug.QUARANTINE_EXPORT" =>
        val f = java.io.File(c.getExternalFilesDir(null), Option(intent.getStringExtra("file")).getOrElse("quarantine.zip"))
        Thread(() =>
          try
            val n = Backup.writeQuarantine(c, android.net.Uri.fromFile(f), (d, t) => ())
            Log.i(s"quarantine export done: ${n.bytes} bytes to $f")
          catch case e: Throwable => Log.e("quarantine export failed", e)
        , "ashidori-quarantine-export").start()
      case "ashidori.debug.READ" =>
        // What the app's readers show of a day (through ArchiveStore.reading).
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() =>
          val d = LocalDate.now(AshidoriApp.zone).minusDays(ago)
          val (a, b) = (AshidoriApp.archive.startOfDay(d), AshidoriApp.archive.startOfDay(d.plusDays(1)))
          val fixes = AshidoriApp.db.fixes(a, b)
          val pending = AshidoriApp.archive.commitPending
          Log.i(s"read $d fixes=${fixes.size} distinct=${fixes.map(f => (f.time, f.lat, f.lon)).distinct.size} pending=$pending")
        , "ashidori-read").start()
      case "ashidori.debug.ENERGY" =>
        val d = LocalDate.now(AshidoriApp.zone).minusDays(intent.getIntExtra("daysAgo", 2))
        Thread(() => Log.i(s"energy seed: ${DebugData.insertSignals(d)}"), "ashidori-energy").start()
      case "ashidori.debug.CLOSE_LATE" =>
        val d = LocalDate.now(AshidoriApp.zone).minusDays(intent.getIntExtra("daysAgo", 2))
        Thread(() => Log.i(s"late close: ${DebugData.closeLate(d)}"), "ashidori-late").start()
      case "ashidori.debug.DELETE" =>
        val d = LocalDate.now(AshidoriApp.zone).minusDays(intent.getIntExtra("daysAgo", 2))
        val from = d.atStartOfDay(AshidoriApp.zone).toInstant.toEpochMilli + intent.getIntExtra("fromMinute", 600) * 60_000L
        val to = from + intent.getIntExtra("minutes", 60) * 60_000L
        LoggerWrites.deleteRange(from, to)(r => Log.i(s"deleted [$from, $to): $r"))
      case "ashidori.debug.SPILL_SMALL" =>
        Thread(() =>
          val t = System.currentTimeMillis()
          SmallOverflow.spill(ashidori.core.SmallRecord.Pressure((0 until 10).map(i => ashidori.core.PressureSample(t + i * 5000L, 1000f))))
          Log.i("spilled a small-row batch")
        , "ashidori-spill-small").start()
      case "ashidori.debug.IMPORT" => LoggerWrites.markStart()
      case "ashidori.debug.RESERVE" =>
        // The emergency reserve and state (plan C stage 2).
        AshidoriApp.io.execute(() =>
          val e = Storage.emergency
          Log.i(s"reserve: status ${e.status} gen ${e.generation} active ${e.active} largeWork ${e.largeWorkAllowed} " +
            s"file ${Storage.ReserveFiles.file.length} free ${Storage.free()} counts ${LoggerWrites.counts()}"))
      case "ashidori.debug.BATCHES" =>
        // Batches of the logger with unique ids for the emergency path's tests:
        // `n` raw batches of `lines` lines (`Dbg,<run>,<k>,<i>,<pad>`) and a
        // pressure sample each (time base + k s, hPa 1234.5), every `ms` ms,
        // through LoggerWrites like the logger's own. AUDIT_BATCHES accounts them.
        val run = Option(intent.getStringExtra("run")).getOrElse("r")
        val n = intent.getIntExtra("n", 100)
        val lines = intent.getIntExtra("lines", 200)
        val ms = intent.getIntExtra("ms", 200).toLong
        val pad = "x" * intent.getIntExtra("pad", 40)
        val base = intent.getLongExtra("base", System.currentTimeMillis())
        Thread(() =>
          Log.i(s"batches $run: base $base n $n lines $lines")
          for k <- 0 until n do
            LoggerWrites.rawLines((0 until lines).map(i => (base + k * 1000L + i, s"Dbg,$run,$k,$i,$pad")))
            LoggerWrites.small(ashidori.core.SmallRecord.Pressure(Seq(ashidori.core.PressureSample(base + k * 1000L, 1234.5f))))
            Thread.sleep(ms)
          Log.i(s"batches $run: submitted $n")
        , "ashidori-batches").start()
      case "ashidori.debug.AUDIT_BATCHES" =>
        val run = Option(intent.getStringExtra("run")).getOrElse("r")
        val n = intent.getIntExtra("n", 100)
        val lines = intent.getIntExtra("lines", 200)
        val base = intent.getLongExtra("base", 0L)
        AshidoriApp.io.execute(() => Log.i(s"audit batches $run: ${DebugData.auditBatches(run, n, lines, base)}"))
      case "ashidori.debug.AUDIT_SIGNALS" =>
        val d = LocalDate.now(AshidoriApp.zone).minusDays(intent.getIntExtra("daysAgo", 2))
        Thread(() => DebugData.auditSignals(d).foreach(l => Log.i(s"audit $l")), "ashidori-audit-signals").start()
      case "ashidori.debug.REPAIR" => Maintenance.repairNow(c)
      case "ashidori.debug.REPAIR_NOW" => c.startService(Intent(c, classOf[DebugMaintenanceService]).putExtra("repair", true))
      case "ashidori.debug.AUDIT" =>
        val ago = intent.getIntExtra("daysAgo", 1)
        Thread(() => DebugData.audit(LocalDate.now(AshidoriApp.zone).minusDays(ago)).foreach(l => Log.i(s"audit $l")), "ashidori-audit").start()
      case "ashidori.debug.OUTBOUND" =>
        import ashidori.core.*
        val rule = for
          d <- Dest.fromKey(Option(intent.getStringExtra("dest")).getOrElse(""))
          w <- When.fromKey(Option(intent.getStringExtra("when")).getOrElse(""))
          l <- Level.fromKey(Option(intent.getStringExtra("level")).getOrElse(Settings.gate.rule(d).level.key))
        yield d -> Rule(w, l, Option(intent.getStringExtra("off")).fold(Set.empty[String])(_.split(',').map(_.trim).filter(_.nonEmpty).toSet))
        rule match
          case Some((d, r)) if Policy.valid(d, r) =>
            Net.change(Settings.gate.changeRule(d, r))(
              returned = c => Log.i(s"outbound rule ${d.key}: $r -> $c"),
              complete = () => Log.i(s"outbound rule ${d.key}: complete"))
          case other => Log.w(s"outbound: not a rule: $other")
      case "ashidori.debug.PAUSE" =>
        val on = intent.getBooleanExtra("on", true)
        val t0 = System.nanoTime()
        def ms = (System.nanoTime() - t0) / 1_000_000
        Net.change(Settings.gate.changePaused(on))(
          returned = c => Log.i(s"outbound pause $on -> $c after $ms ms"),
          complete = () => Log.i(s"outbound pause $on complete after $ms ms"))
      case "ashidori.debug.OUTBOUND_STATE" =>
        val st = Settings.gate.snapshot
        Log.i(s"outbound state paused=${st.paused} problem=${st.problem} policy=${ashidori.core.Policy.encode(st.policy)} " +
          s"consents=${ashidori.core.Consents.encode(st.consents)}")
      case "ashidori.debug.CORRUPT_POLICY" =>
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
          .putString(ashidori.core.ExternalSettings.PolicyKey, Option(intent.getStringExtra("value")).getOrElse("{corrupt")).commit()
        Log.i("outbound policy corrupted (read at the next start)")
      case "ashidori.debug.GEOCODE" =>
        val (lat, lon) = (intent.getFloatExtra("lat", 35.681f).toDouble, intent.getFloatExtra("lon", 139.767f).toDouble)
        Thread(() => Log.i(s"geocode area=${Geocoding.area(lat, lon)} region=${Geocoding.region(lat, lon)}"), "ashidori-geocode").start()
      case "ashidori.debug.VENUES" =>
        val p = ashidori.core.LatLng(intent.getFloatExtra("lat", 35.681f).toDouble, intent.getFloatExtra("lon", 139.767f).toDouble)
        ui.VenueSearch.latest(p)(r => Log.i(s"venues ${r.map(a => s"${a.venues.size} from ${a.server}")}"))
      case "ashidori.debug.CAMERA" =>
        val p = ashidori.core.LatLng(intent.getFloatExtra("lat", 35.681f).toDouble, intent.getFloatExtra("lon", 139.767f).toDouble)
        val z = intent.getFloatExtra("zoom", 14f).toDouble
        AshidoriApp.app.getMainExecutor.execute { () =>
          ui.TrackMap.all.foreach(_.moveTo(p, z))
          ui.RegionSelectScreen.shown.foreach(_.moveTo(p, z))
        }
      case "ashidori.debug.CUSTOM_TILES" =>
        // An empty URL removes it (as before the user set one).
        val url = Option(intent.getStringExtra("url")).filterNot(_ == "none").getOrElse("")
        if url.isEmpty then c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("customTileUrl").commit()
        else ui.MapStyle.setCustom(url, "")
        Log.i(s"custom tiles: ${ui.MapStyle.custom._1}")
        AshidoriApp.app.getMainExecutor.execute(() => ui.TrackMap.all.foreach(_.refreshBaseMap(force = true)))
      case "ashidori.debug.MAP_STYLE" =>
        // The chosen base map, as the picker stores it (no consent: for automatic maps).
        ui.MapStyle.values.find(_.key == intent.getStringExtra("key")).foreach { ms =>
          ui.MapStyle.current = ms
          AshidoriApp.app.getMainExecutor.execute(() => ui.TrackMap.all.foreach(_.refreshBaseMap(force = true)))
        }
        Log.i(s"map style: ${ui.MapStyle.current.key}")
      case "ashidori.debug.DETAIL" =>
        // As 「オンライン地図で表示」 on every open map, for the map `key`.
        val ms = ui.MapStyle.values.find(_.key == intent.getStringExtra("key"))
        AshidoriApp.app.getMainExecutor.execute(() => ui.TrackMap.all.foreach(_.setDetailed(ms.nonEmpty, ms)))
      case "ashidori.debug.DATA_URL" =>
        val dl = if intent.getStringExtra("kind") == "venues" then VenueDownload else BasemapDownload
        dl.debugUrl = Option(intent.getStringExtra("url")).filterNot(_ == "none").getOrElse("")
        Log.i(s"data debug url (${dl.WorkName}): ${dl.debugUrl}")
      case "ashidori.debug.DATA_DELETE" =>
        // Deletes the downloaded data, so that a download sends again.
        val dl = if intent.getStringExtra("kind") == "venues" then VenueDownload else BasemapDownload
        dl.delete(c)
        Log.i(s"data deleted (${dl.WorkName})")
      case "ashidori.debug.PAUSE_AT" =>
        // The next Outbound request: the pause starts right after the check of
        // the I/O call `at` (e.g. checked:write), while that call is held for
        // `stallMs` (as a call stuck in the system; longer than the change's
        // deadline gives 「停止中…」 first).
        val at = Option(intent.getStringExtra("at")).getOrElse("checked:write")
        val stallMs = intent.getIntExtra("stallMs", 300).toLong
        // false: only hold the call (the pause then comes from the screen).
        val pause = intent.getBooleanExtra("pause", true)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        def now = f"${System.nanoTime() / 1e6}%.1f"
        Net.outbound.step = st =>
          if fired.get && st == "done:" + at.stripPrefix("checked:") then Net.log(s"step $st t=$now")
          if st == at && fired.compareAndSet(false, true) then
            Net.log(s"step $st t=$now: the call held for $stallMs ms${if pause then ", pausing" else ""}")
            if pause then
              Net.change(Settings.gate.changePaused(true))(
                returned = c => Net.log(s"step pause returned t=$now: $c"),
                complete = () => Net.log(s"step pause complete t=$now"))
            Thread.sleep(stallMs)
            Net.log(s"step $st t=$now: the call would go on")
      case "ashidori.debug.MAP_DELAY" =>
        // Each request of the map library waits this long after the guard (0: none).
        Net.debugMapDelayMs = intent.getIntExtra("ms", 0).toLong
        Log.i(s"map delay: ${Net.debugMapDelayMs} ms")
      case "ashidori.debug.STYLE_FAIL" =>
        // The next style fetches fail before they send (as an unreachable server).
        ui.OnlineStyles.debugFailures = intent.getIntExtra("n", 1)
        Log.i(s"style fetches to fail: ${ui.OnlineStyles.debugFailures}")
      case "ashidori.debug.STYLE_RETRY" =>
        // As a network becoming available: the waiting style fetches are retried.
        ui.OnlineStyles.retryNow()
        Log.i("style retry asked")
      case "ashidori.debug.FETCH" =>
        // One request through Outbound under a permit of `dest` (a redirect test).
        val dest = ashidori.core.Dest.fromKey(Option(intent.getStringExtra("dest")).getOrElse("data")).get
        val url = intent.getStringExtra("url")
        Thread(() =>
          val r = scala.util.Try(Settings.gate.permit(dest, ashidori.core.Mode.OnAction).map(p => Net.outbound.small(p, url)))
          Log.i(s"fetch ${dest.key}: ${r.map(_.map(_.map(_.length)))}")
        , "ashidori-fetch").start()
      case "ashidori.debug.DATA_START" =>
        val dl = if intent.getStringExtra("kind") == "venues" then VenueDownload else BasemapDownload
        dl.start(c, allowMetered = true)
        Log.i(s"data start ${dl.WorkName} allowed=${dl.allowed}")
      case "ashidori.debug.REGIONS_SOURCE" =>
        // The region packs from a local server (adb reverse) and its catalog's pin:
        // --es url http://127.0.0.1:8799 --es version V --el bytes N --es sha256 H ("none": the bundled pin).
        val url = Option(intent.getStringExtra("url")).filterNot(_ == "none").getOrElse("")
        val version = Option(intent.getStringExtra("version")).getOrElse("")
        val pin = if url.isEmpty then "" else ujson.write(ujson.Obj("format" -> ashidori.core.RegionPin.Format,
          "version" -> version, "file" -> ashidori.core.RegionPin.catalogName(version),
          "bytes" -> intent.getLongExtra("bytes", 0L).toDouble, "sha256" -> Option(intent.getStringExtra("sha256")).getOrElse("")))
        Regions.setDebugSource(url, pin)
        Log.i(s"regions source: ${Regions.debugUrl} pin ${Regions.pin}")
      case "ashidori.debug.REGIONS_FREE" | "ashidori.debug.STORAGE_FREE" =>
        // Pretends this many free bytes for the storage guard (0: the real free space).
        val b = intent.getLongExtra("bytes", 0L)
        Storage.debugFree = Option.when(b > 0)(b)
        AshidoriApp.io.execute(() => Log.i(s"regions free: ${Storage.free()} reserve ${Storage.reserve()}"))
      case "ashidori.debug.REGIONS_ADD" =>
        // A region of --es cells "8-227-100,8-228-100" (--es name), planned and started as the screen does.
        val cells = Option(intent.getStringExtra("cells")).toSeq.flatMap(_.split(",")).flatMap(ashidori.core.Cell.parse)
        val name = Option(intent.getStringExtra("name")).getOrElse("地域")
        AshidoriApp.io.execute { () =>
          val r = scala.util.Try {
            val p = Settings.gate.permit(ashidori.core.Dest.Data, ashidori.core.Mode.OnAction).get
            val cat = Regions.fetchCatalog(p)
            Regions.add(name, cells, cat)
          }
          Log.i(s"regions add: $r")
          AshidoriApp.app.getMainExecutor.execute(() => RegionDownload.start(c))
        }
      case "ashidori.debug.REGIONS_DELETE" =>
        // As 削除 in the list: --es id r1.
        val id = Option(intent.getStringExtra("id")).getOrElse("")
        AshidoriApp.io.execute(() => Regions.delete(id))
        Log.i(s"regions delete $id")
      case "ashidori.debug.REGIONS_STATE" =>
        AshidoriApp.io.execute { () =>
          val s = Regions.state
          Log.i(s"regions state: ${ashidori.core.RegionState.encode(s)}")
          Log.i(s"regions scan: ${Regions.store.scan()} holders ${Regions.use.holders} drawable ${Regions.placed.map(_._1)}")
          Log.i(s"regions budget: free ${Storage.free()} reserve ${Storage.reserve()} available ${Storage.budget.available}")
        }
      case "ashidori.debug.VISITS" =>
        val op = Option(intent.getStringExtra("op")).getOrElse("check")
        val at = Option(intent.getStringExtra("at")).toSeq.flatMap(_.split(";")).flatMap(s =>
          scala.util.Try { val Array(a, b) = s.split(",").map(_.trim.toDouble); ashidori.core.LatLng(a, b) }.toOption)
        VisitDays.exec.execute(() => DebugData.visits(op, at))
      case other => Log.w(s"debug receiver: unknown action $other")

/** Debug builds only (declared in the debug manifest, in the `:maintenance`
  * process): runs archive maintenance at once, outside JobScheduler and its
  * quota, for tests on emulators. `... -a ashidori.debug.MAINTAIN_NOW`
  */
class DebugMaintenanceService extends android.app.Service:
  override def onBind(i: Intent): android.os.IBinder = null
  override def onStartCommand(i: Intent, flags: Int, id: Int): Int =
    if (getApplicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 then
      val repair = i != null && i.getBooleanExtra("repair", false)
      Thread(() =>
        try
          if repair then Maintenance.repair(manual = true)
          // Again while work is left, but not after a stop for the room (as the job's backoff would).
          else while Maintenance.run(manual = true, () => false) && !Maintenance.stoppedLow do ()
        catch case e: Throwable => Log.w("debug maintenance failed", e)
        finally stopSelf(id)
      , "ashidori-maintenance").start()
    android.app.Service.START_NOT_STICKY
