// User accounts. Users never sign in to the web, and every user is created
// together with their first app device (POST /v1/accounts, accounts.ts): with
// an invite from an admin (invites.ts), or without one when REGISTRATION =
// "open". The person chooses their username on that device. Further devices
// are paired from the app. All /v1/* data is scoped to the device's user,
// except shared check-ins, which followers see (see follows.ts).
//
// Users created before usernames existed (by an admin, or self-registered)
// may have none (legacy); the admin page lists them as needing one, and an
// admin sets it here. users.name is what such a user was called before; for
// newer users it equals the username, and nothing shows it.

import { bumpUserCheckins } from "./checkins";
import type { UserRow, UserStatus } from "./env";
import { normalizeUsername } from "./follows";

const USER_COLUMNS = "id, name, handle, email, status, created_at, created_by, disabled_at";

export function parseAdminEmails(raw: string | undefined): Set<string> {
  return new Set(
    (raw ?? "")
      .split(",")
      .map((s) => s.trim().toLowerCase())
      .filter((s) => s.length > 0),
  );
}

export interface UserView {
  id: string;
  /** Null only for legacy users. */
  username: string | null;
  /** The name of a legacy user without a username, so that an admin can tell who it is; else null. */
  legacyName: string | null;
  email: string | null;
  status: UserStatus;
  createdAt: number;
  /** "self" (no invite), "invite:<invite id>", or the e-mail address of the admin who created a legacy user. */
  createdBy: string;
  disabledAt: number | null;
}

export interface UserListItem extends UserView {
  /** Non-revoked devices. */
  deviceCount: number;
  /** Latest lastSeenAt over the user's devices. */
  lastSeenAt: number | null;
  /** Blob bytes stored by the user's devices (what the quota counts, see accounts.ts). */
  storageBytes: number;
  batchCount: number;
  /** Shared check-ins, tombstones included. */
  sharedCheckinCount: number;
}

/** Another user already has the username. */
export class UsernameTaken extends Error {}

export function isUsernameTakenError(e: unknown): boolean {
  return e instanceof Error && /UNIQUE constraint failed: users\.handle/.test(e.message);
}

function toView(r: UserRow): UserView {
  return {
    id: r.id,
    username: r.handle,
    legacyName: r.handle === null ? r.name : null,
    email: r.email,
    status: r.status,
    createdAt: r.created_at,
    createdBy: r.created_by,
    disabledAt: r.disabled_at,
  };
}

/** Validates `{username}` (required); returns an error message or the normalized username. */
export function parseUsernamePatch(body: unknown): { username: string } | string {
  const { username } = (body ?? {}) as Record<string, unknown>;
  if (typeof username !== "string") return "username is required";
  const u = normalizeUsername(username);
  return u === null ? "invalid username" : { username: u };
}

/** Sets or changes the username. Returns null if there is no such user; throws UsernameTaken. */
export async function setUsername(db: D1Database, id: string, username: string): Promise<UserView | null> {
  try {
    const row = await db
      .prepare(`UPDATE users SET handle = ?2 WHERE id = ?1 RETURNING ${USER_COLUMNS}`)
      .bind(id, username)
      .first<UserRow>();
    return row ? toView(row) : null;
  } catch (e) {
    if (isUsernameTakenError(e)) throw new UsernameTaken();
    throw e;
  }
}

export async function getUser(db: D1Database, id: string): Promise<UserRow | null> {
  return db.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE id = ?1`).bind(id).first<UserRow>();
}

export async function listUsers(db: D1Database): Promise<UserListItem[]> {
  const { results } = await db
    .prepare(
      `SELECT u.id, u.name, u.handle, u.email, u.status, u.created_at, u.created_by, u.disabled_at,
         COUNT(d.id) - COUNT(d.revoked_at) AS device_count,
         MAX(d.last_seen_at) AS last_seen_at, u.storage_bytes, u.batch_count, u.checkin_count
       FROM users u LEFT JOIN devices d ON d.user_id = u.id
       GROUP BY u.id ORDER BY u.created_at, u.id`,
    )
    .all<
      UserRow & {
        device_count: number;
        last_seen_at: number | null;
        storage_bytes: number;
        batch_count: number;
        checkin_count: number;
      }
    >();
  return results.map((r) => ({
    ...toView(r),
    deviceCount: r.device_count,
    lastSeenAt: r.last_seen_at,
    storageBytes: r.storage_bytes,
    batchCount: r.batch_count,
    sharedCheckinCount: r.checkin_count,
  }));
}

/**
 * Disables or re-enables a user. Disabling takes effect immediately: the
 * user's device tokens and unused pairing codes stop working (both check the
 * user's status), the user cannot be looked up or followed, and the user's
 * shared check-ins are listed as deleted. Nothing is deleted. Returns null if
 * there is no such user.
 */
export async function setUserStatus(db: D1Database, id: string, status: UserStatus): Promise<UserView | null> {
  const [updated] = await db.batch([
    db
      .prepare(
        `UPDATE users SET status = ?2,
           disabled_at = CASE WHEN ?2 = 'disabled' THEN COALESCE(disabled_at, ?3) ELSE NULL END
         WHERE id = ?1
         RETURNING ${USER_COLUMNS}`,
      )
      .bind(id, status, Date.now()),
    bumpUserCheckins(db, id),
  ]);
  const row = (updated!.results as UserRow[])[0];
  return row ? toView(row) : null;
}
