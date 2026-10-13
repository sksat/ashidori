import type { MiddlewareHandler } from "hono";
import { verifyAccessJwt } from "./access";
import type { AppEnv, DeviceRow, UserStatus } from "./env";
import { parseAdminEmails } from "./users";
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
  const row = await c.env.DB.prepare(
    `SELECT d.id, d.name, d.token_hash, d.created_at, d.last_seen_at, d.revoked_at, d.user_id,
       u.status AS user_status
     FROM devices d LEFT JOIN users u ON u.id = d.user_id
     WHERE d.token_hash = ?1`,
  )
    .bind(hash)
    .first<DeviceRow & { user_status: UserStatus | null }>();
  // The lookup is by hash; the explicit constant-time comparison below is defense in depth.
  if (!row || !timingSafeEqualStr(row.token_hash, hash) || row.revoked_at !== null) {
    return jsonError(c, 401, "unauthorized");
  }
  // Devices of a disabled user stop working (legacy devices have no user).
  if (row.user_id !== null && row.user_status !== "active") return jsonError(c, 401, "unauthorized");
  const { user_status: _, ...device } = row;

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

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/** Admin identity of the local-development ADMIN_DEV_TOKEN. */
export const DEV_ADMIN_EMAIL = "dev-admin@localhost";

/**
 * /admin/* authentication. The admin page is for operators only: a valid
 * Cloudflare Access JWT whose `email` claim is listed in ADMIN_EMAILS (fails
 * closed when ADMIN_EMAILS is empty). Access itself may let more people
 * through (e.g. any SSO login); they get 403 here. For local development only
 * (ALLOW_DEV_ADMIN == "true"), the ADMIN_DEV_TOKEN secret as a bearer token.
 */
export const adminAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  // Cross-site state-changing requests are never legitimate (CSRF). Safe
  // methods are exempt: the first GET /admin/ after the Access login arrives
  // through a cross-site redirect chain, and GETs change nothing (cross-origin
  // pages still cannot read the responses, as /admin/* sends no CORS headers).
  if (!SAFE_METHODS.has(c.req.method)) {
    const origin = c.req.header("Origin");
    if (origin && origin !== new URL(c.req.url).origin) return jsonError(c, 403, "forbidden");
    if (c.req.header("Sec-Fetch-Site") === "cross-site") return jsonError(c, 403, "forbidden");
  }

  const assertion = c.req.header("Cf-Access-Jwt-Assertion");
  if (assertion) {
    const r = await verifyAccessJwt(assertion, c.env.CF_ACCESS_TEAM_DOMAIN, c.env.CF_ACCESS_AUD);
    if (!r.ok) {
      // Only requests that passed Access carry this header, so the reason is
      // shown to an authenticated operator, which makes misconfiguration
      // (wrong AUD or team domain) diagnosable.
      console.warn("admin: Access JWT rejected", r.reason);
      return jsonError(c, 403, `forbidden: ${r.reason}`);
    }
    const email = r.email?.trim().toLowerCase();
    if (!email || !parseAdminEmails(c.env.ADMIN_EMAILS).has(email)) return jsonError(c, 403, "not an admin");
    c.set("adminEmail", email);
    return next();
  }

  const devToken = c.env.ADMIN_DEV_TOKEN;
  if (c.env.ALLOW_DEV_ADMIN === "true" && devToken && devToken.length >= 16) {
    const given = bearer(c.req.raw);
    if (given && timingSafeEqualStr(await sha256Hex(given), await sha256Hex(devToken))) {
      c.set("adminEmail", DEV_ADMIN_EMAIL);
      return next();
    }
  }
  return jsonError(c, 401, "unauthorized");
};
