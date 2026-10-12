import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { base64UrlEncode } from "../src/util";
import { ADMIN_DEV_TOKEN, adminCall, call, newDevice } from "./helpers";

const TEAM = "ashidori-test.cloudflareaccess.com";
const AUD = "test-aud";
const KID = "test-kid";

let keyPair: CryptoKeyPair;
let otherKeyPair: CryptoKeyPair;
let publicJwk: JsonWebKey;

async function genKey(): Promise<CryptoKeyPair> {
  return (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
}

beforeAll(async () => {
  keyPair = await genKey();
  otherKeyPair = await genKey();
  publicJwk = (await crypto.subtle.exportKey("jwk", keyPair.publicKey)) as JsonWebKey;
});

afterEach(() => {
  vi.restoreAllMocks();
});

function mockCerts(): ReturnType<typeof vi.fn> {
  const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url === `https://${TEAM}/cdn-cgi/access/certs`) {
      return Response.json({ keys: [{ ...publicJwk, kid: KID, alg: "RS256", use: "sig" }] });
    }
    return new Response("unexpected fetch", { status: 500 });
  });
  return spy as unknown as ReturnType<typeof vi.fn>;
}

async function makeJwt(
  claims: Record<string, unknown>,
  opts: { key?: CryptoKey; kid?: string; alg?: string } = {},
): Promise<string> {
  const enc = (o: unknown) => base64UrlEncode(new TextEncoder().encode(JSON.stringify(o)));
  const header = enc({ alg: opts.alg ?? "RS256", kid: opts.kid ?? KID, typ: "JWT" });
  const now = Math.floor(Date.now() / 1000);
  const payload = enc({ aud: [AUD], iss: `https://${TEAM}`, exp: now + 600, iat: now, email: "me@example.com", ...claims });
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    opts.key ?? keyPair.privateKey,
    new TextEncoder().encode(`${header}.${payload}`),
  );
  return `${header}.${payload}.${base64UrlEncode(new Uint8Array(sig))}`;
}

function withJwt(jwt: string, path = "/admin/devices", init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Cf-Access-Jwt-Assertion", jwt);
  return call(path, { ...init, headers }, { ALLOW_DEV_ADMIN: "false" });
}

describe("admin auth", () => {
  it("rejects requests without Access, even with the dev token when dev admin is disabled", async () => {
    const prod = { ALLOW_DEV_ADMIN: "false" };
    expect((await call("/admin/devices", {}, prod)).status).toBe(401);
    expect((await call("/admin/pairing", { method: "POST" }, prod)).status).toBe(401);
    expect((await call("/admin/", {}, prod)).status).toBe(401);
    const withDev = await call(
      "/admin/pairing",
      { method: "POST", headers: { Authorization: `Bearer ${ADMIN_DEV_TOKEN}` } },
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

  it("accepts a valid Access JWT", async () => {
    const spy = mockCerts();
    const res = await withJwt(await makeJwt({}));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([]);
    const pairing = await withJwt(await makeJwt({}), "/admin/pairing", { method: "POST" });
    expect(pairing.status).toBe(201);
    // Certs are cached.
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("accepts a string aud claim", async () => {
    mockCerts();
    expect((await withJwt(await makeJwt({ aud: AUD }))).status).toBe(200);
  });

  it("rejects JWTs with bad signature, wrong aud/iss, expiry, unknown kid, or wrong alg", async () => {
    mockCerts();
    const now = Math.floor(Date.now() / 1000);
    const cases: Array<Promise<string>> = [
      makeJwt({}, { key: otherKeyPair.privateKey }),
      makeJwt({ aud: ["other-aud"] }),
      makeJwt({ iss: "https://evil.cloudflareaccess.com" }),
      makeJwt({ exp: now - 3600 }),
      makeJwt({ nbf: now + 3600 }),
      makeJwt({}, { kid: "unknown" }),
      makeJwt({}, { alg: "HS256" }),
      Promise.resolve("not.a.jwt"),
      Promise.resolve("garbage"),
    ];
    for (const jwt of await Promise.all(cases)) {
      const res = await withJwt(jwt);
      expect(res.status, jwt).toBe(403);
    }
  });

  it("rejects everything when Access is not configured", async () => {
    mockCerts();
    const jwt = await makeJwt({});
    const res = await call(
      "/admin/devices",
      { headers: { "Cf-Access-Jwt-Assertion": jwt } },
      { ALLOW_DEV_ADMIN: "false", CF_ACCESS_AUD: "", CF_ACCESS_TEAM_DOMAIN: "" },
    );
    expect(res.status).toBe(403);
  });

  it("rejects cross-origin admin requests", async () => {
    const res = await adminCall("/admin/pairing", { method: "POST", headers: { Origin: "https://evil.example" } });
    expect(res.status).toBe(403);
  });
});

describe("admin API", () => {
  it("issues codes, lists and revokes devices", async () => {
    const a = await newDevice("phone");
    const list = (await (await adminCall("/admin/devices")).json()) as Array<{ id: string; revoked: boolean }>;
    expect(list).toHaveLength(1);
    expect(list[0]!.id).toBe(a.deviceId);

    const rev = await adminCall(`/admin/devices/${a.deviceId}/revoke`, { method: "POST" });
    expect(rev.status).toBe(200);
    expect((await call("/v1/devices", { headers: { Authorization: `Bearer ${a.token}` } })).status).toBe(401);
    expect((await adminCall(`/admin/devices/unknown/revoke`, { method: "POST" })).status).toBe(404);
  });

  it("serves the admin page with a nonce CSP", async () => {
    const res = await adminCall("/admin/");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toMatch(/text\/html/);
    expect(res.headers.get("Content-Security-Policy")).toMatch(/script-src 'nonce-/);
  });

  it("rate limits pairing code creation", async () => {
    for (let i = 0; i < 20; i++) {
      expect((await adminCall("/admin/pairing", { method: "POST" })).status).toBe(201);
    }
    expect((await adminCall("/admin/pairing", { method: "POST" })).status).toBe(429);
  });
});
