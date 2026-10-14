// Concurrent registrations, interleaved deterministically: both requests pass
// their first lookup ("is this token registered already?") before either
// commits. Each request's first D1 batch (the commit) is held at a gate until
// both have reached it; then the first is released and completes, then the
// second.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { acceptInvite, adminCall, createInvite, issueAdminCode, newDevice, newToken, OPEN, register, selfRegister } from "./helpers";

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

/**
 * A D1 binding whose first query (the request's recovery lookup) completes
 * and then holds the request until `release`; `reached` resolves there.
 */
function holdAfterFirstQuery(): { db: D1Database; reached: Promise<void>; release: () => void } {
  let onReached!: () => void;
  let release!: () => void;
  const reached = new Promise<void>((r) => (onReached = r));
  const gate = new Promise<void>((r) => (release = r));
  let first = true;
  const held = (s: D1PreparedStatement): D1PreparedStatement =>
    new Proxy(s, {
      get(target, prop) {
        if (prop === "bind") return (...args: unknown[]) => held(target.bind(...args));
        if (prop === "first" || prop === "all" || prop === "run" || prop === "raw") {
          return async (...args: unknown[]) => {
            const r = await (Reflect.get(target, prop) as (...a: unknown[]) => Promise<unknown>).apply(target, args);
            onReached();
            await gate;
            return r;
          };
        }
        const v = Reflect.get(target, prop);
        return typeof v === "function" ? v.bind(target) : v;
      },
    });
  const db = new Proxy(env.DB, {
    get(target, prop) {
      if (prop === "prepare") {
        return (sql: string) => {
          const stmt = target.prepare(sql);
          if (!first) return stmt;
          first = false;
          return held(stmt);
        };
      }
      const v = Reflect.get(target, prop);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
  return { db, reached, release };
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
  // Replays the TLC counterexample of formal/tla/Registration_norecheck.cfg
  // (RetriesAgree): the second request's batch consumes 0 rows because the
  // first used the code; without the recheck it answered 401 / 409.
  it("Registration_norecheck: the same code and token: one creates the device, the other gets it (200)", async () => {
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

  // Registration_norecheck at the device limit: the first request takes the last slot.
  it("Registration_norecheck: the same code and token at the user's device limit: still 200", async () => {
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

  // Replays the TLC counterexample of formal/tla/Accounts_norecheck.cfg
  // (RetriesAgree, MaxUsers = 1): the second request's user INSERT inserts 0
  // rows because the first took the last slot; without the recheck it
  // answered 403 "registration full".
  it("Accounts_norecheck: the same token at MAX_USERS: one creates the account, the other gets it (200)", async () => {
    const env1 = { ...OPEN, MAX_USERS: "1" };
    const [a, b] = await race(
      (db) => selfRegister({ deviceName: "pixel", token, username: "alice" }, "192.0.2.1", { ...env1, DB: db }),
      (db) => selfRegister({ deviceName: "pixel", token, username: "alice" }, "192.0.2.2", { ...env1, DB: db }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
  });

  it("the same token below MAX_USERS: 201 and 200, one user", async () => {
    const [a, b] = await race(
      (db) => selfRegister({ deviceName: "pixel", token, username: "alice" }, "192.0.2.1", { ...OPEN, DB: db }),
      (db) => selfRegister({ deviceName: "pixel", token, username: "alice" }, "192.0.2.2", { ...OPEN, DB: db }),
    );
    const first = await body(a);
    expect(first.status).toBe(201);
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(1);
  });

  // Replays the TLC counterexample of formal/tla/Accounts_closedrace.cfg
  // (RetriesAgree): copies A and B of one request both pass the recovery
  // lookup; A creates the account; the operator closes registration; B then
  // reaches the REGISTRATION check. Without the second lookup B got 403
  // "registration closed", and the client gave up an account that exists.
  // The Worker reads REGISTRATION after the recovery, so B's environment
  // has it closed from the start: B reads it only after A completed.
  it("Accounts_closedrace: a copy that passed the recovery before the account existed gets it after the close (200)", async () => {
    const t = newToken();
    const held = holdAfterFirstQuery();
    const pb = selfRegister({ deviceName: "pixel", token: t, username: "alice" }, "192.0.2.2", { REGISTRATION: "admin", DB: held.db });
    await held.reached; // B's recovery found nothing
    const a = await body(await selfRegister({ deviceName: "pixel", token: t, username: "alice" }, "192.0.2.1", OPEN));
    expect(a.status).toBe(201);
    held.release();
    expect(await body(await pb)).toEqual({ status: 200, body: a.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
  });

  // The case the recheck does not cover: B is refused before A commits.
  // The account exists afterwards; a later copy (the client sends again
  // before it takes a 403 as final, see docs/sync-protocol.md) gets it.
  it("a copy refused before the other copy committed: 403, and a later copy gets the account (200)", async () => {
    const t = newToken();
    const b = await body(await selfRegister({ deviceName: "pixel", token: t, username: "alice" }, "192.0.2.2", { REGISTRATION: "admin" }));
    expect(b).toEqual({ status: 403, body: { error: "registration closed" } });
    const a = await body(await selfRegister({ deviceName: "pixel", token: t, username: "alice" }, "192.0.2.1", OPEN));
    expect(a.status).toBe(201);
    const again = await body(await selfRegister({ deviceName: "pixel", token: t, username: "alice" }, "192.0.2.2", { REGISTRATION: "admin" }));
    expect(again).toEqual({ status: 200, body: a.body });
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

describe("concurrent registration with an invite", () => {
  const invites = async () =>
    (await (await adminCall("/admin/invites")).json()) as Array<{ status: string; user: { username: string } | null }>;

  // formal/tla/Invites.tla RetriesAgree: the second copy's batch consumes
  // nothing (the first used the invite), and the recheck finds the account.
  it("the same request twice: one creates the account, the other gets it (200)", async () => {
    const { code } = await createInvite();
    const token = newToken();
    const [a, b] = await race(
      (db) => acceptInvite(code, "alice", token, "192.0.2.1", { DB: db }),
      (db) => acceptInvite(code, "alice", token, "192.0.2.2", { DB: db }),
    );
    const first = await body(a);
    expect(first).toEqual({ status: 201, body: { deviceId: expect.any(String), username: "alice" } });
    expect(await body(b)).toEqual({ status: 200, body: first.body });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
  });

  // Invites.tla OneUserPerInvite.
  it("two tokens race for one invite: one user; the other gets 401", async () => {
    const { code } = await createInvite();
    const [a, b] = await race(
      (db) => acceptInvite(code, "alice", newToken(), "192.0.2.1", { DB: db }),
      (db) => acceptInvite(code, "bob", newToken(), "192.0.2.2", { DB: db }),
    );
    expect(a.status).toBe(201);
    expect(await body(b)).toEqual({ status: 401, body: { error: "invalid or expired invite" } });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect((await invites())[0]).toMatchObject({ status: "used", user: { username: "alice" } });
  });

  // Invites.tla UsernameUnique and ConsumedHasUser: the loser's batch rolls
  // back (UNIQUE), and its invite stays unused.
  it("two invites race for one username: 201 and 409, the loser's invite unused", async () => {
    const [x, y] = [await createInvite(), await createInvite()];
    const [a, b] = await race(
      (db) => acceptInvite(x.code, "alice", newToken(), "192.0.2.1", { DB: db }),
      (db) => acceptInvite(y.code, "Alice", newToken(), "192.0.2.2", { DB: db }),
    );
    expect(a.status).toBe(201);
    expect(await body(b)).toEqual({ status: 409, body: { error: "username taken" } });
    expect(await count("SELECT COUNT(*) AS n FROM users")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM devices")).toBe(1);
    expect((await invites()).map((i) => i.status).sort()).toEqual(["pending", "used"]);
  });

  it("without invites, two tokens race for one username: 201 and 409", async () => {
    const [a, b] = await race(
      (db) => selfRegister({ token: newToken(), username: "alice" }, "192.0.2.1", { ...OPEN, DB: db }),
      (db) => selfRegister({ token: newToken(), username: "alice" }, "192.0.2.2", { ...OPEN, DB: db }),
    );
    expect(a.status).toBe(201);
    expect(await body(b)).toEqual({ status: 409, body: { error: "username taken" } });
    expect(await count("SELECT user_count AS n FROM server_usage")).toBe(1);
  });
});
