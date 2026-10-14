// The read side of the sync API (docs/sync-protocol.md, "HTTP API"), with
// the caps the app enforces while reading ("Other devices' status"):
// a listing page ≤ 1 MiB and ≤ `limit` rows, other JSON ≤ 1 MiB, a status
// blob ≤ 64 KiB, any blob ≤ 8 MiB (the server's maximum).

import { LimitExceeded, readCapped } from "./bytes";
import type { BlobRef } from "./crypto";

export const LIST_MAX_LIMIT = 1000;
export const MAX_JSON = 1024 * 1024;
export const MAX_STATUS_BLOB = 64 * 1024;
export const MAX_BLOB = 8 * 1024 * 1024;
/** 200 000 batches per user (the default limit) is 200 pages; more is not a listing to follow. */
export const MAX_PAGES = 400;

const CURSOR_MAX = 999_999_999_999_999; // 15 digits
const SEQ_MAX = 999_999_999_999; // 12 digits
const STREAM_RE = /^[a-z][a-z0-9_]{0,31}$/;
const SHA_RE = /^[0-9a-f]{64}$/i;
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export interface DeviceInfo {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number | null;
  revoked: boolean;
  /** "viewer" for a browser (read-only, no batches); "device" for an app, and from older servers. */
  kind: "device" | "viewer";
  /** A viewer's expiry (ms); null for app devices. */
  expiresAt: number | null;
}

export interface BatchMeta extends BlobRef {
  cursor: number;
  createdAt: number;
}

export interface Page {
  items: BatchMeta[];
  next: number | null;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code ? `HTTP ${status}: ${code}` : `HTTP ${status}`);
    this.name = "ApiError";
  }
  /** The token is no longer valid (revoked browser, unknown token). */
  get unauthorized(): boolean {
    return this.status === 401;
  }
}

/** A listing that breaks the protocol's rules (a malformed row, a cursor that does not increase). */
export class ListingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ListingError";
  }
}

export interface ApiOptions {
  token: string;
  /** Origin of the server; the viewer is served by it, so "" (same origin) by default. */
  base?: string;
  fetch?: typeof fetch;
}

const isInt = (v: unknown, max: number): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 && v <= max;

/** Validates one listing row; throws on a malformed one (it fails the page, as in the app). */
export function parseBatchRow(x: unknown): BatchMeta {
  if (typeof x !== "object" || x === null) throw new ListingError("row is not an object");
  const r = x as Record<string, unknown>;
  if (!isInt(r.cursor, CURSOR_MAX)) throw new ListingError("cursor out of range");
  if (!isInt(r.seq, SEQ_MAX)) throw new ListingError("seq out of range");
  if (!isInt(r.size, Number.MAX_SAFE_INTEGER)) throw new ListingError("size out of range");
  if (typeof r.sha256 !== "string" || !SHA_RE.test(r.sha256)) throw new ListingError("malformed sha256");
  if (typeof r.stream !== "string" || !STREAM_RE.test(r.stream)) throw new ListingError("malformed stream");
  if (typeof r.deviceId !== "string" || !ID_RE.test(r.deviceId)) throw new ListingError("malformed device id");
  const createdAt = typeof r.createdAt === "number" && Number.isFinite(r.createdAt) ? r.createdAt : 0;
  return { cursor: r.cursor, deviceId: r.deviceId, stream: r.stream, seq: r.seq, size: r.size, sha256: r.sha256.toLowerCase(), createdAt };
}

/** Checks a page against `after` and `limit`: rows above `after`, strictly increasing. */
export function checkPage(raw: unknown, after: number, limit: number): Page {
  if (typeof raw !== "object" || raw === null || !Array.isArray((raw as { items?: unknown }).items)) throw new ListingError("not a listing");
  const rawItems = (raw as { items: unknown[] }).items;
  if (rawItems.length > limit) throw new ListingError(`more than ${limit} rows`);
  const items = rawItems.map(parseBatchRow);
  let prev = after;
  for (const it of items) {
    if (it.cursor <= prev) throw new ListingError("cursor does not increase");
    prev = it.cursor;
  }
  const next = (raw as { next?: unknown }).next;
  if (next === null || next === undefined) return { items, next: null };
  if (!isInt(next, CURSOR_MAX) || next <= after) throw new ListingError("next does not move on");
  return { items, next };
}

export class Api {
  private readonly base: string;
  private readonly f: typeof fetch;

  constructor(private readonly opts: ApiOptions) {
    this.base = opts.base ?? "";
    this.f = opts.fetch ?? fetch.bind(globalThis);
  }

  private async get(path: string, accept: string, signal?: AbortSignal): Promise<Response> {
    const res = await this.f(this.base + path, {
      headers: { Authorization: `Bearer ${this.opts.token}`, Accept: accept },
      credentials: "omit",
      cache: "no-store",
      referrerPolicy: "no-referrer",
      ...(signal ? { signal } : {}),
    });
    if (!res.ok) {
      let code = "";
      try {
        const body = (await readCapped(res.body ?? new Blob().stream(), 4096, "error")) as Uint8Array;
        const j = JSON.parse(new TextDecoder().decode(body)) as { error?: unknown };
        if (typeof j.error === "string") code = j.error;
      } catch {
        // Not a JSON error: the status says enough.
      }
      throw new ApiError(res.status, code);
    }
    return res;
  }

  private async json(path: string, max = MAX_JSON, signal?: AbortSignal): Promise<unknown> {
    const res = await this.get(path, "application/json", signal);
    const len = Number(res.headers.get("Content-Length") ?? "0");
    if (len > max) throw new LimitExceeded(path, max);
    const body = await readCapped(res.body ?? new Blob().stream(), max, path);
    return JSON.parse(new TextDecoder().decode(body)) as unknown;
  }

  async devices(signal?: AbortSignal): Promise<DeviceInfo[]> {
    const raw = await this.json("/v1/devices", MAX_JSON, signal);
    if (!Array.isArray(raw)) throw new ListingError("devices is not a list");
    return raw.flatMap((x): DeviceInfo[] => {
      if (typeof x !== "object" || x === null) return [];
      const d = x as Record<string, unknown>;
      if (typeof d.id !== "string" || !ID_RE.test(d.id)) return [];
      return [
        {
          id: d.id,
          name: typeof d.name === "string" ? d.name : "",
          createdAt: typeof d.createdAt === "number" ? d.createdAt : 0,
          lastSeenAt: typeof d.lastSeenAt === "number" ? d.lastSeenAt : null,
          revoked: d.revoked === true || (typeof d.revoked === "number" && d.revoked > 0),
          kind: d.kind === "viewer" ? "viewer" : "device",
          expiresAt: typeof d.expiresAt === "number" && Number.isFinite(d.expiresAt) ? d.expiresAt : null,
        },
      ];
    });
  }

  async batches(after: number, limit = LIST_MAX_LIMIT, signal?: AbortSignal): Promise<Page> {
    const raw = await this.json(`/v1/batches?after=${after}&limit=${limit}`, MAX_JSON, signal);
    return checkPage(raw, after, limit);
  }

  /** Every listed batch from `after`, page by page. */
  async *allBatches(after = 0, signal?: AbortSignal): AsyncGenerator<BatchMeta[]> {
    let cursor = after;
    for (let page = 0; page < MAX_PAGES; page++) {
      const p = await this.batches(cursor, LIST_MAX_LIMIT, signal);
      if (p.items.length) yield p.items;
      if (p.next === null) return;
      cursor = p.next;
    }
    throw new ListingError(`more than ${MAX_PAGES} pages`);
  }

  /** One blob, under the cap of its stream. */
  async blob(ref: BlobRef, signal?: AbortSignal): Promise<Uint8Array> {
    const max = ref.stream === "status" ? MAX_STATUS_BLOB : MAX_BLOB;
    if (ref.size > max) throw new LimitExceeded(`${ref.stream} blob`, max);
    const path = `/v1/batches/${encodeURIComponent(ref.deviceId)}/${encodeURIComponent(ref.stream)}/${ref.seq}`;
    const res = await this.get(path, "application/octet-stream", signal);
    const len = Number(res.headers.get("Content-Length") ?? "0");
    if (len > max) throw new LimitExceeded(`${ref.stream} blob`, max);
    return readCapped(res.body ?? new Blob().stream(), max, `${ref.stream} blob`);
  }

  /** Public server information (`basemap` versions). */
  static async info(base = "", f: typeof fetch = fetch.bind(globalThis)): Promise<{ basemap: string[] }> {
    const res = await f(base + "/v1/info", { credentials: "omit", cache: "no-store" });
    if (!res.ok) throw new ApiError(res.status, "");
    const body = await readCapped(res.body ?? new Blob().stream(), 64 * 1024, "info");
    const j = JSON.parse(new TextDecoder().decode(body)) as { basemap?: unknown };
    const versions = Array.isArray(j.basemap) ? j.basemap.filter((v): v is string => typeof v === "string" && /^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$/.test(v) && !v.includes("..")) : [];
    return { basemap: versions };
  }
}
