// Shared check-ins. A user may share selected check-ins with their followers
// (see follows.ts). They are the only plaintext user content on the server, by
// design: the user chose to show them to others. Detailed logs stay
// end-to-end encrypted in batches.
//
// Every change (share, edit, unshare, removal by an admin, the owner being
// disabled or enabled) moves the row to a new cursor, MAX(cursor) + 1. D1 runs
// statements one at a time on a single primary, so the cursor increases with
// each change, and an incremental listing (`after=<last cursor>`) sees every
// change exactly once. Unshared rows stay as tombstones with their content
// nulled out.
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
  const { t, lat, lon } = b;
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
  return { t, lat, lon, venue, category, place, comment };
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

/** Shares or updates (and reshares, if unshared) a device's check-in. */
export async function upsertCheckin(
  db: D1Database,
  userId: string,
  deviceId: string,
  localId: string,
  c: CheckinInput,
): Promise<{ id: string; cursor: number }> {
  // An unchanged re-upload (e.g. a client retry) does not move the cursor.
  const row = await db
    .prepare(
      `INSERT INTO shared_checkins
         (id, user_id, device_id, local_id, t, lat, lon, venue, category, place, comment,
          visibility, updated_at, deleted_at, cursor)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, 'followers', ?12, NULL, ${NEXT_CURSOR})
       ON CONFLICT (device_id, local_id) DO UPDATE SET
         t = excluded.t, lat = excluded.lat, lon = excluded.lon, venue = excluded.venue,
         category = excluded.category, place = excluded.place, comment = excluded.comment,
         updated_at = excluded.updated_at, deleted_at = NULL, cursor = excluded.cursor
       WHERE deleted_at IS NOT NULL OR t IS NOT excluded.t OR lat IS NOT excluded.lat
         OR lon IS NOT excluded.lon OR venue IS NOT excluded.venue OR category IS NOT excluded.category
         OR place IS NOT excluded.place OR comment IS NOT excluded.comment
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
    )
    .first<{ id: string; cursor: number }>();
  if (row) return row;
  const same = await db
    .prepare("SELECT id, cursor FROM shared_checkins WHERE device_id = ?1 AND local_id = ?2")
    .bind(deviceId, localId)
    .first<{ id: string; cursor: number }>();
  return same!;
}

const TOMBSTONE_SET = `t = NULL, lat = NULL, lon = NULL, venue = NULL, category = NULL, place = NULL,
  comment = NULL, deleted_at = ?2, updated_at = ?2, cursor = ${NEXT_CURSOR}`;

/**
 * Unshares a device's check-in, keeping a tombstone. Returns false if the
 * device has no such check-in. Unsharing twice is a no-op.
 */
export async function unshareCheckin(db: D1Database, deviceId: string, localId: string): Promise<boolean> {
  const [, existing] = await db.batch([
    db
      .prepare(`UPDATE shared_checkins SET ${TOMBSTONE_SET} WHERE device_id = ?1 AND local_id = ?3 AND deleted_at IS NULL`)
      .bind(deviceId, floorHour(Date.now()), localId),
    db.prepare("SELECT 1 FROM shared_checkins WHERE device_id = ?1 AND local_id = ?2").bind(deviceId, localId),
  ]);
  return existing!.results.length > 0;
}

/** Admin moderation: like unshareCheckin, by server id. */
export async function removeCheckin(db: D1Database, id: string): Promise<boolean> {
  const [, existing] = await db.batch([
    db.prepare(`UPDATE shared_checkins SET ${TOMBSTONE_SET} WHERE id = ?1 AND deleted_at IS NULL`).bind(id, floorHour(Date.now())),
    db.prepare("SELECT 1 FROM shared_checkins WHERE id = ?1").bind(id),
  ]);
  return existing!.results.length > 0;
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
