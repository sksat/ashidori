import { env } from "cloudflare:workers";
import { afterEach, describe, expect, it, vi } from "vitest";
import { clientKey, hitRateLimit, MAX_RULE_WINDOW_MS, parseIPv6, resetEdgeWarnings, RULES } from "../src/ratelimit";
import {
  ADMIN_DEV_TOKEN,
  authed,
  call,
  fakeLimiter,
  newDevice,
  newUser,
  OPEN,
  postJson,
  register,
  selfRegister,
} from "./helpers";

const key = (ip: string | null) =>
  clientKey(new Request("https://x/", { headers: ip === null ? {} : { "CF-Connecting-IP": ip } }));

describe("IPv6 parsing", () => {
  it("expands compressed forms to 8 groups", () => {
    expect(parseIPv6("::")).toEqual([0, 0, 0, 0, 0, 0, 0, 0]);
    expect(parseIPv6("::1")).toEqual([0, 0, 0, 0, 0, 0, 0, 1]);
    expect(parseIPv6("1::")).toEqual([1, 0, 0, 0, 0, 0, 0, 0]);
    expect(parseIPv6("2001:db8::1")).toEqual([0x2001, 0xdb8, 0, 0, 0, 0, 0, 1]);
    expect(parseIPv6("2600::1:2:3:4:5")).toEqual([0x2600, 0, 0, 1, 2, 3, 4, 5]);
    expect(parseIPv6("2001:DB8:0:0:8:800:200C:417A")).toEqual([0x2001, 0xdb8, 0, 0, 8, 0x800, 0x200c, 0x417a]);
    expect(parseIPv6("2001:0db8:0000:0001:0000:0000:0000:0001")).toEqual([0x2001, 0xdb8, 0, 1, 0, 0, 0, 1]);
    expect(parseIPv6("fe80::1%eth0")).toEqual([0xfe80, 0, 0, 0, 0, 0, 0, 1]);
  });

  it("reads embedded IPv4", () => {
    expect(parseIPv6("::ffff:192.0.2.1")).toEqual([0, 0, 0, 0, 0, 0xffff, 0xc000, 0x0201]);
    expect(parseIPv6("64:ff9b::198.51.100.7")).toEqual([0x64, 0xff9b, 0, 0, 0, 0, 0xc633, 0x6407]);
    expect(parseIPv6("1:2:3:4:5:6:1.2.3.4")).toEqual([1, 2, 3, 4, 5, 6, 0x0102, 0x0304]);
    expect(parseIPv6("::1.2.3.4")).toEqual([0, 0, 0, 0, 0, 0, 0x0102, 0x0304]);
  });

  it("rejects malformed addresses", () => {
    for (const bad of [
      "",
      ":",
      ":::",
      "1::2::3",
      "1:2:3:4:5:6:7:8:9",
      "1:2:3:4:5:6:7",
      "12345::",
      "g::1",
      ":1::",
      "1:2:3:4:5:6:7::8",
      "::1.2.3",
      "::256.1.1.1",
      "::1.2.3.4:5",
      "1:2:3:4:5:6:7:1.2.3.4",
    ]) {
      expect(parseIPv6(bad), bad).toBeNull();
    }
  });
});

describe("client keys", () => {
  it("bucket IPv6 by the full /64 prefix", () => {
    // The same /64, written differently.
    expect(key("2600::1:2:3:4:5")).toBe("2600:0:0:1::/64");
    expect(key("2600:0:0:1::1")).toBe("2600:0:0:1::/64");
    expect(key("2600:0000:0000:0001:ffff:ffff:ffff:ffff")).toBe("2600:0:0:1::/64");
    expect(key("2001:DB8:1:2::9")).toBe("2001:db8:1:2::/64");
    // Different /64s stay apart.
    expect(key("2001:db8:1:3::1")).toBe("2001:db8:1:3::/64");
    expect(key("2001:db8::1")).toBe("2001:db8:0:0::/64");
    expect(key("2001:db8:0:0:1::")).toBe("2001:db8:0:0::/64");
  });

  it("count IPv4-mapped addresses as IPv4, and keep IPv4 as is", () => {
    expect(key("::ffff:192.0.2.1")).toBe("192.0.2.1");
    expect(key("::FFFF:c000:0201")).toBe("192.0.2.1");
    expect(key("192.0.2.1")).toBe("192.0.2.1");
    // NAT64 is a real IPv6 prefix.
    expect(key("64:ff9b::192.0.2.1")).toBe("64:ff9b:0:0::/64");
  });

  it("put missing and malformed addresses into fixed buckets", () => {
    expect(key(null)).toBe("unknown");
    expect(key("not-an-ip")).toBe("invalid");
    expect(key("1::2::3")).toBe("invalid");
    expect(key("300.1.1.1")).toBe("invalid");
  });

  it("limit compressed and expanded forms of one /64 together", async () => {
    for (let i = 0; i < 5; i++) await register("ZZZZZZZZ", "x", `2600::1:${i}:3:4:5`);
    for (let i = 0; i < 5; i++) await register("ZZZZZZZZ", "x", `2600:0:0:1::${i + 1}`);
    expect((await register("ZZZZZZZZ", "x", "2600:0:0:1:ffff::1")).status).toBe(429);
    expect((await register("ZZZZZZZZ", "x", "2600:0:0:2::1")).status).toBe(401);
  });
});

describe("D1 counters", () => {
  it("allow exactly `limit` hits per window and then change no row", async () => {
    const rule = { bucket: "t", limit: 3, windowMs: 60_000 };
    const results = await Promise.all(Array.from({ length: 6 }, () => hitRateLimit(env.DB, rule, "c")));
    expect(results.filter(Boolean)).toHaveLength(3);
    const row = await env.DB.prepare("SELECT count, expires_at - window_start AS len FROM rate_limits").first();
    expect(row).toEqual({ count: 3, len: 60_000 });
  });

  it("restart in a new window", async () => {
    const now = Date.now();
    await env.DB.prepare("INSERT INTO rate_limits (key, window_start, expires_at, count) VALUES ('register:c', ?1, ?2, 99)")
      .bind(now - 2 * RULES.register.windowMs, now - RULES.register.windowMs)
      .run();
    expect(await hitRateLimit(env.DB, RULES.register, "c")).toBe(true);
    expect((await env.DB.prepare("SELECT count FROM rate_limits").first<{ count: number }>())!.count).toBe(1);
  });
});

/** A D1 binding that counts the statements run through it. */
function countingDb(): { db: D1Database; count: () => number } {
  let n = 0;
  const wrapStmt = (s: D1PreparedStatement): D1PreparedStatement =>
    new Proxy(s, {
      get(target, prop) {
        if (prop === "bind") return (...args: unknown[]) => wrapStmt(target.bind(...args));
        if (prop === "first" || prop === "run" || prop === "all" || prop === "raw") {
          return (...args: unknown[]) => {
            n++;
            return (target[prop] as (...a: unknown[]) => unknown).apply(target, args);
          };
        }
        const v = Reflect.get(target, prop);
        return typeof v === "function" ? v.bind(target) : v;
      },
    });
  const db = new Proxy(env.DB, {
    get(target, prop) {
      if (prop === "prepare") return (sql: string) => wrapStmt(target.prepare(sql));
      if (prop === "batch") {
        return (stmts: D1PreparedStatement[]) => {
          n++;
          return target.batch(stmts);
        };
      }
      const v = Reflect.get(target, prop);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
  return { db, count: () => n };
}

describe("edge rate limiters", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    resetEdgeWarnings();
  });

  it("check pairing and lookups by IP before the device authentication, without D1", async () => {
    const counted = countingDb();
    // A well-formed token nobody has: authentication would read D1 and give 401.
    const unknown = "x".repeat(43);
    for (const [path, binding, key] of [
      ["/v1/pairing", "RL_PAIRING", "pairing:ip:203.0.113.9"],
      ["/v1/users/lookup?handle=alice", "RL_LOOKUP", "lookup:ip:203.0.113.9"],
    ] as const) {
      const limiter = fakeLimiter(() => false);
      const res = await authed(
        unknown,
        path,
        { method: path === "/v1/pairing" ? "POST" : "GET", headers: { "CF-Connecting-IP": "203.0.113.9" } },
        { [binding]: limiter, DB: counted.db },
      );
      expect(res.status, path).toBe(429);
      expect(limiter.keys).toEqual([key]);
    }
    expect(counted.count()).toBe(0);
  });

  it("then check pairing and lookups by user after the authentication", async () => {
    const alice = await newUser("alice", "alice");
    const counters = async () => (await env.DB.prepare("SELECT key, count FROM rate_limits ORDER BY key").all()).results;
    const before = await counters();
    const ip = { headers: { "CF-Connecting-IP": "203.0.113.9" } };
    const pairing = fakeLimiter((k) => k.includes(":ip:"));
    const res = await authed(alice.token, "/v1/pairing", { method: "POST", ...ip }, { RL_PAIRING: pairing });
    expect(res.status).toBe(429);
    expect(pairing.keys).toEqual(["pairing:ip:203.0.113.9", `pairing:user:${alice.userId}`]);
    const lookup = fakeLimiter((k) => k.includes(":ip:"));
    const l = await authed(alice.token, "/v1/users/lookup?handle=alice", ip, { RL_LOOKUP: lookup });
    expect(l.status).toBe(429);
    expect(lookup.keys).toEqual(["lookup:ip:203.0.113.9", `lookup:user:${alice.userId}`]);
    // Refused before the D1 counters.
    expect(await counters()).toEqual(before);
  });

  it("pass when the binding fails, leaving the decision to the D1 counters", async () => {
    const a = await newDevice();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const broken: RateLimit = {
      async limit() {
        throw new Error("down");
      },
    };
    expect((await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: broken })).status).toBe(201);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("RL_PAIRING failed"), expect.any(Error));
    // The D1 counter still applies (20 per 10 minutes per IP; one used above).
    for (let i = 0; i < 19; i++) await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: broken });
    expect((await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: broken })).status).toBe(429);
  });

  it("pass when the pairing or lookup binding is missing, warning once per isolate", async () => {
    const a = await newUser("a", "aaa");
    resetEdgeWarnings();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    for (let i = 0; i < 3; i++) {
      expect((await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: undefined })).status).toBe(201);
      expect((await authed(a.token, "/v1/users/lookup?handle=aaa", {}, { RL_LOOKUP: undefined })).status).toBe(200);
    }
    const msgs = warn.mock.calls.map((c) => String(c[0]));
    expect(msgs.filter((m) => m.includes("RL_PAIRING is missing"))).toHaveLength(1);
    expect(msgs.filter((m) => m.includes("RL_LOOKUP is missing"))).toHaveLength(1);
    expect((await postJson(a.token, "/v1/pairing")).status).toBe(201);
  });

  it("refuse open self-registration with 503 when its binding is missing, and show it on the admin page", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const res = await selfRegister({ deviceName: "x" }, "192.0.2.1", { ...OPEN, RL_ACCOUNT: undefined });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "server misconfigured" });
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first()).toEqual({ n: 0 });
    // With registration closed, the binding does not matter: 403 as usual.
    expect((await selfRegister({ deviceName: "x" }, "192.0.2.1", { RL_ACCOUNT: undefined })).status).toBe(403);
    // A binding that fails at runtime passes (the D1 counter applies).
    const broken: RateLimit = {
      async limit() {
        throw new Error("down");
      },
    };
    vi.spyOn(console, "warn").mockImplementation(() => {});
    expect((await selfRegister({ deviceName: "x" }, "192.0.2.1", { ...OPEN, RL_ACCOUNT: broken })).status).toBe(201);

    const page = async (overrides: Partial<Cloudflare.Env>) => {
      const headers = new Headers({ Authorization: `Bearer ${ADMIN_DEV_TOKEN}` });
      return (await call("/admin/", { headers }, overrides)).text();
    };
    expect(await page({ ...OPEN, RL_ACCOUNT: undefined })).toContain("RL_ACCOUNT");
    expect(await page({ ...OPEN })).not.toContain("RL_ACCOUNT");
    expect(await page({ RL_ACCOUNT: undefined })).not.toContain("RL_ACCOUNT");
  });

  it("no rule has a window longer than the lifetime migration 0005 gives old counters", () => {
    for (const r of Object.values(RULES)) expect(r.windowMs).toBeLessThanOrEqual(MAX_RULE_WINDOW_MS);
    expect(MAX_RULE_WINDOW_MS).toBe(3_600_000);
  });
});
