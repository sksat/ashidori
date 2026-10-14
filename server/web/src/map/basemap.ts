// The app's offline basemap, read in place from the archive the server
// serves at /basemap/<version>/ashidori-basemap-<version>.zip (see
// server/src/basemap.ts and tools/basemap/package.py).
//
// The archive is a zip whose .pmtiles entries are stored (not deflated),
// so each is one contiguous byte range of the zip: the map reads tiles
// with HTTP Range requests at an offset into the archive, and the style,
// glyphs and sprites (deflated) are inflated with DecompressionStream.
// Nothing is fetched from another origin.
//
// Unlike the app, which downloads the whole archive, the browser reads only
// the ranges of the tiles it shows; the server (and Cloudflare's edge) can
// therefore tell which area is being viewed. The viewer already trusts the
// server with the key; this is noted in the report, not hidden.

import type { AddProtocolAction, StyleSpecification } from "maplibre-gl";
import { PMTiles, Protocol, type RangeResponse, type Source } from "pmtiles";
import { Api } from "../data/api";
import { buf, readCapped } from "../data/bytes";

const PLACEHOLDER = "@BASEMAP_DIR@";
const ZIP_SCHEME = "ashzip";
const MAX_STYLE = 4 * 1024 * 1024;
const MAX_ENTRY = 16 * 1024 * 1024;

interface ZipEntry {
  name: string;
  method: number;
  compressed: number;
  size: number;
  headerOffset: number;
}

class RangeError_ extends Error {}

async function range(url: string, start: number, length: number, signal?: AbortSignal): Promise<{ data: Uint8Array; total: number }> {
  const res = await fetch(url, {
    headers: { Range: `bytes=${start}-${start + length - 1}` },
    credentials: "omit",
    ...(signal ? { signal } : {}),
  });
  if (res.status !== 206) throw new RangeError_(`range request answered ${res.status}`);
  const cr = /\/(\d+)$/.exec(res.headers.get("Content-Range") ?? "");
  const data = await readCapped(res.body ?? new Blob().stream(), length, "range");
  return { data, total: cr ? Number(cr[1]) : NaN };
}

async function suffix(url: string, length: number): Promise<{ data: Uint8Array; total: number }> {
  const res = await fetch(url, { headers: { Range: `bytes=-${length}` }, credentials: "omit" });
  if (res.status !== 206) throw new RangeError_(`range request answered ${res.status}`);
  const cr = /\/(\d+)$/.exec(res.headers.get("Content-Range") ?? "");
  const data = await readCapped(res.body ?? new Blob().stream(), length, "range");
  return { data, total: cr ? Number(cr[1]) : NaN };
}

/** Reads a zip over HTTP ranges: its central directory, then entries on demand. */
export class ZipOverHttp {
  private readonly dataOffsets = new Map<string, Promise<number>>();
  private constructor(
    readonly url: string,
    readonly entries: Map<string, ZipEntry>,
  ) {}

  static async open(url: string): Promise<ZipOverHttp> {
    // End of central directory: 22 bytes plus a comment of up to 64 KiB.
    const tail = await suffix(url, 22 + 0xffff);
    const v = new DataView(tail.data.buffer, tail.data.byteOffset, tail.data.byteLength);
    let eocd = -1;
    for (let i = tail.data.byteLength - 22; i >= 0; i--) if (v.getUint32(i, true) === 0x06054b50) {
      eocd = i;
      break;
    }
    if (eocd < 0) throw new Error("not a zip archive");
    const count = v.getUint16(eocd + 10, true);
    const cdSize = v.getUint32(eocd + 12, true);
    const cdOffset = v.getUint32(eocd + 16, true);
    if (cdOffset === 0xffffffff || count === 0xffff) throw new Error("zip64 archives are not supported");
    const cd = await range(url, cdOffset, cdSize);
    const d = new DataView(cd.data.buffer, cd.data.byteOffset, cd.data.byteLength);
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
      const name = dec.decode(cd.data.subarray(p + 46, p + 46 + n));
      entries.set(name, { name, method, compressed, size, headerOffset });
      p += 46 + n + m + k;
    }
    return new ZipOverHttp(url, entries);
  }

  /** Offset of an entry's data: after its local header, whose extra field may differ from the central one. */
  dataOffset(e: ZipEntry): Promise<number> {
    let o = this.dataOffsets.get(e.name);
    if (!o) {
      o = range(this.url, e.headerOffset, 30).then(({ data }) => {
        const v = new DataView(data.buffer, data.byteOffset, data.byteLength);
        if (v.getUint32(0, true) !== 0x04034b50) throw new Error("bad local header");
        return e.headerOffset + 30 + v.getUint16(26, true) + v.getUint16(28, true);
      });
      this.dataOffsets.set(e.name, o);
    }
    return o;
  }

  async read(name: string, max = MAX_ENTRY, signal?: AbortSignal): Promise<Uint8Array> {
    const e = this.entries.get(name);
    if (!e) throw new Error(`no ${name} in the basemap`);
    if (e.size > max || e.compressed > max) throw new Error(`${name} is too large`);
    if (e.size === 0) return new Uint8Array(0);
    const off = await this.dataOffset(e);
    const { data } = await range(this.url, off, e.compressed, signal);
    if (e.method === 0) return data;
    if (e.method !== 8) throw new Error(`unsupported zip method ${e.method}`);
    const out = new Blob([buf(data)]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return readCapped(out, Math.max(e.size, 1), name);
  }
}

/** A .pmtiles entry stored in the zip, as a pmtiles Source. */
class ZipEntrySource implements Source {
  constructor(
    private readonly zip: ZipOverHttp,
    private readonly entry: ZipEntry,
    private readonly key: string,
  ) {}
  getKey() {
    return this.key;
  }
  async getBytes(offset: number, length: number, signal?: AbortSignal): Promise<RangeResponse> {
    const base = await this.zip.dataOffset(this.entry);
    const len = Math.max(0, Math.min(length, this.entry.size - offset));
    if (len === 0) return { data: new ArrayBuffer(0) };
    const { data } = await range(this.zip.url, base + offset, len, signal);
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

let cached: Promise<Basemap | null> | null = null;

/** The newest basemap the server serves, or null (no basemap, no Range support, a bad archive). */
export function offlineBasemap(): Promise<Basemap | null> {
  cached ??= load().catch((e: unknown) => {
    console.warn("offline basemap unavailable", e);
    return null;
  });
  return cached;
}

async function load(): Promise<Basemap | null> {
  const { basemap } = await Api.info();
  const version = basemap[0];
  if (!version) return null;
  const url = `/basemap/${encodeURIComponent(version)}/ashidori-basemap-${encodeURIComponent(version)}.zip`;
  const zip = await ZipOverHttp.open(url);
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
      addProtocol(ZIP_SCHEME, async (params, abort) => {
        const path = decodeURIComponent(params.url.slice(`${ZIP_SCHEME}://archive/`.length));
        const data = await zip.read(path, MAX_ENTRY, abort.signal);
        if (params.type === "json") return { data: JSON.parse(new TextDecoder().decode(data)) as object };
        return { data: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) };
      });
    },
  };
}
