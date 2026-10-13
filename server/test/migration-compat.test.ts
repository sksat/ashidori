// The deploy workflow applies migrations before it deploys, so the Worker of
// migration 0003 keeps serving for a while on the schema of 0005 (and again
// after a rollback). These tests write rows the way that Worker does (its SQL,
// copied verbatim) after the migrations, and check that it keeps working and
// that the current Worker handles its rows safely.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { deleteExpiredRateLimits, hitRateLimit, RULES } from "../src/ratelimit";
import { generatePairingCode, sha256Hex } from "../src/util";
import { defaultUserId, issueAdminCode, newDevice, postJson, register } from "./helpers";

const HOUR = 3_600_000;
const TEN_MIN = 10 * 60 * 1000;

/** The rate-limit statement of the older Worker (no expires_at). */
function oldHit(key: string, windowStart: number): Promise<{ count: number } | null> {
  return env.DB.prepare(
    `INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 1)
     ON CONFLICT (key) DO UPDATE SET
       count = CASE WHEN window_start = excluded.window_start THEN count + 1 ELSE 1 END,
       window_start = excluded.window_start
     RETURNING count`,
  )
    .bind(key, windowStart)
    .first<{ count: number }>();
}

/** The pairing-code insert of the older Worker (no issuer_device_id). Returns the code. */
async function oldCode(createdBy: string, userId: string | null): Promise<string> {
  const code = generatePairingCode();
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO pairing_codes (code_hash, created_at, expires_at, created_by, user_id) VALUES (?1, ?2, ?3, ?4, ?5)",
  )
    .bind(await sha256Hex(code), now, now + TEN_MIN, createdBy, userId)
    .run();
  return code;
}

const expiresAt = async (key: string) =>
  (await env.DB.prepare("SELECT expires_at FROM rate_limits WHERE key = ?1").bind(key).first<{ expires_at: number }>())
    ?.expires_at;

describe("rate-limit counters of the older Worker", () => {
  it("can be written, and get an expiry that outlasts their window", async () => {
    const now = Date.now();
    const w = now - (now % TEN_MIN);
    expect(await oldHit("register:192.0.2.1", w)).toEqual({ count: 1 });
    expect(await oldHit("register:192.0.2.1", w)).toEqual({ count: 2 });
    expect(await expiresAt("register:192.0.2.1")).toBe(w + HOUR);
    // The older Worker moves window_start to later windows (without expiry).
    // In every window, the cleanup keeps the counter until the window ends.
    for (let i = 1; i <= 13; i++) {
      const start = w + i * TEN_MIN;
      expect(await oldHit("register:192.0.2.1", start)).toEqual({ count: 1 });
      expect(await expiresAt("register:192.0.2.1")).toBeGreaterThanOrEqual(start + TEN_MIN);
      expect(await deleteExpiredRateLimits(env.DB, start + TEN_MIN - 1)).toBe(0);
    }
    // And deletes it at the latest an hour after its last window started.
    expect(await deleteExpiredRateLimits(env.DB, w + 13 * TEN_MIN + HOUR)).toBe(1);
  });

  it("are continued by the current Worker with its own expiry", async () => {
    const now = Date.now();
    const w = now - (now % RULES.register.windowMs);
    await oldHit("register:c", w);
    expect(await hitRateLimit(env.DB, RULES.register, "c")).toBe(true);
    const row = await env.DB.prepare("SELECT count, window_start, expires_at FROM rate_limits").first();
    expect(row).toEqual({ count: 2, window_start: w, expires_at: w + RULES.register.windowMs });
  });
});

describe("pairing codes of the older Worker", () => {
  it("get their issuing device from created_by, so revoking it invalidates them", async () => {
    const issuer = await newDevice("issuer");
    const userId = await defaultUserId();
    const code = await oldCode(issuer.deviceId, userId);
    const row = await env.DB.prepare("SELECT issuer_device_id FROM pairing_codes WHERE created_by = ?1")
      .bind(issuer.deviceId)
      .first();
    expect(row).toEqual({ issuer_device_id: issuer.deviceId });
    expect((await postJson(issuer.token, `/v1/devices/${issuer.deviceId}/revoke`)).status).toBe(200);
    expect((await register(code)).status).toBe(401);
  });

  it("are not taken for admin-issued when issuer_device_id is NULL", async () => {
    const issuer = await newDevice("issuer");
    const userId = await defaultUserId();
    const code = await oldCode(issuer.deviceId, userId);
    const second = await oldCode(issuer.deviceId, userId);
    // As if the trigger had not filled it in.
    await env.DB.prepare("UPDATE pairing_codes SET issuer_device_id = NULL").run();

    // Revoked without the API (so the unused codes are not deleted): refused.
    await env.DB.prepare("UPDATE devices SET revoked_at = 1 WHERE id = ?1").bind(issuer.deviceId).run();
    expect((await register(code)).status).toBe(401);
    // Unrevoked: accepted, so the issuer was derived, not just any NULL refused.
    await env.DB.prepare("UPDATE devices SET revoked_at = NULL WHERE id = ?1").bind(issuer.deviceId).run();
    expect((await register(code)).status).toBe(201);
    // Revoking through the API deletes such a code too.
    expect((await postJson(issuer.token, `/v1/devices/${issuer.deviceId}/revoke`)).status).toBe(200);
    expect(
      await env.DB.prepare("SELECT COUNT(*) AS n FROM pairing_codes WHERE code_hash = ?1").bind(await sha256Hex(second)).first(),
    ).toEqual({ n: 0 });
    expect((await register(second)).status).toBe(401);
  });

  it("issued by an admin, or by a device id that does not exist, behave as such", async () => {
    const userId = await defaultUserId();
    const admin = await oldCode("admin:ops@example.com", userId);
    const ghost = await oldCode("no-such-device", userId);
    await env.DB.prepare("UPDATE pairing_codes SET issuer_device_id = NULL").run();
    expect((await register(admin)).status).toBe(201);
    expect((await register(ghost)).status).toBe(401);
    // Codes of the current Worker are unaffected.
    expect((await register(await issueAdminCode())).status).toBe(201);
  });
});
