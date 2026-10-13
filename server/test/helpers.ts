import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { vi } from "vitest";
import app from "../src/index";
import { base64UrlEncode } from "../src/util";

export const ADMIN_DEV_TOKEN = "dev-admin-token-for-tests";
export const BASE = "https://ashidori.example";

export async function call(
  path: string,
  init: RequestInit = {},
  envOverrides: Partial<Cloudflare.Env> = {},
): Promise<Response> {
  const ctx = createExecutionContext();
  const res = await app.fetch(new Request(BASE + path, init), { ...env, ...envOverrides }, ctx);
  await waitOnExecutionContext(ctx);
  return res;
}

export function adminCall(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${ADMIN_DEV_TOKEN}`);
  return call(path, { ...init, headers });
}

export async function createUser(name: string, email?: string, handle?: string): Promise<string> {
  const res = await adminCall("/admin/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, email, handle }),
  });
  if (res.status !== 201) throw new Error(`create user failed: ${res.status}`);
  return ((await res.json()) as { id: string }).id;
}

/** The user that devices belong to unless a test says otherwise (created on demand). */
export async function defaultUserId(): Promise<string> {
  const row = await env.DB.prepare("SELECT id FROM users WHERE name = 'default'").first<{ id: string }>();
  return row?.id ?? (await createUser("default"));
}

/** A pairing code issued from the admin API for `userId` (default: the default user). */
export async function issueAdminCode(userId?: string): Promise<string> {
  const id = userId ?? (await defaultUserId());
  const res = await adminCall(`/admin/users/${id}/pairing`, { method: "POST" });
  if (res.status !== 201) throw new Error(`pairing failed: ${res.status}`);
  return ((await res.json()) as { code: string }).code;
}

export function register(code: string, name = "pixel", ip = "192.0.2.1"): Promise<Response> {
  return call("/v1/devices/register", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: JSON.stringify({ code, name }),
  });
}

/** Env with open self-registration (REGISTRATION is "admin" in wrangler.jsonc). */
export const OPEN: Partial<Cloudflare.Env> = { REGISTRATION: "open" };

/** POST /v1/accounts with `body` as JSON. */
export function selfRegister(
  body: unknown = { deviceName: "pixel" },
  ip = "192.0.2.1",
  envOverrides: Partial<Cloudflare.Env> = OPEN,
): Promise<Response> {
  return call(
    "/v1/accounts",
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
      body: JSON.stringify(body),
    },
    envOverrides,
  );
}

export async function newDevice(name = "pixel", userId?: string): Promise<{ deviceId: string; token: string }> {
  const res = await register(await issueAdminCode(userId), name);
  if (res.status !== 201) throw new Error(`register failed: ${res.status}`);
  return (await res.json()) as { deviceId: string; token: string };
}

export function authed(
  token: string,
  path: string,
  init: RequestInit = {},
  envOverrides: Partial<Cloudflare.Env> = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  return call(path, { ...init, headers }, envOverrides);
}

export async function sha256Hex(data: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function putBatch(
  token: string,
  stream: string,
  seq: number | string,
  body: Uint8Array,
  hash?: string,
  envOverrides: Partial<Cloudflare.Env> = {},
): Promise<Response> {
  return authed(
    token,
    `/v1/batches/${stream}/${seq}`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Content-SHA256": hash ?? (await sha256Hex(body)),
      },
      body,
    },
    envOverrides,
  );
}

export function blob(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

/** Inserts a device as it existed before accounts (user_id NULL). */
export async function legacyDevice(id: string): Promise<{ deviceId: string; token: string }> {
  const token = id.padEnd(43, "x");
  await env.DB.prepare("INSERT INTO devices (id, name, token_hash, created_at) VALUES (?1, ?1, ?2, ?3)")
    .bind(id, await sha256Hex(new TextEncoder().encode(token)), Date.now())
    .run();
  return { deviceId: id, token };
}

// ---- Shared check-ins and follows ----

export const CHECKIN = { t: 1_760_000_000_000, lat: 35.68, lon: 139.76, venue: "Cafe" };

export function putCheckin(token: string, localId: string, body: unknown): Promise<Response> {
  return authed(token, `/v1/checkins/shared/${localId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function unshare(token: string, localId: string): Promise<Response> {
  return authed(token, `/v1/checkins/shared/${localId}`, { method: "DELETE" });
}

export function postJson(token: string, path: string, body?: unknown): Promise<Response> {
  return authed(token, path, {
    method: "POST",
    ...(body === undefined ? {} : { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
  });
}

/** `follower` requests to follow `followee`'s user, and `followee` accepts. */
export async function follow(
  follower: { token: string; userId: string },
  followee: { token: string; userId: string },
): Promise<void> {
  const req = await postJson(follower.token, "/v1/follows", { userId: followee.userId });
  if (req.status !== 200) throw new Error(`follow request failed: ${req.status}`);
  const acc = await postJson(followee.token, `/v1/follow-requests/${follower.userId}/accept`);
  if (acc.status !== 200) throw new Error(`accept failed: ${acc.status}`);
}

/** A user with one device. */
export async function newUser(name: string, handle?: string): Promise<{ userId: string; token: string; deviceId: string }> {
  const userId = await createUser(name, undefined, handle);
  return { userId, ...(await newDevice(name, userId)) };
}

export interface FeedItem {
  id: string;
  cursor: number;
  user: { id: string; handle: string | null; name: string };
  t: number | null;
  lat: number | null;
  lon: number | null;
  venue: string | null;
  category: string | null;
  place: string | null;
  comment: string | null;
  updatedAt: number;
  deleted: boolean;
}

export interface Feed {
  items: FeedItem[];
  next: number | null;
  followsVersion: number;
}

export async function feed(token: string, query = ""): Promise<Feed> {
  const res = await authed(token, `/v1/checkins/feed${query}`);
  if (res.status !== 200) throw new Error(`feed failed: ${res.status}`);
  return (await res.json()) as Feed;
}

// ---- Cloudflare Access JWTs ----

export const ACCESS_TEAM = "ashidori-test.cloudflareaccess.com";
export const ACCESS_AUD = "test-aud";
export const ACCESS_KID = "test-kid";
export const ADMIN_EMAIL = "admin@example.com";
/** ADMIN_EMAILS used by accessCall unless overridden (mixed case and spaces on purpose). */
export const TEST_ADMIN_EMAILS = " Admin@Example.com , second-admin@example.com";

let accessKeys: Promise<{ keyPair: CryptoKeyPair; publicJwk: JsonWebKey }> | null = null;

export async function genRsaKey(): Promise<CryptoKeyPair> {
  return (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
}

function keys(): Promise<{ keyPair: CryptoKeyPair; publicJwk: JsonWebKey }> {
  accessKeys ??= (async () => {
    const keyPair = await genRsaKey();
    const publicJwk = (await crypto.subtle.exportKey("jwk", keyPair.publicKey)) as JsonWebKey;
    return { keyPair, publicJwk };
  })();
  return accessKeys;
}

/** Serves the test signing key as the Access certs endpoint. Restore with vi.restoreAllMocks(). */
export async function mockAccessCerts(): Promise<ReturnType<typeof vi.fn>> {
  const { publicJwk } = await keys();
  const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url === `https://${ACCESS_TEAM}/cdn-cgi/access/certs`) {
      return Response.json({ keys: [{ ...publicJwk, kid: ACCESS_KID, alg: "RS256", use: "sig" }] });
    }
    return new Response("unexpected fetch", { status: 500 });
  });
  return spy as unknown as ReturnType<typeof vi.fn>;
}

/**
 * An Access JWT. `email` defaults to ADMIN_EMAIL; pass `email: undefined` in
 * `claims` to omit it.
 */
export async function makeAccessJwt(
  claims: Record<string, unknown> = {},
  opts: { key?: CryptoKey; kid?: string; alg?: string } = {},
): Promise<string> {
  const enc = (o: unknown) => base64UrlEncode(new TextEncoder().encode(JSON.stringify(o)));
  const header = enc({ alg: opts.alg ?? "RS256", kid: opts.kid ?? ACCESS_KID, typ: "JWT" });
  const now = Math.floor(Date.now() / 1000);
  const payload = enc({
    aud: [ACCESS_AUD],
    iss: `https://${ACCESS_TEAM}`,
    exp: now + 600,
    iat: now,
    email: ADMIN_EMAIL,
    ...claims,
  });
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    opts.key ?? (await keys()).keyPair.privateKey,
    new TextEncoder().encode(`${header}.${payload}`),
  );
  return `${header}.${payload}.${base64UrlEncode(new Uint8Array(sig))}`;
}

/** A request with an Access JWT and the dev token disabled, as in production. */
export function accessCall(
  jwt: string,
  path: string,
  init: RequestInit = {},
  envOverrides: Partial<Cloudflare.Env> = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Cf-Access-Jwt-Assertion", jwt);
  return call(path, { ...init, headers }, { ALLOW_DEV_ADMIN: "false", ADMIN_EMAILS: TEST_ADMIN_EMAILS, ...envOverrides });
}
