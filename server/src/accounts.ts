// Self-registration, usage and the expiry of unused accounts.
//
// With REGISTRATION = "open", a device may create its own user without an
// admin (`POST /v1/accounts`); the server is then only an end-to-end
// encrypted backup and sync target, and further devices are paired from the
// first one as usual. With "admin" (the default, and for any other value),
// only admins create users. Self-registration stops once the server has
// MAX_USERS users.
//
// The per-user limits (see config.ts) apply in both modes. Usage is counted
// by triggers (migration 0005) in users.storage_bytes / batch_count /
// checkin_count, over all the user's devices, revoked ones included (their
// data is kept). Legacy devices (no user) share their data, so together they
// share one quota, counted in server_usage.legacy_*.
//
// A self-registered user that has stored nothing (no batch, no shared
// check-in) UNUSED_ACCOUNT_TTL_MS after its creation is deleted by the
// scheduled sweep, with its devices. Users with data are never deleted
// automatically.

import { isUniqueTokenError } from "./devices";
import { floorHour, sha256Hex } from "./util";

export { registrationMode, type RegistrationMode } from "./config";

/** `created_by` of self-registered users. Admin-created users have an e-mail address there. */
export const SELF_REGISTERED = "self";

export const UNUSED_ACCOUNT_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export type CreateAccountResult = { deviceId: string; created: boolean } | "full" | "token in use";

/**
 * Creates a user with its first device, which authenticates with the
 * client-generated `token` (only its hash is stored). One batch: the user
 * never exists without the device. Idempotent per token: if the token
 * already belongs to the first device of a self-registered user, that
 * device's id is returned (`created: false`).
 */
export async function createAccount(
  db: D1Database,
  a: { token: string; name: string; maxUsers: number },
): Promise<CreateAccountResult> {
  const tokenHash = await sha256Hex(a.token);

  const earlier = async (): Promise<CreateAccountResult | null> => {
    // The first device was created together with its user, at the same time.
    const row = await db
      .prepare(
        `SELECT d.id, d.revoked_at, d.created_at, u.created_at AS user_created_at, u.created_by
         FROM devices d LEFT JOIN users u ON u.id = d.user_id WHERE d.token_hash = ?1`,
      )
      .bind(tokenHash)
      .first<{ id: string; revoked_at: number | null; created_at: number; user_created_at: number | null; created_by: string | null }>();
    if (!row) return null;
    const same = row.created_by === SELF_REGISTERED && row.user_created_at === row.created_at && row.revoked_at === null;
    return same ? { deviceId: row.id, created: false } : "token in use";
  };

  const prior = await earlier();
  if (prior) return prior;

  const now = Date.now();
  const userId = crypto.randomUUID();
  const deviceId = crypto.randomUUID();
  let device: D1Result;
  try {
    [, device] = (await db.batch([
      // users_count_insert (trigger) counts the user in server_usage.
      db
        .prepare(
          `INSERT INTO users (id, name, handle, email, status, created_at, created_by)
           SELECT ?1, ?2, NULL, NULL, 'active', ?3, ?4
           WHERE (SELECT user_count FROM server_usage WHERE id = 1) < ?5`,
        )
        .bind(userId, a.name, now, SELF_REGISTERED, a.maxUsers),
      db
        .prepare(
          `INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id)
           SELECT ?1, ?2, ?3, ?4, ?5, id FROM users WHERE id = ?6`,
        )
        .bind(deviceId, a.name, tokenHash, now, floorHour(now), userId),
    ])) as [D1Result, D1Result];
  } catch (e) {
    // A concurrent request with the same token won; this batch rolled back.
    if (isUniqueTokenError(e)) return (await earlier()) ?? "token in use";
    throw e;
  }
  if (device.meta.changes > 0) return { deviceId, created: true };
  // Nothing inserted (the server is at MAX_USERS). A concurrent request with
  // the same token may have created the account after our first lookup (and
  // with it reached the limit): then this is a retry of that request.
  return (await earlier()) ?? "full";
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
