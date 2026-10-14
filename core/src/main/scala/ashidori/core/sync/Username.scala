package ashidori.core.sync

/** A username: what a user is shown and looked up by, chosen on the user's
  * first device. The same rule as the server (server/src/follows.ts):
  * 3 to 20 of `a-z`, `0-9` and `_`, case-insensitive (stored lowercase).
  */
object Username:
  private val Re = "[a-z0-9_]{3,20}".r

  /** Trimmed and lowercased; None unless it is a valid username. */
  def normalize(raw: String): Option[String] =
    val u = raw.trim.toLowerCase(java.util.Locale.ROOT)
    Option.when(Re.matches(u))(u)

  /** Why `raw` is not a valid username (for the input field), or None. */
  def problem(raw: String): Option[String] =
    val u = raw.trim
    if u.isEmpty then Some("ユーザ名を入力してください")
    else if u.length < 3 || u.length > 20 then Some("3〜20 文字で入力してください")
    else if normalize(u).isEmpty then Some("英字・数字・_ だけを使えます")
    else None
