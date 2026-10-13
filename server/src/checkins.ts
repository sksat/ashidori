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
// Revisions: every PUT carries the client's `rev` (e.g. the check-in's
// updatedAt in ms), and the row keeps the highest one, tombstones included.
// A PUT whose rev is not above the stored one is refused, so a delayed or
// retried old PUT cannot undo a later edit or unshare. An unshare sets the
// rev to the given one or, if none, to the server time. Removal by an admin is
// final for that entry: it cannot be shared again under the same local id.
//
// Which authors a feed covers depends on whom the caller follows. The cursor
// cannot express changes to that set, so feeds carry the caller's
// `followsVersion` and clients resync from the start when it changes.
//
// `visibility` is always 'followers' for now. Sharing with selected users
// (encrypted per recipient) is expected to add other values.

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
  user_name: string;
  user_handle: string | null;
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
  user: { id: string; handle: string | null; name: string };
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
const VIEW_COLUMNS = `p.id, p.user_id, u.name AS user_name, u.handle AS user_handle, u.status AS user_status,
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

/** A revision from a query string: a non-negative integer, or null if absent; undefined if invalid. */
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
    user: { id: r.user_id, handle: r.user_handle, name: r.user_name },
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
    .first<Omit<CheckinRow, "user_id" | "user_name" | "user_handle" | "user_status" | "updated_at"> & {
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

// ?2: the hour of the change.
const TOMBSTONE_SET = `t = NULL, lat = NULL, lon = NULL, venue = NULL, category = NULL, place = NULL,
  comment = NULL, deleted_at = ?2, updated_at = ?2, cursor = ${NEXT_CURSOR}`;

/**
 * Tombstones the entries matched by `where` (params from ?3 on) and raises
 * their rev to `rev`. Returns false if nothing matched. Unsharing twice only
 * raises the rev; the cursor stays.
 */
async function tombstone(
  db: D1Database,
  where: string,
  params: unknown[],
  rev: number,
  adminRemoval: boolean,
): Promise<boolean> {
  const hour = floorHour(Date.now());
  const [, , existing] = await db.batch([
    db.prepare(`UPDATE shared_checkins SET ${TOMBSTONE_SET} WHERE ${where} AND deleted_at IS NULL`).bind(rev, hour, ...params),
    db
      .prepare(
        `UPDATE shared_checkins SET rev = MAX(rev, ?1)${adminRemoval ? ", removed_by_admin = 1" : ""} WHERE ${where}`,
      )
      .bind(rev, hour, ...params),
    // Same numbering as above (?1 and ?2 unused here).
    db.prepare(`SELECT 1 FROM shared_checkins WHERE ${where}`).bind(rev, hour, ...params),
  ]);
  return existing!.results.length > 0;
}

/**
 * Unshares a device's check-in, keeping a tombstone with `rev` (default: the
 * server time in ms). Returns false if the device has no such check-in.
 */
export function unshareCheckin(db: D1Database, deviceId: string, localId: string, rev: number | null): Promise<boolean> {
  return tombstone(db, "device_id = ?3 AND local_id = ?4", [deviceId, localId], rev ?? Date.now(), false);
}

/**
 * Unshares a check-in by server id, from any device of its user (e.g. after
 * the sharing device was lost and revoked). Returns false if the user has no
 * such check-in.
 */
export function unshareCheckinById(db: D1Database, userId: string, id: string, rev: number | null): Promise<boolean> {
  return tombstone(db, "id = ?3 AND user_id = ?4", [id, userId], rev ?? Date.now(), false);
}

/**
 * Admin moderation: removes a check-in by server id. Final: the entry stays
 * removed, whatever rev its owner sends later.
 */
export function removeCheckin(db: D1Database, id: string): Promise<boolean> {
  return tombstone(db, "id = ?3", [id], Date.now(), true);
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
