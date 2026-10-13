#!/bin/sh
# Builds the offline coarse basemap used when 「位置情報を外部サービスに送らない」
# is on: vector tiles for Japan and a low-zoom world, a MapLibre style, glyphs
# for the non-CJK characters in the labels, and sprites.
#
#   tools/basemap/build.sh <work dir> [<output dir>]
#
# Environment (defaults are the pinned, tested values):
#   BUILD          Protomaps daily build, YYYYMMDD (https://build.protomaps.com/)
#   JAPAN_MAXZOOM  max zoom of the Japan extract (z6..)        default 10
#   WORLD_MAXZOOM  max zoom of the world extract (z0..)        default 5
#
# Needs curl, tar, sha256sum (or shasum), python3 (3.9+) and node (18+).
# Downloads only from the pinned URLs below and checks their checksums.
#
# Output (default app/src/main/assets/basemap/, untracked):
#   japan.pmtiles  world.pmtiles  style.json  fonts/<stack>/<range>.pbf
#   sprites/light{,@2x}.{json,png}  manifest.json  ATTRIBUTION  OFL.txt
set -eu

work=${1:?work dir}
out=${2:-app/src/main/assets/basemap}
here=$(cd "$(dirname "$0")" && pwd)

BUILD=${BUILD:-20261013}
JAPAN_MAXZOOM=${JAPAN_MAXZOOM:-10}
WORLD_MAXZOOM=${WORLD_MAXZOOM:-5}

# go-pmtiles (BSD-3-Clause), https://github.com/protomaps/go-pmtiles
PMTILES_VERSION=1.31.2
# @protomaps/basemaps (BSD-3-Clause): style layers for the Protomaps schema.
BASEMAPS_VERSION=5.7.2
BASEMAPS_SHA256=2d5d41b29cdd2364f7092ad439bd9d170b4f38cbec8858cefbe84d0f98125ce6
# protomaps/basemaps-assets: Noto Sans glyphs (OFL) and sprites (MIT), by commit.
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
fetch() { # url file sha256
  if [ ! -s "$2" ]; then curl -fsSL -o "$2.part" "$1"; mv "$2.part" "$2"; fi
  got=$(sha256 "$2")
  [ "$got" = "$3" ] || { echo "checksum mismatch: $2 ($got)" >&2; rm -f "$2"; exit 1; }
}

mkdir -p "$work/bin" "$work/npm" "$out"

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

src="https://build.protomaps.com/${BUILD}.pmtiles"
# Japan: from z6 (below that the world archive draws it), clipped to the
# islands (japan.geojson) rather than a bbox to skip most of the neighbours.
[ -s "$work/japan-z$JAPAN_MAXZOOM.pmtiles" ] ||
  "$pmtiles" extract "$src" "$work/japan-z$JAPAN_MAXZOOM.pmtiles" \
    --region="$here/japan.geojson" --minzoom=6 --maxzoom="$JAPAN_MAXZOOM"
[ -s "$work/world-z$WORLD_MAXZOOM.pmtiles" ] ||
  "$pmtiles" extract "$src" "$work/world-z$WORLD_MAXZOOM.pmtiles" --maxzoom="$WORLD_MAXZOOM"
cp "$work/japan-z$JAPAN_MAXZOOM.pmtiles" "$out/japan.pmtiles"
cp "$work/world-z$WORLD_MAXZOOM.pmtiles" "$out/world.pmtiles"

node "$here/style.mjs" "$work/npm/package/dist/esm/index.js" > "$work/layers.json"

python3 -I "$here/assemble.py" \
  --layers "$work/layers.json" --out "$out" --work "$work" \
  --assets-commit "$ASSETS_COMMIT" --build "$BUILD" \
  --japan-maxzoom "$JAPAN_MAXZOOM" --world-maxzoom "$WORLD_MAXZOOM" \
  --tool "go-pmtiles $PMTILES_VERSION" --tool "@protomaps/basemaps $BASEMAPS_VERSION"

du -sh "$out"
