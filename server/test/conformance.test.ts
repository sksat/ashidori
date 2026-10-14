// Conformance of the real migrations and SQL to the invariants of the formal
// models (formal/tla), checked on random operation sequences. Each sequence
// comes from a seeded PRNG: a failure names its seed and step, and the same
// seed replays the same sequence.
//
// - Counters (BatchCommit CountersMatch / UnderCaps, Accounts UsersUnderMax):
//   the trigger-maintained counters equal independent SUM/COUNT queries, and
//   no limit is exceeded, after batch PUTs (concurrent, duplicate and
//   conflicting), revocations, registrations, shared check-in PUT/DELETE and
//   admin removals, follows, disabling and enabling users, self-registration
//   and the scheduled maintenance.
// - Shared check-in revisions (SharedCheckin LatestWins, NoPhantom,
//   AdminAbsorbing, StaleMeansSuperseded): PUT and DELETE with strictly
//   increasing revs, delivered any number of times, in any order, also
//   concurrently; the stored state is that of the highest-rev operation
//   delivered, and an admin removal is final.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { maintenance } from "../src/index";
import {
  adminCall,
  authed,
  call,
  createUser,
  issueAdminCode,
  legacyDevice,
  newToken,
  newUsername,
  OPEN,
  register,
  sha256Hex,
} from "./helpers";

/** mulberry32: a small seeded PRNG. */
function prng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    int: (n: number) => Math.floor(next() * n),
    pick<T>(xs: readonly T[]): T {
      return xs[Math.floor(next() * xs.length)]!;
    },
    chance: (p: number) => next() < p,
  };
}
type Rng = ReturnType<typeof prng>;

// ---- Counters ----

/**
 * Small limits, so that sequences reach them. Odd seeds run into the byte
 * quotas first (blobs are 32 to 121 bytes), even seeds into the batch count.
 */
function limitsFor(seed: number) {
  const bytes = seed % 2 === 1;
  return {
    USER_QUOTA_BYTES: bytes ? "300" : "5000",
    USER_MAX_BATCHES: bytes ? "50" : "4",
    MAX_TOTAL_BYTES: bytes ? "700" : "1200",
    USER_MAX_SHARED_CHECKINS: "4",
    USER_MAX_DEVICES: "3",
    MAX_USERS: "6",
  };
}
let LIMITS = limitsFor(1);
let ENV: Partial<Cloudflare.Env> = { ...OPEN, ...LIMITS };

interface Dev {
  deviceId: string;
  token: string;
  userId: string | null;
}

interface World {
  devices: Dev[];
  users: string[];
  /** Server ids of shared check-ins seen in responses. */
  checkinIds: string[];
  rev: number;
}

function req(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  return authed(token, path, init, ENV);
}

/** A blob for a slot: two variants per slot, of different sizes, so that re-sends both repeat and conflict. */
function slotBlob(deviceId: string, seq: number, variant: number): Uint8Array {
  const size = 32 + ((seq * 23 + variant * 41 + deviceId.charCodeAt(0)) % 90);
  const b = new Uint8Array(size);
  for (let i = 0; i < size; i++) b[i] = (i * 7 + seq * 13 + variant * 101 + deviceId.charCodeAt(i % deviceId.length)) & 0xff;
  return b;
}

async function putBlob(d: Dev, seq: number, variant: number): Promise<Response> {
  const body = slotBlob(d.deviceId, seq, variant);
  return req(d.token, `/v1/batches/s/${seq}`, {
    method: "PUT",
    headers: { "Content-Type": "application/octet-stream", "X-Content-SHA256": await sha256Hex(body) },
    body,
  });
}

const VENUES = ["a", "b", "c"];

type Op = { name: string; run: () => Promise<unknown> };

/** Outcomes seen over all counter sequences ("<op>:<status> <error>"), to check that they reach the limits. */
const reached = new Set<string>();
async function note(op: string, res: Response): Promise<Response> {
  const body = (await res.clone().json().catch(() => ({}))) as { error?: string };
  // Which quota refused it: the seed's limits make it the batch count or the bytes.
  const which = body.error === "quota exceeded" && LIMITS.USER_MAX_BATCHES === "4" ? " (batches)" : "";
  reached.add(`${op}:${res.status}${body.error ? ` ${body.error}${which}` : ""}`);
  return res;
}

/** One random operation; `concurrent` leaves out the maintenance (its clock is not D1's, see below). */
function randomOp(rng: Rng, w: World, concurrent: boolean): Op {
  const dev = () => rng.pick(w.devices);
  const userDev = () => rng.pick(w.devices.filter((d) => d.userId !== null).length ? w.devices.filter((d) => d.userId !== null) : w.devices);
  const kinds = [
    "put", "put", "put", "burst", "burst", "putSame", "revoke", "pair", "share", "share", "unshare", "unshareById",
    "adminRemove", "follow", "unfollow", "toggleUser", "selfRegister",
    ...(concurrent ? [] : (["maintenance"] as const)),
  ] as const;
  const kind = rng.pick(kinds);
  switch (kind) {
    case "put": {
      const d = dev();
      const seq = rng.int(6);
      const v = rng.int(2);
      return { name: `put ${d.deviceId.slice(0, 4)} s/${seq} v${v}`, run: async () => note("put", await putBlob(d, seq, v)) };
    }
    case "putSame": {
      // The same request twice at once (a retry before the first answer).
      const d = dev();
      const seq = rng.int(6);
      const v = rng.int(2);
      return { name: `put×2 ${d.deviceId.slice(0, 4)} s/${seq} v${v}`, run: async () => Promise.all((await Promise.all([putBlob(d, seq, v), putBlob(d, seq, v)])).map((r) => note("put", r))) };
    }
    case "burst": {
      // Several uploads of one user at once, to different slots: they pass
      // the early usage check together, and only the commit statement keeps
      // them within the limits.
      const d0 = dev();
      const mine = w.devices.filter((d) => d.userId === d0.userId);
      const puts = Array.from({ length: 3 + rng.int(3) }, () => [rng.pick(mine), rng.int(6), rng.int(2)] as const);
      return {
        name: `burst ${puts.map(([d, seq, v]) => `${d.deviceId.slice(0, 4)} s/${seq} v${v}`).join(", ")}`,
        run: async () => Promise.all((await Promise.all(puts.map(([d, seq, v]) => putBlob(d, seq, v)))).map((r) => note("put", r))),
      };
    }
    case "revoke": {
      const d = dev();
      return { name: `revoke ${d.deviceId.slice(0, 4)}`, run: () => adminCall(`/admin/devices/${d.deviceId}/revoke`, { method: "POST" }) };
    }
    case "pair": {
      const d = userDev();
      return {
        name: `pair from ${d.deviceId.slice(0, 4)}`,
        run: async () => {
          const ip = `203.0.113.${rng.int(250)}`;
          const res = await authed(d.token, "/v1/pairing", { method: "POST", headers: { "CF-Connecting-IP": ip } }, ENV);
          if (res.status !== 201) return;
          const { code } = (await res.json()) as { code: string };
          const token = newToken();
          const reg = await note("register", await register(code, "new", ip, token, ENV));
          if (reg.status === 201) {
            const { deviceId } = (await reg.json()) as { deviceId: string };
            w.devices.push({ deviceId, token, userId: d.userId });
          }
        },
      };
    }
    case "share": {
      const d = userDev();
      const local = `c${rng.int(5)}`;
      const rev = ++w.rev;
      const venue = rng.pick(VENUES);
      return {
        name: `share ${d.deviceId.slice(0, 4)} ${local} rev ${rev}`,
        run: async () => {
          const res = await note("share", await req(d.token, `/v1/checkins/shared/${local}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ rev, t: 1, lat: 1, lon: 2, venue }),
          }));
          if (res.status === 200) w.checkinIds.push(((await res.json()) as { id: string }).id);
        },
      };
    }
    case "unshare": {
      const d = userDev();
      const local = `c${rng.int(6)}`; // c5 is never shared: a tombstone of an unknown id
      const rev = ++w.rev;
      return { name: `unshare ${d.deviceId.slice(0, 4)} ${local} rev ${rev}`, run: async () => note("unshare", await req(d.token, `/v1/checkins/shared/${local}?rev=${rev}`, { method: "DELETE" })) };
    }
    case "unshareById": {
      const d = userDev();
      const id = w.checkinIds.length ? rng.pick(w.checkinIds) : "none";
      const rev = ++w.rev;
      return { name: `unshare by id ${id.slice(0, 4)} rev ${rev}`, run: () => req(d.token, `/v1/checkins/shared/by-id/${id}?rev=${rev}`, { method: "DELETE" }) };
    }
    case "adminRemove": {
      const id = w.checkinIds.length ? rng.pick(w.checkinIds) : "none";
      return { name: `admin remove ${id.slice(0, 4)}`, run: () => adminCall(`/admin/checkins/${id}/delete`, { method: "POST" }) };
    }
    case "follow": {
      const a = userDev();
      const b = userDev();
      return {
        name: `follow ${a.userId?.slice(0, 4)} -> ${b.userId?.slice(0, 4)}`,
        run: async () => {
          const bDev = w.devices.find((d) => d.userId === b.userId)!;
          await req(a.token, "/v1/follows", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ userId: b.userId }) });
          await req(bDev.token, `/v1/follow-requests/${a.userId}/accept`, { method: "POST" });
        },
      };
    }
    case "unfollow": {
      const a = userDev();
      const b = userDev();
      return { name: `unfollow ${a.userId?.slice(0, 4)} -> ${b.userId?.slice(0, 4)}`, run: () => req(a.token, `/v1/follows/${b.userId}`, { method: "DELETE" }) };
    }
    case "toggleUser": {
      const u = rng.pick(w.users);
      const to = rng.pick(["disable", "enable"]);
      return { name: `${to} ${u.slice(0, 4)}`, run: () => adminCall(`/admin/users/${u}/${to}`, { method: "POST" }) };
    }
    case "selfRegister": {
      const token = newToken();
      return {
        name: "self-register",
        run: async () => {
          const res = await note("selfRegister", await call(
            "/v1/accounts",
            {
              method: "POST",
              headers: { "Content-Type": "application/json", "CF-Connecting-IP": `198.51.100.${rng.int(250)}` },
              body: JSON.stringify({ token, deviceName: "self", username: newUsername() }),
            },
            ENV,
          ));
          if (res.status === 201) {
            const { deviceId } = (await res.json()) as { deviceId: string };
            const row = await env.DB.prepare("SELECT user_id FROM devices WHERE id = ?1").bind(deviceId).first<{ user_id: string }>();
            w.devices.push({ deviceId, token, userId: row!.user_id });
            w.users.push(row!.user_id);
          }
        },
      };
    }
    case "maintenance": {
      // Eight days later: unused self-registered users are deleted, and the
      // orphan sweep sees every object as old. Only between requests: an
      // upload in flight would see its object swept by this made-up clock
      // while D1's clock still lets it commit (the real sweep runs on the
      // real clock; formal/tla BatchCommit).
      return {
        name: "maintenance +8d",
        run: async () => {
          await maintenance({ ...env, ...ENV } as Cloudflare.Env, Date.now() + 8 * 24 * 60 * 60 * 1000);
          const gone = await env.DB.prepare("SELECT id FROM devices").all<{ id: string }>();
          const alive = new Set(gone.results.map((r) => r.id));
          w.devices = w.devices.filter((d) => alive.has(d.deviceId));
          const users = await env.DB.prepare("SELECT id FROM users").all<{ id: string }>();
          w.users = users.results.map((r) => r.id);
        },
      };
    }
    default:
      throw new Error(`unknown op ${kind satisfies never}`);
  }
}

/** The invariants; returns the violations (empty if none). */
async function violations(): Promise<string[]> {
  const out: string[] = [];
  const { results: users } = await env.DB.prepare(
    `SELECT u.id, u.storage_bytes, u.batch_count, u.checkin_count,
       (SELECT COALESCE(SUM(b.size), 0) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id = u.id) AS sum_bytes,
       (SELECT COUNT(*) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id = u.id) AS n_batches,
       (SELECT COUNT(*) FROM shared_checkins p WHERE p.user_id = u.id) AS n_checkins,
       (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id) AS n_devices
     FROM users u`,
  ).all<Record<string, number | string>>();
  for (const u of users) {
    if (u.storage_bytes !== u.sum_bytes) out.push(`user ${u.id}: storage_bytes ${u.storage_bytes} != ${u.sum_bytes}`);
    if (u.batch_count !== u.n_batches) out.push(`user ${u.id}: batch_count ${u.batch_count} != ${u.n_batches}`);
    if (u.checkin_count !== u.n_checkins) out.push(`user ${u.id}: checkin_count ${u.checkin_count} != ${u.n_checkins}`);
    if ((u.sum_bytes as number) > Number(LIMITS.USER_QUOTA_BYTES)) out.push(`user ${u.id}: over the quota`);
    if ((u.n_batches as number) > Number(LIMITS.USER_MAX_BATCHES)) out.push(`user ${u.id}: over the batch limit`);
    if ((u.n_checkins as number) > Number(LIMITS.USER_MAX_SHARED_CHECKINS)) out.push(`user ${u.id}: over the check-in limit`);
    if ((u.n_devices as number) > Number(LIMITS.USER_MAX_DEVICES)) out.push(`user ${u.id}: over the device limit`);
  }
  const s = (await env.DB.prepare(
    `SELECT s.user_count, s.total_bytes, s.legacy_storage_bytes, s.legacy_batch_count,
       (SELECT COUNT(*) FROM users) AS n_users,
       (SELECT COALESCE(SUM(size), 0) FROM batches) AS sum_bytes,
       (SELECT COALESCE(SUM(b.size), 0) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id IS NULL) AS legacy_bytes,
       (SELECT COUNT(*) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id IS NULL) AS legacy_n
     FROM server_usage s WHERE s.id = 1`,
  ).first<Record<string, number>>())!;
  if (s.user_count !== s.n_users) out.push(`user_count ${s.user_count} != ${s.n_users}`);
  if (s.total_bytes !== s.sum_bytes) out.push(`total_bytes ${s.total_bytes} != ${s.sum_bytes}`);
  if (s.legacy_storage_bytes !== s.legacy_bytes) out.push(`legacy_storage_bytes ${s.legacy_storage_bytes} != ${s.legacy_bytes}`);
  if (s.legacy_batch_count !== s.legacy_n) out.push(`legacy_batch_count ${s.legacy_batch_count} != ${s.legacy_n}`);
  if (s.sum_bytes! > Number(LIMITS.MAX_TOTAL_BYTES)) out.push("over MAX_TOTAL_BYTES");
  if (s.legacy_bytes! > Number(LIMITS.USER_QUOTA_BYTES) || s.legacy_n! > Number(LIMITS.USER_MAX_BATCHES)) out.push("legacy over its quota");
  // MAX_USERS bounds self-registration only; this sequence creates no other users beyond the initial ones.
  if (s.n_users! > Number(LIMITS.MAX_USERS)) out.push("over MAX_USERS");

  // BatchCommit CommittedHasObject: every committed batch has its object.
  const { results: rows } = await env.DB.prepare("SELECT r2_key, sha256, size FROM batches").all<{ r2_key: string; sha256: string; size: number }>();
  for (const r of rows) {
    const o = await env.BLOBS.head(r.r2_key);
    if (!o || o.size !== r.size) out.push(`batch ${r.r2_key}: object missing or of another size`);
  }
  return out;
}

const COUNTER_SEEDS = [1, 2, 3, 4, 5, 6, 7, 8];
const COUNTER_STEPS = 80;

describe("counters and limits on random sequences", () => {
  it.each(COUNTER_SEEDS)("seed %i", async (seed) => {
    const rng = prng(seed);
    LIMITS = limitsFor(seed);
    ENV = { ...OPEN, ...LIMITS };
    const w: World = { devices: [], users: [], checkinIds: [], rev: 0 };
    for (const name of ["u1", "u2", "u3"]) {
      const userId = await createUser(name);
      w.users.push(userId);
      for (let i = 0; i < 2; i++) {
        const token = newToken();
        const res = await register(await issueAdminCode(userId), name, "192.0.2.1", token, ENV);
        w.devices.push({ deviceId: ((await res.json()) as { deviceId: string }).deviceId, token, userId });
      }
    }
    w.devices.push({ ...(await legacyDevice("legacy1")), userId: null });

    const log: string[] = [];
    for (let step = 0; step < COUNTER_STEPS; step++) {
      if (rng.chance(0.35)) {
        const ops = Array.from({ length: 2 + rng.int(3) }, () => randomOp(rng, w, true));
        log.push(`[${ops.map((o) => o.name).join(" | ")}]`);
        await Promise.all(ops.map((o) => o.run()));
      } else {
        const op = randomOp(rng, w, false);
        log.push(op.name);
        await op.run();
      }
      const bad = await violations();
      expect(bad, `seed ${seed}, step ${step}:\n${log.join("\n")}`).toEqual([]);
    }
  });

  it("reach every limit and race (else the checks above say little)", () => {
    for (const outcome of [
      "put:201",
      "put:200",
      "put:409 batch already exists with a different hash",
      "put:507 quota exceeded",
      "put:507 quota exceeded (batches)",
      "put:507 server storage full",
      "put:401 unauthorized",
      "share:507 too many shared check-ins",
      "unshare:507 too many shared check-ins",
      "register:201",
      "register:409 too many devices",
      "selfRegister:201",
      "selfRegister:403 registration full",
    ]) {
      expect([...reached].sort(), outcome).toContain(outcome);
    }
  });
});

// ---- Shared check-in revisions ----

interface CheckinOp {
  rev: number;
  kind: "put" | "del";
  venue: string;
}

interface Row {
  id: string;
  rev: number;
  deleted_at: number | null;
  venue: string | null;
  removed_by_admin: number;
}

const REV_SEEDS = [11, 12, 13, 14, 15, 16, 17, 18];
/** Answers seen over all revision sequences ("<kind>:<status> <error>"). */
const revReached = new Set<string>();
const REV_STEPS = 70;
const LOCALS = ["x", "y"];

describe("shared check-in revisions on random sequences (SharedCheckin)", () => {
  it.each(REV_SEEDS)("seed %i", async (seed) => {
    const rng = prng(seed);
    const userId = await createUser("rev");
    const devs: Dev[] = [];
    for (let i = 0; i < 2; i++) {
      const token = newToken();
      const res = await register(await issueAdminCode(userId), "d", "192.0.2.1", token);
      devs.push({ deviceId: ((await res.json()) as { deviceId: string }).deviceId, token, userId });
    }
    const owner = devs[0]!;

    // Per local id: the client's issued operations, those delivered, and whether an admin removed it.
    const issued = new Map<string, CheckinOp[]>(LOCALS.map((l) => [l, []]));
    const delivered = new Map<string, Set<CheckinOp>>(LOCALS.map((l) => [l, new Set()]));
    const removed = new Map<string, boolean>(LOCALS.map((l) => [l, false]));
    let lastRev = 0;
    const log: string[] = [];

    const rowOf = (local: string) =>
      env.DB.prepare(
        "SELECT id, rev, deleted_at, venue, removed_by_admin FROM shared_checkins WHERE device_id = ?1 AND local_id = ?2",
      )
        .bind(owner.deviceId, local)
        .first<Row>();

    /** Delivers `op`; returns the answer. By server id from the other device when the entry exists (DELETE only). */
    const deliver = async (local: string, op: CheckinOp): Promise<{ status: number; error?: string }> => {
      let res: Response;
      if (op.kind === "put") {
        res = await authed(owner.token, `/v1/checkins/shared/${local}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ rev: op.rev, t: 1, lat: 1, lon: 2, venue: op.venue }),
        });
      } else {
        const row = rng.chance(0.4) ? await rowOf(local) : null;
        res = row
          ? await authed(devs[1]!.token, `/v1/checkins/shared/by-id/${row.id}?rev=${op.rev}`, { method: "DELETE" })
          : await authed(owner.token, `/v1/checkins/shared/${local}?rev=${op.rev}`, { method: "DELETE" });
      }
      delivered.get(local)!.add(op);
      const body = (await res.json()) as { error?: string };
      return { status: res.status, error: body.error };
    };

    for (let step = 0; step < REV_STEPS; step++) {
      const local = rng.pick(LOCALS);
      const r = rng.int(10);
      const answers: Array<{ local: string; op: CheckinOp; status: number; error?: string }> = [];
      if (r < 3 || issued.get(local)!.length === 0) {
        // Issue: a new operation with a higher rev (gaps allowed), delivered at once.
        lastRev += 1 + rng.int(3);
        const op: CheckinOp = { rev: lastRev, kind: rng.chance(0.6) ? "put" : "del", venue: rng.pick(VENUES) };
        issued.get(local)!.push(op);
        log.push(`issue+deliver ${local} ${op.kind} ${op.rev} ${op.venue}`);
        answers.push({ local, op, ...(await deliver(local, op)) });
      } else if (r < 7) {
        // Redeliver an issued operation (a retry, or a delayed copy).
        const op = rng.pick(issued.get(local)!);
        log.push(`redeliver ${local} ${op.kind} ${op.rev}`);
        answers.push({ local, op, ...(await deliver(local, op)) });
      } else if (r < 9) {
        // Several issued operations at once, in any order.
        const ops = Array.from({ length: 2 + rng.int(2) }, () => rng.pick(issued.get(local)!));
        log.push(`concurrent ${local} [${ops.map((o) => `${o.kind} ${o.rev}`).join(", ")}]`);
        const got = await Promise.all(ops.map((op) => deliver(local, op)));
        got.forEach((g, i) => answers.push({ local, op: ops[i]!, ...g }));
      } else {
        const row = await rowOf(local);
        if (row) {
          log.push(`admin remove ${local}`);
          expect((await adminCall(`/admin/checkins/${row.id}/delete`, { method: "POST" })).status).toBe(200);
          removed.set(local, true);
        }
      }

      const where = `seed ${seed}, step ${step}:\n${log.join("\n")}`;
      for (const l of LOCALS) {
        const row = await rowOf(l);
        const ds = [...delivered.get(l)!];
        // NoPhantom, both ways: a row exists iff an operation for it arrived.
        expect(row !== null, where).toBe(ds.length > 0);
        if (!row) continue;
        if (removed.get(l)) {
          // AdminAbsorbing: removed, a tombstone, whatever arrived since.
          expect({ removed: row.removed_by_admin, deleted: row.deleted_at !== null, venue: row.venue }, where).toEqual({
            removed: 1,
            deleted: true,
            venue: null,
          });
        } else {
          // LatestWins: the state of the highest-rev operation delivered.
          const max = ds.reduce((a, b) => (b.rev > a.rev ? b : a));
          expect({ rev: row.rev, deleted: row.deleted_at !== null, venue: row.venue }, where).toEqual({
            rev: max.rev,
            deleted: max.kind === "del",
            venue: max.kind === "put" ? max.venue : null,
          });
        }
      }
      for (const a of answers) {
        const where2 = `seed ${seed}, step ${step} (${a.op.kind} ${a.op.rev}):\n${log.join("\n")}`;
        expect([200, 409], where2).toContain(a.status);
        revReached.add(`${a.op.kind}:${a.status}${a.error ? ` ${a.error}` : ""}`);
        if (a.error === "stale revision") {
          // StaleMeansSuperseded: a later operation for the entry had arrived.
          expect([...delivered.get(a.local)!].some((o) => o.rev > a.op.rev), where2).toBe(true);
        }
        if (a.error === "removed by an admin") expect(removed.get(a.local), where2).toBe(true);
      }
    }
  });

  it("reach stale, retried and removed answers (else the checks above say little)", () => {
    for (const outcome of [
      "put:200",
      "del:200",
      "put:409 stale revision",
      "del:409 stale revision",
      "put:409 removed by an admin",
    ]) {
      expect([...revReached].sort(), outcome).toContain(outcome);
    }
  });
});
