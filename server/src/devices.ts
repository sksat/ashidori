import type { DeviceRow } from "./env";
import { generatePairingCode, sha256Hex } from "./util";

export const PAIRING_TTL_MS = 10 * 60 * 1000;
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
  userName: string | null;
}

/**
 * Which devices a caller may see and revoke: those of one user (`null` = the
 * legacy devices registered before accounts existed), or all (admins).
 */
export type DeviceScope = { userId: string | null } | "all";

/**
 * `createdBy` is "admin:<email>" or the id of the device that created the code.
 * The device registered with the code belongs to `userId` (null only when a
 * legacy device creates a code).
 */
export async function createPairingCode(
  db: D1Database,
  createdBy: string,
  userId: string | null,
): Promise<{ code: string; expiresAt: number }> {
  const now = Date.now();
  const code = generatePairingCode();
  const expiresAt = now + PAIRING_TTL_MS;
  await db.batch([
    // Housekeeping: expired codes are useless; keep used ones for a day for auditing.
    db.prepare("DELETE FROM pairing_codes WHERE expires_at < ?1").bind(now - 24 * 60 * 60 * 1000),
    db
      .prepare(
        "INSERT INTO pairing_codes (code_hash, created_at, expires_at, created_by, user_id) VALUES (?1, ?2, ?3, ?4, ?5)",
      )
      .bind(await sha256Hex(code), now, expiresAt, createdBy, userId),
  ]);
  return { code, expiresAt };
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
      `SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked_at, d.user_id, u.name AS user_name
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
       ORDER BY d.created_at, d.id`,
    )
    .all<Omit<DeviceRow, "token_hash"> & { user_name: string | null }>();
  return results.map((r) => ({ ...toView(r), userId: r.user_id, userName: r.user_name }));
}

/** Returns false if the device does not exist in `scope`. Revoking twice is a no-op. */
export async function revokeDevice(db: D1Database, id: string, scope: DeviceScope): Promise<boolean> {
  const stmt =
    scope === "all"
      ? db.prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?2) WHERE id = ?1").bind(id, Date.now())
      : db
          .prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?2) WHERE id = ?1 AND user_id IS ?3")
          .bind(id, Date.now(), scope.userId);
  const res = await stmt.run();
  return res.meta.changes > 0;
}
