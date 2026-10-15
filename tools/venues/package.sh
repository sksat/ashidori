#!/bin/sh
# Packs the output of build.sh into the archive that the app downloads, and
# pins it in the app.
#
#   tools/venues/package.sh [<build output dir>] [<dist dir>]
#
# Environment:
#   VERSION   version (default: the OpenStreetMap date of the data, e.g.
#             20261014). Use a new one for every change of the data.
#   NO_PIN=1  do not update the app's pin
#
# Writes (default tools/venues/dist/, untracked):
#   ashidori-venues-<version>.zip    the archive (GitHub Releases, tag venues-<version>)
#   ashidori-venues-<version>.json   its descriptor: version, file, bytes, sha256
# and copies the descriptor to app/src/main/assets/venues-pin.json, the
# version, size and sha256 the app accepts.
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
  cp "$desc" "$repo/app/src/main/assets/venues-pin.json"
  echo "pinned in app/src/main/assets/venues-pin.json" >&2
fi
cat "$desc"
