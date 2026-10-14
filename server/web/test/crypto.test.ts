import { describe, expect, it } from "vitest";
import { fromHex } from "../src/data/bytes";
import { BlobError, STATUS_CAPS, aad, gunzip, importBatchKey, openBlob, parseJsonLines } from "../src/data/crypto";
import { blobOf, listing, syncMeta } from "./helpers";

const key = () => importBatchKey(fromHex(syncMeta().batchKeyHex));
const row = (stream: string, seq = 0) => {
  const r = listing().items.find((x) => x.stream === stream && x.seq === seq)!;
  return r;
};

describe("ASH1 blobs sealed by the app", () => {
  it("opens a status batch: listing checks, AES-GCM with the slot's AAD, gunzip, JSON Lines", async () => {
    const ref = row("status", 1);
    const recs = await openBlob(await key(), ref, blobOf(ref.deviceId, "status", 1), STATUS_CAPS);
    expect(recs).toHaveLength(1);
    expect(recs[0]).toMatchObject({ battery: { pct: 78 }, recording: { on: true, mode: "balanced" }, device: { model: "Google Pixel 8a" } });
  });

  it("keeps the key non-extractable", async () => {
    const k = await key();
    expect(k.extractable).toBe(false);
    expect(k.usages).toEqual(["decrypt"]);
    await expect(crypto.subtle.exportKey("raw", k)).rejects.toThrow();
  });

  it("checks the size and SHA-256 of the listing before decrypting", async () => {
    const ref = row("places");
    const blob = blobOf(ref.deviceId, "places", 0);
    await expect(openBlob(await key(), { ...ref, size: ref.size + 1 }, blob)).rejects.toMatchObject({ kind: "size" });
    const flipped = blob.slice();
    flipped[40]! ^= 1;
    await expect(openBlob(await key(), ref, flipped)).rejects.toMatchObject({ kind: "hash" });
  });

  it("fails to decrypt a blob moved to another slot (AAD)", async () => {
    const ref = row("places");
    const blob = blobOf(ref.deviceId, "places", 0);
    // The listing row of the other slot, with this blob's size and hash.
    for (const moved of [{ ...ref, seq: 1 }, { ...ref, stream: "checkins" }, { ...ref, deviceId: "another-device" }]) {
      await expect(openBlob(await key(), moved, blob)).rejects.toMatchObject({ kind: "decrypt" });
    }
  });

  it("fails with a wrong key", async () => {
    const ref = row("places");
    const other = await importBatchKey(new Uint8Array(32).fill(9));
    await expect(openBlob(other, ref, blobOf(ref.deviceId, "places", 0))).rejects.toBeInstanceOf(BlobError);
  });

  it("refuses what is not an ASH1 blob", async () => {
    const data = new Uint8Array(40);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
    const sha = [...digest].map((b) => b.toString(16).padStart(2, "0")).join("");
    await expect(openBlob(await key(), { deviceId: "d", stream: "places", seq: 0, size: 40, sha256: sha }, data)).rejects.toMatchObject({ kind: "format" });
  });

  it("caps the decompressed size and the line count", async () => {
    const big = new Uint8Array(200_000).fill(0x20);
    const gz = new Uint8Array(await new Response(new Blob([big]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
    await expect(gunzip(gz, 100_000)).rejects.toMatchObject({ kind: "limit" });
    expect((await gunzip(gz, 200_000)).byteLength).toBe(200_000);
    const lines = new TextEncoder().encode("{}\n".repeat(65));
    expect(() => parseJsonLines(lines, 64)).toThrow(/64 lines/);
    expect(parseJsonLines(new TextEncoder().encode('{"a":1}\n\n {"b":2} \n'), 64)).toEqual([{ a: 1 }, { b: 2 }]);
  });

  it("builds the AAD as the app does", () => {
    expect(new TextDecoder().decode(aad("dev", "archive", 12))).toBe("dev/archive/12");
  });
});
