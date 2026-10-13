import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { DEFAULT_USER_QUOTA_BYTES, registrationMode, userQuotaBytes } from "../src/accounts";
import {
  ADMIN_DEV_TOKEN,
  adminCall,
  authed,
  blob,
  call,
  legacyDevice,
  newDevice,
  newUser,
  OPEN,
  postJson,
  putBatch,
  register,
  selfRegister,
} from "./helpers";

interface Account {
  deviceId: string;
  token: string;
}

async function account(deviceName = "pixel", ip = "192.0.2.1"): Promise<Account> {
  const res = await selfRegister({ deviceName }, ip);
  if (res.status !== 201) throw new Error(`self-registration failed: ${res.status}`);
  return (await res.json()) as Account;
}

async function userOf(deviceId: string): Promise<string> {
  const row = await env.DB.prepare("SELECT user_id FROM devices WHERE id = ?1")
    .bind(deviceId)
    .first<{ user_id: string }>();
  return row!.user_id;
}

const userCount = async () => (await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>())!.n;

describe("GET /v1/info", () => {
  it("reports the registration mode without auth", async () => {
    const res = await call("/v1/info");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ registration: "admin" });
    expect(await (await call("/v1/info", {}, OPEN)).json()).toEqual({ registration: "open" });
  });

  it("treats anything but \"open\" as admin", async () => {
    for (const v of ["", "admin", "closed", "yes", "opened"]) {
      expect(registrationMode({ REGISTRATION: v })).toBe("admin");
    }
    expect(registrationMode({ REGISTRATION: undefined as unknown as string })).toBe("admin");
    expect(registrationMode({ REGISTRATION: " Open " })).toBe("open");
  });
});

describe("POST /v1/accounts", () => {
  it("is refused unless registration is open", async () => {
    for (const REGISTRATION of ["admin", "", "nonsense"]) {
      const res = await selfRegister({ deviceName: "pixel" }, "192.0.2.1", { REGISTRATION });
      expect(res.status).toBe(403);
      expect(await res.json()).toEqual({ error: "registration closed" });
    }
    expect(await userCount()).toBe(0);
  });

  it("creates a self-registered user with this device", async () => {
    const res = await selfRegister({ deviceName: "  Pixel 8a " });
    expect(res.status).toBe(201);
    const body = (await res.json()) as Account;
    expect(Object.keys(body).sort()).toEqual(["deviceId", "token"]);
    expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);

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
    expect(user!.token_hash).not.toContain(body.token);

    const devices = await authed(body.token, "/v1/devices");
    expect(devices.status).toBe(200);
    expect(((await devices.json()) as unknown[]).length).toBe(1);
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
    // Failed attempts count too.
    for (let i = 0; i < 5; i++) expect((await selfRegister({ deviceName: 1 }, "198.51.100.8")).status).toBe(400);
    expect((await selfRegister({ deviceName: "ok" }, "198.51.100.8")).status).toBe(429);
    // Other IPs are unaffected.
    expect((await selfRegister({ deviceName: "ok" }, "198.51.100.9")).status).toBe(201);
  });

  it("keeps the hourly counter for its whole window, and no longer", async () => {
    const now = Date.now();
    const insert = env.DB.prepare("INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 5)");
    await env.DB.batch([
      insert.bind("account:203.0.113.1", now - 30 * 60 * 1000),
      insert.bind("register:203.0.113.1", now - 30 * 60 * 1000),
      insert.bind("account:203.0.113.2", now - 61 * 60 * 1000),
    ]);
    // Any check deletes counters whose own window has ended.
    await register("ZZZZZZZZ", "x", "198.51.100.7");
    const { results } = await env.DB.prepare("SELECT key FROM rate_limits ORDER BY key").all<{ key: string }>();
    expect(results.map((r) => r.key)).toEqual(["account:203.0.113.1", "register:198.51.100.7"]);
  });
});

describe("storage quota", () => {
  const SMALL = { USER_QUOTA_BYTES: "10" };

  it("parses USER_QUOTA_BYTES, defaulting to 2 GiB", () => {
    expect(DEFAULT_USER_QUOTA_BYTES).toBe(2 * 1024 ** 3);
    for (const v of ["", "abc", "0", "-1", "1.5", "1e9", "99999999999999999"]) {
      expect(userQuotaBytes({ USER_QUOTA_BYTES: v })).toBe(DEFAULT_USER_QUOTA_BYTES);
    }
    expect(userQuotaBytes({ USER_QUOTA_BYTES: undefined as unknown as string })).toBe(DEFAULT_USER_QUOTA_BYTES);
    expect(userQuotaBytes({ USER_QUOTA_BYTES: " 1024 " })).toBe(1024);
  });

  it("rejects uploads beyond the user's quota", async () => {
    const a = await newDevice();
    expect((await putBatch(a.token, "fixes", 0, blob("123456"), undefined, SMALL)).status).toBe(201);
    const over = await putBatch(a.token, "fixes", 1, blob("123456"), undefined, SMALL);
    expect(over.status).toBe(507);
    expect(await over.json()).toEqual({ error: "quota exceeded" });
    expect(await env.BLOBS.get(`b/${a.deviceId}/fixes/000000000001`)).toBeNull();
    // Re-sending an existing batch still succeeds.
    expect((await putBatch(a.token, "fixes", 0, blob("123456"), undefined, SMALL)).status).toBe(200);
    // Exactly at the limit is allowed.
    expect((await putBatch(a.token, "fixes", 1, blob("1234"), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(a.token, "fixes", 2, blob("1"), undefined, SMALL)).status).toBe(507);
    // The default quota is far larger.
    expect((await putBatch(a.token, "fixes", 2, blob("1"))).status).toBe(201);
  });

  it("counts all devices of the user, revoked ones included, and no other user", async () => {
    const alice = await newUser("alice");
    const alice2 = await newDevice("alice-2", alice.userId);
    const bob = await newUser("bob");
    expect((await putBatch(alice.token, "fixes", 0, blob("12345678"), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(alice2.token, "fixes", 0, blob("123"), undefined, SMALL)).status).toBe(507);
    expect((await putBatch(bob.token, "fixes", 0, blob("12345678"), undefined, SMALL)).status).toBe(201);

    expect((await postJson(alice2.token, `/v1/devices/${alice.deviceId}/revoke`)).status).toBe(200);
    expect((await putBatch(alice2.token, "fixes", 0, blob("123"), undefined, SMALL)).status).toBe(507);
  });

  it("counts the legacy devices together", async () => {
    const a = await legacyDevice("legacy-a");
    const b = await legacyDevice("legacy-b");
    expect((await putBatch(a.token, "fixes", 0, blob("12345678"), undefined, SMALL)).status).toBe(201);
    expect((await putBatch(b.token, "fixes", 0, blob("123"), undefined, SMALL)).status).toBe(507);
  });

  it("shows each user's usage and origin to the admin", async () => {
    const alice = await newUser("alice");
    const self = await account("phone");
    await putBatch(alice.token, "fixes", 0, blob("12345"));
    await putBatch(self.token, "fixes", 0, blob("123"));
    await putBatch(self.token, "motion", 0, blob("1234"));

    const users = (await (await adminCall("/admin/users")).json()) as Array<{
      name: string;
      createdBy: string;
      storageBytes: number;
    }>;
    expect(users.map((u) => ({ name: u.name, createdBy: u.createdBy, storageBytes: u.storageBytes }))).toEqual([
      { name: "alice", createdBy: "dev-admin@localhost", storageBytes: 5 },
      { name: "phone", createdBy: "self", storageBytes: 7 },
    ]);
  });

  it("shows the mode and the quota on the admin page", async () => {
    const res = await call(
      "/admin/",
      { headers: { Authorization: `Bearer ${ADMIN_DEV_TOKEN}` } },
      { ...OPEN, USER_QUOTA_BYTES: "1048576" },
    );
    const page = await res.text();
    expect(page).toContain("新規登録: 公開");
    expect(page).toContain("const QUOTA = 1048576;");
    const closed = await (await adminCall("/admin/")).text();
    expect(closed).toContain("新規登録: 管理者のみ");
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
    const reg = await register(code, "tablet");
    expect(reg.status).toBe(201);
    const second = (await reg.json()) as Account;
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
