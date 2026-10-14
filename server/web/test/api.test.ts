import { describe, expect, it } from "vitest";
import { Api, ApiError, ListingError, MAX_STATUS_BLOB, checkPage, parseBatchRow } from "../src/data/api";
import { LimitExceeded } from "../src/data/bytes";
import { TOKEN, fakeServer, listing } from "./helpers";

const row = { cursor: 5, deviceId: "dev", stream: "archive", seq: 0, size: 100, sha256: "a".repeat(64), createdAt: 0 };

describe("listing rows", () => {
  it("accepts rows within the API's ranges", () => {
    expect(parseBatchRow({ ...row, sha256: "A".repeat(64) }).sha256).toBe("a".repeat(64));
  });

  it("refuses malformed rows (they fail the page)", () => {
    for (const bad of [
      { ...row, cursor: 1e15 },
      { ...row, cursor: -1 },
      { ...row, seq: 1e12 },
      { ...row, seq: 1.5 },
      { ...row, size: -1 },
      { ...row, sha256: "xyz" },
      { ...row, stream: "Archive" },
      { ...row, deviceId: "../x" },
      { ...row, size: undefined },
    ])
      expect(() => parseBatchRow(bad)).toThrow(ListingError);
  });

  it("requires cursors above `after`, increasing, at most `limit` rows, and a `next` that moves on", () => {
    expect(checkPage({ items: [row, { ...row, cursor: 9 }], next: 9 }, 0, 2).next).toBe(9);
    expect(() => checkPage({ items: [row], next: null }, 5, 10)).toThrow(/increase/);
    expect(() => checkPage({ items: [{ ...row, cursor: 9 }, row], next: null }, 0, 10)).toThrow(/increase/);
    expect(() => checkPage({ items: [row, { ...row, cursor: 6 }], next: null }, 0, 1)).toThrow(/rows/);
    expect(() => checkPage({ items: [], next: 3 }, 3, 10)).toThrow(/move on/);
  });
});

describe("Api against the server's read endpoints", () => {
  it("pages through the listing with `after`", async () => {
    const s = fakeServer();
    const api = new Api({ token: TOKEN, fetch: s.fetch });
    const pages: number[] = [];
    // Small pages, to cross page boundaries.
    let after = 0;
    for (;;) {
      const p = await api.batches(after, 4);
      pages.push(p.items.length);
      if (p.next === null) break;
      after = p.next;
    }
    expect(pages.reduce((a, b) => a + b, 0)).toBe(listing().items.length);
    expect(pages.length).toBeGreaterThan(2);
    const all = [];
    for await (const page of api.allBatches()) all.push(...page);
    expect(all.map((r) => r.cursor)).toEqual(listing().items.map((r) => r.cursor));
  });

  it("sends the device token and turns error bodies into ApiError", async () => {
    const bad = new Api({ token: "B".repeat(43), fetch: fakeServer().fetch });
    const e = await bad.devices().catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).unauthorized).toBe(true);
    expect((e as ApiError).code).toBe("unauthorized");
  });

  it("caps blobs: status at 64 KiB, others at 8 MiB, also when the listing understates the size", async () => {
    const api = new Api({ token: TOKEN, fetch: fakeServer().fetch });
    const st = listing().items.find((r) => r.stream === "status")!;
    await expect(api.blob({ ...st, size: MAX_STATUS_BLOB + 1 })).rejects.toBeInstanceOf(LimitExceeded);
    const huge = new Api({
      token: TOKEN,
      fetch: (async () => new Response(new Uint8Array(MAX_STATUS_BLOB + 10))) as unknown as typeof fetch,
    });
    await expect(huge.blob(st)).rejects.toBeInstanceOf(LimitExceeded);
    expect((await api.blob(st)).byteLength).toBe(st.size);
  });

  it("caps JSON responses at 1 MiB", async () => {
    const big = new Api({
      token: TOKEN,
      fetch: (async () => new Response("[" + '"x",'.repeat(300_000) + '"x"]')) as unknown as typeof fetch,
    });
    await expect(big.devices()).rejects.toBeInstanceOf(LimitExceeded);
  });
});
