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
import { QRCODE_GENERATOR_JS } from "../src/vendor/qrcode-generator.ts";

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

// The pairing QR code (src/ui/qr.ts) is drawn with the QR code generator
// the server's pages inline (server/src/vendor/qrcode-generator.ts, verbatim
// and hash-checked there), bundled as an ES module into /assets/ like the
// rest of the viewer: no inline script, no eval, no change to the CSP.
const QRCODE_ID = "virtual:qrcode-generator";

function qrcodeVendor(): Plugin {
  return {
    name: "ashidori-qrcode-generator",
    resolveId: (id) => (id === QRCODE_ID ? `\0${QRCODE_ID}` : null),
    // In a function whose `define`, `exports` and `module` are undefined: the
    // file's UMD footer then exports nothing of its own (a module runner may
    // have an `exports` object).
    load: (id) =>
      id === `\0${QRCODE_ID}`
        ? `const qrcode = (function (define, exports, module) {\n${QRCODE_GENERATOR_JS}\nreturn qrcode;\n})(undefined, undefined, undefined);\nexport default qrcode;\n`
        : null,
  };
}

// The offline basemap archive the viewer may download (src/map/basemap.ts):
// the app's own pin, so that both verify the same size and sha256.
function basemapPin(): { version: string; file: string; bytes: number; sha256: string } {
  const p = JSON.parse(readFileSync(join(import.meta.dirname, "../../app/src/main/assets/basemap-pin.json"), "utf8")) as Record<string, unknown>;
  const { version, file, bytes, sha256 } = p;
  if (
    p.format !== "ashidori basemap archive v1" ||
    typeof version !== "string" ||
    !/^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$/.test(version) ||
    file !== `ashidori-basemap-${version}.zip` ||
    typeof bytes !== "number" ||
    !Number.isSafeInteger(bytes) ||
    bytes <= 0 ||
    typeof sha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(sha256)
  )
    throw new Error("app/src/main/assets/basemap-pin.json is malformed");
  return { version, file, bytes, sha256 };
}

// Dev: `pnpm dev` here, with `wrangler dev` (port 8787) for the API.
const API = process.env.ASHIDORI_API ?? "http://localhost:8787";

export default defineConfig({
  root: import.meta.dirname,
  base: "/",
  define: { __MAPLIBRE_PATH__: JSON.stringify(MAPLIBRE_PATH), __BASEMAP_PIN__: JSON.stringify(basemapPin()) },
  plugins: [maplibreVendor(), qrcodeVendor()],
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
