// The `archive` stream (docs/sync-protocol.md, "Archive"): Parquet files of
// finished days and months, sent as parts `{file, gen, part, parts, data}`.
// Parts are assembled per (file, gen); only a complete generation is used.
// Files are read with hyparquet, which decodes the app's encodings
// (DELTA_BINARY_PACKED for integers, BYTE_STREAM_SPLIT for floats, PLAIN
// strings, RLE definition levels, GZIP pages; see core's ParquetWriter).

import { gunzipSync } from "fflate";
import { parquetMetadata, parquetReadObjects } from "hyparquet";
import { concat, fromBase64 } from "./bytes";
import type { Fix, MotionWindow } from "./model";

export interface ArchivePart {
  file: string;
  gen: string;
  part: number;
  parts: number;
  data: Uint8Array;
}

/** A chunk of 3 MiB at most; bound the count so a bad record cannot ask for a huge file. */
export const MAX_PARTS = 4096;
const FILE_RE = /^([a-z][a-z0-9_]{0,31})\/(\d{4}-\d{2}(?:-\d{2})?)\.parquet$/;

/** Validates one archive record; null when it is not one. */
export function parseArchiveRecord(x: unknown): ArchivePart | null {
  if (typeof x !== "object" || x === null) return null;
  const r = x as Record<string, unknown>;
  const { file, gen, part, parts, data } = r;
  if (typeof file !== "string" || !FILE_RE.test(file)) return null;
  if (typeof gen !== "string" || !/^[0-9a-f]{1,32}$/i.test(gen)) return null;
  if (!Number.isInteger(parts) || (parts as number) < 1 || (parts as number) > MAX_PARTS) return null;
  if (!Number.isInteger(part) || (part as number) < 0 || (part as number) >= (parts as number)) return null;
  if (typeof data !== "string") return null;
  let bytes: Uint8Array;
  try {
    bytes = fromBase64(data);
  } catch {
    return null;
  }
  return { file, gen: gen.toLowerCase(), part: part as number, parts: parts as number, data: bytes };
}

/** `fixes/2026-10-05.parquet` → table and period. */
export function fileInfo(file: string): { table: string; period: string; month: boolean } | null {
  const m = FILE_RE.exec(file);
  return m ? { table: m[1]!, period: m[2]!, month: m[2]!.length === 7 } : null;
}

interface Generation {
  parts: number;
  chunks: Map<number, Uint8Array>;
  /** The highest seq of its parts: the newest complete generation wins. */
  lastSeq: number;
}

/** Collects parts and yields complete files. */
export class ArchiveAssembler {
  private readonly gens = new Map<string, Map<string, Generation>>();

  /** Adds a part; returns the file's bytes once its generation is complete. */
  add(p: ArchivePart, seq: number): Uint8Array | null {
    let byGen = this.gens.get(p.file);
    if (!byGen) this.gens.set(p.file, (byGen = new Map()));
    let g = byGen.get(p.gen);
    if (!g) byGen.set(p.gen, (g = { parts: p.parts, chunks: new Map(), lastSeq: seq }));
    // Parts of one generation must agree on the count; a disagreeing part is dropped.
    if (g.parts !== p.parts) return null;
    // A duplicate part (re-uploaded after an app upgrade) keeps the first copy.
    if (!g.chunks.has(p.part)) g.chunks.set(p.part, p.data);
    g.lastSeq = Math.max(g.lastSeq, seq);
    return g.chunks.size === g.parts ? this.bytesOf(g) : null;
  }

  private bytesOf(g: Generation): Uint8Array {
    return concat(Array.from({ length: g.parts }, (_, i) => g.chunks.get(i)!));
  }

  /** The newest complete generation of every file. */
  complete(): Map<string, Uint8Array> {
    const out = new Map<string, Uint8Array>();
    for (const [file, byGen] of this.gens) {
      let best: Generation | null = null;
      for (const g of byGen.values()) if (g.chunks.size === g.parts && (!best || g.lastSeq > best.lastSeq)) best = g;
      if (best) out.set(file, this.bytesOf(best));
    }
    return out;
  }

  hasComplete(file: string): boolean {
    return [...(this.gens.get(file)?.values() ?? [])].some((g) => g.chunks.size === g.parts);
  }

  /** Files with parts but no complete generation (shown as partly received). */
  incomplete(): string[] {
    const out: string[] = [];
    for (const [file, byGen] of this.gens) if (![...byGen.values()].some((g) => g.chunks.size === g.parts)) out.push(file);
    return out;
  }
}

// ------------------------------------------------------------- Parquet

const compressors = {
  GZIP: (input: Uint8Array) => gunzipSync(input),
};

export type Cell = number | bigint | string | null;

export interface Table {
  columns: string[];
  rows: Record<string, Cell>[];
}

/** Row cap of one archive file (a month of 1 Hz fixes is 2.7 million rows). */
export const MAX_FILE_ROWS = 5_000_000;

export async function readParquet(bytes: Uint8Array): Promise<Table> {
  const file = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  const meta = parquetMetadata(file);
  if (Number(meta.num_rows) > MAX_FILE_ROWS) throw new Error(`Parquet file has ${meta.num_rows} rows`);
  const columns = meta.schema.slice(1).map((s) => s.name);
  const rows = (await parquetReadObjects({ file, compressors, utf8: true })) as Record<string, Cell>[];
  return { columns, rows };
}

const num = (v: Cell | undefined): number => (v === null || v === undefined ? NaN : typeof v === "bigint" ? Number(v) : typeof v === "number" ? v : NaN);

function int(v: Cell | undefined): number | null {
  if (typeof v === "bigint") {
    const n = Number(v);
    return Number.isSafeInteger(n) ? n : null;
  }
  return typeof v === "number" && Number.isInteger(v) ? v : null;
}

/** Rows of `fixes` (Archive.fixOf). Rows without a time or position are skipped. */
export function fixesOf(t: Table): Fix[] {
  const out: Fix[] = [];
  for (const r of t.rows) {
    const time = int(r.time);
    const lat = num(r.lat);
    const lon = num(r.lon);
    if (time === null || !Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    out.push({
      time,
      lat,
      lon,
      accuracy: num(r.acc),
      altitude: num(r.alt),
      speed: num(r.speed),
      bearing: num(r.bearing),
      provider: typeof r.provider === "string" ? r.provider : "",
    });
  }
  return out;
}

/** Rows of `motion` (Archive.motionOf). */
export function motionOf(t: Table): MotionWindow[] {
  const out: MotionWindow[] = [];
  for (const r of t.rows) {
    const start = int(r.start);
    const end = int(r.end);
    if (start === null || end === null) continue;
    out.push({ start, end, accelStd: num(r.std), accelMean: num(r.mean), steps: int(r.steps) ?? 0 });
  }
  return out;
}

// Earlier app versions sent fixes and motion as JSON Lines streams
// (Db.SyncColumns: t, lat, lon, acc, alt, spd, brg, src / s, e, std, mean, steps).

export function legacyFix(x: unknown): Fix | null {
  if (typeof x !== "object" || x === null) return null;
  const r = x as Record<string, unknown>;
  const n = (v: unknown) => (typeof v === "number" ? v : NaN);
  if (!Number.isSafeInteger(r.t) || !Number.isFinite(r.lat) || !Number.isFinite(r.lon)) return null;
  return {
    time: r.t as number,
    lat: r.lat as number,
    lon: r.lon as number,
    accuracy: n(r.acc),
    altitude: n(r.alt),
    speed: n(r.spd),
    bearing: n(r.brg),
    provider: typeof r.src === "string" ? r.src : "",
  };
}

export function legacyMotion(x: unknown): MotionWindow | null {
  if (typeof x !== "object" || x === null) return null;
  const r = x as Record<string, unknown>;
  if (!Number.isSafeInteger(r.s) || !Number.isSafeInteger(r.e)) return null;
  const n = (v: unknown) => (typeof v === "number" ? v : NaN);
  return { start: r.s as number, end: r.e as number, accelStd: n(r.std), accelMean: n(r.mean), steps: Number.isInteger(r.steps) ? (r.steps as number) : 0 };
}

/** Day and month files of a table overlap: drop exact duplicates (docs: "A restorer ... drops exact duplicate rows"). */
export function dedupeFixes(fixes: Fix[]): Fix[] {
  const seen = new Set<string>();
  const out: Fix[] = [];
  for (const f of fixes.sort((a, b) => a.time - b.time)) {
    const k = `${f.time}|${f.lat}|${f.lon}|${f.accuracy}|${f.speed}|${f.provider}`;
    if (!seen.has(k)) {
      seen.add(k);
      out.push(f);
    }
  }
  return out;
}

export function dedupeMotion(ws: MotionWindow[]): MotionWindow[] {
  const seen = new Set<string>();
  const out: MotionWindow[] = [];
  for (const w of ws.sort((a, b) => a.start - b.start)) {
    const k = `${w.start}|${w.end}|${w.accelStd}|${w.steps}`;
    if (!seen.has(k)) {
      seen.add(k);
      out.push(w);
    }
  }
  return out;
}
