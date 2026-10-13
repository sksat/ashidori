// User accounts. Admins (operators, see adminAuth) create users from the admin
// page; users never sign in to the web. A user receives a pairing code from an
// admin, registers their first device with it, and pairs further devices from
// the app. With REGISTRATION = "open", the first device may instead create its
// own user (see accounts.ts). All /v1/* data is scoped to the device's user,
// except shared check-ins, which followers see (see follows.ts).

import { bumpUserCheckins } from "./checkins";
import type { UserRow, UserStatus } from "./env";
import { normalizeHandle } from "./follows";

export const MAX_USER_NAME = 64;
const MAX_EMAIL = 254;
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
  name: string;
  handle: string | null;
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
  /** Blob bytes stored by the user's devices (what the quota counts, see accounts.ts). */
  storageBytes: number;
}

/** Another user already has the handle. */
export class HandleTaken extends Error {}

function toView(r: UserRow): UserView {
  return {
    id: r.id,
    name: r.name,
    handle: r.handle,
    email: r.email,
    status: r.status,
    createdAt: r.created_at,
    createdBy: r.created_by,
    disabledAt: r.disabled_at,
  };
}

function parseName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const name = raw.trim();
  if (name.length === 0 || name.length > MAX_USER_NAME || /[\u0000-\u001f\u007f]/.test(name)) return null;
  return name;
}

/** null or "" = no handle; undefined = invalid. */
function parseHandle(raw: unknown): string | null | undefined {
  if (raw === null || raw === "") return null;
  if (typeof raw !== "string") return undefined;
  return normalizeHandle(raw) ?? undefined;
}

/** Validates `{name, handle?, email?}`; returns an error message or the cleaned values. */
export function parseNewUser(
  body: unknown,
): { name: string; handle: string | null; email: string | null } | string {
  const { name: rawName, handle: rawHandle, email: rawEmail } = (body ?? {}) as Record<string, unknown>;
  if (typeof rawName !== "string") return "name is required";
  const name = parseName(rawName);
  if (name === null) return "invalid name";
  const handle = rawHandle === undefined ? null : parseHandle(rawHandle);
  if (handle === undefined) return "invalid handle";
  let email: string | null = null;
  if (rawEmail !== undefined && rawEmail !== null && rawEmail !== "") {
    if (typeof rawEmail !== "string") return "invalid email";
    email = rawEmail.trim().toLowerCase();
    if (email.length > MAX_EMAIL || !/^[^\s@]+@[^\s@]+$/.test(email)) return "invalid email";
  }
  return { name, handle, email };
}

/** Validates `{name?, handle?}` (handle null clears it); returns an error message or the changes. */
export function parseUserPatch(body: unknown): { name?: string; handle?: string | null } | string {
  const b = (body ?? {}) as Record<string, unknown>;
  const out: { name?: string; handle?: string | null } = {};
  if (b.name !== undefined) {
    const name = parseName(b.name);
    if (name === null) return "invalid name";
    out.name = name;
  }
  if (b.handle !== undefined) {
    const handle = parseHandle(b.handle);
    if (handle === undefined) return "invalid handle";
    out.handle = handle;
  }
  return out;
}

async function catchHandleTaken<T>(p: Promise<T>): Promise<T> {
  try {
    return await p;
  } catch (e) {
    if (e instanceof Error && /UNIQUE constraint failed: users\.handle/.test(e.message)) throw new HandleTaken();
    throw e;
  }
}

export async function createUser(
  db: D1Database,
  user: { name: string; handle: string | null; email: string | null },
  createdBy: string,
): Promise<UserView> {
  const row = await catchHandleTaken(
    db
      .prepare(
        `INSERT INTO users (id, name, handle, email, status, created_at, created_by)
         VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?6)
         RETURNING ${USER_COLUMNS}`,
      )
      .bind(crypto.randomUUID(), user.name, user.handle, user.email, Date.now(), createdBy)
      .first<UserRow>(),
  );
  return toView(row!);
}

/** Changes the name and/or handle. Returns null if there is no such user. */
export async function updateUser(
  db: D1Database,
  id: string,
  patch: { name?: string; handle?: string | null },
): Promise<UserView | null> {
  const row = await catchHandleTaken(
    db
      .prepare(
        `UPDATE users SET name = COALESCE(?2, name), handle = CASE WHEN ?3 THEN ?4 ELSE handle END
         WHERE id = ?1
         RETURNING ${USER_COLUMNS}`,
      )
      .bind(id, patch.name ?? null, patch.handle !== undefined ? 1 : 0, patch.handle ?? null)
      .first<UserRow>(),
  );
  return row ? toView(row) : null;
}

export async function getUser(db: D1Database, id: string): Promise<UserRow | null> {
  return db.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE id = ?1`).bind(id).first<UserRow>();
}

export async function listUsers(db: D1Database): Promise<UserListItem[]> {
  const { results } = await db
    .prepare(
      `SELECT u.id, u.name, u.handle, u.email, u.status, u.created_at, u.created_by, u.disabled_at,
         COUNT(d.id) - COUNT(d.revoked_at) AS device_count,
         MAX(d.last_seen_at) AS last_seen_at,
         (SELECT COALESCE(SUM(b.size), 0) FROM devices d2 JOIN batches b ON b.device_id = d2.id
          WHERE d2.user_id = u.id) AS storage_bytes
       FROM users u LEFT JOIN devices d ON d.user_id = u.id
       GROUP BY u.id ORDER BY u.created_at, u.id`,
    )
    .all<UserRow & { device_count: number; last_seen_at: number | null; storage_bytes: number }>();
  return results.map((r) => ({
    ...toView(r),
    deviceCount: r.device_count,
    lastSeenAt: r.last_seen_at,
    storageBytes: r.storage_bytes,
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
