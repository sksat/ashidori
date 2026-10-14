// The web viewer (server/web, built to server/web/dist) at `/`, served
// through Workers Static Assets. wrangler.jsonc sets `run_worker_first`, so
// every request reaches this Worker first: /v1, /admin, /pair and /basemap
// keep their routes, and the viewer's files get the headers below.
//
// The viewer decrypts the user's records and holds the batch key in the
// browser, so its page must not run anything but its own files: no inline
// scripts or styles, no script of another origin, and no other script of
// this origin (the admin page, /pair): scripts only from the viewer's own
// directories. The only other origins are the online map providers it may
// fetch map data from (web/src/map/styles.ts).

import { Hono } from "hono";
import type { AppEnv } from "./env";

/** The Static Assets binding (wrangler.jsonc "assets.binding"). */
interface AssetsEnv {
  ASSETS?: Fetcher;
}

/**
 * Where the viewer's online maps come from (web/src/map/styles.ts
 * ONLINE_ORIGINS): OpenFreeMap's style, TileJSON, tiles, glyphs and
 * sprites, and GSI's raster tiles. MapLibre fetches all of them, raster
 * tiles included (refreshExpiredTiles), so only connect-src needs them.
 */
export const MAP_ORIGINS = ["https://tiles.openfreemap.org", "https://cyberjapandata.gsi.go.jp"] as const;

/**
 * The viewer's CSP for a page or file served at `origin`. Scripts are
 * allowed by path, not by 'self': Vite's hashed files under /assets/ and the
 * MapLibre copy under /vendor/ (web/vite.config.ts). These directories hold
 * nothing else, and this Worker redirects nothing out of them, so CSP's path
 * matching (relaxed only after a redirect) keeps /admin and /pair out.
 * A nonce would need a fresh page per response (no caching of `/`) and
 * module graphs to inherit it in every browser; the paths need neither.
 */
export function viewerCsp(origin: string): string {
  return [
    "default-src 'none'",
    `script-src ${origin}/assets/ ${origin}/vendor/`,
    "style-src 'self'",
    // MapLibre's worker is a same-origin module (web/vite.config.ts).
    `worker-src ${origin}/vendor/`,
    // The API, the offline basemap archive (one full download, never Range
    // requests) and the online map providers.
    `connect-src 'self' ${MAP_ORIGINS.join(" ")}`,
    // MapLibre's stylesheet uses data: images; decoded tiles may be blob:.
    "img-src 'self' data: blob:",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join("; ");
}

export const viewer = new Hono<AppEnv>();

async function serve(req: Request, env: AssetsEnv, path: string, cache: string): Promise<Response> {
  if (!env.ASSETS) return new Response("viewer not built", { status: 503, headers: { "Content-Type": "text/plain; charset=utf-8" } });
  const url = new URL(req.url);
  url.pathname = path;
  url.search = "";
  const res = await env.ASSETS.fetch(new Request(url, { method: req.method, headers: req.headers }));
  // No redirect out of the viewer's paths (the CSP allows scripts by path).
  if (res.status === 404 || (res.status >= 300 && res.status < 400 && res.status !== 304)) {
    return Response.json({ error: "not found" }, { status: 404 });
  }
  // A response of a binding has immutable headers; copy it.
  const out = new Response(res.body, res);
  out.headers.set("Content-Security-Policy", viewerCsp(new URL(req.url).origin));
  out.headers.set("Cache-Control", res.ok ? cache : "no-store");
  out.headers.set("Cross-Origin-Resource-Policy", "same-origin");
  return out;
}

// The page itself is revalidated, so a deploy takes effect at once.
viewer.get("/", (c) => serve(c.req.raw, c.env as AssetsEnv, "/", "no-cache"));
// Hashed or versioned file names never change their content.
viewer.get("/assets/:file{[A-Za-z0-9._-]+}", (c) => serve(c.req.raw, c.env as AssetsEnv, c.req.path, "public, max-age=31536000, immutable"));
viewer.get("/vendor/:dir{maplibre-gl-[0-9.]+}/:file{[A-Za-z0-9._-]+}", (c) =>
  serve(c.req.raw, c.env as AssetsEnv, c.req.path, "public, max-age=31536000, immutable"),
);
viewer.get("/favicon.svg", (c) => serve(c.req.raw, c.env as AssetsEnv, "/favicon.svg", "public, max-age=86400"));
