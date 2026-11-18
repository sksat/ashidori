#!/bin/sh
# Pins the catalog written by build.sh in the app: its version, size and
# sha256 go to app/src/main/assets/regions-pin.json. The app downloads the
# catalog and accepts only these bytes; the catalog in turn pins every pack
# and the style bundle (docs/design/outbound-and-offline.md 6.5).
#
#   tools/regionpacks/package.sh [<version>] [<dist dir>]
#
# <version> defaults to the only catalog in <dist> (default tools/regionpacks/dist/).
# NO_PIN=1 prints the pin without writing it.
#
# Needs python3 (3.9+).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
dist=${2:-$here/dist}
version=${1:-}
if [ -z "$version" ]; then
  set -- "$dist"/ashidori-regions-*.json
  [ $# -eq 1 ] && [ -f "$1" ] || { echo "give the version: catalogs in $dist: $*" >&2; exit 1; }
  version=$(basename "$1" .json)
  version=${version#ashidori-regions-}
fi
catalog="$dist/ashidori-regions-$version.json"
[ -f "$catalog" ] || { echo "no $catalog (run tools/regionpacks/build.sh)" >&2; exit 1; }

pin=$(python3 -I - "$catalog" "$version" <<'EOF'
import hashlib, json, os, sys
path, version = sys.argv[1], sys.argv[2]
with open(path, "rb") as f:
    data = f.read()
cat = json.loads(data)
assert cat["format"] == "ashidori regions catalog v1" and cat["version"] == version, "not this version's catalog"
print(json.dumps({
    "format": "ashidori regions pin v1",
    "version": version,
    "file": os.path.basename(path),
    "bytes": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "build": cat["build"],
    "cells": len(cat["cells"]),
    "cellBytes": sum(c["map"]["bytes"] for c in cat["cells"]),
}, indent=2, ensure_ascii=False))
EOF
)
if [ "${NO_PIN:-}" != 1 ]; then
  printf '%s\n' "$pin" > "$repo/app/src/main/assets/regions-pin.json"
  echo "pinned in app/src/main/assets/regions-pin.json" >&2
fi
printf '%s\n' "$pin"
