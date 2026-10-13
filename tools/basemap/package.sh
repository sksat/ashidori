#!/bin/sh
# Packs the output of build.sh into the archive that the app downloads on
# first run, and pins it in the app.
#
#   tools/basemap/package.sh [<build output dir>] [<dist dir>]
#
# Environment:
#   VERSION   basemap version (default: the Protomaps build of the output,
#             e.g. 20261013). Use a new one for every change of the data.
#   NO_PIN=1  do not update the app's pin
#
# Writes (default tools/basemap/dist/, untracked):
#   ashidori-basemap-<version>.zip    the archive (GitHub Releases / sync server)
#   ashidori-basemap-<version>.json   its descriptor: version, file, bytes, sha256
# and copies the descriptor to app/src/main/assets/basemap-pin.json, the
# version, size and sha256 the app accepts from either source.
#
# Needs python3 (3.9+).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
src=${1:-$here/out}
dist=${2:-$here/dist}

set -- --src "$src" --dist "$dist"
if [ -n "${VERSION:-}" ]; then set -- "$@" --version "$VERSION"; fi
desc=$(python3 -I "$here/package.py" "$@")

if [ "${NO_PIN:-}" != 1 ]; then
  cp "$desc" "$repo/app/src/main/assets/basemap-pin.json"
  echo "pinned in app/src/main/assets/basemap-pin.json" >&2
fi
cat "$desc"
