import { readFileSync } from "node:fs";
import { join } from "node:path";
import { strToU8, zipSync } from "fflate";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { hex } from "../src/data/bytes";
import { cachedBasemap, CACHE_NAME, archiveUrl, resetBasemap, type BasemapDeps, type BasemapPin } from "../src/map/basemap";
import {
  chooseStyle,
  creditsOf,
  currentStyle,
  DEFAULT_STYLE,
  MAP_STYLES,
  ONLINE_ORIGINS,
  onlineStyle,
  onStyle,
  planBase,
  readChoice,
  resetStyle,
  STORAGE_KEY,
  writeChoice,
  type ChoiceStorage,
} from "../src/map/styles";

/** localStorage in memory; `blocked` throws as in a private window with site data blocked. */
function fakeStorage(blocked = false): ChoiceStorage & { map: Map<string, string> } {
  const map = new Map<string, string>();
  return {
    map,
    getItem(k) {
      if (blocked) throw new DOMException("blocked", "SecurityError");
      return map.get(k) ?? null;
    },
    setItem(k, v) {
      if (blocked) throw new DOMException("blocked", "SecurityError");
      map.set(k, v);
    },
  };
}

beforeEach(() => {
  resetStyle();
  resetBasemap();
});
afterEach(() => vi.unstubAllGlobals());

describe("map style choice", () => {
  it("offers the app's styles, 標準 by default", () => {
    expect(MAP_STYLES.map((s) => s.label)).toEqual(["標準", "シンプル", "地理院 標準", "地理院 淡色", "航空写真", "簡易地図（端末内）"]);
    expect(DEFAULT_STYLE).toBe("ofm-liberty");
    expect(readChoice(fakeStorage())).toBe("ofm-liberty");
    expect(readChoice(null)).toBe("ofm-liberty");
  });

  it("keeps the choice across a reload", () => {
    const storage = fakeStorage();
    vi.stubGlobal("localStorage", storage);
    expect(currentStyle()).toBe("ofm-liberty");
    chooseStyle("offline");
    expect(storage.map.get(STORAGE_KEY)).toBe("offline");
    // A new page: the module state is gone, the storage is not.
    resetStyle();
    expect(currentStyle()).toBe("offline");
  });

  it("ignores an unknown stored value, and works without storage", () => {
    const storage = fakeStorage();
    storage.setItem(STORAGE_KEY, "custom");
    expect(readChoice(storage)).toBe(DEFAULT_STYLE);
    const blocked = fakeStorage(true);
    expect(readChoice(blocked)).toBe(DEFAULT_STYLE);
    expect(() => writeChoice("gsi-pale", blocked)).not.toThrow();
    vi.stubGlobal("localStorage", blocked);
    chooseStyle("gsi-pale");
    expect(currentStyle()).toBe("gsi-pale");
  });

  it("tells every map of a change", () => {
    vi.stubGlobal("localStorage", fakeStorage());
    const seen: string[] = [];
    onStyle(() => seen.push(currentStyle()));
    chooseStyle("gsi-photo");
    chooseStyle("gsi-photo");
    expect(seen).toEqual(["gsi-photo"]);
  });
});

describe("what a map shows", () => {
  it("never shows an online map while the offline one is chosen", () => {
    expect(planBase("offline", true, true)).toEqual({ kind: "offline" });
    // Evicted from Cache Storage (or never fetched): no base map, and a re-fetch if the server has it.
    expect(planBase("offline", false, true)).toEqual({ kind: "offline-missing", offered: true });
    expect(planBase("offline", false, false)).toEqual({ kind: "offline-missing", offered: false });
    expect(planBase("ofm-positron", true, true)).toEqual({ kind: "online", key: "ofm-positron", style: "https://tiles.openfreemap.org/styles/positron" });
  });

  it("credits each provider", () => {
    expect(creditsOf("ofm-liberty").map((c) => c.text)).toEqual(["OpenFreeMap", "© OpenMapTiles", "© OpenStreetMap contributors"]);
    expect(creditsOf("gsi-std").map((c) => c.text)).toEqual(["地理院タイル"]);
    expect(creditsOf("gsi-photo").map((c) => c.text)).toEqual(["地理院タイル"]);
    expect(creditsOf("offline").map((c) => c.text)).toEqual(["© OpenStreetMap contributors"]);
  });

  it("fetches only from the origins the server's CSP allows", () => {
    const urls: string[] = [];
    for (const s of MAP_STYLES) {
      if (s.key === "offline") continue;
      const st = onlineStyle(s.key);
      if (typeof st === "string") urls.push(st);
      else for (const src of Object.values(st.sources)) urls.push(...((src as { tiles?: string[] }).tiles ?? []));
    }
    for (const u of urls) expect(ONLINE_ORIGINS).toContain(new URL(u.replace(/[{}]/g, "")).origin);
    const viewer = readFileSync(join(import.meta.dirname, "../../src/viewer.ts"), "utf8");
    const pinned = /MAP_ORIGINS = \[([^\]]*)\]/.exec(viewer)![1]!.match(/"[^"]+"/g)!.map((s) => JSON.parse(s) as string);
    expect(pinned).toEqual([...ONLINE_ORIGINS]);
  });
});

/** A minimal archive with an offline style, as tools/basemap/package.py lays it out. */
async function archive(): Promise<{ zip: Uint8Array; pin: BasemapPin }> {
  const style = JSON.stringify({ version: 8, sources: { japan: { type: "vector", url: "pmtiles://file://@BASEMAP_DIR@/japan.pmtiles" } }, layers: [] });
  const zip = zipSync({ "style.json": [strToU8(style), { level: 6 }], "japan.pmtiles": [new Uint8Array(100), { level: 0 }] });
  const sha256 = hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new Uint8Array(zip))));
  return { zip, pin: { version: "20990101", file: "ashidori-basemap-20990101.zip", bytes: zip.byteLength, sha256 } };
}

function cachesWith(entries: Map<string, Blob>): CacheStorage {
  const cache = {
    match: async (url: string) => (entries.has(url) ? new Response(entries.get(url)!) : undefined),
    put: async (url: string, res: Response) => void entries.set(url, await res.blob()),
    delete: async (url: string) => entries.delete(url),
  };
  return { open: async (name: string) => (expect(name).toBe(CACHE_NAME), cache as unknown as Cache) } as unknown as CacheStorage;
}

describe("a reload with 簡易地図 chosen", () => {
  it("uses the kept archive without any request, and plans no online map", async () => {
    const { zip, pin } = await archive();
    const storage = fakeStorage();
    storage.setItem(STORAGE_KEY, "offline");
    vi.stubGlobal("localStorage", storage);
    const calls: string[] = [];
    const deps: BasemapDeps = {
      fetch: (async (u: RequestInfo | URL) => (calls.push(String(u)), new Response(null, { status: 500 }))) as typeof fetch,
      caches: cachesWith(new Map([[archiveUrl(pin), new Blob([new Uint8Array(zip)])]])),
      pin,
    };
    // The choice is known synchronously, before anything is drawn.
    const key = currentStyle();
    expect(key).toBe("offline");
    const basemap = await cachedBasemap(deps);
    expect(basemap).not.toBeNull();
    expect(planBase(key, !!basemap, false)).toEqual({ kind: "offline" });
    expect(calls).toEqual([]);
  });

  it("offers a re-fetch, not an online map, when the archive was evicted", async () => {
    const { pin } = await archive();
    const storage = fakeStorage();
    storage.setItem(STORAGE_KEY, "offline");
    vi.stubGlobal("localStorage", storage);
    const calls: string[] = [];
    const deps: BasemapDeps = {
      fetch: (async (u: RequestInfo | URL) => (calls.push(String(u)), new Response(null, { status: 500 }))) as typeof fetch,
      caches: cachesWith(new Map()),
      pin,
    };
    const basemap = await cachedBasemap(deps);
    expect(basemap).toBeNull();
    expect(planBase(currentStyle(), !!basemap, true)).toEqual({ kind: "offline-missing", offered: true });
    expect(calls).toEqual([]);
  });
});
