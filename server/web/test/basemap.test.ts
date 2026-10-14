import { zipSync, strToU8 } from "fflate";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ZipOverHttp, remoteUrls } from "../src/map/basemap";

/** A zip laid out as tools/basemap/package.py writes it: .pmtiles stored, the rest deflated. */
function archive(): { zip: Uint8Array; pmtiles: Uint8Array; style: string } {
  const pmtiles = Uint8Array.from({ length: 5000 }, (_, i) => (i * 7) & 0xff);
  const style = JSON.stringify({ version: 8, glyphs: "file://@BASEMAP_DIR@/fonts/{fontstack}/{range}.pbf", sources: {}, layers: [] }).repeat(20);
  const zip = zipSync({
    "style.json": [strToU8(style), { level: 6 }],
    "japan.pmtiles": [pmtiles, { level: 0 }],
    "fonts/Noto Sans Regular/0-255.pbf": [new Uint8Array(300).fill(3), { level: 6 }],
  });
  return { zip, pmtiles, style };
}

/** Serves `bytes` with single Range requests (206), as server/src/basemap.ts does. */
function rangeFetch(bytes: Uint8Array, calls: string[]) {
  return async (_url: RequestInfo | URL, init?: RequestInit) => {
    const range = new Headers(init?.headers).get("Range") ?? "";
    calls.push(range);
    const m = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!m) return new Response(new Uint8Array(bytes));
    const size = bytes.length;
    const start = m[1] === "" ? size - Number(m[2]) : Number(m[1]);
    const end = m[1] === "" ? size - 1 : Math.min(Number(m[2] || size - 1), size - 1);
    return new Response(new Uint8Array(bytes.slice(Math.max(0, start), end + 1)), { status: 206, headers: { "Content-Range": `bytes ${start}-${end}/${size}` } });
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("basemap archive over HTTP ranges", () => {
  it("reads the central directory, a deflated entry, and a stored entry in place", async () => {
    const { zip, pmtiles, style } = archive();
    const calls: string[] = [];
    vi.stubGlobal("fetch", rangeFetch(zip, calls));
    const z = await ZipOverHttp.open("/basemap/1/ashidori-basemap-1.zip");
    expect([...z.entries.keys()].sort()).toEqual(["fonts/Noto Sans Regular/0-255.pbf", "japan.pmtiles", "style.json"]);
    expect(new TextDecoder().decode(await z.read("style.json"))).toBe(style);
    expect(await z.read("fonts/Noto Sans Regular/0-255.pbf")).toEqual(new Uint8Array(300).fill(3));
    // A stored entry is a contiguous range of the archive: tiles are read at an offset.
    const e = z.entries.get("japan.pmtiles")!;
    expect(e.method).toBe(0);
    const off = await z.dataOffset(e);
    expect(zip.slice(off, off + e.size)).toEqual(pmtiles);
    expect(calls.every((c) => c.startsWith("bytes="))).toBe(true);
  });

  it("refuses an archive the server does not serve in ranges", async () => {
    vi.stubGlobal("fetch", async () => new Response(new Uint8Array(10)));
    await expect(ZipOverHttp.open("/x.zip")).rejects.toThrow(/range/);
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
