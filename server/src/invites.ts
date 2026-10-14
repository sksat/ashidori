// Invites: how an admin lets a person register (docs/pairing.md).
//
// An admin creates an invite: a single-use registration link with an expiry,
// https://<server>/pair#i=<CODE>. The person opens it on their first app
// device, chooses a username there, and POST /v1/accounts with the invite
// creates the user and that device and consumes the invite, in one D1
// transaction (accounts.ts). Nothing exists for the person before that: an
// invite is not a user.
//
// Only the SHA-256 of the code is stored; the code is shown once, when the
// invite is created. The expiry is set and judged by D1's clock, like that of
// a pairing code. An unused invite can be revoked; a used one keeps the user
// it created (used_by_user), which a registration retry is recovered from.

import { D1_NOW_MS, generateInviteCode, sha256Hex } from "./util";

export const DAY_MS = 24 * 60 * 60 * 1000;
export const INVITE_DEFAULT_DAYS = 7;
export const INVITE_MAX_DAYS = 30;
/** Unused invites are deleted this long after they expired or were revoked; used ones are kept. */
export const INVITE_KEEP_MS = 30 * DAY_MS;

export type InviteStatus = "pending" | "used" | "expired" | "revoked";

export interface InviteView {
  id: string;
  createdBy: string;
  createdAt: number;
  expiresAt: number;
  status: InviteStatus;
  usedAt: number | null;
  /** The user it created, once used. */
  user: { id: string; username: string | null } | null;
  revokedAt: number | null;
}

/** An invite is usable while unused, not revoked and not expired, by D1's clock when the statement runs. */
export const INVITE_USABLE = `used_by_user IS NULL AND revoked_at IS NULL AND expires_at > ${D1_NOW_MS}`;

/** `ttlDays`: 1..INVITE_MAX_DAYS (an integer), default INVITE_DEFAULT_DAYS; null if invalid. */
export function parseInviteDays(body: unknown): number | null {
  const { ttlDays } = (body ?? {}) as Record<string, unknown>;
  if (ttlDays === undefined || ttlDays === null) return INVITE_DEFAULT_DAYS;
  if (typeof ttlDays !== "number" || !Number.isInteger(ttlDays) || ttlDays < 1 || ttlDays > INVITE_MAX_DAYS) return null;
  return ttlDays;
}

export async function createInvite(
  db: D1Database,
  createdBy: string,
  days: number,
): Promise<{ id: string; code: string; expiresAt: number }> {
  const code = generateInviteCode();
  const row = await db
    .prepare(
      `INSERT INTO invites (id, code_hash, created_by, created_at, expires_at)
       SELECT ?1, ?2, ?3, n, n + ?4 FROM (SELECT ${D1_NOW_MS} AS n)
       RETURNING id, expires_at`,
    )
    .bind(crypto.randomUUID(), await sha256Hex(code), createdBy, days * DAY_MS)
    .first<{ id: string; expires_at: number }>();
  if (!row) throw new Error("invite not stored");
  return { id: row.id, code, expiresAt: row.expires_at };
}

/** The newest `limit` invites, with their status by D1's clock. */
export async function listInvites(db: D1Database, limit = 200): Promise<InviteView[]> {
  const { results } = await db
    .prepare(
      `SELECT i.id, i.created_by, i.created_at, i.expires_at, i.used_at, i.used_by_user, i.revoked_at,
         u.handle AS username,
         CASE WHEN i.used_by_user IS NOT NULL THEN 'used'
              WHEN i.revoked_at IS NOT NULL THEN 'revoked'
              WHEN i.expires_at <= ${D1_NOW_MS} THEN 'expired'
              ELSE 'pending' END AS status
       FROM invites i LEFT JOIN users u ON u.id = i.used_by_user
       ORDER BY i.created_at DESC, i.id LIMIT ?1`,
    )
    .bind(limit)
    .all<{
      id: string;
      created_by: string;
      created_at: number;
      expires_at: number;
      used_at: number | null;
      used_by_user: string | null;
      revoked_at: number | null;
      username: string | null;
      status: InviteStatus;
    }>();
  return results.map((r) => ({
    id: r.id,
    createdBy: r.created_by,
    createdAt: r.created_at,
    expiresAt: r.expires_at,
    status: r.status,
    usedAt: r.used_at,
    user: r.used_by_user === null ? null : { id: r.used_by_user, username: r.username },
    revokedAt: r.revoked_at,
  }));
}

/**
 * Revokes an unused invite; revoking twice is a no-op. "used": it already
 * created its user (nothing changes); null: no such invite.
 */
export async function revokeInvite(db: D1Database, id: string): Promise<"revoked" | "used" | null> {
  const row = await db
    .prepare(
      `UPDATE invites SET revoked_at = COALESCE(revoked_at, ${D1_NOW_MS})
       WHERE id = ?1 AND used_by_user IS NULL
       RETURNING id`,
    )
    .bind(id)
    .first();
  if (row) return "revoked";
  const used = await db.prepare("SELECT 1 FROM invites WHERE id = ?1").bind(id).first();
  return used ? "used" : null;
}

/** Scheduled: deletes unused invites INVITE_KEEP_MS after they expired or were revoked, at most `limit`. */
export async function deleteOldInvites(db: D1Database, now: number, limit = 1000): Promise<number> {
  const res = await db
    .prepare(
      `DELETE FROM invites WHERE id IN (SELECT id FROM invites
         WHERE used_by_user IS NULL AND (expires_at < ?1 OR revoked_at < ?1) LIMIT ?2)`,
    )
    .bind(now - INVITE_KEEP_MS, limit)
    .run();
  return res.meta.changes;
}
