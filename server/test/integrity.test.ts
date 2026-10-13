// Concurrent uploads to one slot, and quota races. Requests are interleaved
// deterministically: a request's R2 put is held at a gate, so that it has
// stored its object but not yet committed when the other request runs.

import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { legacyBlobKey, ORPHAN_MIN_AGE_MS } from "../src/batches";
import worker, { maintenance } from "../src/index";
import { authed, newDevice, putBatch, sha256Hex } from "./helpers";

interface Gate {
  /** Resolves once the gated request has stored its object. */
  stored: Promise<void>;
  release: () => void;
  bucket: R2Bucket;
}

/** An R2 binding whose put stores the object, then waits until released. */
function gatedBucket(): Gate {
  let release!: () => void;
  let onStored!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const stored = new Promise<void>((r) => (onStored = r));
  const real = env.BLOBS;
  const bucket = new Proxy(real, {
    get(target, prop) {
      if (prop === "put") {
        return async (...args: Parameters<R2Bucket["put"]>) => {
          const res = await target.put(...args);
          onStored();
          await gate;
          return res;
        };
      }
      const v = Reflect.get(target, prop);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
  return { stored, release, bucket };
}

const bytes = (n: number, fill: number) => new Uint8Array(n).fill(fill);
const slotObjects = async (deviceId: string, stream: string, seq: number) =>
  (await env.BLOBS.list({ prefix: `${legacyBlobKey(deviceId, stream, seq)}/` })).objects.map((o) => o.key);

describe("concurrent PUTs to one slot", () => {
  it("keep metadata and blob together: the committed upload wins, the other gets 409 and its object is deleted", async () => {
    const a = await newDevice();
    const first = bytes(40, 1); // stores first, commits last
    const second = bytes(64, 2);
    const g = gatedBucket();

    const slow = putBatch(a.token, "fixes", 0, first, undefined, { BLOBS: g.bucket });
    await g.stored;
    // Both objects exist now, under different keys.
    const fast = await putBatch(a.token, "fixes", 0, second);
    expect(fast.status).toBe(201);
    expect((await slotObjects(a.deviceId, "fixes", 0)).length).toBe(2);
    g.release();
    const late = await slow;
    expect(late.status).toBe(409);

    // The loser's object is gone; the listing and GET agree on the winner.
    const keys = await slotObjects(a.deviceId, "fixes", 0);
    expect(keys).toHaveLength(1);
    expect(keys[0]).toContain(await sha256Hex(second));
    const list = (await (await authed(a.token, "/v1/batches")).json()) as { items: Array<{ size: number; sha256: string }> };
    expect(list.items).toEqual([expect.objectContaining({ size: 64, sha256: await sha256Hex(second) })]);
    const got = await authed(a.token, `/v1/batches/${a.deviceId}/fixes/0`);
    const data = new Uint8Array(await got.arrayBuffer());
    expect(data).toEqual(second);
    expect(await sha256Hex(data)).toBe(got.headers.get("X-Content-SHA256"));
    // Usage counts the winner only.
    expect(await env.DB.prepare("SELECT storage_bytes, batch_count FROM users").first()).toEqual({ storage_bytes: 64, batch_count: 1 });
  });

  it("with the same content: both succeed, one object remains", async () => {
    const a = await newDevice();
    const body = bytes(48, 7);
    const g = gatedBucket();
    const slow = putBatch(a.token, "fixes", 0, body, undefined, { BLOBS: g.bucket });
    await g.stored;
    expect((await putBatch(a.token, "fixes", 0, body)).status).toBe(201);
    g.release();
    const late = await slow;
    expect(late.status).toBe(200);
    expect(await late.json()).toEqual({ status: "exists" });
    expect(await slotObjects(a.deviceId, "fixes", 0)).toHaveLength(1);
    const got = await authed(a.token, `/v1/batches/${a.deviceId}/fixes/0`);
    expect(new Uint8Array(await got.arrayBuffer())).toEqual(body);
  });

  it("many at once: exactly one commits, and the slot has exactly its object", async () => {
    const a = await newDevice();
    const bodies = Array.from({ length: 6 }, (_, i) => bytes(32 + i, i + 1));
    const results = await Promise.all(bodies.map((b) => putBatch(a.token, "fixes", 5, b)));
    expect(results.filter((r) => r.status === 201)).toHaveLength(1);
    expect(results.filter((r) => r.status === 409)).toHaveLength(5);
    const row = await env.DB.prepare("SELECT sha256, r2_key FROM batches").first<{ sha256: string; r2_key: string }>();
    expect(await slotObjects(a.deviceId, "fixes", 5)).toEqual([row!.r2_key]);
    const obj = await env.BLOBS.get(row!.r2_key);
    expect(await sha256Hex(new Uint8Array(await obj!.arrayBuffer()))).toBe(row!.sha256);
  });
});

describe("quota races", () => {
  it("never let concurrent uploads exceed the quota", async () => {
    const a = await newDevice();
    const QUOTA = { USER_QUOTA_BYTES: "100" };
    const g = gatedBucket();
    // Both pass the early check (usage 0) and store their objects before either commits.
    const slow = putBatch(a.token, "fixes", 0, bytes(60, 1), undefined, { ...QUOTA, BLOBS: g.bucket });
    await g.stored;
    expect((await putBatch(a.token, "fixes", 1, bytes(60, 2), undefined, QUOTA)).status).toBe(201);
    g.release();
    const late = await slow;
    expect(late.status).toBe(507);
    expect(await env.DB.prepare("SELECT storage_bytes, batch_count FROM users").first()).toEqual({ storage_bytes: 60, batch_count: 1 });
    expect(await slotObjects(a.deviceId, "fixes", 0)).toEqual([]);
  });

  it("hold for many parallel uploads, for bytes and for the batch count", async () => {
    const a = await newDevice();
    const r1 = await Promise.all(
      Array.from({ length: 8 }, (_, i) => putBatch(a.token, "fixes", i, bytes(40, i + 1), undefined, { USER_QUOTA_BYTES: "130" })),
    );
    expect(r1.filter((r) => r.status === 201)).toHaveLength(3);
    const b = await newDevice("b");
    const r2 = await Promise.all(
      Array.from({ length: 8 }, (_, i) => putBatch(b.token, "motion", i, bytes(32, i + 1), undefined, { USER_MAX_BATCHES: "8" })),
    );
    // b shares the default user with a, which already has 3 batches.
    expect(r2.filter((r) => r.status === 201)).toHaveLength(5);
    const usage = await env.DB.prepare("SELECT batch_count FROM users").first<{ batch_count: number }>();
    expect(usage!.batch_count).toBe(8);
    const all = await env.BLOBS.list({ prefix: "b/" });
    expect(all.objects).toHaveLength(8);
  });

  it("hold for the server-wide byte cap", async () => {
    const a = await newDevice("a");
    const CAP = { MAX_TOTAL_BYTES: "100" };
    const results = await Promise.all(
      Array.from({ length: 5 }, (_, i) => putBatch(a.token, "fixes", i, bytes(40, i + 1), undefined, CAP)),
    );
    expect(results.filter((r) => r.status === 201)).toHaveLength(2);
    expect(results.filter((r) => r.status === 507)).toHaveLength(3);
    expect((await env.DB.prepare("SELECT total_bytes FROM server_usage").first<{ total_bytes: number }>())!.total_bytes).toBe(80);
  });
});

describe("batches stored before the immutable keys", () => {
  it("are served from the old key", async () => {
    const a = await newDevice();
    const body = bytes(40, 9);
    const sha = await sha256Hex(body);
    await env.BLOBS.put(legacyBlobKey(a.deviceId, "fixes", 3), body);
    await env.DB.prepare(
      "INSERT INTO batches (device_id, stream, seq, size, sha256, created_at) VALUES (?1, 'fixes', 3, 40, ?2, 0)",
    )
      .bind(a.deviceId, sha)
      .run();
    const got = await authed(a.token, `/v1/batches/${a.deviceId}/fixes/3`);
    expect(got.status).toBe(200);
    expect(new Uint8Array(await got.arrayBuffer())).toEqual(body);
    // Re-sending it is idempotent; a different blob conflicts.
    expect((await putBatch(a.token, "fixes", 3, body)).status).toBe(200);
    expect((await putBatch(a.token, "fixes", 3, bytes(40, 8))).status).toBe(409);
  });
});

describe("orphan sweep", () => {
  it("deletes old unreferenced objects of the new scheme only", async () => {
    const a = await newDevice();
    expect((await putBatch(a.token, "fixes", 0, bytes(40, 1))).status).toBe(201);
    const committed = (await env.DB.prepare("SELECT r2_key FROM batches").first<{ r2_key: string }>())!.r2_key;
    const orphan = `${legacyBlobKey(a.deviceId, "fixes", 1)}/${"a".repeat(64)}.${"0".repeat(16)}`;
    const old = legacyBlobKey(a.deviceId, "fixes", 2);
    await env.BLOBS.put(orphan, bytes(40, 2));
    await env.BLOBS.put(old, bytes(40, 3));

    // Too young: kept (its upload may still be committing).
    await maintenance(env, Date.now());
    expect((await env.BLOBS.list({ prefix: "b/" })).objects).toHaveLength(3);

    await maintenance(env, Date.now() + ORPHAN_MIN_AGE_MS + 60_000);
    const keys = (await env.BLOBS.list({ prefix: "b/" })).objects.map((o) => o.key).sort();
    expect(keys).toEqual([committed, old].sort());
  });

  it("walks the bucket in bounded steps and starts over at the end", async () => {
    const ctx = createExecutionContext();
    await worker.scheduled!(createScheduledController(), env, ctx);
    await waitOnExecutionContext(ctx);
    const state = await env.DB.prepare("SELECT orphan_sweep_after FROM server_usage").first();
    expect(state).toEqual({ orphan_sweep_after: null });
  });
});
