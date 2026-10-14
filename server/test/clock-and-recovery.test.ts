// Expiries and windows judged by D1's clock when the statement runs, and
// result recovery of registrations ahead of the gates for new ones (review 6,
// items 7 and 9).

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { hitRateLimit, RULES } from "../src/ratelimit";
import { D1_NOW_MS } from "../src/util";
import {
  adminCall,
  createUser,
  fakeLimiter,
  issueAdminCode,
  newDevice,
  newToken,
  OPEN,
  register,
  selfRegister,
} from "./helpers";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A D1 binding whose batches (the registration's commit) wait `ms` before they run. */
function delayedBatches(ms: number): D1Database {
  return new Proxy(env.DB, {
    get(target, prop) {
      if (prop === "batch") {
        return async (stmts: D1PreparedStatement[]) => {
          await sleep(ms);
          return target.batch(stmts);
        };
      }
      const v = Reflect.get(target, prop);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
}

/** Lets the code expire `ms` from now by D1's clock. */
async function expireIn(code: string, ms: number): Promise<void> {
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(code)))]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  await env.DB.prepare(`UPDATE pairing_codes SET expires_at = ${D1_NOW_MS} + ?2 WHERE code_hash = ?1`)
    .bind(hash, ms)
    .run();
}

describe("pairing-code expiry", () => {
  it("is judged when D1 consumes the code, not when the Worker read its clock", async () => {
    const code = await issueAdminCode();
    // The code is valid when the request starts and expires while its
    // commit is delayed: it must not be consumed.
    await expireIn(code, 300);
    const res = await register(code, "pixel", "192.0.2.1", newToken(), { DB: delayedBatches(800) });
    expect(res.status).toBe(401);
    expect(await env.DB.prepare("SELECT used_at, (SELECT COUNT(*) FROM devices) AS n FROM pairing_codes").first()).toEqual({
      used_at: null,
      n: 0,
    });
  });

  it("still accepts a code that has not expired when the commit runs", async () => {
    const code = await issueAdminCode();
    await expireIn(code, 60_000);
    expect((await register(code, "pixel", "192.0.2.1", newToken(), { DB: delayedBatches(200) })).status).toBe(201);
  });

  it("is set from D1's clock when the code is issued", async () => {
    const id = await createUser("u");
    const res = await adminCall(`/admin/users/${id}/pairing`, { method: "POST" });
    const { expiresAt } = (await res.json()) as { expiresAt: number };
    const row = await env.DB.prepare("SELECT created_at, expires_at FROM pairing_codes").first<{
      created_at: number;
      expires_at: number;
    }>();
    expect(row!.expires_at).toBe(expiresAt);
    expect(row!.expires_at - row!.created_at).toBe(10 * 60 * 1000);
  });
});

describe("rate-limit windows", () => {
  const rule = RULES.register;
  const key = `${rule.bucket}:c`;
  const row = () => env.DB.prepare("SELECT window_start, count, expires_at FROM rate_limits WHERE key = ?1").bind(key).first();
  const currentWindow = async () =>
    (await env.DB.prepare(`SELECT ${D1_NOW_MS} - ${D1_NOW_MS} % ?1 AS w`).bind(rule.windowMs).first<{ w: number }>())!.w;

  it("are taken from D1's clock", async () => {
    expect(await hitRateLimit(env.DB, rule, "c")).toBe(true);
    const w = await currentWindow();
    expect(await row()).toEqual({ window_start: w, count: 1, expires_at: w + rule.windowMs });
  });

  it("never move backwards: a request of an older window counts in the stored one", async () => {
    // A later window is stored (written by a Worker whose clock is ahead, or
    // before D1's clock stepped back), at its limit.
    const later = (await currentWindow()) + rule.windowMs;
    await env.DB.prepare("INSERT INTO rate_limits (key, window_start, expires_at, count) VALUES (?1, ?2, ?3, ?4)")
      .bind(key, later, later + rule.windowMs, rule.limit)
      .run();
    const before = await row();
    expect(await hitRateLimit(env.DB, rule, "c")).toBe(false);
    expect(await row()).toEqual(before);

    // Below the limit, it is counted in the stored window, which stays.
    await env.DB.prepare("UPDATE rate_limits SET count = 3 WHERE key = ?1").bind(key).run();
    expect(await hitRateLimit(env.DB, rule, "c")).toBe(true);
    expect(await row()).toEqual({ window_start: later, count: 4, expires_at: later + rule.windowMs });
  });

  it("restart in a later window", async () => {
    const earlier = (await currentWindow()) - rule.windowMs;
    await env.DB.prepare("INSERT INTO rate_limits (key, window_start, expires_at, count) VALUES (?1, ?2, ?3, ?4)")
      .bind(key, earlier, earlier + rule.windowMs, rule.limit)
      .run();
    expect(await hitRateLimit(env.DB, rule, "c")).toBe(true);
    const w = await currentWindow();
    expect(await row()).toEqual({ window_start: w, count: 1, expires_at: w + rule.windowMs });
  });
});

const json = async (r: Response) => ({ status: r.status, body: (await r.json()) as Record<string, unknown> });

/** Exhausts the D1 counter of `rule` for `ip`. */
async function exhaust(rule: (typeof RULES)[keyof typeof RULES], ip: string): Promise<void> {
  while (await hitRateLimit(env.DB, rule, ip));
}

describe("result recovery of a self-registration (review 6, item 9)", () => {
  it("returns the device of a lost response whatever the gates for new users say", async () => {
    const token = newToken();
    const first = await json(await selfRegister({ deviceName: "pixel", token }, "192.0.2.1"));
    expect(first.status).toBe(201);
    const ip = "198.51.100.7";
    await exhaust(RULES.account, ip);
    const closedGates: Array<[string, Partial<Cloudflare.Env>]> = [
      ["registration closed", { REGISTRATION: "admin" }],
      ["edge limiter refuses", { ...OPEN, RL_ACCOUNT: fakeLimiter(() => false) }],
      ["edge binding missing", { ...OPEN, RL_ACCOUNT: undefined }],
      ["server full", { ...OPEN, MAX_USERS: "1" }],
      ["limit variable invalid", { ...OPEN, MAX_USERS: "0" }],
      ["D1 counter exhausted", OPEN],
    ];
    for (const [why, gate] of closedGates) {
      expect(await json(await selfRegister({ deviceName: "pixel", token }, ip, gate)), why).toEqual({
        status: 200,
        body: first.body,
      });
    }
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>())!.n).toBe(1);
  });

  it("does not let a new user through a closed gate", async () => {
    expect(await json(await selfRegister({ deviceName: "pixel" }, "192.0.2.1", { REGISTRATION: "admin" }))).toEqual({
      status: 403,
      body: { error: "registration closed" },
    });
    const ip = "198.51.100.8";
    await exhaust(RULES.account, ip);
    expect((await selfRegister({ deviceName: "pixel" }, ip)).status).toBe(429);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>())!.n).toBe(0);
  });

  it("does not recover a revoked device, nor a token of another kind of device", async () => {
    const token = newToken();
    const first = (await (await selfRegister({ deviceName: "pixel", token })).json()) as { deviceId: string };
    await adminCall(`/admin/devices/${first.deviceId}/revoke`, { method: "POST" });
    expect((await selfRegister({ deviceName: "pixel", token }, "192.0.2.1", { REGISTRATION: "admin" })).status).toBe(403);
    expect((await selfRegister({ deviceName: "pixel", token })).status).toBe(409);
    const paired = await newDevice("paired");
    expect((await selfRegister({ deviceName: "pixel", token: paired.token }, "192.0.2.1", { REGISTRATION: "admin" })).status).toBe(403);
  });
});

describe("result recovery of a registration with a code (review 6, item 9)", () => {
  it("returns the device of a lost response whatever the gates for new devices say", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    const first = await json(await register(code, "pixel", "192.0.2.1", token));
    expect(first.status).toBe(201);
    const ip = "198.51.100.7";
    await exhaust(RULES.register, ip);
    const gates: Array<[string, Partial<Cloudflare.Env>]> = [
      ["edge limiter refuses", { RL_REGISTER: fakeLimiter(() => false) }],
      ["D1 counter exhausted", {}],
      ["limit variable invalid", { USER_MAX_DEVICES: "0" }],
      ["device limit reached", { USER_MAX_DEVICES: "1" }],
    ];
    for (const [why, gate] of gates) {
      expect(await json(await register(code, "pixel", ip, token, gate)), why).toEqual({ status: 200, body: first.body });
    }
  });

  it("gates everything else: an unknown token, or a known token with another code", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    expect((await register(code, "pixel", "192.0.2.1", token)).status).toBe(201);
    const ip = "198.51.100.9";
    await exhaust(RULES.register, ip);
    expect((await register(code, "pixel", ip, newToken())).status).toBe(429);
    expect((await register(await issueAdminCode(), "pixel", ip, token)).status).toBe(429);
    expect((await register(await issueAdminCode(), "pixel", "192.0.2.2", token)).status).toBe(401);
  });
});
