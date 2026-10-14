import { strToU8, zipSync } from "fflate";
import { beforeEach, describe, expect, it } from "vitest";
import { hex } from "../src/data/bytes";
import {
  archiveUrl,
  BasemapError,
  basemapOffered,
  cachedBasemap,
  CACHE_NAME,
  downloadBasemap,
  LocalZip,
  loadedBasemap,
  PIN,
  remoteUrls,
  resetBasemap,
  type BasemapDeps,
  type BasemapPin,
} from "../src/map/basemap";

/** A zip laid out as tools/basemap/package.py writes it: .pmtiles stored, the rest deflated. */
function archive(): { zip: Uint8Array; pmtiles: Uint8Array } {
  const pmtiles = Uint8Array.from({ length: 5000 }, (_, i) => (i * 7) & 0xff);
  const style = JSON.stringify({
    version: 8,
    glyphs: "file://@BASEMAP_DIR@/fonts/{fontstack}/{range}.pbf",
    sources: { japan: { type: "vector", url: "pmtiles://file://@BASEMAP_DIR@/japan.pmtiles" } },
    layers: [],
  });
  const zip = zipSync({
    "style.json": [strToU8(style), { level: 6 }],
    "japan.pmtiles": [pmtiles, { level: 0 }],
    "fonts/Noto Sans Regular/0-255.pbf": [new Uint8Array(300).fill(3), { level: 6 }],
  });
  return { zip, pmtiles };
}

async function pinOf(zip: Uint8Array): Promise<BasemapPin> {
  const sha256 = hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new Uint8Array(zip))));
  return { version: "20990101", file: "ashidori-basemap-20990101.zip", bytes: zip.byteLength, sha256 };
}

/** Cache Storage in memory. */
function fakeCaches(): CacheStorage & { store: Map<string, Blob> } {
  const store = new Map<string, Blob>();
  const cache = {
    async match(url: string) {
      const b = store.get(url);
      return b ? new Response(b) : undefined;
    },
    async put(url: string, res: Response) {
      store.set(url, await res.blob());
    },
    async delete(url: string) {
      return store.delete(url);
    },
  };
  return {
    store,
    open: async (name: string) => {
      expect(name).toBe(CACHE_NAME);
      return cache as unknown as Cache;
    },
  } as unknown as CacheStorage & { store: Map<string, Blob> };
}

interface Call {
  url: string;
  range: string | null;
}

/** Serves the archive whole (200), recording every request. */
function server(body: Uint8Array, calls: Call[], length: number = body.byteLength): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, range: new Headers(init?.headers).get("Range") });
    if (url === "/v1/info") return Response.json({ registration: "admin", basemap: ["20990101"] });
    return new Response(new Uint8Array(body), { status: 200, headers: { "Content-Length": String(length) } });
  }) as typeof fetch;
}

beforeEach(() => resetBasemap());

describe("basemap archive read locally", () => {
  it("reads the central directory, a deflated entry, and a stored entry in place", async () => {
    const { zip, pmtiles } = archive();
    const z = await LocalZip.open(new Blob([new Uint8Array(zip)]));
    expect([...z.entries.keys()].sort()).toEqual(["fonts/Noto Sans Regular/0-255.pbf", "japan.pmtiles", "style.json"]);
    expect(await z.read("fonts/Noto Sans Regular/0-255.pbf")).toEqual(new Uint8Array(300).fill(3));
    const e = z.entries.get("japan.pmtiles")!;
    expect(e.method).toBe(0);
    const off = await z.dataOffset(e);
    expect(zip.slice(off, off + e.size)).toEqual(pmtiles);
  });

  it("pins the app's archive", () => {
    expect(PIN.file).toBe(`ashidori-basemap-${PIN.version}.zip`);
    expect(PIN.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(PIN.bytes).toBeGreaterThan(1_000_000);
  });

  it("downloads the whole archive in one request without Range, verifies it, keeps it, and reads it locally", async () => {
    const { zip } = archive();
    const pin = await pinOf(zip);
    const calls: Call[] = [];
    const caches = fakeCaches();
    const deps: BasemapDeps = { fetch: server(zip, calls), caches, pin };
    const seen: number[] = [];
    const b = await downloadBasemap((n) => seen.push(n), deps);
    expect(calls).toEqual([{ url: archiveUrl(pin), range: null }]);
    expect(seen.at(-1)).toBe(zip.byteLength);
    expect(loadedBasemap()).toBe(b);
    expect(caches.store.get(archiveUrl(pin))?.size).toBe(zip.byteLength);
    // Glyphs (and tiles) come from the archive in memory: no further request.
    const actions = new Map<string, (p: { url: string; type?: string }, c: AbortController) => Promise<{ data: unknown }>>();
    b.install((name, action) => actions.set(name, action as never));
    expect([...actions.keys()].sort()).toEqual(["ashzip", "pmtiles"]);
    const glyph = await actions.get("ashzip")!({ url: "ashzip://archive/fonts/Noto%20Sans%20Regular/0-255.pbf", type: "arrayBuffer" }, new AbortController());
    expect(new Uint8Array(glyph.data as ArrayBuffer)).toEqual(new Uint8Array(300).fill(3));
    expect(calls).toHaveLength(1);
    // A second load in the page is the same basemap, without a request.
    expect(await downloadBasemap(undefined, deps)).toBe(b);
    expect(calls).toHaveLength(1);
  });

  it("uses a verified archive from Cache Storage without any request", async () => {
    const { zip } = archive();
    const pin = await pinOf(zip);
    const caches = fakeCaches();
    caches.store.set(archiveUrl(pin), new Blob([new Uint8Array(zip)]));
    const calls: Call[] = [];
    const b = await cachedBasemap({ fetch: server(zip, calls), caches, pin });
    expect(b).not.toBeNull();
    expect(calls).toEqual([]);
  });

  it("drops a cached archive that does not match the pin", async () => {
    const { zip } = archive();
    const pin = await pinOf(zip);
    const caches = fakeCaches();
    const bad = new Uint8Array(zip);
    bad[100]! ^= 1;
    caches.store.set(archiveUrl(pin), new Blob([bad]));
    const calls: Call[] = [];
    expect(await cachedBasemap({ fetch: server(zip, calls), caches, pin })).toBeNull();
    expect(caches.store.size).toBe(0);
    expect(calls).toEqual([]);
  });

  it("finds nothing without a cached copy, and sends nothing", async () => {
    const calls: Call[] = [];
    const { zip } = archive();
    expect(await cachedBasemap({ fetch: server(zip, calls), caches: fakeCaches(), pin: await pinOf(zip) })).toBeNull();
    expect(calls).toEqual([]);
  });

  it("refuses a download that does not match the pin, and keeps nothing", async () => {
    const { zip } = archive();
    const pin = await pinOf(zip);
    const bad = new Uint8Array(zip);
    bad[200]! ^= 1;
    for (const [body, len, kind] of [
      [bad, bad.byteLength, "hash"],
      [zip, zip.byteLength + 1, "size"],
      [new Uint8Array([...zip, 0]), zip.byteLength, "size"],
    ] as const) {
      resetBasemap();
      const caches = fakeCaches();
      const err = await downloadBasemap(undefined, { fetch: server(body, [], len), caches, pin }).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(BasemapError);
      expect((err as BasemapError).kind).toBe(kind);
      expect(caches.store.size).toBe(0);
      expect(loadedBasemap()).toBeNull();
    }
  });

  it("asks only /v1/info whether the server has the pinned version", async () => {
    const { zip } = archive();
    const calls: Call[] = [];
    expect(await basemapOffered({ fetch: server(zip, calls), caches: null, pin: await pinOf(zip) })).toBe(true);
    expect(await basemapOffered({ fetch: server(zip, calls), caches: null, pin: { ...(await pinOf(zip)), version: "20000101" } })).toBe(false);
    expect(calls.map((c) => c.url)).toEqual(["/v1/info", "/v1/info"]);
  });

  it("lets only archive-local resources through as an offline style", () => {
    const ok = {
      version: 8 as const,
      glyphs: "ashzip://archive/fonts/{fontstack}/{range}.pbf",
      sprite: "ashzip://archive/sprites/light",
      sources: { japan: { type: "vector" as const, url: "pmtiles://ashzip/japan.pmtiles" } },
      layers: [],
    };
    expect(remoteUrls(ok)).toEqual([]);
    expect(remoteUrls({ ...ok, glyphs: "https://example.net/{fontstack}/{range}.pbf" })).toHaveLength(1);
    expect(remoteUrls({ ...ok, sources: { x: { type: "raster", tiles: ["https://example.net/{z}/{x}/{y}.png"] } } })).toHaveLength(1);
  });
});
