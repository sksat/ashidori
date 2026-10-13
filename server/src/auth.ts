import type { MiddlewareHandler } from "hono";
import { verifyAccessJwt } from "./access";
import type { AppEnv, DeviceRow } from "./env";
import { DEVICE_TOKEN_RE, jsonError, sha256Hex, timingSafeEqualStr } from "./util";

const LAST_SEEN_RESOLUTION_MS = 60 * 1000;

function bearer(req: Request): string | null {
  const h = req.headers.get("Authorization");
  if (!h) return null;
  const m = /^Bearer\s+(\S+)\s*$/i.exec(h);
  return m ? m[1]! : null;
}

/** Device bearer-token authentication for /v1/*. */
export const deviceAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const token = bearer(c.req.raw);
  if (!token || !DEVICE_TOKEN_RE.test(token)) return jsonError(c, 401, "unauthorized");

  const hash = await sha256Hex(token);
  const device = await c.env.DB.prepare(
    "SELECT id, name, token_hash, created_at, last_seen_at, revoked_at FROM devices WHERE token_hash = ?1",
  )
    .bind(hash)
    .first<DeviceRow>();
  // The lookup is by hash; the explicit constant-time comparison below is defense in depth.
  if (!device || !timingSafeEqualStr(device.token_hash, hash) || device.revoked_at !== null) {
    return jsonError(c, 401, "unauthorized");
  }

  const now = Date.now();
  if (device.last_seen_at === null || now - device.last_seen_at >= LAST_SEEN_RESOLUTION_MS) {
    const update = c.env.DB.prepare("UPDATE devices SET last_seen_at = ?2 WHERE id = ?1")
      .bind(device.id, now)
      .run();
    try {
      c.executionCtx.waitUntil(update);
    } catch {
      await update; // no execution context (e.g. direct app.request in tests)
    }
  }

  c.set("device", device);
  await next();
};

/**
 * /admin/* authentication: a valid Cloudflare Access JWT, or (local development
 * only, ALLOW_DEV_ADMIN == "true") the ADMIN_DEV_TOKEN secret as a bearer token.
 */
export const adminAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  // Cross-site state-changing requests are never legitimate for the admin API.
  // Page loads (GET) arrive cross-site right after the Access login redirect,
  // so only unsafe methods are checked; GET still needs the Access JWT below.
  const unsafe = !["GET", "HEAD", "OPTIONS"].includes(c.req.method);
  if (unsafe) {
    const origin = c.req.header("Origin");
    if (origin && origin !== new URL(c.req.url).origin) return jsonError(c, 403, "forbidden");
    if (c.req.header("Sec-Fetch-Site") === "cross-site") return jsonError(c, 403, "forbidden");
  }

  const assertion = c.req.header("Cf-Access-Jwt-Assertion");
  if (assertion) {
    const r = await verifyAccessJwt(assertion, c.env.CF_ACCESS_TEAM_DOMAIN, c.env.CF_ACCESS_AUD);
    if (r.ok) return next();
    // Only requests that passed Access carry this header, so the reason is
    // shown to an authenticated operator, which makes misconfiguration
    // (wrong AUD or team domain) diagnosable.
    console.warn("admin: Access JWT rejected", r.reason);
    return jsonError(c, 403, `forbidden: ${r.reason}`);
  }

  const devToken = c.env.ADMIN_DEV_TOKEN;
  if (c.env.ALLOW_DEV_ADMIN === "true" && devToken && devToken.length >= 16) {
    const given = bearer(c.req.raw);
    if (given && timingSafeEqualStr(await sha256Hex(given), await sha256Hex(devToken))) return next();
  }
  return jsonError(c, 401, "unauthorized");
};
