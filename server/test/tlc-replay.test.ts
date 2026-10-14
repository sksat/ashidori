// The counterexamples TLC finds for the earlier designs (the sanity configs
// of formal/tla, expected to fail there), replayed as concrete operation
// sequences against the real handlers and SQL. Each test is named after its
// config and asserts the invariant that the counterexample breaks.
//
// Replayed elsewhere, under the name of their config:
// - Registration_norecheck (formal/tla/Registration_norecheck.cfg, RetriesAgree):
//   test/registration-race.test.ts, "Registration_norecheck: ...".
// - Accounts_norecheck (formal/tla/Accounts_norecheck.cfg, RetriesAgree):
//   test/registration-race.test.ts, "Accounts_norecheck: ...".
// - Accounts_closedrace (formal/tla/Accounts_closedrace.cfg, RetriesAgree):
//   test/registration-race.test.ts, "Accounts_closedrace: ...".
// - SharedCheckin_old (formal/tla/SharedCheckin_old.cfg, LatestWins):
//   test/checkins-rev.test.ts, "SharedCheckin_old: ...".
// Replayed here:
// - BatchCommit_current (formal/tla/BatchCommit_current.cfg, CommittedHasObject, SweepSafe).
// - FeedResync_firstpage (formal/tla/FeedResync_firstpage.cfg, NoMixedVersions).

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { ORPHAN_MIN_AGE_MS, sweepOrphanBlobs } from "../src/batches";
import { CHECKIN, feed, follow, newUser, putBatch, putCheckin, sha256Hex } from "./helpers";

const HOUR = 60 * 60 * 1000;

describe("BatchCommit_current (formal/tla/BatchCommit_current.cfg)", () => {
  // Counterexample: Start -> Put -> (the attempt stalls longer than the grace
  // period G) -> SweepScan finds the object old and unreferenced ->
  // SweepDelete -> Commit lands: a listed batch without its blob
  // (CommittedHasObject, SweepSafe). The fix: D1 refuses a commit more than
  // the commit deadline D after the attempt started, by D1's clock.
  it("a commit delayed beyond the grace period, with the sweep in between, is refused", async () => {
    const a = await newUser("a");
    const body = new Uint8Array(40).fill(7);
    const sha = await sha256Hex(body);

    // Start: D1's clock as the attempt reads it is G + 1 h before the commit.
    const stalledFor = ORPHAN_MIN_AGE_MS + HOUR;
    const db = new Proxy(env.DB, {
      get(target, prop) {
        if (prop === "prepare") {
          return (sql: string) =>
            target.prepare(/^SELECT .* AS t$/.test(sql) ? sql.replace(" AS t", ` - ${stalledFor} AS t`) : sql);
        }
        const v = Reflect.get(target, prop);
        return typeof v === "function" ? v.bind(target) : v;
      },
    });
    // Put, then hold the attempt between the put and the commit.
    let release!: () => void;
    let onStored!: () => void;
    const held = new Promise<void>((r) => (release = r));
    const stored = new Promise<void>((r) => (onStored = r));
    const bucket = new Proxy(env.BLOBS, {
      get(target, prop) {
        if (prop === "put") {
          return async (...args: Parameters<R2Bucket["put"]>) => {
            const res = await target.put(...args);
            onStored();
            await held;
            return res;
          };
        }
        const v = Reflect.get(target, prop);
        return typeof v === "function" ? v.bind(target) : v;
      },
    });
    const attempt = putBatch(a.token, "fixes", 0, body, sha, { DB: db, BLOBS: bucket });
    await stored;
    const [key] = (await env.BLOBS.list({ prefix: "b/" })).objects.map((o) => o.key);
    expect(key).toBeDefined();

    // SweepScan + SweepDelete, G + 1 h after the put: the object is old and unreferenced.
    expect(await sweepOrphanBlobs(env.DB, env.BLOBS, Date.now() + stalledFor)).toBe(1);
    expect(await env.BLOBS.head(key!)).toBeNull();

    // Commit: refused (503, retry), so no row references the swept object.
    release();
    const res = await attempt;
    expect(res.status).toBe(503);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM batches").first()).toEqual({ n: 0 });

    // The client sends the same blob again; CommittedHasObject holds.
    expect((await putBatch(a.token, "fixes", 0, body, sha)).status).toBe(201);
    const { results } = await env.DB.prepare("SELECT r2_key FROM batches").all<{ r2_key: string }>();
    for (const r of results) expect(await env.BLOBS.head(r.r2_key)).not.toBeNull();
  });
});

describe("FeedResync_firstpage (formal/tla/FeedResync_firstpage.cfg)", () => {
  interface Client {
    cur: number;
    ver: number | null;
    view: Map<string, string>;
    /** Ghost of the model: the followsVersion of every page applied since the last reset. */
    applied: Set<number>;
  }

  /**
   * The client algorithm of docs/sync-protocol.md "Feed", with pages of one
   * row. `checkEveryPage = false` is the earlier algorithm (the version is
   * checked on the first page only). `between` runs after each page.
   */
  async function sync(token: string, c: Client, checkEveryPage: boolean, between: (page: number) => Promise<void>) {
    let first = true;
    for (let page = 0; ; page++) {
      const f = await feed(token, `?after=${c.cur}&limit=1`);
      if (c.ver !== null && f.followsVersion !== c.ver && (checkEveryPage || first)) {
        c.view.clear();
        c.cur = 0;
        c.ver = f.followsVersion;
        c.applied.clear();
      } else {
        for (const i of f.items) {
          if (i.deleted) c.view.delete(i.id);
          else c.view.set(i.id, i.venue!);
          c.cur = i.cursor;
        }
        c.ver = f.followsVersion;
        c.applied.add(f.followsVersion);
        if (f.next === null) return;
      }
      first = false;
      await between(page);
    }
  }

  /** What the client should show: a full listing from after=0. */
  async function truth(token: string): Promise<Map<string, string>> {
    const all = await feed(token, "?after=0&limit=500");
    return new Map(all.items.filter((i) => !i.deleted).map((i) => [i.id, i.venue!]));
  }

  /**
   * Counterexample: an incremental sync is under way when the caller starts
   * following another user (followsVersion changes). Entries of the new
   * followee are older than the cursor; the next page carries the new
   * version. A client that checks the version on the first page only
   * applies that page and finishes without the new followee's entries
   * (NoMixedVersions; the view differs from the truth).
   */
  async function scenario(checkEveryPage: boolean) {
    const me = await newUser("me");
    const bob = await newUser("bob");
    const carol = await newUser("carol");
    // Carol's entry first, so that its cursor is below everything else.
    await putCheckin(carol.token, "e3", { ...CHECKIN, venue: "carol" });
    await putCheckin(me.token, "e1", { ...CHECKIN, venue: "me" });
    await putCheckin(bob.token, "e2", { ...CHECKIN, venue: "bob" });
    await follow(me, bob);

    const c: Client = { cur: 0, ver: null, view: new Map(), applied: new Set() };
    await sync(me.token, c, checkEveryPage, async () => {});
    expect(c.view).toEqual(await truth(me.token));

    // Two edits move both entries past the cursor; between their pages, me follows carol.
    await putCheckin(bob.token, "e2", { ...CHECKIN, rev: 2, venue: "bob2" });
    await putCheckin(me.token, "e1", { ...CHECKIN, rev: 2, venue: "me2" });
    c.applied.clear();
    await sync(me.token, c, checkEveryPage, async (page) => {
      if (page === 0) await follow(me, carol);
    });
    return { c, truth: await truth(me.token) };
  }

  it("a follow change between pages: the version on every page makes the client resync", async () => {
    const { c, truth } = await scenario(true);
    expect(c.applied.size).toBe(1); // NoMixedVersions
    expect(c.applied).toEqual(new Set([c.ver]));
    expect(c.view).toEqual(truth); // ConvergedWhenStable
    expect([...c.view.values()].sort()).toEqual(["bob2", "carol", "me2"]);
  });

  it("sanity: the server lets a first-page-only client mix versions, as TLC finds", async () => {
    const { c, truth } = await scenario(false);
    expect(c.applied.size).toBe(2);
    expect(c.view).not.toEqual(truth);
    expect([...c.view.values()].sort()).toEqual(["bob2", "me2"]);
  });
});
