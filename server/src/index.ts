import { Hono } from "hono";
import { admin } from "./admin";
import type { AppEnv, Env } from "./env";
import { sweepUnusedAccounts } from "./accounts";
import { sweepOrphanBlobs } from "./batches";
import { deleteOldPairingCodes } from "./devices";
import { deleteExpiredRateLimits } from "./ratelimit";
import { jsonError } from "./util";
import { v1 } from "./v1";

// strict: false so that both /admin and /admin/ serve the admin page.
const app = new Hono<AppEnv>({ strict: false });

// ---- Security headers (applied to every response) ----
app.use("*", async (c, next) => {
  await next();
  const h = c.res.headers;
  h.set("X-Content-Type-Options", "nosniff");
  h.set("Referrer-Policy", "no-referrer");
  h.set("X-Frame-Options", "DENY");
  h.set("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
  h.set("Cross-Origin-Opener-Policy", "same-origin");
  h.set("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
  if (!h.has("Content-Security-Policy")) {
    h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
  }
  if (!h.has("Cache-Control")) h.set("Cache-Control", "no-store");
});

// ---- CORS for /v1/* (only for explicitly allow-listed origins; none by default) ----
function allowedOrigin(allowList: string, origin: string | undefined): string | null {
  if (!origin) return null;
  const list = allowList
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  return list.includes(origin) ? origin : null;
}

app.use("/v1/*", async (c, next) => {
  const origin = allowedOrigin(c.env.CORS_ALLOWED_ORIGINS ?? "", c.req.header("Origin"));
  if (c.req.method === "OPTIONS") {
    if (!origin) return c.body(null, 204, { Vary: "Origin" });
    return c.body(null, 204, {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, PUT, POST, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Content-SHA256",
      "Access-Control-Max-Age": "600",
      Vary: "Origin",
    });
  }
  await next();
  c.res.headers.append("Vary", "Origin");
  if (origin) {
    c.res.headers.set("Access-Control-Allow-Origin", origin);
    c.res.headers.set("Access-Control-Expose-Headers", "X-Content-SHA256, ETag");
  }
});

app.route("/v1", v1);
app.route("/admin", admin);

app.notFound((c) => jsonError(c, 404, "not found"));
app.onError((err, c) => {
  // Never log request data (IPs, bodies): only the error itself.
  console.error("unhandled error", err);
  return jsonError(c, 500, "internal error");
});

/**
 * Scheduled maintenance (cron trigger in wrangler.jsonc, every 10 minutes).
 * Every task is bounded per run, and one failing does not stop the others.
 */
export async function maintenance(env: Env, now: number): Promise<void> {
  const tasks: Array<[string, () => Promise<unknown>]> = [
    // IP-keyed rate-limit counters whose window has ended.
    ["rate limits", () => deleteExpiredRateLimits(env.DB, now)],
    ["pairing codes", () => deleteOldPairingCodes(env.DB, now)],
    ["unused accounts", () => sweepUnusedAccounts(env.DB, now)],
    ["orphan blobs", () => sweepOrphanBlobs(env.DB, env.BLOBS, now)],
  ];
  for (const [name, task] of tasks) {
    try {
      await task();
    } catch (e) {
      console.error(`maintenance (${name}) failed`, e);
    }
  }
}

export default {
  fetch: app.fetch,
  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(maintenance(env, Date.now()));
  },
} satisfies ExportedHandler<Env>;
