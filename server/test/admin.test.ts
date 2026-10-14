import { env } from "cloudflare:workers";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import {
  acceptInvite,
  accessCall,
  ADMIN_DEV_TOKEN,
  adminCall,
  call,
  createInvite,
  createUser,
  genRsaKey,
  makeAccessJwt,
  mockAccessCerts,
  newDevice,
  register,
} from "./helpers";

let otherKeyPair: CryptoKeyPair;

beforeAll(async () => {
  otherKeyPair = await genRsaKey();
});

afterEach(() => {
  vi.restoreAllMocks();
});

const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

describe("admin auth", () => {
  it("rejects requests without Access, even with the dev token when dev admin is disabled", async () => {
    const prod = { ALLOW_DEV_ADMIN: "false" };
    expect((await call("/admin/devices", {}, prod)).status).toBe(401);
    expect((await call("/admin/invites", json({}), prod)).status).toBe(401);
    expect((await call("/admin/", {}, prod)).status).toBe(401);
    const withDev = await call(
      "/admin/invites",
      { ...json({}), headers: { "Content-Type": "application/json", Authorization: `Bearer ${ADMIN_DEV_TOKEN}` } },
      prod,
    );
    expect(withDev.status).toBe(401);
  });

  it("rejects a wrong dev token", async () => {
    const res = await call("/admin/devices", { headers: { Authorization: "Bearer wrong-token-wrong-token" } });
    expect(res.status).toBe(401);
  });

  it("rejects device tokens on admin routes", async () => {
    const a = await newDevice();
    const res = await call("/admin/devices", { headers: { Authorization: `Bearer ${a.token}` } }, { ALLOW_DEV_ADMIN: "false" });
    expect(res.status).toBe(401);
  });

  it("accepts a valid Access JWT of a listed admin, case-insensitively", async () => {
    const spy = await mockAccessCerts();
    const res = await accessCall(await makeAccessJwt({ email: "ADMIN@example.COM" }), "/admin/devices");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([]);
    const created = await accessCall(await makeAccessJwt(), "/admin/invites", json({}));
    expect(created.status).toBe(201);
    const invites = await (await accessCall(await makeAccessJwt(), "/admin/invites")).json();
    expect(invites).toMatchObject([{ status: "pending", createdBy: "admin:admin@example.com" }]);
    // Certs are cached.
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("accepts a string aud claim", async () => {
    await mockAccessCerts();
    expect((await accessCall(await makeAccessJwt({ aud: "test-aud" }), "/admin/devices")).status).toBe(200);
  });

  it("rejects valid JWTs whose email is missing or not in ADMIN_EMAILS", async () => {
    await mockAccessCerts();
    const outsider = await makeAccessJwt({ email: "someone@example.com" });
    for (const path of ["/admin/", "/admin/devices", "/admin/users", "/admin/invites"]) {
      expect((await accessCall(outsider, path)).status, path).toBe(403);
    }
    expect((await accessCall(outsider, "/admin/invites", json({}))).status).toBe(403);
    expect((await accessCall(await makeAccessJwt({ email: undefined }), "/admin/devices")).status).toBe(403);
    expect((await accessCall(await makeAccessJwt({ email: "" }), "/admin/devices")).status).toBe(403);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("fails closed when ADMIN_EMAILS is empty", async () => {
    await mockAccessCerts();
    for (const list of ["", " , "]) {
      const res = await accessCall(await makeAccessJwt(), "/admin/devices", {}, { ADMIN_EMAILS: list });
      expect(res.status).toBe(403);
    }
  });

  it("rejects JWTs with bad signature, wrong aud/iss, expiry, unknown kid, or wrong alg", async () => {
    await mockAccessCerts();
    const now = Math.floor(Date.now() / 1000);
    const cases: Array<Promise<string>> = [
      makeAccessJwt({}, { key: otherKeyPair.privateKey }),
      makeAccessJwt({ aud: ["other-aud"] }),
      makeAccessJwt({ iss: "https://evil.cloudflareaccess.com" }),
      makeAccessJwt({ exp: now - 3600 }),
      makeAccessJwt({ nbf: now + 3600 }),
      makeAccessJwt({}, { kid: "unknown" }),
      makeAccessJwt({}, { alg: "HS256" }),
      Promise.resolve("not.a.jwt"),
      Promise.resolve("garbage"),
    ];
    for (const jwt of await Promise.all(cases)) {
      const res = await accessCall(jwt, "/admin/devices");
      expect(res.status, jwt).toBe(403);
    }
  });

  it("rejects everything when Access is not configured", async () => {
    await mockAccessCerts();
    const res = await accessCall(await makeAccessJwt(), "/admin/devices", {}, { CF_ACCESS_AUD: "", CF_ACCESS_TEAM_DOMAIN: "" });
    expect(res.status).toBe(403);
  });

  it("rejects cross-origin state-changing requests", async () => {
    const res = await adminCall("/admin/invites", {
      ...json({}),
      headers: { "Content-Type": "application/json", Origin: "https://evil.example" },
    });
    expect(res.status).toBe(403);
  });

  it("allows cross-site GETs (the redirect back from the Access login) but not cross-site POSTs", async () => {
    await mockAccessCerts();
    const jwt = await makeAccessJwt();
    const page = await accessCall(jwt, "/admin/", { headers: { "Sec-Fetch-Site": "cross-site" } });
    expect(page.status).toBe(200);
    const list = await accessCall(jwt, "/admin/devices", {
      headers: { "Sec-Fetch-Site": "cross-site", Origin: "https://evil.example" },
    });
    expect(list.status).toBe(200);

    const post = json({});
    const crossSite = await accessCall(jwt, "/admin/invites", {
      ...post,
      headers: { "Content-Type": "application/json", "Sec-Fetch-Site": "cross-site" },
    });
    expect(crossSite.status).toBe(403);
    const foreign = await accessCall(jwt, "/admin/invites", {
      ...post,
      headers: { "Content-Type": "application/json", Origin: "https://evil.example" },
    });
    expect(foreign.status).toBe(403);
    const sameOrigin = await accessCall(jwt, "/admin/invites", {
      ...post,
      headers: { "Content-Type": "application/json", Origin: "https://ashidori.example", "Sec-Fetch-Site": "same-origin" },
    });
    expect(sameOrigin.status).toBe(201);
  });
});

describe("admin API: users", () => {
  it("no longer creates users: a user is created by its first device", async () => {
    expect((await adminCall("/admin/users", json({ name: "Alice" }))).status).toBe(404);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first()).toEqual({ n: 0 });
  });

  it("sets the username of a legacy user and changes it: lowercase, validated, unique", async () => {
    const legacy = await createUser("Dave", "dave@example.net");
    await createUser("Carol", undefined, "carol_1");
    const listed = (await (await adminCall("/admin/users")).json()) as Array<Record<string, unknown>>;
    expect(listed.find((u) => u.id === legacy)).toMatchObject({
      username: null,
      legacyName: "Dave",
      email: "dave@example.net",
    });
    expect(listed.find((u) => u.id !== legacy)).toMatchObject({ username: "carol_1", legacyName: null });

    const patch = (body: unknown) => adminCall(`/admin/users/${legacy}`, { ...json(body), method: "PATCH" });
    // Uniqueness is case-insensitive (usernames are stored lowercase).
    const taken = await patch({ username: "Carol_1" });
    expect(taken.status).toBe(409);
    expect(await taken.json()).toEqual({ error: "username taken" });
    for (const username of ["ab", "x".repeat(21), "has-dash", "ユーザ名", 3, null, ""]) {
      expect((await patch({ username })).status, String(username)).toBe(400);
    }
    expect((await patch({})).status).toBe(400);
    const ok = await patch({ username: " Dave " });
    expect(ok.status).toBe(200);
    expect(await ok.json()).toMatchObject({ id: legacy, username: "dave", legacyName: null });
    expect(await (await patch({ username: "david" })).json()).toMatchObject({ username: "david" });
    expect((await adminCall("/admin/users/nope", { ...json({ username: "zed" }), method: "PATCH" })).status).toBe(404);
  });


  it("lists users with device counts and last access", async () => {
    const alice = await createUser("alice");
    await createUser("bob");
    const a1 = await newDevice("a1", alice);
    await newDevice("a2", alice);
    await adminCall(`/admin/devices/${a1.deviceId}/revoke`, { method: "POST" });

    // By name: both users may have the same created_at (ms), and ties are ordered by the random id.
    const users = ((await (await adminCall("/admin/users")).json()) as Array<Record<string, unknown>>).sort((x, y) =>
      String(x.legacyName).localeCompare(String(y.legacyName)),
    );
    expect(users.map((u) => [u.legacyName, u.deviceCount])).toEqual([
      ["alice", 1],
      ["bob", 0],
    ]);
    expect(users[0]!.lastSeenAt).toEqual(expect.any(Number));
    expect(users[1]!.lastSeenAt).toBeNull();
  });

  it("issues pairing codes bound to a user", async () => {
    const alice = await createUser("alice");
    const res = await adminCall(`/admin/users/${alice}/pairing`, { method: "POST" });
    expect(res.status).toBe(201);
    const row = await env.DB.prepare("SELECT user_id, created_by FROM pairing_codes").first<{
      user_id: string;
      created_by: string;
    }>();
    expect(row).toEqual({ user_id: alice, created_by: "admin:dev-admin@localhost" });
    expect((await adminCall(`/admin/users/nope/pairing`, { method: "POST" })).status).toBe(404);
    // There are no codes without a user any more.
    expect((await adminCall("/admin/pairing", { method: "POST" })).status).toBe(404);
  });

  it("disables and re-enables users", async () => {
    const alice = await createUser("alice");
    const a = await newDevice("a", alice);
    const pending = await (await adminCall(`/admin/users/${alice}/pairing`, { method: "POST" })).json();

    const dis = await adminCall(`/admin/users/${alice}/disable`, { method: "POST" });
    expect(dis.status).toBe(200);
    expect(await dis.json()).toMatchObject({ status: "disabled", disabledAt: expect.any(Number) });
    // Devices stop working; unused codes are rejected (and not consumed); no new codes.
    expect((await call("/v1/devices", { headers: { Authorization: `Bearer ${a.token}` } })).status).toBe(401);
    const code = (pending as { code: string }).code;
    expect(
      (
        await register(code, "b")
      ).status,
    ).toBe(401);
    expect((await adminCall(`/admin/users/${alice}/pairing`, { method: "POST" })).status).toBe(409);
    const listed = (await (await adminCall("/admin/users")).json()) as Array<{ status: string }>;
    expect(listed[0]!.status).toBe("disabled");

    const en = await adminCall(`/admin/users/${alice}/enable`, { method: "POST" });
    expect(en.status).toBe(200);
    expect(await en.json()).toMatchObject({ status: "active", disabledAt: null });
    expect((await call("/v1/devices", { headers: { Authorization: `Bearer ${a.token}` } })).status).toBe(200);
    // The code issued before disabling still works once re-enabled (it was never consumed).
    expect(
      (
        await register(code, "b")
      ).status,
    ).toBe(201);

    expect((await adminCall(`/admin/users/nope/disable`, { method: "POST" })).status).toBe(404);
    expect((await adminCall(`/admin/users/nope/enable`, { method: "POST" })).status).toBe(404);
  });
});

describe("admin API: devices", () => {
  it("lists all users' devices (and legacy ones) and revokes any device", async () => {
    const alice = await createUser("alice", undefined, "alice");
    const bob = await createUser("bob", undefined, "bob");
    const a = await newDevice("phone", alice);
    const b = await newDevice("tablet", bob);
    await env.DB.prepare(
      "INSERT INTO devices (id, name, token_hash, created_at) VALUES ('legacy', 'old', ?1, ?2)",
    )
      .bind("0".repeat(64), Date.now() + 1000)
      .run();

    const list = (await (await adminCall("/admin/devices")).json()) as Array<Record<string, unknown>>;
    expect(list.map((d) => [d.id, d.userId, d.username])).toEqual([
      [a.deviceId, alice, "alice"],
      [b.deviceId, bob, "bob"],
      ["legacy", null, null],
    ]);

    const rev = await adminCall(`/admin/devices/${b.deviceId}/revoke`, { method: "POST" });
    expect(rev.status).toBe(200);
    expect((await call("/v1/devices", { headers: { Authorization: `Bearer ${b.token}` } })).status).toBe(401);
    expect((await adminCall(`/admin/devices/unknown/revoke`, { method: "POST" })).status).toBe(404);
  });

  it("serves the admin page with a nonce CSP", async () => {
    const res = await adminCall("/admin/");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toMatch(/text\/html/);
    expect(res.headers.get("Content-Security-Policy")).toMatch(/script-src 'nonce-/);
  });

  it("rate limits pairing code creation", async () => {
    const alice = await createUser("alice");
    for (let i = 0; i < 20; i++) {
      expect((await adminCall(`/admin/users/${alice}/pairing`, { method: "POST" })).status).toBe(201);
    }
    expect((await adminCall(`/admin/users/${alice}/pairing`, { method: "POST" })).status).toBe(429);
  });
});

describe("registration with an invite while a limit is invalid", () => {
  it("is refused with 503, like every other creation, and leaves the invite unused", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const { code } = await createInvite();
    for (const bad of [{ MAX_USERS: "invalid" }, { USER_QUOTA_BYTES: "0" }]) {
      const res = await acceptInvite(code, "alice", undefined, "192.0.2.1", bad);
      expect(res.status).toBe(503);
      expect(await res.json()).toEqual({ error: "server misconfigured" });
    }
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first()).toEqual({ n: 0 });
    expect((await acceptInvite(code, "alice")).status).toBe(201);
  });
});
