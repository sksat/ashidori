import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { registrationMode, UNUSED_ACCOUNT_TTL_MS } from "../src/accounts";
import { configErrors, DEFAULT_LIMITS, parseLimits } from "../src/config";
import worker from "../src/index";
import {
  ADMIN_DEV_TOKEN,
  adminCall,
  authed,
  blob,
  call,
  CHECKIN,
  fakeLimiter,
  follow,
  legacyDevice,
  newDevice,
  newToken,
  newUser,
  OPEN,
  postJson,
  putBatch,
  putCheckin,
  register,
  selfRegister,
} from "./helpers";

interface Account {
  deviceId: string;
  token: string;
}

async function account(deviceName = "pixel", ip = "192.0.2.1"): Promise<Account> {
  const token = newToken();
  const res = await selfRegister({ deviceName, token }, ip);
  if (res.status !== 201) throw new Error(`self-registration failed: ${res.status}`);
  return { ...((await res.json()) as { deviceId: string }), token };
}

async function userOf(deviceId: string): Promise<string> {
  const row = await env.DB.prepare("SELECT user_id FROM devices WHERE id = ?1")
    .bind(deviceId)
    .first<{ user_id: string }>();
  return row!.user_id;
}

const count = async (sql: string) => (await env.DB.prepare(sql).first<{ n: number }>())!.n;
const userCount = () => count("SELECT COUNT(*) AS n FROM users");

async function runScheduled(): Promise<void> {
  const ctx = createExecutionContext();
  await worker.scheduled!(createScheduledController(), env, ctx);
  await waitOnExecutionContext(ctx);
}

describe("GET /v1/info", () => {
  it("reports the registration mode without auth", async () => {
    const res = await call("/v1/info");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ registration: "admin" });
    expect(await (await call("/v1/info", {}, OPEN)).json()).toEqual({ registration: "open" });
  });

  it("opens only for exactly \"open\"", async () => {
    for (const v of ["", "admin", "closed", "yes", "opened", "Open", " open", "open ", "OPEN"]) {
      expect(registrationMode({ REGISTRATION: v }), v).toBe("admin");
    }
    expect(registrationMode({})).toBe("admin");
    expect(registrationMode({ REGISTRATION: "open" })).toBe("open");
    expect(await (await call("/v1/info", {}, { REGISTRATION: "Open" })).json()).toEqual({ registration: "admin" });
  });
});

describe("POST /v1/accounts", () => {
  it("is refused unless registration is open", async () => {
    for (const REGISTRATION of ["admin", "", "nonsense", " open"]) {
      const res = await selfRegister({ deviceName: "pixel" }, "192.0.2.1", { REGISTRATION });
      expect(res.status).toBe(403);
      expect(await res.json()).toEqual({ error: "registration closed" });
    }
    expect(await userCount()).toBe(0);
  });

  it("creates a self-registered user with this device and the client's token", async () => {
    const token = newToken();
    const res = await selfRegister({ deviceName: "  Pixel 8a ", token });
    expect(res.status).toBe(201);
    const body = (await res.json()) as { deviceId: string };
    expect(Object.keys(body)).toEqual(["deviceId"]);

    const user = await env.DB.prepare(
      `SELECT u.name, u.handle, u.email, u.status, u.created_by, d.name AS device_name, d.token_hash
       FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ?1`,
    )
      .bind(body.deviceId)
      .first<Record<string, unknown>>();
    expect(user).toMatchObject({
      name: "Pixel 8a",
      handle: null,
      email: null,
      status: "active",
      created_by: "self",
      device_name: "Pixel 8a",
    });
    expect(user!.token_hash).not.toContain(token);

    const devices = await authed(token, "/v1/devices");
    expect(devices.status).toBe(200);
    expect(((await devices.json()) as unknown[]).length).toBe(1);
  });

  it("requires a well-formed token", async () => {
    for (const token of [undefined, null, "", "x".repeat(42), "x".repeat(44), "a/b".padEnd(43, "c"), 1]) {
      const res = await selfRegister({ deviceName: "pixel", token });
      expect(res.status, String(token)).toBe(400);
    }
    expect(await userCount()).toBe(0);
  });

  it("returns the same device for a retry with the same token", async () => {
    const token = newToken();
    const first = await selfRegister({ deviceName: "pixel", token });
    expect(first.status).toBe(201);
    const { deviceId } = (await first.json()) as { deviceId: string };
    const retry = await selfRegister({ deviceName: "pixel", token });
    expect(retry.status).toBe(200);
    expect(await retry.json()).toEqual({ deviceId });
    expect(await userCount()).toBe(1);

    // Concurrent retries agree too.
    const t2 = newToken();
    const both = await Promise.all([selfRegister({ token: t2 }), selfRegister({ token: t2 })]);
    expect(both.map((r) => r.status).sort()).toEqual([200, 201]);
    const ids = await Promise.all(both.map(async (r) => ((await r.json()) as { deviceId: string }).deviceId));
    expect(ids[0]).toBe(ids[1]);
    expect(await userCount()).toBe(2);
  });

  it("refuses a token that belongs to another kind of device", async () => {
    // A device paired with a code is not the first device of a self-registered user.
    const paired = await newDevice("paired");
    const res = await selfRegister({ deviceName: "x", token: paired.token });
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ error: "token already in use" });
    // So is a second device of a self-registered user.
    const first = await account("first");
    const code = ((await (await postJson(first.token, "/v1/pairing")).json()) as { code: string }).code;
    const token = newToken();
    expect((await register(code, "second", undefined, token)).status).toBe(201);
    expect((await selfRegister({ token })).status).toBe(409);
  });

  it("uses a generic name when none is given, and validates it", async () => {
    for (const b of [{}, { deviceName: "" }, { deviceName: null }]) {
      const res = await selfRegister(b);
      expect(res.status).toBe(201);
      const { deviceId } = (await res.json()) as Account;
      const row = await env.DB.prepare(
        "SELECT d.name, u.name AS user_name FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ?1",
      )
        .bind(deviceId)
        .first<{ name: string; user_name: string }>();
      expect(row).toEqual({ name: "device", user_name: "device" });
    }
    for (const deviceName of ["x".repeat(65), "a\nb", "   ", 42]) {
      expect((await selfRegister({ deviceName }, "192.0.2.2")).status).toBe(400);
    }
    // Not JSON.
    const plain = await call(
      "/v1/accounts",
      { method: "POST", body: "{}", headers: { "CF-Connecting-IP": "192.0.2.3" } },
      OPEN,
    );
    expect(plain.status).toBe(400);
  });

  it("is rate limited per IP: 5 attempts per hour", async () => {
    for (let i = 0; i < 5; i++) expect((await selfRegister({ deviceName: `d${i}` }, "198.51.100.7")).status).toBe(201);
    const res = await selfRegister({ deviceName: "d5" }, "198.51.100.7");
    expect(res.status).toBe(429);
    expect(await userCount()).toBe(5);
    // Other IPs are unaffected.
    expect((await selfRegister({ deviceName: "ok" }, "198.51.100.9")).status).toBe(201);
  });

  it("checks the edge rate limiter by IP before D1", async () => {
    const limiter = fakeLimiter(() => false);
    const res = await selfRegister({ deviceName: "x" }, "198.51.100.7", { ...OPEN, RL_ACCOUNT: limiter });
    expect(res.status).toBe(429);
    expect(limiter.keys).toEqual(["account:198.51.100.7"]);
    expect(await count("SELECT COUNT(*) AS n FROM rate_limits")).toBe(0);
  });

  it("stops at MAX_USERS, counting every user", async () => {
    const CAP = { ...OPEN, MAX_USERS: "3" };
    await newUser("by-admin");
    expect((await selfRegister({}, "192.0.2.10", CAP)).status).toBe(201);
    expect((await selfRegister({}, "192.0.2.11", CAP)).status).toBe(201);
    const full = await selfRegister({}, "192.0.2.12", CAP);
    expect(full.status).toBe(403);
    expect(await full.json()).toEqual({ error: "registration full" });
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(3);
    // Admins may still create users.
    expect((await adminCall("/admin/users", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: "late" }),
    })).status).toBe(201);
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(4);
  });

  it("does not exceed MAX_USERS under concurrent registrations", async () => {
    const CAP = { ...OPEN, MAX_USERS: "2" };
    const results = await Promise.all(
      Array.from({ length: 5 }, (_, i) => selfRegister({ deviceName: `d${i}` }, `192.0.2.${20 + i}`, CAP)),
    );
    expect(results.filter((r) => r.status === 201)).toHaveLength(2);
    expect(results.filter((r) => r.status === 403)).toHaveLength(3);
    expect(await userCount()).toBe(2);
  });

  it("is refused while a limit variable is invalid", async () => {
    const res = await selfRegister({}, "192.0.2.1", { ...OPEN, MAX_USERS: "lots" });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "server misconfigured" });
    expect(await userCount()).toBe(0);
  });
});

describe("unused self-registered accounts", () => {
  const age = async (deviceId: string, ms: number) => {
    const userId = await userOf(deviceId);
    await env.DB.prepare("UPDATE users SET created_at = created_at - ?2 WHERE id = ?1").bind(userId, ms).run();
    return userId;
  };

  it("are deleted after the TTL with their devices, codes and follows", async () => {
    const idle = await account("idle");
    const idleUser = await age(idle.deviceId, UNUSED_ACCOUNT_TTL_MS + 1000);
    await postJson(idle.token, "/v1/pairing");
    const other = await newUser("other", "other");
    await env.DB.prepare("UPDATE users SET handle = 'idle' WHERE id = ?1").bind(idleUser).run();
    await follow({ token: idle.token, userId: idleUser }, other);

    const young = await account("young", "192.0.2.2");
    await age(young.deviceId, UNUSED_ACCOUNT_TTL_MS - 60_000);

    await runScheduled();
    expect(await count("SELECT COUNT(*) AS n FROM users WHERE created_by = 'self'")).toBe(1);
    expect((await authed(idle.token, "/v1/devices")).status).toBe(401);
    expect((await authed(young.token, "/v1/devices")).status).toBe(200);
    expect(await count(`SELECT COUNT(*) AS n FROM devices WHERE user_id = '${idleUser}'`)).toBe(0);
    expect(await count(`SELECT COUNT(*) AS n FROM pairing_codes WHERE user_id = '${idleUser}'`)).toBe(0);
    expect(await count("SELECT COUNT(*) AS n FROM follows")).toBe(0);
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(2);
  });

  it("are kept once they stored a batch or a shared check-in", async () => {
    const withBatch = await account("batch");
    expect((await putBatch(withBatch.token, "status", 0, blob("x"))).status).toBe(201);
    await age(withBatch.deviceId, UNUSED_ACCOUNT_TTL_MS * 10);
    const withCheckin = await account("checkin", "192.0.2.2");
    expect((await putCheckin(withCheckin.token, "c1", CHECKIN)).status).toBe(200);
    await age(withCheckin.deviceId, UNUSED_ACCOUNT_TTL_MS * 10);
    // Admin-created users are never swept.
    const admin = await newUser("admin-made");
    await env.DB.prepare("UPDATE users SET created_at = 0 WHERE id = ?1").bind(admin.userId).run();

    await runScheduled();
    expect(await userCount()).toBe(3);
  });

  it("allow a retry of the registration until they are deleted", async () => {
    const token = newToken();
    const { deviceId } = (await (await selfRegister({ token })).json()) as { deviceId: string };
    expect((await selfRegister({ token })).status).toBe(200);
    await age(deviceId, UNUSED_ACCOUNT_TTL_MS + 1000);
    await runScheduled();
    // The token is unknown now; the same token creates a new account.
    const again = await selfRegister({ token });
    expect(again.status).toBe(201);
    expect(((await again.json()) as { deviceId: string }).deviceId).not.toBe(deviceId);
  });
});

describe("limit variables", () => {
  it("default when unset or empty, and are invalid unless a positive integer", () => {
    expect(DEFAULT_LIMITS.userQuotaBytes).toBe(2 * 1024 ** 3);
    expect(parseLimits({})).toEqual({ ok: true, limits: DEFAULT_LIMITS });
    expect(parseLimits({ USER_QUOTA_BYTES: "" })).toEqual({ ok: true, limits: DEFAULT_LIMITS });
    expect(parseLimits({ USER_QUOTA_BYTES: "1024", MAX_USERS: "7" })).toEqual({
      ok: true,
      limits: { ...DEFAULT_LIMITS, userQuotaBytes: 1024, maxUsers: 7 },
    });
    for (const v of ["0", "-1", "1.5", "1e9", "10MiB", " 1024", "1024 ", "0x10", "01", "99999999999999999", "abc"]) {
      expect(parseLimits({ USER_QUOTA_BYTES: v }), v).toEqual({ ok: false, invalid: ["USER_QUOTA_BYTES"] });
    }
    expect(parseLimits({ MAX_TOTAL_BYTES: "0", USER_MAX_BATCHES: "x" })).toEqual({
      ok: false,
      invalid: ["USER_MAX_BATCHES", "MAX_TOTAL_BYTES"],
    });
  });

  it("are checked before deploy, together with REGISTRATION", () => {
    expect(configErrors({})).toEqual([]);
    expect(configErrors({ REGISTRATION: "open", USER_QUOTA_BYTES: "1073741824" })).toEqual([]);
    expect(configErrors({ REGISTRATION: "admin" })).toEqual([]);
    const errors = configErrors({ REGISTRATION: "Open", MAX_USERS: "0" });
    expect(errors).toHaveLength(2);
    expect(errors[0]).toMatch(/REGISTRATION/);
    expect(errors[1]).toMatch(/MAX_USERS/);
  });
});

describe("storage quota", () => {
  // Blobs are at least 32 bytes.
  const SMALL = { USER_QUOTA_BYTES: "100" };
  const bytes = (n: number, fill = 1) => new Uint8Array(n).fill(fill);

  it("rejects uploads beyond the user's quota", async () => {
    const a = await newDevice();
    expect((await putBatch(a.token, "fixes", 0, bytes(60), undefined, SMALL)).status).toBe(201);
    const over = await putBatch(a.token, "fixes", 1, bytes(60, 2), undefined, SMALL);
    expect(over.status).toBe(507);
    expect(await over.json()).toEqual({ error: "quota exceeded" });
    expect((await env.BLOBS.list({ prefix: `b/${a.deviceId}/fixes/000000000001` })).objects).toEqual([]);
    // Re-sending an existing batch still succeeds.
    expect((await putBatch(a.token, "fixes", 0, bytes(60), undefined, SMALL)).status).toBe(200);
    // Exactly at the limit is allowed.
    expect((await putBatch(a.token, "fixes", 1, bytes(40, 2), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(a.token, "fixes", 2, bytes(32, 3), undefined, SMALL)).status).toBe(507);
    // The default quota is far larger.
    expect((await putBatch(a.token, "fixes", 2, bytes(32, 3))).status).toBe(201);
  });

  it("limits the number of batches", async () => {
    const a = await newDevice();
    const TWO = { USER_MAX_BATCHES: "2" };
    expect((await putBatch(a.token, "fixes", 0, bytes(32, 0), undefined, TWO)).status).toBe(201);
    expect((await putBatch(a.token, "fixes", 1, bytes(32, 1), undefined, TWO)).status).toBe(201);
    const third = await putBatch(a.token, "fixes", 2, bytes(32, 2), undefined, TWO);
    expect(third.status).toBe(507);
    expect(await third.json()).toEqual({ error: "quota exceeded" });
  });

  it("counts all devices of the user, revoked ones included, and no other user", async () => {
    const alice = await newUser("alice");
    const alice2 = await newDevice("alice-2", alice.userId);
    const bob = await newUser("bob");
    expect((await putBatch(alice.token, "fixes", 0, bytes(80), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(alice2.token, "fixes", 0, bytes(32), undefined, SMALL)).status).toBe(507);
    expect((await putBatch(bob.token, "fixes", 0, bytes(80), undefined, SMALL)).status).toBe(201);

    expect((await postJson(alice2.token, `/v1/devices/${alice.deviceId}/revoke`)).status).toBe(200);
    expect((await putBatch(alice2.token, "fixes", 0, bytes(32), undefined, SMALL)).status).toBe(507);
  });

  it("counts the legacy devices together", async () => {
    const a = await legacyDevice("legacy-a");
    const b = await legacyDevice("legacy-b");
    expect((await putBatch(a.token, "fixes", 0, bytes(80), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(b.token, "fixes", 0, bytes(32), undefined, SMALL)).status).toBe(507);
    const usage = await env.DB.prepare("SELECT legacy_storage_bytes, legacy_batch_count, total_bytes FROM server_usage").first();
    expect(usage).toEqual({ legacy_storage_bytes: 80, legacy_batch_count: 1, total_bytes: 80 });
  });

  it("refuses new storage beyond MAX_TOTAL_BYTES", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    const CAP = { MAX_TOTAL_BYTES: "100" };
    expect((await putBatch(alice.token, "fixes", 0, bytes(60), undefined, CAP)).status).toBe(201);
    const full = await putBatch(bob.token, "fixes", 0, bytes(60), undefined, CAP);
    expect(full.status).toBe(507);
    expect(await full.json()).toEqual({ error: "server storage full" });
    expect((await putBatch(bob.token, "fixes", 0, bytes(40), undefined, CAP)).status).toBe(201);
  });

  it("refuses new storage while a limit is invalid, but still confirms stored batches", async () => {
    const a = await newDevice();
    expect((await putBatch(a.token, "fixes", 0, bytes(40))).status).toBe(201);
    for (const bad of [{ USER_QUOTA_BYTES: "0" }, { USER_QUOTA_BYTES: "10MiB" }, { MAX_TOTAL_BYTES: "-1" }]) {
      const res = await putBatch(a.token, "fixes", 1, bytes(40, 2), undefined, bad);
      expect(res.status).toBe(503);
      expect(await res.json()).toEqual({ error: "server misconfigured" });
    }
    expect(await count("SELECT COUNT(*) AS n FROM batches")).toBe(1);
  });

  it("keeps the counters without summing, and shows them to the admin", async () => {
    const alice = await newUser("alice");
    const self = await account("phone");
    await putBatch(alice.token, "fixes", 0, bytes(50));
    await putBatch(self.token, "fixes", 0, bytes(33));
    await putBatch(self.token, "motion", 0, bytes(34));
    await putCheckin(self.token, "c1", CHECKIN);

    const users = (await (await adminCall("/admin/users")).json()) as Array<{
      name: string;
      createdBy: string;
      storageBytes: number;
      batchCount: number;
      sharedCheckinCount: number;
    }>;
    expect(
      users.map((u) => ({ name: u.name, createdBy: u.createdBy, storageBytes: u.storageBytes, batchCount: u.batchCount, sharedCheckinCount: u.sharedCheckinCount })),
    ).toEqual([
      { name: "alice", createdBy: "dev-admin@localhost", storageBytes: 50, batchCount: 1, sharedCheckinCount: 0 },
      { name: "phone", createdBy: "self", storageBytes: 67, batchCount: 2, sharedCheckinCount: 1 },
    ]);
    expect(await count("SELECT total_bytes AS n FROM server_usage")).toBe(117);
  });

  it("shows the mode, the quota and the server totals on the admin page", async () => {
    const res = await call(
      "/admin/",
      { headers: { Authorization: `Bearer ${ADMIN_DEV_TOKEN}` } },
      { ...OPEN, USER_QUOTA_BYTES: "1048576" },
    );
    const page = await res.text();
    expect(page).toContain("新規登録: 公開");
    expect(page).toContain("const QUOTA = 1048576;");
    expect(page).toContain(`const MAX_TOTAL = ${50 * 1024 ** 3};`);
    const closed = await (await adminCall("/admin/")).text();
    expect(closed).toContain("新規登録: 管理者のみ");
    const broken = await (
      await call("/admin/", { headers: { Authorization: `Bearer ${ADMIN_DEV_TOKEN}` } }, { USER_MAX_DEVICES: "0" })
    ).text();
    expect(broken).toContain("設定が不正なため");
    expect(broken).toContain("USER_MAX_DEVICES");
  });
});

describe("self-registered user, end to end", () => {
  it("uploads, lists, and pairs a second device", async () => {
    const first = await account("phone");
    const body = blob("ASH1 ciphertext");
    expect((await putBatch(first.token, "fixes", 0, body)).status).toBe(201);

    const list = (await (await authed(first.token, "/v1/batches")).json()) as { items: Array<{ deviceId: string }> };
    expect(list.items.map((i) => i.deviceId)).toEqual([first.deviceId]);

    // The second device joins with a code from the first; no admin involved.
    const pairing = await postJson(first.token, "/v1/pairing");
    expect(pairing.status).toBe(201);
    const { code } = (await pairing.json()) as { code: string };
    const token = newToken();
    const reg = await register(code, "tablet", undefined, token);
    expect(reg.status).toBe(201);
    const second = { ...((await reg.json()) as { deviceId: string }), token };
    expect(await userOf(second.deviceId)).toBe(await userOf(first.deviceId));

    const seen = (await (await authed(second.token, "/v1/batches")).json()) as { items: Array<{ deviceId: string }> };
    expect(seen.items.map((i) => i.deviceId)).toEqual([first.deviceId]);
    const fetched = await authed(second.token, `/v1/batches/${first.deviceId}/fixes/0`);
    expect(fetched.status).toBe(200);
    expect(new Uint8Array(await fetched.arrayBuffer())).toEqual(body);
    const devices = (await (await authed(second.token, "/v1/devices")).json()) as Array<{ name: string }>;
    expect(devices.map((d) => d.name).sort()).toEqual(["phone", "tablet"]);

    // Other users see none of it.
    const other = await account("other", "192.0.2.9");
    const otherList = (await (await authed(other.token, "/v1/batches")).json()) as { items: unknown[] };
    expect(otherList.items).toEqual([]);
  });

  it("can be disabled by an admin", async () => {
    const a = await account();
    const userId = await userOf(a.deviceId);
    expect((await adminCall(`/admin/users/${userId}/disable`, { method: "POST" })).status).toBe(200);
    expect((await authed(a.token, "/v1/devices")).status).toBe(401);
  });
});
