// Invitation-based registration: an admin creates an invite, and the person
// creates their user with a username of their choice on their first device
// (POST /v1/accounts with `invite`). See docs/pairing.md.

import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { UNUSED_ACCOUNT_TTL_MS } from "../src/accounts";
import worker from "../src/index";
import { DAY_MS, INVITE_KEEP_MS } from "../src/invites";
import { D1_NOW_MS } from "../src/util";
import {
  acceptInvite,
  adminCall,
  authed,
  createInvite,
  createUser,
  fakeLimiter,
  newDevice,
  newToken,
  OPEN,
  postJson,
  register,
} from "./helpers";

const json = (body: unknown, method = "POST"): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
const count = async (sql: string) => (await env.DB.prepare(sql).first<{ n: number }>())!.n;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

interface InviteRow {
  id: string;
  status: string;
  createdBy: string;
  createdAt: number;
  expiresAt: number;
  usedAt: number | null;
  user: { id: string; username: string | null } | null;
  revokedAt: number | null;
}
const invites = async () => (await (await adminCall("/admin/invites")).json()) as InviteRow[];

async function hashOf(s: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Lets the invite expire `ms` from now by D1's clock. */
async function expireIn(code: string, ms: number): Promise<void> {
  await env.DB.prepare(`UPDATE invites SET expires_at = ${D1_NOW_MS} + ?2 WHERE code_hash = ?1`)
    .bind(await hashOf(code), ms)
    .run();
}

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

async function runScheduled(): Promise<void> {
  const ctx = createExecutionContext();
  await worker.scheduled!(createScheduledController(), env, ctx);
  await waitOnExecutionContext(ctx);
}

const body = async (r: Response) => ({ status: r.status, body: (await r.json()) as Record<string, unknown> });

describe("admin API: invites", () => {
  it("creates a single-use link with an expiry by D1's clock, and lists it", async () => {
    const res = await adminCall("/admin/invites", json({}));
    expect(res.status).toBe(201);
    const inv = (await res.json()) as { id: string; code: string; expiresAt: number; link: string };
    expect(inv.code).toMatch(/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}$/);
    expect(inv.link).toBe(`https://ashidori.example/pair#i=${inv.code}`);
    const row = await env.DB.prepare("SELECT code_hash, created_at, expires_at, created_by FROM invites").first<{
      code_hash: string;
      created_at: number;
      expires_at: number;
      created_by: string;
    }>();
    // Only the hash is stored.
    expect(row!.code_hash).toBe(await hashOf(inv.code));
    expect(row!.expires_at).toBe(inv.expiresAt);
    expect(row!.expires_at - row!.created_at).toBe(7 * DAY_MS);
    expect(row!.created_by).toBe("admin:dev-admin@localhost");
    expect(await invites()).toEqual([
      {
        id: inv.id,
        status: "pending",
        createdBy: "admin:dev-admin@localhost",
        createdAt: row!.created_at,
        expiresAt: inv.expiresAt,
        usedAt: null,
        user: null,
        revokedAt: null,
      },
    ]);
    // No user exists before the person registers.
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(0);
  });

  it("takes ttlDays from 1 to 30", async () => {
    const one = (await (await adminCall("/admin/invites", json({ ttlDays: 1 }))).json()) as { id: string };
    const r = await env.DB.prepare("SELECT expires_at - created_at AS ttl FROM invites WHERE id = ?1").bind(one.id).first();
    expect(r).toEqual({ ttl: DAY_MS });
    expect((await adminCall("/admin/invites", json({ ttlDays: 30 }))).status).toBe(201);
    for (const ttlDays of [0, 31, 1.5, "7", -1]) {
      expect((await adminCall("/admin/invites", json({ ttlDays }))).status, String(ttlDays)).toBe(400);
    }
    expect((await adminCall("/admin/invites", { method: "POST", body: "{}" })).status).toBe(400);
  });

  it("revokes an unused invite; not a used one", async () => {
    const a = await createInvite();
    const revoke = (id: string) => adminCall(`/admin/invites/${id}/revoke`, { method: "POST" });
    expect((await revoke(a.id)).status).toBe(200);
    const at = (await invites())[0]!.revokedAt;
    expect(at).toEqual(expect.any(Number));
    // Twice is a no-op.
    expect((await revoke(a.id)).status).toBe(200);
    expect((await invites())[0]).toMatchObject({ status: "revoked", revokedAt: at });
    // A revoked invite is refused like an unknown one, and creates nothing.
    expect(await body(await acceptInvite(a.code, "alice"))).toEqual({
      status: 401,
      body: { error: "invalid or expired invite" },
    });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(0);

    const b = await createInvite();
    expect((await acceptInvite(b.code, "bob")).status).toBe(201);
    const used = await revoke(b.id);
    expect(used.status).toBe(409);
    expect(await used.json()).toEqual({ error: "invite already used" });
    expect((await revoke("nope")).status).toBe(404);
  });

  it("are deleted 30 days after they expired or were revoked, unless used", async () => {
    const [old, revoked, used, fresh] = [await createInvite(), await createInvite(), await createInvite(), await createInvite()];
    expect((await acceptInvite(used.code, "alice")).status).toBe(201);
    await adminCall(`/admin/invites/${revoked.id}/revoke`, { method: "POST" });
    const past = Date.now() - INVITE_KEEP_MS - 60_000;
    await env.DB.prepare("UPDATE invites SET expires_at = ?2 WHERE id IN (?1, ?3)").bind(old.id, past, used.id).run();
    await env.DB.prepare("UPDATE invites SET revoked_at = ?2 WHERE id = ?1").bind(revoked.id, past).run();
    await runScheduled();
    expect((await invites()).map((i) => i.id).sort()).toEqual([used.id, fresh.id].sort());
  });
});

describe("POST /v1/accounts with an invite", () => {
  it("creates the user with the chosen username and this device, in any REGISTRATION mode", async () => {
    for (const [mode, username] of [["admin", "alice"], ["open", "bob"]] as const) {
      const inv = await createInvite();
      const token = newToken();
      const res = await acceptInvite(inv.code, username.toUpperCase(), token, "192.0.2.1", { REGISTRATION: mode });
      expect(res.status, mode).toBe(201);
      const { deviceId, username: got } = (await res.json()) as { deviceId: string; username: string };
      expect(got).toBe(username);
      const row = await env.DB.prepare(
        `SELECT u.id, u.name, u.handle, u.created_by, d.name AS device_name
         FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ?1`,
      )
        .bind(deviceId)
        .first<{ id: string; name: string; handle: string; created_by: string; device_name: string }>();
      expect(row).toMatchObject({ name: username, handle: username, created_by: `invite:${inv.id}`, device_name: "pixel" });
      const listed = (await invites()).find((i) => i.id === inv.id)!;
      expect(listed).toMatchObject({ status: "used", user: { id: row!.id, username }, usedAt: expect.any(Number) });
      // The device works, and knows its user.
      expect(await (await authed(token, "/v1/me")).json()).toEqual({ userId: row!.id, username });
    }
    // The admin list shows how each user registered.
    const users = (await (await adminCall("/admin/users")).json()) as Array<{ createdBy: string }>;
    expect(users.every((u) => u.createdBy.startsWith("invite:"))).toBe(true);
  });

  it("is used once: another token gets 401 like an unknown invite", async () => {
    const inv = await createInvite();
    expect((await acceptInvite(inv.code, "alice")).status).toBe(201);
    const unknown = await body(await acceptInvite("7K3M9QXZ7K3M9QXZ", "bob"));
    const reused = await body(await acceptInvite(inv.code, "bob"));
    expect(reused).toEqual(unknown);
    expect(reused).toEqual({ status: 401, body: { error: "invalid or expired invite" } });
    // Malformed codes too, after they were counted by the rate limit.
    expect(await body(await acceptInvite("short", "bob"))).toEqual(unknown);
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    // Codes are normalized like pairing codes: case, hyphens, I/L/O.
    const other = await createInvite();
    const typed = other.code.toLowerCase().match(/.{4}/g)!.join("-");
    expect((await acceptInvite(typed, "carol")).status).toBe(201);
  });

  it("validates the request", async () => {
    const inv = await createInvite();
    const post = (b: unknown) =>
      adminCall("/v1/accounts", { ...json(b), headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1" } });
    expect(await body(await post({ token: newToken(), username: "alice", invite: 42 }))).toEqual({
      status: 400,
      body: { error: "invalid invite" },
    });
    expect(await body(await post({ token: newToken(), invite: inv.code }))).toEqual({
      status: 400,
      body: { error: "invalid username" },
    });
    expect((await post({ token: "short", username: "alice", invite: inv.code })).status).toBe(400);
    expect((await invites())[0]!.status).toBe("pending");
  });

  it("refuses a username that is taken (409) without consuming the invite", async () => {
    await createUser("Carol", undefined, "carol");
    const inv = await createInvite();
    const res = await acceptInvite(inv.code, "Carol");
    expect(await body(res)).toEqual({ status: 409, body: { error: "username taken" } });
    expect((await invites())[0]!.status).toBe("pending");
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(0);
    // The person chooses another one, with a new request.
    expect((await acceptInvite(inv.code, "carol2")).status).toBe(201);
  });

  it("returns the same account for a retry with the same token, whatever changed since", async () => {
    const inv = await createInvite();
    const token = newToken();
    const first = await body(await acceptInvite(inv.code, "alice", token));
    expect(first.status).toBe(201);
    expect(await body(await acceptInvite(inv.code, "alice", token))).toEqual({ status: 200, body: first.body });
    // The invite expired, the client is rate limited, a limit variable is
    // invalid: recovery comes before every gate.
    await expireIn(inv.code, -1000);
    const limited = { RL_REGISTER: fakeLimiter(() => false), MAX_USERS: "x" };
    expect(await body(await acceptInvite(inv.code, "alice", token, "192.0.2.9", limited))).toEqual({
      status: 200,
      body: first.body,
    });
    // A request stored by an older app has no username: recovered all the same.
    const old = await adminCall("/v1/accounts", {
      ...json({ token, invite: inv.code, deviceName: "pixel" }),
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1" },
    });
    expect(await body(old)).toEqual({ status: 200, body: first.body });
    // The answer carries the username as it is now.
    const userId = (await (await authed(token, "/v1/me")).json() as { userId: string }).userId;
    await adminCall(`/admin/users/${userId}`, json({ username: "alice2" }, "PATCH"));
    expect(await body(await acceptInvite(inv.code, "alice", token))).toEqual({
      status: 200,
      body: { deviceId: first.body.deviceId, username: "alice2" },
    });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
  });

  it("does not recover across kinds of request: another invite, no invite, another device's token", async () => {
    const [a, b] = [await createInvite(), await createInvite()];
    const token = newToken();
    expect((await acceptInvite(a.code, "alice", token)).status).toBe(201);
    expect(await body(await acceptInvite(b.code, "bob", token))).toEqual({
      status: 409,
      body: { error: "token already in use" },
    });
    const open = await adminCall("/v1/accounts", {
      ...json({ token, username: "alice", deviceName: "pixel" }),
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1" },
    });
    // Without an invite this token is not a self-registered account: refused like any other request.
    expect(open.status).toBe(403);
    const paired = await newDevice("paired");
    expect(await body(await acceptInvite(b.code, "carol", paired.token))).toEqual({
      status: 409,
      body: { error: "token already in use" },
    });
    expect((await invites()).find((i) => i.id === b.id)!.status).toBe("pending");
  });

  it("judges the expiry by D1's clock when the invite is consumed", async () => {
    const inv = await createInvite();
    // Valid when the request starts, expired while its commit is delayed.
    await expireIn(inv.code, 300);
    const res = await acceptInvite(inv.code, "alice", newToken(), "192.0.2.1", { DB: delayedBatches(800) });
    expect(await body(res)).toEqual({ status: 401, body: { error: "invalid or expired invite" } });
    expect((await invites())[0]!.status).toBe("expired");
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(0);
    // Not expired when the commit runs: accepted.
    const ok = await createInvite();
    await expireIn(ok.code, 60_000);
    expect((await acceptInvite(ok.code, "alice", newToken(), "192.0.2.1", { DB: delayedBatches(200) })).status).toBe(201);
  });

  it("is rate limited per IP like registration with a code, and not capped by MAX_USERS", async () => {
    const limiter = fakeLimiter();
    for (let i = 0; i < 10; i++) {
      expect((await acceptInvite("7K3M9QXZ7K3M9QXZ", "alice", newToken(), "198.51.100.7", { RL_REGISTER: limiter })).status).toBe(401);
    }
    expect(limiter.keys[0]).toBe("invite:198.51.100.7");
    const inv = await createInvite();
    expect((await acceptInvite(inv.code, "alice", newToken(), "198.51.100.7")).status).toBe(429);
    expect((await acceptInvite(inv.code, "alice", newToken(), "198.51.100.8", { MAX_USERS: "1" })).status).toBe(201);
    const second = await createInvite();
    expect((await acceptInvite(second.code, "bob", newToken(), "198.51.100.8", { MAX_USERS: "1" })).status).toBe(201);
  });

  it("invited users are not deleted as unused, unlike self-registered ones", async () => {
    const inv = await createInvite();
    const res = await acceptInvite(inv.code, "alice", newToken(), "192.0.2.1", OPEN);
    expect(res.status).toBe(201);
    await env.DB.prepare("UPDATE users SET created_at = created_at - ?1").bind(UNUSED_ACCOUNT_TTL_MS * 2).run();
    await runScheduled();
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
  });

  it("the invited user adds devices with pairing codes as usual", async () => {
    const inv = await createInvite();
    const token = newToken();
    expect((await acceptInvite(inv.code, "alice", token)).status).toBe(201);
    const { code } = (await (await postJson(token, "/v1/pairing")).json()) as { code: string };
    const t2 = newToken();
    expect((await register(code, "tablet", "192.0.2.1", t2)).status).toBe(201);
    expect(await (await authed(t2, "/v1/me")).json()).toMatchObject({ username: "alice" });
  });
});

describe("GET /v1/me", () => {
  it("names the device's user; null for legacy users and devices", async () => {
    const legacyUser = await createUser("Old");
    const d = await newDevice("phone", legacyUser);
    expect(await (await authed(d.token, "/v1/me")).json()).toEqual({ userId: legacyUser, username: null });
    expect((await authed(newToken(), "/v1/me")).status).toBe(401);
  });
});
