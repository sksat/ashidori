// Shared check-ins. A user may share selected check-ins with their followers
// (see follows.ts). They are the only plaintext user content on the server, by
// design: the user chose to show them to others. Detailed logs stay
// end-to-end encrypted in batches.
//
// Every change (share, edit, unshare, removal by an admin, the owner being
// disabled or enabled) moves the row to a new cursor, MAX(cursor) + 1. D1 runs
// statements one at a time on a single primary, so the cursor increases with
// each change. A row keeps only its latest state, so an incremental listing
// (`after=<last cursor>`) converges to the latest state of every entry; it
// does not see intermediate states. Unshared rows stay as tombstones with
// their content nulled out.
//
// Revisions: every PUT and DELETE carries the client's `rev` (e.g. the
// check-in's updatedAt in ms), required for both, and the row keeps the rev
// of the last operation applied, tombstones included. A DELETE without a rev
// is refused (400): a rev the server assigned would differ on each retry, so
// a retried DELETE would be a new, later operation and could unshare an
// entry shared again since (review 6, item 8).
// An operation applies only if its rev is above the stored one, decided in
// the statement that applies it, so a delayed or replayed older PUT or DELETE
// cannot undo a later one. A DELETE of an unknown local id leaves a
// tombstone, so that a first PUT arriving after it with a lower rev is
// refused. Removal by an admin is final for that entry: it cannot be shared
// again under the same local id.
//
// Which authors a feed covers depends on whom the caller follows. The cursor
// cannot express changes to that set, so feeds carry the caller's
// `followsVersion` and clients resync from the start when it changes.
//
// `visibility` is always 'followers' for now. Sharing with selected users
// (encrypted per recipient) is expected to add other values.

import type { UserRef } from "./follows";
import { floorHour } from "./util";

export const LOCAL_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_VENUE = 200;
const MAX_SHORT = 200;
const MAX_COMMENT = 1000;
// Control characters; the comment may also contain tabs and line breaks.
const CONTROL_RE = /[\u0000-\u001f\u007f]/;
const COMMENT_CONTROL_RE = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

export interface CheckinInput {
  rev: number;
  t: number;
  lat: number;
  lon: number;
  venue: string;
  category: string | null;
  place: string | null;
  comment: string | null;
}

interface CheckinRow {
  id: string;
  user_id: string;
  user_username: string | null;
  user_status: string;
  t: number | null;
  lat: number | null;
  lon: number | null;
  venue: string | null;
  category: string | null;
  place: string | null;
  comment: string | null;
  updated_at: number;
  deleted_at: number | null;
  cursor: number;
}

export interface CheckinView {
  id: string;
  cursor: number;
  user: UserRef;
  t: number | null;
  lat: number | null;
  lon: number | null;
  venue: string | null;
  category: string | null;
  place: string | null;
  comment: string | null;
  updatedAt: number;
  /** Unshared, removed by an admin, or the owner is disabled. Content fields are then null. */
  deleted: boolean;
}

const NEXT_CURSOR = "(SELECT COALESCE(MAX(cursor), 0) + 1 FROM shared_checkins)";
const VIEW_COLUMNS = `p.id, p.user_id, u.handle AS user_username, u.status AS user_status,
  p.t, p.lat, p.lon, p.venue, p.category, p.place, p.comment, p.updated_at, p.deleted_at, p.cursor`;

function optionalText(v: unknown, max: number, re: RegExp): string | null | undefined {
  if (v === undefined || v === null || v === "") return null;
  if (typeof v !== "string" || v.length > max || re.test(v)) return undefined;
  return v;
}

/** Validates the PUT body; returns an error message or the cleaned values. */
export function parseCheckin(body: unknown): CheckinInput | string {
  const b = (body ?? {}) as Record<string, unknown>;
  const { rev, t, lat, lon } = b;
  if (typeof rev !== "number" || !Number.isSafeInteger(rev) || rev < 0) return "invalid rev";
  if (typeof t !== "number" || !Number.isSafeInteger(t) || t < 0) return "invalid t";
  if (typeof lat !== "number" || !Number.isFinite(lat) || lat < -90 || lat > 90) return "invalid lat";
  if (typeof lon !== "number" || !Number.isFinite(lon) || lon < -180 || lon > 180) return "invalid lon";
  if (typeof b.venue !== "string") return "venue is required";
  const venue = b.venue.trim();
  if (venue.length === 0 || venue.length > MAX_VENUE || CONTROL_RE.test(venue)) return "invalid venue";
  const category = optionalText(b.category, MAX_SHORT, CONTROL_RE);
  if (category === undefined) return "invalid category";
  const place = optionalText(b.place, MAX_SHORT, CONTROL_RE);
  if (place === undefined) return "invalid place";
  const comment = optionalText(b.comment, MAX_COMMENT, COMMENT_CONTROL_RE);
  if (comment === undefined) return "invalid comment";
  return { rev, t, lat, lon, venue, category, place, comment };
}

/** A revision from a query string: a non-negative integer, or null if absent (refused by the routes); undefined if invalid. */
export function parseRev(raw: string | undefined): number | null | undefined {
  if (raw === undefined) return null;
  if (!/^(0|[1-9][0-9]{0,15})$/.test(raw)) return undefined;
  const n = Number(raw);
  return Number.isSafeInteger(n) ? n : undefined;
}

function toView(r: CheckinRow): CheckinView {
  // A disabled user's entries are reported as deleted, so that they disappear
  // for followers, including those syncing incrementally (see setUserStatus).
  const deleted = r.deleted_at !== null || r.user_status !== "active";
  return {
    id: r.id,
    cursor: r.cursor,
    user: { id: r.user_id, username: r.user_username },
    t: deleted ? null : r.t,
    lat: deleted ? null : r.lat,
    lon: deleted ? null : r.lon,
    venue: deleted ? null : r.venue,
    category: deleted ? null : r.category,
    place: deleted ? null : r.place,
    comment: deleted ? null : r.comment,
    updatedAt: r.updated_at,
    deleted,
  };
}

export type UpsertResult = { id: string; cursor: number } | "stale" | "removed" | "limit";

const CONTENT_CHANGED = `deleted_at IS NOT NULL OR t IS NOT excluded.t OR lat IS NOT excluded.lat
  OR lon IS NOT excluded.lon OR venue IS NOT excluded.venue OR category IS NOT excluded.category
  OR place IS NOT excluded.place OR comment IS NOT excluded.comment`;

/**
 * Shares or updates (and reshares, if unshared) a device's check-in.
 *
 * - A new entry is created only while the user has fewer than `maxEntries`
 *   (tombstones included); else "limit".
 * - An existing entry is changed only by a higher `rev`. An unchanged content
 *   with a higher rev only records the rev (the cursor stays).
 * - A retry of the PUT that set the current state (same rev, same content)
 *   returns the entry as is. Any other rev not above the stored one is
 *   "stale"; an entry removed by an admin is "removed".
 */
export async function upsertCheckin(
  db: D1Database,
  userId: string,
  deviceId: string,
  localId: string,
  c: CheckinInput,
  maxEntries: number,
): Promise<UpsertResult> {
  const row = await db
    .prepare(
      `INSERT INTO shared_checkins
         (id, user_id, device_id, local_id, t, lat, lon, venue, category, place, comment,
          visibility, updated_at, deleted_at, cursor, rev)
       SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, 'followers', ?12, NULL, ${NEXT_CURSOR}, ?13
       WHERE EXISTS (SELECT 1 FROM shared_checkins WHERE device_id = ?3 AND local_id = ?4)
         OR (SELECT checkin_count FROM users WHERE id = ?2) < ?14
       ON CONFLICT (device_id, local_id) DO UPDATE SET
         t = excluded.t, lat = excluded.lat, lon = excluded.lon, venue = excluded.venue,
         category = excluded.category, place = excluded.place, comment = excluded.comment,
         updated_at = CASE WHEN ${CONTENT_CHANGED} THEN excluded.updated_at ELSE updated_at END,
         cursor = CASE WHEN ${CONTENT_CHANGED} THEN excluded.cursor ELSE cursor END,
         deleted_at = NULL, rev = excluded.rev
       WHERE excluded.rev > shared_checkins.rev AND shared_checkins.removed_by_admin = 0
       RETURNING id, cursor`,
    )
    .bind(
      crypto.randomUUID(),
      userId,
      deviceId,
      localId,
      c.t,
      c.lat,
      c.lon,
      c.venue,
      c.category,
      c.place,
      c.comment,
      floorHour(Date.now()),
      c.rev,
      maxEntries,
    )
    .first<{ id: string; cursor: number }>();
  if (row) return row;

  const cur = await db
    .prepare(
      `SELECT id, cursor, rev, removed_by_admin, deleted_at, t, lat, lon, venue, category, place, comment
       FROM shared_checkins WHERE device_id = ?1 AND local_id = ?2`,
    )
    .bind(deviceId, localId)
    .first<Omit<CheckinRow, "user_id" | "user_username" | "user_status" | "updated_at"> & {
      rev: number;
      removed_by_admin: number;
    }>();
  if (!cur) return "limit";
  if (cur.removed_by_admin === 1) return "removed";
  const retry =
    cur.rev === c.rev &&
    cur.deleted_at === null &&
    cur.t === c.t &&
    cur.lat === c.lat &&
    cur.lon === c.lon &&
    cur.venue === c.venue &&
    cur.category === c.category &&
    cur.place === c.place &&
    cur.comment === c.comment;
  return retry ? { id: cur.id, cursor: cur.cursor } : "stale";
}

export type UnshareResult = "deleted" | "stale" | "limit" | "not found";

// Turns the row into a tombstone (in an UPDATE or an upsert's DO UPDATE,
// where every expression reads the row as it was before the change). A live
// entry gets a new cursor and the hour of the change; a tombstone keeps
// them. ?2: the hour of the change.
const TOMBSTONE_SET = `t = NULL, lat = NULL, lon = NULL, venue = NULL, category = NULL, place = NULL,
  comment = NULL,
  updated_at = CASE WHEN deleted_at IS NULL THEN ?2 ELSE updated_at END,
  cursor = CASE WHEN deleted_at IS NULL THEN ${NEXT_CURSOR} ELSE cursor END,
  deleted_at = COALESCE(deleted_at, ?2)`;

interface RevRow {
  rev: number;
  deleted_at: number | null;
  removed_by_admin: number;
}

/**
 * Why an unshare with `rev` changed nothing, from the row as it is now:
 * removed by an admin (final, so the unshare holds: "deleted"), a retry of
 * the unshare that made the current tombstone (same rev: "deleted"), or
 * superseded by a later PUT or DELETE ("stale"). Null if there is no row.
 */
function unshareOutcome(row: RevRow | null, rev: number): UnshareResult | null {
  if (!row) return null;
  if (row.removed_by_admin === 1) return "deleted";
  return row.deleted_at !== null && row.rev === rev ? "deleted" : "stale";
}

/**
 * Unshares a device's check-in: the entry becomes a tombstone with `rev`, if
 * `rev` is above the stored one. One statement decides and applies it.
 *
 * - A rev not above the stored one changes nothing: "deleted" if it retries
 *   the unshare that made the current tombstone (same rev), else "stale".
 * - An unknown local id gets a tombstone with `rev`, so that a delayed first
 *   PUT with a lower rev cannot share the entry afterwards. It counts against
 *   `maxEntries` like any entry ("limit" when the user is at it).
 * - An entry removed by an admin stays as it is ("deleted").
 */
export async function unshareCheckin(
  db: D1Database,
  userId: string,
  deviceId: string,
  localId: string,
  r: number,
  maxEntries: number,
): Promise<UnshareResult> {
  const row = await db
    .prepare(
      `INSERT INTO shared_checkins
         (id, user_id, device_id, local_id, visibility, updated_at, deleted_at, cursor, rev)
       SELECT ?3, ?4, ?5, ?6, 'followers', ?2, ?2, ${NEXT_CURSOR}, ?1
       WHERE EXISTS (SELECT 1 FROM shared_checkins WHERE device_id = ?5 AND local_id = ?6)
         OR (SELECT checkin_count FROM users WHERE id = ?4) < ?7
       ON CONFLICT (device_id, local_id) DO UPDATE SET ${TOMBSTONE_SET}, rev = excluded.rev
       WHERE excluded.rev > shared_checkins.rev AND shared_checkins.removed_by_admin = 0
       RETURNING id`,
    )
    .bind(r, floorHour(Date.now()), crypto.randomUUID(), userId, deviceId, localId, maxEntries)
    .first();
  if (row) return "deleted";
  const cur = await db
    .prepare("SELECT rev, deleted_at, removed_by_admin FROM shared_checkins WHERE device_id = ?1 AND local_id = ?2")
    .bind(deviceId, localId)
    .first<RevRow>();
  return unshareOutcome(cur, r) ?? "limit";
}

/**
 * Unshares a check-in by server id, from any device of its user (e.g. after
 * the sharing device was lost and revoked), under the same revision rule as
 * unshareCheckin. An unknown id, or another user's, is "not found".
 */
export async function unshareCheckinById(
  db: D1Database,
  userId: string,
  id: string,
  r: number,
): Promise<UnshareResult> {
  const row = await db
    .prepare(
      `UPDATE shared_checkins SET ${TOMBSTONE_SET}, rev = ?1
       WHERE id = ?3 AND user_id = ?4 AND rev < ?1 AND removed_by_admin = 0
       RETURNING id`,
    )
    .bind(r, floorHour(Date.now()), id, userId)
    .first();
  if (row) return "deleted";
  const cur = await db
    .prepare("SELECT rev, deleted_at, removed_by_admin FROM shared_checkins WHERE id = ?1 AND user_id = ?2")
    .bind(id, userId)
    .first<RevRow>();
  return unshareOutcome(cur, r) ?? "not found";
}

/**
 * Admin moderation: removes a check-in by server id, whatever its rev. Final:
 * the entry stays removed, whatever rev its owner sends later.
 */
export async function removeCheckin(db: D1Database, id: string): Promise<boolean> {
  const now = Date.now();
  const row = await db
    .prepare(
      `UPDATE shared_checkins SET ${TOMBSTONE_SET}, rev = MAX(rev, ?1), removed_by_admin = 1
       WHERE id = ?3 RETURNING id`,
    )
    .bind(now, floorHour(now), id)
    .first();
  return row !== null;
}

/**
 * Changes since `after` to the check-ins of `userId` and the users it
 * follows (accepted), including tombstones, ordered by cursor. Read in one batch (one
 * transaction) with the follows version the listing is valid for.
 */
export async function listFeed(
  db: D1Database,
  userId: string,
  after: number,
  limit: number,
): Promise<{ items: CheckinView[]; next: number | null; followsVersion: number }> {
  const [version, rows] = await db.batch([
    db.prepare("SELECT follows_version FROM users WHERE id = ?1").bind(userId),
    // One extra row tells whether there is a next page.
    db
      .prepare(
        `SELECT ${VIEW_COLUMNS}
         FROM shared_checkins p JOIN users u ON u.id = p.user_id
         WHERE p.cursor > ?1 AND p.visibility = 'followers'
           AND (p.user_id = ?3 OR p.user_id IN
             (SELECT followee_id FROM follows WHERE follower_id = ?3 AND status = 'accepted'))
         ORDER BY p.cursor LIMIT ?2`,
      )
      .bind(after, limit + 1, userId),
  ]);
  const results = rows!.results as CheckinRow[];
  const page = results.slice(0, limit);
  const next = results.length > limit ? page[page.length - 1]!.cursor : null;
  const followsVersion = (version!.results as Array<{ follows_version: number }>)[0]!.follows_version;
  return { items: page.map(toView), next, followsVersion };
}

/** Admin view: the most recently changed shared check-ins, with the owner's status. */
export async function listRecentCheckins(
  db: D1Database,
  limit: number,
): Promise<Array<Omit<CheckinView, "deleted"> & { userStatus: string }>> {
  const { results } = await db
    .prepare(
      `SELECT ${VIEW_COLUMNS}
       FROM shared_checkins p JOIN users u ON u.id = p.user_id
       WHERE p.deleted_at IS NULL
       ORDER BY p.cursor DESC LIMIT ?1`,
    )
    .bind(limit)
    .all<CheckinRow>();
  return results.map((r) => {
    const { deleted: _, ...view } = toView({ ...r, user_status: "active" });
    return { ...view, userStatus: r.user_status };
  });
}

/**
 * Moves all of a user's entries to new cursors, so that clients syncing
 * incrementally see them again: as deleted after the user is disabled, and as
 * shared after the user is enabled. Run together with the status change.
 */
export function bumpUserCheckins(db: D1Database, userId: string): D1PreparedStatement {
  return db
    .prepare(
      `UPDATE shared_checkins SET cursor = (SELECT MAX(cursor) FROM shared_checkins) + r.rn
       FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY cursor) AS rn FROM shared_checkins WHERE user_id = ?1) AS r
       WHERE shared_checkins.id = r.id`,
    )
    .bind(userId);
}
