#!/bin/sh
# Extracts zoom-8 cells (z8..15) from the remote Protomaps daily build with
# `pmtiles extract` (HTTP Range; only the cell's tiles are downloaded) and
# inspects each pack with pmtiles_list.py.
#
#   tools/proto/extract_cells.sh <pmtiles binary> <build YYYYMMDD> <work dir> <inset deg> <name>=<cell>...
#
# inset 0 passes the exact cell bbox; a small positive inset (1e-7 degrees)
# shrinks it so that no tile of a neighbouring cell is included.
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
set -eu
pm=${1:?pmtiles binary}
build=${2:?build}
work=${3:?work dir}
inset=${4:?inset}
shift 4
here=$(cd "$(dirname "$0")" && pwd)
src="https://build.protomaps.com/${build}.pmtiles"
mkdir -p "$work"
for arg in "$@"; do
  name=${arg%%=*}
  cell=${arg#*=}
  b=$(python3 -I "$here/cells.py" bbox "$cell" "$inset")
  f="$work/$name-$cell-i$inset.pmtiles"
  [ -s "$f" ] || "$pm" extract "$src" "$f" --bbox="$b" --minzoom=8 --maxzoom=15 > "$f.log" 2>&1
  python3 -I "$here/pmtiles_list.py" "$f" "$cell" > "$f.json"
  python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(sys.argv[2], sys.argv[3], d["bytes"], d["addressed_tiles"], d["contents"], sum(sum(v.values()) for v in d.get("outside_cell", {}).values()))' "$f.json" "$name" "$cell"
done
