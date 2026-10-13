// Follows between users. A user finds another by their exact handle (there is
// no directory or search, so users cannot be enumerated) and sends a follow
// request. Once the other user accepts it, the follower sees their shared
// check-ins. Following is one-way; either side can end it (unfollow, remove
// follower), and declining deletes the request, so it can be sent again.
//
// Only users with status 'active' are found, followed or listed.
//
// Every change to whom a user follows (accepted) bumps users.follows_version
// (triggers in migration 0003); feeds carry it so clients know to resync (see
// checkins.ts).

import { floorHour } from "./util";

export const HANDLE_RE = /^[a-z0-9_]{3,20}$/;

export type FollowStatus = "pending" | "accepted";

export interface UserRef {
  id: string;
  handle: string | null;
  name: string;
}

export interface FollowView extends UserRef {
  /** Accepted: when accepted; pending: when requested. Rounded down to the hour. */
  since: number;
}

export interface FollowingView extends FollowView {
  status: FollowStatus;
}

/** Lowercases; returns null if the result is not a valid handle. */
export function normalizeHandle(raw: string): string | null {
  const h = raw.trim().toLowerCase();
  return HANDLE_RE.test(h) ? h : null;
}

/** Exact, case-insensitive (handles are stored lowercase) lookup of an active user. */
export async function lookupHandle(db: D1Database, handle: string): Promise<UserRef | null> {
  return db
    .prepare("SELECT id, handle, name FROM users WHERE handle = ?1 AND status = 'active'")
    .bind(handle)
    .first<UserRef>();
}

export type RequestResult = { user: UserRef; status: FollowStatus } | "no user" | "self";

/** Sends a follow request; idempotent (an existing request or follow is returned as is). */
export async function requestFollow(db: D1Database, followerId: string, followeeId: string): Promise<RequestResult> {
  if (followerId === followeeId) return "self";
  const [, row] = await db.batch([
    db
      .prepare(
        `INSERT INTO follows (follower_id, followee_id, status, created_at)
         SELECT ?1, id, 'pending', ?3 FROM users WHERE id = ?2 AND status = 'active'
         ON CONFLICT DO NOTHING`,
      )
      .bind(followerId, followeeId, floorHour(Date.now())),
    db
      .prepare(
        `SELECT u.id, u.handle, u.name, f.status FROM follows f JOIN users u ON u.id = f.followee_id
         WHERE f.follower_id = ?1 AND f.followee_id = ?2 AND u.status = 'active'`,
      )
      .bind(followerId, followeeId),
  ]);
  const r = (row!.results as Array<UserRef & { status: FollowStatus }>)[0];
  if (!r) return "no user";
  const { status, ...user } = r;
  return { user, status };
}

/** Users `userId` follows or has asked to follow. */
export async function listFollowing(db: D1Database, userId: string): Promise<FollowingView[]> {
  const { results } = await db
    .prepare(
      `SELECT u.id, u.handle, u.name, f.status, COALESCE(f.accepted_at, f.created_at) AS since
       FROM follows f JOIN users u ON u.id = f.followee_id
       WHERE f.follower_id = ?1 AND u.status = 'active'
       ORDER BY since, u.id`,
    )
    .bind(userId)
    .all<FollowingView>();
  return results;
}

/** Users that follow `userId` (status 'accepted') or ask to ('pending'). */
export async function listFollowers(db: D1Database, userId: string, status: FollowStatus): Promise<FollowView[]> {
  const { results } = await db
    .prepare(
      `SELECT u.id, u.handle, u.name, COALESCE(f.accepted_at, f.created_at) AS since
       FROM follows f JOIN users u ON u.id = f.follower_id
       WHERE f.followee_id = ?1 AND f.status = ?2 AND u.status = 'active'
       ORDER BY since, u.id`,
    )
    .bind(userId, status)
    .all<FollowView>();
  return results;
}

/** Accepts a pending request. Accepting twice is a no-op. Returns false if there is no request. */
export async function acceptFollow(db: D1Database, followerId: string, followeeId: string): Promise<boolean> {
  const [, existing] = await db.batch([
    db
      .prepare(
        `UPDATE follows SET status = 'accepted', accepted_at = ?3
         WHERE follower_id = ?1 AND followee_id = ?2 AND status = 'pending'
           AND follower_id IN (SELECT id FROM users WHERE status = 'active')`,
      )
      .bind(followerId, followeeId, floorHour(Date.now())),
    db
      .prepare(
        `SELECT 1 FROM follows f JOIN users u ON u.id = f.follower_id
         WHERE f.follower_id = ?1 AND f.followee_id = ?2 AND u.status = 'active'`,
      )
      .bind(followerId, followeeId),
  ]);
  return existing!.results.length > 0;
}

/**
 * Deletes a follow or request. `status` limits which (decline: 'pending';
 * remove follower: 'accepted'; unfollow: either). Returns false if there was none.
 */
export async function deleteFollow(
  db: D1Database,
  followerId: string,
  followeeId: string,
  status?: FollowStatus,
): Promise<boolean> {
  const res = await db
    .prepare("DELETE FROM follows WHERE follower_id = ?1 AND followee_id = ?2 AND (?3 IS NULL OR status = ?3)")
    .bind(followerId, followeeId, status ?? null)
    .run();
  return res.meta.changes > 0;
}
