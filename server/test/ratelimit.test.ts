import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { clientKey, hitRateLimit, parseIPv6, RULES } from "../src/ratelimit";
import { authed, fakeLimiter, newDevice, newUser, postJson, register } from "./helpers";

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

describe("edge rate limiters", () => {
  it("key pairing by user and lookup by user, before D1", async () => {
    const alice = await newUser("alice", "alice");
    const counters = async () => (await env.DB.prepare("SELECT key, count FROM rate_limits ORDER BY key").all()).results;
    const before = await counters();
    const pairing = fakeLimiter(() => false);
    const res = await authed(alice.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: pairing });
    expect(res.status).toBe(429);
    expect(pairing.keys).toEqual([`pairing:${alice.userId}`]);
    const lookup = fakeLimiter(() => false);
    const l = await authed(alice.token, "/v1/users/lookup?handle=alice", {}, { RL_LOOKUP: lookup });
    expect(l.status).toBe(429);
    expect(lookup.keys).toEqual([`lookup:${alice.userId}`]);
    expect(await counters()).toEqual(before);
  });

  it("pass when the binding is missing or fails", async () => {
    const a = await newDevice();
    const broken: RateLimit = {
      async limit() {
        throw new Error("down");
      },
    };
    expect((await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: broken })).status).toBe(201);
    expect((await authed(a.token, "/v1/pairing", { method: "POST" }, { RL_PAIRING: undefined })).status).toBe(201);
    expect((await postJson(a.token, "/v1/pairing")).status).toBe(201);
  });
});
