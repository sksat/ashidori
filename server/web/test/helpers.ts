// Fixtures written by core's WebFixtures (./gradlew :core:webFixtures) with
// the app's own Parquet writer, crypto and timeline code.

import { readFileSync } from "node:fs";
import { join } from "node:path";

export const FIXTURES = join(import.meta.dirname, "fixtures");

export function fixture(name: string): Uint8Array {
  return new Uint8Array(readFileSync(join(FIXTURES, name)));
}

export function fixtureJson<T>(name: string): T {
  return JSON.parse(readFileSync(join(FIXTURES, name), "utf8")) as T;
}

export interface Listing {
  items: { cursor: number; deviceId: string; stream: string; seq: number; size: number; sha256: string; createdAt: number }[];
  next: null;
}

export interface SyncMeta {
  batchKeyHex: string;
  devices: { id: string; name: string; createdAt: number; lastSeenAt: number; revoked: boolean }[];
}

export const listing = () => fixtureJson<Listing>("sync/listing.json");
export const syncMeta = () => fixtureJson<SyncMeta>("sync/meta.json");
export const blobOf = (deviceId: string, stream: string, seq: number) => fixture(`sync/blobs/${deviceId}.${stream}.${seq}`);

export const TOKEN = "A".repeat(43);

/**
 * A fake of the server's read API over the fixture batches: the listing
 * paged by `after`/`limit` as the server does, and the blobs.
 */
export function fakeServer(opts: { tamper?: (path: string, body: Uint8Array) => Uint8Array; status?: (path: string) => number | null } = {}) {
  const calls: string[] = [];
  const f = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), "https://example.net");
    calls.push(url.pathname + url.search);
    const auth = new Headers(init?.headers).get("Authorization");
    if (auth !== `Bearer ${TOKEN}`) return Response.json({ error: "unauthorized" }, { status: 401 });
    const forced = opts.status?.(url.pathname);
    if (forced) return Response.json({ error: "forced" }, { status: forced });
    if (url.pathname === "/v1/devices") return Response.json(syncMeta().devices);
    if (url.pathname === "/v1/batches") {
      const after = Number(url.searchParams.get("after") ?? "0");
      const limit = Number(url.searchParams.get("limit") ?? "1000");
      const rows = listing().items.filter((r) => r.cursor > after);
      const page = rows.slice(0, limit);
      return Response.json({ items: page, next: rows.length > limit ? page[page.length - 1]!.cursor : null });
    }
    const m = /^\/v1\/batches\/([^/]+)\/([^/]+)\/(\d+)$/.exec(url.pathname);
    if (m) {
      let body: Uint8Array;
      try {
        body = blobOf(decodeURIComponent(m[1]!), m[2]!, Number(m[3]));
      } catch {
        return Response.json({ error: "not found" }, { status: 404 });
      }
      if (opts.tamper) body = opts.tamper(url.pathname, body);
      return new Response(new Uint8Array(body), { headers: { "Content-Type": "application/octet-stream" } });
    }
    return Response.json({ error: "not found" }, { status: 404 });
  };
  return { fetch: f as typeof fetch, calls };
}
