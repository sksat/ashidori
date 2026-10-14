// The viewer, built to static files (dist/) that the Worker serves at `/`
// through Workers Static Assets (wrangler.jsonc "assets", src/viewer.ts).
//
// - No inline scripts or styles and no data: URIs (assetsInlineLimit 0), so
//   the CSP of src/viewer.ts needs no 'unsafe-inline' and no nonce.
// - MapLibre GL JS is not bundled: its ES module build and its worker are
//   copied as they are to /vendor/maplibre-gl-<version>/ and loaded only
//   when a map is shown. Its worker is then a same-origin module worker
//   (CSP worker-src 'self', no blob: URLs).

import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

const require = createRequire(import.meta.url);
const maplibreDir = dirname(require.resolve("maplibre-gl/package.json"));
const maplibreVersion = (JSON.parse(readFileSync(join(maplibreDir, "package.json"), "utf8")) as { version: string }).version;
const MAPLIBRE_PATH = `/vendor/maplibre-gl-${maplibreVersion}/`;
const MAPLIBRE_FILES = ["maplibre-gl.mjs", "maplibre-gl-shared.mjs", "maplibre-gl-worker.mjs", "maplibre-gl.css"];

function maplibreVendor(): Plugin {
  const contentType = (f: string) => (f.endsWith(".css") ? "text/css" : "text/javascript");
  return {
    name: "ashidori-maplibre-vendor",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? "";
        if (!url.startsWith(MAPLIBRE_PATH)) return next();
        const f = url.slice(MAPLIBRE_PATH.length).split("?")[0]!;
        if (!MAPLIBRE_FILES.includes(f)) return next();
        res.setHeader("Content-Type", contentType(f));
        res.end(readFileSync(join(maplibreDir, "dist", f)));
      });
    },
    generateBundle() {
      for (const f of MAPLIBRE_FILES)
        this.emitFile({ type: "asset", fileName: `${MAPLIBRE_PATH.slice(1)}${f}`, source: readFileSync(join(maplibreDir, "dist", f)) });
      this.emitFile({ type: "asset", fileName: `${MAPLIBRE_PATH.slice(1)}LICENSE.txt`, source: readFileSync(join(maplibreDir, "LICENSE.txt")) });
    },
  };
}

// Dev: `pnpm dev` here, with `wrangler dev` (port 8787) for the API.
const API = process.env.ASHIDORI_API ?? "http://localhost:8787";

export default defineConfig({
  root: import.meta.dirname,
  base: "/",
  define: { __MAPLIBRE_PATH__: JSON.stringify(MAPLIBRE_PATH) },
  plugins: [maplibreVendor()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "assets",
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    sourcemap: false,
    target: "es2022",
  },
  server: {
    proxy: { "/v1": API, "/basemap": API },
  },
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
    // Day boundaries use the local time zone; the fixtures are of Tokyo.
    env: { TZ: "Asia/Tokyo" },
  },
});
