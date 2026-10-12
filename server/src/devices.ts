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

export async function createPairingCode(
  db: D1Database,
  createdBy: string,
): Promise<{ code: string; expiresAt: number }> {
  const now = Date.now();
  const code = generatePairingCode();
  const expiresAt = now + PAIRING_TTL_MS;
  await db.batch([
    // Housekeeping: expired codes are useless; keep used ones for a day for auditing.
    db.prepare("DELETE FROM pairing_codes WHERE expires_at < ?1").bind(now - 24 * 60 * 60 * 1000),
    db
      .prepare("INSERT INTO pairing_codes (code_hash, created_at, expires_at, created_by) VALUES (?1, ?2, ?3, ?4)")
      .bind(await sha256Hex(code), now, expiresAt, createdBy),
  ]);
  return { code, expiresAt };
}

export async function listDevices(db: D1Database): Promise<DeviceView[]> {
  const { results } = await db
    .prepare("SELECT id, name, created_at, last_seen_at, revoked_at FROM devices ORDER BY created_at, id")
    .all<Omit<DeviceRow, "token_hash">>();
  return results.map((r) => ({
    id: r.id,
    name: r.name,
    createdAt: r.created_at,
    lastSeenAt: r.last_seen_at,
    revoked: r.revoked_at !== null,
  }));
}

/** Returns false if the device does not exist. Revoking twice is a no-op. */
export async function revokeDevice(db: D1Database, id: string): Promise<boolean> {
  const res = await db
    .prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?2) WHERE id = ?1")
    .bind(id, Date.now())
    .run();
  return res.meta.changes > 0;
}
