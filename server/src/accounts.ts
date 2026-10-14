// Account creation, usage and the expiry of unused accounts.
//
// Every user is created by their first app device (`POST /v1/accounts`),
// together with that device, under the username the person chose there:
// - with an invite from an admin (invites.ts), in any REGISTRATION mode. The
//   invite is consumed in the same transaction. MAX_USERS does not apply: the
//   admin decided to invite.
// - without one only when REGISTRATION = "open" (self-registration); the
//   server is then only an end-to-end encrypted backup and sync target. It
//   stops once the server has MAX_USERS users.
// Further devices are paired from the first one as usual.
//
// The per-user limits (see config.ts) apply in both modes. Usage is counted
// by triggers (migration 0005) in users.storage_bytes / batch_count /
// checkin_count, over all the user's devices, revoked ones included (their
// data is kept). Legacy devices (no user) share their data, so together they
// share one quota, counted in server_usage.legacy_*.
//
// A self-registered user (no invite) that has stored nothing (no batch, no
// shared check-in) UNUSED_ACCOUNT_TTL_MS after its creation is deleted by the
// scheduled sweep, with its devices. Invited users and users with data are
// never deleted automatically.

import { isUniqueTokenError } from "./devices";
import { INVITE_USABLE } from "./invites";
import { isUsernameTakenError } from "./users";
import { floorHour, sha256Hex } from "./util";

export { registrationMode, type RegistrationMode } from "./config";

/** `created_by` of self-registered users (no invite). */
export const SELF_REGISTERED = "self";
/** `created_by` of invited users: this prefix and the invite's id. Legacy admin-created users have an e-mail address there. */
export const INVITED_PREFIX = "invite:";

export const UNUSED_ACCOUNT_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface Account {
  deviceId: string;
  /** The user's username now (an admin may have changed it since). */
  username: string | null;
}

export type CreateAccountResult =
  | (Account & { created: boolean })
  | "full"
  | "token in use"
  | "invalid invite"
  | "username taken";

/**
 * What an earlier request with this token and invite (null: none) got: null
 * if no device has the token; the account if the token's device is the first
 * device of a user created by such a request (with this invite, or
 * self-registered without one) and is not revoked; else "token in use".
 */
async function accountOf(
  db: D1Database,
  tokenHash: string,
  inviteHash: string | null,
): Promise<(Account & { created: false }) | "token in use" | null> {
  // The first device was created together with its user, at the same time.
  const row = await db
    .prepare(
      `SELECT d.id, d.revoked_at, d.created_at, u.created_at AS user_created_at, u.created_by, u.handle,
         EXISTS (SELECT 1 FROM invites i WHERE i.used_by_user = d.user_id AND i.code_hash = ?2) AS invited
       FROM devices d LEFT JOIN users u ON u.id = d.user_id WHERE d.token_hash = ?1`,
    )
    .bind(tokenHash, inviteHash)
    .first<{
      id: string;
      revoked_at: number | null;
      created_at: number;
      user_created_at: number | null;
      created_by: string | null;
      handle: string | null;
      invited: number;
    }>();
  if (!row) return null;
  const first = row.user_created_at === row.created_at && row.revoked_at === null;
  const same = inviteHash === null ? row.created_by === SELF_REGISTERED : row.invited === 1;
  return first && same ? { deviceId: row.id, username: row.handle, created: false } : "token in use";
}

/**
 * Result recovery, separate from the permission to create a user: the
 * account that an earlier request with this token and invite (null: none)
 * created (its first device, not revoked), or null. Changes nothing, so the
 * route answers it before any gate (REGISTRATION, rate limits, MAX_USERS,
 * limit variables) and before it validates the username: a client whose
 * response got lost gets its account back even if the operator closed
 * registration meanwhile.
 */
export async function recoverAccount(db: D1Database, token: string, invite: string | null): Promise<Account | null> {
  const r = await accountOf(db, await sha256Hex(token), invite === null ? null : await sha256Hex(invite));
  return r !== null && typeof r === "object" ? { deviceId: r.deviceId, username: r.username } : null;
}

/**
 * Creates a user named `username` with its first device, which authenticates
 * with the client-generated `token` (only its hash is stored). With `invite`,
 * consumes it. One batch (one transaction): the invite is consumed, the user
 * created and the device created all together or not at all; the user never
 * exists without the device, and a username that another user has (UNIQUE,
 * the batch rolls back) consumes nothing.
 *
 * Idempotent per token: if the token already belongs to the first device of
 * a user created by the same request, that account is returned (`created:
 * false`), also when the request lost a race to a concurrent copy of itself.
 * An invite used by another request gives "invalid invite", like an unknown,
 * expired or revoked one.
 */
export async function createAccount(
  db: D1Database,
  a: { token: string; invite: string | null; username: string; name: string; maxUsers: number },
): Promise<CreateAccountResult> {
  const tokenHash = await sha256Hex(a.token);
  const inviteHash = a.invite === null ? null : await sha256Hex(a.invite);
  const earlier = () => accountOf(db, tokenHash, inviteHash);

  const prior = await earlier();
  if (prior) return prior;

  const now = Date.now();
  const userId = crypto.randomUUID();
  const deviceId = crypto.randomUUID();
  // users.name is NOT NULL (and read by older Workers): the username.
  // users_count_insert (trigger) counts the user in server_usage.
  const stmts =
    inviteHash === null
      ? [
          db
            .prepare(
              `INSERT INTO users (id, name, handle, email, status, created_at, created_by)
               SELECT ?1, ?2, ?2, NULL, 'active', ?3, ?4
               WHERE (SELECT user_count FROM server_usage WHERE id = 1) < ?5`,
            )
            .bind(userId, a.username, now, SELF_REGISTERED, a.maxUsers),
        ]
      : [
          // The expiry is judged by D1's clock when the statement runs (INVITE_USABLE).
          db
            .prepare(`UPDATE invites SET used_by_user = ?2, used_at = ?3 WHERE code_hash = ?1 AND ${INVITE_USABLE}`)
            .bind(inviteHash, userId, now),
          // Only if the UPDATE above consumed the invite for this user.
          db
            .prepare(
              `INSERT INTO users (id, name, handle, email, status, created_at, created_by)
               SELECT ?1, ?2, ?2, NULL, 'active', ?3, ?4 || id FROM invites WHERE code_hash = ?5 AND used_by_user = ?1`,
            )
            .bind(userId, a.username, now, INVITED_PREFIX, inviteHash),
        ];
  stmts.push(
    db
      .prepare(
        `INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id)
         SELECT ?1, ?2, ?3, ?4, ?5, id FROM users WHERE id = ?6`,
      )
      .bind(deviceId, a.name, tokenHash, now, floorHour(now), userId),
  );
  let results: D1Result[];
  try {
    results = await db.batch(stmts);
  } catch (e) {
    // The batch rolled back. A concurrent copy of this request (same token)
    // may have won the token or the username: then this is a retry of it.
    if (isUniqueTokenError(e)) return (await earlier()) ?? "token in use";
    if (isUsernameTakenError(e)) return (await earlier()) ?? "username taken";
    throw e;
  }
  if (results[results.length - 1]!.meta.changes > 0) return { deviceId, username: a.username, created: true };
  // Nothing inserted: the invite is not usable (unknown, used, expired,
  // revoked), or without one the server is at MAX_USERS. A concurrent copy
  // with the same token may have done it after our first lookup (and used the
  // invite, or taken the last place): then this is a retry of that request.
  return (await earlier()) ?? (inviteHash === null ? "full" : "invalid invite");
}

// The recheck in every DELETE, so that a user who stored something meanwhile is kept.
const UNUSED = `EXISTS (SELECT 1 FROM users WHERE id = ?1 AND created_by = 'self'
  AND batch_count = 0 AND checkin_count = 0 AND created_at < ?2)`;

/**
 * Scheduled: deletes self-registered users that have stored nothing
 * UNUSED_ACCOUNT_TTL_MS after creation, with their devices, pairing codes and
 * follows. At most `limit` users per run. Returns how many were deleted.
 */
export async function sweepUnusedAccounts(db: D1Database, now: number, limit = 50): Promise<number> {
  const cutoff = now - UNUSED_ACCOUNT_TTL_MS;
  const { results } = await db
    .prepare(
      `SELECT id FROM users WHERE created_by = 'self' AND batch_count = 0 AND checkin_count = 0
         AND created_at < ?1 LIMIT ?2`,
    )
    .bind(cutoff, limit)
    .all<{ id: string }>();
  let deleted = 0;
  for (const { id } of results) {
    // One batch (one transaction) per user.
    const res = await db.batch([
      db.prepare(`DELETE FROM follows WHERE (follower_id = ?1 OR followee_id = ?1) AND ${UNUSED}`).bind(id, cutoff),
      db.prepare(`DELETE FROM pairing_codes WHERE user_id = ?1 AND ${UNUSED}`).bind(id, cutoff),
      db.prepare(`DELETE FROM devices WHERE user_id = ?1 AND ${UNUSED}`).bind(id, cutoff),
      db.prepare(`DELETE FROM users WHERE id = ?1 AND created_by = 'self' AND batch_count = 0 AND checkin_count = 0 AND created_at < ?2`).bind(id, cutoff),
    ]);
    deleted += res[3]!.meta.changes;
  }
  return deleted;
}

export interface Usage {
  storageBytes: number;
  batchCount: number;
  totalBytes: number;
  /** False if the device was deleted or revoked meanwhile. */
  deviceActive: boolean;
}

/** Current usage of a user (`null` = the legacy devices) and of the whole server. */
export async function readUsage(db: D1Database, userId: string | null, deviceId: string): Promise<Usage> {
  const row = await db
    .prepare(
      `SELECT
         CASE WHEN ?1 IS NULL THEN s.legacy_storage_bytes ELSE (SELECT storage_bytes FROM users WHERE id = ?1) END AS storage_bytes,
         CASE WHEN ?1 IS NULL THEN s.legacy_batch_count ELSE (SELECT batch_count FROM users WHERE id = ?1) END AS batch_count,
         s.total_bytes,
         EXISTS (SELECT 1 FROM devices WHERE id = ?2 AND revoked_at IS NULL) AS device_active
       FROM server_usage s WHERE s.id = 1`,
    )
    .bind(userId, deviceId)
    .first<{ storage_bytes: number | null; batch_count: number | null; total_bytes: number; device_active: number }>();
  return {
    storageBytes: row?.storage_bytes ?? 0,
    batchCount: row?.batch_count ?? 0,
    totalBytes: row?.total_bytes ?? 0,
    deviceActive: (row?.device_active ?? 0) === 1,
  };
}

export async function serverUsage(db: D1Database): Promise<{ userCount: number; totalBytes: number }> {
  const row = await db
    .prepare("SELECT user_count, total_bytes FROM server_usage WHERE id = 1")
    .first<{ user_count: number; total_bytes: number }>();
  return { userCount: row?.user_count ?? 0, totalBytes: row?.total_bytes ?? 0 };
}
