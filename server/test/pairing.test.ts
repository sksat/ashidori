import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  authed,
  call,
  createUser,
  issueAdminCode,
  newDevice,
  newToken,
  postJson,
  register,
} from "./helpers";

async function deviceCode(token: string): Promise<string> {
  const res = await postJson(token, "/v1/pairing");
  expect(res.status).toBe(201);
  return ((await res.json()) as { code: string }).code;
}

const count = async (sql: string) => (await env.DB.prepare(sql).first<{ n: number }>())!.n;

describe("pairing codes of revoked devices", () => {
  it("are refused, and not consumed, once the issuing device is revoked", async () => {
    const alice = await createUser("alice");
    const stolen = await newDevice("stolen", alice);
    const kept = await newDevice("kept", alice);
    const code = await deviceCode(stolen.token);
    // The issuer is stored as a column.
    const row = await env.DB.prepare("SELECT issuer_device_id FROM pairing_codes WHERE created_by = ?1")
      .bind(stolen.deviceId)
      .first<{ issuer_device_id: string }>();
    expect(row!.issuer_device_id).toBe(stolen.deviceId);

    // Revoking deletes the device's unused codes in the same batch.
    expect((await postJson(kept.token, `/v1/devices/${stolen.deviceId}/revoke`)).status).toBe(200);
    expect(await count("SELECT COUNT(*) AS n FROM pairing_codes WHERE used_at IS NULL AND issuer_device_id IS NOT NULL")).toBe(0);
    expect((await register(code, "thief")).status).toBe(401);
  });

  it("are refused even if a code row survived the revocation", async () => {
    const alice = await createUser("alice");
    const a = await newDevice("a", alice);
    const code = await deviceCode(a.token);
    // Revoke without the code cleanup, as an older server would have.
    await env.DB.prepare("UPDATE devices SET revoked_at = ?2 WHERE id = ?1").bind(a.deviceId, Date.now()).run();
    expect((await register(code, "x")).status).toBe(401);
    expect(await count("SELECT COUNT(*) AS n FROM pairing_codes WHERE used_at IS NOT NULL")).toBe(1); // only the admin code of `a`
  });

  it("revoking another user's device leaves its codes alone", async () => {
    const alice = await newDevice("alice", await createUser("alice"));
    const bob = await newDevice("bob", await createUser("bob"));
    const code = await deviceCode(bob.token);
    expect((await postJson(alice.token, `/v1/devices/${bob.deviceId}/revoke`)).status).toBe(404);
    expect((await register(code, "bob-2")).status).toBe(201);
  });

  it("admin codes have no issuing device and stay valid when devices are revoked", async () => {
    const alice = await createUser("alice");
    const a = await newDevice("a", alice);
    const code = await issueAdminCode(alice);
    await postJson(a.token, `/v1/devices/${a.deviceId}/revoke`);
    expect((await register(code, "b")).status).toBe(201);
  });
});

describe("atomic registration", () => {
  it("does not consume the code when the device cannot be created", async () => {
    const code = await issueAdminCode();
    const taken = await newDevice("taken");
    // The token is already some device's.
    const res = await register(code, "x", undefined, taken.token);
    expect(res.status).toBe(401);
    expect(await count("SELECT COUNT(*) AS n FROM pairing_codes WHERE used_at IS NULL")).toBe(1);
    expect((await register(code, "y")).status).toBe(201);
  });

  it("rolls back the code when a concurrent request takes the token", async () => {
    // Both requests may pass the token check before either commits; then the
    // loser's device INSERT fails and its batch, code consumption included, rolls back.
    const [c1, c2] = [await issueAdminCode(), await issueAdminCode()];
    const token = newToken();
    const results = await Promise.all([register(c1, "a", undefined, token), register(c2, "b", undefined, token)]);
    expect(results.map((r) => r.status).sort()).toEqual([201, 401]);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM pairing_codes WHERE used_at IS NULL")).toBe(1);
    // A code is consumed if and only if a device was created with it.
    expect(
      await count("SELECT COUNT(*) AS n FROM pairing_codes p JOIN devices d ON d.id = p.used_by"),
    ).toBe(1);
  });

  it("registers one device for concurrent uses of one code", async () => {
    const code = await issueAdminCode();
    const results = await Promise.all([register(code, "a"), register(code, "b"), register(code, "c")]);
    expect(results.map((r) => r.status).sort()).toEqual([201, 401, 401]);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
  });
});

describe("idempotent registration", () => {
  it("returns the same device for a retry with the same code and token", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    const first = await register(code, "pixel", undefined, token);
    expect(first.status).toBe(201);
    const { deviceId } = (await first.json()) as { deviceId: string };

    // The response was lost; the app retries.
    const retry = await register(code, "pixel", undefined, token);
    expect(retry.status).toBe(200);
    expect(await retry.json()).toEqual({ deviceId });
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);

    // Concurrent retries agree.
    const both = await Promise.all([register(code, "p", undefined, token), register(code, "p", undefined, token)]);
    for (const r of both) expect(await r.json()).toEqual({ deviceId });
  });

  it("gives nothing for a used code without its token, or a token with another code", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    expect((await register(code, "a", undefined, token)).status).toBe(201);
    expect((await register(code, "b")).status).toBe(401);
    const other = await issueAdminCode();
    const res = await register(other, "a", undefined, token);
    expect(res.status).toBe(401);
    // The other code was not consumed.
    expect((await register(other, "c")).status).toBe(201);
  });

  it("does not hand out a revoked device again", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    const { deviceId } = (await (await register(code, "a", undefined, token)).json()) as { deviceId: string };
    const admin = await newDevice("admin-side");
    await postJson(admin.token, `/v1/devices/${deviceId}/revoke`);
    expect((await register(code, "a", undefined, token)).status).toBe(401);
  });
});

describe("device limit", () => {
  it("counts all devices of the user, revoked ones included", async () => {
    const LIMIT = { USER_MAX_DEVICES: "2" };
    const alice = await createUser("alice");
    const a = await newDevice("a", alice);
    expect((await register(await issueAdminCode(alice), "b", undefined, newToken(), LIMIT)).status).toBe(201);
    const code = await issueAdminCode(alice);
    const full = await register(code, "c", undefined, newToken(), LIMIT);
    expect(full.status).toBe(409);
    expect(await full.json()).toEqual({ error: "too many devices" });
    // Revoking does not free a slot; the code is not consumed.
    await postJson(a.token, `/v1/devices/${a.deviceId}/revoke`);
    expect((await register(code, "c", undefined, newToken(), LIMIT)).status).toBe(409);
    expect((await register(code, "c", undefined, newToken(), { USER_MAX_DEVICES: "3" })).status).toBe(201);
    // Another user is unaffected.
    expect((await register(await issueAdminCode(await createUser("bob")), "x", undefined, newToken(), LIMIT)).status).toBe(201);
  });

  it("does not reveal the limit for an invalid code", async () => {
    const res = await register("ZZZZZZZZ", "x", undefined, newToken(), { USER_MAX_DEVICES: "1" });
    expect(res.status).toBe(401);
  });
});

describe("misconfigured limits", () => {
  it("refuse new devices", async () => {
    const code = await issueAdminCode();
    const res = await register(code, "x", undefined, newToken(), { USER_MAX_DEVICES: "0" });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "server misconfigured" });
    expect((await register(code, "x")).status).toBe(201);
  });

  it("do not block devices that exist", async () => {
    const a = await newDevice();
    expect((await authed(a.token, "/v1/devices", {}, { MAX_USERS: "-1" })).status).toBe(200);
    expect((await call("/v1/health", {}, { MAX_USERS: "-1" })).status).toBe(200);
  });
});
