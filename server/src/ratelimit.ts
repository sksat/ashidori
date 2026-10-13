// Per-IP fixed-window rate limiting backed by D1.
//
// Chosen over the Workers Rate Limiting binding because it is strongly
// consistent (one D1 primary), works identically in `wrangler dev` and tests,
// and the request volume of a personal server is tiny. Each check is one D1
// batch: delete expired counters, then UPSERT ... RETURNING.

export interface RateLimitRule {
  bucket: string;
  limit: number;
  windowMs: number;
}

export const RULES = {
  register: { bucket: "register", limit: 10, windowMs: 10 * 60 * 1000 },
  pairing: { bucket: "pairing", limit: 20, windowMs: 10 * 60 * 1000 },
  // Handle lookups (exact match), against enumerating users.
  lookup: { bucket: "lookup", limit: 20, windowMs: 10 * 60 * 1000 },
  // Self-registration (REGISTRATION = "open"): each success creates a user,
  // so it is much stricter than pairing-code registration.
  account: { bucket: "account", limit: 5, windowMs: 60 * 60 * 1000 },
} satisfies Record<string, RateLimitRule>;

/** Client IP; IPv6 addresses are bucketed by their /64 prefix. */
export function clientKey(req: Request): string {
  const ip = req.headers.get("CF-Connecting-IP") ?? "unknown";
  if (ip.includes(":")) {
    // Expand "::" enough to take the first four hextets.
    const [head = ""] = ip.split("::");
    const groups = head.split(":").filter((g) => g.length > 0);
    while (groups.length < 4) groups.push("0");
    return groups.slice(0, 4).join(":").toLowerCase() + "::/64";
  }
  return ip;
}

const MAX_WINDOW_MS = Math.max(...Object.values(RULES).map((r) => r.windowMs));
const MIN_WINDOW_MS = Math.min(...Object.values(RULES).map((r) => r.windowMs));

// Window length per bucket (the part of the key before ":"), so that each
// counter goes as soon as its own window has ended. The values come from
// RULES, not from requests.
const WINDOW_OF_KEY = `CASE substr(key, 1, instr(key, ':') - 1) ${Object.values(RULES)
  .map((r) => `WHEN '${r.bucket}' THEN ${r.windowMs}`)
  .join(" ")} ELSE ${MAX_WINDOW_MS} END`;

/**
 * Counters are keyed by client IP, so they are deleted as soon as their window
 * has ended: on every check and by the scheduled cleanup (index.ts), which
 * covers the case where no further request comes.
 */
export function deleteExpiredRateLimits(db: D1Database, now: number): D1PreparedStatement {
  // The first condition lets the window_start index narrow the scan.
  return db
    .prepare(`DELETE FROM rate_limits WHERE window_start <= ?2 AND window_start + ${WINDOW_OF_KEY} <= ?1`)
    .bind(now, now - MIN_WINDOW_MS);
}

/** Returns true if the request is allowed. */
export async function hitRateLimit(db: D1Database, rule: RateLimitRule, client: string): Promise<boolean> {
  const now = Date.now();
  const windowStart = now - (now % rule.windowMs);
  const key = `${rule.bucket}:${client}`;
  const [, hit] = await db.batch([
    deleteExpiredRateLimits(db, now),
    db
      .prepare(
        `INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 1)
         ON CONFLICT (key) DO UPDATE SET
           count = CASE WHEN window_start = excluded.window_start THEN count + 1 ELSE 1 END,
           window_start = excluded.window_start
         RETURNING count`,
      )
      .bind(key, windowStart),
  ]);
  const count = (hit!.results as Array<{ count: number }>)[0]?.count ?? 1;
  return count <= rule.limit;
}
