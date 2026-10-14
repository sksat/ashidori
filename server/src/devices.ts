import type { DeviceRow } from "./env";
import { D1_NOW_MS, floorHour, generatePairingCode, sha256Hex } from "./util";

export const PAIRING_TTL_MS = 10 * 60 * 1000;
/** Used and expired codes are kept this long after expiry (registration retries, auditing). */
export const PAIRING_KEEP_MS = 24 * 60 * 60 * 1000;
export const MAX_DEVICE_NAME = 64;

export interface DeviceView {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number | null;
  revoked: boolean;
}

/** Admin view: every device with the user it belongs to (null for legacy devices). */
export interface AdminDeviceView extends DeviceView {
  userId: string | null;
  /** The user's username (null for legacy devices, or a legacy user without one). */
  username: string | null;
}

/**
 * Which devices a caller may see and revoke: those of one user (`null` = the
 * legacy devices registered before accounts existed), or all (admins).
 */
export type DeviceScope = { userId: string | null } | "all";

/**
 * `createdBy` is "admin:<email>" or the id of the device that created the
 * code; `issuerDeviceId` is that device id (null for admins). The code is
 * only accepted while its issuing device is not revoked. The device
 * registered with the code belongs to `userId` (null only when a legacy
 * device creates a code).
 */
export async function createPairingCode(
  db: D1Database,
  createdBy: string,
  userId: string | null,
  issuerDeviceId: string | null,
): Promise<{ code: string; expiresAt: number }> {
  const code = generatePairingCode();
  // Created and expiring by D1's clock, the clock that registration checks
  // the expiry against (CODE_USABLE).
  const row = await db
    .prepare(
      `INSERT INTO pairing_codes (code_hash, created_at, expires_at, created_by, user_id, issuer_device_id)
       SELECT ?1, n, n + ?2, ?3, ?4, ?5 FROM (SELECT ${D1_NOW_MS} AS n)
       RETURNING expires_at`,
    )
    .bind(await sha256Hex(code), PAIRING_TTL_MS, createdBy, userId, issuerDeviceId)
    .first<{ expires_at: number }>();
  if (!row) throw new Error("pairing code not stored");
  return { code, expiresAt: row.expires_at };
}

/** Scheduled: deletes codes that expired more than PAIRING_KEEP_MS ago, at most `limit`. */
export async function deleteOldPairingCodes(db: D1Database, now: number, limit = 1000): Promise<number> {
  const res = await db
    .prepare(
      "DELETE FROM pairing_codes WHERE code_hash IN (SELECT code_hash FROM pairing_codes WHERE expires_at < ?1 LIMIT ?2)",
    )
    .bind(now - PAIRING_KEEP_MS, limit)
    .run();
  return res.meta.changes;
}

export function isUniqueTokenError(e: unknown): boolean {
  return e instanceof Error && /UNIQUE constraint failed: devices\.token_hash/.test(e.message);
}

export type RegisterResult = { deviceId: string; created: boolean } | "invalid" | "too many devices";

/** `created_by` of a code issued by an admin: "admin:<email>", or "admin" (before migration 0002). */
const ADMIN_ISSUED = "(created_by = 'admin' OR created_by LIKE 'admin:%')";

/**
 * The device that issued a code, NULL only for codes issued by an admin.
 * `issuer_device_id` is NULL also for a code that a Worker older than
 * migration 0005 inserted (while the migration and the deploy overlap), if
 * the trigger of 0005 did not fill it in; such a code names its issuing
 * device in `created_by`. NULL alone therefore never means "admin".
 */
const ISSUER = `COALESCE(issuer_device_id, CASE WHEN ${ADMIN_ISSUED} THEN NULL ELSE created_by END)`;

// A code is usable if unused, unexpired, of an active user (or legacy), and
// its issuing device (unless an admin issued it) exists and is not revoked.
// The expiry is judged by D1's clock when the statement runs, not by a time
// the Worker read before: a request delayed between the two must not consume
// a code that has expired meanwhile (review 6, item 7).
const CODE_USABLE = `used_at IS NULL AND expires_at > ${D1_NOW_MS}
  AND (user_id IS NULL OR user_id IN (SELECT id FROM users WHERE status = 'active'))
  AND (CASE WHEN ${ISSUER} IS NULL THEN ${ADMIN_ISSUED}
       ELSE ${ISSUER} IN (SELECT id FROM devices WHERE revoked_at IS NULL) END)`;
// Devices ever registered for the code's user (revoked ones included); `IS` so that NULL counts the legacy devices.
const USER_DEVICES = "(SELECT COUNT(*) FROM devices WHERE user_id IS pairing_codes.user_id)";

/**
 * What an earlier request with this token got: null if no device has the
 * token; its device if that device consumed this code and is not revoked;
 * else "invalid".
 */
async function registeredWith(db: D1Database, tokenHash: string, codeHash: string): Promise<RegisterResult | null> {
  const row = await db
    .prepare(
      `SELECT d.id, d.revoked_at, p.code_hash AS matched FROM devices d
       LEFT JOIN pairing_codes p ON p.used_by = d.id AND p.code_hash = ?2
       WHERE d.token_hash = ?1`,
    )
    .bind(tokenHash, codeHash)
    .first<{ id: string; revoked_at: number | null; matched: string | null }>();
  if (!row) return null;
  return row.matched !== null && row.revoked_at === null ? { deviceId: row.id, created: false } : "invalid";
}

/**
 * Result recovery, separate from the permission to create a device: the id
 * of the device that an earlier request with this code and token created
 * (not revoked), or null. Changes nothing, so the route answers it before
 * any gate (rate limits, limit variables): a client whose response got lost
 * gets its device back whatever has changed since, as long as the device and
 * the used code exist.
 */
export async function recoverRegistration(db: D1Database, code: string, token: string): Promise<string | null> {
  const r = await registeredWith(db, await sha256Hex(token), await sha256Hex(code));
  return r !== null && typeof r === "object" ? r.deviceId : null;
}

/**
 * Registers a device with a pairing code and a client-generated token (only
 * its hash is stored).
 *
 * Idempotent per token: if a device with this token already exists and was
 * registered with this very code, its id is returned (`created: false`), so a
 * client whose response got lost can retry. A used code without that token
 * gives nothing ("invalid"), and so does a known token with another code.
 *
 * Consuming the code and creating the device are one D1 batch (one
 * transaction): either both happen or neither.
 */
export async function registerWithCode(
  db: D1Database,
  r: { code: string; name: string; token: string; maxDevices: number },
): Promise<RegisterResult> {
  const codeHash = await sha256Hex(r.code);
  const tokenHash = await sha256Hex(r.token);
  const earlier = () => registeredWith(db, tokenHash, codeHash);

  const prior = await earlier();
  if (prior) return prior;

  const now = Date.now();
  const deviceId = crypto.randomUUID();
  let consumed: D1Result;
  try {
    [consumed] = (await db.batch([
      db
        .prepare(
          `UPDATE pairing_codes SET used_at = ?2, used_by = ?3
           WHERE code_hash = ?1 AND ${CODE_USABLE} AND ${USER_DEVICES} < ?4
           RETURNING user_id`,
        )
        .bind(codeHash, now, deviceId, r.maxDevices),
      // The device belongs to the code's user (null only for legacy codes).
      // Inserted only if the UPDATE above consumed the code for this device.
      db
        .prepare(
          `INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id)
           SELECT ?1, ?2, ?3, ?4, ?5, user_id FROM pairing_codes WHERE code_hash = ?6 AND used_by = ?1`,
        )
        .bind(deviceId, r.name, tokenHash, now, floorHour(now), codeHash),
    ])) as [D1Result];
  } catch (e) {
    // A concurrent request with the same token won; this batch rolled back.
    if (isUniqueTokenError(e)) return (await earlier()) ?? "invalid";
    throw e;
  }
  if (consumed.results.length > 0) return { deviceId, created: true };

  // Not consumed. A concurrent request with the same token and code may have
  // consumed the code after our first lookup: then this is a retry of that
  // registration, not an error.
  const raced = await earlier();
  if (raced) return raced;

  // Not consumed. Only to a caller who holds a usable code, tell that the user has too many devices.
  const full = await db
    .prepare(`SELECT 1 FROM pairing_codes WHERE code_hash = ?1 AND ${CODE_USABLE} AND ${USER_DEVICES} >= ?2`)
    .bind(codeHash, r.maxDevices)
    .first();
  return full ? "too many devices" : "invalid";
}

/** Trims a device name; null if it is empty, too long or has control characters. */
export function parseDeviceName(raw: string): string | null {
  const name = raw.trim();
  if (name.length === 0 || name.length > MAX_DEVICE_NAME || /[\u0000-\u001f\u007f]/.test(name)) return null;
  return name;
}

function toView(r: Omit<DeviceRow, "token_hash" | "user_id">): DeviceView {
  return {
    id: r.id,
    name: r.name,
    createdAt: r.created_at,
    lastSeenAt: r.last_seen_at,
    revoked: r.revoked_at !== null,
  };
}

/** Devices of one user; `IS` so that null selects the legacy devices. */
export async function listDevices(db: D1Database, userId: string | null): Promise<DeviceView[]> {
  const { results } = await db
    .prepare(
      "SELECT id, name, created_at, last_seen_at, revoked_at FROM devices WHERE user_id IS ?1 ORDER BY created_at, id",
    )
    .bind(userId)
    .all<Omit<DeviceRow, "token_hash" | "user_id">>();
  return results.map(toView);
}

export async function listAllDevices(db: D1Database): Promise<AdminDeviceView[]> {
  const { results } = await db
    .prepare(
      `SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked_at, d.user_id, u.handle AS username
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
       ORDER BY d.created_at, d.id`,
    )
    .all<Omit<DeviceRow, "token_hash"> & { username: string | null }>();
  return results.map((r) => ({ ...toView(r), userId: r.user_id, username: r.username }));
}

/**
 * Revokes a device and, in the same batch (one transaction), deletes the
 * unused pairing codes it issued. Returns false if the device does not exist
 * in `scope`. Revoking twice is a no-op.
 */
export async function revokeDevice(db: D1Database, id: string, scope: DeviceScope): Promise<boolean> {
  const now = Date.now();
  const stmts =
    scope === "all"
      ? [
          db.prepare(`DELETE FROM pairing_codes WHERE ${ISSUER} = ?1 AND used_at IS NULL`).bind(id),
          db.prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?2) WHERE id = ?1").bind(id, now),
        ]
      : [
          db
            .prepare(
              `DELETE FROM pairing_codes WHERE ${ISSUER} = ?1 AND used_at IS NULL
                 AND EXISTS (SELECT 1 FROM devices WHERE id = ?1 AND user_id IS ?2)`,
            )
            .bind(id, scope.userId),
          db
            .prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?2) WHERE id = ?1 AND user_id IS ?3")
            .bind(id, now, scope.userId),
        ];
  const [, revoked] = await db.batch(stmts);
  return revoked!.meta.changes > 0;
}
