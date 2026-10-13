// Encrypted batches: metadata in D1, blobs in R2.
//
// Storing a batch (see putBatch):
//   1. The blob goes to R2 under a key no other request uses:
//      b/{deviceId}/{stream}/{seq 12 digits}/{sha256}.{random}
//      (R2 has no create-only put, so concurrent uploads to one slot must
//      not share a key).
//   2. One D1 statement commits the metadata with that key, if the slot is
//      still free and the counters (kept by triggers) leave room for it. The
//      slot's UNIQUE constraint decides the winner, and the cursor
//      (AUTOINCREMENT) is assigned at this commit, so a listed batch always
//      has a durable blob and listings never skip a cursor.
//   3. A request that did not commit deletes its own object (best effort).
//      Objects left behind by a crash between 1 and 2 are found by the
//      scheduled sweep (sweepOrphanBlobs).
// GET serves the key the metadata references. Batches stored before
// migration 0005 have no key there and live at the old key
// b/{deviceId}/{stream}/{seq 12 digits}; they were not moved.

import type { Limits } from "./config";
import { readUsage } from "./accounts";
import { toHex, randomBytes } from "./util";

export const MAX_BLOB_BYTES = 8 * 1024 * 1024;
/** "ASH1" ‖ 12-byte nonce ‖ 16-byte GCM tag: no valid blob is shorter. */
export const MIN_BLOB_BYTES = 4 + 12 + 16;

function slotPrefix(deviceId: string, stream: string, seq: number): string {
  return `b/${deviceId}/${stream}/${String(seq).padStart(12, "0")}`;
}

/** Key of a batch stored before migration 0005 (batches.r2_key NULL). */
export function legacyBlobKey(deviceId: string, stream: string, seq: number): string {
  return slotPrefix(deviceId, stream, seq);
}

/** A fresh, never reused key for one upload attempt of a slot. */
export function newBlobKey(deviceId: string, stream: string, seq: number, sha256: string): string {
  return `${slotPrefix(deviceId, stream, seq)}/${sha256}.${toHex(randomBytes(8))}`;
}

export type PutResult =
  | { status: "created"; cursor: number }
  | { status: "exists" }
  | "conflict"
  | "quota exceeded"
  | "server full"
  | "device gone";

function checkUsage(
  u: { storageBytes: number; batchCount: number; totalBytes: number },
  size: number,
  limits: Limits,
): "quota exceeded" | "server full" | null {
  if (u.storageBytes + size > limits.userQuotaBytes || u.batchCount + 1 > limits.userMaxBatches) return "quota exceeded";
  if (u.totalBytes + size > limits.maxTotalBytes) return "server full";
  return null;
}

export interface PutInput {
  deviceId: string;
  /** null for legacy devices (one shared quota). */
  userId: string | null;
  stream: string;
  seq: number;
  sha256: string;
  body: Uint8Array;
  createdAt: number;
}

export async function putBatch(
  db: D1Database,
  blobs: R2Bucket,
  p: PutInput,
  limits: Limits,
  background: (work: Promise<unknown>) => void,
): Promise<PutResult> {
  const slot = db
    .prepare("SELECT sha256 FROM batches WHERE device_id = ?1 AND stream = ?2 AND seq = ?3")
    .bind(p.deviceId, p.stream, p.seq);
  const fromSlot = (row: { sha256: string } | null): PutResult | null =>
    row === null ? null : row.sha256 === p.sha256 ? { status: "exists" } : "conflict";

  // Idempotency first, so that re-sending a stored batch succeeds even at the limit.
  const existing = fromSlot(await slot.first<{ sha256: string }>());
  if (existing) return existing;

  // Cheap early refusal before writing to R2; the commit below re-checks atomically.
  const size = p.body.byteLength;
  const usage = await readUsage(db, p.userId, p.deviceId);
  if (!usage.deviceActive) return "device gone";
  const early = checkUsage(usage, size, limits);
  if (early) return early;

  const key = newBlobKey(p.deviceId, p.stream, p.seq, p.sha256);
  await blobs.put(key, p.body, { sha256: p.sha256, httpMetadata: { contentType: "application/octet-stream" } });

  const userBytes =
    "CASE WHEN ?7 IS NULL THEN (SELECT legacy_storage_bytes FROM server_usage WHERE id = 1) ELSE (SELECT storage_bytes FROM users WHERE id = ?7) END";
  const userBatches =
    "CASE WHEN ?7 IS NULL THEN (SELECT legacy_batch_count FROM server_usage WHERE id = 1) ELSE (SELECT batch_count FROM users WHERE id = ?7) END";
  // One statement: slot uniqueness, the quota and the counters (triggers) are
  // decided together. The upload time is for display only (ordering is by
  // cursor), so it is kept at hour granularity.
  const inserted = await db
    .prepare(
      `INSERT INTO batches (device_id, stream, seq, size, sha256, created_at, r2_key)
       SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?8
       WHERE EXISTS (SELECT 1 FROM devices WHERE id = ?1 AND revoked_at IS NULL AND user_id IS ?7)
         AND ${userBytes} + ?4 <= ?9
         AND ${userBatches} + 1 <= ?10
         AND (SELECT total_bytes FROM server_usage WHERE id = 1) + ?4 <= ?11
       ON CONFLICT (device_id, stream, seq) DO NOTHING
       RETURNING cursor`,
    )
    .bind(
      p.deviceId,
      p.stream,
      p.seq,
      size,
      p.sha256,
      p.createdAt,
      p.userId,
      key,
      limits.userQuotaBytes,
      limits.userMaxBatches,
      limits.maxTotalBytes,
    )
    .first<{ cursor: number }>();
  if (inserted) return { status: "created", cursor: inserted.cursor };

  // Not committed: nothing references this request's object.
  background(blobs.delete(key).catch((e) => console.warn("could not delete an uncommitted blob", e)));
  const raced = fromSlot(await slot.first<{ sha256: string }>());
  if (raced) return raced;
  const now = await readUsage(db, p.userId, p.deviceId);
  if (!now.deviceActive) return "device gone";
  return checkUsage(now, size, limits) ?? "quota exceeded";
}

/** The R2 key holding a committed batch, or null if the caller's user has no such batch. */
export async function committedBlob(
  db: D1Database,
  userId: string | null,
  deviceId: string,
  stream: string,
  seq: number,
): Promise<{ key: string; sha256: string } | null> {
  // Another user's batch is indistinguishable from a missing one.
  const meta = await db
    .prepare(
      `SELECT b.sha256, b.r2_key FROM batches b JOIN devices d ON d.id = b.device_id
       WHERE b.device_id = ?1 AND b.stream = ?2 AND b.seq = ?3 AND d.user_id IS ?4`,
    )
    .bind(deviceId, stream, seq, userId)
    .first<{ sha256: string; r2_key: string | null }>();
  if (!meta) return null;
  return { key: meta.r2_key ?? legacyBlobKey(deviceId, stream, seq), sha256: meta.sha256 };
}

/** Objects younger than this are never swept: their upload may still be committing. */
export const ORPHAN_MIN_AGE_MS = 24 * 60 * 60 * 1000;
const NEW_KEY_RE = /^b\/[^/]+\/[a-z][a-z0-9_]{0,31}\/[0-9]{12}\/[0-9a-f]{64}\.[0-9a-f]{16}$/;

/**
 * Scheduled: walks R2 from where the previous run stopped, `limit` keys per
 * run, and deletes objects under the new key scheme that no batch references
 * and that are older than ORPHAN_MIN_AGE_MS (left behind by a request that
 * stopped between the R2 put and the D1 commit). Old-scheme keys are left
 * alone. Returns how many were deleted.
 */
export async function sweepOrphanBlobs(db: D1Database, blobs: R2Bucket, now: number, limit = 500): Promise<number> {
  const state = await db
    .prepare("SELECT orphan_sweep_after FROM server_usage WHERE id = 1")
    .first<{ orphan_sweep_after: string | null }>();
  const listed = await blobs.list({
    prefix: "b/",
    limit,
    ...(state?.orphan_sweep_after ? { startAfter: state.orphan_sweep_after } : {}),
  });
  const candidates = listed.objects.filter(
    (o) => NEW_KEY_RE.test(o.key) && o.uploaded.getTime() < now - ORPHAN_MIN_AGE_MS,
  );
  const orphans: string[] = [];
  // D1 binds at most 100 parameters per statement.
  for (let i = 0; i < candidates.length; i += 100) {
    const chunk = candidates.slice(i, i + 100).map((o) => o.key);
    const { results } = await db
      .prepare(`SELECT r2_key FROM batches WHERE r2_key IN (${chunk.map((_, j) => `?${j + 1}`).join(", ")})`)
      .bind(...chunk)
      .all<{ r2_key: string }>();
    const referenced = new Set(results.map((r) => r.r2_key));
    orphans.push(...chunk.filter((k) => !referenced.has(k)));
  }
  if (orphans.length > 0) await blobs.delete(orphans);
  const last = listed.objects[listed.objects.length - 1]?.key ?? null;
  await db
    .prepare("UPDATE server_usage SET orphan_sweep_after = ?1 WHERE id = 1")
    .bind(listed.truncated ? last : null)
    .run();
  return orphans.length;
}
