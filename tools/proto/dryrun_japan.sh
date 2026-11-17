#!/bin/sh
# Dry-runs `pmtiles extract` of the whole of Japan (tools/basemap/japan.geojson,
# as tools/basemap/build.sh does) from z6 up to each max zoom given.
#
#   tools/proto/dryrun_japan.sh <pmtiles binary> <build YYYYMMDD> <maxzoom>...
#
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
set -eu
pm=${1:?pmtiles binary}
build=${2:?build}
shift 2
here=$(cd "$(dirname "$0")" && pwd)
src="https://build.protomaps.com/${build}.pmtiles"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf 'minzoom\tmaxzoom\tregion_tiles\tentries\tarchive\n'
for z in "$@"; do
  log=$("$pm" extract "$src" "$tmp/x.pmtiles" --region="$here/../basemap/japan.geojson" --minzoom=6 --maxzoom="$z" --dry-run 2>&1) || {
    printf '6\t%s\tFAILED\n' "$z"; continue; }
  tiles=$(printf '%s\n' "$log" | sed -n 's/.*Region tiles \([0-9]*\), result tile entries \([0-9]*\).*/\1\t\2/p')
  size=$(printf '%s\n' "$log" | sed -n 's/.*for an archive size of \(.*\)$/\1/p')
  printf '6\t%s\t%s\t%s\n' "$z" "$tiles" "$size"
done
