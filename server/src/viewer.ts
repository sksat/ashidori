// The web viewer (server/web, built to server/web/dist) at `/`, served
// through Workers Static Assets. wrangler.jsonc sets `run_worker_first`, so
// every request reaches this Worker first: /v1, /admin, /pair and /basemap
// keep their routes, and the viewer's files get the headers below.
//
// The viewer decrypts the user's records in the browser, so its page must
// not run anything but its own files: no inline scripts or styles, no
// other origin except the GSI tiles that 「詳しい地図」 shows on request.

import { Hono } from "hono";
import type { AppEnv } from "./env";

/** The Static Assets binding (wrangler.jsonc "assets.binding"). */
interface AssetsEnv {
  ASSETS?: Fetcher;
}

export const VIEWER_CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  // MapLibre's worker is a same-origin module (web/vite.config.ts).
  "worker-src 'self'",
  // The API, the basemap archive (one full download on 「簡易地図を読み込む」,
  // never Range requests), and the online tiles of 「詳しい地図」.
  "connect-src 'self' https://cyberjapandata.gsi.go.jp",
  "img-src 'self' data: blob: https://cyberjapandata.gsi.go.jp",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join("; ");

export const viewer = new Hono<AppEnv>();

async function serve(req: Request, env: AssetsEnv, path: string, cache: string): Promise<Response> {
  if (!env.ASSETS) return new Response("viewer not built", { status: 503, headers: { "Content-Type": "text/plain; charset=utf-8" } });
  const url = new URL(req.url);
  url.pathname = path;
  url.search = "";
  const res = await env.ASSETS.fetch(new Request(url, { method: req.method, headers: req.headers }));
  if (res.status === 404) return Response.json({ error: "not found" }, { status: 404 });
  // A response of a binding has immutable headers; copy it.
  const out = new Response(res.body, res);
  out.headers.set("Content-Security-Policy", VIEWER_CSP);
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
