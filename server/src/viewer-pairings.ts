// Browser pairing v1 (docs/design/browser-pairing-v1.md, "サーバ API"):
// the server side of an attempt in which an app hands the batch key to a
// browser, sealed (HPKE) to the browser's temporary key. The server relays
// public values and stores the attempt's state; it never sees the batch key
// (only the sealed grant) and is not trusted for the key's confidentiality
// or for the confirmation words. What it decides is the state machine:
//
//   open --claim--> claimed --nonce--> nonce --reveal--> revealed --(approve + confirm)--> active
//     \______________\___________________\__________________\__abort / approver revoked--> aborted
//
// Every transition is one conditional statement (or one D1 batch, which is
// one transaction) on the previous state, judged at D1's clock: of two
// concurrent transitions, exactly one wins, and the loser reads the state
// that won. Expiry is not a state: an attempt that is not active is expired
// once D1's clock reaches expires_at, and every transition towards active
// checks that in its own statement (410 after it).
//
// The browser authenticates with its token T (`Authorization: Bearer T`),
// whose hash it sent at creation; once the attempt is active, the same T is
// the viewer device's token. The app authenticates as a device: only the
// device that claimed the attempt may continue it.

import { Hono, type Context, type MiddlewareHandler } from "hono";
import { authenticateToken, bearer, deviceAuth, VIEWER_HASH_PREFIX } from "./auth";
import type { AppEnv } from "./env";
import { clientKey, edgeAllowed, hitRateLimit, RULES } from "./ratelimit";
import {
  base64UrlDecode,
  base64UrlEncode,
  D1_NOW_MS,
  DEVICE_TOKEN_RE,
  generatePairingCode,
  jsonError,
  normalizePairingCode,
  randomBytes,
  readBody,
  sha256Hex,
  timingSafeEqualStr,
  TooLarge,
} from "./util";

/** An attempt lives this long after creation (D1's clock). */
export const VIEWER_PAIRING_TTL_MS = 10 * 60 * 1000;
/** Attempt rows are deleted this long after they expired (result recovery until then). */
export const VIEWER_PAIRING_KEEP_MS = 24 * 60 * 60 * 1000;
/** Active (unrevoked, unexpired) viewers per user. */
export const MAX_ACTIVE_VIEWERS = 5;
export const VIEWER_SCOPE = "batch-v1";
export const VIEWER_TTLS = [3600, 86400] as const;
export const GRANT_BYTES = 113;
/** The viewer device's name (the app and the admin page show viewers as ブラウザ). */
export const VIEWER_NAME = "ブラウザ";
const COMMIT_LABEL = "ashidori pair commit v1";

export type PairingState = "open" | "claimed" | "nonce" | "revealed" | "active" | "aborted";

interface PairingRow {
  id: string;
  token_hash: string;
  commit_b64: string;
  state: PairingState;
  created_at: number;
  expires_at: number;
  claimed_by: string | null;
  user_id: string | null;
  n_a: string | null;
  scope: string | null;
  ttl: number | null;
  pk_b: string | null;
  n_b: string | null;
  grant_b64: string | null;
  approved_at: number | null;
  confirmed_at: number | null;
  device_id: string | null;
  /** 1 if D1's clock >= expires_at when the row was read. */
  expired: number;
}

const ID_RE = /^[A-Za-z0-9_-]{22}$/;
const TOKEN_HASH_RE = /^[0-9a-f]{64}$/;
const MAX_BODY = 4 * 1024;

export const viewerPairings = new Hono<AppEnv>();

// ---- helpers ----

/** Canonical unpadded base64url of exactly `len` bytes, or null. */
function b64(v: unknown, len: number): string | null {
  if (typeof v !== "string" || !/^[A-Za-z0-9_-]+$/.test(v)) return null;
  let bytes: Uint8Array;
  try {
    bytes = base64UrlDecode(v);
  } catch {
    return null;
  }
  if (bytes.byteLength !== len || base64UrlEncode(bytes) !== v) return null;
  return v;
}

type Body = Record<string, unknown>;

/**
 * A JSON object with exactly `keys` (no others, none missing), or a 4xx
 * response. `empty` also accepts no body at all (confirm, abort).
 */
async function strictBody(c: Context<AppEnv>, keys: readonly string[], empty = false): Promise<Body | Response> {
  let raw: Uint8Array;
  try {
    raw = await readBody(c.req.raw, MAX_BODY);
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "body too large");
    throw e;
  }
  if (raw.byteLength === 0 && empty) return {};
  if (!/^application\/json\b/i.test(c.req.header("Content-Type") ?? "")) return jsonError(c, 400, "invalid JSON body");
  let body: unknown;
  try {
    body = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(raw));
  } catch {
    return jsonError(c, 400, "invalid JSON body");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) return jsonError(c, 400, "invalid JSON body");
  const got = Object.keys(body);
  if (got.length !== keys.length || !keys.every((k) => got.includes(k))) return jsonError(c, 400, "unexpected fields");
  return body as Body;
}

async function load(db: D1Database, id: string): Promise<PairingRow | null> {
  return db
    .prepare(
      `SELECT id, token_hash, commit_b64, state, created_at, expires_at, claimed_by, user_id, n_a, scope, ttl,
         pk_b, n_b, grant_b64, approved_at, confirmed_at, device_id,
         CASE WHEN ${D1_NOW_MS} >= expires_at THEN 1 ELSE 0 END AS expired
       FROM viewer_pairings WHERE id = ?1`,
    )
    .bind(id)
    .first<PairingRow>();
}

/** The answer to a transition that did not apply: 410 if expired before active, else 409 with the state. */
function refused(c: Context<AppEnv>, row: PairingRow): Response {
  if (row.state !== "active" && row.state !== "aborted" && row.expired) return jsonError(c, 410, "expired");
  return c.json({ error: "invalid state", state: row.state }, 409);
}

/** The attempt, if the request carries its token T (constant-time hash comparison); else null. */
async function browserAttempt(c: Context<AppEnv>): Promise<PairingRow | null> {
  const id = c.req.param("id") ?? "";
  const token = bearer(c.req.raw);
  if (!ID_RE.test(id) || !token || !DEVICE_TOKEN_RE.test(token)) return null;
  const row = await load(c.env.DB, id);
  if (!row) return null;
  return timingSafeEqualStr(row.token_hash, await sha256Hex(token)) ? row : null;
}

/** The attempt, if the authenticated device claimed it; else null (404, like a missing one). */
async function claimedAttempt(c: Context<AppEnv>): Promise<PairingRow | null> {
  const id = c.req.param("id") ?? "";
  if (!ID_RE.test(id)) return null;
  const row = await load(c.env.DB, id);
  return row && row.claimed_by !== null && row.claimed_by === c.get("device").id ? row : null;
}

/**
 * The activation, as statements to run after a transition in the same
 * batch (one transaction): creates the viewer and makes the attempt active
 * only if it is revealed, approved and confirmed, not expired (D1's clock in
 * the statement), its approving device is an unrevoked app device of an
 * active user, and that user has fewer than MAX_ACTIVE_VIEWERS active
 * viewers. Whichever of approve and confirm commits last activates; a
 * concurrent abort or revocation of the approving device either commits
 * first (then nothing is created) or after (then it sees an active attempt,
 * resp. revokes the viewer it granted).
 */
function activation(db: D1Database, id: string): D1PreparedStatement[] {
  const deviceId = crypto.randomUUID();
  return [
    db
      .prepare(
        `INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id, kind, expires_at, granted_by)
         SELECT ?2, ?3, '${VIEWER_HASH_PREFIX}' || p.token_hash, n, n - n % 3600000, p.user_id, 'viewer',
           n + p.ttl * 1000, p.claimed_by
         FROM viewer_pairings p, (SELECT ${D1_NOW_MS} AS n)
         WHERE p.id = ?1 AND p.state = 'revealed' AND p.approved_at IS NOT NULL AND p.confirmed_at IS NOT NULL
           AND n < p.expires_at
           AND EXISTS (SELECT 1 FROM devices a WHERE a.id = p.claimed_by AND a.revoked_at IS NULL
                         AND a.kind = 'device' AND a.user_id = p.user_id)
           AND EXISTS (SELECT 1 FROM users u WHERE u.id = p.user_id AND u.status = 'active')
           AND (SELECT COUNT(*) FROM devices v WHERE v.user_id = p.user_id AND v.kind = 'viewer'
                  AND v.revoked_at IS NULL AND v.expires_at > n) < ?4`,
      )
      .bind(id, deviceId, VIEWER_NAME, MAX_ACTIVE_VIEWERS),
    db
      .prepare(
        `UPDATE viewer_pairings SET state = 'active', device_id = ?2, activated_at = ${D1_NOW_MS}
         WHERE id = ?1 AND state = 'revealed' AND EXISTS (SELECT 1 FROM devices WHERE id = ?2)`,
      )
      .bind(id, deviceId),
  ];
}

function isUniqueError(e: unknown, column: string): boolean {
  return e instanceof Error && e.message.includes(`UNIQUE constraint failed: ${column}`);
}

/** Runs a transition with the activation after it; a viewer-token collision fails the whole batch (409). */
async function withActivation(c: Context<AppEnv>, id: string, first: D1PreparedStatement): Promise<Response | null> {
  try {
    await c.env.DB.batch([first, ...activation(c.env.DB, id)]);
    return null;
  } catch (e) {
    if (isUniqueError(e, "devices.token_hash")) return jsonError(c, 409, "token already in use");
    throw e;
  }
}

/** Edge limiter by client IP, before anything reads D1. */
function edgeByIp(bucket: string): MiddlewareHandler<AppEnv> {
  return async (c, next) => {
    if (!(await edgeAllowed(c.env.RL_PAIRING, "RL_PAIRING", `${bucket}:ip:${clientKey(c.req.raw)}`))) {
      return jsonError(c, 429, "too many requests");
    }
    await next();
  };
}

// ---- the browser ----

// Create: `{commit, tokenHash}` → 201 {id, code, expiresAt}. Unauthenticated
// and rate limited per IP. tokenHash is unique across attempts (UNIQUE) and
// devices (checked in the statement): the browser makes a new T on 409.
viewerPairings.post("/", edgeByIp("viewer"), async (c) => {
  const body = await strictBody(c, ["commit", "tokenHash"]);
  if (body instanceof Response) return body;
  const commit = b64(body.commit, 32);
  const tokenHash = typeof body.tokenHash === "string" && TOKEN_HASH_RE.test(body.tokenHash) ? body.tokenHash : null;
  if (!commit) return jsonError(c, 400, "invalid commit");
  if (!tokenHash) return jsonError(c, 400, "invalid tokenHash");
  if (!(await hitRateLimit(c.env.DB, RULES.viewerCreate, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  for (let i = 0; i < 3; i++) {
    const id = base64UrlEncode(randomBytes(16));
    const code = generatePairingCode();
    try {
      const row = await c.env.DB.prepare(
        `INSERT INTO viewer_pairings (id, code_hash, token_hash, commit_b64, state, created_at, expires_at)
         SELECT ?1, ?2, ?3, ?4, 'open', n, n + ?5 FROM (SELECT ${D1_NOW_MS} AS n)
         WHERE NOT EXISTS (SELECT 1 FROM devices WHERE token_hash IN (?3, '${VIEWER_HASH_PREFIX}' || ?3))
         RETURNING expires_at`,
      )
        .bind(id, await sha256Hex(code), tokenHash, commit, VIEWER_PAIRING_TTL_MS)
        .first<{ expires_at: number }>();
      if (!row) return jsonError(c, 409, "token already in use");
      return c.json({ id, code, expiresAt: row.expires_at }, 201);
    } catch (e) {
      if (isUniqueError(e, "viewer_pairings.token_hash")) return jsonError(c, 409, "token already in use");
      // A code or id that another attempt has: draw again.
      if (isUniqueError(e, "viewer_pairings.code_hash") || isUniqueError(e, "viewer_pairings.id")) continue;
      throw e;
    }
  }
  throw new Error("could not draw an unused pairing code");
});

// Claim: an app device takes the attempt by the code the browser shows.
// Unknown, expired, or claimed by another device: 404 alike.
viewerPairings.use("/claim", edgeByIp("viewer-claim"), deviceAuth);
viewerPairings.post("/claim", async (c) => {
  const device = c.get("device");
  if (device.user_id === null) return jsonError(c, 403, "no user");
  if (!(await edgeAllowed(c.env.RL_PAIRING, "RL_PAIRING", `viewer-claim:user:${device.user_id}`))) {
    return jsonError(c, 429, "too many requests");
  }
  const body = await strictBody(c, ["code"]);
  if (body instanceof Response) return body;
  if (typeof body.code !== "string") return jsonError(c, 400, "invalid code");
  // Counted after validation, before the code is looked at: wrong codes count.
  if (!(await hitRateLimit(c.env.DB, RULES.viewerClaim, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  if (!(await hitRateLimit(c.env.DB, RULES.viewerClaimUser, device.user_id))) {
    return jsonError(c, 429, "too many requests");
  }
  const code = normalizePairingCode(body.code);
  if (!code) return jsonError(c, 404, "invalid or expired code");
  const codeHash = await sha256Hex(code);
  const claimed = await c.env.DB.prepare(
    `UPDATE viewer_pairings SET state = 'claimed', claimed_by = ?2, user_id = ?3
     WHERE code_hash = ?1 AND state = 'open' AND ${D1_NOW_MS} < expires_at
     RETURNING id, commit_b64, expires_at`,
  )
    .bind(codeHash, device.id, device.user_id)
    .first<{ id: string; commit_b64: string; expires_at: number }>();
  // A re-send by the device that claimed it gets the same answer.
  const row =
    claimed ??
    (await c.env.DB.prepare(
      `SELECT id, commit_b64, expires_at FROM viewer_pairings
       WHERE code_hash = ?1 AND claimed_by = ?2 AND state <> 'aborted'`,
    )
      .bind(codeHash, device.id)
      .first<{ id: string; commit_b64: string; expires_at: number }>());
  if (!row) return jsonError(c, 404, "invalid or expired code");
  return c.json({ id: row.id, commit: row.commit_b64, expiresAt: row.expires_at });
});

for (const path of ["/:id/nonce", "/:id/app", "/:id/approve"]) viewerPairings.use(path, deviceAuth);

// Polling by the browser: answers from the stored state, also after expiry
// (result recovery). Once active, the grant and the viewer while it is valid.
viewerPairings.get("/:id", async (c) => {
  const row = await browserAttempt(c);
  if (!row) return jsonError(c, 401, "unauthorized");
  const out: Record<string, unknown> = { state: row.state, expiresAt: row.expires_at };
  if (row.state === "nonce" || row.state === "revealed" || row.state === "active") {
    out.nA = row.n_a;
    out.scope = row.scope;
    out.ttl = row.ttl;
  }
  if (row.state === "active") {
    const viewer = await c.env.DB.prepare(
      `SELECT d.expires_at FROM devices d LEFT JOIN users u ON u.id = d.user_id
       WHERE d.id = ?1 AND d.kind = 'viewer' AND d.revoked_at IS NULL AND d.expires_at > ${D1_NOW_MS}
         AND u.status = 'active'`,
    )
      .bind(row.device_id)
      .first<{ expires_at: number }>();
    // T is now the viewer's token: revoked or expired, it is no longer valid.
    if (!viewer) return jsonError(c, 401, "unauthorized");
    out.grant = row.grant_b64;
    out.deviceId = row.device_id;
    out.viewerExpiresAt = viewer.expires_at;
  }
  return c.json(out);
});

// Reveal: `{pkB, nB}`, which must open the commit. Only after the app's nonce.
viewerPairings.post("/:id/reveal", async (c) => {
  const row = await browserAttempt(c);
  if (!row) return jsonError(c, 401, "unauthorized");
  const body = await strictBody(c, ["pkB", "nB"]);
  if (body instanceof Response) return body;
  const pkB = b64(body.pkB, 32);
  const nB = b64(body.nB, 32);
  if (!pkB || !nB) return jsonError(c, 400, "invalid pkB or nB");
  const label = new TextEncoder().encode(COMMIT_LABEL);
  const input = new Uint8Array(label.byteLength + 64);
  input.set(label, 0);
  input.set(base64UrlDecode(pkB), label.byteLength);
  input.set(base64UrlDecode(nB), label.byteLength + 32);
  const commit = base64UrlEncode(new Uint8Array(await crypto.subtle.digest("SHA-256", input)));
  if (commit !== row.commit_b64) return jsonError(c, 400, "commit mismatch");
  const done = await c.env.DB.prepare(
    `UPDATE viewer_pairings SET state = 'revealed', pk_b = ?2, n_b = ?3
     WHERE id = ?1 AND state = 'nonce' AND ${D1_NOW_MS} < expires_at RETURNING state`,
  )
    .bind(row.id, pkB, nB)
    .first();
  if (done) return c.json({ state: "revealed" });
  const now = (await load(c.env.DB, row.id))!;
  if (now.state !== "aborted" && now.pk_b !== null) {
    return now.pk_b === pkB && now.n_b === nB ? c.json({ state: now.state }) : jsonError(c, 409, "conflict");
  }
  return refused(c, now);
});

// Confirm: the browser's 「一致する」. Activates if the app approved already.
viewerPairings.post("/:id/confirm", async (c) => {
  const row = await browserAttempt(c);
  if (!row) return jsonError(c, 401, "unauthorized");
  const body = await strictBody(c, [], true);
  if (body instanceof Response) return body;
  const failed = await withActivation(
    c,
    row.id,
    c.env.DB.prepare(
      `UPDATE viewer_pairings SET confirmed_at = COALESCE(confirmed_at, ${D1_NOW_MS})
       WHERE id = ?1 AND state = 'revealed' AND ${D1_NOW_MS} < expires_at`,
    ).bind(row.id),
  );
  if (failed) return failed;
  const now = (await load(c.env.DB, row.id))!;
  if (now.state === "active" || (now.state === "revealed" && now.confirmed_at !== null && !now.expired)) {
    return c.json({ state: now.state });
  }
  return refused(c, now);
});

// Abort: by the browser (T) or by the device that claimed it. Answers the
// current state: aborted, or active if the activation committed first.
viewerPairings.post("/:id/abort", async (c) => {
  const id = c.req.param("id");
  const token = bearer(c.req.raw);
  if (!ID_RE.test(id) || !token || !DEVICE_TOKEN_RE.test(token)) return jsonError(c, 401, "unauthorized");
  const row = await load(c.env.DB, id);
  let allowed = row !== null && timingSafeEqualStr(row.token_hash, await sha256Hex(token));
  if (!allowed) {
    const device = await authenticateToken(c.env.DB, token);
    if (!device) return jsonError(c, 401, "unauthorized");
    if (device.kind !== "device") return jsonError(c, 403, "read-only token");
    allowed = row !== null && row.claimed_by === device.id;
    if (!allowed) return jsonError(c, 404, "no such pairing");
  }
  const body = await strictBody(c, [], true);
  if (body instanceof Response) return body;
  await c.env.DB.prepare(
    `UPDATE viewer_pairings SET state = 'aborted', aborted_at = ${D1_NOW_MS}
     WHERE id = ?1 AND state NOT IN ('active', 'aborted')`,
  )
    .bind(id)
    .run();
  const now = (await load(c.env.DB, id))!;
  return c.json({ state: now.state });
});

// ---- the app (the device that claimed the attempt) ----

// Nonce: `{nA, scope, ttl}`, fixed by the app before it sees pkB and nB.
viewerPairings.post("/:id/nonce", async (c) => {
  const row = await claimedAttempt(c);
  if (!row) return jsonError(c, 404, "no such pairing");
  const body = await strictBody(c, ["nA", "scope", "ttl"]);
  if (body instanceof Response) return body;
  const nA = b64(body.nA, 32);
  if (!nA) return jsonError(c, 400, "invalid nA");
  if (body.scope !== VIEWER_SCOPE) return jsonError(c, 400, "invalid scope");
  if (typeof body.ttl !== "number" || !(VIEWER_TTLS as readonly number[]).includes(body.ttl)) {
    return jsonError(c, 400, "invalid ttl");
  }
  const done = await c.env.DB.prepare(
    `UPDATE viewer_pairings SET state = 'nonce', n_a = ?2, scope = ?3, ttl = ?4
     WHERE id = ?1 AND state = 'claimed' AND ${D1_NOW_MS} < expires_at RETURNING state`,
  )
    .bind(row.id, nA, body.scope, body.ttl)
    .first();
  if (done) return c.json({ state: "nonce" });
  const now = (await load(c.env.DB, row.id))!;
  if (now.state !== "aborted" && now.n_a !== null) {
    return now.n_a === nA && now.scope === body.scope && now.ttl === body.ttl
      ? c.json({ state: now.state })
      : jsonError(c, 409, "conflict");
  }
  return refused(c, now);
});

// The app's polling: pkB and nB once revealed, whether the browser confirmed.
viewerPairings.get("/:id/app", async (c) => {
  const row = await claimedAttempt(c);
  if (!row) return jsonError(c, 404, "no such pairing");
  const out: Record<string, unknown> = { state: row.state, expiresAt: row.expires_at, confirmed: row.confirmed_at !== null };
  if (row.state === "revealed" || row.state === "active") {
    out.pkB = row.pk_b;
    out.nB = row.n_b;
  }
  if (row.state === "active") out.deviceId = row.device_id;
  return c.json(out);
});

// Approve: `{grant}`, sealed once by the app; a re-send must be the same
// bytes. Activates if the browser confirmed already. At the viewer cap the
// attempt stays revealed with the grant kept: approving again retries.
viewerPairings.post("/:id/approve", async (c) => {
  const row = await claimedAttempt(c);
  if (!row) return jsonError(c, 404, "no such pairing");
  const body = await strictBody(c, ["grant"]);
  if (body instanceof Response) return body;
  const grant = b64(body.grant, GRANT_BYTES);
  if (!grant) return jsonError(c, 400, "invalid grant");
  const failed = await withActivation(
    c,
    row.id,
    c.env.DB.prepare(
      `UPDATE viewer_pairings SET grant_b64 = ?2, approved_at = ${D1_NOW_MS}
       WHERE id = ?1 AND state = 'revealed' AND grant_b64 IS NULL AND ${D1_NOW_MS} < expires_at`,
    ).bind(row.id, grant),
  );
  if (failed) return failed;
  const now = (await load(c.env.DB, row.id))!;
  if (now.state === "aborted" || now.grant_b64 === null) return refused(c, now);
  if (now.grant_b64 !== grant) return jsonError(c, 409, "conflict");
  if (now.state === "active") return c.json({ state: "active", deviceId: now.device_id });
  if (now.expired) return jsonError(c, 410, "expired");
  if (now.confirmed_at === null) return c.json({ state: now.state });
  // Approved and confirmed, not expired, yet not active: the viewer cap
  // (the approving device and its user are valid, or deviceAuth refused).
  return jsonError(c, 409, "too many viewers");
});

// ---- maintenance ----

/** Scheduled: deletes attempts that expired more than VIEWER_PAIRING_KEEP_MS ago, at most `limit`. */
export async function deleteOldViewerPairings(db: D1Database, now: number, limit = 1000): Promise<number> {
  const res = await db
    .prepare("DELETE FROM viewer_pairings WHERE id IN (SELECT id FROM viewer_pairings WHERE expires_at < ?1 LIMIT ?2)")
    .bind(now - VIEWER_PAIRING_KEEP_MS, limit)
    .run();
  return res.meta.changes;
}
