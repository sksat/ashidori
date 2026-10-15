package ashidori.core

import scala.collection.mutable

/** On-device cache of online venue searches (OpenStreetMap), kept across
  * restarts so that the check-in sheet has candidates without a network and
  * without sending the position anywhere.
  *
  * An entry is one successful search: its circle (the center and radius
  * asked for) and every venue the server returned. Entries are keyed by
  * the ~200 m cell of the center and the radius ([[Key]]); a newer search
  * in the same cell with the same radius replaces the entry.
  *
  * - A lookup ([[lookup]]) unions the venues of every entry whose circle
  *   comes near the spot, newest first per venue. It is
  *   [[Hit.fresh]] when the spot's own cell was searched with at least that
  *   radius within [[FreshMs]]: no automatic online search is needed then.
  * - A complete result (not cut at [[Overpass.Limit]]) also removes, from
  *   older overlapping entries, venues inside its circle that it no longer
  *   lists (closed or renamed), so they do not come back from an old entry.
  * - Entries expire [[KeepMs]] after they were fetched. Beyond
  *   [[MaxVenues]] venues or [[MaxEntries]] entries in total, the entries
  *   used least recently are dropped first.
  *
  * The cache itself is in memory; every change returns a [[Change]] that
  * the store writes (the app's SQLite table), and [[load]] reads it back.
  * Nothing is removed on a failed search: only [[put]] (a successful one),
  * expiry and the size cap remove entries. Thread-safe.
  */
final class VenueCache private (initial: Iterable[VenueCache.Entry]):
  import VenueCache.*

  private val entries = mutable.HashMap.from(initial.map(e => e.key -> e))

  def size: Int = synchronized(entries.size)
  def venueCount: Int = synchronized(entries.valuesIterator.map(_.venues.size).sum)
  def all: Vector[Entry] = synchronized(entries.values.toVector)

  /** Cached venues within `maxDistM` of `p`, closest first, from entries
    * fetched within [[KeepMs]] of `now`. None when no entry comes near.
    */
  def lookup(p: LatLng, radiusM: Int, now: Long, maxDistM: Double = -1): Option[Hit] = synchronized {
    val limit = if maxDistM >= 0 then maxDistM else 2.0 * radiusM
    val near = entries.valuesIterator
      .filter(e => now - e.fetchedAt < KeepMs)
      .filter(e => Geo.distance(p.lat, p.lon, e.lat, e.lon) <= e.radiusM + limit)
      .toVector
      .sortBy(e => (-e.fetchedAt, e.key.toString))
    if near.isEmpty then None
    else
      // Newest entry first, so a venue seen twice keeps its latest name and position.
      val venues = near.iterator.flatMap(_.venues).distinctBy(_.id).map(v => v -> v.latLng.distanceTo(p))
        .filter(_._2 <= limit).toVector.sortBy(_._2).distinctBy(_._1.name)
      val own = key(p, radiusM)
      val fresh = near.exists(e => e.key.y == own.y && e.key.x == own.x && e.key.radiusM >= radiusM && now - e.fetchedAt < FreshMs)
      Some(Hit(venues, near.map(_.fetchedAt).max, fresh, near.map(_.key)))
  }

  /** The entries of a lookup were used now (for least-recently-used eviction). */
  def touch(keys: Seq[Key], now: Long): Change = synchronized {
    val updated = keys.distinct.flatMap(k => entries.get(k)).filter(_.usedAt < now).map(_.copy(usedAt = now)).toVector
    updated.foreach(e => entries(e.key) = e)
    Change(updated, Vector.empty)
  }

  /** Stores a successful search for `radiusM` around `p`. `complete`: the
    * server returned everything in the circle (not cut at its limit).
    */
  def put(p: LatLng, radiusM: Int, now: Long, venues: Seq[Venue], complete: Boolean): Change = synchronized {
    val k = key(p, radiusM)
    val entry = Entry(k, p.lat, p.lon, now, now, venues.toVector)
    val changed = mutable.LinkedHashMap.empty[Key, Entry]
    if complete then
      val listed = venues.iterator.map(_.id).toSet
      // Only venues well inside the circle: one near its edge (an area
      // whose center is outside) may be missing from a complete result.
      def gone(v: Venue) = !listed(v.id) && Geo.distance(p.lat, p.lon, v.lat, v.lon) <= radiusM * PruneWithin
      for e <- entries.valuesIterator if e.key != k && e.venues.exists(gone) do
        changed(e.key) = e.copy(venues = e.venues.filterNot(gone))
    changed(k) = entry
    changed.valuesIterator.foreach(e => entries(e.key) = e)
    val removed = evict(now)
    Change(changed.values.filterNot(e => removed.contains(e.key)).toVector, removed)
  }

  /** Drops expired entries and, beyond the size cap, the least recently used. */
  def expire(now: Long): Change = synchronized(Change(Vector.empty, evict(now)))

  private def evict(now: Long): Vector[Key] =
    val out = Vector.newBuilder[Key]
    for e <- entries.values.toVector if now - e.fetchedAt >= KeepMs do
      entries -= e.key
      out += e.key
    var venues = entries.valuesIterator.map(_.venues.size).sum
    if venues > MaxVenues || entries.size > MaxEntries then
      val lru = entries.values.toVector.sortBy(e => (e.usedAt, e.fetchedAt))
      val it = lru.iterator
      while (venues > MaxVenues || entries.size > MaxEntries) && it.hasNext do
        val e = it.next()
        entries -= e.key
        venues -= e.venues.size
        out += e.key
    out.result()

object VenueCache:
  /** Cell size: ~220 m north-south, ~230 m east-west in Japan. */
  val CellLatDeg = 0.002
  val CellLonDeg = 0.0025
  /** Entries are kept this long after they were fetched. */
  val KeepMs: Long = 90L * 86_400_000L
  /** A search this recent in the same cell needs no automatic online search. */
  val FreshMs: Long = 86_400_000L
  /** Size cap (~100 bytes per venue in SQLite: a few MB). */
  val MaxVenues = 20_000
  val MaxEntries = 5_000
  /** A complete result removes stale venues within this fraction of its radius. */
  val PruneWithin = 0.9

  final case class Key(y: Int, x: Int, radiusM: Int)

  def key(p: LatLng, radiusM: Int): Key =
    Key(math.floor(p.lat / CellLatDeg).toInt, math.floor(p.lon / CellLonDeg).toInt, radiusM)

  /** One successful search: its circle (`lat`, `lon`, `key.radiusM`) and the venues found. */
  final case class Entry(key: Key, lat: Double, lon: Double, fetchedAt: Long, usedAt: Long, venues: Vector[Venue]):
    def radiusM: Int = key.radiusM

  /** Entries to write (insert or replace, with their venues) and to delete. */
  final case class Change(put: Vector[Entry], delete: Vector[Key]):
    def isEmpty: Boolean = put.isEmpty && delete.isEmpty

  /** A lookup: venues with their distance, closest first; the newest fetch
    * time among the entries used; whether the spot was searched recently
    * enough ([[FreshMs]]); and the entries used.
    */
  final case class Hit(venues: Vector[(Venue, Double)], fetchedAt: Long, fresh: Boolean, keys: Vector[Key])

  /** The cache as stored, with expired entries and any excess dropped
    * (returned for the store to delete).
    */
  def load(stored: Iterable[Entry], now: Long): (VenueCache, Change) =
    val c = VenueCache(stored)
    (c, c.expire(now))

  def empty: VenueCache = VenueCache(Nil)
