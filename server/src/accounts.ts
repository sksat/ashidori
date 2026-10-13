// Self-registration and per-user storage quota.
//
// With REGISTRATION = "open", a device may create its own user without an
// admin (`POST /v1/accounts`); the server is then only an end-to-end
// encrypted backup and sync target, and further devices are paired from the
// first one as usual. With "admin" (the default, and for any other value),
// only admins create users.
//
// The quota (USER_QUOTA_BYTES) applies in both modes, so that open
// registration cannot be used for unlimited storage. It counts the blob bytes
// of all the user's devices, revoked ones included (their blobs are kept).
// Legacy devices (no user) share their data, so together they share one quota.

import type { Env } from "./env";

export type RegistrationMode = "admin" | "open";

/** `created_by` of self-registered users. Admin-created users have an e-mail address there. */
export const SELF_REGISTERED = "self";

export const DEFAULT_USER_QUOTA_BYTES = 2 * 1024 * 1024 * 1024;

/** Fails closed: only exactly "open" (case and spaces ignored) opens registration. */
export function registrationMode(env: Pick<Env, "REGISTRATION">): RegistrationMode {
  return (env.REGISTRATION ?? "").trim().toLowerCase() === "open" ? "open" : "admin";
}

/** USER_QUOTA_BYTES as a positive integer; empty or invalid = DEFAULT_USER_QUOTA_BYTES. */
export function userQuotaBytes(env: Pick<Env, "USER_QUOTA_BYTES">): number {
  const raw = (env.USER_QUOTA_BYTES ?? "").trim();
  if (!/^[0-9]{1,16}$/.test(raw)) return DEFAULT_USER_QUOTA_BYTES;
  const n = Number(raw);
  return Number.isSafeInteger(n) && n > 0 ? n : DEFAULT_USER_QUOTA_BYTES;
}

/** Total blob bytes of a user's devices (`null` = the legacy devices). */
export async function storageUsed(db: D1Database, userId: string | null): Promise<number> {
  const row = await db
    .prepare(
      `SELECT COALESCE(SUM(b.size), 0) AS used
       FROM devices d JOIN batches b ON b.device_id = d.id
       WHERE d.user_id IS ?1`,
    )
    .bind(userId)
    .first<{ used: number }>();
  return row?.used ?? 0;
}
