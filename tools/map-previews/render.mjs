// Renders the base-map picker's samples: one WebP per online map
// (app/src/main/scala/net/sksat/ashidori/ui/MapStyles.scala), of one fixed
// area unrelated to any user (central Tokyo around Tokyo Station), so the
// picker can show what each map looks like without sending the area being
// viewed.
//
//   cd tools/map-previews && npm install && npx playwright-core install chromium-headless-shell
//   node render.mjs
//
// Writes app/src/main/res/drawable-nodpi/map_preview_<key>.webp. The styles
// are the app's: the OpenFreeMap style URLs, and the GSI raster tiles with
// the same tile size and max zoom. Sources and credits: tools/ATTRIBUTION.
import { chromium } from "playwright-core";
import { createRequire } from "node:module";
import { createServer } from "node:http";
import { readFileSync, writeFileSync, mkdirSync, statSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "../../app/src/main/res/drawable-nodpi");
const require = createRequire(import.meta.url);
const maplibreDir = dirname(require.resolve("maplibre-gl/package.json"));

// Fixed sample area and size (about the picker's card on a 1080 px screen).
const center = [139.7671, 35.6812];
const zoom = 14;
const [width, height] = [400, 280];
const quality = 0.4;

const gsi = (layer, ext, maxzoom = 18) => ({
  version: 8,
  sources: {
    base: {
      type: "raster",
      tiles: [`https://cyberjapandata.gsi.go.jp/xyz/${layer}/{z}/{x}/{y}.${ext}`],
      tileSize: 256,
      maxzoom,
    },
  },
  layers: [{ id: "base", type: "raster", source: "base" }],
});

const maps = [
  ["ofm_liberty", "https://tiles.openfreemap.org/styles/liberty"],
  ["ofm_positron", "https://tiles.openfreemap.org/styles/positron"],
  ["gsi_std", gsi("std", "png")],
  ["gsi_pale", gsi("pale", "png")],
  ["gsi_photo", gsi("seamlessphoto", "jpg")],
  // Wider, so the sample shows some relief (the plateau and the lowlands).
  ["gsi_relief", gsi("relief", "png", 15), 11],
];

// MapLibre GL JS is ES modules only: serve its dist directory and a page
// that loads it from 127.0.0.1.
const page = (w, h) => `<!doctype html><html><head><link rel="stylesheet" href="/maplibre-gl.css">
<script type="module">import * as maplibregl from "/maplibre-gl.mjs"; window.maplibregl = maplibregl;</script>
</head><body style="margin:0"><div id="map" style="width:${w}px;height:${h}px"></div></body></html>`;
const server = createServer((req, res) => {
  const name = basename(new URL(req.url, "http://x").pathname);
  if (name === "" || name === "index.html") {
    res.writeHead(200, { "content-type": "text/html" }).end(page(width, height));
    return;
  }
  try {
    const body = readFileSync(join(maplibreDir, "dist", name));
    const type = name.endsWith(".css") ? "text/css" : "text/javascript";
    res.writeHead(200, { "content-type": type }).end(body);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;

// Every image is rendered first; files are written only if all succeed, so
// a failed tile, style, sprite or glyph never leaves a partial sample behind.
const results = [];
const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
try {
  for (const [name, style, z = zoom] of maps) {
    const tab = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    const failures = [];
    tab.on("console", (m) => console.error(`[${name}] ${m.text()}`));
    tab.on("requestfailed", (r) => failures.push(`${r.url()}: ${r.failure()?.errorText}`));
    tab.on("response", (r) => {
      if (r.status() >= 400) failures.push(`${r.url()}: HTTP ${r.status()}`);
    });
    await tab.goto(`${origin}/index.html`);
    await tab.waitForFunction(() => window.maplibregl !== undefined);
    const dataUrl = await tab.evaluate(
      ({ style, center, zoom, quality }) =>
        new Promise((resolve, reject) => {
          const map = new maplibregl.Map({
            container: "map",
            style,
            center,
            zoom,
            interactive: false,
            attributionControl: false,
            fadeDuration: 0,
            canvasContextAttributes: { preserveDrawingBuffer: true },
          });
          // Any error (a tile, the style, a sprite, glyphs) fails the image.
          map.on("error", (e) => reject(new Error(String(e.error?.message ?? e.error ?? e))));
          map.once("idle", () => resolve(map.getCanvas().toDataURL("image/webp", quality)));
          setTimeout(() => reject(new Error("timed out")), 60_000);
        }),
      { style, center, zoom: z, quality },
    );
    if (failures.length > 0) throw new Error(`${name}: failed requests\n${failures.join("\n")}`);
    if (!dataUrl.startsWith("data:image/webp")) throw new Error(`${name}: not WebP`);
    results.push([name, Buffer.from(dataUrl.split(",")[1], "base64")]);
    await tab.close();
  }
} finally {
  await browser.close();
  server.close();
}

mkdirSync(out, { recursive: true });
for (const [name, bytes] of results) {
  const file = join(out, `map_preview_${name}.webp`);
  writeFileSync(file, bytes);
  console.log(`${file}: ${(statSync(file).size / 1024).toFixed(1)} KB`);
}
