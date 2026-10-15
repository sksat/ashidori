#!/bin/sh
# Builds the offline venue index (施設データ) from OpenStreetMap: named
# places one checks in to in Japan, for the check-in sheet without a network.
#
#   tools/venues/build.sh <work dir> [<output dir>]
#
# Environment (defaults are the pinned, tested values):
#   EXTRACT         Geofabrik extract of Japan, japan-YYMMDD (a dated file:
#                   https://download.geofabrik.de/asia/japan.html lists them)
#   EXTRACT_SHA256  its sha256
#   OSM_DATE        OpenStreetMap time of the extract (its header's
#                   osmosis_replication_timestamp), for the manifest
#
# Needs curl, sha256sum (or shasum), python3 (3.9+) and either uv or a
# python3 with venv and pip: pyosmium (pip package "osmium") is installed
# into <work dir>/venv at the pinned version. About 3 GB of disk in the work
# dir and 2.5 GB of memory; about 5 minutes.
#
# Output (default tools/venues/out/, untracked):
#   venues.aven  manifest.json  ATTRIBUTION
# The app does not bundle it: tools/venues/package.sh packs it into the
# archive that the app downloads and pins in the app.
set -eu

work=${1:?work dir}
here=$(cd "$(dirname "$0")" && pwd)
out=${2:-$here/out}

EXTRACT=${EXTRACT:-japan-261014}
EXTRACT_SHA256=${EXTRACT_SHA256:-47897333ff734752efeb12ca1fad46a327e72d305bf6ecc249b8fd068634a8e4}
OSM_DATE=${OSM_DATE:-2026-10-14T20:21:12Z}
# pyosmium (BSD-2-Clause) with libosmium (BSL-1.0), https://osmcode.org/pyosmium/
PYOSMIUM_VERSION=4.3.1

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

mkdir -p "$work/src" "$out"
pbf="$work/src/$EXTRACT.osm.pbf"
if [ ! -s "$pbf" ]; then
  curl -fSL --retry 3 -C - -o "$pbf.part" "https://download.geofabrik.de/asia/$EXTRACT.osm.pbf"
  mv "$pbf.part" "$pbf"
fi
got=$(sha256 "$pbf")
[ "$got" = "$EXTRACT_SHA256" ] || { echo "checksum mismatch: $pbf ($got)" >&2; exit 1; }

py="$work/venv/bin/python"
osmium_version() { "$py" -I -c 'import importlib.metadata as m; print(m.version("osmium"))' 2>/dev/null || true; }
make_venv() {
  rm -rf "$work/venv"
  if command -v uv >/dev/null; then
    uv venv -q --python 3.13 "$work/venv"
    uv pip install -q --python "$py" "osmium==$PYOSMIUM_VERSION"
  else
    python3 -m venv "$work/venv"
    "$py" -m pip install -q "osmium==$PYOSMIUM_VERSION"
  fi
}
# Every run: the venv must hold exactly the pinned pyosmium (else it is rebuilt).
[ -x "$py" ] && [ "$(osmium_version)" = "$PYOSMIUM_VERSION" ] || make_venv
used=$(osmium_version)
[ "$used" = "$PYOSMIUM_VERSION" ] || { echo "pyosmium $used, expected $PYOSMIUM_VERSION" >&2; exit 1; }

# A fresh extraction per extract (selected.osm.pbf and venues.tsv are cached in it).
"$py" -I "$here/extract.py" "$pbf" "$work/$EXTRACT"
python3 -I "$here/build_index.py" "$work/$EXTRACT/venues.tsv" "$out" \
  --source-file "$EXTRACT.osm.pbf" --source-sha256 "$EXTRACT_SHA256" --source-date "$OSM_DATE" \
  --tool "pyosmium $used" --tool "Python $("$py" -I -c 'import platform; print(platform.python_version())')"

ls -l "$out"
