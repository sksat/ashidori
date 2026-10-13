// The app's offline basemap, served from R2 (basemap/<version>/<file>) as an
// alternative to GitHub Releases (tools/basemap/package.sh, release.sh).
//
// Public and unauthenticated: the archive is the same for every user and
// covers whole countries, so a request reveals nothing about where anyone
// is, and the app checks every byte against the size and sha256 it pins, so
// the server need not be trusted for it. Only the archive and its descriptor
// of a well-formed version can be read: the route never maps a request to
// another key (such as the b/... batches). No cookies are set.

import { Hono, type Context } from "hono";
import type { AppEnv } from "./env";
import { jsonError } from "./util";

export const BASEMAP_PREFIX = "basemap/";
const VERSION_RE = /^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$/;

export function validBasemapVersion(v: string): boolean {
  return VERSION_RE.test(v) && !v.includes("..");
}

/** The R2 key of an allowed file, or null. */
export function basemapKey(version: string, file: string): string | null {
  if (!validBasemapVersion(version)) return null;
  const allowed = [`ashidori-basemap-${version}.zip`, `ashidori-basemap-${version}.json`];
  return allowed.includes(file) ? `${BASEMAP_PREFIX}${version}/${file}` : null;
}

/**
 * A single `Range: bytes=` range against `size`: the byte offset and length,
 * "unsatisfiable", or null to serve the whole object (no header, or one this
 * route does not handle, such as several ranges).
 */
export function parseRange(header: string | undefined, size: number): { offset: number; length: number } | "unsatisfiable" | null {
  if (!header) return null;
  const m = /^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$/.exec(header);
  if (!m) return null;
  const [, a, b] = m;
  if (a === "" && b === "") return null;
  if (a === "") {
    // Suffix: the last b bytes.
    const n = Number(b);
    if (n === 0 || size === 0) return "unsatisfiable";
    const length = Math.min(n, size);
    return { offset: size - length, length };
  }
  const first = Number(a);
  if (first >= size) return "unsatisfiable";
  const last = b === "" ? size - 1 : Math.min(Number(b), size - 1);
  if (last < first) return null;
  return { offset: first, length: last - first + 1 };
}

const CACHE_TTL_MS = 10 * 60 * 1000;
let versionsCache: { at: number; versions: string[] } | null = null;

/** For tests. */
export function clearBasemapCache(): void {
  versionsCache = null;
}

/**
 * Versions whose archive is in R2, newest name first. Cached per isolate
 * for a few minutes: GET /v1/info is unauthenticated, and each listing is
 * an R2 class A operation.
 */
export async function basemapVersions(blobs: R2Bucket, now = Date.now()): Promise<string[]> {
  if (versionsCache && now - versionsCache.at < CACHE_TTL_MS) return versionsCache.versions;
  const versions = new Set<string>();
  let cursor: string | undefined;
  for (let page = 0; page < 5; page++) {
    const listed = await blobs.list({ prefix: BASEMAP_PREFIX, limit: 1000, ...(cursor ? { cursor } : {}) });
    for (const o of listed.objects) {
      const m = /^basemap\/([^/]+)\/ashidori-basemap-([^/]+)\.zip$/.exec(o.key);
      if (m && m[1] === m[2] && validBasemapVersion(m[1]!)) versions.add(m[1]!);
    }
    if (!listed.truncated) break;
    cursor = listed.cursor;
  }
  const sorted = [...versions].sort().reverse();
  versionsCache = { at: now, versions: sorted };
  return sorted;
}

function contentType(file: string): string {
  return file.endsWith(".zip") ? "application/zip" : "application/json";
}

async function serve(c: Context<AppEnv>): Promise<Response> {
  const { version, file } = c.req.param() as { version: string; file: string };
  const key = basemapKey(version, file);
  if (!key) return jsonError(c, 404, "not found");
  const head = await c.env.BLOBS.head(key);
  if (!head) return jsonError(c, 404, "not found");

  const headers: Record<string, string> = {
    "Content-Type": contentType(file),
    ETag: head.httpEtag,
    "Accept-Ranges": "bytes",
    // A version's files never change.
    "Cache-Control": "public, max-age=604800, immutable",
  };
  const inm = c.req.header("If-None-Match");
  if (inm && inm.split(",").some((t) => t.trim() === head.httpEtag || t.trim() === "*")) {
    return new Response(null, { status: 304, headers });
  }
  // If-Range: a range only applies to the same object.
  const ifRange = c.req.header("If-Range");
  const range = ifRange && ifRange.trim() !== head.httpEtag ? null : parseRange(c.req.header("Range"), head.size);
  if (range === "unsatisfiable") {
    return new Response(null, { status: 416, headers: { ...headers, "Content-Range": `bytes */${head.size}` } });
  }
  const obj = await c.env.BLOBS.get(key, range ? { range } : undefined);
  if (!obj) return jsonError(c, 404, "not found");
  if (range) {
    return new Response(obj.body, {
      status: 206,
      headers: {
        ...headers,
        "Content-Length": String(range.length),
        "Content-Range": `bytes ${range.offset}-${range.offset + range.length - 1}/${head.size}`,
      },
    });
  }
  return new Response(obj.body, { status: 200, headers: { ...headers, "Content-Length": String(obj.size) } });
}

export const basemap = new Hono<AppEnv>();
// HEAD is answered by the GET handler without a body.
basemap.get("/:version/:file", serve);
