package ashidori.core

/** Maps and surfaces as the app has them, for the gate's tests. */
object OutboundFixtures:
  val Offline = "offline"
  val Liberty = "ofm-liberty"
  val Positron = "ofm-positron"
  val GsiStd = "gsi-std"
  val GsiPhoto = "gsi-photo"
  val Custom = "custom:https://tiles.example.net/{z}/{x}/{y}.png"
  val Online: Seq[String] = Seq(Liberty, Positron, GsiStd, GsiPhoto, Custom)
  val Maps: Seq[String] = Offline +: Online
  val Surfaces: Set[String] = Set("map", "period", "picker")

  def destOf(m: String): Option[Dest] =
    if m.startsWith("ofm-") then Some(Dest.Ofm)
    else if m.startsWith("gsi-") then Some(Dest.Gsi)
    else if m == Custom then Some(Dest.CustomTiles)
    else None

  /** A gate as the app makes it from stored values, with stored writes kept in `store`. */
  final class Store(var strings: Map[String, String] = Map.empty, var booleans: Map[String, Boolean] = Map.empty):
    import MapConsents.Stored
    private def str(k: String): Stored[String] = strings.get(k).fold(Stored.Missing)(Stored.Ok(_))
    private def bool(k: String): Stored[Boolean] = booleans.get(k).fold(Stored.Missing)(Stored.Ok(_))
    def values: ExternalSettings.Values = ExternalSettings.Values(
      str(ExternalSettings.PolicyKey), str(ExternalSettings.ConsentsKey), str(ExternalSettings.GensKey),
      bool(ExternalSettings.PausedKey), bool(ExternalSettings.LegacyBlockKey), str(ExternalSettings.LegacyConsentsKey))
    def write(w: ExternalSettings.Writes): Unit =
      strings ++= w.strings
      booleans ++= w.booleans
    /** Starts the app: loads (migrating if needed) and stores what loading writes. */
    def start(seed: Long = 1, epoch: Epoch = Epoch.random()): SendGate =
      val rnd = java.util.Random(seed)
      val (state, ws) = ExternalSettings.load(values, Surfaces, destOf, rnd)
      ws.foreach(write)
      SendGate(state, s => write(ExternalSettings.writes(s)), rnd, epoch)

  def gate(p: Policy = Policy.fresh, paused: Boolean = false, seed: Long = 7, revokeWaitMs: Long = SendGate.RevokeWaitMs): SendGate =
    val rnd = java.util.Random(seed)
    SendGate(SendGate.State(p, Map.empty, Gens.random(rnd), paused), _ => (), rnd, Epoch.random(), revokeWaitMs)
