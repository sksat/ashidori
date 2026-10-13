import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { normalizePairingCode } from "../src/util";
import { authed, call, issueAdminCode, newDevice, register } from "./helpers";

describe("health", () => {
  it("is public", async () => {
    const res = await call("/v1/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });
});

describe("pairing and registration", () => {
  it("issues 8-char Crockford codes with a 10 minute TTL", async () => {
    const before = Date.now();
    const code = await issueAdminCode();
    expect(code).toMatch(/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/);
    const row = await env.DB.prepare("SELECT expires_at, created_by FROM pairing_codes").first<{
      expires_at: number;
      created_by: string;
    }>();
    expect(row!.created_by).toBe("admin:dev-admin@localhost");
    expect(row!.expires_at - before).toBeGreaterThanOrEqual(10 * 60 * 1000);
    expect(row!.expires_at - before).toBeLessThan(10 * 60 * 1000 + 5000);
    // The code itself is not stored.
    const raw = await env.DB.prepare("SELECT COUNT(*) AS n FROM pairing_codes WHERE code_hash = ?1")
      .bind(code)
      .first<{ n: number }>();
    expect(raw!.n).toBe(0);
  });

  it("registers a device with a code exactly once", async () => {
    const code = await issueAdminCode();
    const res = await register(code, "pixel 9");
    expect(res.status).toBe(201);
    const body = (await res.json()) as { deviceId: string; token: string };
    expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);

    // Only the hash of the token is stored.
    const row = await env.DB.prepare("SELECT name, token_hash FROM devices WHERE id = ?1")
      .bind(body.deviceId)
      .first<{ name: string; token_hash: string }>();
    expect(row!.name).toBe("pixel 9");
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row!.token_hash).not.toContain(body.token);

    const again = await register(code, "other");
    expect(again.status).toBe(401);
  });

  it("accepts codes in lower case with hyphens and Crockford aliases", async () => {
    const code = await issueAdminCode();
    const sloppy = (code.slice(0, 4) + "-" + code.slice(4)).toLowerCase().replace(/1/g, "l").replace(/0/g, "o");
    const res = await register(sloppy);
    expect(res.status).toBe(201);
  });

  it("rejects expired codes", async () => {
    const code = await issueAdminCode();
    await env.DB.prepare("UPDATE pairing_codes SET expires_at = ?1").bind(Date.now() - 1).run();
    expect((await register(code)).status).toBe(401);
  });

  it("rejects unknown and malformed codes", async () => {
    expect((await register("ZZZZZZZZ")).status).toBe(401);
    expect((await register("short")).status).toBe(401);
  });

  it("validates the request body", async () => {
    const code = await issueAdminCode();
    const bad = await call("/v1/devices/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not json",
    });
    expect(bad.status).toBe(400);
    expect(await bad.json()).toEqual({ error: expect.any(String) });
    expect((await register(code, "")).status).toBe(400);
    expect((await register(code, "x".repeat(65))).status).toBe(400);
    // The code was not consumed by the invalid attempts.
    expect((await register(code, "ok")).status).toBe(201);
  });

  it("lets an existing device create pairing codes", async () => {
    const a = await newDevice("a");
    const res = await authed(a.token, "/v1/pairing", { method: "POST" });
    expect(res.status).toBe(201);
    const { code, expiresAt } = (await res.json()) as { code: string; expiresAt: number };
    expect(expiresAt).toBeGreaterThan(Date.now());
    expect((await register(code, "b")).status).toBe(201);
  });

  it("rate limits registration per IP", async () => {
    for (let i = 0; i < 10; i++) {
      expect((await register("ZZZZZZZZ", "x", "198.51.100.7")).status).toBe(401);
    }
    expect((await register("ZZZZZZZZ", "x", "198.51.100.7")).status).toBe(429);
    // Other IPs are unaffected.
    expect((await register("ZZZZZZZZ", "x", "198.51.100.8")).status).toBe(401);
  });

  it("buckets IPv6 clients by /64", async () => {
    for (let i = 0; i < 10; i++) {
      await register("ZZZZZZZZ", "x", `2001:db8:1:2::${i + 1}`);
    }
    expect((await register("ZZZZZZZZ", "x", "2001:db8:1:2:ffff::1")).status).toBe(429);
    expect((await register("ZZZZZZZZ", "x", "2001:db8:1:3::1")).status).toBe(401);
  });

  it("normalizes codes", () => {
    expect(normalizePairingCode("abcd-efgh")).toBe("ABCDEFGH");
    expect(normalizePairingCode("0OIL 1111")).toBe("00111111");
    expect(normalizePairingCode("ABCDEFGU")).toBeNull(); // U is not in the alphabet
  });
});

describe("device auth", () => {
  it("rejects missing, malformed and unknown tokens", async () => {
    expect((await call("/v1/devices")).status).toBe(401);
    expect((await call("/v1/devices", { headers: { Authorization: "Basic abc" } })).status).toBe(401);
    expect((await authed("not-a-token", "/v1/devices")).status).toBe(401);
    expect((await authed("A".repeat(43), "/v1/devices")).status).toBe(401);
    const res = await authed("A".repeat(43), "/v1/batches");
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("lists devices and updates lastSeenAt at most once per minute", async () => {
    const a = await newDevice("a");
    await env.DB.prepare("UPDATE devices SET last_seen_at = 1000").run();
    const res = await authed(a.token, "/v1/devices");
    expect(res.status).toBe(200);
    const list = (await res.json()) as Array<Record<string, unknown>>;
    expect(list).toEqual([
      { id: a.deviceId, name: "a", createdAt: expect.any(Number), lastSeenAt: expect.any(Number), revoked: false },
    ]);
    const seen = await env.DB.prepare("SELECT last_seen_at FROM devices").first<{ last_seen_at: number }>();
    expect(seen!.last_seen_at).toBeGreaterThan(1000);

    // Within a minute, no further writes.
    await env.DB.prepare("UPDATE devices SET last_seen_at = ?1").bind(Date.now() - 30_000).run();
    const pinned = (await env.DB.prepare("SELECT last_seen_at FROM devices").first<{ last_seen_at: number }>())!
      .last_seen_at;
    await authed(a.token, "/v1/devices");
    const after = (await env.DB.prepare("SELECT last_seen_at FROM devices").first<{ last_seen_at: number }>())!
      .last_seen_at;
    expect(after).toBe(pinned);
  });

  it("revokes other devices and self", async () => {
    const a = await newDevice("a");
    const b = await newDevice("b");

    const r1 = await authed(a.token, `/v1/devices/${b.deviceId}/revoke`, { method: "POST" });
    expect(r1.status).toBe(200);
    expect((await authed(b.token, "/v1/devices")).status).toBe(401);

    const list = (await (await authed(a.token, "/v1/devices")).json()) as Array<{ id: string; revoked: boolean }>;
    expect(list.find((d) => d.id === b.deviceId)!.revoked).toBe(true);

    expect((await authed(a.token, `/v1/devices/nope/revoke`, { method: "POST" })).status).toBe(404);

    const self = await authed(a.token, `/v1/devices/${a.deviceId}/revoke`, { method: "POST" });
    expect(self.status).toBe(200);
    expect((await authed(a.token, "/v1/devices")).status).toBe(401);
  });

  it("revoked devices cannot create pairing codes", async () => {
    const a = await newDevice("a");
    await authed(a.token, `/v1/devices/${a.deviceId}/revoke`, { method: "POST" });
    expect((await authed(a.token, "/v1/pairing", { method: "POST" })).status).toBe(401);
  });
});
