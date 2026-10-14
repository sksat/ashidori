// The base maps the viewer offers, as in the app (app/.../ui/MapStyles.scala):
// online maps from OpenFreeMap and GSI by default, and the offline basemap
// (map/basemap.ts) as an option. The online maps fetch the tiles of the
// visible area from their provider, which so learns the area shown; the
// offline one is read in this browser. The CSP of src/viewer.ts allows
// exactly ONLINE_ORIGINS.
//
// The choice is kept per browser in localStorage and read synchronously
// before a map is drawn, so a page whose choice is the offline map never
// requests an online tile, not even for the first frame.

import type { StyleSpecification } from "maplibre-gl";

export type MapStyleKey = "ofm-liberty" | "ofm-positron" | "gsi-std" | "gsi-pale" | "gsi-photo" | "offline";

export interface MapStyleDef {
  key: MapStyleKey;
  label: string;
}

/** In the order of the picker. */
export const MAP_STYLES: readonly MapStyleDef[] = [
  { key: "ofm-liberty", label: "標準" },
  { key: "ofm-positron", label: "シンプル" },
  { key: "gsi-std", label: "地理院 標準" },
  { key: "gsi-pale", label: "地理院 淡色" },
  { key: "gsi-photo", label: "航空写真" },
  { key: "offline", label: "簡易地図（端末内）" },
];

export const DEFAULT_STYLE: MapStyleKey = "ofm-liberty";

const OFM = "https://tiles.openfreemap.org";
const GSI = "https://cyberjapandata.gsi.go.jp";

/**
 * Every origin an online map fetches from: OpenFreeMap's style, TileJSON,
 * tiles, glyphs and sprites are all on tiles.openfreemap.org; GSI's tiles
 * on cyberjapandata.gsi.go.jp.
 */
export const ONLINE_ORIGINS = [OFM, GSI] as const;

export interface Credit {
  text: string;
  href: string;
}

const OSM: Credit = { text: "© OpenStreetMap contributors", href: "https://www.openstreetmap.org/copyright" };
const OFM_CREDITS: Credit[] = [
  { text: "OpenFreeMap", href: "https://openfreemap.org/" },
  { text: "© OpenMapTiles", href: "https://www.openmaptiles.org/" },
  OSM,
];
/** GSI tiles must carry this credit (their terms of use). */
const GSI_CREDITS: Credit[] = [{ text: "地理院タイル", href: "https://maps.gsi.go.jp/development/ichiran.html" }];

/** The credits shown under a map of this style. The offline one adds 「このサーバから配信」. */
export function creditsOf(key: MapStyleKey): Credit[] {
  if (key === "offline") return [OSM];
  return key.startsWith("ofm-") ? OFM_CREDITS : GSI_CREDITS;
}

function gsiRaster(layer: string, ext: string): StyleSpecification {
  return {
    version: 8,
    sources: { base: { type: "raster", tiles: [`${GSI}/xyz/${layer}/{z}/{x}/{y}.${ext}`], tileSize: 256, maxzoom: 18 } },
    layers: [{ id: "base", type: "raster", source: "base" }],
  };
}

/** MapLibre style of an online map: a URL or an inline style. */
export function onlineStyle(key: Exclude<MapStyleKey, "offline">): string | StyleSpecification {
  switch (key) {
    case "ofm-liberty":
      return `${OFM}/styles/liberty`;
    case "ofm-positron":
      return `${OFM}/styles/positron`;
    case "gsi-std":
      return gsiRaster("std", "png");
    case "gsi-pale":
      return gsiRaster("pale", "png");
    case "gsi-photo":
      return gsiRaster("seamlessphoto", "jpg");
  }
}

// ---- the choice, per browser ----

export const STORAGE_KEY = "mapStyle";

/** The part of Storage used here (a fake in tests). */
export type ChoiceStorage = Pick<Storage, "getItem" | "setItem">;

function browserStorage(): ChoiceStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

const isKey = (k: unknown): k is MapStyleKey => MAP_STYLES.some((s) => s.key === k);

/** The kept choice, or the default when there is none or storage is blocked. */
export function readChoice(storage: ChoiceStorage | null = browserStorage()): MapStyleKey {
  try {
    const k = storage?.getItem(STORAGE_KEY);
    return isKey(k) ? k : DEFAULT_STYLE;
  } catch {
    return DEFAULT_STYLE;
  }
}

/** Keeps the choice; still used for this page when storage is blocked. */
export function writeChoice(key: MapStyleKey, storage: ChoiceStorage | null = browserStorage()): void {
  try {
    storage?.setItem(STORAGE_KEY, key);
  } catch {
    // A private window or blocked site data: the choice lasts for this page only.
  }
}

let current: MapStyleKey | null = null;
const listeners = new Set<() => void>();

/** The choice of this page, read from storage on first use. */
export function currentStyle(): MapStyleKey {
  current ??= readChoice();
  return current;
}

/** Changes the choice of every map on the page and keeps it. */
export function chooseStyle(key: MapStyleKey): void {
  if (key === currentStyle()) return;
  current = key;
  writeChoice(key);
  for (const f of [...listeners]) f();
}

export function onStyle(f: () => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}

/** For tests. */
export function resetStyle(): void {
  current = null;
  listeners.clear();
}

// ---- what a map shows for the choice ----

export type BasePlan =
  /** Tiles from the provider. */
  | { kind: "online"; key: Exclude<MapStyleKey, "offline">; style: string | StyleSpecification }
  /** The offline basemap, in this browser. */
  | { kind: "offline" }
  /**
   * The offline map is chosen but not in this browser (never fetched, or
   * evicted from Cache Storage): no base map, and a button to fetch it if
   * the server has it. Never an online map in its place.
   */
  | { kind: "offline-missing"; offered: boolean };

export function planBase(key: MapStyleKey, basemapHere: boolean, offered: boolean): BasePlan {
  if (key !== "offline") return { kind: "online", key, style: onlineStyle(key) };
  if (basemapHere) return { kind: "offline" };
  return { kind: "offline-missing", offered };
}
