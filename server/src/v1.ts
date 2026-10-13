import { Hono } from "hono";
import { deviceAuth } from "./auth";
import { createPairingCode, listDevices, MAX_DEVICE_NAME, revokeDevice } from "./devices";
import type { AppEnv } from "./env";
import { clientKey, hitRateLimit, RULES } from "./ratelimit";
import { base64UrlEncode, jsonError, normalizePairingCode, randomBytes, readBody, readJson, sha256Hex, TooLarge } from "./util";

export const MAX_BLOB_BYTES = 8 * 1024 * 1024;
const STREAM_RE = /^[a-z][a-z0-9_]{0,31}$/;
// Non-negative integer that fits the 12-digit zero-padded R2 key.
const SEQ_RE = /^(0|[1-9][0-9]{0,11})$/;
const SHA256_RE = /^[0-9a-fA-F]{64}$/;
const LIST_DEFAULT_LIMIT = 100;
const LIST_MAX_LIMIT = 1000;

export function blobKey(deviceId: string, stream: string, seq: number): string {
  return `b/${deviceId}/${stream}/${String(seq).padStart(12, "0")}`;
}

interface BatchRow {
  cursor: number;
  device_id: string;
  stream: string;
  seq: number;
  size: number;
  sha256: string;
  created_at: number;
}

export const v1 = new Hono<AppEnv>();

v1.get("/health", (c) => c.json({ ok: true }));

// ---- Registration (unauthenticated, consumes a pairing code) ----

v1.post("/devices/register", async (c) => {
  if (!(await hitRateLimit(c.env.DB, RULES.register, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  let body: unknown;
  try {
    body = await readJson(c.req.raw);
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "body too large");
    return jsonError(c, 400, "invalid JSON body");
  }
  const { code: rawCode, name: rawName } = (body ?? {}) as { code?: unknown; name?: unknown };
  if (typeof rawCode !== "string" || typeof rawName !== "string") {
    return jsonError(c, 400, "code and name are required");
  }
  const name = rawName.trim();
  if (name.length === 0 || name.length > MAX_DEVICE_NAME || /[\u0000-\u001f\u007f]/.test(name)) {
    return jsonError(c, 400, "invalid name");
  }
  const code = normalizePairingCode(rawCode);
  if (!code) return jsonError(c, 401, "invalid or expired pairing code");

  const now = Date.now();
  const deviceId = crypto.randomUUID();
  // A code of a disabled user is neither accepted nor consumed. The response is
  // the same as for an unknown code, so the endpoint does not reveal it.
  const consumed = await c.env.DB.prepare(
    `UPDATE pairing_codes SET used_at = ?2, used_by = ?3
     WHERE code_hash = ?1 AND used_at IS NULL AND expires_at > ?2
       AND (user_id IS NULL OR user_id IN (SELECT id FROM users WHERE status = 'active'))
     RETURNING user_id`,
  )
    .bind(await sha256Hex(code), now, deviceId)
    .first<{ user_id: string | null }>();
  if (!consumed) return jsonError(c, 401, "invalid or expired pairing code");

  // The device belongs to the code's user (null only for legacy codes).
  const token = base64UrlEncode(randomBytes(32));
  await c.env.DB.prepare(
    "INSERT INTO devices (id, name, token_hash, created_at, last_seen_at, user_id) VALUES (?1, ?2, ?3, ?4, ?4, ?5)",
  )
    .bind(deviceId, name, await sha256Hex(token), now, consumed.user_id)
    .run();
  return c.json({ deviceId, token }, 201);
});

// ---- Everything below requires a device token ----
// A device acts for its user: it sees and manages only that user's devices and
// batches. Legacy devices (user_id NULL) see each other (`IS` matches NULL).

v1.use("/pairing", deviceAuth);
v1.use("/devices", deviceAuth);
v1.use("/devices/:id/revoke", deviceAuth);
v1.use("/batches", deviceAuth);
v1.use("/batches/*", deviceAuth);

v1.post("/pairing", async (c) => {
  if (!(await hitRateLimit(c.env.DB, RULES.pairing, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  const device = c.get("device");
  return c.json(await createPairingCode(c.env.DB, device.id, device.user_id), 201);
});

v1.get("/devices", async (c) => c.json(await listDevices(c.env.DB, c.get("device").user_id)));

v1.post("/devices/:id/revoke", async (c) => {
  const scope = { userId: c.get("device").user_id };
  if (!(await revokeDevice(c.env.DB, c.req.param("id"), scope))) return jsonError(c, 404, "no such device");
  return c.json({ revoked: true });
});

v1.put("/batches/:stream/:seq", async (c) => {
  const stream = c.req.param("stream");
  const seqStr = c.req.param("seq");
  if (!STREAM_RE.test(stream)) return jsonError(c, 400, "invalid stream");
  if (!SEQ_RE.test(seqStr)) return jsonError(c, 400, "invalid seq");
  const seq = Number(seqStr);

  const ct = c.req.header("Content-Type") ?? "";
  if (!/^application\/octet-stream\b/i.test(ct)) return jsonError(c, 415, "body must be application/octet-stream");

  const claimed = c.req.header("X-Content-SHA256") ?? "";
  if (!SHA256_RE.test(claimed)) return jsonError(c, 400, "X-Content-SHA256 (hex) is required");
  const sha256 = claimed.toLowerCase();

  let body: Uint8Array;
  try {
    body = await readBody(c.req.raw, MAX_BLOB_BYTES);
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "blob too large");
    throw e;
  }
  if ((await sha256Hex(body)) !== sha256) return jsonError(c, 400, "X-Content-SHA256 does not match body");

  const deviceId = c.get("device").id; // never from the URL
  const db = c.env.DB;
  const existingStmt = db
    .prepare("SELECT sha256 FROM batches WHERE device_id = ?1 AND stream = ?2 AND seq = ?3")
    .bind(deviceId, stream, seq);

  const existing = await existingStmt.first<{ sha256: string }>();
  if (existing) {
    if (existing.sha256 === sha256) return c.json({ status: "exists" }, 200);
    return jsonError(c, 409, "batch already exists with a different hash");
  }

  // Blob first, then metadata: a batch is listed only once its blob is durable.
  // The cursor is assigned at metadata insert time, so listings never skip it.
  await c.env.BLOBS.put(blobKey(deviceId, stream, seq), body, {
    sha256,
    httpMetadata: { contentType: "application/octet-stream" },
  });
  const inserted = await db
    .prepare(
      `INSERT INTO batches (device_id, stream, seq, size, sha256, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6)
       ON CONFLICT (device_id, stream, seq) DO NOTHING
       RETURNING cursor`,
    )
    .bind(deviceId, stream, seq, body.byteLength, sha256, Date.now())
    .first<{ cursor: number }>();
  if (inserted) return c.json({ status: "created", cursor: inserted.cursor }, 201);

  // Lost a race with a concurrent upload of the same slot.
  const raced = await existingStmt.first<{ sha256: string }>();
  if (raced?.sha256 === sha256) return c.json({ status: "exists" }, 200);
  return jsonError(c, 409, "batch already exists with a different hash");
});

v1.get("/batches", async (c) => {
  const afterStr = c.req.query("after") ?? "0";
  const limitStr = c.req.query("limit") ?? String(LIST_DEFAULT_LIMIT);
  if (!/^[0-9]{1,15}$/.test(afterStr)) return jsonError(c, 400, "invalid cursor");
  if (!/^[0-9]{1,5}$/.test(limitStr)) return jsonError(c, 400, "invalid limit");
  const after = Number(afterStr);
  const limit = Math.min(Math.max(Number(limitStr), 1), LIST_MAX_LIMIT);

  // Fetch one extra row to know whether there is a next page.
  // The cursor is global, so a user's listing has gaps where other users'
  // batches are; clients only rely on it increasing.
  const { results } = await c.env.DB.prepare(
    `SELECT cursor, device_id, stream, seq, size, sha256, created_at
     FROM batches
     WHERE cursor > ?1 AND device_id IN (SELECT id FROM devices WHERE user_id IS ?3)
     ORDER BY cursor LIMIT ?2`,
  )
    .bind(after, limit + 1, c.get("device").user_id)
    .all<BatchRow>();
  const page = results.slice(0, limit);
  const items = page.map((r) => ({
    cursor: r.cursor,
    deviceId: r.device_id,
    stream: r.stream,
    seq: r.seq,
    size: r.size,
    sha256: r.sha256,
    createdAt: r.created_at,
  }));
  const next = results.length > limit ? page[page.length - 1]!.cursor : null;
  return c.json({ items, next });
});

v1.get("/batches/:deviceId/:stream/:seq", async (c) => {
  const { deviceId, stream, seq: seqStr } = c.req.param();
  if (!STREAM_RE.test(stream) || !SEQ_RE.test(seqStr)) return jsonError(c, 404, "not found");
  const seq = Number(seqStr);
  // Another user's batch is indistinguishable from a missing one.
  const meta = await c.env.DB.prepare(
    `SELECT b.size, b.sha256 FROM batches b JOIN devices d ON d.id = b.device_id
     WHERE b.device_id = ?1 AND b.stream = ?2 AND b.seq = ?3 AND d.user_id IS ?4`,
  )
    .bind(deviceId, stream, seq, c.get("device").user_id)
    .first<{ size: number; sha256: string }>();
  if (!meta) return jsonError(c, 404, "not found");
  const obj = await c.env.BLOBS.get(blobKey(deviceId, stream, seq));
  if (!obj) return jsonError(c, 404, "not found");
  return new Response(obj.body, {
    status: 200,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(obj.size),
      "X-Content-SHA256": meta.sha256,
      ETag: `"${meta.sha256}"`,
    },
  });
});
