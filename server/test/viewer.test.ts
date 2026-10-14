import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { viewerCsp } from "../src/viewer";
import { BASE, call } from "./helpers";

/** The viewer's CSP, exactly: scripts only from its own directories, nothing of another origin. */
const CSP =
  "default-src 'none'; " +
  `script-src ${BASE}/assets/ ${BASE}/vendor/; ` +
  "style-src 'self'; " +
  `worker-src ${BASE}/vendor/; ` +
  "connect-src 'self'; " +
  "img-src 'self' data: blob:; " +
  "frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

/** The Static Assets binding of wrangler.jsonc (web/dist, built before the tests). */
const assets = (env as unknown as { ASSETS?: Fetcher }).ASSETS;

/** Stands in for the binding: serves a page and one hashed file. */
const fakeAssets: Fetcher = {
  async fetch(input: RequestInfo | URL) {
    const path = new URL(input instanceof Request ? input.url : String(input)).pathname;
    if (path === "/") return new Response('<!doctype html><script type="module" src="/assets/index-abc.js"></script>', { headers: { "Content-Type": "text/html" } });
    if (path === "/assets/index-abc.js") return new Response("export {}", { headers: { "Content-Type": "text/javascript" } });
    if (path === "/assets/moved.js") return new Response(null, { status: 307, headers: { Location: "/admin/app.js" } });
    return new Response("not found", { status: 404 });
  },
  connect() {
    throw new Error("not used");
  },
} as unknown as Fetcher;

describe("web viewer at /", () => {
  it("serves the page with the viewer's CSP and security headers", async () => {
    const res = await call("/", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toMatch(/text\/html/);
    const csp = res.headers.get("Content-Security-Policy")!;
    expect(csp).toBe(CSP);
    expect(csp).toBe(viewerCsp(BASE));
    // No 'self' for scripts (it would allow /admin and /pair), no other origin.
    expect(/script-src[^;]*'self'/.test(csp)).toBe(false);
    expect(csp).not.toMatch(/https:\/\/(?!ashidori\.example\/)/);
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("unsafe-eval");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(res.headers.get("X-Frame-Options")).toBe("DENY");
    expect(res.headers.get("Cache-Control")).toBe("no-cache");
  });

  it("serves hashed files as immutable, and 404s what is not there", async () => {
    const js = await call("/assets/index-abc.js", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(js.status).toBe(200);
    expect(js.headers.get("Cache-Control")).toMatch(/immutable/);
    expect(js.headers.get("Content-Security-Policy")).toBe(CSP);
    const missing = await call("/assets/nope.js", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(missing.status).toBe(404);
    // A redirect of the binding is not passed on (scripts are allowed by path).
    const moved = await call("/assets/moved.js", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(moved.status).toBe(404);
    expect(moved.headers.get("Location")).toBeNull();
  });

  it("leaves the API, the admin page, /pair and /basemap to their routes", async () => {
    const health = await call("/v1/health", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(await health.json()).toEqual({ ok: true });
    expect(health.headers.get("Content-Security-Policy")).toMatch(/default-src 'none'/);
    const pair = await call("/pair", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(pair.headers.get("Content-Security-Policy")).toMatch(/nonce-/);
    const admin = await call("/admin/users", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect([401, 403]).toContain(admin.status);
    const basemap = await call("/basemap/1/nope.zip", {}, { ASSETS: fakeAssets } as Partial<Cloudflare.Env>);
    expect(basemap.status).toBe(404);
    expect(basemap.headers.get("Content-Security-Policy")).not.toBe(CSP);
  });

  it.runIf(assets)("serves the built app from the Static Assets binding", async () => {
    const res = await call("/");
    expect(res.status, "web/dist is missing: run `pnpm --dir web build` first").toBe(200);
    const html = await res.text();
    expect(html).toContain('<html lang="ja">');
    expect(html).toMatch(/<script type="module" crossorigin src="\/assets\/index-[\w-]+\.js"><\/script>/);
    // No inline script: every <script> has a src.
    expect(html.match(/<script(?![^>]*\bsrc=)[^>]*>/g)).toBeNull();
    expect(res.headers.get("Content-Security-Policy")).toBe(CSP);
    const src = /src="(\/assets\/index-[\w-]+\.js)"/.exec(html)![1]!;
    const js = await call(src);
    expect(js.status).toBe(200);
    expect(js.headers.get("Content-Type")).toMatch(/javascript/);
  });
});
