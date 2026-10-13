// User accounts. Admins (operators, see adminAuth) create users from the admin
// page; users never sign in to the web. A user receives a pairing code from an
// admin, registers their first device with it, and pairs further devices from
// the app. All /v1/* data is scoped to the device's user.

import type { UserRow, UserStatus } from "./env";

export const MAX_USER_NAME = 64;
const MAX_EMAIL = 254;
const USER_COLUMNS = "id, name, email, status, created_at, created_by, disabled_at";

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
  name: string;
  email: string | null;
  status: UserStatus;
  createdAt: number;
  createdBy: string;
  disabledAt: number | null;
}

export interface UserListItem extends UserView {
  /** Non-revoked devices. */
  deviceCount: number;
  /** Latest lastSeenAt over the user's devices. */
  lastSeenAt: number | null;
}

function toView(r: UserRow): UserView {
  return {
    id: r.id,
    name: r.name,
    email: r.email,
    status: r.status,
    createdAt: r.created_at,
    createdBy: r.created_by,
    disabledAt: r.disabled_at,
  };
}

/** Validates `{name, email?}`; returns an error message or the cleaned values. */
export function parseNewUser(body: unknown): { name: string; email: string | null } | string {
  const { name: rawName, email: rawEmail } = (body ?? {}) as { name?: unknown; email?: unknown };
  if (typeof rawName !== "string") return "name is required";
  const name = rawName.trim();
  if (name.length === 0 || name.length > MAX_USER_NAME || /[\u0000-\u001f\u007f]/.test(name)) {
    return "invalid name";
  }
  let email: string | null = null;
  if (rawEmail !== undefined && rawEmail !== null && rawEmail !== "") {
    if (typeof rawEmail !== "string") return "invalid email";
    email = rawEmail.trim().toLowerCase();
    if (email.length > MAX_EMAIL || !/^[^\s@]+@[^\s@]+$/.test(email)) return "invalid email";
  }
  return { name, email };
}

export async function createUser(
  db: D1Database,
  name: string,
  email: string | null,
  createdBy: string,
): Promise<UserView> {
  const row = await db
    .prepare(
      `INSERT INTO users (id, name, email, status, created_at, created_by)
       VALUES (?1, ?2, ?3, 'active', ?4, ?5)
       RETURNING ${USER_COLUMNS}`,
    )
    .bind(crypto.randomUUID(), name, email, Date.now(), createdBy)
    .first<UserRow>();
  return toView(row!);
}

export async function getUser(db: D1Database, id: string): Promise<UserRow | null> {
  return db.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE id = ?1`).bind(id).first<UserRow>();
}

export async function listUsers(db: D1Database): Promise<UserListItem[]> {
  const { results } = await db
    .prepare(
      `SELECT u.id, u.name, u.email, u.status, u.created_at, u.created_by, u.disabled_at,
         COUNT(d.id) - COUNT(d.revoked_at) AS device_count,
         MAX(d.last_seen_at) AS last_seen_at
       FROM users u LEFT JOIN devices d ON d.user_id = u.id
       GROUP BY u.id ORDER BY u.created_at, u.id`,
    )
    .all<UserRow & { device_count: number; last_seen_at: number | null }>();
  return results.map((r) => ({ ...toView(r), deviceCount: r.device_count, lastSeenAt: r.last_seen_at }));
}

/**
 * Disables or re-enables a user. Disabling takes effect immediately: the
 * user's device tokens and unused pairing codes stop working (both check the
 * user's status), and nothing is deleted. Returns null if there is no such user.
 */
export async function setUserStatus(db: D1Database, id: string, status: UserStatus): Promise<UserView | null> {
  const row = await db
    .prepare(
      `UPDATE users SET status = ?2,
         disabled_at = CASE WHEN ?2 = 'disabled' THEN COALESCE(disabled_at, ?3) ELSE NULL END
       WHERE id = ?1
       RETURNING ${USER_COLUMNS}`,
    )
    .bind(id, status, Date.now())
    .first<UserRow>();
  return row ? toView(row) : null;
}
