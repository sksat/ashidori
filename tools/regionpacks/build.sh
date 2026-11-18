#!/bin/sh
# Builds the region packs (docs/design/outbound-and-offline.md 6.4, 6.5): one
# PMTiles archive per zoom-8 cell (zooms 8-14), the style bundle they are
# drawn with, and the catalog of their sizes and sha256.
#
#   tools/regionpacks/build.sh <work dir> [<dist dir>]
#
# Environment (defaults are the pinned, tested values):
#   BUILD     Protomaps daily build, YYYYMMDD (https://build.protomaps.com/);
#             the builds are removed after a while, so a later rebuild needs a new one
#   VERSION   version of the packs and the catalog (default: BUILD)
#   CELLS     cells to build, e.g. "8-227-100 8-216-110" (default: every cell
#             intersecting tools/basemap/japan.geojson, 136)
#   JOBS      extractions run in parallel (default 4)
#
# Each cell is extracted with `pmtiles extract --bbox` from the remote build
# (HTTP Range: only that cell's tiles are downloaded), with the bbox shrunk by
# 1e-7 degrees so that no tile of a neighbouring cell is included; verify.py
# then checks every pack's header and tiles against its cell, and that no
# tile is in two packs. Output (default tools/regionpacks/dist/, untracked):
# see catalog.py. tools/regionpacks/package.sh pins the catalog in the app.
#
# Needs curl, tar, sha256sum (or shasum), python3 (3.9+) and node (18+).
set -eu

work=${1:?work dir}
here=$(cd "$(dirname "$0")" && pwd)
dist=${2:-$here/dist}

BUILD=${BUILD:-20261117}
VERSION=${VERSION:-$BUILD}
JOBS=${JOBS:-4}

# The same tools and assets as tools/basemap/build.sh.
PMTILES_VERSION=1.31.2
BASEMAPS_VERSION=5.7.2
BASEMAPS_SHA256=2d5d41b29cdd2364f7092ad439bd9d170b4f38cbec8858cefbe84d0f98125ce6
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7
# Style version: the layers (@protomaps/basemaps) and the fonts and sprites (assets commit).
STYLE="$BASEMAPS_VERSION-$(printf %.8s "$ASSETS_COMMIT")"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
fetch() { # url file sha256
  if [ ! -s "$2" ]; then curl -fsSL -o "$2.part" "$1"; mv "$2.part" "$2"; fi
  got=$(sha256 "$2")
  [ "$got" = "$3" ] || { echo "checksum mismatch: $2 ($got)" >&2; rm -f "$2"; exit 1; }
}

mkdir -p "$work/bin" "$work/npm" "$work/cells" "$dist"
case "$(uname -s)_$(uname -m)" in
  Linux_x86_64)  pm_asset=go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz
                 pm_sha=3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f ;;
  Linux_aarch64|Linux_arm64)
                 pm_asset=go-pmtiles_${PMTILES_VERSION}_Linux_arm64.tar.gz
                 pm_sha=f8bd47e7ea866863489cad588fbaf2f31f42e5821f7a03f009b3769f05801cb1 ;;
  Darwin_arm64)  pm_asset=go-pmtiles-${PMTILES_VERSION}_Darwin_arm64.zip
                 pm_sha=40528f7f616fcbf91207cd48c8fc023d213f6d86c0cbf1f748732803d1880f3d ;;
  Darwin_x86_64) pm_asset=go-pmtiles-${PMTILES_VERSION}_Darwin_x86_64.zip
                 pm_sha=1f0dc02eee6c58312dd6c509faee1b5c32f0596568af1bf51f1b034e7a88a65b ;;
  *) echo "unsupported platform for go-pmtiles" >&2; exit 1 ;;
esac
fetch "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/${pm_asset}" "$work/$pm_asset" "$pm_sha"
case "$pm_asset" in
  *.zip) (cd "$work/bin" && unzip -oq "../$pm_asset" pmtiles) ;;
  *)     tar -xzf "$work/$pm_asset" -C "$work/bin" pmtiles ;;
esac
pmtiles="$work/bin/pmtiles"
fetch "https://registry.npmjs.org/@protomaps/basemaps/-/basemaps-${BASEMAPS_VERSION}.tgz" "$work/npm/basemaps.tgz" "$BASEMAPS_SHA256"
tar -xzf "$work/npm/basemaps.tgz" -C "$work/npm"

cells=${CELLS:-$(python3 -I "$here/cells.py" region "$here/../basemap/japan.geojson")}
src="https://build.protomaps.com/${BUILD}.pmtiles"
# A built cell is kept only together with its passing report (<cell>.json).
for cell in $cells; do echo "$cell"; done | xargs -P "$JOBS" -I{} sh -c '
  cell=$1 pm=$2 src=$3 here=$4 dir=$5
  [ -s "$dir/$cell.json" ] && exit 0
  b=$(python3 -I "$here/cells.py" bbox "$cell" 0.0000001)
  t0=$(date +%s)
  "$pm" extract "$src" "$dir/$cell.pmtiles.part" --bbox="$b" --minzoom=8 --maxzoom=14 > "$dir/$cell.log" 2>&1 ||
    { echo "$cell: extract failed (see $dir/$cell.log)" >&2; exit 1; }
  t1=$(date +%s)
  mv "$dir/$cell.pmtiles.part" "$dir/$cell.pmtiles"
  python3 -I "$here/verify.py" pack "$dir/$cell.pmtiles" "$cell" 8 14 > "$dir/$cell.json.part" ||
    { echo "$cell: verification failed: $(cat "$dir/$cell.json.part")" >&2; exit 1; }
  python3 -I -c "import json,sys; d=json.load(open(sys.argv[1])); d[\"seconds\"]=int(sys.argv[2]); json.dump(d,open(sys.argv[1],\"w\"))" "$dir/$cell.json.part" "$((t1 - t0))"
  mv "$dir/$cell.json.part" "$dir/$cell.json"
  echo "$cell $(stat -c %s "$dir/$cell.pmtiles" 2>/dev/null || stat -f %z "$dir/$cell.pmtiles") bytes, $((t1 - t0)) s" >&2
' sh {} "$pmtiles" "$src" "$here" "$work/cells"

# Only the cells asked for this time (a work dir may hold others).
set --
for cell in $cells; do set -- "$@" "$work/cells/$cell.pmtiles"; done
python3 -I "$here/verify.py" borders "$@" > "$work/borders.json"
echo "borders: $(cat "$work/borders.json")" >&2

node "$here/layers.mjs" "$work/npm/package/dist/esm/index.js" > "$work/layers.json"
style_zip=$(python3 -I "$here/style.py" --layers "$work/layers.json" --work "$work" --out "$work" \
  --style "$STYLE" --assets-commit "$ASSETS_COMMIT" \
  --tool "go-pmtiles $PMTILES_VERSION" --tool "@protomaps/basemaps $BASEMAPS_VERSION")

# The catalog lists exactly the cells of this build.
rm -rf "$work/catalog-cells"
mkdir -p "$work/catalog-cells"
for cell in $cells; do
  ln -f "$work/cells/$cell.pmtiles" "$work/catalog-cells/$cell.pmtiles" 2>/dev/null || cp "$work/cells/$cell.pmtiles" "$work/catalog-cells/"
  cp "$work/cells/$cell.json" "$work/catalog-cells/"
done
python3 -I "$here/catalog.py" --cells "$work/catalog-cells" --style-zip "$style_zip" --style "$STYLE" \
  --build "$BUILD" --version "$VERSION" --out "$dist" \
  --tool "go-pmtiles $PMTILES_VERSION" --tool "@protomaps/basemaps $BASEMAPS_VERSION" \
  --tool "basemaps-assets $ASSETS_COMMIT"
