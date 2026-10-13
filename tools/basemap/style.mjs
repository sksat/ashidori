// Prints the Protomaps "light" style layers (Japanese labels) for the two
// sources of the offline basemap, as JSON:
//   { "world": { "base": [...], "labels": [...] }, "japan": { ... } }
//
//   node tools/basemap/style.mjs <@protomaps/basemaps>/dist/esm/index.js
import { pathToFileURL } from "node:url";

const lib = await import(pathToFileURL(process.argv[2]).href);
const flavor = lib.namedFlavor("light");
const out = {};
for (const source of ["world", "japan"]) {
  out[source] = {
    base: lib.layers(source, flavor),
    labels: lib.layers(source, flavor, { labelsOnly: true, lang: "ja" }),
  };
}
process.stdout.write(JSON.stringify(out));
