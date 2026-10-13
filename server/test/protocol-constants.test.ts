// The numbers the server and the app must agree on are listed once, in
// docs/protocol-constants.json. This pins the server to that file, checks
// the relations between them that the design (and the formal models) rely
// on, and checks the app's cursor and seq bounds, which it validates listing
// rows with (core DeviceStatus.scala, BatchMeta).

import { describe, expect, it } from "vitest";
import constants from "../../docs/protocol-constants.json";
import deviceStatusScala from "../../core/src/main/scala/ashidori/core/sync/DeviceStatus.scala?raw";
import { COMMIT_DEADLINE_MS, MAX_BLOB_BYTES, MIN_BLOB_BYTES, ORPHAN_MIN_AGE_MS } from "../src/batches";
import { PAIRING_TTL_MS } from "../src/devices";
import { DEVICE_TOKEN_RE } from "../src/util";
import { CURSOR_MAX_DIGITS, FEED_MAX_LIMIT, LIST_MAX_LIMIT, SEQ_MAX_DIGITS } from "../src/v1";
import { authed, newDevice } from "./helpers";

/** A `val Name: Long = 999_999L` of the Scala source, as a bigint. */
function scalaLong(name: string): bigint {
  const m = new RegExp(`val ${name}: Long = ([0-9_]+)L`).exec(deviceStatusScala);
  if (!m) throw new Error(`${name} not found in DeviceStatus.scala`);
  return BigInt(m[1]!.replaceAll("_", ""));
}

const largest = (digits: number) => 10n ** BigInt(digits) - 1n;

describe("protocol constants (docs/protocol-constants.json)", () => {
  it("are those of the server", () => {
    expect({
      cursorMaxDigits: CURSOR_MAX_DIGITS,
      seqMaxDigits: SEQ_MAX_DIGITS,
      listMaxLimit: LIST_MAX_LIMIT,
      feedMaxLimit: FEED_MAX_LIMIT,
      minBlobBytes: MIN_BLOB_BYTES,
      maxBlobBytes: MAX_BLOB_BYTES,
      pairingCodeTtlMs: PAIRING_TTL_MS,
      commitDeadlineMs: COMMIT_DEADLINE_MS,
      orphanGraceMs: ORPHAN_MIN_AGE_MS,
    }).toEqual({
      cursorMaxDigits: constants.cursorMaxDigits,
      seqMaxDigits: constants.seqMaxDigits,
      listMaxLimit: constants.listMaxLimit,
      feedMaxLimit: constants.feedMaxLimit,
      minBlobBytes: constants.minBlobBytes,
      maxBlobBytes: constants.maxBlobBytes,
      pairingCodeTtlMs: constants.pairingCodeTtlMs,
      commitDeadlineMs: constants.commitDeadlineMs,
      orphanGraceMs: constants.orphanGraceMs,
    });
    expect(DEVICE_TOKEN_RE.test("a".repeat(constants.deviceTokenLength))).toBe(true);
    expect(DEVICE_TOKEN_RE.test("a".repeat(constants.deviceTokenLength + 1))).toBe(false);
  });

  it("keep the commit deadline at most half the orphan grace period (BatchCommit: D <= G, with room for clock skew)", () => {
    // D + 2 * skew <= G; D <= G / 2 leaves at least D / 2 of skew.
    expect(constants.commitDeadlineMs * 2).toBeLessThanOrEqual(constants.orphanGraceMs);
  });

  it("keep every cursor and seq the server can list within a JavaScript safe integer", () => {
    expect(largest(constants.cursorMaxDigits)).toBeLessThanOrEqual(BigInt(Number.MAX_SAFE_INTEGER));
    expect(largest(constants.seqMaxDigits)).toBeLessThanOrEqual(BigInt(Number.MAX_SAFE_INTEGER));
  });

  it("are the bounds the app validates listing rows with (DeviceStatus.scala)", () => {
    expect(scalaLong("MaxCursor")).toBe(largest(constants.cursorMaxDigits));
    expect(scalaLong("MaxSeq")).toBe(largest(constants.seqMaxDigits));
  });

  it("are the bounds the API takes", async () => {
    const a = await newDevice();
    const max = "9".repeat(constants.cursorMaxDigits);
    for (const path of ["/v1/batches", "/v1/checkins/feed"]) {
      expect((await authed(a.token, `${path}?after=${max}`)).status, path).toBe(200);
      expect((await authed(a.token, `${path}?after=1${max}`)).status, path).toBe(400);
    }
    const seq = "9".repeat(constants.seqMaxDigits);
    expect((await authed(a.token, `/v1/batches/${a.deviceId}/fixes/${seq}`)).status).toBe(404); // valid, absent
    const put = (s: string) =>
      authed(a.token, `/v1/batches/fixes/${s}`, {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream", "X-Content-SHA256": "0".repeat(64) },
        body: new Uint8Array(constants.minBlobBytes),
      });
    // Past the seq check, the (wrong) hash is refused; a seq too long is refused before.
    expect(await (await put(seq)).json()).toEqual({ error: "X-Content-SHA256 does not match body" });
    expect(await (await put(`1${seq}`)).json()).toEqual({ error: "invalid seq" });
  });
});
