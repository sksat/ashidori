// Browser pairing v1 (docs/design/browser-pairing-v1.md, "サーバ API" and
// "閲覧端末とトークンの範囲"): the attempt's state machine, its races, the
// viewer device's read-only scope, the cascade on revocation, expiry and the
// scheduled purge.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { maintenance } from "../src/index";
import { v1 } from "../src/v1";
import { base64UrlEncode } from "../src/util";
import {
  adminCall,
  authed,
  blob,
  call,
  legacyDevice,
  newDevice,
  newToken,
  newUser,
  postJson,
  putBatch,
  register,
  sha256Hex,
} from "./helpers";

const enc = new TextEncoder();
const rand = (n: number) => crypto.getRandomValues(new Uint8Array(n));
const b64 = base64UrlEncode;
const DAY = 24 * 3600 * 1000;

async function sha(...parts: Uint8Array[]): Promise<Uint8Array> {
  const n = parts.reduce((a, p) => a + p.byteLength, 0);
  const all = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    all.set(p, o);
    o += p.byteLength;
  }
  return new Uint8Array(await crypto.subtle.digest("SHA-256", all));
}

const tokenHash = (t: string) => sha256Hex(enc.encode(t));

const B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
/** The same 32 bytes, with non-zero padding bits in the last character (decodes, but is not canonical). */
function nonCanonical(s: string): string {
  const i = B64URL.indexOf(s[s.length - 1]!);
  return s.slice(0, -1) + B64URL[(i & ~3) | 1];
}

interface Browser {
  T: string;
  pkB: string;
  nB: string;
  commit: string;
  id: string;
  code: string;
  expiresAt: number;
}

function jsonPost(token: string | null, path: string, body?: unknown, ip = "192.0.2.10"): Promise<Response> {
  const headers: Record<string, string> = { "CF-Connecting-IP": ip };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return call(path, { method: "POST", headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
}

let ipCounter = 0;
/** A fresh IP per call, so that the per-IP limits of creation never interfere. */
const freshIp = () => `198.51.100.${(ipCounter++ % 250) + 1}`;

async function create(T = newToken(), ip = freshIp()): Promise<Browser> {
  const pk = rand(32);
  const n = rand(32);
  const commit = b64(await sha(enc.encode("ashidori pair commit v1"), pk, n));
  const res = await jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: await tokenHash(T) }, ip);
  expect(res.status).toBe(201);
  const body = (await res.json()) as { id: string; code: string; expiresAt: number };
  expect(Object.keys(body).sort()).toEqual(["code", "expiresAt", "id"]);
  expect(body.id).toMatch(/^[A-Za-z0-9_-]{22}$/);
  expect(body.code).toMatch(/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/);
  return { T, pkB: b64(pk), nB: b64(n), commit, ...body };
}

const claim = (app: string, code: string, ip = freshIp()) => jsonPost(app, "/v1/viewer-pairings/claim", { code }, ip);
const nonce = (app: string, b: Browser, nA = b64(rand(32)), ttl = 3600) =>
  jsonPost(app, `/v1/viewer-pairings/${b.id}/nonce`, { nA, scope: "batch-v1", ttl });
const reveal = (b: Browser) => jsonPost(b.T, `/v1/viewer-pairings/${b.id}/reveal`, { pkB: b.pkB, nB: b.nB });
const confirm = (b: Browser) => jsonPost(b.T, `/v1/viewer-pairings/${b.id}/confirm`);
const approve = (app: string, b: Browser, grant: string) => jsonPost(app, `/v1/viewer-pairings/${b.id}/approve`, { grant });
const abort = (token: string, b: Browser) => jsonPost(token, `/v1/viewer-pairings/${b.id}/abort`);
const poll = (b: Browser) => authed(b.T, `/v1/viewer-pairings/${b.id}`);
const appPoll = (app: string, b: Browser) => authed(app, `/v1/viewer-pairings/${b.id}/app`);
const newGrant = () => b64(rand(113));

/** An attempt up to `revealed`, claimed by `app`. */
async function revealed(app: string, ttl = 3600): Promise<Browser> {
  const b = await create();
  expect((await claim(app, b.code)).status).toBe(200);
  expect((await nonce(app, b, undefined, ttl)).status).toBe(200);
  expect((await reveal(b)).status).toBe(200);
  return b;
}

/** A finished pairing: the browser's token T is now a viewer's. */
async function viewer(app: string, ttl = 3600): Promise<Browser & { deviceId: string; grant: string }> {
  const b = await revealed(app, ttl);
  const grant = newGrant();
  expect(await (await approve(app, b, grant)).json()).toEqual({ state: "revealed" });
  const res = await confirm(b);
  expect(await res.json()).toEqual({ state: "active" });
  const p = (await (await poll(b)).json()) as { deviceId: string };
  return { ...b, deviceId: p.deviceId, grant };
}

const state = async (id: string) =>
  (await env.DB.prepare("SELECT state FROM viewer_pairings WHERE id = ?1").bind(id).first<{ state: string }>())?.state;
const viewerCount = async (userId: string) =>
  (
    await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM devices WHERE user_id = ?1 AND kind = 'viewer' AND revoked_at IS NULL",
    )
      .bind(userId)
      .first<{ n: number }>()
  )!.n;
const expire = (id: string) =>
  env.DB.prepare("UPDATE viewer_pairings SET expires_at = ?2 WHERE id = ?1").bind(id, Date.now() - 1).run();

describe("browser pairing: the normal flow", () => {
  it("approve first: the browser gets the grant and becomes a read-only viewer", async () => {
    const app = await newUser("alice");
    const b = await create();
    expect(b.expiresAt - Date.now()).toBeGreaterThan(9 * 60 * 1000);
    expect(b.expiresAt - Date.now()).toBeLessThanOrEqual(10 * 60 * 1000 + 5000);
    expect(await (await poll(b)).json()).toEqual({ state: "open", expiresAt: b.expiresAt });

    // The app enters the code as the user typed it.
    const typed = `${b.code.slice(0, 4)}-${b.code.slice(4)}`.toLowerCase();
    const cl = await claim(app.token, typed);
    expect(cl.status).toBe(200);
    expect(await cl.json()).toEqual({ id: b.id, commit: b.commit, expiresAt: b.expiresAt });
    expect(await (await poll(b)).json()).toEqual({ state: "claimed", expiresAt: b.expiresAt });

    const nA = b64(rand(32));
    expect(await (await nonce(app.token, b, nA, 86400)).json()).toEqual({ state: "nonce" });
    expect(await (await poll(b)).json()).toEqual({ state: "nonce", expiresAt: b.expiresAt, nA, scope: "batch-v1", ttl: 86400 });
    expect(await (await appPoll(app.token, b)).json()).toEqual({ state: "nonce", expiresAt: b.expiresAt, confirmed: false });

    expect(await (await reveal(b)).json()).toEqual({ state: "revealed" });
    expect(await (await appPoll(app.token, b)).json()).toEqual({
      state: "revealed",
      expiresAt: b.expiresAt,
      confirmed: false,
      pkB: b.pkB,
      nB: b.nB,
    });

    const grant = newGrant();
    expect(await (await approve(app.token, b, grant)).json()).toEqual({ state: "revealed" });
    // Before the browser's confirmation, it gets no grant.
    expect(await (await poll(b)).json()).not.toHaveProperty("grant");
    expect(await (await confirm(b)).json()).toEqual({ state: "active" });

    const p = (await (await poll(b)).json()) as Record<string, unknown>;
    expect(p).toMatchObject({ state: "active", nA, scope: "batch-v1", ttl: 86400, grant });
    const deviceId = p.deviceId as string;
    const viewerExpiresAt = p.viewerExpiresAt as number;
    expect(viewerExpiresAt - Date.now()).toBeGreaterThan(86400_000 - 60_000);
    expect(viewerExpiresAt - Date.now()).toBeLessThanOrEqual(86400_000 + 5000);
    expect(await (await appPoll(app.token, b)).json()).toMatchObject({ state: "active", confirmed: true, deviceId });

    // T is the viewer's token now, and the device list says what it is.
    const list = (await (await authed(b.T, "/v1/devices")).json()) as Array<Record<string, unknown>>;
    expect(list).toContainEqual(expect.objectContaining({ id: deviceId, kind: "viewer", expiresAt: viewerExpiresAt, name: "ブラウザ" }));
    expect(list).toContainEqual(expect.objectContaining({ id: app.deviceId, kind: "device", expiresAt: null }));
    const row = await env.DB.prepare("SELECT token_hash, granted_by, user_id FROM devices WHERE id = ?1")
      .bind(deviceId)
      .first<{ token_hash: string; granted_by: string; user_id: string }>();
    expect(row).toEqual({ token_hash: `v:${await tokenHash(b.T)}`, granted_by: app.deviceId, user_id: app.userId });
  });

  it("confirm first: the approval activates", async () => {
    const app = await newUser("bob");
    const b = await revealed(app.token);
    expect(await (await confirm(b)).json()).toEqual({ state: "revealed" });
    expect(await (await appPoll(app.token, b)).json()).toMatchObject({ confirmed: true, state: "revealed" });
    const res = await approve(app.token, b, newGrant());
    const body = (await res.json()) as { state: string; deviceId: string };
    expect(body.state).toBe("active");
    expect(body.deviceId).toMatch(/^[0-9a-f-]{36}$/);
    expect((await authed(b.T, "/v1/batches")).status).toBe(200);
  });

  it("the viewer reads the user's batches and nothing of other users", async () => {
    const app = await newUser("carol");
    const other = await newUser("dave");
    expect((await putBatch(app.token, "places", 0, blob("mine"))).status).toBe(201);
    expect((await putBatch(other.token, "places", 0, blob("theirs"))).status).toBe(201);
    const v = await viewer(app.token);
    const list = (await (await authed(v.T, "/v1/batches")).json()) as { items: Array<{ deviceId: string }> };
    expect(list.items.map((i) => i.deviceId)).toEqual([app.deviceId]);
    expect((await authed(v.T, `/v1/batches/${app.deviceId}/places/0`)).status).toBe(200);
    expect((await authed(v.T, `/v1/batches/${other.deviceId}/places/0`)).status).toBe(404);
  });
});

describe("browser pairing: validation", () => {
  it("refuses malformed bodies", async () => {
    const commit = b64(rand(32));
    const th = await tokenHash(newToken());
    const bad: unknown[] = [
      { commit },
      { tokenHash: th },
      { commit, tokenHash: th, extra: 1 },
      { commit: b64(rand(31)), tokenHash: th },
      { commit: b64(rand(33)), tokenHash: th },
      { commit, tokenHash: th.toUpperCase() },
      { commit, tokenHash: th.slice(1) },
      // Not canonical: 43 characters whose last one carries non-zero padding bits.
      { commit: nonCanonical(commit), tokenHash: th },
      [commit, th],
      null,
    ];
    for (const body of bad) {
      const res = await jsonPost(null, "/v1/viewer-pairings", body, freshIp());
      expect(res.status, JSON.stringify(body)).toBe(400);
    }
    const app = await newUser("erin");
    const b = await create();
    expect((await claim(app.token, b.code)).status).toBe(200);
    const nA = b64(rand(32));
    for (const body of [
      { nA, scope: "batch-v2", ttl: 3600 },
      { nA, scope: "batch-v1", ttl: 7200 },
      { nA, scope: "batch-v1", ttl: "3600" },
      { nA, scope: "batch-v1" },
      { nA: b64(rand(16)), scope: "batch-v1", ttl: 3600 },
      { nA, scope: "batch-v1", ttl: 3600, x: true },
    ]) {
      expect((await jsonPost(app.token, `/v1/viewer-pairings/${b.id}/nonce`, body)).status, JSON.stringify(body)).toBe(400);
    }
    expect((await nonce(app.token, b)).status).toBe(200);
    for (const body of [{ pkB: b.pkB }, { pkB: b.pkB, nB: b.nB, x: 1 }, { pkB: b64(rand(32)), nB: b.nB }]) {
      expect((await jsonPost(b.T, `/v1/viewer-pairings/${b.id}/reveal`, body)).status, JSON.stringify(body)).toBe(400);
    }
    // A reveal that does not open the commit.
    const wrong = await jsonPost(b.T, `/v1/viewer-pairings/${b.id}/reveal`, { pkB: b.nB, nB: b.pkB });
    expect(await wrong.json()).toEqual({ error: "commit mismatch" });
    expect((await reveal(b)).status).toBe(200);
    for (const grant of [b64(rand(112)), b64(rand(114)), 42]) {
      expect((await jsonPost(app.token, `/v1/viewer-pairings/${b.id}/approve`, { grant })).status).toBe(400);
    }
    expect((await jsonPost(b.T, `/v1/viewer-pairings/${b.id}/confirm`, { x: 1 })).status).toBe(400);
    expect(await state(b.id)).toBe("revealed");
  });

  it("refuses transitions out of order", async () => {
    const app = await newUser("frank");
    const b = await create();
    expect(await (await reveal(b)).json()).toEqual({ error: "invalid state", state: "open" });
    expect(await (await confirm(b)).json()).toEqual({ error: "invalid state", state: "open" });
    expect((await claim(app.token, b.code)).status).toBe(200);
    expect(await (await reveal(b)).json()).toEqual({ error: "invalid state", state: "claimed" });
    expect(await (await approve(app.token, b, newGrant())).json()).toEqual({ error: "invalid state", state: "claimed" });
    expect((await nonce(app.token, b)).status).toBe(200);
    expect(await (await approve(app.token, b, newGrant())).json()).toEqual({ error: "invalid state", state: "nonce" });
  });

  it("authenticates the browser by T and the app as the claiming device only", async () => {
    const app = await newUser("gina");
    const stranger = await newUser("hank");
    const b = await create();
    expect((await authed(newToken(), `/v1/viewer-pairings/${b.id}`)).status).toBe(401);
    expect((await call(`/v1/viewer-pairings/${b.id}`)).status).toBe(401);
    expect((await authed(b.T, `/v1/viewer-pairings/${"A".repeat(22)}`)).status).toBe(401);
    expect((await claim(app.token, b.code)).status).toBe(200);
    // The app's token is not T, and another user's device does not see the attempt.
    expect((await authed(app.token, `/v1/viewer-pairings/${b.id}`)).status).toBe(401);
    expect(await (await nonce(stranger.token, b)).json()).toEqual({ error: "no such pairing" });
    expect((await appPoll(stranger.token, b)).status).toBe(404);
    expect((await abort(stranger.token, b)).status).toBe(404);
    expect(await state(b.id)).toBe("claimed");
  });

  it("keeps tokenHash unique across attempts and devices", async () => {
    const T = newToken();
    const th = await tokenHash(T);
    const commit = b64(rand(32));
    const [r1, r2] = await Promise.all([
      jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: th }, freshIp()),
      jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: th }, freshIp()),
    ]);
    expect([r1.status, r2.status].sort()).toEqual([201, 409]);
    const loser = r1.status === 409 ? r1 : r2;
    expect(await loser.json()).toEqual({ error: "token already in use" });
    // The token of an app device.
    const dev = await newDevice("pixel");
    const res = await jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: await tokenHash(dev.token) }, freshIp());
    expect(await res.json()).toEqual({ error: "token already in use" });
    // The token of a viewer (its attempt is gone after the purge).
    const app = await newUser("ivan");
    const v = await viewer(app.token);
    await env.DB.prepare("DELETE FROM viewer_pairings").run();
    const again = await jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: await tokenHash(v.T) }, freshIp());
    expect(again.status).toBe(409);
  });

  it("rate limits creation per IP", async () => {
    const commit = b64(rand(32));
    for (let i = 0; i < 10; i++) {
      const res = await jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: await tokenHash(newToken()) }, "203.0.113.5");
      expect(res.status).toBe(201);
    }
    const res = await jsonPost(null, "/v1/viewer-pairings", { commit, tokenHash: await tokenHash(newToken()) }, "203.0.113.5");
    expect(res.status).toBe(429);
  });
});

describe("browser pairing: claim", () => {
  it("answers unknown, expired and taken codes alike, and a re-send of the claimer the same", async () => {
    const app = await newUser("jane");
    const other = await newUser("kate");
    expect(await (await claim(app.token, "ABCD2345")).json()).toEqual({ error: "invalid or expired code" });
    expect((await claim(app.token, "not a code")).status).toBe(404);
    const b = await create();
    const first = await (await claim(app.token, b.code)).json();
    expect(await (await claim(app.token, b.code)).json()).toEqual(first);
    expect(await (await claim(other.token, b.code)).json()).toEqual({ error: "invalid or expired code" });
    const late = await create();
    await expire(late.id);
    expect((await claim(app.token, late.code)).status).toBe(404);
    expect(await state(late.id)).toBe("open");
    // An aborted attempt is not claimed again.
    const gone = await create();
    expect(await (await abort(gone.T, gone)).json()).toEqual({ state: "aborted" });
    expect((await claim(app.token, gone.code)).status).toBe(404);
  });

  it("needs a device of a user", async () => {
    const legacy = await legacyDevice("legacy-1");
    const b = await create();
    expect(await (await claim(legacy.token, b.code)).json()).toEqual({ error: "no user" });
    expect((await claim(newToken(), b.code)).status).toBe(401);
  });

  it("counts wrong codes per IP and per user", async () => {
    const app = await newUser("liam");
    for (let i = 0; i < 20; i++) expect((await claim(app.token, "ABCD2345", "203.0.113.9")).status).toBe(404);
    expect((await claim(app.token, "ABCD2345", "203.0.113.9")).status).toBe(429);
    // The same user from elsewhere: the per-user counter is full too.
    expect((await claim(app.token, "ABCD2345", "203.0.113.10")).status).toBe(429);
    // Another user from another IP is not affected.
    const other = await newUser("mona");
    expect((await claim(other.token, "ABCD2345", "203.0.113.11")).status).toBe(404);
  });
});

describe("browser pairing: re-sends", () => {
  it("nonce: the same values again are fine, others conflict", async () => {
    const app = await newUser("nick");
    const b = await create();
    await claim(app.token, b.code);
    const nA = b64(rand(32));
    expect((await nonce(app.token, b, nA)).status).toBe(200);
    expect(await (await nonce(app.token, b, nA)).json()).toEqual({ state: "nonce" });
    expect(await (await nonce(app.token, b, b64(rand(32)))).json()).toEqual({ error: "conflict" });
    expect(await (await nonce(app.token, b, nA, 86400)).json()).toEqual({ error: "conflict" });
    expect((await reveal(b)).status).toBe(200);
    expect(await (await reveal(b)).json()).toEqual({ state: "revealed" });
  });

  it("approve: the same grant gives the same result, another grant 409", async () => {
    const app = await newUser("olga");
    const b = await revealed(app.token);
    const grant = newGrant();
    expect(await (await approve(app.token, b, grant)).json()).toEqual({ state: "revealed" });
    expect(await (await approve(app.token, b, grant)).json()).toEqual({ state: "revealed" });
    expect(await (await approve(app.token, b, newGrant())).json()).toEqual({ error: "conflict" });
    await confirm(b);
    const done = await (await approve(app.token, b, grant)).json();
    expect(done).toMatchObject({ state: "active" });
    expect(await (await approve(app.token, b, grant)).json()).toEqual(done);
    expect((await approve(app.token, b, newGrant())).status).toBe(409);
    // The stored grant is the first one.
    expect(((await (await poll(b)).json()) as { grant: string }).grant).toBe(grant);
  });

  it("confirm after active answers active", async () => {
    const app = await newUser("paul");
    const v = await viewer(app.token);
    expect(await (await confirm(v)).json()).toEqual({ state: "active" });
    expect(await (await confirm(v)).json()).toEqual({ state: "active" });
  });

  it("concurrent approve and confirm activate exactly once", async () => {
    const app = await newUser("quin");
    for (let i = 0; i < 3; i++) {
      const b = await revealed(app.token);
      const [a, c] = await Promise.all([approve(app.token, b, newGrant()), confirm(b)]);
      expect(a.status).toBe(200);
      expect(c.status).toBe(200);
      expect(await state(b.id)).toBe("active");
    }
    expect(await viewerCount(app.userId)).toBe(3);
  });
});

describe("browser pairing: abort", () => {
  it("abort first: the activation does not happen", async () => {
    const app = await newUser("rita");
    const b = await revealed(app.token);
    expect(await (await approve(app.token, b, newGrant())).json()).toEqual({ state: "revealed" });
    expect(await (await abort(b.T, b)).json()).toEqual({ state: "aborted" });
    expect(await (await confirm(b)).json()).toEqual({ error: "invalid state", state: "aborted" });
    expect(await (await approve(app.token, b, newGrant())).json()).toEqual({ error: "invalid state", state: "aborted" });
    expect(await viewerCount(app.userId)).toBe(0);
    expect(await (await poll(b)).json()).toEqual({ state: "aborted", expiresAt: b.expiresAt });
    // The app can abort too; again is a no-op.
    expect(await (await abort(app.token, b)).json()).toEqual({ state: "aborted" });
  });

  it("activation first: the abort gets the active state", async () => {
    const app = await newUser("sam");
    const v = await viewer(app.token);
    expect(await (await abort(app.token, v)).json()).toEqual({ state: "active" });
    expect(await (await abort(v.T, v)).json()).toEqual({ state: "active" });
    expect((await authed(v.T, "/v1/devices")).status).toBe(200);
  });

  it("concurrent abort and confirm: a viewer exists exactly when the attempt is active", async () => {
    const app = await newUser("tina");
    for (let i = 0; i < 4; i++) {
      const b = await revealed(app.token);
      await approve(app.token, b, newGrant());
      const [ab, cf] = await Promise.all([i % 2 ? abort(b.T, b) : abort(app.token, b), confirm(b)]);
      const final = ((await ab.json()) as { state: string }).state;
      expect(["aborted", "active"]).toContain(final);
      expect(await state(b.id)).toBe(final);
      expect(cf.status).toBe(final === "active" ? 200 : 409);
      const viewers = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE token_hash = ?1")
        .bind(`v:${await tokenHash(b.T)}`)
        .first<{ n: number }>();
      expect(viewers!.n).toBe(final === "active" ? 1 : 0);
    }
  });
});

describe("browser pairing: the approving device", () => {
  it("revoked before the confirmation: the attempt is aborted, no viewer remains", async () => {
    const app = await newUser("uma");
    const second = await newDevice("tablet", app.userId);
    const b = await revealed(app.token);
    await approve(app.token, b, newGrant());
    expect((await postJson(second.token, `/v1/devices/${app.deviceId}/revoke`)).status).toBe(200);
    expect(await state(b.id)).toBe("aborted");
    expect(await (await confirm(b)).json()).toEqual({ error: "invalid state", state: "aborted" });
    expect(await viewerCount(app.userId)).toBe(0);
  });

  it("revoked while claimed or after nonce: aborted too", async () => {
    const app = await newUser("vera");
    const b1 = await create();
    await claim(app.token, b1.code);
    const other = await newDevice("other", app.userId);
    const b2 = await create();
    await claim(other.token, b2.code);
    await nonce(other.token, b2);
    // By an admin this time.
    expect((await adminCall(`/admin/devices/${app.deviceId}/revoke`, { method: "POST" })).status).toBe(200);
    expect(await state(b1.id)).toBe("aborted");
    expect(await state(b2.id)).toBe("nonce");
  });

  it("revoked after the activation: its viewers are revoked in the same transaction", async () => {
    const app = await newUser("walt");
    const second = await newDevice("tablet", app.userId);
    const v1v = await viewer(app.token);
    const v2v = await viewer(second.token);
    expect((await postJson(second.token, `/v1/devices/${app.deviceId}/revoke`)).status).toBe(200);
    expect((await authed(v1v.T, "/v1/devices")).status).toBe(401);
    expect((await poll(v1v)).status).toBe(401);
    // A viewer approved by another device is not affected.
    expect((await authed(v2v.T, "/v1/devices")).status).toBe(200);
    expect(await viewerCount(app.userId)).toBe(1);
  });

  it("the activation re-checks the approving device (revocation between approve and confirm, any order)", async () => {
    const app = await newUser("xena");
    const second = await newDevice("tablet", app.userId);
    const b = await revealed(app.token);
    await approve(app.token, b, newGrant());
    const [rv, cf] = await Promise.all([postJson(second.token, `/v1/devices/${app.deviceId}/revoke`), confirm(b)]);
    expect(rv.status).toBe(200);
    // Whichever committed first, no viewer of the revoked device is valid.
    expect([200, 409]).toContain(cf.status);
    expect((await authed(b.T, "/v1/devices")).status).toBe(401);
    expect(await viewerCount(app.userId)).toBe(0);
  });

  it("a disabled user's viewers stop working", async () => {
    const app = await newUser("yuri");
    const v = await viewer(app.token);
    expect((await adminCall(`/admin/users/${app.userId}/disable`, { method: "POST" })).status).toBe(200);
    expect((await authed(v.T, "/v1/devices")).status).toBe(401);
    expect((await poll(v)).status).toBe(401);
  });
});

describe("browser pairing: at most 5 active viewers per user", () => {
  it("refuses the sixth, keeps the attempt revealed, and activates on a re-sent approval after a revocation", async () => {
    const app = await newUser("zoe");
    const vs = [];
    for (let i = 0; i < 5; i++) vs.push(await viewer(app.token));
    const b = await revealed(app.token);
    expect((await confirm(b)).status).toBe(200);
    const grant = newGrant();
    const res = await approve(app.token, b, grant);
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ error: "too many viewers" });
    expect(await state(b.id)).toBe("revealed");
    expect(await (await confirm(b)).json()).toEqual({ state: "revealed" });
    // Revoke the oldest viewer and approve again.
    expect((await postJson(app.token, `/v1/devices/${vs[0]!.deviceId}/revoke`)).status).toBe(200);
    expect(await (await approve(app.token, b, grant)).json()).toMatchObject({ state: "active" });
    expect(await viewerCount(app.userId)).toBe(5);
  });

  it("expired viewers do not count, and viewers do not count towards USER_MAX_DEVICES", async () => {
    const app = await newUser("abby");
    const vs = [];
    for (let i = 0; i < 5; i++) vs.push(await viewer(app.token));
    await env.DB.prepare("UPDATE devices SET expires_at = ?2 WHERE id = ?1").bind(vs[0]!.deviceId, Date.now() - 1).run();
    await viewer(app.token);
    // 1 app device + 6 viewers, with a limit of 2 devices: a second app device still fits.
    const code = ((await (await postJson(app.token, "/v1/pairing")).json()) as { code: string }).code;
    const res = await register(code, "tablet", "192.0.2.77", newToken(), { USER_MAX_DEVICES: "2" });
    expect(res.status).toBe(201);
  });

  it("concurrent activations never exceed the cap", async () => {
    const app = await newUser("bert");
    for (let i = 0; i < 3; i++) await viewer(app.token);
    const pending = [];
    for (let i = 0; i < 4; i++) {
      const b = await revealed(app.token);
      await approve(app.token, b, newGrant());
      pending.push(b);
    }
    const results = await Promise.all(pending.map((b) => confirm(b)));
    expect(results.every((r) => r.status === 200)).toBe(true);
    expect(await viewerCount(app.userId)).toBe(5);
    const states = await Promise.all(pending.map((b) => state(b.id)));
    expect(states.filter((s) => s === "active")).toHaveLength(2);
    expect(states.filter((s) => s === "revealed")).toHaveLength(2);
  });
});

describe("browser pairing: expiry", () => {
  it("refuses every transition towards active after expiry with 410", async () => {
    const app = await newUser("cleo");
    const b1 = await create();
    await claim(app.token, b1.code);
    await expire(b1.id);
    expect(await (await nonce(app.token, b1)).json()).toEqual({ error: "expired" });

    const b2 = await create();
    await claim(app.token, b2.code);
    await nonce(app.token, b2);
    await expire(b2.id);
    expect((await reveal(b2)).status).toBe(410);

    const b3 = await revealed(app.token);
    await expire(b3.id);
    expect((await confirm(b3)).status).toBe(410);
    expect((await approve(app.token, b3, newGrant())).status).toBe(410);

    const b4 = await revealed(app.token);
    await approve(app.token, b4, newGrant());
    await expire(b4.id);
    expect((await confirm(b4)).status).toBe(410);
    expect(await state(b4.id)).toBe("revealed");
    expect(await viewerCount(app.userId)).toBe(0);
    // Abort still works, and the state stays readable.
    expect(await (await abort(b4.T, b4)).json()).toEqual({ state: "aborted" });
    expect((await poll(b4)).status).toBe(200);
  });

  it("recovers the result of an activation whose answer was lost, after the attempt's expiry", async () => {
    const app = await newUser("dina");
    const v = await viewer(app.token);
    await expire(v.id);
    const p = (await (await poll(v)).json()) as Record<string, unknown>;
    expect(p).toMatchObject({ state: "active", grant: v.grant, deviceId: v.deviceId });
    expect(await (await approve(app.token, v, v.grant)).json()).toEqual({ state: "active", deviceId: v.deviceId });
    expect(await (await confirm(v)).json()).toEqual({ state: "active" });
    expect(await (await appPoll(app.token, v)).json()).toMatchObject({ state: "active", deviceId: v.deviceId });
  });

  it("an expired viewer gets 401", async () => {
    const app = await newUser("eddy");
    const v = await viewer(app.token);
    await env.DB.prepare("UPDATE devices SET expires_at = ?2 WHERE id = ?1").bind(v.deviceId, Date.now() - 1).run();
    expect((await authed(v.T, "/v1/devices")).status).toBe(401);
    expect((await authed(v.T, "/v1/batches")).status).toBe(401);
    expect((await poll(v)).status).toBe(401);
  });
});

describe("viewer tokens are read-only", () => {
  /** Public routes: a token plays no part. Routes of the browser's attempt authenticate T. */
  const PUBLIC = new Set(["GET /v1/health", "GET /v1/info", "POST /v1/devices/register", "POST /v1/accounts", "POST /v1/viewer-pairings"]);
  const ATTEMPT_T = new Set([
    "GET /v1/viewer-pairings/:id",
    "POST /v1/viewer-pairings/:id/reveal",
    "POST /v1/viewer-pairings/:id/confirm",
    "POST /v1/viewer-pairings/:id/abort",
  ]);

  it("refuses every route but the allow list (enumerated from the router)", async () => {
    const app = await newUser("fred");
    expect((await putBatch(app.token, "status", 0, blob("x"))).status).toBe(201);
    const v = await viewer(app.token);
    // Every route the /v1 app has (middleware entries are "ALL").
    const routes = [...new Set(v1.routes.filter((r) => r.method !== "ALL").map((r) => `${r.method} /v1${r.path === "/" ? "" : r.path}`.replace(/\/$/, "")))];
    expect(routes.length).toBeGreaterThan(30);
    const fill = (path: string) =>
      path
        .replace("/batches/:deviceId/:stream/:seq", `/batches/${app.deviceId}/status/0`)
        .replace("/devices/:id/revoke", `/devices/${app.deviceId}/revoke`)
        .replace(/\/viewer-pairings\/:id/, `/viewer-pairings/${"B".repeat(22)}`)
        .replace(/:([A-Za-z]+)/g, (_, n: string) => (n === "seq" ? "0" : n === "stream" ? "places" : "x1"));
    const allowed: Record<string, number> = {
      "GET /v1/devices": 200,
      "GET /v1/batches": 200,
      "GET /v1/batches/:deviceId/:stream/:seq": 200,
    };
    const seen: string[] = [];
    for (const route of routes) {
      if (PUBLIC.has(route)) continue;
      const [method, path] = route.split(" ") as [string, string];
      const url = fill(path) + (method === "DELETE" && path.includes("checkins") ? "?rev=1" : "");
      const res = await authed(v.T, url, {
        method,
        ...(method === "GET" || method === "DELETE" ? {} : { headers: { "Content-Type": "application/json" }, body: "{}" }),
      });
      seen.push(route);
      if (ATTEMPT_T.has(route)) {
        // Not this browser's attempt: refused, never served.
        expect([401, 403], route).toContain(res.status);
      } else if (route in allowed) {
        expect(res.status, route).toBe(allowed[route]);
      } else {
        expect(res.status, route).toBe(403);
        expect(await res.json(), route).toEqual({ error: "read-only token" });
      }
    }
    // The routes the design names are among those checked.
    for (const r of [
      "PUT /v1/batches/:stream/:seq",
      "POST /v1/pairing",
      "POST /v1/viewer-pairings/claim",
      "POST /v1/viewer-pairings/:id/approve",
      "GET /v1/checkins/feed",
      "POST /v1/follows",
      "GET /v1/me",
      "POST /v1/devices/:id/revoke",
    ]) {
      expect(seen).toContain(r);
    }
    expect(await viewerCount(app.userId)).toBe(1);
  });

  it("revokes itself only (登録の解除)", async () => {
    const app = await newUser("gwen");
    const v = await viewer(app.token);
    const other = await viewer(app.token);
    expect(await (await postJson(v.T, `/v1/devices/${other.deviceId}/revoke`)).json()).toEqual({ error: "read-only token" });
    expect((await postJson(v.T, `/v1/devices/${encodeURIComponent(v.deviceId)}/revoke`)).status).toBe(200);
    expect((await authed(v.T, "/v1/devices")).status).toBe(401);
    expect((await authed(other.T, "/v1/devices")).status).toBe(200);
    expect((await authed(app.token, "/v1/devices")).status).toBe(200);
  });

  it("an app device revokes a viewer", async () => {
    const app = await newUser("hugo");
    const v = await viewer(app.token);
    expect((await postJson(app.token, `/v1/devices/${v.deviceId}/revoke`)).status).toBe(200);
    expect((await authed(v.T, "/v1/batches")).status).toBe(401);
  });
});

describe("browser pairing: maintenance and the previous Worker", () => {
  it("deletes attempts a day after expiry, and viewers 30 days after expiry or revocation", async () => {
    const app = await newUser("iris");
    const live = await viewer(app.token);
    const ended = await viewer(app.token);
    const revoked = await viewer(app.token);
    const now = Date.now();
    await env.DB.prepare("UPDATE devices SET expires_at = ?2 WHERE id = ?1").bind(ended.deviceId, now - 31 * DAY).run();
    await env.DB.prepare("UPDATE devices SET revoked_at = ?2 WHERE id = ?1").bind(revoked.deviceId, now - 31 * DAY).run();
    await env.DB.prepare("UPDATE viewer_pairings SET expires_at = ?2 WHERE id = ?1").bind(ended.id, now - 2 * DAY).run();
    await env.DB.prepare("UPDATE viewer_pairings SET expires_at = ?2 WHERE id = ?1").bind(revoked.id, now - DAY + 60_000).run();
    await maintenance(env, now);
    const ids = (await env.DB.prepare("SELECT id FROM devices WHERE kind = 'viewer'").all<{ id: string }>()).results.map((r) => r.id);
    expect(ids).toEqual([live.deviceId]);
    expect(await state(ended.id)).toBeUndefined();
    expect(await state(revoked.id)).toBe("active");
    expect(await state(live.id)).toBe("active");
    // App devices with old revocations stay.
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE kind = 'device'").first<{ n: number }>())!.n).toBe(1);
  });

  it("the Worker of migration 0006 never authenticates a viewer token", async () => {
    const app = await newUser("jack");
    const v = await viewer(app.token);
    // deviceAuth of that Worker, verbatim.
    const row = await env.DB.prepare(
      `SELECT d.id, d.name, d.token_hash, d.created_at, d.last_seen_at, d.revoked_at, d.user_id,
         u.status AS user_status
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
       WHERE d.token_hash = ?1`,
    )
      .bind(await tokenHash(v.T))
      .first();
    expect(row).toBeNull();
    // Its device insert (no kind) still makes an app device.
    await env.DB.prepare("INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id) VALUES ('old', 'old', 'h', 0, 0, ?1)")
      .bind(app.userId)
      .run();
    expect(await env.DB.prepare("SELECT kind, expires_at FROM devices WHERE id = 'old'").first()).toEqual({ kind: "device", expires_at: null });
  });
});
