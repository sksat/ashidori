// Rate limiting in two layers.
//
// 1. Workers Rate Limiting bindings (RL_* in wrangler.jsonc): counted per
//    Cloudflare location, eventually consistent, 60-second windows, and
//    without any D1 access. They turn away floods cheaply, before D1. They are
//    deliberately looser than the D1 limits and are optional: without a
//    binding (e.g. a local setup without it) the check passes.
// 2. Fixed-window counters in D1 (`rate_limits`): precise (one D1 primary)
//    and the limits the protocol documents. Once a counter has reached its
//    limit, further hits change no row, so rejected requests cause no D1
//    writes. Expired counters are deleted by the scheduled cleanup, in bounded
//    chunks, using the expires_at index.

export interface RateLimitRule {
  bucket: string;
  limit: number;
  windowMs: number;
}

export const RULES = {
  register: { bucket: "register", limit: 10, windowMs: 10 * 60 * 1000 },
  pairing: { bucket: "pairing", limit: 20, windowMs: 10 * 60 * 1000 },
  // Handle lookups (exact match), against bulk probing of handles.
  lookup: { bucket: "lookup", limit: 20, windowMs: 10 * 60 * 1000 },
  // Self-registration (REGISTRATION = "open"): each success creates a user,
  // so it is much stricter than pairing-code registration.
  account: { bucket: "account", limit: 5, windowMs: 60 * 60 * 1000 },
} satisfies Record<string, RateLimitRule>;

// ---- Client keys ----

/** The 8 16-bit groups of an IPv6 address (zone id ignored), or null if it is not one. */
export function parseIPv6(raw: string): number[] | null {
  let s = raw.trim().toLowerCase();
  const zone = s.indexOf("%");
  if (zone >= 0) s = s.slice(0, zone);
  if (s.length === 0 || s.length > 45) return null;

  // Embedded IPv4 in the last 32 bits (e.g. ::ffff:192.0.2.1, 64:ff9b::192.0.2.1).
  let tailV4: number[] = [];
  const lastColon = s.lastIndexOf(":");
  if (s.includes(".")) {
    const v4 = parseIPv4(s.slice(lastColon + 1));
    if (!v4 || lastColon < 0) return null;
    tailV4 = [(v4[0]! << 8) | v4[1]!, (v4[2]! << 8) | v4[3]!];
    s = s.slice(0, lastColon + 1);
    // "::1.2.3.4" leaves "::"; "a:b:c:d:e:f:1.2.3.4" leaves "a:...:f:"; drop the trailing ":" unless it ends "::".
    if (s.endsWith(":") && !s.endsWith("::")) s = s.slice(0, -1);
  }

  const groupRe = /^[0-9a-f]{1,4}$/;
  const parseGroups = (part: string): number[] | null => {
    if (part === "") return [];
    const out: number[] = [];
    for (const g of part.split(":")) {
      if (!groupRe.test(g)) return null;
      out.push(parseInt(g, 16));
    }
    return out;
  };

  const want = 8 - tailV4.length;
  const halves = s.split("::");
  if (halves.length > 2) return null;
  let groups: number[];
  if (halves.length === 2) {
    const head = parseGroups(halves[0]!);
    const tail = parseGroups(halves[1]!);
    if (!head || !tail || head.length + tail.length >= want) return null;
    groups = [...head, ...new Array<number>(want - head.length - tail.length).fill(0), ...tail];
  } else {
    const all = parseGroups(s);
    if (!all || all.length !== want) return null;
    groups = all;
  }
  return [...groups, ...tailV4];
}

function parseIPv4(s: string): number[] | null {
  const parts = s.split(".");
  if (parts.length !== 4) return null;
  const out: number[] = [];
  for (const p of parts) {
    if (!/^(0|[1-9][0-9]{0,2})$/.test(p)) return null;
    const n = Number(p);
    if (n > 255) return null;
    out.push(n);
  }
  return out;
}

/**
 * Client key for rate limiting: the IPv4 address, or the /64 prefix of an
 * IPv6 address (one subscriber usually has a whole /64). IPv4-mapped IPv6
 * addresses count as their IPv4 address.
 */
export function clientKey(req: Request): string {
  const ip = (req.headers.get("CF-Connecting-IP") ?? "").trim();
  if (ip === "") return "unknown";
  if (!ip.includes(":")) return parseIPv4(ip) ? ip : "invalid";
  const g = parseIPv6(ip);
  if (!g) return "invalid";
  if (g.slice(0, 5).every((x) => x === 0) && g[5] === 0xffff) {
    return [g[6]! >> 8, g[6]! & 0xff, g[7]! >> 8, g[7]! & 0xff].join(".");
  }
  return g.slice(0, 4).map((x) => x.toString(16)).join(":") + "::/64";
}

// ---- Edge layer ----

/** True unless the binding says the key is over its limit. Without a binding, or if it fails, true. */
export async function edgeAllowed(limiter: RateLimit | undefined, key: string): Promise<boolean> {
  if (!limiter) return true;
  try {
    return (await limiter.limit({ key })).success;
  } catch (e) {
    console.warn("rate limit binding failed", e);
    return true;
  }
}

// ---- D1 layer ----

/** Returns true if the request is allowed. A rejected request changes no row. */
export async function hitRateLimit(db: D1Database, rule: RateLimitRule, client: string): Promise<boolean> {
  const now = Date.now();
  const windowStart = now - (now % rule.windowMs);
  const row = await db
    .prepare(
      `INSERT INTO rate_limits (key, window_start, expires_at, count) VALUES (?1, ?2, ?3, 1)
       ON CONFLICT (key) DO UPDATE SET
         count = CASE WHEN window_start = excluded.window_start THEN count + 1 ELSE 1 END,
         window_start = excluded.window_start,
         expires_at = excluded.expires_at
       WHERE window_start <> excluded.window_start OR count < ?4
       RETURNING count`,
    )
    .bind(`${rule.bucket}:${client}`, windowStart, windowStart + rule.windowMs, rule.limit)
    .first<{ count: number }>();
  return row !== null;
}

const CLEANUP_CHUNK = 1000;
const CLEANUP_MAX_CHUNKS = 10;

/**
 * Deletes counters whose window has ended (scheduled, every 10 minutes), at
 * most CLEANUP_CHUNK * CLEANUP_MAX_CHUNKS per run. Returns how many.
 */
export async function deleteExpiredRateLimits(db: D1Database, now: number): Promise<number> {
  let total = 0;
  for (let i = 0; i < CLEANUP_MAX_CHUNKS; i++) {
    const res = await db
      .prepare("DELETE FROM rate_limits WHERE key IN (SELECT key FROM rate_limits WHERE expires_at <= ?1 LIMIT ?2)")
      .bind(now, CLEANUP_CHUNK)
      .run();
    total += res.meta.changes;
    if (res.meta.changes < CLEANUP_CHUNK) break;
  }
  return total;
}
