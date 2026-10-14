package net.sksat.ashidori.sync

import net.sksat.ashidori.Log
import ashidori.core.sync.*

/** Blob authentication v1.1 on the device: the cut-over state
  * ([[BlobAuthState]], kept in its own file `no_backup/durable/blobauth.json`,
  * per registration) and the making of the cut-over record C
  * (docs/design/browser-pairing-v1.md, "blob の認証（v1.1）").
  *
  * Writers seal ASHM ([[Uploader]]); the status download opens blobs through
  * [[BlobAuth.open]] and records here what it accepted ([[PeerStatus.pull]]).
  */
object AuthCutover:
  private lazy val store = BlobAuthStore(DurablePrefs("blobauth", legacy = Some(Set.empty)).store)

  /** Listing pages read to make C (1000 rows each). */
  private val MaxCutPages = 1000
  /** Listing pages read to check the digest of one pair, per sync run. */
  val MaxDigestPages = 200

  def state(reg: String): BlobAuthState = if reg.isEmpty then BlobAuthState() else store.state(reg)

  /** Applies `f` to the state of `reg` and returns once that is on disk;
    * throws [[java.io.IOException]] otherwise.
    */
  def update(reg: String)(f: BlobAuthState => BlobAuthState): BlobAuthState = store.update(reg)(f)

  /** The C this device names in its status (None while it knows none). */
  def knownCut: Option[CutRef] = state(SyncConfig.registration).cut

  /** The required state: ASH1 is accepted only as C allows. */
  def required: Boolean = state(SyncConfig.registration).required

  /** Forgets the state (unpairing); throws if that is not on disk. */
  def clear(): Unit =
    try store.clear()
    catch case e: java.io.IOException => throw java.io.IOException("認証の切り替えの状態を消去できませんでした", e)

  /** Makes sure the cut-over record C exists and this device is in the
    * required state, and returns C. Call before the first viewer (browser)
    * pairing is approved, and approve only after it returned. Blocking: call
    * off the main thread. Runs under [[SyncLock]]: waits up to a minute for
    * a running sync, then throws.
    *
    * First downloads the other devices' status, which records which of them
    * write ASHM. Throws [[CutoverNotReady]] (「すべての端末のアプリを更新してください」)
    * while a device that is neither revoked nor a viewer has not shown that;
    * other failures (network, storage) throw too, and it can be run again.
    * See [[Cutover.ensure]].
    */
  def ensureCutover(): CutRef =
    SyncLock.tryRun(LockWaitMs) {
      val run = SyncRun.current().getOrElse(throw IllegalStateException("同期が設定されていません"))
      try PeerStatus.pull(run)
      catch case e: Exception => Log.w("peer status before the cut-over failed", e)
      Cutover.ensure(Env(run))
    }.getOrElse(throw java.io.IOException("同期の処理が終わっていません。しばらくしてから再度お試しください"))

  /** How long [[ensureCutover]] waits for a running sync. */
  private val LockWaitMs = 60_000L

  /** Remembers the `authcut` rows of a listing page, to be fetched while C is not. Best effort. */
  def noteListed(reg: String, items: Seq[BatchMeta]): Unit =
    if items.exists(_.stream == CutoverRecord.Stream) then
      try update(reg)(_.listed(items))
      catch case e: Exception => Log.w("noting listed cut-over records failed", e)

  /** Fetches the C still to fetch (named by a status, or listed). Best effort. */
  def learn(run: SyncRun): Unit =
    if state(run.reg).toFetch.nonEmpty then
      try Cutover.learn(Source(run))
      catch
        case e: SyncRun.Stopped => throw e
        case e: Exception => Log.w("fetching the cut-over record failed", e)

  /** Every listing row from cursor 0 that `keep` keeps; None when the listing
    * does not end within `maxPages` pages. A page that does not move the
    * cursor on ends the walk (the rows so far are taken as the listing).
    */
  def listAll(run: SyncRun, maxPages: Int, keep: BatchMeta => Boolean = _ => true): Option[Vector[BatchMeta]] =
    val out = Vector.newBuilder[BatchMeta]
    var after = 0L
    var pages = 0
    var done = false
    while !done && pages < maxPages do
      run.checkStopped()
      pages += 1
      val (items, next) = run.client.listBatches(after, 1000)
      out ++= items.filter(keep)
      val to = items.lastOption.fold(after)(_.cursor)
      done = next.isEmpty || items.isEmpty || to <= after
      after = to
    Option.when(done)(out.result())

  private class Source(run: SyncRun) extends CutoverSource:
    def keys: BlobKeys = run.keys
    def state: BlobAuthState = AuthCutover.state(run.reg)
    def update(f: BlobAuthState => BlobAuthState): BlobAuthState = AuthCutover.update(run.reg)(f)
    def fetch(ref: CutRef): Array[Byte] =
      run.checkStopped()
      run.client.getBatch(ref.deviceId, CutoverRecord.Stream, ref.seq, CutoverRecord.MaxBlob)

  private final class Env(run: SyncRun) extends Source(run) with CutoverEnv:
    def selfId: String = run.deviceId
    def flush(): Boolean = Uploader.flushCutover(run)
    def listAll(): Vector[BatchMeta] =
      AuthCutover.listAll(run, MaxCutPages).getOrElse(throw IllegalStateException("the listing is too long to make the cut-over record"))
    def devices(): Vector[DeviceRow] =
      val js = run.client.devices()
      PeerStatus.saveDevices(js, run.reg)
      js.arr.toVector.map { d =>
        DeviceRow(d("id").str, d.obj.get("revoked").exists(_.boolOpt.contains(true)),
          d.obj.get("kind").flatMap(_.strOpt).contains("viewer"))
      }
    def enqueue(plain: Array[Byte]): CutRef = Uploader.enqueueCutover(run, plain)
