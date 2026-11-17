#!/bin/sh
# Dry-runs `pmtiles extract` (z8..15, or z8..maxzoom) for every zoom-8 cell that intersects
# tools/basemap/japan.geojson, against the remote Protomaps daily build, and
# prints one TSV line per cell. Only directories are read (HTTP Range); no
# tile data is downloaded. go-pmtiles prints sizes rounded to 3 digits (SI).
#
#   tools/proto/dryrun_cells.sh <pmtiles binary> <build YYYYMMDD> [inset deg] [maxzoom]
#
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
set -eu
pm=${1:?pmtiles binary}
build=${2:?build}
inset=${3:-0.0000001}
maxzoom=${4:-15}
here=$(cd "$(dirname "$0")" && pwd)
src="https://build.protomaps.com/${build}.pmtiles"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

printf 'cell\tregion_tiles\tentries\tarchive\n'
python3 -I "$here/cells.py" region "$here/../basemap/japan.geojson" | while read -r cell _; do
  b=$(python3 -I "$here/cells.py" bbox "$cell" "$inset")
  log=$("$pm" extract "$src" "$tmp/x.pmtiles" --bbox="$b" --minzoom=8 --maxzoom="$maxzoom" --dry-run 2>&1) || {
    printf '%s\tFAILED\t\t\n' "$cell"; continue; }
  tiles=$(printf '%s\n' "$log" | sed -n 's/.*Region tiles \([0-9]*\), result tile entries \([0-9]*\).*/\1\t\2/p')
  size=$(printf '%s\n' "$log" | sed -n 's/.*for an archive size of \(.*\)$/\1/p')
  printf '%s\t%s\t%s\n' "$cell" "$tiles" "$size"
done
