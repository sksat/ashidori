import type { MiddlewareHandler } from "hono";
import { verifyAccessJwt } from "./access";
import type { AppEnv, DeviceRow, UserStatus } from "./env";
import { parseAdminEmails } from "./users";
import { D1_NOW_MS, DEVICE_TOKEN_RE, floorHour, jsonError, sha256Hex, timingSafeEqualStr } from "./util";

export function bearer(req: Request): string | null {
  const h = req.headers.get("Authorization");
  if (!h) return null;
  const m = /^Bearer\s+(\S+)\s*$/i.exec(h);
  return m ? m[1]! : null;
}

/**
 * The stored token_hash of a viewer (a read-only browser, migration 0007):
 * 'v:' || SHA-256(token). A Worker of migration 0006 looks devices up by the
 * plain hash only, so it never authenticates a viewer token.
 */
export const VIEWER_HASH_PREFIX = "v:";

/**
 * What a viewer token may do (docs/design/browser-pairing-v1.md, 閲覧端末と
 * トークンの範囲). Everything else is refused with 403 "read-only token": a
 * route is open to viewers only if it is listed here (default deny).
 * Revoking is allowed for the viewer itself only (the browser's 登録の解除).
 */
const VIEWER_ROUTES: Array<{ method: string; path: RegExp; self?: true }> = [
  { method: "GET", path: /^\/v1\/devices$/ },
  { method: "GET", path: /^\/v1\/batches$/ },
  { method: "GET", path: /^\/v1\/batches\/[^/]+\/[^/]+\/[^/]+$/ },
  { method: "POST", path: /^\/v1\/devices\/([^/]+)\/revoke$/, self: true },
];

/** Whether a viewer device may make this request. */
export function viewerAllowed(method: string, path: string, viewerId: string): boolean {
  const m = method === "HEAD" ? "GET" : method;
  for (const r of VIEWER_ROUTES) {
    if (r.method !== m) continue;
    const match = r.path.exec(path);
    if (!match) continue;
    if (!r.self) return true;
    try {
      return decodeURIComponent(match[1]!) === viewerId;
    } catch {
      return false;
    }
  }
  return false;
}

type AuthRow = DeviceRow & { user_status: UserStatus | null; expired: number; approver_valid: number };

/**
 * SQL (a condition) for whether the viewer `d` was approved by a device that
 * is still valid: an unrevoked app device of the viewer's user. Revoking the
 * approving device revokes its viewers (src/devices.ts, and the trigger of
 * migration 0008 when the Worker of 0006 revokes); this check refuses a
 * viewer whose cascade was missed all the same.
 */
export const viewerApproverValid = (d: string) =>
  `EXISTS (SELECT 1 FROM devices a WHERE a.id = ${d}.granted_by AND a.kind = 'device'
     AND a.revoked_at IS NULL AND a.user_id = ${d}.user_id)`;

/**
 * Looks a device up by its token: an app device by SHA-256(token), a viewer
 * by 'v:' || SHA-256(token). Null if none, if both match (never issued: the
 * pairing refuses a token that a device has), if revoked, expired (judged by
 * D1's clock in the same statement) or of a disabled user; a viewer also if
 * its approving device is not an unrevoked app device of the same user.
 */
export async function authenticateToken(db: D1Database, token: string): Promise<DeviceRow | null> {
  if (!DEVICE_TOKEN_RE.test(token)) return null;
  const hash = await sha256Hex(token);
  const { results } = await db
    .prepare(
      `SELECT d.id, d.name, d.token_hash, d.created_at, d.last_seen_at, d.revoked_at, d.user_id,
         d.kind, d.expires_at, d.granted_by, u.status AS user_status,
         CASE WHEN d.expires_at IS NOT NULL AND d.expires_at <= ${D1_NOW_MS} THEN 1 ELSE 0 END AS expired,
         CASE WHEN ${viewerApproverValid("d")} THEN 1 ELSE 0 END AS approver_valid
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
       WHERE d.token_hash IN (?1, '${VIEWER_HASH_PREFIX}' || ?1)`,
    )
    .bind(hash)
    .all<AuthRow>();
  if (results.length !== 1) return null;
  const row = results[0]!;
  const expected = row.kind === "viewer" ? VIEWER_HASH_PREFIX + hash : hash;
  // The lookup is by hash; the explicit constant-time comparison is defense in depth.
  if (!timingSafeEqualStr(row.token_hash, expected) || row.revoked_at !== null || row.expired !== 0) return null;
  // A viewer always has an expiry, a user and a valid approving device; one without is never valid.
  if (row.kind === "viewer" && (row.expires_at === null || row.user_id === null || row.approver_valid !== 1)) {
    return null;
  }
  // Devices of a disabled user stop working (legacy devices have no user).
  if (row.user_id !== null && row.user_status !== "active") return null;
  const { user_status: _s, expired: _e, approver_valid: _a, ...device } = row;
  return device;
}

/**
 * Device bearer-token authentication for /v1/*. A viewer token gets 403
 * "read-only token" on every route that VIEWER_ROUTES does not list.
 */
export const deviceAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const token = bearer(c.req.raw);
  if (!token || !DEVICE_TOKEN_RE.test(token)) return jsonError(c, 401, "unauthorized");
  const device = await authenticateToken(c.env.DB, token);
  if (!device) return jsonError(c, 401, "unauthorized");
  if (device.kind === "viewer" && !viewerAllowed(c.req.method, c.req.path, device.id)) {
    return jsonError(c, 403, "read-only token");
  }

  // Hour granularity: enough to spot unused devices, without a timeline of
  // when the user is active. At most one write per device per hour.
  const hour = floorHour(Date.now());
  if (device.last_seen_at === null || device.last_seen_at < hour) {
    const update = c.env.DB.prepare("UPDATE devices SET last_seen_at = ?2 WHERE id = ?1")
      .bind(device.id, hour)
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
