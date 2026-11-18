// Prints the Protomaps "light" style layers (Japanese labels) of one region
// pack as JSON, for the source named "pack": the app copies them per pack
// with that pack's source id (docs/design/outbound-and-offline.md 6.10).
//   { "base": [...], "labels": [...] }
//
//   node tools/regionpacks/layers.mjs <@protomaps/basemaps>/dist/esm/index.js
import { pathToFileURL } from "node:url";

const lib = await import(pathToFileURL(process.argv[2]).href);
const flavor = lib.namedFlavor("light");
process.stdout.write(JSON.stringify({
  // The background is the offline basemap's; a pack draws on top of it.
  base: lib.layers("pack", flavor).filter((l) => l.type !== "background"),
  labels: lib.layers("pack", flavor, { labelsOnly: true, lang: "ja" }),
}));
