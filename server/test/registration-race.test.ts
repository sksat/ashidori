// Concurrent registrations, interleaved deterministically: both requests pass
// their first lookup ("is this token registered already?") before either
// commits. Each request's first D1 batch (the commit) is held at a gate until
// both have reached it; then the first is released and completes, then the
// second.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { issueAdminCode, newDevice, newToken, OPEN, register, selfRegister } from "./helpers";

interface Gate {
  db: D1Database;
  /** Resolves once both requests wait at their commit. */
  bothWaiting: Promise<void>;
  release: (i: 0 | 1) => void;
}

function gatedDb(): Gate {
  const releases: Array<() => void> = [];
  let onBoth!: () => void;
  const bothWaiting = new Promise<void>((r) => (onBoth = r));
  const db = new Proxy(env.DB, {
    get(target, prop) {
      if (prop === "batch") {
        return async (stmts: D1PreparedStatement[]) => {
          if (releases.length < 2) {
            const gate = new Promise<void>((r) => releases.push(r));
            if (releases.length === 2) onBoth();
            await gate;
          }
          return target.batch(stmts);
        };
      }
      const v = Reflect.get(target, prop);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
  return { db, bothWaiting, release: (i) => releases[i]!() };
}

/** Runs `a` and `b` with the gate: both wait at their commit, then `a` commits and completes, then `b`. */
async function race(
  a: (db: D1Database) => Promise<Response>,
  b: (db: D1Database) => Promise<Response>,
): Promise<[Response, Response]> {
  const g = gatedDb();
  const pa = a(g.db);
  const pb = b(g.db);
  await g.bothWaiting;
  g.release(0);
  const ra = await pa;
  g.release(1);
  return [ra, await pb];
}

const body = async (r: Response) => ({ status: r.status, body: (await r.json()) as Record<string, unknown> });
const count = async (sql: string) => (await env.DB.prepare(sql).first<{ n: number }>())!.n;

describe("concurrent registration with a pairing code", () => {
  it("the same code and token: one creates the device, the other gets it (200)", async () => {
    const code = await issueAdminCode();
    const token = newToken();
    const [a, b] = await race(
      (db) => register(code, "pixel", "192.0.2.1", token, { DB: db }),
      (db) => register(code, "pixel", "192.0.2.2", token, { DB: db }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
  });

  it("the same code and token at the user's device limit: still 200", async () => {
    await newDevice("existing"); // the default user has 1 device; the limit is 2
    const code = await issueAdminCode();
    const token = newToken();
    const limit = { USER_MAX_DEVICES: "2" };
    const [a, b] = await race(
      (db) => register(code, "pixel", "192.0.2.1", token, { DB: db, ...limit }),
      (db) => register(code, "pixel", "192.0.2.2", token, { DB: db, ...limit }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
  });

  it("the same token with another code: the second is refused", async () => {
    const [codeA, codeB] = [await issueAdminCode(), await issueAdminCode()];
    const token = newToken();
    const [a, b] = await race(
      (db) => register(codeA, "pixel", "192.0.2.1", token, { DB: db }),
      (db) => register(codeB, "pixel", "192.0.2.2", token, { DB: db }),
    );
    expect(a.status).toBe(201);
    expect(b.status).toBe(401);
    // The other code was not consumed.
    expect(await count("SELECT COUNT(*) AS n FROM pairing_codes WHERE used_at IS NULL")).toBe(1);
  });

  it("the same code with another token: the second is refused", async () => {
    const code = await issueAdminCode();
    const [a, b] = await race(
      (db) => register(code, "pixel", "192.0.2.1", newToken(), { DB: db }),
      (db) => register(code, "pixel", "192.0.2.2", newToken(), { DB: db }),
    );
    expect(a.status).toBe(201);
    expect(b.status).toBe(401);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
  });
});

describe("concurrent self-registration", () => {
  const token = newToken();

  it("the same token at MAX_USERS: one creates the account, the other gets it (200)", async () => {
    const env1 = { ...OPEN, MAX_USERS: "1" };
    const [a, b] = await race(
      (db) => selfRegister({ deviceName: "pixel", token }, "192.0.2.1", { ...env1, DB: db }),
      (db) => selfRegister({ deviceName: "pixel", token }, "192.0.2.2", { ...env1, DB: db }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
  });

  it("the same token below MAX_USERS: 201 and 200, one user", async () => {
    const [a, b] = await race(
      (db) => selfRegister({ deviceName: "pixel", token }, "192.0.2.1", { ...OPEN, DB: db }),
      (db) => selfRegister({ deviceName: "pixel", token }, "192.0.2.2", { ...OPEN, DB: db }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(1);
  });

  it("different tokens at MAX_USERS: the second is full", async () => {
    const env1 = { ...OPEN, MAX_USERS: "1" };
    const [a, b] = await race(
      (db) => selfRegister({ deviceName: "pixel" }, "192.0.2.1", { ...env1, DB: db }),
      (db) => selfRegister({ deviceName: "pixel" }, "192.0.2.2", { ...env1, DB: db }),
    );
    expect(a.status).toBe(201);
    expect(await body(b)).toEqual({ status: 403, body: { error: "registration full" } });
  });
});
