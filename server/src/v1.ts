import { Hono, type Context, type MiddlewareHandler } from "hono";
import { createAccount, registrationMode } from "./accounts";
import { deviceAuth } from "./auth";
import { committedBlob, MAX_BLOB_BYTES, MIN_BLOB_BYTES, putBatch } from "./batches";
import {
  listFeed,
  LOCAL_ID_RE,
  parseCheckin,
  parseRev,
  unshareCheckin,
  unshareCheckinById,
  upsertCheckin,
} from "./checkins";
import { type Limits, parseLimits } from "./config";
import {
  acceptFollow,
  deleteFollow,
  listFollowers,
  listFollowing,
  lookupHandle,
  normalizeHandle,
  requestFollow,
} from "./follows";
import { createPairingCode, listDevices, parseDeviceName, registerWithCode, revokeDevice } from "./devices";
import type { AppEnv } from "./env";
import { clientKey, edgeAllowed, hitRateLimit, RULES } from "./ratelimit";
import {
  DEVICE_TOKEN_RE,
  floorHour,
  jsonError,
  normalizePairingCode,
  readBody,
  readJson,
  sha256Hex,
  TooLarge,
} from "./util";

export { MAX_BLOB_BYTES };
const STREAM_RE = /^[a-z][a-z0-9_]{0,31}$/;
// Non-negative integer that fits the 12-digit zero-padded R2 key.
const SEQ_RE = /^(0|[1-9][0-9]{0,11})$/;
const SHA256_RE = /^[0-9a-fA-F]{64}$/;
const LIST_DEFAULT_LIMIT = 100;
const LIST_MAX_LIMIT = 1000;
const FEED_MAX_LIMIT = 500;
/** Device (and user) name of a self-registered account when the app sends none. */
const DEFAULT_DEVICE_NAME = "device";

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

/** The limits, or a 503 response if a limit variable is invalid (fail closed: nothing new is created). */
function limitsOr503(c: Context<AppEnv>): Limits | Response {
  const r = parseLimits(c.env);
  if (r.ok) return r.limits;
  console.error("invalid limit variables; refusing new users, devices and data:", r.invalid.join(", "));
  return jsonError(c, 503, "server misconfigured");
}

/** Work that should not delay the response (falls back to awaiting it when there is no execution context). */
function background(c: Context<AppEnv>): (work: Promise<unknown>) => void {
  return (work) => {
    try {
      c.executionCtx.waitUntil(work);
    } catch {
      void work;
    }
  };
}

async function jsonBody(c: Context<AppEnv>): Promise<{ body: Record<string, unknown> } | Response> {
  try {
    const body = await readJson(c.req.raw);
    return { body: (body ?? {}) as Record<string, unknown> };
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "body too large");
    return jsonError(c, 400, "invalid JSON body");
  }
}

v1.get("/health", (c) => c.json({ ok: true }));

// ---- Registration (unauthenticated, consumes a pairing code) ----

// `{code, name, token}`: the client generates the device token and keeps it
// before sending, so that it can retry after a lost response: a retry with
// the same code and token returns the same device (200).
v1.post("/devices/register", async (c) => {
  const ip = clientKey(c.req.raw);
  if (!(await edgeAllowed(c.env.RL_REGISTER, `register:${ip}`))) return jsonError(c, 429, "too many requests");
  const parsed = await jsonBody(c);
  if (parsed instanceof Response) return parsed;
  const { code: rawCode, name: rawName, token } = parsed.body;
  if (typeof rawCode !== "string" || typeof rawName !== "string") {
    return jsonError(c, 400, "code and name are required");
  }
  if (typeof token !== "string" || !DEVICE_TOKEN_RE.test(token)) return jsonError(c, 400, "invalid token");
  const name = parseDeviceName(rawName);
  if (name === null) return jsonError(c, 400, "invalid name");
  // Counted after validation, before the code is looked at: wrong codes count.
  if (!(await hitRateLimit(c.env.DB, RULES.register, ip))) return jsonError(c, 429, "too many requests");
  const code = normalizePairingCode(rawCode);
  if (!code) return jsonError(c, 401, "invalid or expired pairing code");
  const limits = limitsOr503(c);
  if (limits instanceof Response) return limits;

  const r = await registerWithCode(c.env.DB, { code, name, token, maxDevices: limits.userMaxDevices });
  // A code of a disabled user, or issued by a revoked device, is neither
  // accepted nor consumed. The response is the same as for an unknown code.
  if (r === "invalid") return jsonError(c, 401, "invalid or expired pairing code");
  if (r === "too many devices") return jsonError(c, 409, "too many devices");
  return c.json({ deviceId: r.deviceId }, r.created ? 201 : 200);
});

// ---- Self-registration (unauthenticated, only when REGISTRATION = "open") ----

v1.get("/info", (c) => c.json({ registration: registrationMode(c.env) }));

// `{token, deviceName?}`: creates a user and registers the calling device,
// authenticated by the client-generated token, as its first device. The user
// has no handle (an admin may set one) and is marked as self-registered.
// Further devices are paired from this one (POST /v1/pairing). A retry with
// the same token returns the same device (200).
v1.post("/accounts", async (c) => {
  if (registrationMode(c.env) !== "open") return jsonError(c, 403, "registration closed");
  const ip = clientKey(c.req.raw);
  if (!(await edgeAllowed(c.env.RL_ACCOUNT, `account:${ip}`))) return jsonError(c, 429, "too many requests");
  const parsed = await jsonBody(c);
  if (parsed instanceof Response) return parsed;
  const { deviceName: rawName, token } = parsed.body;
  if (typeof token !== "string" || !DEVICE_TOKEN_RE.test(token)) return jsonError(c, 400, "invalid token");
  let name: string | null = DEFAULT_DEVICE_NAME;
  if (rawName !== undefined && rawName !== null && rawName !== "") {
    name = typeof rawName === "string" ? parseDeviceName(rawName) : null;
    if (name === null) return jsonError(c, 400, "invalid deviceName");
  }
  if (!(await hitRateLimit(c.env.DB, RULES.account, ip))) return jsonError(c, 429, "too many requests");
  const limits = limitsOr503(c);
  if (limits instanceof Response) return limits;

  const r = await createAccount(c.env.DB, { token, name, maxUsers: limits.maxUsers });
  if (r === "full") return jsonError(c, 403, "registration full");
  if (r === "token in use") return jsonError(c, 409, "token already in use");
  return c.json({ deviceId: r.deviceId }, r.created ? 201 : 200);
});

// ---- Everything below requires a device token ----
// A device acts for its user: it sees and manages only that user's devices and
// batches. Legacy devices (user_id NULL) see each other (`IS` matches NULL).

v1.use("/pairing", deviceAuth);
v1.use("/devices", deviceAuth);
v1.use("/devices/:id/revoke", deviceAuth);
v1.use("/batches", deviceAuth);
v1.use("/batches/*", deviceAuth);

// Shared check-ins and follows are between users, so legacy devices, which
// have no user, cannot take part.
const requireUser: MiddlewareHandler<AppEnv> = async (c, next) => {
  if (c.get("device").user_id === null) return jsonError(c, 403, "no user");
  await next();
};
for (const path of [
  "/checkins/*",
  "/users/lookup",
  "/follows",
  "/follows/*",
  "/follow-requests/*",
  "/follow-requests",
  "/followers",
  "/followers/*",
]) {
  v1.use(path, deviceAuth, requireUser);
}

v1.post("/pairing", async (c) => {
  const device = c.get("device");
  if (!(await edgeAllowed(c.env.RL_PAIRING, `pairing:${device.user_id ?? "legacy"}`))) {
    return jsonError(c, 429, "too many requests");
  }
  if (!(await hitRateLimit(c.env.DB, RULES.pairing, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  return c.json(await createPairingCode(c.env.DB, device.id, device.user_id, device.id), 201);
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
  if (body.byteLength < MIN_BLOB_BYTES) return jsonError(c, 400, "blob too small");
  if ((await sha256Hex(body)) !== sha256) return jsonError(c, 400, "X-Content-SHA256 does not match body");
  const limits = limitsOr503(c);
  if (limits instanceof Response) return limits;

  const device = c.get("device");
  const r = await putBatch(
    c.env.DB,
    c.env.BLOBS,
    {
      deviceId: device.id, // never from the URL
      userId: device.user_id,
      stream,
      seq,
      sha256,
      body,
      createdAt: floorHour(Date.now()),
    },
    limits,
    background(c),
  );
  switch (r) {
    case "conflict":
      return jsonError(c, 409, "batch already exists with a different hash");
    case "quota exceeded":
      return jsonError(c, 507, "quota exceeded");
    case "server full":
      return jsonError(c, 507, "server storage full");
    case "device gone":
      return jsonError(c, 401, "unauthorized");
  }
  return r.status === "created" ? c.json(r, 201) : c.json(r, 200);
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
  const meta = await committedBlob(c.env.DB, c.get("device").user_id, deviceId, stream, Number(seqStr));
  if (!meta) return jsonError(c, 404, "not found");
  const obj = await c.env.BLOBS.get(meta.key);
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

// ---- Shared check-ins (see checkins.ts) ----

v1.put("/checkins/shared/:localId", async (c) => {
  const localId = c.req.param("localId");
  if (!LOCAL_ID_RE.test(localId)) return jsonError(c, 400, "invalid id");
  const json = await jsonBody(c);
  if (json instanceof Response) return json;
  const parsed = parseCheckin(json.body);
  if (typeof parsed === "string") return jsonError(c, 400, parsed);
  const limits = limitsOr503(c);
  if (limits instanceof Response) return limits;
  const device = c.get("device");
  const r = await upsertCheckin(c.env.DB, device.user_id!, device.id, localId, parsed, limits.userMaxSharedCheckins);
  if (r === "stale") return jsonError(c, 409, "stale revision");
  if (r === "removed") return jsonError(c, 409, "removed by an admin");
  if (r === "limit") return jsonError(c, 507, "too many shared check-ins");
  return c.json(r);
});

// Unshare by server id, from any device of the entry's user.
v1.delete("/checkins/shared/by-id/:id", async (c) => {
  const rev = parseRev(c.req.query("rev"));
  if (rev === undefined) return jsonError(c, 400, "invalid rev");
  const id = c.req.param("id");
  if (id.length > 64 || !(await unshareCheckinById(c.env.DB, c.get("device").user_id!, id, rev))) {
    return jsonError(c, 404, "not found");
  }
  return c.json({ deleted: true });
});

v1.delete("/checkins/shared/:localId", async (c) => {
  const rev = parseRev(c.req.query("rev"));
  if (rev === undefined) return jsonError(c, 400, "invalid rev");
  const localId = c.req.param("localId");
  if (!LOCAL_ID_RE.test(localId) || !(await unshareCheckin(c.env.DB, c.get("device").id, localId, rev))) {
    return jsonError(c, 404, "not found");
  }
  return c.json({ deleted: true });
});

v1.get("/checkins/feed", async (c) => {
  const afterStr = c.req.query("after") ?? "0";
  const limitStr = c.req.query("limit") ?? String(LIST_DEFAULT_LIMIT);
  if (!/^[0-9]{1,15}$/.test(afterStr)) return jsonError(c, 400, "invalid cursor");
  if (!/^[0-9]{1,5}$/.test(limitStr)) return jsonError(c, 400, "invalid limit");
  const limit = Math.min(Math.max(Number(limitStr), 1), FEED_MAX_LIMIT);
  return c.json(await listFeed(c.env.DB, c.get("device").user_id!, Number(afterStr), limit));
});

// ---- Follows (see follows.ts) ----

// Exact match only, and rate limited, which limits bulk probing of handles.
v1.get("/users/lookup", async (c) => {
  if (!(await edgeAllowed(c.env.RL_LOOKUP, `lookup:${c.get("device").user_id}`))) {
    return jsonError(c, 429, "too many requests");
  }
  if (!(await hitRateLimit(c.env.DB, RULES.lookup, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  const handle = normalizeHandle(c.req.query("handle") ?? "");
  const user = handle ? await lookupHandle(c.env.DB, handle) : null;
  return user ? c.json(user) : jsonError(c, 404, "no such user");
});

v1.post("/follows", async (c) => {
  const json = await jsonBody(c);
  if (json instanceof Response) return json;
  const { userId } = json.body;
  if (typeof userId !== "string" || userId.length === 0 || userId.length > 64) {
    return jsonError(c, 400, "userId is required");
  }
  const result = await requestFollow(c.env.DB, c.get("device").user_id!, userId);
  if (result === "self") return jsonError(c, 400, "cannot follow yourself");
  if (result === "no user") return jsonError(c, 404, "no such user");
  return c.json(result);
});

v1.get("/follows", async (c) => c.json(await listFollowing(c.env.DB, c.get("device").user_id!)));

// Unfollow, or cancel a request.
v1.delete("/follows/:userId", async (c) => {
  if (!(await deleteFollow(c.env.DB, c.get("device").user_id!, c.req.param("userId")))) {
    return jsonError(c, 404, "not following");
  }
  return c.json({ deleted: true });
});

v1.get("/follow-requests", async (c) => c.json(await listFollowers(c.env.DB, c.get("device").user_id!, "pending")));

v1.post("/follow-requests/:userId/accept", async (c) => {
  if (!(await acceptFollow(c.env.DB, c.req.param("userId"), c.get("device").user_id!))) {
    return jsonError(c, 404, "no such request");
  }
  return c.json({ status: "accepted" });
});

// Deletes the request; the user may send it again.
v1.post("/follow-requests/:userId/decline", async (c) => {
  if (!(await deleteFollow(c.env.DB, c.req.param("userId"), c.get("device").user_id!, "pending"))) {
    return jsonError(c, 404, "no such request");
  }
  return c.json({ deleted: true });
});

v1.get("/followers", async (c) => c.json(await listFollowers(c.env.DB, c.get("device").user_id!, "accepted")));

// Removing a follower: they stop seeing the caller's shared check-ins.
v1.delete("/followers/:userId", async (c) => {
  if (!(await deleteFollow(c.env.DB, c.req.param("userId"), c.get("device").user_id!, "accepted"))) {
    return jsonError(c, 404, "not a follower");
  }
  return c.json({ deleted: true });
});

