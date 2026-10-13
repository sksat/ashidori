package ashidori.core

import java.io.{File, FileInputStream, FileOutputStream, IOException, InputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest
import scala.util.Try

/** One published version of the offline basemap archive
  * (tools/basemap/package.sh): the app pins it, and whatever it downloads,
  * from whichever source, must have exactly this size and sha256. So neither
  * GitHub nor the sync server has to be trusted.
  */
final case class BasemapPin(version: String, file: String, bytes: Long, sha256: String)

object BasemapPin:
  val Format = "ashidori basemap archive v1"
  private val VersionRe = "[0-9A-Za-z][0-9A-Za-z._-]{0,63}".r
  private val ShaRe = "[0-9a-f]{64}".r
  /** Larger than any archive we would ship; guards against a bad descriptor. */
  val MaxBytes: Long = 2L * 1024 * 1024 * 1024

  def validVersion(v: String): Boolean = VersionRe.matches(v) && !v.contains("..")
  def archiveName(version: String): String = s"ashidori-basemap-$version.zip"
  def descriptorName(version: String): String = s"ashidori-basemap-$version.json"

  /** Parses the descriptor (`ashidori-basemap-<version>.json`). */
  def parse(json: String): Either[String, BasemapPin] =
    for
      js <- Try(ujson.read(json)).toEither.left.map(_ => "not JSON")
      obj <- js.objOpt.toRight("not an object")
      _ <- Either.cond(obj.get("format").flatMap(_.strOpt).contains(Format), (), "unknown format")
      version <- obj.get("version").flatMap(_.strOpt).filter(validVersion).toRight("invalid version")
      file <- obj.get("file").flatMap(_.strOpt).filter(_ == archiveName(version)).toRight("invalid file name")
      bytes <- obj.get("bytes").flatMap(_.numOpt).filter(n => n > 0 && n <= MaxBytes && n == n.floor)
        .map(_.toLong).toRight("invalid size")
      sha <- obj.get("sha256").flatMap(_.strOpt).filter(ShaRe.matches).toRight("invalid sha256")
    yield BasemapPin(version, file, bytes, sha)

/** Where the archive can come from. */
enum BasemapSource(val key: String, val label: String):
  /** The sync server this device is paired with (`GET /basemap/<version>/<file>`). */
  case Server extends BasemapSource("server", "サーバ")
  /** GitHub Releases of the repository, tag `basemap-<version>`. */
  case GitHub extends BasemapSource("github", "GitHub")
  /** Debug builds: a URL set for development. */
  case DebugUrl extends BasemapSource("debug-url", "開発用 URL")
  /** Debug builds: a file pushed to the device. */
  case LocalFile extends BasemapSource("file", "端末内のファイル")

object BasemapSource:
  val Repository = "sksat/ashidori"

  def fromKey(k: String): Option[BasemapSource] = values.find(_.key == k)

  def githubUrl(pin: BasemapPin): String =
    s"https://github.com/$Repository/releases/download/basemap-${pin.version}/${pin.file}"

  /** `base` is the sync server's URL, as paired. */
  def serverUrl(base: String, pin: BasemapPin): String =
    s"${base.stripSuffix("/")}/basemap/${pin.version}/${pin.file}"

  /** `base` is a directory URL holding the archive. */
  def debugUrl(base: String, pin: BasemapPin): String = s"${base.stripSuffix("/")}/${pin.file}"

  /** Basemap versions the sync server's `GET /v1/info` lists; none for a
    * server without the field (older servers) or a malformed answer.
    */
  def serverVersions(info: String): Set[String] =
    Try(ujson.read(info).obj.get("basemap").map(_.arr.flatMap(_.strOpt).filter(BasemapPin.validVersion).toSet))
      .toOption.flatten.getOrElse(Set.empty)

  /** Sources to try, in order. With sync configured, the server comes first
    * if it lists the pinned version, and each is the other's fallback.
    */
  def order(syncConfigured: Boolean, serverHasVersion: Boolean): Seq[BasemapSource] =
    if !syncConfigured then Seq(GitHub)
    else if serverHasVersion then Seq(Server, GitHub)
    else Seq(GitHub, Server)

/** A plain GET with an optional byte range; the app implements it with
  * HttpURLConnection, tests with a fake.
  */
trait RangeHttp:
  /** GET `url`, from byte `from` (`Range: bytes=<from>-` when positive). */
  def get(url: String, from: Long): RangeHttp.Response

object RangeHttp:
  trait Response extends AutoCloseable:
    def code: Int
    def header(name: String): Option[String]
    def body: InputStream

/** Downloads into a partial file that later runs continue (HTTP Range). The
  * result is checked against the pin afterwards ([[Sha256]]); a partial file
  * with wrong bytes fails that check and is discarded.
  */
object ResumableDownload:
  final class Cancelled extends IOException("cancelled")
  /** The server answered something a retry of the same request will not fix. */
  final class Refused(msg: String) extends IOException(msg)

  enum Plan:
    /** 206 from where the partial file ends: append. */
    case Append
    /** 200: the whole file; write it from the start. */
    case Restart
    /** 416 and the partial file already has every byte. */
    case Complete
    /** 416 otherwise: drop the partial file and ask again from 0. */
    case Discard
    case Fail(reason: String)

  /** `Content-Range: bytes <first>-<last>/<size or *>`. */
  def parseContentRange(v: String): Option[(Long, Long, Option[Long])] =
    val Re = """\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*""".r
    v match
      case Re(a, b, c) => Try((a.toLong, b.toLong, if c == "*" then None else Some(c.toLong))).toOption
      case _ => None

  /** What to do with a response to a request from byte `have` of `total`. */
  def plan(have: Long, total: Long, code: Int, contentRange: Option[String]): Plan =
    code match
      case 206 =>
        contentRange.flatMap(parseContentRange) match
          case Some((first, last, size)) if first == have && last < total && size.forall(_ == total) => Plan.Append
          case _ => Plan.Fail(s"unexpected Content-Range ${contentRange.getOrElse("(none)")}")
      case 200 => Plan.Restart
      case 416 => if have == total then Plan.Complete else Plan.Discard
      case c => Plan.Fail(s"HTTP $c")

  /** Brings `part` to `total` bytes from `url`. Throws [[Cancelled]] when
    * `cancelled` turns true, [[Refused]] for an answer a retry will not
    * change, and other IOExceptions for transient failures; the partial file
    * stays for the next attempt in every case but a wrong one.
    */
  def run(http: RangeHttp, url: String, part: File, total: Long,
          cancelled: () => Boolean = () => false, progress: Long => Unit = _ => ()): Unit =
    if part.length > total then part.delete()
    var attempts = 0
    var done = false
    while !done do
      attempts += 1
      val have = if part.isFile then part.length else 0L
      if have == total then done = true
      else
        val res = http.get(url, have)
        try
          plan(have, total, res.code, res.header("Content-Range")) match
            case Plan.Append => copy(res.body, part, append = true, have, total, cancelled, progress)
            case Plan.Restart => copy(res.body, part, append = false, 0L, total, cancelled, progress)
            case Plan.Complete => ()
            case Plan.Discard =>
              part.delete()
              if attempts >= 2 then throw Refused("range not satisfiable")
            case Plan.Fail(r) =>
              if res.code == 404 || res.code == 410 || res.code == 403 then throw Refused(r) else throw IOException(r)
        finally res.close()
        val now = if part.isFile then part.length else 0L
        if now == total then done = true
        else if now > total then
          part.delete()
          throw Refused("longer than expected")
        else if attempts >= 3 then throw IOException(s"incomplete: $now of $total bytes")

  private def copy(in: InputStream, part: File, append: Boolean, start: Long, total: Long,
                   cancelled: () => Boolean, progress: Long => Unit): Unit =
    part.getParentFile.mkdirs()
    val out = FileOutputStream(part, append)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = start
      progress(n)
      var r = in.read(buf)
      while r >= 0 do
        if cancelled() then throw Cancelled()
        n += r
        // Never let a server fill the disk past the pinned size.
        if n > total then throw Refused("longer than expected")
        out.write(buf, 0, r)
        progress(n)
        r = in.read(buf)
    finally out.close()

object Sha256:
  def file(f: File): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = FileInputStream(f)
    try
      val buf = new Array[Byte](256 * 1024)
      var r = in.read(buf)
      while r >= 0 do
        md.update(buf, 0, r)
        r = in.read(buf)
    finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  /** The file is exactly the pinned archive. */
  def matches(f: File, pin: BasemapPin): Boolean = f.isFile && f.length == pin.bytes && file(f) == pin.sha256

/** The installed basemap under `root` (the app's filesDir/basemap):
  *
  *   <version>/                 one complete, extracted version
  *   .download/<file>.part      the archive being downloaded
  *   .tmp-<version>/            an extraction in progress
  *
  * A version directory appears only by renaming a complete extraction, so
  * its presence means it is complete. Callers serialize [[install]] and
  * [[cleanup]] (one process, one lock).
  */
final class BasemapStore(val root: File):
  import BasemapStore.*

  def versionDir(version: String): File = File(root, version)
  def downloadDir: File = File(root, DownloadDir)
  def partFile(pin: BasemapPin): File = File(downloadDir, s"${pin.file}.part")

  /** The pinned version's directory, if installed. */
  def installed(pin: BasemapPin): Option[File] =
    Some(versionDir(pin.version)).filter(d => Required.forall(r => File(d, r).isFile))

  /** Extracts a verified archive and moves it into place as `pin.version`. */
  def install(archive: File, pin: BasemapPin): File =
    root.mkdirs()
    val tmp = File(root, s"$TmpPrefix${pin.version}")
    deleteTree(tmp)
    try
      extract(archive, tmp)
      val missing = Required.filterNot(r => File(tmp, r).isFile)
      if missing.nonEmpty then throw IOException(s"archive lacks ${missing.mkString(", ")}")
      val target = versionDir(pin.version)
      deleteTree(target)
      Files.move(tmp.toPath, target.toPath, StandardCopyOption.ATOMIC_MOVE)
      target
    finally deleteTree(tmp)

  /** Removes everything but `keep`'s version and its partial download:
    * older versions, the pre-download layout, leftovers of extractions.
    */
  def cleanup(keep: Option[BasemapPin]): Seq[String] =
    val names = Option(root.list()).map(_.toSeq).getOrElse(Seq.empty)
    val gone = stale(names, keep)
    gone.foreach(n => deleteTree(File(root, n)))
    val dl = downloadDir
    Option(dl.list()).map(_.toSeq).getOrElse(Seq.empty)
      .filterNot(n => keep.exists(p => n == s"${p.file}.part"))
      .foreach(n => deleteTree(File(dl, n)))
    gone

  def deleteAll(): Unit = deleteTree(root)

object BasemapStore:
  val DownloadDir = ".download"
  val TmpPrefix = ".tmp-"
  /** Files every archive has (tools/basemap/assemble.py). */
  val Required: Seq[String] = Seq("style.json", "manifest.json", "ATTRIBUTION", "japan.pmtiles", "world.pmtiles")
  val MaxEntries = 5000
  val MaxExtracted: Long = 1024L * 1024 * 1024

  /** Entries of `root` to delete when `keep` is the version to keep. */
  def stale(names: Seq[String], keep: Option[BasemapPin]): Seq[String] =
    names.filterNot(n => keep.exists(_.version == n) || n == DownloadDir).sorted

  private val Segment = "[A-Za-z0-9 ._@-]{1,128}".r

  /** A relative path inside the archive that stays inside the target. */
  def safeEntry(name: String): Boolean =
    name.nonEmpty && name.length <= 512 && !name.startsWith("/") &&
      name.stripSuffix("/").split('/').forall(s => Segment.matches(s) && s != "." && s != "..")

  def extract(archive: File, dest: File): Unit =
    import scala.jdk.CollectionConverters.*
    val zip = java.util.zip.ZipFile(archive)
    try
      val entries = zip.entries().asScala.toSeq
      if entries.size > MaxEntries then throw IOException("too many entries")
      var total = 0L
      val canonical = dest.getCanonicalPath + File.separator
      for e <- entries do
        if !safeEntry(e.getName) then throw IOException(s"unsafe entry ${e.getName}")
        val f = File(dest, e.getName)
        if !(f.getCanonicalPath + (if e.isDirectory then File.separator else "")).startsWith(canonical) then
          throw IOException(s"unsafe entry ${e.getName}")
        if e.isDirectory then f.mkdirs()
        else
          f.getParentFile.mkdirs()
          val in = zip.getInputStream(e)
          val n =
            try Files.copy(in, f.toPath, StandardCopyOption.REPLACE_EXISTING)
            finally in.close()
          if e.getSize >= 0 && n != e.getSize then throw IOException(s"${e.getName}: size mismatch")
          total += n
          if total > MaxExtracted then throw IOException("archive too large")
    finally zip.close()

  def deleteTree(f: File): Unit =
    if Files.isDirectory(f.toPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
      Option(f.listFiles()).foreach(_.foreach(deleteTree))
    f.delete()
