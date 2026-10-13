import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { authed, blob, newDevice, putBatch, sha256Hex } from "./helpers";

interface ListItem {
  cursor: number;
  deviceId: string;
  stream: string;
  seq: number;
  size: number;
  sha256: string;
  createdAt: number;
}

describe("PUT /v1/batches/:stream/:seq", () => {
  it("creates, then is idempotent, then conflicts", async () => {
    const a = await newDevice();
    const body = blob("ASH1 ciphertext one");
    const first = await putBatch(a.token, "fixes", 0, body);
    expect(first.status).toBe(201);
    expect(await first.json()).toEqual({ status: "created", cursor: expect.any(Number) });

    // Stored under an immutable key with the hash, referenced by the metadata.
    const objects = async () => (await env.BLOBS.list({ prefix: `b/${a.deviceId}/fixes/000000000000/` })).objects;
    const [obj] = await objects();
    expect(obj!.key).toMatch(new RegExp(`^b/${a.deviceId}/fixes/000000000000/${await sha256Hex(body)}\\.[0-9a-f]{16}$`));
    const row = await env.DB.prepare("SELECT r2_key FROM batches").first<{ r2_key: string }>();
    expect(row!.r2_key).toBe(obj!.key);
    expect(new Uint8Array(await (await env.BLOBS.get(obj!.key))!.arrayBuffer())).toEqual(body);

    const again = await putBatch(a.token, "fixes", 0, body);
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ status: "exists" });

    const conflict = await putBatch(a.token, "fixes", 0, blob("different"));
    expect(conflict.status).toBe(409);
    // The stored blob is untouched, and nothing else was stored.
    expect((await objects()).map((o) => o.key)).toEqual([obj!.key]);
    const got = await authed(a.token, `/v1/batches/${a.deviceId}/fixes/0`);
    expect(new Uint8Array(await got.arrayBuffer())).toEqual(body);
  });

  it("rejects blobs shorter than magic, nonce and tag", async () => {
    const a = await newDevice();
    for (const n of [0, 1, 31]) {
      const res = await putBatch(a.token, "fixes", n, new Uint8Array(n));
      expect(res.status, String(n)).toBe(400);
      expect(await res.json()).toEqual({ error: "blob too small" });
    }
    expect((await putBatch(a.token, "fixes", 32, new Uint8Array(32))).status).toBe(201);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM batches").first<{ n: number }>())!.n).toBe(1);
  });

  it("accepts an upper-case hash header", async () => {
    const a = await newDevice();
    const body = blob("x");
    const res = await putBatch(a.token, "fixes", 1, body, (await sha256Hex(body)).toUpperCase());
    expect(res.status).toBe(201);
  });

  it("rejects a hash mismatch and a missing hash", async () => {
    const a = await newDevice();
    const res = await putBatch(a.token, "fixes", 0, blob("abc"), "0".repeat(64));
    expect(res.status).toBe(400);
    const missing = await authed(a.token, "/v1/batches/fixes/0", {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: blob("abc"),
    });
    expect(missing.status).toBe(400);
    expect((await env.BLOBS.list({ prefix: `b/${a.deviceId}/` })).objects).toEqual([]);
  });

  it("enforces the 8 MiB limit", async () => {
    const a = await newDevice();
    const max = new Uint8Array(8 * 1024 * 1024);
    expect((await putBatch(a.token, "gnss", 0, max)).status).toBe(201);
    const over = new Uint8Array(8 * 1024 * 1024 + 1);
    expect((await putBatch(a.token, "gnss", 1, over)).status).toBe(413);
  });

  it("validates stream, seq and content type", async () => {
    const a = await newDevice();
    const body = blob("x");
    expect((await putBatch(a.token, "Fixes", 0, body)).status).toBe(400);
    expect((await putBatch(a.token, "a".repeat(33), 0, body)).status).toBe(400);
    expect((await putBatch(a.token, "fixes", -1, body)).status).toBe(400);
    expect((await putBatch(a.token, "fixes", "01", body)).status).toBe(400);
    expect((await putBatch(a.token, "fixes", "1.5", body)).status).toBe(400);
    expect((await putBatch(a.token, "fixes", "1000000000000", body)).status).toBe(400);
    expect((await putBatch(a.token, "fixes", "999999999999", body)).status).toBe(201);
    const wrongType = await authed(a.token, "/v1/batches/fixes/5", {
      method: "PUT",
      headers: { "Content-Type": "text/plain", "X-Content-SHA256": await sha256Hex(body) },
      body,
    });
    expect(wrongType.status).toBe(415);
  });

  it("takes the device id from the token, so devices have separate slots", async () => {
    const a = await newDevice("a");
    const b = await newDevice("b");
    expect((await putBatch(a.token, "fixes", 0, blob("from a"))).status).toBe(201);
    expect((await putBatch(b.token, "fixes", 0, blob("from b"))).status).toBe(201);
  });

  it("requires auth", async () => {
    const res = await putBatch("A".repeat(43), "fixes", 0, blob("x"));
    expect(res.status).toBe(401);
  });
});

describe("GET /v1/batches (listing)", () => {
  it("lists all devices' batches by a global cursor with pagination", async () => {
    const a = await newDevice("a");
    const b = await newDevice("b");
    const uploads: Array<[string, string, number]> = [
      [a.token, "fixes", 0],
      [b.token, "fixes", 0],
      [a.token, "motion", 0],
      [b.token, "checkins", 0],
      [a.token, "fixes", 1],
    ];
    for (const [tok, stream, seq] of uploads) {
      expect((await putBatch(tok, stream, seq, blob(`${tok}/${stream}/${seq}`))).status).toBe(201);
    }

    const seen: ListItem[] = [];
    let after = 0;
    let pages = 0;
    for (;;) {
      const res = await authed(b.token, `/v1/batches?after=${after}&limit=2`);
      expect(res.status).toBe(200);
      const page = (await res.json()) as { items: ListItem[]; next: number | null };
      seen.push(...page.items);
      pages++;
      if (page.next === null) break;
      after = page.next;
    }
    expect(pages).toBe(3);
    expect(seen.map((i) => [i.deviceId, i.stream, i.seq])).toEqual([
      [a.deviceId, "fixes", 0],
      [b.deviceId, "fixes", 0],
      [a.deviceId, "motion", 0],
      [b.deviceId, "checkins", 0],
      [a.deviceId, "fixes", 1],
    ]);
    const cursors = seen.map((i) => i.cursor);
    expect([...cursors].sort((x, y) => x - y)).toEqual(cursors);
    expect(new Set(cursors).size).toBe(cursors.length);
    expect(seen[0]).toEqual({
      cursor: expect.any(Number),
      deviceId: a.deviceId,
      stream: "fixes",
      seq: 0,
      size: blob(`${a.token}/fixes/0`).byteLength,
      sha256: await sha256Hex(blob(`${a.token}/fixes/0`)),
      createdAt: expect.any(Number),
    });
    // The upload time is kept at hour granularity.
    expect(seen.every((i) => i.createdAt % (60 * 60 * 1000) === 0)).toBe(true);

    // Resuming from the last cursor yields nothing; a new upload appears after it.
    const last = cursors[cursors.length - 1]!;
    const empty = (await (await authed(a.token, `/v1/batches?after=${last}`)).json()) as {
      items: ListItem[];
      next: null;
    };
    expect(empty).toEqual({ items: [], next: null });
    await putBatch(b.token, "fixes", 1, blob("late"));
    const tail = (await (await authed(a.token, `/v1/batches?after=${last}`)).json()) as { items: ListItem[] };
    expect(tail.items.map((i) => [i.deviceId, i.seq])).toEqual([[b.deviceId, 1]]);
  });

  it("does not list idempotent re-uploads twice", async () => {
    const a = await newDevice();
    await putBatch(a.token, "fixes", 0, blob("x"));
    await putBatch(a.token, "fixes", 0, blob("x"));
    const list = (await (await authed(a.token, "/v1/batches")).json()) as { items: ListItem[] };
    expect(list.items).toHaveLength(1);
  });

  it("validates query parameters", async () => {
    const a = await newDevice();
    expect((await authed(a.token, "/v1/batches?after=-1")).status).toBe(400);
    expect((await authed(a.token, "/v1/batches?limit=abc")).status).toBe(400);
    expect((await authed(a.token, "/v1/batches?limit=0")).status).toBe(200);
  });
});

describe("GET /v1/batches/:deviceId/:stream/:seq", () => {
  it("returns another device's blob", async () => {
    const a = await newDevice("a");
    const b = await newDevice("b");
    const body = blob("ASH1 opaque bytes");
    await putBatch(a.token, "places", 3, body);
    const res = await authed(b.token, `/v1/batches/${a.deviceId}/places/3`);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/octet-stream");
    expect(res.headers.get("X-Content-SHA256")).toBe(await sha256Hex(body));
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(body);
  });

  it("404s for missing batches and requires auth", async () => {
    const a = await newDevice();
    expect((await authed(a.token, `/v1/batches/${a.deviceId}/fixes/0`)).status).toBe(404);
    expect((await authed(a.token, `/v1/batches/${a.deviceId}/BAD/0`)).status).toBe(404);
    await putBatch(a.token, "fixes", 0, blob("x"));
    expect((await authed("A".repeat(43), `/v1/batches/${a.deviceId}/fixes/0`)).status).toBe(401);
  });

  it("revoked devices can no longer read", async () => {
    const a = await newDevice("a");
    const b = await newDevice("b");
    await putBatch(a.token, "fixes", 0, blob("x"));
    await authed(a.token, `/v1/devices/${b.deviceId}/revoke`, { method: "POST" });
    expect((await authed(b.token, `/v1/batches/${a.deviceId}/fixes/0`)).status).toBe(401);
    expect((await authed(b.token, "/v1/batches")).status).toBe(401);
    expect((await putBatch(b.token, "fixes", 0, blob("y"))).status).toBe(401);
  });
});
