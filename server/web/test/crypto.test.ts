import { describe, expect, it } from "vitest";
import { fromHex, sha256Hex } from "../src/data/bytes";
import { BlobError, MAC, MIN_BLOB_AUTH, STATUS_CAPS, aad, blobFormat, gunzip, importBatchKey, openBlob, parseJsonLines } from "../src/data/crypto";
import { blobOf, listing, syncMeta } from "./helpers";

const key = () => importBatchKey(fromHex(syncMeta().batchKeyHex));
const row = (stream: string, seq = 0) => {
  const r = listing().items.find((x) => x.stream === stream && x.seq === seq)!;
  return r;
};

/** The listing row of a changed blob: its own size and SHA-256 (as a server would list it). */
const relisted = async (ref: ReturnType<typeof row>, blob: Uint8Array) => ({ ...ref, size: blob.byteLength, sha256: await sha256Hex(blob) });

describe("ASHM blobs sealed by the app (v1.1)", () => {
  it("the fixtures mix both formats as a real history: ASH1 before the update, ASHM after it", () => {
    const formats = listing().items.map((r) => `${r.stream}/${r.seq}:${blobFormat(blobOf(r.deviceId, r.stream, r.seq))}`);
    expect(formats).toContain("places/0:ASH1");
    expect(formats).toContain("places/1:ASHM");
    expect(formats).toContain("status/0:ASH1");
    expect(formats).toContain("status/1:ASHM");
    expect(formats).toContain("authcut/0:ASHM");
    expect(formats.every((f) => f.endsWith(":ASH1") || f.endsWith(":ASHM"))).toBe(true);
  });

  it("opens every listed batch of either format", async () => {
    const k = await key();
    for (const ref of listing().items) {
      const recs = await openBlob(k, ref, blobOf(ref.deviceId, ref.stream, ref.seq));
      expect(recs.length).toBeGreaterThan(0);
    }
  });

  it("opens the status that names the cut-over record, and the record itself", async () => {
    const ref = row("status", 1);
    const [st] = (await openBlob(await key(), ref, blobOf(ref.deviceId, "status", 1), STATUS_CAPS)) as { authcut: { deviceId: string; seq: number; sha256: string } }[];
    const cut = row("authcut", 0);
    expect(st!.authcut).toEqual({ deviceId: cut.deviceId, seq: 0, sha256: cut.sha256 });
    const entries = (await openBlob(await key(), cut, blobOf(cut.deviceId, "authcut", 0))) as { deviceId: string; stream: string; below: number; digest: string }[];
    expect(entries.find((e) => e.stream === "places")).toMatchObject({ deviceId: cut.deviceId, below: 2 });
    expect(entries.every((e) => /^[0-9a-f]{64}$/.test(e.digest))).toBe(true);
  });

  it("cannot check the mac: a changed mac still opens (viewers do not authenticate the writer until v2)", async () => {
    const ref = row("places", 1);
    const blob = blobOf(ref.deviceId, "places", 1);
    expect(blobFormat(blob)).toBe("ASHM");
    const badMac = blob.slice();
    badMac[badMac.length - 1]! ^= 1;
    await expect(openBlob(await key(), await relisted(ref, badMac), badMac)).resolves.toHaveLength(1);
    // The GCM part is that of ASH1: with the mac stripped and the magic ASH1, it opens the same.
    const stripped = blob.slice(0, blob.length - MAC);
    stripped[3] = 0x31;
    await expect(openBlob(await key(), await relisted(ref, stripped), stripped)).resolves.toHaveLength(1);
  });

  it("still fails on a changed GCM byte, a moved blob, or a truncated mac", async () => {
    const ref = row("places", 1);
    const blob = blobOf(ref.deviceId, "places", 1);
    const badGcm = blob.slice();
    badGcm[20]! ^= 1;
    await expect(openBlob(await key(), await relisted(ref, badGcm), badGcm)).rejects.toMatchObject({ kind: "decrypt" });
    await expect(openBlob(await key(), { ...ref, seq: 0 }, blob)).rejects.toMatchObject({ kind: "decrypt" });
    // Cut into the mac: the GCM tag then sits where the mac is assumed to start.
    const cut = blob.slice(0, blob.length - 1);
    await expect(openBlob(await key(), await relisted(ref, cut), cut)).rejects.toMatchObject({ kind: "decrypt" });
    const tooShort = blob.slice(0, MIN_BLOB_AUTH - 1);
    await expect(openBlob(await key(), await relisted(ref, tooShort), tooShort)).rejects.toMatchObject({ kind: "format" });
  });
});

describe("ASH1 blobs sealed by the app", () => {
  it("opens a status batch: listing checks, AES-GCM with the slot's AAD, gunzip, JSON Lines", async () => {
    const ref = row("status", 0);
    const blob = blobOf(ref.deviceId, "status", 0);
    expect(blobFormat(blob)).toBe("ASH1");
    const recs = await openBlob(await key(), ref, blob, STATUS_CAPS);
    expect(recs).toHaveLength(1);
    expect(recs[0]).toMatchObject({ battery: { pct: 81 }, recording: { on: true, mode: "balanced" }, device: { model: "Google Pixel 8a" } });
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
