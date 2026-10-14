// The app's offline basemap in the viewer: the same archive the app uses,
// /basemap/<version>/ashidori-basemap-<version>.zip (see server/src/basemap.ts
// and tools/basemap/package.py), pinned by the size and sha256 of
// app/src/main/assets/basemap-pin.json (vite.config.ts injects it).
//
// Where someone is looking on the map is location data. The viewer
// therefore never reads the archive in pieces: HTTP Range requests for the
// tiles in view would tell the server (and Cloudflare's edge) which area is
// shown. Instead choosing 「簡易地図（端末内）」 (ui/mappanel.ts) downloads
// the whole archive in one request, the same for every user. It is verified
// against the pin, kept in Cache Storage, and read locally from then on:
// tiles, style, glyphs and sprites are slices of a Blob in this browser. A
// copy already in Cache Storage is verified again and used without asking,
// since that sends nothing. (The online maps of map/styles.ts are the
// default; this one is the choice that sends nothing.)

import type { AddProtocolAction, StyleSpecification } from "maplibre-gl";
import { PMTiles, Protocol, type RangeResponse, type Source } from "pmtiles";
import { Api } from "../data/api";
import { buf, hex, readCapped } from "../data/bytes";

export interface BasemapPin {
  version: string;
  file: string;
  bytes: number;
  sha256: string;
}

/** The archive the app pins (one source of truth: the app's asset). */
export const PIN: BasemapPin = __BASEMAP_PIN__;

const PLACEHOLDER = "@BASEMAP_DIR@";
const ZIP_SCHEME = "ashzip";
const MAX_STYLE = 4 * 1024 * 1024;
const MAX_ENTRY = 16 * 1024 * 1024;
export const CACHE_NAME = "ashidori-basemap";

export function archiveUrl(pin: BasemapPin = PIN): string {
  return `/basemap/${encodeURIComponent(pin.version)}/${encodeURIComponent(pin.file)}`;
}

/** The size the load button names: 「約 64 MB」. */
export function approxMB(pin: BasemapPin = PIN): number {
  return Math.round(pin.bytes / 1_000_000);
}

interface ZipEntry {
  name: string;
  method: number;
  compressed: number;
  size: number;
  headerOffset: number;
}

async function slice(blob: Blob, start: number, length: number): Promise<Uint8Array> {
  if (start < 0 || length < 0 || start + length > blob.size) throw new Error("read past the end of the archive");
  return new Uint8Array(await blob.slice(start, start + length).arrayBuffer());
}

/** A zip held in this browser: its central directory, then entries on demand. */
export class LocalZip {
  private readonly dataOffsets = new Map<string, Promise<number>>();
  private constructor(
    readonly blob: Blob,
    readonly entries: Map<string, ZipEntry>,
  ) {}

  static async open(blob: Blob): Promise<LocalZip> {
    // End of central directory: 22 bytes plus a comment of up to 64 KiB.
    const tailLen = Math.min(blob.size, 22 + 0xffff);
    const tail = await slice(blob, blob.size - tailLen, tailLen);
    const v = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
    let eocd = -1;
    for (let i = tail.byteLength - 22; i >= 0; i--)
      if (v.getUint32(i, true) === 0x06054b50) {
        eocd = i;
        break;
      }
    if (eocd < 0) throw new Error("not a zip archive");
    const count = v.getUint16(eocd + 10, true);
    const cdSize = v.getUint32(eocd + 12, true);
    const cdOffset = v.getUint32(eocd + 16, true);
    if (cdOffset === 0xffffffff || count === 0xffff) throw new Error("zip64 archives are not supported");
    const cd = await slice(blob, cdOffset, cdSize);
    const d = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
    const entries = new Map<string, ZipEntry>();
    const dec = new TextDecoder();
    let p = 0;
    for (let i = 0; i < count && p + 46 <= cdSize; i++) {
      if (d.getUint32(p, true) !== 0x02014b50) throw new Error("bad central directory");
      const method = d.getUint16(p + 10, true);
      const compressed = d.getUint32(p + 20, true);
      const size = d.getUint32(p + 24, true);
      const n = d.getUint16(p + 28, true);
      const m = d.getUint16(p + 30, true);
      const k = d.getUint16(p + 32, true);
      const headerOffset = d.getUint32(p + 42, true);
      const name = dec.decode(cd.subarray(p + 46, p + 46 + n));
      entries.set(name, { name, method, compressed, size, headerOffset });
      p += 46 + n + m + k;
    }
    return new LocalZip(blob, entries);
  }

  /** Offset of an entry's data: after its local header, whose extra field may differ from the central one. */
  dataOffset(e: ZipEntry): Promise<number> {
    let o = this.dataOffsets.get(e.name);
    if (!o) {
      o = slice(this.blob, e.headerOffset, 30).then((data) => {
        const v = new DataView(data.buffer, data.byteOffset, data.byteLength);
        if (v.getUint32(0, true) !== 0x04034b50) throw new Error("bad local header");
        return e.headerOffset + 30 + v.getUint16(26, true) + v.getUint16(28, true);
      });
      this.dataOffsets.set(e.name, o);
    }
    return o;
  }

  async read(name: string, max = MAX_ENTRY): Promise<Uint8Array> {
    const e = this.entries.get(name);
    if (!e) throw new Error(`no ${name} in the basemap`);
    if (e.size > max || e.compressed > max) throw new Error(`${name} is too large`);
    if (e.size === 0) return new Uint8Array(0);
    const data = await slice(this.blob, await this.dataOffset(e), e.compressed);
    if (e.method === 0) return data;
    if (e.method !== 8) throw new Error(`unsupported zip method ${e.method}`);
    const out = new Blob([buf(data)]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return readCapped(out, Math.max(e.size, 1), name);
  }
}

/** A .pmtiles entry stored in the zip, as a pmtiles Source over the local Blob. */
class ZipEntrySource implements Source {
  constructor(
    private readonly zip: LocalZip,
    private readonly entry: ZipEntry,
    private readonly key: string,
  ) {}
  getKey() {
    return this.key;
  }
  async getBytes(offset: number, length: number): Promise<RangeResponse> {
    const base = await this.zip.dataOffset(this.entry);
    const len = Math.max(0, Math.min(length, this.entry.size - offset));
    if (len === 0) return { data: new ArrayBuffer(0) };
    const data = await slice(this.zip.blob, base + offset, len);
    return { data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer };
  }
}

export interface Basemap {
  version: string;
  style: StyleSpecification;
  attribution: string;
  /** Registers the protocols MapLibre needs for this basemap. */
  install(addProtocol: (name: string, action: AddProtocolAction) => void): void;
}

/** Remote URLs of a style (sources, glyphs, sprite) that are not of the archive. */
export function remoteUrls(style: StyleSpecification): string[] {
  const urls: string[] = [];
  for (const s of Object.values(style.sources ?? {})) {
    const src = s as { url?: string; tiles?: string[] };
    if (src.url) urls.push(src.url);
    if (src.tiles) urls.push(...src.tiles);
  }
  if (typeof style.glyphs === "string") urls.push(style.glyphs);
  if (typeof style.sprite === "string") urls.push(style.sprite);
  else if (Array.isArray(style.sprite)) urls.push(...style.sprite.map((s) => s.url));
  return urls.filter((u) => !u.startsWith(`${ZIP_SCHEME}://`) && !u.startsWith(`pmtiles://${ZIP_SCHEME}/`));
}

/** Whether `blob` is exactly the pinned archive. */
export async function matchesPin(blob: Blob, pin: BasemapPin = PIN): Promise<boolean> {
  if (blob.size !== pin.bytes) return false;
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()));
  return hex(digest) === pin.sha256.toLowerCase();
}

/** A basemap read from a verified archive in this browser. Null if its style would fetch from elsewhere. */
export async function basemapFromBlob(blob: Blob, version: string): Promise<Basemap | null> {
  const zip = await LocalZip.open(blob);
  const raw = new TextDecoder().decode(await zip.read("style.json", MAX_STYLE));
  if (!raw.includes(PLACEHOLDER)) return null;
  // Archive paths: "pmtiles://file://@BASEMAP_DIR@/japan.pmtiles" → "pmtiles://ashzip/japan.pmtiles",
  // glyphs and sprite "file://@BASEMAP_DIR@/..." → "ashzip://archive/...".
  const style = JSON.parse(
    raw.replaceAll(`pmtiles://file://${PLACEHOLDER}/`, `pmtiles://${ZIP_SCHEME}/`).replaceAll(`file://${PLACEHOLDER}/`, `${ZIP_SCHEME}://archive/`),
  ) as StyleSpecification;
  // Never let a style that would fetch from elsewhere pass as offline.
  if (remoteUrls(style).length) return null;
  const pmtiles = new Map<string, PMTiles>();
  for (const e of zip.entries.values()) {
    if (!e.name.endsWith(".pmtiles")) continue;
    if (e.method !== 0) return null;
    const key = `${ZIP_SCHEME}/${e.name}`;
    pmtiles.set(key, new PMTiles(new ZipEntrySource(zip, e, key)));
  }
  let installed = false;
  return {
    version,
    style,
    attribution: '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>',
    install(addProtocol) {
      if (installed) return;
      installed = true;
      const protocol = new Protocol();
      for (const p of pmtiles.values()) protocol.add(p);
      addProtocol("pmtiles", protocol.tile as AddProtocolAction);
      addProtocol(ZIP_SCHEME, async (params) => {
        const path = decodeURIComponent(params.url.slice(`${ZIP_SCHEME}://archive/`.length));
        const data = await zip.read(path, MAX_ENTRY);
        if (params.type === "json") return { data: JSON.parse(new TextDecoder().decode(data)) as object };
        return { data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) };
      });
    },
  };
}

// ---- loading: from Cache Storage (no request), or the explicit download ----

export interface BasemapDeps {
  fetch: typeof fetch;
  /** Cache Storage, or null where it is unavailable (an insecure context, storage blocked). */
  caches: CacheStorage | null;
  pin: BasemapPin;
}

function browserDeps(): BasemapDeps {
  let c: CacheStorage | null = null;
  try {
    c = typeof caches === "undefined" ? null : caches;
  } catch {
    c = null;
  }
  return { fetch: fetch.bind(globalThis), caches: c, pin: PIN };
}

let current: Basemap | null = null;
const listeners = new Set<() => void>();

/** The basemap loaded in this page, if any. */
export function loadedBasemap(): Basemap | null {
  return current;
}

/** Called when a basemap becomes available (every map switches to it). */
export function onBasemap(f: () => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}

function publish(b: Basemap): Basemap {
  current = b;
  for (const f of [...listeners]) f();
  return b;
}

/** For tests. */
export function resetBasemap(): void {
  current = null;
  cachedOnce = null;
  downloading = null;
}

let cachedOnce: Promise<Basemap | null> | null = null;

/**
 * The archive kept in Cache Storage by an earlier download,
 * verified again against the pin. Sends no request. A copy that does not
 * match is deleted.
 */
export function cachedBasemap(deps: BasemapDeps = browserDeps()): Promise<Basemap | null> {
  if (current) return Promise.resolve(current);
  cachedOnce ??= (async () => {
    if (!deps.caches) return null;
    try {
      const cache = await deps.caches.open(CACHE_NAME);
      const url = archiveUrl(deps.pin);
      const res = await cache.match(url);
      if (!res) return null;
      const blob = await res.blob();
      if (!(await matchesPin(blob, deps.pin))) {
        await cache.delete(url);
        return null;
      }
      const b = await basemapFromBlob(blob, deps.pin.version);
      return b ? publish(b) : null;
    } catch (e) {
      console.warn("cached basemap unavailable", e);
      return null;
    }
  })();
  return cachedOnce;
}

/** Whether the server serves the pinned version (GET /v1/info, the same for everyone). */
export async function basemapOffered(deps: BasemapDeps = browserDeps()): Promise<boolean> {
  try {
    return (await Api.info("", deps.fetch)).basemap.includes(deps.pin.version);
  } catch {
    return false;
  }
}

export class BasemapError extends Error {
  constructor(readonly kind: "http" | "size" | "hash" | "archive") {
    super(`basemap ${kind}`);
    this.name = "BasemapError";
  }
}

let downloading: Promise<Basemap> | null = null;

/**
 * Choosing 「簡易地図（端末内）」: the whole pinned archive in one request, without
 * a Range header, verified, then kept in Cache Storage. `progress` gets
 * the bytes received so far.
 */
export function downloadBasemap(progress?: (received: number, total: number) => void, deps: BasemapDeps = browserDeps()): Promise<Basemap> {
  if (current) return Promise.resolve(current);
  downloading ??= (async () => {
    const pin = deps.pin;
    const url = archiveUrl(pin);
    const res = await deps.fetch(url, { credentials: "omit", cache: "no-store", referrerPolicy: "no-referrer" });
    if (res.status !== 200 || !res.body) throw new BasemapError("http");
    const declared = res.headers.get("Content-Length");
    if (declared !== null && Number(declared) !== pin.bytes) throw new BasemapError("size");
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let n = 0;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        n += value.byteLength;
        if (n > pin.bytes) {
          await reader.cancel().catch(() => {});
          throw new BasemapError("size");
        }
        chunks.push(value);
        progress?.(n, pin.bytes);
      }
    } finally {
      reader.releaseLock();
    }
    const blob = new Blob(chunks.map((c) => buf(c)), { type: "application/zip" });
    chunks.length = 0;
    if (!(await matchesPin(blob, pin))) throw new BasemapError(blob.size === pin.bytes ? "hash" : "size");
    const b = await basemapFromBlob(blob, pin.version).catch(() => null);
    if (!b) throw new BasemapError("archive");
    // Kept for later visits; a page without Cache Storage still uses it now.
    try {
      if (deps.caches) {
        const cache = await deps.caches.open(CACHE_NAME);
        await cache.put(url, new Response(blob, { headers: { "Content-Type": "application/zip", "Content-Length": String(blob.size) } }));
      }
    } catch (e) {
      console.warn("basemap not kept in Cache Storage", e);
    }
    return publish(b);
  })();
  downloading.catch(() => (downloading = null));
  return downloading;
}

